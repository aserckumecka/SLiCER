/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package slicer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.swing.JOptionPane;

import bloom.BF;
import lucene.Lucene;
import parser.CefParser;
import utils.SevenZip;
import utils.Xz;
import utils.Zip;
import utils.Zpaq;

/**
 *
 * @author aserckumecka
 */
public class DevIndexer {

    Lock lock = new ReentrantLock(true);
    // Starting with constructor...
    CefParser cefp = null;
    //SysParser sysp = null;
    //JsnParser jsnp = null;
    //CsvParser csvp = null;
    String cache_path = new String();
    String cloud_path = new String();
    String dvchost = null; //hostname
    String dvc = null; // IP address
    String tsField = new String();
    String devName = new String();
    Path newBlockRawFileName = null;
    Path events2IndexFileName = null;
    int interval = 0;
    int blockSize = 0; // In megabytes.
    int bufferSize = 0;
    int bufferSizeUsed = 0;
    String compress = new String();
    String indexer = new String();
    String log_format = new String();
    LocalDateTime firstTS = null;
    LocalDateTime lastTS = null;
    LocalDateTime firstTSnow = null;
    LocalDateTime intervalTimer = null;
    DateTimeFormatter formatter;

    boolean verifEvent = false;

    // Event Buffer
    List<String[]> processedEvtBuffer = new ArrayList<>(); // Raw events already processed (plus its timestamp at the beginning).
    List<String[]> events2IndexBuffer = new ArrayList<>(); // Reduced events (after processing) used to index.
    List<String[]> fieldsExten2Index = new ArrayList<>(); // A list of fields and extensions used in all events of a block.
    int eventsCount = 0;

    Zip zip = null;
    SevenZip sevenZip = null;
    Xz xz = null;
    //Lucene lc = null;

    public DevIndexer(
            String devName,
            String log_format,
            String compress,
            String tsField,
            String cache_path,
            String cloud_path,
            int interval,
            int size,
            int bufferSize,
            String indexer) {

        this.devName = devName;
        this.log_format = log_format;
        this.compress = compress;
        this.tsField = tsField;
        this.cache_path = cache_path;
        this.cloud_path = cloud_path;
        this.interval = interval;
        this.blockSize = size;
        this.bufferSize = bufferSize;
        this.indexer = indexer;

        this.newBlockRawFileName = Paths.get(cache_path + "/" + devName + "/data/newblockRaw.slc"); // processedEvtBuffer
        this.events2IndexFileName = Paths.get(cache_path + "/" + devName + "/data/events2Index.slc"); // events2IndexBuffer
        this.formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        firstTS = LocalDateTime.now();
        lastTS = LocalDateTime.now();
        firstTSnow = LocalDateTime.now();

        switch (log_format) {
            case "cef":
                cefp = new CefParser();
                break;
            case "sys":
                //sysp = new SyslogParser();
                break;
            case "csv":
                //csvp = new CsvParser();
                break;
            case "jsn":
                //jsnp = new JsnParser();
                break;
            default:
                break;
        }
    }

    public void process(String event, String[] fields, List<String> exten2Index) {

        lock.lock();
        try {
            //firstTSnow = LocalDateTime.now(); // Timestamp of the first event of the current block
            if (!event.isEmpty()) {
                processEvent(event, fields, exten2Index);
            } else {
                System.out.println("Verification event executed. Looking for unclosed blocks...");
                if (!verifEvent) {
                    verifEvent = true;
                    JOptionPane.showMessageDialog(null, "All events uploaded and processed!","Loading finished", JOptionPane.INFORMATION_MESSAGE);
                    
                }
            }
            verifySizeTimeExpiration();
        } finally {
            lock.unlock();
        }
    }

    private void verifySizeTimeExpiration() {
        // If the events2IndexBuffer is full, write this batch to a disk.
        if (processedEvtBuffer.size() >= bufferSize) {
            writeBuffersToDisk();
        }
        // If the block is full or time range is endded, generate a block with
        // the first timestamp and the last one.
        // When a new event arrives, processEvent() will verify and create a
        // new block, if it doesn't exist.
        if ((Files.exists(newBlockRawFileName) && ((bufferSizeUsed / 1024) / 1024) >= blockSize)
                || (firstTS.plusMinutes(interval).compareTo(lastTS) <= 0)
                || (firstTSnow.plusMinutes(interval).compareTo(LocalDateTime.now()) <= 0 && eventsCount > 0)) {
            writeBuffersToDisk();
            createBlockAndIndex();
        }
    }

    private void writeBuffersToDisk() {
        if (!Files.exists(newBlockRawFileName)) {
            newBlockRawFileName.toFile().mkdirs();
        }
        while (processedEvtBuffer.size() > 0) {
            try {
                String fileName = newBlockRawFileName.toString() + "/" + processedEvtBuffer.get(0)[0];
                byte[] peb = processedEvtBuffer.get(0)[1].getBytes();
                Files.write(Paths.get(fileName), peb, StandardOpenOption.CREATE);
                bufferSizeUsed += peb.length;
            } catch (IOException ex) {
                Logger.getLogger(DevIndexer.class.getName()).log(Level.SEVERE, null, ex);
            }
            processedEvtBuffer.remove(0);
        }

        if (indexer.equals("lr")) {
            if (!Files.exists(events2IndexFileName)) {
                events2IndexFileName.toFile().mkdirs();
            }

            while (events2IndexBuffer.size() > 0) {
                try {
                    String fileName = events2IndexFileName.toString() + "/" + events2IndexBuffer.get(0)[0];
                    byte[] e2iB = events2IndexBuffer.get(0)[1].getBytes();
                    Files.write(Paths.get(fileName), e2iB, StandardOpenOption.CREATE);
                } catch (IOException ex) {
                    Logger.getLogger(DevIndexer.class.getName()).log(Level.SEVERE, null, ex);
                }
                events2IndexBuffer.remove(0);
            }
        }
    }

    private void createBlockAndIndex() {
        bufferSizeUsed = 0;
        eventsCount = 0;

        String fTS = this.firstTS.format(formatter);
        String lTS = this.lastTS.format(formatter);

        //Creating the files in the Cache's folder.
        //Verify if newEvtBlockCache exists to avoid override the first.
        File evtBlockZipCache = new File(cache_path + "/" + devName + "/data/" + devName + "_" + fTS + "_" + lTS + "." + compress);
        if (evtBlockZipCache.exists()) {
            Random rand = new Random();
            int value = rand.nextInt(9);
            value++;
            fTS = fTS + ".10" + value;
            lTS = lTS + ".000";
            System.out.println("\n############################################################################# ");
            System.out.println("#########  " + evtBlockZipCache.getName());
            System.out.println("#############################################################################\n");
        }

        Path newEvtBlockCache = Paths.get(cache_path + "/" + devName + "/data/" + devName + "_"
                + fTS + "_" + lTS);
        Path newEvtBlockZipCache = Paths.get(cache_path + "/" + devName + "/data/" + devName + "_"
                + fTS + "_" + lTS + "." + compress);
        Path newIndexPathCache = Paths.get(cache_path + "/" + devName + "/index/" + devName + "_"
                + fTS + "_" + lTS + "__" + indexer);
        Path newIndexPathZipCache = Paths.get(cache_path + "/" + devName + "/index/" + devName + "_"
                + fTS + "_" + lTS + "__" + indexer + "." + compress);
        // Moving the temp file to the files in the cache...
        try {
            Files.move(
                    newBlockRawFileName,
                    newEvtBlockCache,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            Logger.getLogger(DevIndexer.class.getName()).log(Level.SEVERE, null, ex);
        }

        // Index...
        switch (indexer) {
            case "lf":
                Lucene.index(newEvtBlockCache, newIndexPathCache);
                break;
            case "lr":
                //Path newEvtBlockRed = createEvtBlockRed();
                //Lucene.index(newEvtBlockRed, newIndexPathCache);
                Lucene.index(events2IndexFileName, newIndexPathCache);
                break;
            case "bf":
                // Bloom Filter
                BF.feedBloom(newIndexPathCache, fieldsExten2Index); //fieldsExten2Index is used only for BF.
                break;
            default:
                break;
        }

        //Compressing algorithms
        switch (compress) {
            case "7z":
                SevenZip.sevenZipCompressCommandLine(
                        newEvtBlockCache.toString(),
                        newEvtBlockZipCache.toString()
                );
                SevenZip.sevenZipCompressCommandLine(
                        newIndexPathCache.toString(),
                        newIndexPathZipCache.toString()
                );
                break;
            case "tar.xz":
                Xz.encoder(newEvtBlockCache);
                Xz.encoder(newIndexPathCache);
                break;
            case "zip":
                Zip.compressDirectory(newEvtBlockCache.toFile(), newEvtBlockZipCache.toFile());
                Zip.compressDirectory(newIndexPathCache.toFile(), newIndexPathZipCache.toFile());
                break;
            case "zpaq":
                Zpaq.zpaqCompress(
                        newEvtBlockZipCache.toString(),
                        newEvtBlockCache.toString()
                );
                Zpaq.zpaqCompress(
                        newIndexPathZipCache.toString(),
                        newIndexPathCache.toString()
                );
                break;
            default:
                break;
        }

        //Creating the files in the Cloud's folder.
        Path newEvtBlockZipCloud = Paths.get(cloud_path + "/" + devName + "/data/" + devName + "_"
                + fTS + "_" + lTS + "." + compress);
        Path newIndexPathZipCloud = Paths.get(cloud_path + "/" + devName + "/index/" + devName + "_"
                + fTS + "_" + lTS + "__" + indexer + "." + compress);
        //Copying from Cache folder to Cloud folder...
        try {
            Files.copy(newEvtBlockZipCache, newEvtBlockZipCloud, StandardCopyOption.COPY_ATTRIBUTES);
            Files.copy(newIndexPathZipCache, newIndexPathZipCloud, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException ex) {
            Logger.getLogger(DevIndexer.class.getName()).log(Level.SEVERE, null, ex);
        }

        // Deleting the original data and index folders after be compressed.
        try {
            List<File> dataFiles = Files.walk(newEvtBlockCache)
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .collect(Collectors.toList());
            for (int i = 0; i < dataFiles.size(); i++) {
                Files.delete(dataFiles.get(i).toPath());
            }
            Files.delete(newEvtBlockCache);

            List<File> indexFiles = Files.walk(newIndexPathCache)
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .collect(Collectors.toList());

            for (int i = 0; i < indexFiles.size(); i++) {
                Files.delete(indexFiles.get(i).toPath());
            }
            Files.delete(newIndexPathCache);

            if (indexer.equals("lr")) {
                List<File> lrFiles = Files.walk(events2IndexFileName)
                        .filter(Files::isRegularFile)
                        .map(Path::toFile)
                        .collect(Collectors.toList());

                for (int i = 0; i < lrFiles.size(); i++) {
                    Files.delete(lrFiles.get(i).toPath());
                }
                Files.delete(events2IndexFileName);
            }
        } catch (IOException ex) {
            Logger.getLogger(DevIndexer.class.getName()).log(Level.SEVERE, null, ex);
        }

        fieldsExten2Index = new ArrayList<>();
        intervalTimer = LocalDateTime.now();
        firstTS = LocalDateTime.now();
        lastTS = LocalDateTime.now();
        System.out.println("Block  [" + newEvtBlockCache + "]  generated at " + intervalTimer);
    }

    private void processEvent(String event, String[] fields, List<String> exten2Index) {
        switch (log_format) {
            case "cef":
                String timestamp = "";
                String dtz = "";
                String eventId = "";
                String redEvent = ""; // In process event. Reduced event.
                //String[] fields = null;
                //fields = cefp.splitEvent(event);

                for (int i = 0; i < 7; i++) {
                    if (!fields[i].startsWith("#")) {
                        redEvent = redEvent + fields[i] + ";";

                        //Bloom Filter issues... only.
                        // It verifies if fieldsExten2Index already have the uncommented header field.
                        // It also adds the exclusive values (without replicas) to create the bloom filters.
                        String[] nameValue = fields[i].split("=");
                        if (fieldsExten2Index.isEmpty()) { // Add the first because it needs at least 1 to compare.
                            fieldsExten2Index.add(nameValue);
                        } else {
                            boolean found = false;
                            for (int j = 0; j < fieldsExten2Index.size(); j++) {
                                if (fieldsExten2Index.get(j)[0].equals(nameValue[0])) {
                                    found = true;
                                    if (!fieldsExten2Index.get(j)[1].contains(nameValue[1])) {
                                        fieldsExten2Index.get(j)[1] += ";" + nameValue[1];
                                    }
                                    break;
                                }
                            }
                            if (!found) {
                                fieldsExten2Index.add(nameValue);
                            }
                        }
                        //System.out.println(nameValue[0] + "  header field and value:  " + nameValue[1]);
                    }
                }
                //List<String> exten2Index = null;
                //exten2Index = cefp.splitExtenByField(fields[7]); // field 7 is all extensions of CEF together.
                for (int i = 0; i < exten2Index.size(); i++) {
                    String[] temp = exten2Index.get(i).split("=");
                    if (temp[0].equals("#"+tsField) || temp[0].equals(tsField)) { // i.e. timestamp = rt.
                        timestamp = temp[1];   
                    } else if (temp[0].equals("#dtz") || temp[0].equals("dtz")) { // i.e. dtz = Europe/London.
                        dtz = temp[1];
                    } else if (temp[0].equals("#eventId") || temp[0].equals("eventId")) {
                        eventId = temp[1];
                    } //else if ((temp[0].equals(tsField) && cefp.rt)
                    //        || (temp[0].equals("dtz") && cefp.dtz)
                    //        || (temp[0].equals("eventId") && cefp.eventId)) {
                    // add this exten too.
                    //****************************************
                    //} 
                    else {
                        redEvent = redEvent + exten2Index.get(i) + ";";

                        //Bloom Filter issues... only.
                        // It verifies if fieldsExten2Index already have the uncommented extension field.
                        // It also adds the exclusive values (without replicas) to create the bloom filters.
                        if (fieldsExten2Index.isEmpty()) {
                            fieldsExten2Index.add(temp);
                        } else {
                            boolean found = false;
                            for (int j = 0; j < fieldsExten2Index.size(); j++) {
                                if (fieldsExten2Index.get(j)[0].equals(temp[0])) {
                                    found = true;
                                    if (!fieldsExten2Index.get(j)[1].contains(temp[1])) {
                                        fieldsExten2Index.get(j)[1] += ";" + temp[1];
                                    }
                                    break;
                                }
                            }
                            if (!found) {
                                fieldsExten2Index.add(temp);
                            }
                        }
                    }
                    //System.out.println(temp[0] + "  extension field and value:  " + temp[1]);
                }
                redEvent = redEvent.substring(0, redEvent.length() - 1); // Get off the latter ";"
                LocalDateTime ldt = getLDT(timestamp, dtz);
                // If a new block is going to be created, add this timestamp as the first one.
                if (eventsCount == 0) {
                    firstTS = ldt;
                    firstTSnow = LocalDateTime.now();
                    lastTS = ldt;
                    // If the latter event has its TS smaller than firstTS, update it.
                } else if (ldt.compareTo(firstTS) < 0) {
                    firstTS = ldt;
                }
                if (ldt.compareTo(lastTS) > 0) { // If the actual event ts is smaller than firstTS
                    lastTS = ldt;
                }
                if (!indexer.equals("lf")) {
                    String[] evtRed = new String[2];
                    evtRed[0] = eventId;
                    //evtRed[1] = ldt + ";" + redEvent;
                    evtRed[1] = redEvent + "\n";
                    events2IndexBuffer.add(evtRed);
                }   // 
                String[] evtFull = new String[2];
                evtFull[0] = eventId;
                //evtFull[1] = ldt + ";" + rawEvent;
                evtFull[1] = event + "\n";
                processedEvtBuffer.add(evtFull);
                // At each new processed event, it increment eventsCount.
                eventsCount++;
                break;
            //sysp
            case "sys":
                break;
            //csvp
            case "csv":
                break;
            //jsnp
            case "jsn":
                break;
            default:
                break;
        }
    }

    private LocalDateTime getLDT(String timestamp, String dtz) {
        long ts = Long.valueOf(timestamp);
        Instant instant = Instant.ofEpochMilli(ts);
        LocalDateTime evtTS;
        if (dtz.isEmpty()) {
            evtTS = instant.atZone(ZoneId.systemDefault()).toLocalDateTime();
        } else {
            ZoneId id = ZoneId.of(dtz);
            evtTS = instant.atZone(id).toLocalDateTime();
        }
        return evtTS;
    }

}
