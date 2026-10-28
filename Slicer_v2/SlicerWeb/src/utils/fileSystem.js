const fs = require('fs').promises;
const path = require('path');

/**
 * Varre a pasta de cache de um Device e retorna a lista de blocos disponíveis
 * e seus respectivos caminhos para os arquivos .bf e .tar.xz
 */
async function getAvailableBlocks(deviceId) {
    const cacheDir = process.env.CACHE_DIR;
    const indexDir = path.join(cacheDir, deviceId, 'index');

    try {
        // Verifica se a pasta do device/index existe
        await fs.access(indexDir);
        
        const folders = await fs.readdir(indexDir, { withFileTypes: true });
        const blocks = [];

        for (const dirent of folders) {
            if (dirent.isDirectory() && dirent.name !== 'newblock') {
                const blockName = dirent.name; // Ex: Firewall-Edge_2026-05-04T10..._2026-05-04T10...
                
                // Extrai as datas usando regex para podermos filtrar por tempo depois
                const dateMatches = blockName.match(/_(20\d{2}-\d{2}-\d{2}T[\d:.]*)_(20\d{2}-\d{2}-\d{2}T[\d:.]*)$/);
                
                if (dateMatches) {
                    blocks.push({
                        blockName: blockName,
                        startTime: new Date(dateMatches[1]),
                        endTime: new Date(dateMatches[2]),
                        bloomPath: path.join(indexDir, blockName, `${blockName}.bf`),
                        luceneArchivePath: path.join(indexDir, blockName, `${blockName}_lucene.tar.xz`),
                        dataArchivePath: path.join(cacheDir, deviceId, 'data', `${blockName}.tar.xz`)
                    });
                }
            }
        }

        // Ordena do mais recente para o mais antigo
        return blocks.sort((a, b) => b.startTime - a.startTime);

    } catch (error) {
        console.error(`[FS] Erro ao ler o diretório de índices para ${deviceId}:`, error.message);
        return [];
    }
}

module.exports = {
    getAvailableBlocks
};
