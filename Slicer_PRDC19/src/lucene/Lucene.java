/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package lucene;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 *
 * @author aserckumecka
 */
public class Lucene {

    Path index = null;
    IndexWriter iw = null;

    public Lucene(Path index) {
        this.index = index;
        createIndex();
    }

    public void createIndex() {
        try {
            Directory dir = FSDirectory.open(index);
            Analyzer analyzer = new StandardAnalyzer();
            IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
            iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            iw = new IndexWriter(dir, iwc);
        } catch (IOException ex) {
            System.out.println(" caught a " + ex.getClass() + "\n with message: "
                    + ex.getMessage());
        }
    }

    public void indexEvent(List<String[]> evtList) {
        try {
            List<Document> docs = new ArrayList<>();

            for (int i = 0; i < evtList.size(); i++) {
                Document doc = new Document();
                Field evtName = new StringField("evtName", evtList.get(i)[0], Field.Store.YES);
                doc.add(evtName);
                Field contents = new TextField("contents", evtList.get(i)[1], Field.Store.YES);
                doc.add(contents);
                docs.add(doc);
            }
            //System.out.println("adding " + file);
            iw.addDocuments(docs);
        } catch (IOException ex) {
            Logger.getLogger(Lucene.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void closeIndex() {
        try {
            iw.close();
        } catch (IOException ex) {
            Logger.getLogger(Lucene.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private void delFileIndex(String file, String indexPath) {
        try {
            File fpath = new File(file);
            Directory dir = FSDirectory.open(Paths.get(indexPath));
            Analyzer analyzer = new StandardAnalyzer();

            IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
            //iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            IndexWriter iw = new IndexWriter(dir, iwc);

            iw.deleteDocuments(new Term("path", fpath.toString()));

            iw.commit();
            iw.close();

            System.out.println("The File " + fpath.getName() + " was deleted from index.");

        } catch (IOException ex) {
            System.out.println(" caught a " + ex.getClass() + "\n with message: "
                    + ex.getMessage());
        }
    }

    public static void index(Path docPath, Path indexPath) {
        //System.out.println("Indexing to directory '" + indexPath + "'...");

        try {
            Directory dir = FSDirectory.open(indexPath);
            Analyzer analyzer = new StandardAnalyzer();

            IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
            //iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            //iwc.setRAMBufferSizeMB(256.0);
            IndexWriter iw = new IndexWriter(dir, iwc);

            if (Files.isDirectory(docPath)) {
                Files.walkFileTree(docPath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        try {
                            indexDoc(iw, file, attrs.lastModifiedTime().toMillis());
                        } catch (IOException ignore) {
                            // don't index files that can't be read.
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                indexDoc(iw, docPath, Files.getLastModifiedTime(docPath).toMillis());
            }
            iw.close();

        } catch (IOException ex) {
            System.out.println(" caught a " + ex.getClass() + "\n with message: "
                    + ex.getMessage());
        }
    }

    private static void indexDoc(IndexWriter writer, Path file, long lastModified) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            // make a new, empty document
            Document doc = new Document();
            List<Document> docs = new ArrayList<>();

            String evtName = file.toFile().getName();
            Field eventid = new StringField("eventid", evtName, Field.Store.YES);
            doc.add(eventid);

            doc.add(new LongPoint("modified", lastModified));

            doc.add(new TextField("contents", new BufferedReader(new InputStreamReader(stream,
                    StandardCharsets.UTF_8))));

            if (writer.getConfig().getOpenMode() == IndexWriterConfig.OpenMode.CREATE_OR_APPEND) {
                //System.out.println("adding " + file);
                writer.addDocument(doc);
                writer.addDocuments(docs);
            } else {
                System.out.println("updating " + file);
                writer.updateDocument(new Term("eventid", evtName), doc);
            }
        }
    }

    /**
     * @param queryString
     * @param indexPath
     */
    public static List<String> searchByTerm(String queryString, Path indexPath) {
        String field = "contents";
        int hitsPerPage = 10000;
        List<String> events = new ArrayList<>();
        try {
            IndexReader reader = DirectoryReader.open(FSDirectory.open(indexPath));
            IndexSearcher searcher = new IndexSearcher(reader);
            Analyzer analyzer = new StandardAnalyzer();
            QueryParser parser = new QueryParser(field, analyzer);
            Query query = parser.parse(queryString);
            System.out.println("Searching for: " + query.toString(field));

            TopDocs results = searcher.search(query, 5 * hitsPerPage);
            ScoreDoc[] hits = results.scoreDocs;

            System.out.println(results.totalHits + " total matching documents");

            for (int i = 0; i < results.totalHits; i++) {
                Document doc = searcher.doc(hits[i].doc);
                String eventid = doc.get("eventid");
                if (eventid != null) {
                    events.add(eventid);
                }
            }
            return events;
            //}
            //reader.close();
        } catch (IOException | ParseException ex) {
            Logger.getLogger(Lucene.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
    }

    // 
    public static List<String> search(Query query, File matchIndexBlocks) {
        String field = "contents";
        int hitsPerPage = 10000;
        List<String> events = new ArrayList<>();

        try {
            
                IndexReader reader = DirectoryReader.open(FSDirectory.open(matchIndexBlocks.toPath()));
                IndexSearcher searcher = new IndexSearcher(reader);
                //Analyzer analyzer = new StandardAnalyzer();
                //QueryParser parser = new QueryParser(field, analyzer);
                //Query query = parser.parse(queryString);
                System.out.println("Searching for: " + query.toString(field));

                TopDocs results = searcher.search(query, 5 * hitsPerPage);
                ScoreDoc[] hits = results.scoreDocs;
                
                System.out.println(results.totalHits + " total matching documents");

                for (int i = 0; i < results.totalHits; i++) {
                    Document doc = searcher.doc(hits[i].doc);
                    String eventid = doc.get("eventid");
                    if (eventid != null) {
                        String temp = matchIndexBlocks.getName();
                        temp = temp.substring(0, temp.indexOf("__")) + "/" + eventid;
                        events.add(temp);
                    }
                }
            if(results.totalHits == 0){
                return null;
            }else
                return events;
            
        } catch (Exception ex) {
            Logger.getLogger(Lucene.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
    }

    private static void oldSearchByTerm(String queryString, Path indexPath) {
        String field = "contents";
        int hitsPerPage = 10000;

        try {
            IndexReader reader = DirectoryReader.open(FSDirectory.open(indexPath));
            IndexSearcher searcher = new IndexSearcher(reader);
            Analyzer analyzer = new StandardAnalyzer();
            QueryParser parser = new QueryParser(field, analyzer);
            Query query = parser.parse(queryString);
            System.out.println("Searching for: " + query.toString(field));

            TopDocs results = searcher.search(query, 5 * hitsPerPage);
            ScoreDoc[] hits = results.scoreDocs;

            System.out.println(results.totalHits + " total matching documents");

            for (int i = 0; i < results.totalHits; i++) {
                Document doc = searcher.doc(hits[i].doc);
                String path = doc.get("evtName");
                if (path != null) {
                    System.out.println((i + 1) + ". " + path);
                    String title = doc.get("title");
                    if (title != null) {
                        System.out.println("   Title: " + doc.get("title"));
                    }
                } else {
                    System.out.println((i + 1) + ". " + "No path for this document");
                }
            }
            //}
            //reader.close();
        } catch (Exception ex) {
            Logger.getLogger(Lucene.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private static void searchByWildcard(String queryString, String indexPath) {

    }

    private static void searchByRange(String queryString, String indexPath) {

    }

    private static void searchByPrefix(String queryString, String indexPath) {

    }

    private static void searchByFuzzy(String queryString, String indexPath) {

    }

    public static void main(String[] args) {
        List<String> temp = Lucene.searchByTerm("agt=10.100.1.100 AND COLECT.edpcg.local",
                Paths.get("/Users/aserckumecka/_cloud/DC.edpcg.local_2017-11-17T17:58:43_2017-11-17T18:58:44__lr"));

        for (int i = 0; i < temp.size(); i++) {
            System.out.println(temp.get(i));
        }
    }
}
