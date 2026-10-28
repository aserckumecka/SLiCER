package br.edu.utfpr.slicer.parser;

import br.edu.utfpr.slicer.core.ParsedEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CefParser implements EventParser {

    // Regex pré-compilada para performance.
    // Captura os 7 campos do cabeçalho do CEF separados por pipe (|) e isola a extensão.
    private static final Pattern CEF_HEADER_PATTERN = Pattern.compile(
        "^CEF:\\d+\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|(.*)$"
    );

    @Override
    public boolean canParse(String rawEvent) {
        return rawEvent != null && rawEvent.trim().startsWith("CEF:");
    }

    @Override
    public ParsedEvent parse(String rawEvent) {
        if (!canParse(rawEvent)) {
            throw new IllegalArgumentException("Formato de log inválido para CefParser.");
        }

        Matcher matcher = CEF_HEADER_PATTERN.matcher(rawEvent);
        Map<String, String> fields = new HashMap<>();

        if (matcher.find()) {
            // Extraindo o cabeçalho
            fields.put("devvendor", matcher.group(1));
            fields.put("devproduct", matcher.group(2));
            fields.put("devversion", matcher.group(3));
            fields.put("deveventclid", matcher.group(4));
            fields.put("eventname", matcher.group(5));
            fields.put("severity", matcher.group(6));

            // Extraindo as extensões (chave=valor)
            String extensionsPart = matcher.group(7);
            parseExtensions(extensionsPart, fields);
        }

        // Determinar o Device ID (dvchost tem prioridade sobre dvc, como no seu design original)
        String deviceId = fields.getOrDefault("dvchost", fields.getOrDefault("dvc", "unknown_device"));
        
        // Determinar o Timestamp (CEF usa 'rt' por padrão, mas pode haver flexibilidade)
        String timestampStr = fields.getOrDefault("rt", String.valueOf(System.currentTimeMillis()));
        long epochMillis = parseTimestamp(timestampStr);

        return new ParsedEvent(deviceId, timestampStr, epochMillis, rawEvent, fields);
    }

    /**
     * Método seguro para quebrar as extensões do CEF, evitando problemas com 
     * campos que contenham o sinal '=' no meio do valor.
     */
    private void parseExtensions(String extensionsPart, Map<String, String> fields) {
        if (extensionsPart == null || extensionsPart.isBlank()) return;

        // Quebra pelo espaço, mas precisamos garantir que não vamos quebrar no meio de um valor.
        String[] tokens = extensionsPart.split(" ");
        String currentKey = null;
        StringBuilder currentValue = new StringBuilder();

        for (String token : tokens) {
            int equalsIndex = token.indexOf("=");
            if (equalsIndex > 0) {
                // Se já tínhamos uma chave sendo construída, salvamos antes de iniciar a próxima
                if (currentKey != null) {
                    fields.put(currentKey, currentValue.toString().trim());
                }
                currentKey = token.substring(0, equalsIndex);
                currentValue = new StringBuilder(token.substring(equalsIndex + 1));
            } else {
                // É continuação do valor anterior que continha espaço
                if (currentKey != null) {
                    currentValue.append(" ").append(token);
                }
            }
        }
        // Salvar a última chave processada
        if (currentKey != null) {
            fields.put(currentKey, currentValue.toString().trim());
        }
    }

    private long parseTimestamp(String rt) {
        try {
            return Long.parseLong(rt);
        } catch (NumberFormatException e) {
            // Em caso de formatação ISO no campo rt, trataremos aqui futuramente.
            return System.currentTimeMillis();
        }
    }
}