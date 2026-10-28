package br.edu.utfpr.slicer.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class UtilsForensics {
    private static final Logger log = LoggerFactory.getLogger(UtilsForensics.class);
    
    // Tamanhos definidos na nossa arquitetura L2
    private static final int MAGIC_BYTES_LENGTH = 4;
    private static final int VERSION_LENGTH = 1;
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;
    private static final byte[] EXPECTED_MAGIC = new byte[]{'S', 'L', 'C', 'R'};

    /**
     * Inspeciona os metadados de um bloco e exporta o arquivo decriptado original.
     */
    public static boolean verifyAndDecryptBlock(Path encryptedFile, Path outputDecryptedFile) {
        if (!UtilsCrypto.isInitialized()) return false;

        try (FileInputStream fis = new FileInputStream(encryptedFile.toFile())) {
            byte[] magic = new byte[MAGIC_BYTES_LENGTH];
            fis.read(magic);

            if (Arrays.equals(magic, EXPECTED_MAGIC)) {
                // MODO SEGURO: Tem cabeçalho SLC
                int version = fis.read();
                byte[] iv = new byte[IV_LENGTH];
                fis.read(iv);
                
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, UtilsCrypto.getMasterKey(), new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv));
                
                try (FileOutputStream fos = new FileOutputStream(outputDecryptedFile.toFile());
                     javax.crypto.CipherInputStream cis = new javax.crypto.CipherInputStream(fis, cipher)) {
                    fis.transferTo(fos); // O CipherInputStream cuida do resto
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = cis.read(buffer)) != -1) fos.write(buffer, 0, read);
                }
                return true;
            } else {
                // MODO LEGACY: Não tem cabeçalho, tenta decriptografia direta (UtilsCrypto padrão)
                log.warn("⚠️ Bloco sem cabeçalho SLCR detectado. Tentando decriptografia legacy...");
                // Aqui você pode chamar o seu método padrão de decriptografia de arquivos
                // Se o seu UtilsCrypto.decryptFile(input, output) já funciona para os blocos, use-o:
                UtilsCrypto.decryptFile(encryptedFile, outputDecryptedFile);
                return true;
            }
        } catch (Exception e) {
            log.error("Erro na decriptografia forense", e);
            return false;
        }
    }
}