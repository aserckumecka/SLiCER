package br.edu.utfpr.slicer.storage;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.SdkBytes;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AwsS3SecureStorage implements StorageProvider {
    private static final Logger log = LoggerFactory.getLogger(AwsS3SecureStorage.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BIT = 128;
    private static final int GCM_IV_LENGTH_BYTE = 12;

    private final S3Client s3Client;
    private final KmsClient kmsClient;
    private final String bucketName;
    private final String kmsKeyId;

    public AwsS3SecureStorage(String bucketName, String kmsKeyId) {
        // O SDK v2 assume as credenciais automaticamente (variáveis de ambiente ou ~/.aws/credentials)
        this.s3Client = S3Client.create();
        this.kmsClient = KmsClient.create();
        this.bucketName = bucketName;
        this.kmsKeyId = kmsKeyId;
    }

    @Override
    public void uploadBlock(Path localFile, String blockName) {
        try {
            log.info("Iniciando criptografia e upload do bloco: {}", blockName);

            // 1. Gerar Data Key (DEK) via KMS
            GenerateDataKeyResponse dataKey = kmsClient.generateDataKey(GenerateDataKeyRequest.builder()
                    .keyId(kmsKeyId)
                    .keySpec("AES_256")
                    .build());

            // 2. Configurar Cifra AES-GCM
            byte[] plaintextKey = dataKey.plaintext().asByteArray();
            SecretKey secretKey = new SecretKeySpec(plaintextKey, "AES");
            byte[] iv = new byte[GCM_IV_LENGTH_BYTE];
            new SecureRandom().nextBytes(iv);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

            // 3. Ler e Criptografar (Para blocos de até ~1GB, ler em memória é ok. Acima disso, usaríamos Streams)
            byte[] fileContent = Files.readAllBytes(localFile);
            byte[] encryptedContent = cipher.doFinal(fileContent);

            // 4. Salvar DEK Criptografada e IV nos metadados do S3 (Base64)
            String encodedEncryptedDek = Base64.getEncoder().encodeToString(dataKey.ciphertextBlob().asByteArray());
            String encodedIv = Base64.getEncoder().encodeToString(iv);

            // 5. Enviar para o S3
            String s3Key = "data/" + blockName + ".enc";
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .metadata(Map.of(
                            "x-amz-meta-encrypted-dek", encodedEncryptedDek,
                            "x-amz-meta-iv", encodedIv
                    ))
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(encryptedContent));
            
            // Limpar chave da memória por segurança
            java.util.Arrays.fill(plaintextKey, (byte) 0);
            log.info("Upload seguro concluído para o S3: s3://{}/{}", bucketName, s3Key);

        } catch (Exception e) {
            log.error("Falha no upload seguro do bloco {}", blockName, e);
            throw new RuntimeException("Erro de Storage: " + e.getMessage(), e);
        }
    }

    @Override
    public void downloadBlock(String blockName, Path destinationFile) {
        try {
            log.info("Iniciando download e descriptografia do bloco: {}", blockName);
            String s3Key = "data/" + blockName + ".enc";

            // 1. Baixar objeto e Metadados do S3
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();

            ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(getRequest);
            Map<String, String> metadata = s3Object.response().metadata();

            // 2. Extrair e decodificar Metadados
            byte[] encryptedDek = Base64.getDecoder().decode(metadata.get("x-amz-meta-encrypted-dek"));
            byte[] iv = Base64.getDecoder().decode(metadata.get("x-amz-meta-iv"));

            // 3. Descriptografar a DEK usando o KMS
            DecryptResponse decryptResponse = kmsClient.decrypt(DecryptRequest.builder()
                    .ciphertextBlob(SdkBytes.fromByteArray(encryptedDek))
                    .build());
            
            byte[] plaintextKey = decryptResponse.plaintext().asByteArray();

            // 4. Configurar Cifra para Descriptografia
            SecretKey secretKey = new SecretKeySpec(plaintextKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            // 5. Descriptografar o conteúdo e salvar no disco
            byte[] encryptedContent = s3Object.readAllBytes();
            byte[] decryptedContent = cipher.doFinal(encryptedContent);

            Files.write(destinationFile, decryptedContent);

            // Limpar chave da memória
            java.util.Arrays.fill(plaintextKey, (byte) 0);
            log.info("Bloco baixado e descriptografado com sucesso em: {}", destinationFile);

        } catch (Exception e) {
            log.error("Falha no download/descriptografia do bloco {}", blockName, e);
            throw new RuntimeException("Erro de Storage: " + e.getMessage(), e);
        }
    }
}