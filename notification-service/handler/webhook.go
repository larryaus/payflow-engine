package handler

import (
	"bytes"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"time"
)

// webhookClient is a shared HTTP client with a timeout to prevent hanging connections.
var webhookClient = &http.Client{Timeout: 10 * time.Second}

// WebhookHandler 处理 Webhook 回调通知
func WebhookHandler(w http.ResponseWriter, r *http.Request) {
	var payload map[string]interface{}
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		http.Error(w, "invalid payload", http.StatusBadRequest)
		return
	}

	callbackURL, ok := payload["callback_url"].(string)
	if !ok || callbackURL == "" {
		http.Error(w, "missing callback_url", http.StatusBadRequest)
		return
	}

	go SendWebhookWithRetry(callbackURL, payload)
	w.WriteHeader(http.StatusAccepted)
}

// SendWebhookWithRetry 带指数退避重试的 Webhook 发送（导出供 consumer 调用）
func SendWebhookWithRetry(url string, payload map[string]interface{}) {
	body, _ := json.Marshal(payload)
	maxRetries := 3
	backoff := 2 * time.Second

	for i := 0; i <= maxRetries; i++ {
		resp, err := webhookClient.Post(url, "application/json", bytes.NewReader(body))
		if err == nil {
			// Drain and close the body to allow connection reuse.
			io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
			if resp.StatusCode < 300 {
				log.Printf("Webhook sent successfully to %s", url)
				return
			}
		}
		if i < maxRetries {
			log.Printf("Webhook to %s failed (attempt %d), retrying in %v", url, i+1, backoff)
			time.Sleep(backoff)
			backoff *= 2
		}
	}
	log.Printf("Webhook to %s failed after %d retries", url, maxRetries)
}
