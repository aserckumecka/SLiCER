package br.edu.utfpr.slicer.ingest;

/**
 * Contrato funcional para quem vai receber e processar os eventos brutos.
 * Posteriormente, o nosso Motor Principal (que tem o Parser e o Bloom Filter) 
 * implementará esta interface.
 */
@FunctionalInterface
public interface EventConsumer {
    void consume(String rawEvent);
}