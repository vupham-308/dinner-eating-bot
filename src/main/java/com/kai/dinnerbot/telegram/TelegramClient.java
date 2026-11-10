package com.kai.dinnerbot.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Gọi thẳng Telegram Bot API qua HTTP (không cần thư viện bot). */
@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper om = new ObjectMapper();
    private final String base;

    public TelegramClient(@Value("${bot.token}") String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Thiếu bot.token (biến môi trường BOT_TOKEN)");
        }
        this.base = "https://api.telegram.org/bot" + token + "/";
    }

    public JsonNode call(String method, ObjectNode body, Duration timeout) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + method))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = om.readTree(res.body());
        if (!json.path("ok").asBoolean(false)) {
            throw new TelegramException(json.path("description").asText("HTTP " + res.statusCode()));
        }
        return json.path("result");
    }

    private ObjectNode markup(List<List<Btn>> kb) {
        ObjectNode m = om.createObjectNode();
        ArrayNode rows = m.putArray("inline_keyboard");
        for (List<Btn> r : kb) {
            ArrayNode row = rows.addArray();
            for (Btn b : r) {
                ObjectNode btn = row.addObject();
                btn.put("text", b.text());
                btn.put("callback_data", b.data());
            }
        }
        return m;
    }

    /** Gửi tin. Trả về message_id, hoặc null nếu lỗi (vd. user chưa /start bot). */
    public Integer send(long chatId, String text, List<List<Btn>> kb) {
        ObjectNode body = om.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", text);
        if (kb != null && !kb.isEmpty()) body.set("reply_markup", markup(kb));
        try {
            return call("sendMessage", body, Duration.ofSeconds(20)).path("message_id").asInt();
        } catch (Exception e) {
            log.warn("Không gửi được tin tới {}: {}", chatId, e.getMessage());
            return null;
        }
    }

    public Integer send(long chatId, String text) {
        return send(chatId, text, null);
    }

    /** Sửa tin. kb = null -> bỏ hết nút. */
    public void edit(long chatId, Integer messageId, String text, List<List<Btn>> kb) {
        if (messageId == null) return;
        ObjectNode body = om.createObjectNode();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", text);
        if (kb != null && !kb.isEmpty()) body.set("reply_markup", markup(kb));
        try {
            call("editMessageText", body, Duration.ofSeconds(20));
        } catch (Exception e) {
            if (e.getMessage() == null || !e.getMessage().contains("message is not modified")) {
                log.warn("Không sửa được tin {} / {}: {}", chatId, messageId, e.getMessage());
            }
        }
    }

    /** Chỉ tắt nút, giữ nguyên nội dung. */
    public void removeButtons(long chatId, Integer messageId) {
        if (messageId == null) return;
        ObjectNode body = om.createObjectNode();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.set("reply_markup", emptyMarkup());
        try {
            call("editMessageReplyMarkup", body, Duration.ofSeconds(20));
        } catch (Exception e) {
            if (e.getMessage() == null || !e.getMessage().contains("message is not modified")) {
                log.warn("Không tắt được nút {} / {}: {}", chatId, messageId, e.getMessage());
            }
        }
    }

    private ObjectNode emptyMarkup() {
        ObjectNode m = om.createObjectNode();
        m.putArray("inline_keyboard");
        return m;
    }

    public void answerCallback(String callbackId, String text, boolean alert) {
        ObjectNode body = om.createObjectNode();
        body.put("callback_query_id", callbackId);
        if (text != null) body.put("text", text);
        body.put("show_alert", alert);
        try {
            call("answerCallbackQuery", body, Duration.ofSeconds(10));
        } catch (Exception e) {
            log.debug("answerCallbackQuery lỗi: {}", e.getMessage());
        }
    }

    public JsonNode getUpdates(long offset, int timeoutSec) throws Exception {
        ObjectNode body = om.createObjectNode();
        body.put("offset", offset);
        body.put("timeout", timeoutSec);
        body.putArray("allowed_updates").add("message").add("callback_query");
        return call("getUpdates", body, Duration.ofSeconds(timeoutSec + 15L));
    }

    public void deleteWebhook() {
        try {
            call("deleteWebhook", om.createObjectNode(), Duration.ofSeconds(10));
        } catch (Exception e) {
            log.warn("deleteWebhook lỗi: {}", e.getMessage());
        }
    }

    public static class TelegramException extends RuntimeException {
        public TelegramException(String msg) {
            super(msg);
        }
    }
}
