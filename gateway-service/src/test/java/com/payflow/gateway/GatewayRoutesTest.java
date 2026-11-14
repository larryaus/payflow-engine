package com.payflow.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * account / ledger / notification 只暴露读接口: 写请求不匹配任何路由, 不会到达下游。
 * 下游用一个记录请求的本地 HTTP 桩代替; 没有 Redis 时限流器放行, 所以 CI 不需要 Redis。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.jwt.enabled=false")
class GatewayRoutesTest {

    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer BACKEND = startBackend();

    @Autowired
    private WebTestClient client;

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                RECEIVED.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void backendUrls(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + BACKEND.getAddress().getPort();
        for (String service : List.of("PAYMENT", "ACCOUNT", "LEDGER", "RISK", "NOTIFICATION")) {
            registry.add("SERVICES_" + service + "_URL", () -> url);
        }
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop(0);
    }

    @BeforeEach
    void clear() {
        RECEIVED.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/accounts/freeze?account_id=acc001&amount=100",
            "/api/v1/accounts/unfreeze?account_id=acc001&amount=100",
            "/api/v1/accounts/transfer?from_account=acc001&to_account=acc002&amount=100",
            "/api/v1/ledger/entries?reference_id=X&debit_account=acc001&credit_account=acc002&amount=100",
            "/api/v1/ledger/entries/reverse?reference_id=X&debit_account=acc001&credit_account=acc002&amount=100",
            "/api/v1/notify/webhook"
    })
    void internalWriteEndpointsAreNotRouted(String uri) {
        client.post().uri(uri).exchange().expectStatus().isNotFound();
        assertThat(RECEIVED).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/accounts", "/api/v1/accounts/acc001/balance",
            "/api/v1/ledger/entries?payment_id=X", "/api/v1/ledger/verify", "/api/v1/notify/health"})
    void readEndpointsStillReachTheBackend(String uri) {
        client.get().uri(uri).exchange().expectStatus().isOk();
        assertThat(RECEIVED).singleElement().asString().startsWith("GET ");
    }

    @Test
    void headRequestsAreRoutedLikeGet() {
        client.head().uri("/api/v1/accounts").exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Trace-Id");
        assertThat(RECEIVED).containsExactly("HEAD /api/v1/accounts");
    }

    @Test
    void paymentWritesStillReachPaymentService() {
        client.post().uri("/api/v1/payments").header("Idempotency-Key", "k1")
                .bodyValue("{}").header("Content-Type", "application/json")
                .exchange().expectStatus().isOk();
        assertThat(RECEIVED).containsExactly("POST /api/v1/payments");
    }
}
