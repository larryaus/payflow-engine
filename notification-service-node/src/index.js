const express = require('express');
const config = require('./config');
const { startConsumer, disconnectConsumer } = require('./kafka');
const notifyRouter = require('./routes/notify');

const app = express();

app.use(express.json());

// Health check
app.get('/health', (_req, res) => {
  res.json({ status: 'ok' });
});

// Webhook manual trigger routes
app.use('/api/v1/notify', notifyRouter);

// Start Express server
const server = app.listen(config.port, () => {
  console.log(`Notification service starting on :${config.port}`);
});

// Start Kafka consumer
startConsumer().catch((err) => {
  console.error(`[kafka] Failed to start consumer: ${err.message}`);
});

// Graceful shutdown
function shutdown(signal) {
  console.log(`Received ${signal}, shutting down...`);

  disconnectConsumer()
    .catch((err) => console.error(`[shutdown] Kafka disconnect error: ${err.message}`))
    .finally(() => {
      server.close((err) => {
        if (err) {
          console.error(`[shutdown] HTTP server close error: ${err.message}`);
          process.exit(1);
        }
        console.log('Notification service stopped');
        process.exit(0);
      });
    });
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
