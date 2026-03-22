const express = require("express");
const config = require("./config");
const riskRouter = require("./routes/risk");

const app = express();

app.use("/api/v1/risk", riskRouter);

app.get("/health", (req, res) => {
  res.json({ status: "ok" });
});

app.listen(config.port, () => {
  console.log(`Risk service listening on port ${config.port}`);
});
