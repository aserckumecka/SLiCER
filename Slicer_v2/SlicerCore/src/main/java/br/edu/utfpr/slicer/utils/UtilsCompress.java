package br.edu.utfpr.slicer.utils;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

public class UtilsCompress {
    private static final Logger log = LoggerFactory.getLogger(UtilsCompress.class);

    /**
     * Compacta todo o conteúdo de um diretório em um arquivo .tar.xz
     */
    public static void compressDirectoryToTarXz(Path sourceDir, Path destFile) throws IOException {
        try (OutputStream fo = Files.newOutputStream(destFile);
             BufferedOutputStream bo = new BufferedOutputStream(fo);
             XZCompressorOutputStream xzo = new XZCompressorOutputStream(bo);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(xzo)) {

            // Para evitar o aviso de nome de arquivo muito longo no Tar
            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    // Mantém a estrutura de pastas relativa
                    Path targetFile = sourceDir.relativize(file);
                    
                    TarArchiveEntry tarEntry = new TarArchiveEntry(file.toFile(), targetFile.toString());
                    tarOut.putArchiveEntry(tarEntry);
                    Files.copy(file, tarOut);
                    tarOut.closeArchiveEntry();
                    
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        log.debug("Compactação concluída: {}", destFile);
    }
    
    
    /**
     * Extrai um arquivo .tar.xz para uma pasta de destino específica.
     */
    public static void extractTarXz(Path archiveFile, Path destDir) throws IOException {
        try (InputStream fi = Files.newInputStream(archiveFile);
             InputStream bi = new BufferedInputStream(fi);
             InputStream xzi = new XZCompressorInputStream(bi);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(xzi)) {

            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry) tarIn.getNextEntry()) != null) {
                if (!tarIn.canReadEntryData(entry)) continue;

                Path targetFile = destDir.resolve(entry.getName()).normalize();
                
                // Proteção contra Zip Slip vulnerability
                if (!targetFile.startsWith(destDir)) {
                    throw new IOException("Entrada de arquivo maliciosa fora do diretório de destino: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(targetFile);
                } else {
                    Files.createDirectories(targetFile.getParent());
                    Files.copy(tarIn, targetFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}