package br.edu.utfpr.slicer.api;

import br.edu.utfpr.slicer.config.AppConfig;
import br.edu.utfpr.slicer.index.bloom.BF;
import br.edu.utfpr.slicer.index.bloom.LongFastBloomFilter;
import br.edu.utfpr.slicer.utils.UtilsCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class QueryController {
    private static final Logger log = LoggerFactory.getLogger(QueryController.class);
    private final AppConfig config;
    private S3Client s3Client;
    private final ObjectMapper mapper = new ObjectMapper();

    private record BlockSearchResult(List<String> events, long totalRealHits, boolean limitReached) {}

    public record QueryRequest(
        List<String> devices,
        Map<String, String> fields, 
        String rawLuceneQuery,      
        String fromIsoDate,
        String toIsoDate
    ) {}

    public QueryController() {
        this.config = AppConfig.load();
        if (isAwsEnabled()) {
            try { this.s3Client = S3Client.builder().region(Region.SA_EAST_1).build(); } 
            catch (Exception e) { log.warn("AWS S3 configurado, mas cliente não iniciou."); }
        }
    }

    public void executeQuery(Context ctx) {
        if (!UtilsCrypto.isInitialized()) {
            ctx.status(403).json(Map.of("status", "error", "message", "O servidor está selado."));
            return;
        }

        try {
            QueryRequest req = ctx.bodyAsClass(QueryRequest.class);
            
            // PREPARAÇÃO PARA STREAMING (NDJSON)
            ctx.res().setContentType("application/x-ndjson");
            ctx.res().setCharacterEncoding("UTF-8");
            
            Map<String, Long> hitsPerDevice = new HashMap<>(); 
            int maxHits = (config.slicer() != null && config.slicer().search() != null) ? config.slicer().search().maxHitsPerBlock() : 50_000;

            final boolean[] globalLimitReached = {false};
            final long[] globalTotalHits = {0}; 
            final long[] s3BytesDownloaded = {0};
            long totalEventsExtracted = 0;

            long fromEpoch = LocalDateTime.parse(req.fromIsoDate()).toInstant(ZoneOffset.UTC).toEpochMilli();
            long toEpoch = LocalDateTime.parse(req.toIsoDate()).toInstant(ZoneOffset.UTC).toEpochMilli();
            boolean isAdvancedQuery = req.rawLuceneQuery() != null && !req.rawLuceneQuery().isBlank();

            for (String device : req.devices()) {
                long currentDeviceHits = 0;
                List<String> candidateBlocks = discoverCandidateBlocks(device, fromEpoch, toEpoch);

                for (String blockName : candidateBlocks) {
                    if (blockName.equals("newblock")) continue;

                    if (passesBloomFilter(device, blockName, req.fields(), s3BytesDownloaded, isAdvancedQuery)) {
                        BlockSearchResult blockResult = searchInLucene(device, blockName, req, maxHits, s3BytesDownloaded);
                        
                        globalTotalHits[0] += blockResult.totalRealHits();
                        currentDeviceHits += blockResult.totalRealHits();
                        if (blockResult.limitReached()) globalLimitReached[0] = true;
                        
                        // STREAMING: Envia um chunk (pedaço) assim que o bloco é lido!
                        if (!blockResult.events().isEmpty()) {
                            totalEventsExtracted += blockResult.events().size();
                            Map<String, Object> chunk = Map.of(
                                "type", "chunk",
                                "device", device,
                                "block", blockName,
                                "data", blockResult.events()
                            );
                            ctx.res().getWriter().write(mapper.writeValueAsString(chunk) + "\n");
                            ctx.res().getWriter().flush(); // Força o envio imediato pela rede
                        }
                    }
                }
                if (currentDeviceHits > 0) hitsPerDevice.put(device, currentDeviceHits);
            }

            // STREAMING: Envia o pacote final de resumo (Summary)
            Map<String, Object> summary = Map.of(
                "type", "summary",
                "eventsFound", totalEventsExtracted,
                "totalRealHits", globalTotalHits[0],
                "hitsPerDevice", hitsPerDevice,
                "s3BytesDownloaded", s3BytesDownloaded[0],
                "partialResults", globalLimitReached[0]
            );
            ctx.res().getWriter().write(mapper.writeValueAsString(summary) + "\n");
            ctx.res().getWriter().flush();

        } catch (Exception e) {
            log.error("Erro ao executar query", e);
            try {
                Map<String, Object> err = Map.of("type", "error", "message", e.getMessage());
                ctx.res().getWriter().write(mapper.writeValueAsString(err) + "\n");
                ctx.res().getWriter().flush();
            } catch (Exception ex) {}
        }
    }

    private Path resolveOrFetchFile(String device, String blockName, String fileName, long[] s3BytesCounter) throws Exception {
        Path ingestPath = Path.of(config.slicer().storage().ingestCacheDir(), device, "index", blockName, fileName);
        if (Files.exists(ingestPath)) return ingestPath;
        Path ingestEncPath = Path.of(config.slicer().storage().ingestCacheDir(), device, "index", blockName, fileName + ".enc");
        if (Files.exists(ingestEncPath)) return ingestEncPath;
        Path queryPath = Path.of(config.slicer().storage().queryCacheDir(), device, blockName, fileName);
        if (Files.exists(queryPath)) return queryPath;

        Path queryCacheBlockDir = Path.of(config.slicer().storage().queryCacheDir(), device, blockName);
        Files.createDirectories(queryCacheBlockDir);
        Path downloadedEncFile = queryCacheBlockDir.resolve(fileName + ".enc");
        boolean downloaded = false;

        if (isCharonEnabled()) {
            Path charonFile = Path.of(config.slicer().storage().cloudDropDir(), device, blockName, fileName + ".enc");
            if (Files.exists(charonFile)) {
                Files.copy(charonFile, downloadedEncFile, StandardCopyOption.REPLACE_EXISTING);
                downloaded = true;
            }
        }
        if (!downloaded && isAwsEnabled() && s3Client != null) {
            String s3Key = String.format("%s/%s/%s.enc", device, blockName, fileName);
            try {
                s3Client.getObject(GetObjectRequest.builder().bucket(config.slicer().aws().bucketName()).key(s3Key).build(), downloadedEncFile);
                downloaded = true;
                s3BytesCounter[0] += Files.size(downloadedEncFile);
            } catch (Exception e) {}
        }
        if (!downloaded) return null; 

        Path decryptedFile = queryCacheBlockDir.resolve(fileName);
        UtilsCrypto.decryptFile(downloadedEncFile, decryptedFile);
        Files.deleteIfExists(downloadedEncFile);
        return decryptedFile;
    }

    private boolean passesBloomFilter(String device, String blockName, Map<String, String> requiredFields, long[] s3BytesCounter, boolean isAdvancedQuery) throws Exception {
        if (isAdvancedQuery) return true;
        if (requiredFields == null || requiredFields.isEmpty()) return true;

        Path bfFile = resolveOrFetchFile(device, blockName, blockName + ".bf", s3BytesCounter);
        if (bfFile == null || !Files.exists(bfFile)) return true; 

        LongFastBloomFilter bloom = BF.retrieveBloom(bfFile.toFile());
        if (bloom == null) return true;

        for (Map.Entry<String, String> entry : requiredFields.entrySet()) {
            if (entry.getKey().equals("all")) continue; 
            if (!bloom.contains((entry.getKey() + "=" + entry.getValue()).getBytes())) return false; 
        }
        return true;
    }

    private BlockSearchResult searchInLucene(String device, String blockName, QueryRequest req, int maxHits, long[] s3BytesCounter) throws Exception {
        List<String> results = new ArrayList<>();
        Path luceneArchive = resolveOrFetchFile(device, blockName, blockName + "_lucene.tar.xz", s3BytesCounter);

        if (luceneArchive == null || !Files.exists(luceneArchive)) return new BlockSearchResult(results, 0, false);

        Path tempExtractionDir = null;
        boolean limitReached = false;
        long totalRealHits = 0;

        try {
            Path baseTempDir = Path.of(config.slicer().storage().queryCacheDir(), "temp_extract");
            Files.createDirectories(baseTempDir);
            tempExtractionDir = Files.createTempDirectory(baseTempDir, "slicer_search_" + blockName + "_");
            br.edu.utfpr.slicer.utils.UtilsCompress.extractTarXz(luceneArchive, tempExtractionDir);

            try (org.apache.lucene.store.FSDirectory directory = org.apache.lucene.store.FSDirectory.open(tempExtractionDir);
                org.apache.lucene.index.DirectoryReader reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
               
               org.apache.lucene.search.IndexSearcher searcher = new org.apache.lucene.search.IndexSearcher(reader);
               
               String queryStringToParse;
               if (req.rawLuceneQuery() != null && !req.rawLuceneQuery().isBlank()) {
                   queryStringToParse = req.rawLuceneQuery();
               } else {
                   StringBuilder sb = new StringBuilder();
                   req.fields().forEach((k, v) -> {
                       String luceneField = k.equals("all") ? "all_fields" : k;
                       sb.append(luceneField).append(":\"").append(v).append("\" AND ");
                   });
                   if (sb.length() > 5) sb.setLength(sb.length() - 5);
                   queryStringToParse = sb.toString();
               }

               // 1. CORREÇÃO DO <EOF>: Se a query estiver vazia, traz tudo (Match All)
               if (queryStringToParse == null || queryStringToParse.isBlank()) {
                   queryStringToParse = "*:*";
               }

               // 2. CORREÇÃO DO "Windows-AD" (Mágica dos Analyzers Mistos)
               org.apache.lucene.analysis.Analyzer standard = new org.apache.lucene.analysis.standard.StandardAnalyzer();
               org.apache.lucene.analysis.Analyzer keyword = new org.apache.lucene.analysis.core.KeywordAnalyzer();
               
               java.util.Map<String, org.apache.lucene.analysis.Analyzer> analyzerMap = new java.util.HashMap<>();
               analyzerMap.put("all_fields", standard); // O texto livre continua sendo fatiado e lowercased
               
               // O KeywordAnalyzer protege campos exatos (dvc, dst, suser) contra cortes de hifens e pontos
               org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper wrapper = 
                   new org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper(keyword, analyzerMap);

               org.apache.lucene.queryparser.classic.QueryParser parser = 
                   new org.apache.lucene.queryparser.classic.QueryParser("all_fields", wrapper);
               
               parser.setAllowLeadingWildcard(true); 

               org.apache.lucene.search.Query query = parser.parse(queryStringToParse);
               org.apache.lucene.search.TopDocs topDocs = searcher.search(query, maxHits);
               
               totalRealHits = topDocs.totalHits.value;
               if (totalRealHits > maxHits) limitReached = true;

               for (org.apache.lucene.search.ScoreDoc hit : topDocs.scoreDocs) {
                   org.apache.lucene.document.Document doc = searcher.doc(hit.doc);
                   results.add(doc.get("rawContent") != null ? doc.get("rawContent") : doc.get("eventId")); 
               }
           }
        } finally {
            if (tempExtractionDir != null) deleteDirectoryRecursively(tempExtractionDir);
        }
        return new BlockSearchResult(results, totalRealHits, limitReached);
    }

    private List<String> discoverCandidateBlocks(String device, long fromEpoch, long toEpoch) {
        java.util.Set<String> allBlocks = new java.util.HashSet<>();
        try {
            Path ingestIdx = Path.of(config.slicer().storage().ingestCacheDir(), device, "index");
            if (Files.exists(ingestIdx)) {
                try (Stream<Path> stream = Files.list(ingestIdx)) {
                    stream.filter(Files::isDirectory).map(p -> p.getFileName().toString()).forEach(allBlocks::add);
                }
            }
        } catch (Exception e) {}

        try {
            Path ledgerDir = Path.of(config.slicer().storage().ingestCacheDir()).getParent().resolve("ledgers").resolve(device);
            if (Files.exists(ledgerDir)) {
                try (Stream<Path> stream = Files.list(ledgerDir)) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    stream.filter(p -> p.toString().endsWith(".json")).forEach(ledgerFile -> {
                        try {
                            java.util.Map<String, String> ledger = mapper.readValue(ledgerFile.toFile(), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() {});
                            allBlocks.addAll(ledger.keySet());
                        } catch (Exception ex) {}
                    });
                }
            }
        } catch (Exception e) {}

        return allBlocks.stream().filter(n -> isBlockInTimeRange(n, fromEpoch, toEpoch)).toList();
    }

    private boolean isBlockInTimeRange(String folderName, long queryFrom, long queryTo) {
        try {
            String[] parts = folderName.split("_");
            if (parts.length < 3) return false; 
            long blockMinEpoch = LocalDateTime.parse(parts[parts.length - 2]).toInstant(ZoneOffset.UTC).toEpochMilli();
            long blockMaxEpoch = LocalDateTime.parse(parts[parts.length - 1]).toInstant(ZoneOffset.UTC).toEpochMilli();
            return (blockMinEpoch <= queryTo) && (blockMaxEpoch >= queryFrom);
        } catch (Exception e) { return false; }
    }

    private boolean isAwsEnabled() { return config.slicer().aws() != null && config.slicer().aws().bucketName() != null && !config.slicer().aws().bucketName().isBlank(); }
    private boolean isCharonEnabled() { return config.slicer().storage().cloudDropDir() != null && !config.slicer().storage().cloudDropDir().isBlank(); }
    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walk(path).sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
}