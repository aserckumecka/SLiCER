/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZInputStream;
import org.tukaani.xz.XZOutputStream;
import org.xeustechnologies.jtar.TarEntry;
import org.xeustechnologies.jtar.TarInputStream;
import org.xeustechnologies.jtar.TarOutputStream;
import slicer.Slicer;

/**
 *
 * @author adrianoska
 */
public class Xz {

    TarOutputStream outputStream;
    XZOutputStream xzOut;
    Path dir;

    public Xz(Path dir) {
        this.dir = dir;
    }

    //old version receiving the path in constructor method.
    public void encoder1() {
        try {
            outputStream = new TarOutputStream(new BufferedOutputStream(
                    new FileOutputStream(dir.toString())));

            List<File> filesInFolder = Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .collect(Collectors.toList());

            TarEntry entry = null;
            FileInputStream fis = null;

            for (int i = 0; i < filesInFolder.size(); i++) {
                entry = new TarEntry(filesInFolder.get(i), filesInFolder.get(i).getName());
                outputStream.putNextEntry(entry);
                fis = new FileInputStream(filesInFolder.get(i));
                int n;
                byte[] readBuffer = new byte[1024];
                while ((n = fis.read(readBuffer)) > 0) {
                    outputStream.write(readBuffer, 0, n);
                }
            }

            fis = new FileInputStream(dir.toFile());
            FileOutputStream fos = new FileOutputStream(dir + ".xz");
            xzOut = new XZOutputStream(fos, new LZMA2Options());
            int n;
            byte[] readBuffer = new byte[4096];

            while ((n = fis.read(readBuffer)) > 0) {
                xzOut.write(readBuffer, 0, n);
            }
            close();
        } catch (IOException ex) {
            Logger.getLogger(Xz.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    // Actual version in use.
    public static void encoder(Path dir) {
        try {
            FileOutputStream fos = new FileOutputStream(dir + ".tar.xz");
            XZOutputStream xzOut = new XZOutputStream(fos, new LZMA2Options());
            TarOutputStream outputStream = new TarOutputStream(new BufferedOutputStream(
                    xzOut));

            List<File> filesInFolder = Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .collect(Collectors.toList());

            TarEntry entry = null;
            FileInputStream fis = null;

            for (int i = 0; i < filesInFolder.size(); i++) {
                entry = new TarEntry(filesInFolder.get(i), filesInFolder.get(i).getName());
                outputStream.putNextEntry(entry);
                fis = new FileInputStream(filesInFolder.get(i));
                int n;
                byte[] readBuffer = new byte[1024];
                while ((n = fis.read(readBuffer)) > 0) {
                    outputStream.write(readBuffer, 0, n);
                }
            }
            outputStream.close();
            xzOut.finish();
            xzOut.close();
        } catch (IOException ex) {
            Logger.getLogger(Xz.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void close() {
        try {
            outputStream.close();
            xzOut.finish();
            xzOut.close();
        } catch (IOException ex) {
            Logger.getLogger(Xz.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    public static void decoder(File file, File newDst) {
        try {
            FileInputStream fin = new FileInputStream(file);
            BufferedInputStream in = new BufferedInputStream(fin);
            //File newDst = new File(dst + "/" + file.getName().substring(0, file.getName().length() - 7));
            File fileout = new File(newDst.getParent() + "/temp");
            FileOutputStream out = new FileOutputStream(fileout);

            XZInputStream xzIn = new XZInputStream(in);
            final byte[] buffer = new byte[8192];
            int n = 0;
            while (-1 != (n = xzIn.read(buffer))) {
                out.write(buffer, 0, n);
            }
            out.close();
            xzIn.close();
            untar(fileout, newDst);
        } catch (Exception ex) {
            Logger.getLogger(Xz.class.getName()).log(Level.SEVERE, null, ex);

        }
    }

    public static void untar(File src, File newDst) {
        try {
            //File dir = new File(newDst.getParent() + "/" + src.getName().substring(0, file.getName().length() - 4));
            if (!newDst.exists()) {
                newDst.mkdir();
            }
            /* Read TAR File into TarArchiveInputStream */
            TarArchiveInputStream myTarFile = new TarArchiveInputStream(new FileInputStream(src));
            /* To read individual TAR file */
            TarArchiveEntry entry = null;
            String individualFiles;
            int offset;
            FileOutputStream outputFile = null;
            /* Create a loop to read every single entry in TAR file */
            while ((entry = myTarFile.getNextTarEntry()) != null) {
                /* Get the name of the file */
                individualFiles = entry.getName();
                /* Get Size of the file and create a byte array for the size */
                byte[] content = new byte[(int) entry.getSize()];
                offset = 0;
                /* Read file from the archive into byte array */
                myTarFile.read(content, offset, content.length - offset);
                /* Define OutputStream for writing the file */
                outputFile = new FileOutputStream(new File(newDst + "/" + individualFiles));
                /* Use IOUtiles to write content of byte array to physical file */
                IOUtils.write(content, outputFile);
                /* Close Output Stream */
                outputFile.close();
            }
            /* Close TarAchiveInputStream */
            myTarFile.close();
            src.delete();
        } catch (Exception ex) {
            Logger.getLogger(Xz.class.getName()).log(Level.SEVERE, null, ex);

        }
    }

    public static void main(String args[]) {
        //File file = new File("/media/adrianoska/SLiCER/DC.edpcg.local_2017-11-17T18:32:04_2017-11-17T18:49:44.tar.xz");
        //Xz.decoder(file, file.getParent());
        
        File file = new File("/home/adrianoska/Slicer/teste"); 
        File[] files = file.listFiles();
        for (int i = 0; i < files.length; i++) {
            Xz.decoder(files[i], new File("/home/adrianoska/Slicer/teste2"));
        }
    }
}
