package com.kai.dinnerbot.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.kai.dinnerbot.service.BotRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Long polling getUpdates trên 1 thread riêng. */
@Component
public class UpdatePoller {

    private static final Logger log = LoggerFactory.getLogger(UpdatePoller.class);

    private final TelegramClient tg;
    private final BotRouter router;
    private volatile boolean running = true;

    public UpdatePoller(TelegramClient tg, BotRouter router) {
        this.tg = tg;
        this.router = router;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        tg.deleteWebhook();
        Thread t = new Thread(this::loop, "telegram-poller");
        t.setDaemon(false);
        t.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> running = false));
        log.info("Bot đã chạy, đang nhận tin nhắn...");
    }

    private void loop() {
        long offset = 0;
        while (running) {
            try {
                JsonNode updates = tg.getUpdates(offset, 30);
                for (JsonNode u : updates) {
                    offset = u.path("update_id").asLong() + 1;
                    try {
                        router.handle(u);
                    } catch (Exception e) {
                        log.error("Lỗi xử lý update {}", u.path("update_id").asLong(), e);
                    }
                }
            } catch (Exception e) {
                log.warn("getUpdates lỗi: {} — thử lại sau 5s", e.getMessage());
                sleep(5000);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
