module.exports = {
  port: process.env.PORT || 8085,
  kafka: {
    brokers: (process.env.KAFKA_BROKERS || 'localhost:9092').split(','),
  },
};
