package br.edu.utfpr.slicer.ingest;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DirectoryMonitorSource {
    private static final Logger log = LoggerFactory.getLogger(DirectoryMonitorSource.class);
    
    private final Path spoolDir;
    private final EventConsumer consumer;
    private volatile boolean running = true;

    public DirectoryMonitorSource(Path spoolDir, EventConsumer consumer) {
        this.spoolDir = spoolDir;
        this.consumer = consumer;
        
        if (!Files.exists(this.spoolDir)) {
            try {
                Files.createDirectories(this.spoolDir);
            } catch (IOException e) {
                throw new RuntimeException("Não foi possível criar o diretório de spool: " + spoolDir, e);
            }
        }
    }

    public void start() {
        Thread.ofVirtual().name("dir-monitor").start(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService();
                 ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                
                spoolDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
                log.info("SLiCER Directory Monitor observando a pasta: {}", spoolDir);

                while (running) {
                    WatchKey key = watchService.take(); // Bloqueia até um arquivo chegar
                    
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                            Path newFile = spoolDir.resolve((Path) event.context());
                            // Dispara uma thread virtual para ler o arquivo
                            executor.submit(() -> processFile(newFile));
                        }
                    }
                    key.reset();
                }
            } catch (Exception e) {
                log.error("Erro no Monitor de Diretório", e);
            }
        });
    }

    private void processFile(Path file) {
        try {
            // Breve pausa para garantir que o SO terminou de copiar o arquivo para a pasta
            Thread.sleep(500); 
            
            try (Stream<String> lines = Files.lines(file)) {
                lines.filter(line -> !line.isBlank())
                     .forEach(consumer::consume);
            }
            
            // Limpeza: remove o arquivo após o consumo total
            Files.deleteIfExists(file);
            log.info("Arquivo processado e limpo do spool: {}", file.getFileName());

        } catch (Exception e) {
            log.error("Erro ao processar o arquivo de spool: {}", file, e);
        }
    }

    public void stop() {
        this.running = false;
        log.info("Directory Monitor interrompido.");
    }
}