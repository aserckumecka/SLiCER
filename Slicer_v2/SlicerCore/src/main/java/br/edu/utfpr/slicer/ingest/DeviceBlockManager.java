package br.edu.utfpr.slicer.ingest;

import br.edu.utfpr.slicer.core.ParsedEvent;
import br.edu.utfpr.slicer.index.BloomFilterManager;
import br.edu.utfpr.slicer.index.LuceneBlockIndexer;
import br.edu.utfpr.slicer.config.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeviceBlockManager {
    private static final Logger log = LoggerFactory.getLogger(DeviceBlockManager.class);

    private final String deviceId;
    private final AppConfig.Slicer config;
    
    private final List<String> fieldsToIndex;
    private final boolean indexAllFields;

    private LuceneBlockIndexer lucene;
    private BloomFilterManager bloom;
    
    private final ConcurrentLinkedQueue<ParsedEvent> eventBuffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger bufferCount = new AtomicInteger(0);
    
    // Contadores agora serão controlados de forma sincronizada com o disco
    private final AtomicLong currentBlockSizeBytes = new AtomicLong(0);
    private final AtomicLong blockStartTimeMillis = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong minEventEpoch = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxEventEpoch = new AtomicLong(Long.MIN_VALUE);

    private final ScheduledExecutorService blockMonitor = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean isShuttingDown = false;
    private String previousBlockHash = "GENESIS_BLOCK";
    private final Path chainStateFile;
    
    private static final int MOVING_AVERAGE_WINDOW = 5;
    private final ArrayDeque<Long> recentBloomSizes = new ArrayDeque<>();
    private long dynamicBloomSize;

    public DeviceBlockManager(String deviceId, AppConfig.Slicer config) {
        this.deviceId = deviceId;
        this.config = config;
        
        List<String> resolvedFields = null;
        try {
            Map<String, List<String>> specificFields = config.block().deviceFields();
            if (specificFields != null && specificFields.containsKey(deviceId)) {
                resolvedFields = specificFields.get(deviceId);
            } else if (config.block().defaultFieldsToIndex() != null) {
                resolvedFields = config.block().defaultFieldsToIndex();
            }
        } catch (Exception e) {
            log.warn("Falha ao ler regras do YAML. Fallback ativo.");
        }

        if (resolvedFields == null || resolvedFields.isEmpty()) {
            resolvedFields = List.of("all");
        }
        
        this.fieldsToIndex = resolvedFields;
        this.indexAllFields = this.fieldsToIndex.contains("all");

        Path tempRawFile = Path.of(config.storage().ingestCacheDir(), deviceId, "data", "newblockRaw.slc");
        if (Files.exists(tempRawFile)) {
            try {
                currentBlockSizeBytes.set(Files.size(tempRawFile));
                log.info("[{}] Recuperado bloco pendente do disco: {} MB", deviceId, currentBlockSizeBytes.get() / (1024*1024));
            } catch (IOException e) {
                log.warn("Falha ao ler tamanho de arquivo temporário.");
            }
        }

        this.dynamicBloomSize = config.block().maxSizeMb() * 30_000L; 
        
        this.chainStateFile = Path.of(config.storage().ingestCacheDir(), deviceId, "chain_state.txt");
        if (Files.exists(chainStateFile)) {
            try {
                this.previousBlockHash = Files.readString(chainStateFile).trim();
            } catch (IOException e) {
                log.warn("Não foi possível ler o estado da corrente. Iniciando nova chain.");
            }
        }
        
        initIndexers();
        
        blockMonitor.scheduleAtFixedRate(this::checkTimeExpiration, 1, 1, TimeUnit.MINUTES);
        log.info("Inicializado {} limites: {}MB/{}mins. Campos: {}", deviceId, config.block().maxSizeMb(), config.block().maxIntervalMinutes(), fieldsToIndex);
    }

    private void initIndexers() {
        try {
            Path activeIndexDir = Path.of(config.storage().ingestCacheDir(), deviceId, "index", "newblock");
            deleteDirectoryRecursively(activeIndexDir);
            Files.createDirectories(activeIndexDir);
            
            this.lucene = new LuceneBlockIndexer(activeIndexDir);
            this.bloom = new BloomFilterManager(dynamicBloomSize);
        } catch (IOException e) {
            throw new RuntimeException("Falha ao inicializar indexadores em disco para " + deviceId, e);
        }
    }

    /**
     * Otimizado: addEvent apenas joga na fila. Sem matemática pesada nem locks travando a ingestão.
     */
    public void addEvent(ParsedEvent event) {
        eventBuffer.offer(event);
        if (bufferCount.incrementAndGet() >= config.block().bufferLimit()) {
            flushBufferToDisk(true); // Permite checar limite de tamanho
        }
    }

    /**
     * Método central de escrita sincronizada.
     * Calcula os tamanhos e as datas NO MOMENTO em que escreve, blindando contra Race Conditions.
     */
    private synchronized void flushBufferToDisk(boolean checkRotation) {
        if (eventBuffer.isEmpty()) return;

        List<ParsedEvent> batch = new ArrayList<>();
        while (!eventBuffer.isEmpty()) {
            batch.add(eventBuffer.poll());
            bufferCount.decrementAndGet();
        }

        try {
            Path rawDataDir = Path.of(config.storage().ingestCacheDir(), deviceId, "data");
            Files.createDirectories(rawDataDir);
            Path rawFile = rawDataDir.resolve("newblockRaw.slc");
            
            StringBuilder sb = new StringBuilder();
            long batchSize = 0;
            
            for (ParsedEvent evt : batch) {
                byte[] bytes = evt.rawLog().getBytes();
                batchSize += bytes.length;
                sb.append(evt.rawLog()).append(System.lineSeparator());
                
                long eventTime = evt.epochMillis();
                minEventEpoch.updateAndGet(currentMin -> Math.min(currentMin, eventTime));
                maxEventEpoch.updateAndGet(currentMax -> Math.max(currentMax, eventTime));

                Map<String, String> filteredFields = new HashMap<>();
                evt.fields().forEach((key, value) -> {
                    if (indexAllFields || (fieldsToIndex != null && fieldsToIndex.contains(key))) {
                        filteredFields.put(key, value);
                    }
                });

                ParsedEvent filteredEvent = new ParsedEvent(evt.deviceId(), evt.timestamp(), evt.epochMillis(), evt.rawLog(), filteredFields);
                bloom.addEventFields(filteredEvent);
                lucene.addEvent(filteredEvent, "evt_" + evt.epochMillis()); 
            }
            
            Files.writeString(rawFile, sb.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            lucene.commit(); 
            
            // Atualiza o contador de bytes de forma segura dentro do Lock
            currentBlockSizeBytes.addAndGet(batchSize);

            // Evita recursão infinita passando "checkRotation = true" apenas nas chamadas padrão
            if (checkRotation && !isShuttingDown && isSizeLimitExceeded()) {
                rotateAndIndexBlock(); 
            }
                
        } catch (Exception e) {
            log.error("Erro no flush de {}", deviceId, e);
        }
    }

    private void checkTimeExpiration() {
        long elapsedMins = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - blockStartTimeMillis.get());
        if (elapsedMins >= config.block().maxIntervalMinutes() && currentBlockSizeBytes.get() > 0) {
            log.info("[{}] Timeout do bloco ({} mins). Rotacionando...", deviceId, elapsedMins);
            rotateAndIndexBlock();
        }
    }

    private synchronized void rotateAndIndexBlock() {
        // Descarrega o que sobrou na memória (passa "false" para não criar loop infinito)
        flushBufferToDisk(false); 
        
        if (currentBlockSizeBytes.get() == 0) return;

        String blockName = deviceId + "_" + formatEpochToIso(minEventEpoch.get()) + "_" + formatEpochToIso(maxEventEpoch.get());
        log.info("[{}] Fechando e empacotando bloco: {}", deviceId, blockName);

        try {
            lucene.close();

            Path activeIndexDir = Path.of(config.storage().ingestCacheDir(), deviceId, "index", "newblock");
            Path finalIndexDir = Path.of(config.storage().ingestCacheDir(), deviceId, "index", blockName);
            
            Path rawDataDir = Path.of(config.storage().ingestCacheDir(), deviceId, "data");
            Path tempRawFile = rawDataDir.resolve("newblockRaw.slc");
            Path finalDataDir = rawDataDir.resolve(blockName);

            Files.createDirectories(finalIndexDir);
            Files.createDirectories(finalDataDir);

            Path luceneTempDir = finalIndexDir.resolve("lucene_temp");
            Files.move(activeIndexDir, luceneTempDir, StandardCopyOption.REPLACE_EXISTING);

            if (Files.exists(tempRawFile)) {
                Files.move(tempRawFile, finalDataDir.resolve(blockName + ".slc"), StandardCopyOption.REPLACE_EXISTING);
            }

            bloom.flushToDisk(finalIndexDir, blockName);

            Path compressedLucene = finalIndexDir.resolve(blockName + "_lucene.tar.xz");
            br.edu.utfpr.slicer.utils.UtilsCompress.compressDirectoryToTarXz(luceneTempDir, compressedLucene);

            Path chainMetaFile = finalDataDir.resolve("chain.meta");
            Files.writeString(chainMetaFile, "PREVIOUS_BLOCK_HASH=" + this.previousBlockHash);
            
            Path compressedData = rawDataDir.resolve(blockName + ".tar.xz");
            br.edu.utfpr.slicer.utils.UtilsCompress.compressDirectoryToTarXz(finalDataDir, compressedData);

            this.previousBlockHash = br.edu.utfpr.slicer.utils.UtilsSecurity.calculateSha256(compressedData);
            Files.writeString(chainStateFile, this.previousBlockHash); 
            log.debug("[{}] Hash gerado para o elo: {}", deviceId, this.previousBlockHash);
            
            deleteDirectoryRecursively(finalDataDir); 
            deleteDirectoryRecursively(luceneTempDir); 
            deleteDirectoryRecursively(activeIndexDir); 

        } catch (Exception e) {
            log.error("Falha ao rotacionar/compactar bloco: {}", blockName, e);
        }

        // Reseta os contadores DEPOIS que o disco foi limpo com sucesso
        minEventEpoch.set(Long.MAX_VALUE);
        maxEventEpoch.set(Long.MIN_VALUE);
        currentBlockSizeBytes.set(0);
        blockStartTimeMillis.set(System.currentTimeMillis());
        
        long actualCount = bloom.getElementsInserted();
        if (actualCount > 0) {
            long estimatedUniqueItems = (long) (actualCount * 0.15);
            long targetSize = Math.max(10_000L, (long) (estimatedUniqueItems * 1.2));
            
            recentBloomSizes.addLast(targetSize);
            if (recentBloomSizes.size() > MOVING_AVERAGE_WINDOW) {
                recentBloomSizes.removeFirst();
            }
            
            long sum = 0;
            for (long size : recentBloomSizes) sum += size;
            
            this.dynamicBloomSize = sum / recentBloomSizes.size();
            
            log.debug("[{}] Bloom Filter auto-ajustado (Média de {} blocos) para {} itens.", 
                      deviceId, recentBloomSizes.size(), dynamicBloomSize);
        }

        if (!isShuttingDown) {
            initIndexers();
        }
    }

    private boolean isSizeLimitExceeded() {
        return (currentBlockSizeBytes.get() / (1024 * 1024)) >= config.block().maxSizeMb();
    }

    private String formatEpochToIso(long epochMillis) {
        if (epochMillis == Long.MAX_VALUE || epochMillis == Long.MIN_VALUE) epochMillis = System.currentTimeMillis();
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    public void shutdown() {
        this.isShuttingDown = true; 
        blockMonitor.shutdownNow();
        flushBufferToDisk(false);
        rotateAndIndexBlock();
    }

    private void deleteDirectoryRecursively(Path pathToBeDeleted) throws IOException {
        if (!Files.exists(pathToBeDeleted)) return;
        Files.walk(pathToBeDeleted)
             .sorted(java.util.Comparator.reverseOrder())
             .map(Path::toFile)
             .forEach(java.io.File::delete);
    }
}