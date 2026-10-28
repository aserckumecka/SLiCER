package br.edu.utfpr.slicer.utils;

import java.io.PrintWriter;
import java.net.Socket;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogSimulator {
    private static final Logger log = LoggerFactory.getLogger(LogSimulator.class);
    private static final Random random = new Random();

    // Dispositivos que batem com o seu application.yml
    private static final String[] DEVICES = {"Firewall-Edge", "Windows-AD", "Router-Core"};
    private static final String[] SEVERITIES = {"Low", "Medium", "High", "Very-High"};
    private static final String[] ACTIONS = {"accept", "drop", "reject"};

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 8888;
        int totalEvents = 50_000; // Vamos simular 50 mil eventos!

        log.info("Iniciando simulação de logs para {}:{}", host, port);

        try (Socket socket = new Socket(host, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            long startTime = System.currentTimeMillis();

            for (int i = 1; i <= totalEvents; i++) {
                String cefLog = generateFakeCefEvent(i);
                out.println(cefLog); // Dispara via TCP

                // Uma pequena pausa opcional a cada 1000 eventos para não entupir o buffer do SO instantaneamente
                if (i % 1000 == 0) {
                    Thread.sleep(10);
                }
            }

            long endTime = System.currentTimeMillis();
            log.info("Simulação concluída! {} eventos enviados em {} ms.", totalEvents, (endTime - startTime));

        } catch (Exception e) {
            log.error("Erro ao conectar no SLiCER. O SlicerDaemon está rodando?", e);
        }
    }

    private static String generateFakeCefEvent(int id) {
        String device = DEVICES[random.nextInt(DEVICES.length)];
        String severity = SEVERITIES[random.nextInt(SEVERITIES.length)];
        long timestamp = System.currentTimeMillis() - random.nextInt(10000); // Timestamp levemente aleatório
        
        if (device.equals("Firewall-Edge")) {
            String srcIp = "192.168.1." + random.nextInt(255);
            String dstIp = "10.0.0." + random.nextInt(255);
            String action = ACTIONS[random.nextInt(ACTIONS.length)];
            
            return String.format("CEF:0|MockVendor|Firewall|1.0|100|Conexao|%s| dvc=%s rt=%d src=%s dst=%s act=%s msg=Teste de carga %d",
                    severity, device, timestamp, srcIp, dstIp, action, id);
        } else {
            String user = "user" + random.nextInt(100);
            int eventId = 4624 + random.nextInt(10); // IDs clássicos do Windows
            
            return String.format("CEF:0|Microsoft|Windows|10|%d|Logon|%s| dvc=%s rt=%d suser=%s dntdom=CORP msg=Simulacao AD %d",
                    eventId, severity, device, timestamp, user, id);
        }
    }
}