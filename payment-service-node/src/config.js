module.exports = {
  port: process.env.PORT || 8081,
  db: {
    host: process.env.DB_HOST || 'localhost',
    port: parseInt(process.env.DB_PORT || '5432'),
    database: process.env.DB_NAME || 'payflow',
    user: process.env.DB_USER || 'payflow',
    password: process.env.DB_PASSWORD || 'payflow_secret',
  },
  redis: {
    host: process.env.REDIS_HOST || 'localhost',
    port: parseInt(process.env.REDIS_PORT || '6379'),
  },
  kafka: {
    brokers: (process.env.KAFKA_BROKERS || 'localhost:9092').split(','),
  },
  services: {
    accountUrl: process.env.ACCOUNT_SERVICE_URL || 'http://localhost:8082',
    ledgerUrl: process.env.LEDGER_SERVICE_URL || 'http://localhost:8083',
    riskUrl: process.env.RISK_SERVICE_URL || 'http://localhost:8084',
  },
};
