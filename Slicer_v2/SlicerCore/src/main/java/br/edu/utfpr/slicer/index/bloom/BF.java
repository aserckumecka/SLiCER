package br.edu.utfpr.slicer.index.bloom;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BF {

    private static final Logger LOGGER = Logger.getLogger(BF.class.getName());

    // Construtor privado para evitar instanciação, já que agora é apenas uma classe utilitária (Factory)
    private BF() {
    }

    public static LongFastBloomFilter createBloom(long expectedNumberOfElements, double falsePosProb) {
        return LongFastBloomFilter.getFilter(expectedNumberOfElements, falsePosProb);
    }

    public static void storeBloom(LongFastBloomFilter lfb, Path pathFolder, String pathName) {
        try {
            if (!Files.exists(pathFolder)) {
                Files.createDirectories(pathFolder); // Usando createDirectories para garantir a árvore completa
            }

            File destFile = new File(pathFolder.toFile(), pathName);
            
            // Try-with-resources: Garante o fechamento do arquivo mesmo se der erro (Evita File Locks!)
            try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(destFile))) {
                LongFastBloomFilterSerializer lfsb = new LongFastBloomFilterSerializer();
                lfsb.serialize(lfb, dos);
            }
            
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Erro ao salvar o Bloom Filter em disco", ex);
        }
    }

    public static LongFastBloomFilter retrieveBloom(File path) {
        if (!path.exists()) {
            return null;
        }

        // Try-with-resources: Garante liberação do arquivo de leitura
        try (DataInputStream dis = new DataInputStream(new FileInputStream(path))) {
            LongFastBloomFilterSerializer lfbs = new LongFastBloomFilterSerializer();
            return lfbs.deserialize(dis);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Erro ao recuperar o Bloom Filter do disco", ex);
            return null;
        }
    }
}