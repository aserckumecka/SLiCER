const fs = require('fs').promises;

// =====================================================================
// 1. O MOTOR DE HASHING (Tradução exata do Java MurmurHash2 64-bit)
// =====================================================================
const M = 0xc6a4a7935bd1e995n;
const R = 47n;

function toInt64(val) { return BigInt.asIntN(64, val); }
function toUint64(val) { return BigInt.asUintN(64, val); }

function murmurHash64(buffer, seed) {
    let length = BigInt(buffer.length);
    let h = toInt64((BigInt(seed) & 0xffffffffn) ^ toInt64(length * M));
    let length8 = Math.floor(buffer.length / 8);

    for (let i = 0; i < length8; i++) {
        let i8 = i * 8;
        // Java lê bytes em Little Endian internamente no hash original
        let k = buffer.readBigUInt64LE(i8);
        k = toInt64(k);

        k = toInt64(k * M);
        k ^= toUint64(k) >> R;
        k = toInt64(k * M);

        h ^= k;
        h = toInt64(h * M);
    }

    let tailIdx = length8 * 8;
    let rem = buffer.length % 8;
    if (rem > 0) {
        let kTail = 0n;
        if (rem >= 7) kTail ^= BigInt(buffer[tailIdx + 6]) << 48n;
        if (rem >= 6) kTail ^= BigInt(buffer[tailIdx + 5]) << 40n;
        if (rem >= 5) kTail ^= BigInt(buffer[tailIdx + 4]) << 32n;
        if (rem >= 4) kTail ^= BigInt(buffer[tailIdx + 3]) << 24n;
        if (rem >= 3) kTail ^= BigInt(buffer[tailIdx + 2]) << 16n;
        if (rem >= 2) kTail ^= BigInt(buffer[tailIdx + 1]) << 8n;
        if (rem >= 1) kTail ^= BigInt(buffer[tailIdx]);

        h ^= toInt64(kTail);
        h = toInt64(h * M);
    }

    h ^= toUint64(h) >> R;
    h = toInt64(h * M);
    h ^= toUint64(h) >> R;

    return h;
}

// =====================================================================
// 2. A LEITURA DO BINÁRIO PURO E VERIFICAÇÃO DOS BITS
// =====================================================================
async function checkTermInBloom(bloomFilePath, term) {
    try {
        const fileBuffer = await fs.readFile(bloomFilePath);
        
        // O MAPEAMENTO ESTRUTURAL DO ARQUIVO BINÁRIO (Igual ao Java DataOutputStream):
        // Bytes 0-3: k (int32) -> Número de hashes
        // Bytes 4-11: size (int64) -> Quantidade de bits (bitSetSize)
        // Bytes 12-15: arrayLength (int32) -> Tamanho do array de longs
        // Bytes 16+: words (int64 array) -> O array de bits em si

        const k = fileBuffer.readInt32BE(0);
        const bitSetSize = fileBuffer.readBigInt64BE(4);
        const wordsOffset = 16; 

        const termBuffer = Buffer.from(term, 'utf8');
        let hash1 = murmurHash64(termBuffer, 0n);
        let hash2 = murmurHash64(termBuffer, hash1);

        // Otimização de Kirsch-Mitzenmacher (Com simulação de Overflow Java)
        for (let i = 0n; i < BigInt(k); i++) {
            
            // CORREÇÃO CRÍTICA: Simula o overflow cego do Java
            let step = toInt64(i * hash2);
            let combinedHash = toInt64(hash1 + step);
            
            let index = combinedHash % bitSetSize;
            if (index < 0n) {
                index += bitSetSize; // Simula a proteção de array negativo do Java
            }
            
            // Lógica do LongBitSet.get(index)
            let wordIndex = Number(index / 64n); 
            let bitIndex = index % 64n; 

            // Lê os 8 bytes exatos desse 'word'
            let word = fileBuffer.readBigInt64BE(wordsOffset + (wordIndex * 8));

            // Operador AND Bitwise para checar se o bit está aceso (1)
            let isBitSet = (word & (1n << bitIndex)) !== 0n;

            if (!isBitSet) {
                return false; // Definitivamente NÃO ESTÁ no bloco
            }
        }

        return true; // PROVAVELMENTE ESTÁ no bloco (Match de Bloom!)

    } catch (error) {
        console.error(`[Bloom] Erro ao processar o Bloom Filter: ${bloomFilePath}`, error.message);
        return true; // Fail-Open: Se der erro binário, deixa o Lucene tentar achar.
    }
}

module.exports = {
    checkTermInBloom
};
