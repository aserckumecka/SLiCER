package br.edu.utfpr.slicer.utils;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UtilsCrypto {
    private static final Logger log = LoggerFactory.getLogger(UtilsCrypto.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // 16 bytes
    private static final int GCM_IV_LENGTH = 12;   // 12 bytes
    private static final byte[] MAGIC_BYTES = "SLC1".getBytes(StandardCharsets.UTF_8);

    // A Chave Mestra que residirá APENAS na RAM
    private static SecretKey masterKey = null;

    /**
     * Inicializa a Chave Mestra a partir da senha digitada pelo usuário.
     * Usa PBKDF2 para dificultar ataques de força bruta.
     */
    public static void initializeMasterKey(char[] password) {
        try {
            // Um salt estático para o edge node (poderia ser lido de um arquivo config.salt)
            byte[] salt = "SLICER_EDGE_NODE_V1".getBytes(StandardCharsets.UTF_8);
            
            // PBKDF2: 65536 iterações gerando uma chave de 256 bits
            PBEKeySpec spec = new PBEKeySpec(password, salt, 65536, 256);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            
            masterKey = new SecretKeySpec(keyBytes, "AES");
            
            // Zera o array de caracteres da memória por segurança
            Arrays.fill(password, '\0');
            spec.clearPassword();
            
            log.info("🔐 Chave Mestra de Borda gerada e fixada na memória RAM com sucesso.");
        } catch (Exception e) {
            throw new RuntimeException("Falha ao inicializar a criptografia do SLiCER", e);
        }
    }

    public static boolean isInitialized() {
        return masterKey != null;
    }
    
    /**
     * Expõe a chave mestra para módulos de auditoria forense autorizados.
     */
    public static javax.crypto.SecretKey getMasterKey() {
        if (masterKey == null) {
            throw new IllegalStateException("A chave mestra ainda não foi carregada na memória.");
        }
        return masterKey;
    }

    /**
     * Criptografa um arquivo usando Envelope Encryption.
     */
    public static void encryptFile(Path inputPath, Path outputPath) throws Exception {
        if (!isInitialized()) throw new IllegalStateException("Criptografia não inicializada. O servidor está 'Selado'.");

        SecureRandom secureRandom = new SecureRandom();

        // 1. Gera a DEK (Data Encryption Key) exclusiva para este arquivo
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, secureRandom);
        SecretKey dek = keyGen.generateKey();

        // 2. Criptografa a DEK usando a Chave Mestra
        byte[] dekIv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(dekIv);
        Cipher masterCipher = Cipher.getInstance(ALGORITHM);
        masterCipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_LENGTH, dekIv));
        byte[] encryptedDek = masterCipher.doFinal(dek.getEncoded());

        // 3. Prepara a criptografia dos Dados Reais
        byte[] dataIv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(dataIv);
        Cipher dataCipher = Cipher.getInstance(ALGORITHM);
        dataCipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH, dataIv));

        // 4. Grava no disco: Cabeçalho + Dados Criptografados via Stream (Evita OutOfMemory)
        try (OutputStream os = Files.newOutputStream(outputPath)) {
            // Escreve o Cabeçalho (4 + 12 + 48 + 12 = 76 bytes)
            os.write(MAGIC_BYTES);
            os.write(dekIv);
            os.write(encryptedDek);
            os.write(dataIv);

            // Escreve os Dados
            try (InputStream is = Files.newInputStream(inputPath);
                 javax.crypto.CipherOutputStream cos = new javax.crypto.CipherOutputStream(os, dataCipher)) {
                byte[] buffer = new byte[8192]; // Buffer de 8KB
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    cos.write(buffer, 0, bytesRead);
                }
            }
        }
    }

    /**
     * Decriptografa um arquivo autossuficiente (.enc) do SLiCER.
     */
    public static void decryptFile(Path inputPath, Path outputPath) throws Exception {
        if (!isInitialized()) throw new IllegalStateException("Criptografia não inicializada. O servidor está 'Selado'.");

        try (InputStream is = Files.newInputStream(inputPath)) {
            // 1. Lê e Valida a Assinatura (MAGIC_BYTES)
            byte[] magic = new byte[MAGIC_BYTES.length];
            if (is.read(magic) != magic.length || !Arrays.equals(magic, MAGIC_BYTES)) {
                throw new IllegalArgumentException("Arquivo inválido ou corrompido. Não é um arquivo .enc do SLiCER.");
            }

            // 2. Lê os componentes do Cabeçalho
            byte[] dekIv = new byte[GCM_IV_LENGTH];
            is.read(dekIv);

            byte[] encryptedDek = new byte[32 + 16]; // 256 bits + GCM Tag
            is.read(encryptedDek);

            byte[] dataIv = new byte[GCM_IV_LENGTH];
            is.read(dataIv);

            // 3. Decifra a DEK usando a Chave Mestra
            Cipher masterCipher = Cipher.getInstance(ALGORITHM);
            masterCipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_LENGTH, dekIv));
            byte[] dekBytes = masterCipher.doFinal(encryptedDek);
            SecretKey dek = new SecretKeySpec(dekBytes, "AES");

            // 4. Prepara para decifrar os Dados Reais
            Cipher dataCipher = Cipher.getInstance(ALGORITHM);
            dataCipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH, dataIv));

            // 5. Decifra o fluxo para o disco
            try (javax.crypto.CipherInputStream cis = new javax.crypto.CipherInputStream(is, dataCipher);
                 OutputStream os = Files.newOutputStream(outputPath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = cis.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
        }
    }
}