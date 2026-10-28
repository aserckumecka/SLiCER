package br.edu.utfpr.slicer.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record AppConfig(Slicer slicer) {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    
    // Sub-records que mapeiam a hierarquia do YAML
    public record Slicer(Api api, Ingest ingest, Storage storage, Aws aws, Block block, Search search) {}
    
    public record Api(int port) {}
    public record Ingest(int tcpPort, String spoolDir) {}
    
    // Camada de Storage dividida (Tiered Storage)
    public record Storage(
        String ingestCacheDir, 
        int maxIngestCacheMb, 
        String queryCacheDir, 
        int maxQueryCacheMb, 
        int syncIntervalSeconds, 
        String cloudDropDir
    ) {}
    
    // Bucket do AWS S3 para armazenamento dos blocos
    public record Aws(String bucketName) {}
    
    public record Block(
        int maxSizeMb, 
        int maxIntervalMinutes, 
        int bufferLimit,
        List<String> defaultFieldsToIndex,
        Map<String, List<String>> deviceFields
    ) {}
        
    public record Search(@JsonProperty("max-hits-per-block") int maxHitsPerBlock) {}

    public static AppConfig load() {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            InputStream is = AppConfig.class.getClassLoader().getResourceAsStream("application.yml");
            
            if (is == null) {
                throw new RuntimeException("Arquivo application.yml não encontrado no diretório resources!");
            }
            
            AppConfig config = mapper.readValue(is, AppConfig.class);
            log.info("Configurações do SLiCER carregadas com sucesso. Camadas de Storage e Cloud configuradas.");
            return config;
            
        } catch (Exception e) {
            log.error("Falha critica ao carregar configurações", e);
            throw new RuntimeException(e);
        }
    }
}