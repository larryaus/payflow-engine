package consumer

import (
	"context"
	"encoding/json"
	"log"
	"os"
	"sync"
	"time"

	"github.com/segmentio/kafka-go"

	"notification-service/handler"
)

// kafkaBroker returns the Kafka broker address from env or default.
func kafkaBroker() string {
	if broker := os.Getenv("KAFKA_BROKER"); broker != "" {
		return broker
	}
	return "kafka:9092"
}

// StartKafkaConsumer 启动 Kafka 消费者，监听 payment.completed 和 payment.failed 事件。
// 使用 consumer group 确保每条消息只被一个实例处理；offset 自动提交。
// 阻塞直到所有 topic goroutine 退出（即 ctx 取消后全部处理完毕）。
func StartKafkaConsumer(ctx context.Context) {
	topics := []string{"payment.completed", "payment.failed", "notification.send"}

	var wg sync.WaitGroup
	for _, topic := range topics {
		wg.Add(1)
		go func(t string) {
			defer wg.Done()
			consumeTopic(ctx, t)
		}(topic)
	}

	wg.Wait()
	log.Println("Kafka consumer shutting down")
}

// consumeTopic 为单个 topic 创建独立的 reader，持续消费直到 ctx 取消。
func consumeTopic(ctx context.Context, topic string) {
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:        []string{kafkaBroker()},
		GroupID:        "notification-group",
		Topic:          topic,
		MinBytes:       1,
		MaxBytes:       10e6, // 10 MB
		CommitInterval: time.Second,
		StartOffset:    kafka.FirstOffset,
	})
	defer func() {
		if err := reader.Close(); err != nil {
			log.Printf("[%s] error closing reader: %v", topic, err)
		}
	}()

	log.Printf("[%s] consumer started", topic)

	for {
		msg, err := reader.ReadMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				// context cancelled — graceful shutdown
				return
			}
			log.Printf("[%s] read error: %v, retrying in 5s", topic, err)
			time.Sleep(5 * time.Second)
			continue
		}

		traceId := extractTraceId(msg.Headers)
		log.Printf("[%s] [traceId=%s] received message: partition=%d offset=%d key=%s",
			topic, traceId, msg.Partition, msg.Offset, string(msg.Key))

		handleMessage(topic, msg.Value, traceId)
	}
}

// extractTraceId reads the X-Trace-Id header from Kafka message headers.
func extractTraceId(headers []kafka.Header) string {
	for _, h := range headers {
		if h.Key == "X-Trace-Id" {
			return string(h.Value)
		}
	}
	return "-"
}

// handleMessage 根据 topic 分发处理逻辑
func handleMessage(topic string, value []byte, traceId string) {
	var payload map[string]interface{}
	if err := json.Unmarshal(value, &payload); err != nil {
		log.Printf("[%s] [traceId=%s] invalid JSON payload: %v", topic, traceId, err)
		return
	}

	switch topic {
	case "payment.completed", "payment.failed", "notification.send":
		callbackURL, _ := payload["callback_url"].(string)
		if callbackURL == "" {
			log.Printf("[%s] [traceId=%s] no callback_url in payload, skipping", topic, traceId)
			return
		}
		log.Printf("[%s] [traceId=%s] sending webhook to %s", topic, traceId, callbackURL)
		handler.SendWebhookWithRetry(callbackURL, payload)

	default:
		log.Printf("[traceId=%s] unhandled topic: %s", traceId, topic)
	}
}
