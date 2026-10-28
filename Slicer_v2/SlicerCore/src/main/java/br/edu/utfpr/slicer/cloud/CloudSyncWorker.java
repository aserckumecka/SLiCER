package br.edu.utfpr.slicer.cloud;

import br.edu.utfpr.slicer.config.AppConfig;
import br.edu.utfpr.slicer.utils.UtilsCrypto;
import br.edu.utfpr.slicer.utils.UtilsSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class CloudSyncWorker {
    private static final Logger log = LoggerFactory.getLogger(CloudSyncWorker.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private S3Client s3Client;
    private final AppConfig config;

    public CloudSyncWorker(AppConfig config) {
        this.config = config;
        
        if (isAwsEnabled()) {
            try {
                this.s3Client = S3Client.builder().region(Region.SA_EAST_1).build();
                log.info("☁️ Cliente AWS S3 ativado para sincronização (Sem KMS, usando Envelope Encryption Local).");
            } catch (Exception e) {
                log.warn("⚠️ AWS S3 configurado, mas não foi possível inicializar o cliente.", e);
            }
        }
        
        if (isCharonEnabled()) {
            log.info("📁 Integração Multi-Cloud (Charon) ativada. DropDir: {}", config.slicer().storage().cloudDropDir());
        }
    }

    public void start() {
        int interval = config.slicer().storage().syncIntervalSeconds();
        scheduler.scheduleWithFixedDelay(this::syncAndClean, 10, interval, TimeUnit.SECONDS);
        log.info("🔄 Cloud Sync Worker rodando. Verificando blocos a cada {} segundos.", interval);
    }

    public void stop() {
        scheduler.shutdown();
        if (s3Client != null) s3Client.close();
    }

    /**
     * NOVO: Método de chamada síncrona forçada.
     * Utilizado pelo Shutdown Hook do SlicerDaemon para garantir que blocos
     * residuais recém-fechados subam para o S3 antes do programa morrer.
     */
    public void runSyncJobNow() {
        log.info("☁️ [ON-DEMAND] Iniciando sincronização forçada de desligamento...");
        syncAndClean();
        log.info("☁️ [ON-DEMAND] Sincronização forçada concluída.");
    }

    private void syncAndClean() {
        try {
            Path ingestDir = Path.of(config.slicer().storage().ingestCacheDir());
            if (!Files.exists(ingestDir)) return;

            try (Stream<Path> devices = Files.list(ingestDir)) {
                devices.filter(Files::isDirectory).forEach(this::processDevice);
            }
        } catch (Exception e) {
            log.error("Erro no ciclo de sincronização", e);
        }
    }

    private void processDevice(Path deviceDir) {
        String deviceName = deviceDir.getFileName().toString();
        Path indexDir = deviceDir.resolve("index");
        Path dataDir = deviceDir.resolve("data");
        
        if (!Files.exists(indexDir) || !Files.exists(dataDir)) return;

        try (Stream<Path> blockFolders = Files.list(indexDir)) {
            List<Path> sortedBlocks = blockFolders
                    .filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().equals("newblock"))
                    .sorted(Comparator.comparing(Path::getFileName)) 
                    .toList();

            for (Path blockFolder : sortedBlocks) {
                String blockName = blockFolder.getFileName().toString();
                Path dataArchive = dataDir.resolve(blockName + ".tar.xz");

                if (!Files.exists(dataArchive)) continue; 

                // 1. Registro no Ledger (Imutável)
                appendToCentralLedger(deviceName, blockName, dataArchive);

                // 2. Sincronização (Criptografa e Envia)
                boolean synced = syncBlockIfNeeded(deviceName, blockFolder, dataArchive);

                // 3. Egress-Drop (Limpeza Imediata dos Dados Brutos)
                if (synced) {
                    deleteRawDataAfterSync(dataArchive);
                    
                    // 4. Aplica a Retenção Local (Poda os índices se passar de 5GB)
                    enforceRetentionLimit(deviceDir, sortedBlocks, dataDir);
                }
            }
        } catch (IOException e) {
            log.error("Erro ao ler pastas do device {}", deviceName, e);
        }
    }
    
    private void deleteRawDataAfterSync(Path dataArchive) {
        try {
            if (Files.exists(dataArchive)) {
                log.info("🗑️ Egress-Drop: Removendo dado bruto local (já sincronizado): {}", dataArchive.getFileName());
                Files.delete(dataArchive);
            }
        } catch (IOException e) {
            log.warn("Falha ao executar Egress-Drop no arquivo {}", dataArchive, e);
        }
    }

    private void appendToCentralLedger(String deviceName, String blockName, Path dataArchive) {
        try {
            String[] parts = blockName.split("_");
            if (parts.length < 2) return;
            
            String datePart = parts[1]; 
            String yearMonth = datePart.length() >= 7 ? datePart.substring(0, 7) : "unknown";

            Path ledgerDir = Path.of(config.slicer().storage().ingestCacheDir()).getParent().resolve("ledgers").resolve(deviceName);
            Files.createDirectories(ledgerDir);
            Path ledgerFile = ledgerDir.resolve("manifest_" + yearMonth + ".json");

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, String> ledger = new java.util.HashMap<>();

            if (Files.exists(ledgerFile)) {
                ledger = mapper.readValue(ledgerFile.toFile(), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() {});
            }

            if (!ledger.containsKey(blockName)) {
                String hash = UtilsSecurity.calculateSha256(dataArchive);
                ledger.put(blockName, hash);
                mapper.writerWithDefaultPrettyPrinter().writeValue(ledgerFile.toFile(), ledger);
                log.info("📓 Hash registrado no Ledger Central (Mês {}): {}", yearMonth, blockName);
            }
        } catch (Exception e) {
            log.error("Falha ao registrar hash no Ledger Central para o bloco {}", blockName, e);
        }
    }

    private boolean syncBlockIfNeeded(String deviceName, Path blockFolder, Path dataArchive) {
        Path syncedMarker = blockFolder.resolve(".synced");
        if (Files.exists(syncedMarker)) return true;

        String blockName = blockFolder.getFileName().toString();
        log.info("⬆️ Iniciando Criptografia e Sincronização do bloco: {}", blockName);
        boolean success = true;

        File[] indexFiles = blockFolder.toFile().listFiles(f -> 
            f.isFile() && (f.getName().endsWith(".tar.xz") || f.getName().endsWith(".bf"))
        );

        Path tempEncDir = null;

        try {
            tempEncDir = Files.createTempDirectory("slicer_crypto_" + blockName + "_");
            
            if (indexFiles != null) {
                for (File f : indexFiles) {
                    Path encOutputPath = tempEncDir.resolve(f.getName() + ".enc");
                    UtilsCrypto.encryptFile(f.toPath(), encOutputPath);
                }
            }
            
            Path dataEncOutputPath = tempEncDir.resolve(dataArchive.getFileName().toString() + ".enc");
            UtilsCrypto.encryptFile(dataArchive, dataEncOutputPath);

            File[] encryptedFiles = tempEncDir.toFile().listFiles();

            if (isCharonEnabled() && encryptedFiles != null) {
                Path dropDir = Path.of(config.slicer().storage().cloudDropDir(), deviceName, blockName);
                Files.createDirectories(dropDir);
                for (File encFile : encryptedFiles) {
                    Files.copy(encFile.toPath(), dropDir.resolve(encFile.getName()), StandardCopyOption.REPLACE_EXISTING);
                }
                log.info("📁 Charon Drop (Criptografado) concluído para: {}", blockName);
            }

            if (isAwsEnabled() && s3Client != null && encryptedFiles != null) {
                String bucket = config.slicer().aws().bucketName();
                for (File encFile : encryptedFiles) {
                    uploadToS3(deviceName, blockName, encFile, bucket);
                }
                log.info("☁️ AWS S3 Upload (Criptografado) concluído para: {}", blockName);
            }

            if (success && (isCharonEnabled() || isAwsEnabled())) {
                Files.createFile(syncedMarker);
                return true;
            }

        } catch (Exception e) {
            log.error("❌ Falha na sincronização do bloco {}.", blockName, e);
            success = false;
        } finally {
            if (tempEncDir != null) {
                try {
                    deleteDirectoryRecursively(tempEncDir);
                } catch (IOException e) {
                    log.warn("Aviso: Falha ao limpar pasta de criptografia temporária {}", tempEncDir);
                }
            }
        }
        return false;
    }

    private void uploadToS3(String deviceName, String blockName, File encFile, String bucket) {
        String s3Key = String.format("%s/%s/%s", deviceName, blockName, encFile.getName());
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .build();
        s3Client.putObject(request, encFile.toPath());
    }

    private void enforceRetentionLimit(Path deviceDir, List<Path> sortedBlocks, Path dataDir) {
        try {
            long totalSizeBytes = getFolderSize(deviceDir);
            long limitBytes = config.slicer().storage().maxIngestCacheMb() * 1024L * 1024L;

            if (totalSizeBytes > limitBytes) {
                log.warn("⚠️ Limite de Retenção Excedido. Iniciando poda...");
                for (Path blockFolder : sortedBlocks) {
                    if (Files.exists(blockFolder.resolve(".synced"))) {
                        String blockName = blockFolder.getFileName().toString();
                        Path dataArchive = dataDir.resolve(blockName + ".tar.xz");

                        deleteDirectoryRecursively(blockFolder);
                        Files.deleteIfExists(dataArchive);

                        log.info("🗑️ Bloco velho podado do disco local: {}", blockName);

                        totalSizeBytes = getFolderSize(deviceDir);
                        if (totalSizeBytes <= limitBytes) break; 
                    }
                }
            }
        } catch (Exception e) {
            log.error("Erro ao aplicar limite de retenção.", e);
        }
    }

    private boolean isAwsEnabled() {
        return config.slicer().aws() != null 
            && config.slicer().aws().bucketName() != null 
            && !config.slicer().aws().bucketName().isBlank();
    }

    private boolean isCharonEnabled() {
        return config.slicer().storage().cloudDropDir() != null 
            && !config.slicer().storage().cloudDropDir().isBlank();
    }

    private long getFolderSize(Path folder) throws IOException {
        if (!Files.exists(folder)) return 0;
        try (Stream<Path> paths = Files.walk(folder)) {
            return paths.filter(p -> p.toFile().isFile())
                        .mapToLong(p -> p.toFile().length())
                        .sum();
        }
    }

    private void deleteDirectoryRecursively(Path pathToBeDeleted) throws IOException {
        if (!Files.exists(pathToBeDeleted)) return;
        Files.walk(pathToBeDeleted)
             .sorted(java.util.Comparator.reverseOrder())
             .map(Path::toFile)
             .forEach(File::delete);
    }
}