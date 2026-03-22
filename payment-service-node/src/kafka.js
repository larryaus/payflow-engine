const { Kafka, logLevel } = require('kafkajs');
const config = require('./config');

const kafka = new Kafka({
  clientId: 'payment-service',
  brokers: config.kafka.brokers,
  logLevel: logLevel.WARN,
  retry: {
    initialRetryTime: 300,
    retries: 10,
  },
});

const producer = kafka.producer({
  allowAutoTopicCreation: true,
  idempotent: true,
  maxInFlightRequests: 1,
});

const consumer = kafka.consumer({
  groupId: 'payment-group',
  sessionTimeout: 30000,
  heartbeatInterval: 3000,
});

async function connectProducer() {
  await producer.connect();
  console.log('Kafka producer connected');
}

async function connectConsumer() {
  await consumer.connect();
  console.log('Kafka consumer connected');
}

async function publishEvent(topic, key, value) {
  await producer.send({
    topic,
    messages: [
      {
        key,
        value: JSON.stringify(value),
        timestamp: Date.now().toString(),
      },
    ],
  });
}

async function disconnectKafka() {
  await producer.disconnect();
  await consumer.disconnect();
}

module.exports = {
  kafka,
  producer,
  consumer,
  connectProducer,
  connectConsumer,
  publishEvent,
  disconnectKafka,
};
