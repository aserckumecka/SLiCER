require('dotenv').config();
const express = require('express');
const cors = require('cors');
// Importa o controlador
const { searchLogs } = require('./src/controllers/searchController'); 

const app = express();
const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());

// Rota de Teste
app.get('/api/health', (req, res) => {
    res.json({ status: 'SLiCER Web Node.js is Running!', cacheDir: process.env.CACHE_DIR });
});

// Rota Oficial de Busca do Funil (Método POST)
app.post('/api/search', searchLogs);

app.listen(PORT, () => {
    console.log(`🚀 Servidor SLiCER Node.js rodando na porta ${PORT}`);
    console.log(`📂 Lendo cache de: ${process.env.CACHE_DIR}`);
});
