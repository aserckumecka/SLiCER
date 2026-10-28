package br.edu.utfpr.slicer.index.bloom;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class LongBitSetSerializer {
    
    public static void serialize(LongBitSet lbs, DataOutputStream dos) throws IOException {
        // 1. Escreve o tamanho total de bits (long = 8 bytes)
        dos.writeLong(lbs.size()); 
        
        long[] words = lbs.getWords();
        // 2. Escreve o tamanho do array (int = 4 bytes)
        dos.writeInt(words.length); 
        
        // 3. Escreve os bits crus (8 bytes cada)
        for (long word : words) {
            dos.writeLong(word);
        }
    }

    public static LongBitSet deserialize(DataInputStream dis) throws IOException {
        long size = dis.readLong();
        int arrayLength = dis.readInt();
        
        LongBitSet lbs = new LongBitSet(size);
        long[] words = lbs.getWords();
        
        for (int i = 0; i < arrayLength; i++) {
            words[i] = dis.readLong();
        }
        return lbs;
    }
}