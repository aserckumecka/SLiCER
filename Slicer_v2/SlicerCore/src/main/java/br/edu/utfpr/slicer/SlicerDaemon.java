package br.edu.utfpr.slicer;

import br.edu.utfpr.slicer.api.SlicerApiServer;
import br.edu.utfpr.slicer.config.AppConfig;
import br.edu.utfpr.slicer.ingest.DirectoryMonitorSource;
import br.edu.utfpr.slicer.ingest.EventRouter;
import br.edu.utfpr.slicer.ingest.TcpEventSource;
import br.edu.utfpr.slicer.parser.CefParser;
import br.edu.utfpr.slicer.parser.JsonParser;
import br.edu.utfpr.slicer.parser.EventParser;
import br.edu.utfpr.slicer.cloud.CloudSyncWorker;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlicerDaemon {
    private static final Logger log = LoggerFactory.getLogger(SlicerDaemon.class);

    public static void main(String[] args) {
        log.info("Iniciando o SLiCER - Daemon Mode...");

        // 1. Autenticação e Deselagem (RAM)
        java.io.Console console = System.console();
        char[] passwordArray;
        if (console != null) {
            passwordArray = console.readPassword("Digite a Senha Mestra: ");
        } else {
            System.out.print("⚠️ IDE Mode - Senha Mestra: ");
            passwordArray = new java.util.Scanner(System.in).nextLine().toCharArray();
        }

        if (passwordArray == null || passwordArray.length == 0) System.exit(1);
        br.edu.utfpr.slicer.utils.UtilsCrypto.initializeMasterKey(passwordArray);
        
        // 2. Configurações e Parsers
        AppConfig config = AppConfig.load();
        AppConfig.Slicer cfg = config.slicer();

        // Criamos a lista de parsers suportados
        List<EventParser> parsers = List.of(new CefParser(), new JsonParser());
        
        // Inicializamos o Roteador (O EventRouter deve ser alterado para receber List<EventParser>)
        final EventRouter eventRouter = new EventRouter(parsers, cfg);

        // 3. Fontes de Dados
        TcpEventSource tcpSource = new TcpEventSource(cfg.ingest().tcpPort(), eventRouter);
        tcpSource.start();

        DirectoryMonitorSource dirSource = new DirectoryMonitorSource(Path.of(cfg.ingest().spoolDir()), eventRouter);
        dirSource.start();

        // 4. API e Cloud Worker
        SlicerApiServer apiServer = new SlicerApiServer(cfg.api().port());
        apiServer.start();
        
        final CloudSyncWorker syncWorker = new CloudSyncWorker(config);
        syncWorker.start();

        // 5. SHUTDOWN HOOK (Gracioso)
        // Usamos variáveis 'final' para garantir que a Thread do Hook as enxergue
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("🛑 Iniciando desligamento gracioso...");
            
            try {
                // Solicita ao roteador que feche os blocos (Flush)
                log.info("Persistindo blocos em aberto...");
                eventRouter.flushAll();
                
                // Força o upload dos blocos recém-fechados antes de matar o processo
                log.info("☁️ Sincronização final com a Nuvem...");
                syncWorker.runSyncJobNow(); 
                
                log.info("✅ SLiCER encerrado com sucesso.");
            } catch (Exception e) {
                log.error("Erro durante o desligamento: {}", e.getMessage());
            }
        }));
    }
}