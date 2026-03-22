package main

import (
	"log"
	"net/http"

	"notification-service/consumer"
	"notification-service/handler"
)

func main() {
	// 启动 Kafka 消费者(异步)
	go consumer.StartKafkaConsumer()

	// HTTP 健康检查端点
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"ok"}`))
	})

	// Webhook 重试端点(手动触发)
	http.HandleFunc("/api/v1/notify/webhook", handler.WebhookHandler)

	log.Println("Notification service starting on :8085")
	log.Fatal(http.ListenAndServe(":8085", nil))
}
