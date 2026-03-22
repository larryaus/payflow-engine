const express = require('express');
const config = require('./config');
const pool = require('./db');
const ledgerRoutes = require('./routes/ledger');
const { LedgerError } = require('./errors');

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

app.use('/api/v1/ledger', ledgerRoutes);

app.use((err, req, res, next) => {
  if (err instanceof LedgerError) {
    return res.status(err.statusCode).json({
      error: err.type,
      message: err.message,
    });
  }

  console.error('Unhandled error:', err);
  res.status(500).json({
    error: 'INTERNAL_ERROR',
    message: 'An unexpected error occurred',
  });
});

app.listen(config.port, () => {
  console.log(`Ledger service started on port ${config.port}`);
});
