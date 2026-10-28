package br.edu.utfpr.slicer.index;

import br.edu.utfpr.slicer.core.ParsedEvent;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LuceneBlockIndexer {
    private static final Logger log = LoggerFactory.getLogger(LuceneBlockIndexer.class);
    private final IndexWriter writer;

    public LuceneBlockIndexer(Path activeIndexDir) throws IOException {
        Directory fsDirectory = FSDirectory.open(activeIndexDir);
        StandardAnalyzer analyzer = new StandardAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        this.writer = new IndexWriter(fsDirectory, config);
    }

    public void addEvent(ParsedEvent event, String eventId) throws IOException {
        Document doc = new Document();
        
        // 1. Guarda o ID original
        doc.add(new StringField("eventId", eventId, Field.Store.YES));
        
        // 2. A MÁGICA: Guarda o log bruto (Para a API devolver pronto)
        doc.add(new StoredField("rawContent", event.rawLog()));
        
        // 3. A MÁGICA 2: Indexa o log bruto inteiro para buscas de texto livre (ex: "all": "TCP")
        doc.add(new TextField("all_fields", event.rawLog(), Field.Store.NO));
        
        // 4. Indexa os campos parseados individualmente
        event.fields().forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                doc.add(new StringField(key, value, Field.Store.NO));
            }
        });
        
        writer.addDocument(doc);
    }

    public void commit() throws IOException {
        writer.commit(); // Persistência a cada 300 eventos (Cria os mini-segmentos)
    }

    public void close() throws IOException {
        if (writer != null && writer.isOpen()) {
            
            // --- A MÁGICA DA LIMPEZA E OTIMIZAÇÃO ---
            // Força a fusão de todos os pequenos segmentos (_1a, _v, etc) 
            // em exatamente 1 único segmento final otimizado.
            log.debug("Executando Force Merge no Lucene para otimização do bloco...");
            writer.forceMerge(1); 
            writer.commit(); // Salva o resultado da fusão
            
            writer.close(); // Fecha o arquivo de forma limpa
        }
    }
}