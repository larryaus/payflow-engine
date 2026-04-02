package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"notification-service/consumer"
	"notification-service/handler"
)

func main() {
	// 创建可取消的 context，用于优雅关闭
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// 启动 Kafka 消费者(异步)；consumerDone 在所有 topic goroutine 退出后关闭。
	consumerDone := make(chan struct{})
	go func() {
		consumer.StartKafkaConsumer(ctx)
		close(consumerDone)
	}()

	// HTTP 健康检查端点
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"ok"}`))
	})

	// Webhook 重试端点(手动触发)
	mux.HandleFunc("/api/v1/notify/webhook", handler.WebhookHandler)

	server := &http.Server{
		Addr:    ":8085",
		Handler: mux,
	}

	// 监听终止信号，优雅关闭
	go func() {
		sigCh := make(chan os.Signal, 1)
		signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
		sig := <-sigCh
		log.Printf("Received signal %v, shutting down...", sig)

		cancel() // 停止 Kafka consumer

		shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer shutdownCancel()
		if err := server.Shutdown(shutdownCtx); err != nil {
			log.Printf("HTTP server shutdown error: %v", err)
		}

		// 等待所有 consumer goroutine 退出后再让进程结束
		<-consumerDone
	}()

	log.Println("Notification service starting on :8085")
	if err := server.ListenAndServe(); err != http.ErrServerClosed {
		log.Fatalf("HTTP server error: %v", err)
	}
	log.Println("Notification service stopped")
}
