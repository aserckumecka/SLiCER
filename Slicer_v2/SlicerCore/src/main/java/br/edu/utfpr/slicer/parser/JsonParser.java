package br.edu.utfpr.slicer.parser;

import br.edu.utfpr.slicer.core.ParsedEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public class JsonParser implements EventParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public boolean canParse(String rawEvent) {
        return rawEvent != null && rawEvent.trim().startsWith("{");
    }

    @Override
    public ParsedEvent parse(String rawEvent) {
        try {
            Map<String, String> fields = MAPPER.readValue(rawEvent, new TypeReference<Map<String, String>>() {});
            
            // Extração de metadados padrão do SLiCER
            String deviceId = fields.getOrDefault("dvc", fields.getOrDefault("dvchost", "unknown_json_device"));
            String timestampStr = fields.getOrDefault("rt", String.valueOf(System.currentTimeMillis()));
            
            long epochMillis;
            try {
                epochMillis = Long.parseLong(timestampStr);
            } catch (NumberFormatException e) {
                epochMillis = System.currentTimeMillis();
            }

            return new ParsedEvent(deviceId, timestampStr, epochMillis, rawEvent, fields);
        } catch (Exception e) {
            throw new IllegalArgumentException("Erro ao processar JSON: " + e.getMessage());
        }
    }
}