package br.edu.utfpr.slicer.index;

import java.nio.file.Path;
import br.edu.utfpr.slicer.core.ParsedEvent;
import br.edu.utfpr.slicer.index.bloom.BF;
import br.edu.utfpr.slicer.index.bloom.LongFastBloomFilter;

public class BloomFilterManager {

    private static final double FALSE_POSITIVE_RATE = 0.01; // 1%
    private final LongFastBloomFilter bloomFilter;
    
    // NOVO: Contador exato de elementos inseridos
    private long elementsInserted = 0;

    public BloomFilterManager(long estimatedElements) {
        this.bloomFilter = BF.createBloom(estimatedElements, FALSE_POSITIVE_RATE);
    }

    public void addEventFields(ParsedEvent event) {
        event.fields().forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                String term = key + "=" + value;
                bloomFilter.add(term.getBytes());
                bloomFilter.add(value.getBytes());
                
                // NOVO: Incrementa o contador (2 inserções)
                elementsInserted += 2;
            }
        });
    }

    public void flushToDisk(Path destinationFolder, String blockName) {
        BF.storeBloom(bloomFilter, destinationFolder, blockName + ".bf");
    }

    // NOVO: Getter para o DeviceBlockManager consultar no final
    public long getElementsInserted() {
        return elementsInserted;
    }
}