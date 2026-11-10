package com.kai.dinnerbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.kai.dinnerbot.model.Member;
import com.kai.dinnerbot.repo.Repo;
import com.kai.dinnerbot.telegram.Cb;
import com.kai.dinnerbot.telegram.TelegramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Điều phối tin nhắn / nút bấm và tick định kỳ.
 * Mọi xử lý đều đi qua cùng 1 khoá -> không bao giờ chạy song song, tránh tranh chấp trạng thái.
 */
@Component
public class BotRouter {

    private static final Logger log = LoggerFactory.getLogger(BotRouter.class);

    private final Object lock = new Object();
    private final Repo repo;
    private final TelegramClient tg;
    private final DinnerFlowService flow;
    private final ScheduleService schedule;
    private final AdminService admin;
    private final SettingsService settings;
    private final TimeService time;

    public BotRouter(Repo repo, TelegramClient tg, DinnerFlowService flow, ScheduleService schedule,
                     AdminService admin, SettingsService settings, TimeService time) {
        this.repo = repo;
        this.tg = tg;
        this.flow = flow;
        this.schedule = schedule;
        this.admin = admin;
        this.settings = settings;
        this.time = time;
    }

    /** Chạy mỗi 20 giây. */
    @Scheduled(fixedDelay = 20_000, initialDelay = 5_000)
    public void tick() {
        synchronized (lock) {
            try {
                flow.tick();
            } catch (Exception e) {
                log.error("Lỗi tick", e);
            }
        }
    }

    public void handle(JsonNode update) {
        synchronized (lock) {
            if (update.has("callback_query")) {
                onCallback(update.get("callback_query"));
            } else if (update.has("message")) {
                onMessage(update.get("message"));
            }
        }
    }

    private void onMessage(JsonNode msg) {
        if (!"private".equals(msg.path("chat").path("type").asText())) return; // chỉ chat riêng
        long tgId = msg.path("from").path("id").asLong();
        String text = msg.path("text").asText("").trim();
        Optional<Member> om = repo.memberByTg(tgId);
        if (om.isEmpty()) {
            tg.send(tgId, Msg.unauthorized(tgId));
            return;
        }
        Member me = om.get();
        if (!me.started()) repo.setStarted(me.id());

        // Lệnh mới (trừ /huy) thì bỏ trạng thái đang chờ nhập
        String cmd = text.split("\\s+")[0].split("@")[0].toLowerCase();
        if (cmd.startsWith("/") && !"/huy".equals(cmd)) admin.clearPending(me);
        else if (admin.handleText(me, text)) return;

        switch (cmd) {
            case "/lich" -> schedule.sendList(me);
            case "/admin" -> admin.sendMenu(me);
            default -> tg.send(tgId, Msg.start(me, settings.load(time.today()).poll()));
        }
    }

    private void onCallback(JsonNode q) {
        String id = q.path("id").asText();
        long tgId = q.path("from").path("id").asLong();
        String data = q.path("data").asText("");
        JsonNode m = q.path("message");
        Integer messageId = m.has("message_id") ? m.get("message_id").asInt() : null;

        Optional<Member> om = repo.memberByTg(tgId);
        if (om.isEmpty()) {
            tg.answerCallback(id, "⛔ Bạn không có trong danh sách thành viên.", true);
            return;
        }
        Cb cb = new Cb(id, om.get(), messageId, data, tg);
        try {
            switch (cb.part(0)) {
                case "pre" -> flow.onPrecheckChange(cb);
                case "eat" -> flow.onEat(cb);
                case "wd" -> flow.onWithdraw(cb);
                case "cook" -> flow.onCook(cb);
                case "shop" -> flow.onShop(cb);
                case "lich" -> schedule.onCallback(cb);
                case "adm" -> admin.onCallback(cb);
                default -> cb.alert("Nút này không còn dùng được.");
            }
        } catch (Exception e) {
            log.error("Lỗi xử lý nút {}", data, e);
            cb.alert("Có lỗi xảy ra, thử lại sau nhé.");
        } finally {
            cb.done();
        }
    }
}
