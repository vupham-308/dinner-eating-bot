package com.kai.dinnerbot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;

/**
 * HTTP server nhỏ (dùng sẵn trong JDK, không cần thêm thư viện) để kiểm tra bot còn sống.
 * GET /health -> {"status":"UP"}
 */
@Component
public class HealthServer {

    private static final Logger log = LoggerFactory.getLogger(HealthServer.class);

    private final int port;
    private final Instant startedAt = Instant.now();
    private HttpServer server;

    public HealthServer(@Value("${bot.http-port:6970}") int port) {
        this.port = port;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", this::health);
        server.createContext("/", this::health);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        log.info("Health check chạy ở cổng {} (GET /health)", port);
    }

    private void health(HttpExchange ex) throws IOException {
        byte[] body = ("{\"status\":\"UP\",\"startedAt\":\"" + startedAt + "\"}").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
