const { getAvailableBlocks } = require('../utils/fileSystem');
const { checkTermInBloom } = require('../services/bloomService');

async function searchLogs(req, res) {
    try {
        // Recebe os parâmetros do corpo da requisição (POST)
        const { deviceId, term } = req.body;

        if (!deviceId || !term) {
            return res.status(400).json({ error: "Parâmetros 'deviceId' e 'term' são obrigatórios." });
        }

        console.log(`\n🔍 Iniciando busca por [${term}] no device [${deviceId}]...`);
        const startTime = Date.now();

        // 1. Descobre quais blocos existem no disco
        const availableBlocks = await getAvailableBlocks(deviceId);
        
        if (availableBlocks.length === 0) {
            return res.status(404).json({ message: "Nenhum bloco de índice encontrado para este device." });
        }

        console.log(`📦 Encontrados ${availableBlocks.length} blocos. Passando pelo Bloom Filter...`);

        // 2. Passa a peneira (Bloom Filter) em todos os blocos disponíveis
        const matchedBlocks = [];
        
        // Dica de Performance: Poderíamos usar Promise.all() para ler vários em paralelo, 
        // mas vamos fazer sequencial primeiro para ver o log limpo.
        for (const block of availableBlocks) {
            const isMatch = await checkTermInBloom(block.bloomPath, term);
            if (isMatch) {
                // Se o Bloom disser "Sim", separamos este bloco para o Lucene!
                matchedBlocks.push(block);
            }
        }

        const duration = Date.now() - startTime;
        console.log(`✅ Busca concluída em ${duration}ms. Retornando ${matchedBlocks.length} blocos promissores.`);

        // 3. Retorna a resposta para o usuário
        res.json({
            status: "success",
            searchTarget: term,
            executionTimeMs: duration,
            blocksScanned: availableBlocks.length,
            blocksMatched: matchedBlocks.length,
            // Retorna apenas os nomes e caminhos dos blocos que deram Match
            candidates: matchedBlocks.map(b => ({
                blockName: b.blockName,
                luceneArchive: b.luceneArchivePath,
                dataArchive: b.dataArchivePath
            }))
        });

    } catch (error) {
        console.error("Erro na busca:", error);
        res.status(500).json({ error: "Erro interno no servidor." });
    }
}

module.exports = {
    searchLogs
};
