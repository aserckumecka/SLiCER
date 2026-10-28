/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bloom;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 *
 * @author adrianoska
 */
class LongFastBloomFilterSerializer implements ICompactSerializer<LongFastBloomFilter> {
    
    public void serialize(LongFastBloomFilter lbf, DataOutputStream dos) throws IOException {
        dos.writeInt(lbf.getNumHashFunctions());
        LongBitSetSerializer.serialize(lbf.getLongBitSet(), dos);
    }

    public LongFastBloomFilter deserialize(DataInputStream dis) throws IOException {
        int hashes = dis.readInt();
        LongBitSet lbf = LongBitSetSerializer.deserialize(dis);
        return new LongFastBloomFilter(hashes, lbf);
    }
}
