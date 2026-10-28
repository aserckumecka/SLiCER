package br.edu.utfpr.slicer.api;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlicerApiServer {
    private static final Logger log = LoggerFactory.getLogger(SlicerApiServer.class);
    private Javalin app;
    private final int port;

    public SlicerApiServer(int port) {
        this.port = port;
    }

    public void start() {
        app = Javalin.create(config -> {
            // Permite que o frontend faça requisições sem erro de CORS (útil para integrações externas)
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost())); 
            
            // MÁGICA DO FRONTEND: Diz ao Javalin para hospedar e servir qualquer arquivo 
            // que estiver dentro da pasta "web" na raiz do projeto.
            config.staticFiles.add("web", Location.EXTERNAL);
            
        }).start(port);

        QueryController queryController = new QueryController();

        // Mapeamento das Rotas da API
        ExportController exportController = new ExportController();
        app.post("/api/export", exportController::exportBlock);
        
        app.post("/api/query", queryController::executeQuery);
        
        app.get("/api/export/all/{device}", exportController::exportAllDeviceBlocks);
        
        app.post("/api/export/selected", exportController::exportSelectedBlocks);
        
        app.get("/api/health", ctx -> ctx.json("{\"status\": \"SLiCER API is running\"}"));

        log.info("SLiCER API Server rodando na porta {}", port);
    }

    public void stop() {
        if (app != null) {
            app.stop();
            log.info("SLiCER API Server parado.");
        }
    }
}