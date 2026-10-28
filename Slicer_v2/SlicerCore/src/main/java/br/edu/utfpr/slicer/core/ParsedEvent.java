package br.edu.utfpr.slicer.core;

import java.util.Map;

/**
 * Representa um evento padronizado dentro do SLiCER após passar pelo Parser.
 * Records já criam construtores, equals, hashCode e toString automaticamente.
 */
public record ParsedEvent(
    String deviceId,         // Ex: dvchost ou dvc
    String timestamp,        // O timestamp original do evento
    long epochMillis,        // O timestamp convertido para facilitar busca no Lucene
    String rawLog,           // O log cru original
    Map<String, String> fields // Todos os campos extraídos e mapeados (chave=valor)
) {}