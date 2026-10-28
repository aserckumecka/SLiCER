package br.edu.utfpr.slicer.ingest;

import br.edu.utfpr.slicer.core.ParsedEvent;
import br.edu.utfpr.slicer.parser.EventParser;
import br.edu.utfpr.slicer.config.AppConfig;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventRouter implements EventConsumer {
    private static final Logger log = LoggerFactory.getLogger(EventRouter.class);

    // Agora recebemos uma lista de parsers (CEF, JSON, etc)
    private final List<EventParser> parsers;
    private final AppConfig.Slicer config;
    
    private final ConcurrentMap<String, DeviceBlockManager> deviceManagers = new ConcurrentHashMap<>();

    public EventRouter(List<EventParser> parsers, AppConfig.Slicer config) {
        this.parsers = parsers;
        this.config = config;
    }

    @Override
    public void consume(String rawEvent) {
        try {
            EventParser selectedParser = null;
            
            // Procura o primeiro parser que consegue ler este formato
            for (EventParser parser : parsers) {
                if (parser.canParse(rawEvent)) {
                    selectedParser = parser;
                    break;
                }
            }

            if (selectedParser == null) {
                log.warn("Formato não suportado para o log: {}", rawEvent);
                return;
            }

            ParsedEvent event = selectedParser.parse(rawEvent);
            String deviceId = event.deviceId();

            // Repassa a configuração completa para o gerenciador daquele device
            DeviceBlockManager manager = deviceManagers.computeIfAbsent(
                deviceId, 
                id -> new DeviceBlockManager(id, config)
            );

            manager.addEvent(event);

        } catch (Exception e) {
            log.error("Erro ao rotear o evento", e);
        }
    }
    
    /**
     * Força o fechamento de todos os blocos abertos em todos os devices.
     * Fundamental para garantir o Graceful Shutdown.
     */
    public void flushAll() {
        log.info("Iniciando flush de todos os DeviceBlockManagers...");
        deviceManagers.values().forEach(manager -> {
            try {
                // Chama o método de desligamento/flush que você já tem no DeviceBlockManager
                manager.shutdown(); 
            } catch (Exception e) {
                log.error("Erro ao fazer flush/shutdown no manager do device", e);
            }
        });
    }

    public void shutdown() {
        flushAll();
    }
}