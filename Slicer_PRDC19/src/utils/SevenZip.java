package utils;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SevenZip {
    SevenZOutputFile out;
    
    public SevenZip(Path name) {
        try {
            out = new SevenZOutputFile(name.toFile());
        } catch (IOException ex) {
            Logger.getLogger(SevenZip.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void compress(File name, File... files) throws IOException {
        for (File file : files){
            addToArchiveCompression(file, ".");
        }
    }

    public void decompress(String in, File destination) {
        try {
            SevenZFile sevenZFile = new SevenZFile(new File(in));
            SevenZArchiveEntry entry;
            while ((entry = sevenZFile.getNextEntry()) != null){
                if (entry.isDirectory()){
                    continue;
                }
                File curfile = new File(destination, entry.getName());
                File parent = curfile.getParentFile();
                if (!parent.exists()) {
                    parent.mkdirs();
                }
                FileOutputStream out = new FileOutputStream(curfile);
                byte[] content = new byte[(int) entry.getSize()];
                sevenZFile.read(content, 0, content.length);
                out.write(content);
                out.close();
                System.out.println("File " + curfile + " was decompressed.");
            }
        } catch (IOException ex) {
            Logger.getLogger(SevenZip.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    /**
     * 
     * @param file File to be compressed.
     * @param dir can be used to set the directory entry of a file. If empty (i.e. ""), only the name will be used.
     * @throws IOException 
     */
    public void addToArchiveCompression(File file, String dir) {
        String name;
        if(dir.isEmpty()){
            name = file.getName();
        }else
            name = dir + File.separator + file.getName();
        
        if (file.isFile() && !file.isHidden()){
            try {
                SevenZArchiveEntry entry = out.createArchiveEntry(file, name);
                out.putArchiveEntry(entry);
                
                FileInputStream in = new FileInputStream(file);
                byte[] b = new byte[1024];
                int count = 0;
                while ((count = in.read(b)) > 0) {
                    out.write(b, 0, count);
                }
                out.closeArchiveEntry();
                //System.out.println("File " + file + " was added.");
            } catch (IOException ex) {
                Logger.getLogger(SevenZip.class.getName()).log(Level.SEVERE, null, ex);
            }
        } else if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null){
                for (File child : children){
                    addToArchiveCompression(child, name);
                }
            }
        } else {
            System.out.println(file.getName() + " is not supported or hidden");
        }
    }
    
    // Used to add a new Event to a compressed destination file.
    public void sevenZipCompress(String[] evt) {
        try {
            String name = evt[0];
            File file = new File(evt[1]);
            
            SevenZArchiveEntry entry = out.createArchiveEntry(file, name);
            out.putArchiveEntry(entry);
            out.write(evt[1].getBytes());
            out.closeArchiveEntry();
            //System.out.println("File " + file + " was added.");
        } catch (IOException ex) {
            Logger.getLogger(SevenZip.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    // Close the SevenZOutputFile after all necessary files are added.
    public void closeSevenZOutputFile(){
        try {
            out.close();
        } catch (IOException ex) {
            Logger.getLogger(SevenZip.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public static void sevenZipCompressCommandLine(String src, String dst){
        try {
            Runtime rt = Runtime.getRuntime();
            Process prc = rt.exec("7z a " + dst + " " + src);
        } catch (IOException ex) {
            Logger.getLogger(Zpaq.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public static void sevenZipUncompressCommandLine(String src, String dst){
        try {
            Runtime rt = Runtime.getRuntime();
            Process prc = rt.exec("7z x " + src + " " + dst);
        } catch (IOException ex) {
            Logger.getLogger(SevenZip.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public static void main(String args[]){
//        SevenZip sevenZip = new SevenZip(Paths.get(
//                "/home/adrianoska/Desktop/DC.edpcg.local_2017-11-17T23:22:12_2017-11-17T23:40:31.7z"));
//        sevenZip.addToArchiveCompression(new File(
//                "/home/adrianoska/Desktop/DC.edpcg.local_2017-11-17T23:22:12_2017-11-17T23:40:31/"), "");
//        sevenZip.closeSevenZOutputFile();

        SevenZip.sevenZipCompressCommandLine(
                "/home/adrianoska/Desktop/DC.edpcg.local_2017-11-17T23:22:12_2017-11-17T23:40:31", 
                "DC.7z");
    }
}
