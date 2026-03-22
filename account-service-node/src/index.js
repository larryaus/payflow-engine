const express = require('express');
const config = require('./config');
const pool = require('./db');
const accountRoutes = require('./routes/account');
const { errorHandler } = require('./errors');

const app = express();

app.use(express.json());

app.get('/health', async (req, res) => {
  try {
    await pool.query('SELECT 1');
    res.json({ status: 'UP' });
  } catch (err) {
    res.status(503).json({ status: 'DOWN', error: err.message });
  }
});

app.use('/api/v1/accounts', accountRoutes);

app.use(errorHandler);

app.listen(config.port, () => {
  console.log(`Account service (Node.js) listening on port ${config.port}`);
});
