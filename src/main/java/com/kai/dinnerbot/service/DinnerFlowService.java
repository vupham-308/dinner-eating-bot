package com.kai.dinnerbot.service;

import com.kai.dinnerbot.model.*;
import com.kai.dinnerbot.repo.Repo;
import com.kai.dinnerbot.telegram.Btn;
import com.kai.dinnerbot.telegram.Cb;
import com.kai.dinnerbot.telegram.TelegramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static com.kai.dinnerbot.telegram.Btn.kb;
import static com.kai.dinnerbot.telegram.Btn.row;

/**
 * Luồng hằng ngày:
 * 13:00 nhắc người đặt "Không ăn" → 14:00 hỏi ăn / báo không nấu → 14:30 chốt → hỏi người nấu
 * → hỏi đi chợ → 19:00 chưa ai đi chợ thì huỷ và hoàn lượt nấu.
 * Các bước chạy theo "tick" mỗi 20 giây, dựa trên trạng thái lưu trong DB
 * nên server khởi động lại vẫn chạy tiếp đúng bước.
 */
@Service
public class DinnerFlowService {

    private static final Logger log = LoggerFactory.getLogger(DinnerFlowService.class);

    private final Repo repo;
    private final TelegramClient tg;
    private final SettingsService settings;
    private final TimeService time;

    public DinnerFlowService(Repo repo, TelegramClient tg, SettingsService settings, TimeService time) {
        this.repo = repo;
        this.tg = tg;
        this.settings = settings;
        this.time = time;
    }

    // =====================================================================
    // TICK
    // =====================================================================

    public void tick() {
        LocalDateTime now = time.now();
        LocalDate today = now.toLocalDate();
        LocalTime t = now.toLocalTime();
        BotSettings s = settings.load(today);

        cleanupStale(today, s);
        weeklyIfDue(today, t, s);

        DaySession ss = repo.session(today).orElse(null);
        if (ss == null) {
            if (t.isBefore(s.precheck()) || settings.isPaused(today)) return;
            if (!t.isBefore(s.close())) {
                // Bot bật lại sau giờ chốt mà hôm nay chưa chạy -> bỏ qua ngày này
                log.warn("Khởi động sau giờ chốt {} — bỏ qua ngày {}", s.close(), today);
                repo.createSession(today, Status.EXPIRED);
                return;
            }
            repo.createSession(today, Status.PRECHECK);
            if (t.isBefore(s.poll())) sendPrecheck(today, s);
            ss = repo.session(today).orElseThrow();
        }

        switch (ss.status()) {
            case Status.PRECHECK -> {
                if (!t.isBefore(s.poll())) doPoll(today, s);
            }
            case Status.POLLING -> {
                if (!t.isBefore(s.close())) doClose(today, s);
            }
            case Status.ASKING_COOK -> checkCookTimeout(today, s, now);
            case Status.COOK_CONFIRMED -> {
                if (!t.isBefore(s.shopDeadline())) shopFallback(today, s);
            }
            default -> {
            }
        }
    }

    /** Phiên của các ngày trước còn dang dở -> đóng lại. */
    private void cleanupStale(LocalDate today, BotSettings s) {
        for (DaySession old : repo.staleSessions(today)) {
            if (old.is(Status.COOK_CONFIRMED)) {
                shopFallback(old.date(), s);
            } else {
                repo.forceStatus(old.date(), Status.EXPIRED);
            }
            disableButtons(old.date(), "pre", "poll", "precook", "shop");
        }
    }

    // =====================================================================
    // 13:00 — nhắc người đã đặt trước "Không ăn"
    // =====================================================================

    private void sendPrecheck(LocalDate today, BotSettings s) {
        Map<Integer, Schedule> sch = repo.schedules(today);
        for (Member m : repo.members()) {
            Schedule x = sch.get(m.id());
            if (x != null && Boolean.FALSE.equals(x.willEat())) {
                Integer msg = tg.send(m.telegramId(), Msg.precheck(today, s.poll()),
                        kb(row(Btn.of(Msg.BTN_PRECHECK_CHANGE, "pre:" + today))));
                repo.saveMessage(today, m.id(), "pre", msg);
            }
        }
    }

    public void onPrecheckChange(Cb cb) {
        LocalDate date = LocalDate.parse(cb.part(1));
        LocalDate today = time.today();
        BotSettings s = settings.load(today);
        DaySession ss = repo.session(date).orElse(null);
        if (ss == null || !ss.is(Status.PRECHECK) || !date.equals(today) || !time.time().isBefore(s.poll())) {
            cb.alert(Msg.tooLate(s.poll()));
            return;
        }
        repo.setScheduleEat(date, cb.member.id(), true);
        cb.editSelf(Msg.precheckChanged(s.poll()), null);
        cb.toast("Đã đổi");
    }

    // =====================================================================
    // 14:00 — hỏi ăn tối
    // =====================================================================

    public void doPoll(LocalDate today, BotSettings s) {
        DaySession ss = repo.session(today).orElse(null);
        if (ss == null || !ss.is(Status.PRECHECK)) return;

        List<Member> members = repo.members();
        Map<Integer, Schedule> sch = repo.schedules(today);
        disableButtons(today, "pre");

        List<Member> notEat = members.stream().filter(m -> {
            Schedule x = sch.get(m.id());
            return x != null && Boolean.FALSE.equals(x.willEat());
        }).toList();

        // Có người giữ lịch "Không ăn" -> báo các thành viên còn lại, kết thúc ngày
        if (!notEat.isEmpty()) {
            if (!repo.setStatus(today, Status.NO_DINNER, Status.PRECHECK)) return;
            for (Member m : notEat) repo.upsertEat(today, m.id(), false, "preset", time.now());
            String text = Msg.noDinnerPreset(today, notEat);
            for (Member m : members) {
                if (!notEat.contains(m)) tg.send(m.telegramId(), text);
            }
            return;
        }

        if (!repo.setStatus(today, Status.POLLING, Status.PRECHECK)) return;

        for (Member m : members) {
            Schedule x = sch.get(m.id());
            Integer msg;
            if (x != null && Boolean.TRUE.equals(x.willEat())) {
                repo.upsertEat(today, m.id(), true, "preset", time.now());
                msg = tg.send(m.telegramId(), Msg.presetEat(today, s.close()),
                        kb(row(Btn.of(Msg.BTN_CHANGE_TO_NO, "eat:" + today + ":0"))));
            } else {
                msg = tg.send(m.telegramId(), Msg.poll(today, s.close()), pollKb(today));
            }
            repo.saveMessage(today, m.id(), "poll", msg);
        }

        // Người nấu định sẵn: trong số đã đặt "Nấu được", lấy count thấp nhất
        for (CookStat st : repo.cookStats()) {
            Schedule x = sch.get(st.member().id());
            if (x != null && Boolean.TRUE.equals(x.canCook())) {
                repo.setPresetCook(today, st.member().id());
                Integer msg = tg.send(st.member().telegramId(), Msg.presetCook(s.close()),
                        kb(row(Btn.of(Msg.BTN_WITHDRAW, "wd:" + today))));
                repo.saveMessage(today, st.member().id(), "precook", msg);
                break;
            }
        }
    }

    private static List<List<Btn>> pollKb(LocalDate d) {
        return kb(row(Btn.of(Msg.BTN_YES, "eat:" + d + ":1"), Btn.of(Msg.BTN_NO, "eat:" + d + ":0")));
    }

    public void onEat(Cb cb) {
        LocalDate date = LocalDate.parse(cb.part(1));
        boolean yes = "1".equals(cb.part(2));
        LocalDate today = time.today();
        BotSettings s = settings.load(today);
        DaySession ss = repo.session(date).orElse(null);
        if (ss == null || !ss.is(Status.POLLING) || !date.equals(today) || !time.time().isBefore(s.close())) {
            cb.alert(Msg.tooLate(s.close()));
            return;
        }
        repo.upsertEat(date, cb.member.id(), yes, "click", time.now());
        cb.editSelf(Msg.pollChosen(date, yes, s.close()), pollKb(date));
        cb.toast("Đã ghi nhận");
    }

    public void onWithdraw(Cb cb) {
        LocalDate date = LocalDate.parse(cb.part(1));
        LocalDate today = time.today();
        BotSettings s = settings.load(today);
        DaySession ss = repo.session(date).orElse(null);
        boolean inTime = date.equals(today) && time.time().isBefore(s.close());
        if (ss != null && ss.is(Status.POLLING) && inTime
                && Objects.equals(ss.presetCookId(), cb.member.id())) {
            repo.setPresetCook(date, null);
            // ghi nhận đã rút -> lúc hỏi theo luồng thường sẽ bỏ qua người này
            repo.addCookRequest(date, cb.member.id(), "withdrawn", null, time.now());
            cb.editSelf(Msg.WITHDRAWN, null);
            cb.toast("Đã ghi nhận");
        } else if (!inTime) {
            cb.alert(Msg.tooLate(s.close()));
        } else {
            cb.alert("Không còn áp dụng.");
        }
    }

    // =====================================================================
    // 14:30 — chốt
    // =====================================================================

    public void doClose(LocalDate today, BotSettings s) {
        DaySession ss = repo.session(today).orElse(null);
        if (ss == null || !ss.is(Status.POLLING)) return;

        List<Member> members = repo.members();
        Map<Integer, EatResponse> eats = repo.eats(today);
        List<Member> auto = new ArrayList<>();
        for (Member m : members) {
            if (!eats.containsKey(m.id())) {
                repo.upsertEat(today, m.id(), true, "auto", time.now());
                auto.add(m);
            }
        }
        Map<Integer, EatResponse> finalEats = repo.eats(today);
        List<Member> nonEat = members.stream().filter(m -> !finalEats.get(m.id()).willEat()).toList();
        List<Member> eaters = members.stream().filter(m -> finalEats.get(m.id()).willEat()).toList();

        disableButtons(today, "poll", "precook");

        if (!nonEat.isEmpty()) {
            if (!repo.setStatus(today, Status.NO_DINNER, Status.POLLING)) return;
            broadcast(Msg.closeNoDinner(today, eaters, nonEat));
            return;
        }

        if (!repo.setStatus(today, Status.ASKING_COOK, Status.POLLING)) return;
        Member preset = repo.member(ss.presetCookId()).orElse(null);
        broadcast(Msg.closeAllEat(today, members, auto, preset));

        if (preset != null) {
            repo.addCookRequest(today, preset.id(), "yes", null, time.now());
            repo.confirmCook(today, preset.id());
            tg.send(preset.telegramId(), Msg.COOK_THANKS);
            askShopping(today, s);
        } else {
            askNextCook(today, s);
        }
    }

    // =====================================================================
    // Hỏi người nấu
    // =====================================================================

    private void askNextCook(LocalDate today, BotSettings s) {
        Set<Integer> asked = repo.requestedMembers(today);
        Map<Integer, Schedule> sch = repo.schedules(today);
        for (CookStat st : repo.cookStats()) {
            int id = st.member().id();
            if (asked.contains(id)) continue;                         // đã hỏi / đã rút
            Schedule x = sch.get(id);
            if (x != null && Boolean.FALSE.equals(x.canCook())) continue; // đặt trước "Không nấu được"

            repo.addCookRequest(today, id, "pending", null, time.now());
            Integer msg = tg.send(st.member().telegramId(), Msg.askCook(s.cookTimeoutMin()),
                    kb(row(Btn.of(Msg.BTN_YES, "cook:" + today + ":1"), Btn.of(Msg.BTN_NO, "cook:" + today + ":0"))));
            repo.setCookRequestMessage(today, id, msg);
            return;
        }
        if (repo.setStatus(today, Status.NO_COOK, Status.ASKING_COOK)) {
            broadcast(Msg.noCook(today));
        }
    }

    public void onCook(Cb cb) {
        LocalDate date = LocalDate.parse(cb.part(1));
        boolean yes = "1".equals(cb.part(2));
        LocalDate today = time.today();
        BotSettings s = settings.load(today);
        DaySession ss = repo.session(date).orElse(null);
        CookRequest p = repo.pendingCookRequest(date).orElse(null);
        if (ss == null || !ss.is(Status.ASKING_COOK) || !date.equals(today)
                || p == null || p.memberId() != cb.member.id()
                || !repo.answerCookRequest(date, cb.member.id(), yes ? "yes" : "no")) {
            cb.alert("Yêu cầu này đã hết hạn.");
            return;
        }
        if (yes) {
            repo.confirmCook(date, cb.member.id());
            cb.editSelf(Msg.COOK_THANKS, null);
            cb.toast("Cảm ơn bạn!");
            askShopping(date, s);
        } else {
            cb.editSelf(Msg.COOK_DECLINED, null);
            cb.toast("Đã ghi nhận");
            askNextCook(date, s);
        }
    }

    private void checkCookTimeout(LocalDate today, BotSettings s, LocalDateTime now) {
        CookRequest p = repo.pendingCookRequest(today).orElse(null);
        if (p == null) {
            askNextCook(today, s); // phòng trường hợp bị ngắt giữa chừng
            return;
        }
        if (now.isBefore(p.askedAt().plusMinutes(s.cookTimeoutMin()))) return;
        if (repo.answerCookRequest(today, p.memberId(), "timeout")) {
            repo.member(p.memberId()).ifPresent(m -> tg.edit(m.telegramId(), p.messageId(), Msg.COOK_TIMEOUT, null));
            askNextCook(today, s);
        }
    }

    // =====================================================================
    // Đi chợ
    // =====================================================================

    private void askShopping(LocalDate date, BotSettings s) {
        DaySession ss = repo.session(date).orElseThrow();
        Member cook = repo.member(ss.cookId()).orElseThrow();
        for (Member m : repo.members()) {
            if (m.id() == cook.id()) continue;
            Integer msg = tg.send(m.telegramId(), Msg.askShop(cook, s.shopDeadline()),
                    kb(row(Btn.of(Msg.BTN_ME, "shop:" + date))));
            repo.saveMessage(date, m.id(), "shop", msg);
        }
    }

    public void onShop(Cb cb) {
        LocalDate date = LocalDate.parse(cb.part(1));
        DaySession ss = repo.session(date).orElse(null);
        if (ss == null) {
            cb.alert("Không còn áp dụng.");
            return;
        }
        if (Objects.equals(ss.cookId(), cb.member.id())) {
            cb.alert("Bạn là người nấu hôm nay 😄");
            return;
        }
        if (ss.is(Status.COOK_CONFIRMED) && date.equals(time.today()) && repo.claimShopper(date, cb.member.id())) {
            cb.editSelf(Msg.SHOP_MINE, null);
            cb.toast("Cảm ơn!");
            notifyShopperAssigned(date, cb.member);
            return;
        }
        DaySession now = repo.session(date).orElse(ss);
        Member shopper = repo.member(now.shopperId()).orElse(null);
        if (shopper != null) {
            cb.alert(Msg.shopTaken(shopper));
            cb.editSelf(Msg.shopOther(shopper), null);
        } else {
            cb.alert("Hôm nay không còn hỏi đi chợ nữa.");
        }
    }

    /** Báo cho người không nấu còn lại (không báo người nấu), tắt nút của họ. */
    private void notifyShopperAssigned(LocalDate date, Member shopper) {
        DaySession ss = repo.session(date).orElseThrow();
        Map<Integer, Integer> msgs = repo.messages(date, "shop");
        for (Member m : repo.members()) {
            if (m.id() == shopper.id() || Objects.equals(m.id(), ss.cookId())) continue;
            tg.removeButtons(m.telegramId(), msgs.get(m.id()));
            tg.send(m.telegramId(), Msg.shopOther(shopper));
        }
    }

    /** 19:00 chưa ai nhận đi chợ -> huỷ, hoàn lượt nấu, báo cả 3. */
    private void shopFallback(LocalDate date, BotSettings s) {
        DaySession ss = repo.session(date).orElse(null);
        if (ss == null || !ss.is(Status.COOK_CONFIRMED)) return;
        Integer refunded = repo.cancelDay(date);
        disableButtons(date, "shop");
        broadcast(Msg.shopFallback(date, s.shopDeadline(), repo.member(refunded).orElse(null)));
    }

    // =====================================================================
    // Thao tác admin trên phiên hôm nay
    // =====================================================================

    public boolean adminStartPoll() {
        LocalDate today = time.today();
        BotSettings s = settings.load(today);
        repo.createSession(today, Status.PRECHECK);
        DaySession ss = repo.session(today).orElseThrow();
        if (!ss.is(Status.PRECHECK)) return false;
        doPoll(today, s);
        return true;
    }

    public boolean adminClose() {
        LocalDate today = time.today();
        DaySession ss = repo.session(today).orElse(null);
        if (ss == null || !ss.is(Status.POLLING)) return false;
        doClose(today, settings.load(today));
        return true;
    }

    public boolean adminSetEat(Member target, boolean yes) {
        LocalDate today = time.today();
        BotSettings s = settings.load(today);
        DaySession ss = repo.session(today).orElse(null);
        if (ss == null || !ss.is(Status.POLLING)) return false;
        repo.upsertEat(today, target.id(), yes, "admin", time.now());
        Integer msg = repo.messages(today, "poll").get(target.id());
        tg.edit(target.telegramId(), msg, Msg.pollChosen(today, yes, s.close()), pollKb(today));
        return true;
    }

    public boolean adminAssignCook(Member target) {
        LocalDate today = time.today();
        BotSettings s = settings.load(today);
        DaySession ss = repo.session(today).orElse(null);
        if (ss == null || !(ss.is(Status.ASKING_COOK) || ss.is(Status.NO_COOK))) return false;
        CookRequest p = repo.pendingCookRequest(today).orElse(null);
        if (p != null) {
            if (p.memberId() != target.id() && repo.answerCookRequest(today, p.memberId(), "skipped")) {
                repo.member(p.memberId()).ifPresent(m -> tg.edit(m.telegramId(), p.messageId(), Msg.COOK_SKIPPED, null));
            } else if (p.memberId() == target.id()) {
                repo.answerCookRequest(today, p.memberId(), "yes");
                tg.removeButtons(target.telegramId(), p.messageId());
            }
        }
        repo.addCookRequest(today, target.id(), "yes", null, time.now());
        repo.confirmCook(today, target.id());
        tg.send(target.telegramId(), Msg.COOK_ASSIGNED_BY_ADMIN);
        askShopping(today, s);
        return true;
    }

    public boolean adminAssignShopper(Member target) {
        LocalDate today = time.today();
        DaySession ss = repo.session(today).orElse(null);
        if (ss == null || !ss.is(Status.COOK_CONFIRMED) || Objects.equals(ss.cookId(), target.id())) return false;
        if (!repo.claimShopper(today, target.id())) return false;
        tg.removeButtons(target.telegramId(), repo.messages(today, "shop").get(target.id()));
        tg.send(target.telegramId(), Msg.SHOP_ASSIGNED_BY_ADMIN);
        notifyShopperAssigned(today, target);
        return true;
    }

    /** Huỷ hôm nay: hoàn lượt nấu (nếu có), tắt nút, báo cả 3. */
    public Member adminCancelToday() {
        LocalDate today = time.today();
        CookRequest p = repo.pendingCookRequest(today).orElse(null);
        if (p != null && repo.answerCookRequest(today, p.memberId(), "skipped")) {
            repo.member(p.memberId()).ifPresent(m -> tg.removeButtons(m.telegramId(), p.messageId()));
        }
        Member refunded = repo.member(repo.cancelDay(today)).orElse(null);
        disableButtons(today, "pre", "poll", "precook", "shop");
        broadcast(Msg.adminCancelled(today, refunded));
        return refunded;
    }

    /** Rollback lượt nấu của 1 ngày (tối đa 3 ngày gần nhất — kiểm tra ở AdminService). */
    public Member rollback(LocalDate date) {
        Member refunded = repo.member(repo.cancelDay(date)).orElse(null);
        disableButtons(date, "shop");
        return refunded;
    }

    // =====================================================================
    // Báo cáo tuần
    // =====================================================================

    private void weeklyIfDue(LocalDate today, LocalTime t, BotSettings s) {
        if (today.getDayOfWeek() != DayOfWeek.MONDAY || t.isBefore(s.weekly())) return;
        if (today.toString().equals(settings.get(SettingsService.LAST_WEEKLY))) return;
        settings.set(SettingsService.LAST_WEEKLY, today.toString());
        sendWeekly();
    }

    public void sendWeekly() {
        broadcast(weeklyReport(time.today()));
    }

    /** memberId -> chênh lệch so với người đứng giữa */
    public static Map<Integer, Integer> diffs(List<CookStat> stats) {
        Map<Integer, Integer> out = new HashMap<>();
        if (stats.isEmpty()) return out;
        List<Integer> counts = stats.stream().map(CookStat::count).sorted().toList();
        int ref = counts.get(counts.size() / 2);
        for (CookStat st : stats) out.put(st.member().id(), st.count() - ref);
        return out;
    }

    public static String fmtDiff(int diff) {
        if (diff > 0) return "+" + diff;
        if (diff < 0) return "−" + Math.abs(diff);
        return "0";
    }

    public String weeklyReport(LocalDate today) {
        List<CookStat> stats = new ArrayList<>(repo.cookStats());
        Map<Integer, Integer> diffs = diffs(stats);
        stats.sort(Comparator.comparingInt(CookStat::count).reversed()
                .thenComparing(st -> st.member().name()));

        StringBuilder sb = new StringBuilder("📊 Tình hình nấu ăn (tính đến " + Msg.dm(today) + ")\n");
        sb.append("Chênh lệch so với người đứng giữa:\n\n");
        boolean first = true;
        for (CookStat st : stats) {
            int dlt = diffs.get(st.member().id());
            String icon = dlt > 0 ? (first ? "🥇" : "🔺") : dlt == 0 ? "➖" : "🔻";
            sb.append(icon).append(" ").append(st.member().name()).append("   ").append(fmtDiff(dlt)).append("\n");
            first = false;
        }
        if (!stats.isEmpty()) {
            int min = stats.get(stats.size() - 1).count();
            int max = stats.get(0).count();
            if (min == max) {
                sb.append("\n👉 Mọi người đang bằng nhau, cứ thế phát huy nhé!");
            } else {
                List<Member> lowest = stats.stream().filter(st -> st.count() == min).map(CookStat::member).toList();
                sb.append("\n👉 ").append(Msg.names(lowest)).append(" đang nấu ít nhất, ưu tiên nấu những ngày tới nhé!");
            }
        }
        return sb.toString();
    }

    // =====================================================================
    // tiện ích
    // =====================================================================

    private void broadcast(String text) {
        for (Member m : repo.members()) tg.send(m.telegramId(), text);
    }

    private void disableButtons(LocalDate date, String... kinds) {
        Map<Integer, Member> byId = new HashMap<>();
        for (Member m : repo.members()) byId.put(m.id(), m);
        for (String kind : kinds) {
            repo.messages(date, kind).forEach((memberId, msgId) -> {
                Member m = byId.get(memberId);
                if (m != null) tg.removeButtons(m.telegramId(), msgId);
            });
        }
    }
}
