module.exports = {
  port: process.env.PORT || 8083,
  db: {
    host: process.env.DB_HOST || 'localhost',
    port: parseInt(process.env.DB_PORT || '5432'),
    database: process.env.DB_NAME || 'payflow',
    user: process.env.DB_USER || 'payflow',
    password: process.env.DB_PASSWORD || 'payflow_secret',
  },
};
