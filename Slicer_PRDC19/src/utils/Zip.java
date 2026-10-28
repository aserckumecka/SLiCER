/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 * Code example getted from https://www.journaldev.com/966/java-gzip-example-compress-decompress-file
 */
package utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 *
 * @author adrianoska
 */
public class Zip {
    ZipOutputStream out = null;
    
    public Zip(File zipFile){
        try {
            out = new ZipOutputStream(new FileOutputStream(zipFile));
        } catch (FileNotFoundException ex) {
            Logger.getLogger(Zip.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public static synchronized List<File> eventList(String path) {
        try {
            List<File> filesInFolder = Files.walk(Paths.get(path))
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .collect(Collectors.toList());

            String[] files = new String[filesInFolder.size()];

            //List<String> list = Collections.sort(filesInFolder);;
            for (int i = 0; i < filesInFolder.size(); i++) {
                if (filesInFolder.get(i).isHidden()) {
                    filesInFolder.get(i).delete();
                }
            }

            for (int i = 0; i < filesInFolder.size(); i++) {
                files[i] = filesInFolder.get(i).toString();
            }

            Arrays.sort(files);
            filesInFolder.clear();

            for (int i = 0; i < files.length; i++) {
                File file = new File(files[i]);
                filesInFolder.add(file);
            }

            return filesInFolder;
        } catch (IOException ex) {
            Logger.getLogger(Zip.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
    }
    
    /**
     * Zip the contents of the directory, and save it in the zipfile
     * @param dir
     * @param zipfile
     */
    public static void compressDirectory(File dir, File zipfile){
        ZipOutputStream out = null;
        try {
            // Check that the directory is a directory, and get its contents
            if (!dir.isDirectory()) {
                throw new IllegalArgumentException("Compress: not a directory:  " + dir);
            }
            String[] entries = dir.list();
            byte[] buffer = new byte[4096]; // Create a buffer for copying
            int bytes_read;
            // Create a stream to compress data and write it to the zipfile
            out = new ZipOutputStream(new FileOutputStream(zipfile));
            // Loop through all entries in the directory
            for (int i = 0; i < entries.length; i++) {
                File f = new File(dir, entries[i]);
                if (f.isDirectory()) {
                    continue; // Don't zip sub-directories
                }
                FileInputStream in = new FileInputStream(f); // Stream to read file
                ZipEntry entry = new ZipEntry(f.getPath()); // Make a ZipEntry
                out.putNextEntry(entry); // Store entry
                while ((bytes_read = in.read(buffer)) != -1) // Copy bytes
                {
                    out.write(buffer, 0, bytes_read);
                }
                in.close(); // Close input stream
            }   // When we're done with the whole loop, close the output stream
            out.close();
        } catch (FileNotFoundException ex) {
            Logger.getLogger(Zip.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Gzip.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Zip the contents of the directory, and save it in the zipfile
     */
    public void addEventToCompress(String[] evt) {
        try {
            ZipEntry entry = new ZipEntry(evt[0]); // Make a ZipEntry
            out.putNextEntry(entry); // Store entry
            out.write(evt[1].getBytes());
        } catch (FileNotFoundException ex) {
            Logger.getLogger(Zip.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Zip.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public void closeZipOutputStream(){
        try {
            out.close();
        } catch (IOException ex) {
            Logger.getLogger(Zip.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    
    public static void uncompressDirectory(File src, File dst){
        
    }
    
    
    public static void main(String[] args) {
        try {
            /* // Compressing only one folder.
            Gzip.compressGzipDirectory(new File("/media/adrianoska/SLiCER/checkpoint-BF/10.100.250.2/index"),
                    new File("/media/adrianoska/SLiCER/checkpoint-BF/10.100.250.2/index"+".gz"));
            */
            
            
            /* // Compressing a file (block of events).
            Gzip.compressGzipFile(
                    new File("/media/adrianoska/SLiCER/checkpoint-BF/10.100.250.2/data/normal/10.100.250.2_2017-11-17T14:49:21_2017-11-17T14:50:16.slc"),
                    new File("/media/adrianoska/SLiCER/checkpoint-BF/10.100.250.2/data/normal/10.100.250.2_2017-11-17T14:49:21_2017-11-17T14:50:16.slc"));
            */
            
            
            // Compressing a list of files, separately.
            List<File> files = Zip.eventList("/media/adrianoska/SLiCER/checkpoint-LC-FULL/10.100.250.2/data");
            for (int i = 0; i < files.size(); i++) {
                File tmp = new File(files.get(i).toString().replace("data", "data_GZIP"));
                File fparent = new File(tmp.getParent());
                fparent.mkdirs();
                //compressGzipFile(files.get(i), tmp);
            }

            
            /*// Compressing a list of folders, separately.
            File filedir = new File("/media/adrianoska/SLiCER/checkpoint-BF/10.100.250.2/index");
            File[] fileArray = filedir.listFiles();
            for (int i = 0; i < fileArray.length; i++) {
                File tmp = new File(fileArray[i].toString().replace("index", "index_GZIP"));                
                File fparent = new File(tmp.getParent());
                fparent.mkdirs();
                compressGzipDirectory(fileArray[i], new File(tmp.toString() + ".gz"));
            }
             */
        } catch (Exception ex) {
            Logger.getLogger(Zip.class.getName()).log(Level.SEVERE, null, ex);
        }

    }
}
