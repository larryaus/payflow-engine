package handler

import (
	"bytes"
	"encoding/json"
	"io"
	"log"
	"net"
	"net/http"
	"time"
)

// webhookClient is a shared HTTP client with connection pool limits and timeout.
var webhookClient = &http.Client{
	Timeout: 10 * time.Second,
	Transport: &http.Transport{
		MaxIdleConns:        100,
		MaxIdleConnsPerHost: 10,
		IdleConnTimeout:     90 * time.Second,
	},
}

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
// 仅在网络错误或 5xx 响应时重试，4xx 错误不重试。
func SendWebhookWithRetry(url string, payload map[string]interface{}) {
	body, err := json.Marshal(payload)
	if err != nil {
		log.Printf("Webhook payload marshal error: %v", err)
		return
	}

	maxRetries := 3
	backoff := 2 * time.Second

	for i := 0; i <= maxRetries; i++ {
		resp, err := webhookClient.Post(url, "application/json", bytes.NewReader(body))
		if err != nil {
			if !isRetryableError(err) {
				log.Printf("Webhook to %s failed with non-retryable error: %v", url, err)
				return
			}
			if i < maxRetries {
				log.Printf("Webhook to %s failed (attempt %d), retrying in %v: %v", url, i+1, backoff, err)
				time.Sleep(backoff)
				backoff *= 2
			}
			continue
		}

		// Drain and close the body to allow connection reuse.
		io.Copy(io.Discard, resp.Body)
		resp.Body.Close()

		if resp.StatusCode >= 200 && resp.StatusCode < 300 {
			log.Printf("Webhook sent successfully to %s", url)
			return
		}
		// Don't retry client errors (4xx) — the request itself is wrong
		if resp.StatusCode >= 400 && resp.StatusCode < 500 {
			log.Printf("Webhook to %s returned %d (client error), not retrying", url, resp.StatusCode)
			return
		}
		// 5xx — retry
		if i < maxRetries {
			log.Printf("Webhook to %s returned %d (attempt %d), retrying in %v", url, resp.StatusCode, i+1, backoff)
			time.Sleep(backoff)
			backoff *= 2
		}
	}
	log.Printf("Webhook to %s failed after %d retries", url, maxRetries)
}

// isRetryableError checks if the error is a transient network error worth retrying.
func isRetryableError(err error) bool {
	if netErr, ok := err.(net.Error); ok {
		return netErr.Timeout()
	}
	return true // default to retry for unknown errors
}
