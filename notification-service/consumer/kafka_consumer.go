package consumer

import "log"

// StartKafkaConsumer 启动 Kafka 消费者, 监听 payment.completed 和 payment.failed 事件
// 实际生产中使用 segmentio/kafka-go 或 confluent-kafka-go
func StartKafkaConsumer() {
	log.Println("Kafka consumer started, listening on topics: payment.completed, payment.failed")
	// 生产实现:
	// reader := kafka.NewReader(kafka.ReaderConfig{
	//     Brokers: []string{"kafka:9092"},
	//     GroupID: "notification-group",
	//     Topic:   "payment.completed",
	// })
	// for { msg, _ := reader.ReadMessage(ctx); handleNotification(msg) }
	select {} // 阻塞
}
