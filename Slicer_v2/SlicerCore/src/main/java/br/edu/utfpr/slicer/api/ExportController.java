package br.edu.utfpr.slicer.api;

import br.edu.utfpr.slicer.config.AppConfig;
import br.edu.utfpr.slicer.utils.UtilsForensics;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Stream;

public class ExportController {
    private static final Logger log = LoggerFactory.getLogger(ExportController.class);
    private final AppConfig config = AppConfig.load();
    private S3Client s3Client;

    public record ExportRequest(String device, String blockName) {}
    public record SelectedExportRequest(String device, java.util.List<String> blockNames) {}
    
    public ExportController() {
        if (config.slicer().aws() != null && config.slicer().aws().bucketName() != null) {
            try {
                this.s3Client = S3Client.builder().region(Region.SA_EAST_1).build();
            } catch (Exception e) {
                log.warn("⚠️ AWS S3 configurado para exportação, mas cliente não iniciou.");
            }
        }
    }

    /**
     * POST /api/export
     * Exporta um único bloco específico.
     */
    public void exportBlock(Context ctx) {
        try {
            ExportRequest req = ctx.bodyAsClass(ExportRequest.class);
            Path exportPath = processSingleBlock(req.device, req.blockName);

            if (exportPath != null) {
                ctx.status(200).json(Map.of("status", "success", "message", "Bloco exportado: " + exportPath.getFileName()));
            } else {
                ctx.status(404).json(Map.of("status", "error", "message", "Não foi possível localizar ou decifrar o bloco."));
            }
        } catch (Exception e) {
            ctx.status(500).json(Map.of("status", "error", "message", e.getMessage()));
        }
    }
    
    /**
     * POST /api/export/selected
     * Exporta apenas os blocos que o usuário selecionou ou que resultaram da query.
     */
    public void exportSelectedBlocks(Context ctx) {
        try {
            SelectedExportRequest req = ctx.bodyAsClass(SelectedExportRequest.class);
            Path exportDir = getExportPath(req.device());
            
            int count = 0;
            for (String blockName : req.blockNames()) {
                // Reutiliza a lógica de busca (Local -> Charon -> S3)
                Path result = processSingleBlock(req.device(), blockName);
                if (result != null) count++;
            }

            ctx.status(200).json(Map.of(
                "status", "success", 
                "message", String.format("%d blocos exportados para: %s", count, exportDir.toAbsolutePath())
            ));
        } catch (Exception e) {
            log.error("Erro na exportação selecionada", e);
            ctx.status(500).json(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * GET /api/export/all/{device}
     * Varre todas as camadas e extrai todos os blocos disponíveis para a pasta exports.
     */
    public void exportAllDeviceBlocks(Context ctx) {
        String device = ctx.pathParam("device");
        try {
            Path exportDir = getExportPath(device);
            Set<String> allBlocks = discoverAllAvailableBlocks(device);
            
            int count = 0;
            for (String blockName : allBlocks) {
                Path result = processSingleBlock(device, blockName);
                if (result != null) count++;
            }

            ctx.status(200).json(Map.of(
                "status", "success", 
                "message", String.format("Exportação concluída. %d blocos extraídos em: %s", count, exportDir.toAbsolutePath())
            ));
        } catch (Exception e) {
            log.error("Erro na exportação em massa", e);
            ctx.status(500).json(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    private Path processSingleBlock(String device, String blockName) throws Exception {
        Path exportDir = getExportPath(device);
        Path finalFile = exportDir.resolve(blockName + ".tar.xz");

        // Se já existe no destino, não processa de novo
        if (Files.exists(finalFile)) return finalFile;

        // 1. Localiza o arquivo criptografado (.tar.xz.enc)
        Path encFile = findOrFetchEncryptedFile(device, blockName);
        if (encFile == null) return null;

        // 2. Decifra para a pasta de destino
        boolean success = UtilsForensics.verifyAndDecryptBlock(encFile, finalFile);
        
        // Se o arquivo foi baixado temporariamente (S3), removemos o .enc para limpar o cache
        if (encFile.toString().contains("temp_export")) {
            Files.deleteIfExists(encFile);
        }

        return success ? finalFile : null;
    }

    private Path findOrFetchEncryptedFile(String device, String blockName) throws Exception {
        String fileName = blockName + ".tar.xz.enc";

        // Tenta Ingestão Local
        Path local = Path.of(config.slicer().storage().ingestCacheDir(), device, "data", fileName);
        if (Files.exists(local)) return local;

        // Tenta Charon
        Path charon = Path.of(config.slicer().storage().cloudDropDir(), device, blockName, fileName);
        if (Files.exists(charon)) return charon;

        // Tenta AWS S3
        if (s3Client != null) {
            Path tempDir = Path.of(config.slicer().storage().queryCacheDir(), "temp_export");
            Files.createDirectories(tempDir);
            Path s3TempFile = tempDir.resolve(fileName);
            
            String s3Key = String.format("%s/%s/%s", device, blockName, fileName);
            try {
                s3Client.getObject(GetObjectRequest.builder()
                        .bucket(config.slicer().aws().bucketName())
                        .key(s3Key).build(), s3TempFile);
                log.info("☁️ Bloco {} baixado do S3 para exportação.", blockName);
                return s3TempFile;
            } catch (Exception e) {
                log.warn("Bloco {} não encontrado no S3.", blockName);
            }
        }
        return null;
    }

    private Set<String> discoverAllAvailableBlocks(String device) {
        Set<String> blocks = new HashSet<>();
        
        // 1. Busca Blocos Recentes (Pasta Data Local)
        try {
            Path dataDir = Path.of(config.slicer().storage().ingestCacheDir(), device, "data");
            if (Files.exists(dataDir)) {
                try (Stream<Path> s = Files.list(dataDir)) {
                    s.filter(p -> p.toString().endsWith(".tar.xz.enc"))
                     .map(p -> p.getFileName().toString().replace(".tar.xz.enc", ""))
                     .forEach(blocks::add);
                }
            }
        } catch (Exception e) {}

        // 2. Busca Blocos no Charon
        try {
            Path charonDir = Path.of(config.slicer().storage().cloudDropDir(), device);
            if (Files.exists(charonDir)) {
                try (Stream<Path> s = Files.list(charonDir)) {
                    s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).forEach(blocks::add);
                }
            }
        } catch (Exception e) {}

        // 3. A MÁGICA: O Ledger Central! (Localiza blocos que só existem no AWS S3)
        try {
            Path ledgerDir = Path.of(config.slicer().storage().ingestCacheDir()).getParent().resolve("ledgers").resolve(device);
            if (Files.exists(ledgerDir)) {
                try (Stream<Path> stream = Files.list(ledgerDir)) {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    
                    stream.filter(p -> p.toString().endsWith(".json")).forEach(ledgerFile -> {
                        try {
                            java.util.Map<String, String> ledger = mapper.readValue(
                                ledgerFile.toFile(), 
                                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() {}
                            );
                            // Adiciona todos os nomes de blocos históricos à lista
                            blocks.addAll(ledger.keySet());
                        } catch (Exception ex) {
                            log.error("Erro ao ler ledger para exportação", ex);
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.error("Erro ao acessar pasta de ledgers.", e);
        }

        return blocks;
    }

    private Path getExportPath(String device) throws Exception {
        Path projectRoot = Path.of(config.slicer().storage().ingestCacheDir()).getParent().getParent();
        Path exportDir = projectRoot.resolve("exports").resolve(device);
        Files.createDirectories(exportDir);
        return exportDir;
    }
}