const express = require('express');
const config = require('./config');
const pool = require('./db');
const redis = require('./redis');
const { connectProducer, connectConsumer, consumer, disconnectKafka } = require('./kafka');
const paymentRoutes = require('./routes/payment');
const paymentService = require('./services/paymentService');
const { errorHandler } = require('./errors');

const app = express();

// --- Middleware ---
app.use(express.json());

// --- Health Check ---
app.get('/health', async (_req, res) => {
  try {
    await pool.query('SELECT 1');
    const redisStatus = redis.status === 'ready' ? 'up' : 'down';
    res.json({
      status: 'UP',
      service: 'payment-service',
      postgres: 'up',
      redis: redisStatus,
    });
  } catch (err) {
    res.status(503).json({
      status: 'DOWN',
      service: 'payment-service',
      error: err.message,
    });
  }
});

// --- Payment Routes ---
app.use(paymentRoutes);

// --- Error Handler (must be last) ---
app.use(errorHandler);

// --- Kafka Consumer Setup ---
async function startConsumer() {
  await consumer.subscribe({ topic: 'payment.created', fromBeginning: false });

  await consumer.run({
    eachMessage: async ({ message }) => {
      try {
        const paymentData = JSON.parse(message.value.toString());
        console.log(`Received payment.created event for ${paymentData.paymentId}`);
        await paymentService.processPaymentAsync(paymentData);
      } catch (err) {
        console.error('Error processing Kafka message:', err.message);
      }
    },
  });

  console.log('Kafka consumer started — listening on payment.created');
}

// --- Graceful Shutdown ---
function setupGracefulShutdown(server) {
  const shutdown = async (signal) => {
    console.log(`Received ${signal}. Shutting down gracefully...`);

    server.close(async () => {
      try {
        await disconnectKafka();
        await redis.quit();
        await pool.end();
        console.log('All connections closed');
        process.exit(0);
      } catch (err) {
        console.error('Error during shutdown:', err.message);
        process.exit(1);
      }
    });

    // Force exit after 10 seconds
    setTimeout(() => {
      console.error('Forced shutdown after timeout');
      process.exit(1);
    }, 10000);
  };

  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('SIGINT', () => shutdown('SIGINT'));
}

// --- Start ---
async function start() {
  try {
    // Verify database connectivity
    await pool.query('SELECT 1');
    console.log('Connected to PostgreSQL');

    // Connect Kafka producer and consumer
    await connectProducer();
    await connectConsumer();
    await startConsumer();

    // Start HTTP server
    const server = app.listen(config.port, () => {
      console.log(`Payment service (Node.js) listening on port ${config.port}`);
    });

    setupGracefulShutdown(server);
  } catch (err) {
    console.error('Failed to start payment service:', err);
    process.exit(1);
  }
}

start();
