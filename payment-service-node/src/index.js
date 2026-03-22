const express = require('express');
const config = require('./config');
const pool = require('./db');
const { connectProducer, connectConsumer, consumer, disconnectKafka } = require('./kafka');
const paymentRoutes = require('./routes/payment');
const { errorHandler } = require('./errors');
const paymentService = require('./services/paymentService');

const app = express();

app.use(express.json());

// Health check
app.get('/health', async (req, res) => {
  try {
    await pool.query('SELECT 1');
    res.json({ status: 'ok' });
  } catch {
    res.status(503).json({ status: 'error', message: 'Database unavailable' });
  }
});

// Routes
app.use('/api/v1/payments', paymentRoutes);

// Error handler
app.use(errorHandler);

async function startKafkaConsumer() {
  await consumer.subscribe({ topics: ['payment.created'], fromBeginning: false });

  await consumer.run({
    eachMessage: async ({ message }) => {
      try {
        const payload = JSON.parse(message.value.toString());
        console.log(`Processing payment event: ${payload.payment_id}`);
        await paymentService.processPaymentAsync(payload);
      } catch (err) {
        console.error('Error processing Kafka message:', err.message);
      }
    },
  });
}

async function start() {
  try {
    await connectProducer();
    await connectConsumer();
    await startKafkaConsumer();

    app.listen(config.port, () => {
      console.log(`Payment service running on port ${config.port}`);
    });
  } catch (err) {
    console.error('Failed to start payment service:', err);
    process.exit(1);
  }
}

// Graceful shutdown
process.on('SIGINT', async () => {
  console.log('Shutting down...');
  await disconnectKafka();
  await pool.end();
  process.exit(0);
});

process.on('SIGTERM', async () => {
  console.log('Shutting down...');
  await disconnectKafka();
  await pool.end();
  process.exit(0);
});

start();
