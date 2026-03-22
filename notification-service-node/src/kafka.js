const { Kafka } = require('kafkajs');
const config = require('./config');
const { sendWebhookWithRetry } = require('./handlers/webhook');

const TOPICS = ['payment.completed', 'payment.failed', 'notification.send'];

const kafka = new Kafka({
  clientId: 'notification-service',
  brokers: config.kafka.brokers,
});

const consumer = kafka.consumer({ groupId: 'notification-group' });

/**
 * Start the Kafka consumer: subscribe to topics and process messages.
 */
async function startConsumer() {
  await consumer.connect();
  console.log('[kafka] Consumer connected');

  for (const topic of TOPICS) {
    await consumer.subscribe({ topic, fromBeginning: false });
    console.log(`[kafka] Subscribed to topic: ${topic}`);
  }

  await consumer.run({
    eachMessage: async ({ topic, partition, message }) => {
      const key = message.key ? message.key.toString() : '';
      const offset = message.offset;
      console.log(
        `[kafka] [${topic}] Received message: partition=${partition} offset=${offset} key=${key}`
      );

      try {
        const payload = JSON.parse(message.value.toString());
        handleMessage(topic, payload);
      } catch (err) {
        console.error(`[kafka] [${topic}] Invalid JSON payload: ${err.message}`);
      }
    },
  });
}

/**
 * Handle a parsed Kafka message by dispatching to the webhook sender.
 * @param {string} topic
 * @param {object} payload
 */
function handleMessage(topic, payload) {
  const callbackUrl = payload.callback_url;

  if (!callbackUrl) {
    console.log(`[kafka] [${topic}] No callback_url in payload, skipping webhook`);
    return;
  }

  // Fire and forget
  sendWebhookWithRetry(callbackUrl, payload).catch((err) => {
    console.error(`[kafka] [${topic}] Unhandled error in webhook delivery: ${err.message}`);
  });
}

/**
 * Disconnect the Kafka consumer gracefully.
 */
async function disconnectConsumer() {
  await consumer.disconnect();
  console.log('[kafka] Consumer disconnected');
}

module.exports = { startConsumer, disconnectConsumer };
