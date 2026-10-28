package br.edu.utfpr.slicer.parser;

import br.edu.utfpr.slicer.core.ParsedEvent;

/**
 * Contrato padrão para todos os parsers do SLiCER.
 */
public interface EventParser {
    
    /**
     * Identifica se este parser consegue processar a linha de log recebida.
     */
    boolean canParse(String rawEvent);

    /**
     * Transforma a string crua em um objeto estruturado ParsedEvent.
     */
    ParsedEvent parse(String rawEvent);
}