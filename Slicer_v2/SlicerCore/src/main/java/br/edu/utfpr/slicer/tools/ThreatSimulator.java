package br.edu.utfpr.slicer.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

public class ThreatSimulator {
    private static final Logger log = LoggerFactory.getLogger(ThreatSimulator.class);
    private static final Random RND = new Random();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Dicionários para gerar ruído realista
    private static final String[] IPS = {"192.168.1.10", "10.0.0.119", "172.16.0.5", "8.8.8.8", "203.0.113.50", "198.51.100.22", "192.168.87.12"};
    private static final String[] USERS = {"admin", "duser87", "user78", "user82", "root", "postgres", "anonymous"};
    private static final String[] HTTP_METHODS = {"GET", "POST", "PUT", "DELETE"};
    private static final String[] SQL_QUERIES = {"SELECT * FROM users", "DROP TABLE logs", "INSERT INTO auth", "UPDATE config"};
    private static final String[] SNORT_ALERTS = {"ET EXPLOIT Possible CVE-2014-6271", "GPL SQL Injection", "ET SCAN Nmap OS Detection"};

    public static void main(String[] args) {
        try {
            log.info("🚀 Iniciando SLiCER Threat Simulator...");
            File configFile = new File("simulator.json");
            if (!configFile.exists()) {
                log.error("❌ Arquivo simulator.json não encontrado na raiz do projeto.");
                return;
            }

            JsonNode config = MAPPER.readTree(configFile);
            String host = config.get("targetHost").asText();
            int port = config.get("targetTcpPort").asInt();
            boolean saveToDisk = config.get("saveToDisk").asBoolean();
            Path diskPath = Path.of(config.get("diskOutputPath").asText());

            if (saveToDisk) {
                Files.createDirectories(diskPath);
            }

            JsonNode devicesNode = config.get("devices");
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(devicesNode.size());

            // Cria uma Thread assíncrona para cada device atirar em paralelo
            for (JsonNode devNode : devicesNode) {
                String devName = devNode.get("name").asText();
                int eps = devNode.get("eps").asInt();
                String format = devNode.get("format").asText();

                scheduler.scheduleAtFixedRate(
                        () -> generateAndDispatch(devName, eps, format, host, port, saveToDisk, diskPath),
                        0, 1, TimeUnit.SECONDS
                );
            }

            log.info("🔥 Simulação Ativa! Disparando rajadas de eventos para TCP {}:{} a cada segundo.", host, port);

        } catch (Exception e) {
            log.error("Falha fatal no simulador", e);
        }
    }

    /**
     * Gera um lote (batch) equivalente a 1 segundo de EPS e envia.
     */
    private static void generateAndDispatch(String device, int eps, String format, String host, int port, boolean saveToDisk, Path diskPath) {
        long baseTimestamp = System.currentTimeMillis();
        List<String> batch = new ArrayList<>(eps);

        for (int i = 0; i < eps; i++) {
            // Distribui os eventos artificialmente dentro da janela de 1000 milissegundos
            long eventTime = baseTimestamp + (i * 1000L / eps); 
            batch.add(craftRealisticLog(device, format, eventTime));
        }

        // 1. Disparo TCP para o SLiCER
        try (Socket socket = new Socket(host, port);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))) {
            for (String logLine : batch) {
                writer.write(logLine);
                writer.newLine();
            }
            writer.flush();
        } catch (Exception e) {
            log.warn("⚠️ Recusado em {}:{}. O Ingest do SLiCER está ligado e escutando?", host, port);
        }

        // 2. Backup de Auditoria no Disco (.gz)
        if (saveToDisk) {
            try {
                Path outputFile = diskPath.resolve(device + "_mock.gz");
                try (FileOutputStream fos = new FileOutputStream(outputFile.toFile(), true);
                     GZIPOutputStream gzos = new GZIPOutputStream(fos);
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(gzos))) {
                    for (String logLine : batch) {
                        writer.write(logLine);
                        writer.newLine();
                    }
                }
            } catch (Exception e) {
                log.error("Erro ao escrever backup no disco para: {}", device, e);
            }
        }
    }

    /**
     * Gera a estrutura sintática realista de acordo com a tecnologia.
     */
    private static String craftRealisticLog(String device, String format, long timestamp) {
        String srcIp = IPS[RND.nextInt(IPS.length)];
        String dstIp = IPS[RND.nextInt(IPS.length)];
        String user = USERS[RND.nextInt(USERS.length)];
        
        if (format.equalsIgnoreCase("CEF")) {
            return switch (device) {
                case "firewall.sonicwall" -> 
                    String.format("CEF:0|SonicWall|NSA|7.0|98|Connection %s|%s| dvc=%s rt=%d src=%s dst=%s spt=%d dpt=%d act=%s",
                            RND.nextBoolean() ? "Opened" : "Closed", RND.nextBoolean() ? "Low" : "High", device, timestamp, srcIp, dstIp, 1024 + RND.nextInt(60000), 443, RND.nextBoolean() ? "accept" : "drop");
                
                case "firewall.ciscoasa" -> 
                    String.format("CEF:0|Cisco|ASA|9.1|106023|Deny inbound|High| dvc=%s rt=%d src=%s dst=%s proto=%s act=deny",
                            device, timestamp, srcIp, dstIp, RND.nextBoolean() ? "TCP" : "UDP");
                
                case "ids.snort" -> 
                    String.format("CEF:0|Sourcefire|Snort|2.9|1|%s|Very-High| dvc=%s rt=%d src=%s dst=%s suser=%s msg=Intrusion attempt detected",
                            SNORT_ALERTS[RND.nextInt(SNORT_ALERTS.length)], device, timestamp, srcIp, dstIp, user);
                
                case "proxy.squid" -> 
                    String.format("CEF:0|Squid|Proxy|4.0|200|%s|Low| dvc=%s rt=%d src=%s dst=1.1.1.1 requestMethod=%s requestClientApplication=Mozilla/5.0",
                            RND.nextBoolean() ? "TCP_HIT" : "TCP_MISS", device, timestamp, srcIp, HTTP_METHODS[RND.nextInt(HTTP_METHODS.length)]);
                
                case "sgbd.postgresql" -> 
                    String.format("CEF:0|PostgreSQL|DB|13.0|0|DB Login|Medium| dvc=%s rt=%d suser=%s dhost=%s act=login msg=Authentication %s",
                            device, timestamp, user, dstIp, RND.nextBoolean() ? "Success" : "Failed");
                            
                default -> 
                    String.format("CEF:0|Generic|Device|1.0|100|Event|Low| dvc=%s rt=%d src=%s msg=Generic Log", device, timestamp, srcIp);
            };
        } else {
            return switch (device) {
                case "webserver.php" -> 
                    String.format("{\"dvc\":\"%s\", \"rt\":%d, \"src_ip\":\"%s\", \"method\":\"%s\", \"uri\":\"/api/v1/data\", \"status\":%d, \"user_agent\":\"PHP-FPM\"}",
                            device, timestamp, srcIp, HTTP_METHODS[RND.nextInt(HTTP_METHODS.length)], RND.nextBoolean() ? 200 : 404);
                
                case "sgbd.mysql" -> 
                    String.format("{\"dvc\":\"%s\", \"rt\":%d, \"suser\":\"%s\", \"src_ip\":\"%s\", \"query\":\"%s\", \"db\":\"production\"}",
                            device, timestamp, user, srcIp, SQL_QUERIES[RND.nextInt(SQL_QUERIES.length)]);
                            
                default -> 
                    String.format("{\"dvc\":\"%s\", \"rt\":%d, \"src\":\"%s\", \"msg\":\"Generic JSON Log\"}", device, timestamp, srcIp);
            };
        }
    }
}