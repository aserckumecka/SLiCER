package br.edu.utfpr.slicer.ingest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TcpEventSource {
    private static final Logger log = LoggerFactory.getLogger(TcpEventSource.class);
    
    private final int port;
    private final EventConsumer consumer;
    private volatile boolean running = true;

    public TcpEventSource(int port, EventConsumer consumer) {
        this.port = port;
        this.consumer = consumer;
    }

    public void start() {
        // Dispara o servidor em sua própria Virtual Thread para não travar a thread principal
        Thread.ofVirtual().name("tcp-server").start(() -> {
            // newVirtualThreadPerTaskExecutor garante uma thread ultraleve para CADA evento/conexão
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                 ServerSocket serverSocket = new ServerSocket(port)) {
                
                log.info("SLiCER TCP Listener aguardando eventos na porta {}", port);

                while (running) {
                    Socket clientSocket = serverSocket.accept();
                    executor.submit(() -> handleClient(clientSocket));
                }
            } catch (Exception e) {
                log.error("Falha critica no TCP Listener", e);
            }
        });
    }

    private void handleClient(Socket socket) {
        try (socket; 
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    // Entrega o evento cru para o motor processar
                    consumer.consume(line); 
                }
            }
        } catch (Exception e) {
            log.error("Erro ao processar conexão de cliente TCP", e);
        }
    }

    public void stop() {
        this.running = false;
        log.info("TCP Listener interrompido.");
    }
}