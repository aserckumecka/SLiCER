package br.edu.utfpr.slicer.storage;

import java.nio.file.Path;

public interface StorageProvider {
    /**
     * Criptografa e envia um bloco para o armazenamento em nuvem.
     * @param localFile Caminho do arquivo compactado local (.tar.xz)
     * @param blockName Nome do bloco (que será a chave no storage)
     */
    void uploadBlock(Path localFile, String blockName);

    /**
     * Baixa e descriptografa um bloco do armazenamento em nuvem.
     */
    void downloadBlock(String blockName, Path destinationFile);
}