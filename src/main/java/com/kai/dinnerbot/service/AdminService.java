package com.kai.dinnerbot.service;

import com.kai.dinnerbot.dto.*;
import com.kai.dinnerbot.model.*;
import com.kai.dinnerbot.repo.Repo;
import com.kai.dinnerbot.telegram.Btn;
import com.kai.dinnerbot.telegram.Cb;
import com.kai.dinnerbot.telegram.TelegramClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.kai.dinnerbot.telegram.Btn.row;

/**
 * /admin — mọi thành viên đều xem được; chỉ role = admin mới thấy & dùng các nút sửa.
 * Mọi thao tác sửa đều ghi vào admin_logs.
 */
@Service
public class AdminService {

    private static final int ROLLBACK_DAYS = 3;
    /** Chỉ hiện (và giữ) người lạ nhắn bot trong số ngày này. */
    public static final int JOIN_REQUEST_DAYS = 7;
    private static final int JOIN_REQUEST_LIMIT = 10;
    private static final DateTimeFormatter DM_HM = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("d/M/yyyy");
    private static final DateTimeFormatter DMY_OUT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Repo repo;
    private final TelegramClient tg;
    private final DinnerFlowService flow;
    private final SettingsService settings;
    private final TimeService time;

    /** Đang chờ admin nhập chữ: telegramId -> việc cần làm */
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(String action, String arg) {
    }

    private record View(String text, List<List<Btn>> kb) {
    }

    public AdminService(Repo repo, TelegramClient tg, DinnerFlowService flow,
                        SettingsService settings, TimeService time) {
        this.repo = repo;
        this.tg = tg;
        this.flow = flow;
        this.settings = settings;
        this.time = time;
    }

    private static List<Btn> back() {
        return row(Btn.of("⬅️ Menu", "adm:menu"));
    }

    private void log(Member admin, String action) {
        repo.adminLog(admin.id(), action);
    }

    // =====================================================================
    // Vào menu
    // =====================================================================

    public void sendMenu(Member me) {
        View v = menu(me);
        tg.send(me.telegramId(), v.text(), v.kb());
    }

    private View menu(Member me) {
        String text = "⚙️ Quản trị" + (me.isAdmin() ? "" : "\n👁 Chế độ chỉ xem");
        List<List<Btn>> kb = new ArrayList<>();
        kb.add(row(Btn.of("👥 Thành viên", "adm:mem"), Btn.of("🔢 Lượt nấu", "adm:cnt")));
        kb.add(row(Btn.of("📋 Hôm nay", "adm:today"), Btn.of("📅 Lịch đặt trước", "adm:sch")));
        kb.add(row(Btn.of("⏰ Cấu hình giờ", "adm:cfg"), Btn.of("⏸ Tạm dừng", "adm:pause")));
        if (me.isAdmin()) kb.add(row(Btn.of("📊 Gửi báo cáo ngay", "adm:rpt")));
        return new View(text, kb);
    }

    // =====================================================================
    // Callback
    // =====================================================================

    public void onCallback(Cb cb) {
        Member me = cb.member;
        String a = cb.part(1);
        boolean mutate = switch (a) {
            case "menu", "today", "sch" -> false;
            case "mem", "cnt", "cfg", "pause", "rb" -> !cb.part(2).isEmpty();
            default -> true;
        };
        if (mutate && !me.isAdmin()) {
            cb.alert(Msg.VIEW_ONLY);
            return;
        }

        switch (a) {
            case "menu" -> show(cb, menu(me));
            case "mem" -> onMembers(cb);
            case "cnt" -> onCounts(cb);
            case "rb" -> onRollbackPick(cb);
            case "rbok" -> onRollbackConfirm(cb);
            case "today" -> show(cb, today(me));
            case "te", "close", "poll", "tc", "ts", "cancel", "cancelok" -> onTodayAction(cb, a);
            case "sch" -> show(cb, schedules());
            case "cfg" -> onConfig(cb);
            case "pause" -> onPause(cb);
            case "rpt" -> {
                flow.sendWeekly();
                log(me, "Gửi báo cáo tuần thủ công");
                cb.toast("Đã gửi báo cáo cho cả nhà");
            }
            default -> cb.alert("Không rõ thao tác.");
        }
        cb.done();
    }

    private void show(Cb cb, View v) {
        cb.editSelf(v.text(), v.kb());
    }

    // =====================================================================
    // Thành viên
    // =====================================================================

    private View members(Member me) {
        List<Member> list = repo.members();
        StringBuilder sb = new StringBuilder("👥 Thành viên (" + list.size() + "/" + Member.MAX + ")\n");
        List<List<Btn>> kb = new ArrayList<>();
        for (Member m : list) {
            sb.append("\n• ").append(m.name()).append(m.isAdmin() ? " (admin)" : "")
                    .append(" — ID ").append(m.telegramId())
                    .append(" — ").append(m.started() ? "✅ đã /start" : "⚠️ chưa /start");
            if (me.isAdmin()) {
                kb.add(row(Btn.of("✏️ Tên: " + m.name(), "adm:mem:rn:" + m.id()),
                        Btn.of("🆔 ID: " + m.name(), "adm:mem:tg:" + m.id())));
            }
        }
        if (me.isAdmin() && list.size() < Member.MAX) {
            kb.add(row(Btn.of("➕ Thêm thành viên", "adm:mem:add")));
        }
        kb.add(back());
        return new View(sb.toString(), kb);
    }

    /** Danh sách người lạ nhắn bot gần đây để admin bấm thêm nhanh. */
    private View addMember() {
        int free = Member.MAX - repo.countMembers();
        List<List<Btn>> kb = new ArrayList<>();
        if (free <= 0) {
            kb.add(row(Btn.of("⬅️ Quay lại", "adm:mem")));
            return new View("✅ Đã đủ " + Member.MAX + " thành viên, không thêm được nữa.", kb);
        }
        List<JoinRequest> reqs = repo.joinRequests(time.now().minusDays(JOIN_REQUEST_DAYS), JOIN_REQUEST_LIMIT);
        StringBuilder sb = new StringBuilder("➕ Thêm thành viên — còn " + free + " chỗ\n");
        if (reqs.isEmpty()) {
            sb.append("\nChưa có ai nhắn bot trong ").append(JOIN_REQUEST_DAYS).append(" ngày qua.")
                    .append("\nNhờ người cần thêm nhắn bot một tin bất kỳ, rồi bấm 🔄 Làm mới.");
        } else {
            sb.append("\nNgười nhắn bot gần đây — bấm để thêm:");
            for (JoinRequest r : reqs) {
                sb.append("\n• ").append(r.name())
                        .append(r.username() != null ? " (@" + r.username() + ")" : "")
                        .append(" — ID ").append(r.telegramId())
                        .append(" — ").append(r.lastSeen().format(DM_HM));
                kb.add(row(Btn.of("➕ " + r.name(), "adm:mem:addok:" + r.telegramId())));
            }
        }
        kb.add(row(Btn.of("🔄 Làm mới", "adm:mem:add"), Btn.of("⬅️ Quay lại", "adm:mem")));
        return new View(sb.toString(), kb);
    }

    private void onAddMember(Cb cb) {
        long tgId;
        try {
            tgId = Long.parseLong(cb.part(3));
        } catch (NumberFormatException e) {
            cb.alert("Nút không hợp lệ.");
            return;
        }
        if (repo.countMembers() >= Member.MAX) {
            cb.alert("Đã đủ " + Member.MAX + " thành viên.");
            show(cb, members(cb.member));
            return;
        }
        if (repo.memberByTg(tgId).isPresent()) {
            cb.alert("Người này đã là thành viên.");
            show(cb, members(cb.member));
            return;
        }
        JoinRequest r = repo.joinRequest(tgId).orElse(null);
        if (r == null) {
            cb.alert("Yêu cầu đã hết hạn — nhờ người đó nhắn bot lại.");
            show(cb, addMember());
            return;
        }
        repo.insertMember(tgId, r.name(), "member");
        repo.deleteJoinRequest(tgId);
        Member added = repo.memberByTg(tgId).orElseThrow();
        repo.setStarted(added.id()); // họ đã nhắn bot nên bot nhắn lại được
        log(cb.member, "Thêm thành viên " + r.name() + " (" + tgId + ")");
        tg.send(tgId, "✅ " + cb.member.name() + " đã thêm bạn vào bot.\n"
                + Msg.start(added, settings.load(time.today()).poll()));
        cb.toast("Đã thêm " + r.name());
        show(cb, members(cb.member));
    }

    private void onMembers(Cb cb) {
        String sub = cb.part(2);
        if (sub.isEmpty()) {
            show(cb, members(cb.member));
            return;
        }
        if ("add".equals(sub)) {
            show(cb, addMember());
            return;
        }
        if ("addok".equals(sub)) {
            onAddMember(cb);
            return;
        }
        Member target = repo.member(cb.intPart(3)).orElse(null);
        if (target == null) {
            cb.alert("Không tìm thấy thành viên.");
            return;
        }
        if ("rn".equals(sub)) {
            ask(cb.member, new Pending("rn", String.valueOf(target.id())),
                    "✏️ Nhập tên mới cho " + target.name() + " (gõ /huy để huỷ):");
        } else if ("tg".equals(sub)) {
            ask(cb.member, new Pending("tg", String.valueOf(target.id())),
                    "🆔 Nhập Telegram ID mới cho " + target.name()
                            + " (người đó nhắn /start cho bot sẽ thấy ID của mình; gõ /huy để huỷ):");
        }
    }

    // =====================================================================
    // Lượt nấu + rollback
    // =====================================================================

    private View counts(Member me) {
        List<CookStat> stats = new ArrayList<>(repo.cookStats());
        Map<Integer, Integer> diffs = DinnerFlowService.diffs(stats);
        stats.sort(Comparator.comparingInt(CookStat::count).reversed());
        StringBuilder sb = new StringBuilder("🔢 Lượt nấu (chênh so với người đứng giữa)\n");
        List<List<Btn>> kb = new ArrayList<>();
        for (CookStat st : stats) {
            Member m = st.member();
            sb.append("\n• ").append(m.name()).append(": ").append(st.count()).append(" lần (")
                    .append(DinnerFlowService.fmtDiff(diffs.get(m.id()))).append(")");
            if (me.isAdmin()) {
                kb.add(row(Btn.of("➖ " + m.name(), "adm:cnt:dec:" + m.id()),
                        Btn.of("➕ " + m.name(), "adm:cnt:inc:" + m.id()),
                        Btn.of("✏️ " + m.name(), "adm:cnt:set:" + m.id())));
            }
        }
        if (me.isAdmin()) kb.add(row(Btn.of("↩️ Rollback lượt nấu", "adm:rb")));
        kb.add(back());
        return new View(sb.toString(), kb);
    }

    private void onCounts(Cb cb) {
        String sub = cb.part(2);
        if (sub.isEmpty()) {
            show(cb, counts(cb.member));
            return;
        }
        Member target = repo.member(cb.intPart(3)).orElse(null);
        if (target == null) {
            cb.alert("Không tìm thấy thành viên.");
            return;
        }
        switch (sub) {
            case "inc", "dec" -> {
                int delta = "inc".equals(sub) ? 1 : -1;
                repo.adjustCount(target.id(), delta);
                log(cb.member, "Lượt nấu " + target.name() + " " + (delta > 0 ? "+1" : "-1"));
                cb.toast("Đã cập nhật");
                show(cb, counts(cb.member));
            }
            case "set" -> ask(cb.member, new Pending("setcnt", String.valueOf(target.id())),
                    "✏️ Nhập số lần nấu mới cho " + target.name() + " (gõ /huy để huỷ):");
            default -> cb.alert("Không rõ thao tác.");
        }
    }

    private boolean inRollbackRange(LocalDate d) {
        LocalDate today = time.today();
        return !d.isAfter(today) && !d.isBefore(today.minusDays(ROLLBACK_DAYS));
    }

    private void onRollbackPick(Cb cb) {
        String arg = cb.part(2);
        LocalDate today = time.today();
        if (arg.isEmpty()) {
            List<DaySession> list = repo.cookedSessions(today.minusDays(ROLLBACK_DAYS), today);
            List<List<Btn>> kb = new ArrayList<>();
            for (DaySession s : list) {
                String cook = repo.member(s.cookId()).map(Member::name).orElse("?");
                kb.add(row(Btn.of(Msg.dm(s.date()) + " — " + cook + " nấu", "adm:rb:" + s.date())));
            }
            kb.add(row(Btn.of("⬅️ Quay lại", "adm:cnt")));
            String text = list.isEmpty()
                    ? "↩️ Không có ngày nào đã chốt người nấu trong " + ROLLBACK_DAYS + " ngày gần nhất."
                    : "↩️ Chọn ngày cần rollback:";
            show(cb, new View(text, kb));
            return;
        }
        LocalDate d = LocalDate.parse(arg);
        DaySession s = repo.session(d).orElse(null);
        if (!inRollbackRange(d) || s == null || s.cookId() == null || !Status.COOKED.contains(s.status())) {
            cb.alert("Ngày này không rollback được.");
            return;
        }
        String cook = repo.member(s.cookId()).map(Member::name).orElse("?");
        show(cb, new View("Rollback ngày " + Msg.dm(d) + ": trừ 1 lượt nấu của " + cook
                + " và đánh dấu ngày đó \"không nấu\"?",
                List.of(row(Btn.of("✅ Xác nhận", "adm:rbok:" + d), Btn.of("❌ Huỷ", "adm:rb")))));
    }

    private void onRollbackConfirm(Cb cb) {
        LocalDate d = LocalDate.parse(cb.part(2));
        DaySession s = repo.session(d).orElse(null);
        if (!inRollbackRange(d) || s == null || s.cookId() == null || !Status.COOKED.contains(s.status())) {
            cb.alert("Ngày này không rollback được.");
            return;
        }
        Member refunded = flow.rollback(d);
        log(cb.member, "Rollback ngày " + d + (refunded != null ? " — hoàn 1 lượt của " + refunded.name() : ""));
        cb.toast("Đã rollback");
        show(cb, counts(cb.member));
    }

    // =====================================================================
    // Hôm nay
    // =====================================================================

    private static String sourceLabel(String src) {
        return switch (src) {
            case "click" -> "tự chọn";
            case "preset" -> "đặt trước";
            case "auto" -> "không trả lời";
            case "admin" -> "admin chỉnh";
            default -> src;
        };
    }

    private View today(Member me) {
        LocalDate today = time.today();
        DaySession ss = repo.session(today).orElse(null);
        String status = ss == null ? null : ss.status();
        List<Member> members = repo.members();
        Map<Integer, EatResponse> eats = repo.eats(today);

        StringBuilder sb = new StringBuilder("📋 Hôm nay (" + Msg.dm(today) + ") — " + Status.label(status) + "\n");
        if (settings.isPaused(today)) sb.append("⏸ Hôm nay đang tạm dừng.\n");
        sb.append("\nĂn tối:");
        for (Member m : members) {
            EatResponse e = eats.get(m.id());
            sb.append("\n• ").append(m.name()).append(": ");
            sb.append(e == null ? "… chưa trả lời"
                    : (e.willEat() ? "✅ Có" : "❌ Không") + " (" + sourceLabel(e.source()) + ")");
        }
        if (ss != null) {
            if (ss.presetCookId() != null && ss.is(Status.POLLING)) {
                sb.append("\n\n👨‍🍳 Nấu định sẵn: ").append(repo.member(ss.presetCookId()).map(Member::name).orElse("?"));
            }
            if (ss.is(Status.ASKING_COOK)) {
                repo.pendingCookRequest(today).flatMap(p -> repo.member(p.memberId()))
                        .ifPresent(m -> sb.append("\n\n⏳ Đang hỏi nấu: ").append(m.name()));
            }
            if (ss.cookId() != null) {
                sb.append("\n\n👨‍🍳 Người nấu: ").append(repo.member(ss.cookId()).map(Member::name).orElse("?"));
            }
            if (ss.shopperId() != null) {
                sb.append("\n🛒 Đi chợ: ").append(repo.member(ss.shopperId()).map(Member::name).orElse("?"));
            }
        }

        List<List<Btn>> kb = new ArrayList<>();
        if (me.isAdmin()) {
            if (ss == null || ss.is(Status.PRECHECK)) {
                kb.add(row(Btn.of("▶️ Hỏi ăn tối ngay", "adm:poll")));
            }
            if (ss != null && ss.is(Status.POLLING)) {
                for (Member m : members) {
                    kb.add(row(Btn.of("✅ " + m.name() + " ăn", "adm:te:" + m.id() + ":1"),
                            Btn.of("❌ " + m.name() + " không ăn", "adm:te:" + m.id() + ":0")));
                }
                kb.add(row(Btn.of("⏩ Chốt ngay", "adm:close")));
            }
            if (ss != null && (ss.is(Status.ASKING_COOK) || ss.is(Status.NO_COOK))) {
                for (Member m : members) kb.add(row(Btn.of("👨‍🍳 Giao nấu: " + m.name(), "adm:tc:" + m.id())));
            }
            if (ss != null && ss.is(Status.COOK_CONFIRMED)) {
                for (Member m : members) {
                    if (!Objects.equals(m.id(), ss.cookId())) {
                        kb.add(row(Btn.of("🛒 Giao đi chợ: " + m.name(), "adm:ts:" + m.id())));
                    }
                }
            }
            if (ss == null || Status.ACTIVE.contains(status) || ss.is(Status.SHOPPER_ASSIGNED)) {
                kb.add(row(Btn.of("🚫 Huỷ hôm nay", "adm:cancel")));
            }
        }
        kb.add(row(Btn.of("🔄 Làm mới", "adm:today"), Btn.of("⬅️ Menu", "adm:menu")));
        return new View(sb.toString(), kb);
    }

    private void onTodayAction(Cb cb, String a) {
        Member me = cb.member;
        boolean ok;
        switch (a) {
            case "poll" -> {
                ok = flow.adminStartPoll();
                if (ok) log(me, "Chạy bước hỏi ăn tối ngay");
            }
            case "close" -> {
                ok = flow.adminClose();
                if (ok) log(me, "Chốt danh sách sớm");
            }
            case "te" -> {
                Member target = repo.member(cb.intPart(2)).orElse(null);
                boolean yes = "1".equals(cb.part(3));
                ok = target != null && flow.adminSetEat(target, yes);
                if (ok) log(me, "Sửa ăn tối " + target.name() + " = " + (yes ? "Có" : "Không"));
            }
            case "tc" -> {
                Member target = repo.member(cb.intPart(2)).orElse(null);
                ok = target != null && flow.adminAssignCook(target);
                if (ok) log(me, "Giao nấu cho " + target.name());
            }
            case "ts" -> {
                Member target = repo.member(cb.intPart(2)).orElse(null);
                ok = target != null && flow.adminAssignShopper(target);
                if (ok) log(me, "Giao đi chợ cho " + target.name());
            }
            case "cancel" -> {
                show(cb, new View("🚫 Huỷ hôm nay? Nếu đã có người nấu sẽ hoàn lại 1 lượt và báo cho cả nhà.",
                        List.of(row(Btn.of("✅ Xác nhận huỷ", "adm:cancelok"), Btn.of("❌ Không", "adm:today")))));
                return;
            }
            case "cancelok" -> {
                Member refunded = flow.adminCancelToday();
                log(me, "Huỷ hôm nay" + (refunded != null ? " — hoàn 1 lượt của " + refunded.name() : ""));
                ok = true;
            }
            default -> ok = false;
        }
        if (ok) cb.toast("Đã thực hiện");
        else cb.alert("Không thực hiện được ở trạng thái hiện tại.");
        show(cb, today(me));
    }

    // =====================================================================
    // Lịch đặt trước (tổng quan)
    // =====================================================================

    private View schedules() {
        LocalDate today = time.today();
        List<Member> members = repo.members();
        StringBuilder sb = new StringBuilder("📅 Lịch đặt trước 3 ngày tới\n");
        for (int i = 1; i <= ScheduleService.DAYS; i++) {
            LocalDate d = today.plusDays(i);
            Map<Integer, Schedule> sch = repo.schedules(d);
            sb.append("\n").append(Msg.dayShort(d)).append(" ").append(Msg.dm(d)).append(":");
            for (Member m : members) {
                sb.append("\n   • ").append(m.name()).append(": ").append(ScheduleService.describe(sch.get(m.id())));
            }
            sb.append("\n");
        }
        sb.append("\nChọn thành viên để xem chi tiết:");
        List<List<Btn>> kb = new ArrayList<>();
        List<Btn> r = new ArrayList<>();
        for (Member m : members) r.add(Btn.of("📅 " + m.name(), "lich:l:" + m.id()));
        kb.add(r);
        kb.add(back());
        return new View(sb.toString(), kb);
    }

    // =====================================================================
    // Cấu hình giờ + tạm dừng
    // =====================================================================

    private View config(Member me) {
        BotSettings s = settings.load(time.today());
        Map<String, String> next = settings.pendingChanges();
        Map<String, String> cur = new LinkedHashMap<>();
        cur.put(SettingsService.PRECHECK, Msg.hm(s.precheck()));
        cur.put(SettingsService.POLL, Msg.hm(s.poll()));
        cur.put(SettingsService.CLOSE, Msg.hm(s.close()));
        cur.put(SettingsService.SHOP_DEADLINE, Msg.hm(s.shopDeadline()));
        cur.put(SettingsService.COOK_TIMEOUT, s.cookTimeoutMin() + " phút");
        cur.put(SettingsService.WEEKLY, "T2 " + Msg.hm(s.weekly()));

        StringBuilder sb = new StringBuilder("⏰ Cấu hình giờ\n");
        List<List<Btn>> kb = new ArrayList<>();
        List<Btn> r = new ArrayList<>();
        for (Map.Entry<String, String> e : SettingsService.LABELS.entrySet()) {
            sb.append("\n").append(e.getValue()).append(": ").append(cur.get(e.getKey()));
            if (next.containsKey(e.getKey())) sb.append("  → ").append(next.get(e.getKey())).append(" (từ ngày mai)");
            if (me.isAdmin()) {
                r.add(Btn.of("✏️ " + e.getValue(), "adm:cfg:" + e.getKey()));
                if (r.size() == 2) {
                    kb.add(r);
                    r = new ArrayList<>();
                }
            }
        }
        if (!r.isEmpty()) kb.add(r);
        kb.add(back());
        return new View(sb.toString(), kb);
    }

    private void onConfig(Cb cb) {
        String key = cb.part(2);
        if (key.isEmpty()) {
            show(cb, config(cb.member));
            return;
        }
        String label = SettingsService.LABELS.get(key);
        if (label == null) {
            cb.alert("Không có cấu hình này.");
            return;
        }
        String hint = SettingsService.COOK_TIMEOUT.equals(key) ? "số phút, ví dụ 20" : "giờ dạng HH:mm, ví dụ 14:45";
        ask(cb.member, new Pending("cfg", key),
                "✏️ Nhập giá trị mới cho \"" + label + "\" (" + hint + "; gõ /huy để huỷ):");
    }

    private View pause(Member me) {
        LocalDate[] range = settings.pauseRange();
        String text = "⏸ Tạm dừng\n\n" + (range == null
                ? "Bot đang chạy bình thường mỗi ngày."
                : "Tạm dừng từ " + range[0].format(DMY_OUT) + " đến " + range[1].format(DMY_OUT) + ".");
        List<List<Btn>> kb = new ArrayList<>();
        if (me.isAdmin()) {
            kb.add(row(Btn.of("⏸ Đặt khoảng tạm dừng", "adm:pause:set")));
            if (range != null) kb.add(row(Btn.of("▶️ Bỏ tạm dừng", "adm:pause:clr")));
        }
        kb.add(back());
        return new View(text, kb);
    }

    private void onPause(Cb cb) {
        String sub = cb.part(2);
        if (sub.isEmpty()) {
            show(cb, pause(cb.member));
        } else if ("set".equals(sub)) {
            ask(cb.member, new Pending("pause", ""),
                    "⏸ Nhập khoảng ngày tạm dừng dạng dd/MM/yyyy-dd/MM/yyyy\nví dụ: 20/11/2026-25/11/2026 (gõ /huy để huỷ):");
        } else if ("clr".equals(sub)) {
            settings.clearPause();
            log(cb.member, "Bỏ tạm dừng");
            cb.toast("Đã bỏ tạm dừng");
            show(cb, pause(cb.member));
        }
    }

    // =====================================================================
    // Nhập chữ
    // =====================================================================

    private void ask(Member me, Pending p, String prompt) {
        pending.put(me.telegramId(), p);
        tg.send(me.telegramId(), prompt);
    }

    public void clearPending(Member me) {
        pending.remove(me.telegramId());
    }

    /** @return true nếu tin nhắn này là câu trả lời cho một yêu cầu nhập. */
    public boolean handleText(Member me, String text) {
        Pending p = pending.remove(me.telegramId());
        if (p == null) return false;
        long chat = me.telegramId();
        if ("/huy".equalsIgnoreCase(text.trim())) {
            tg.send(chat, "Đã huỷ.");
            return true;
        }
        if (!me.isAdmin()) {
            tg.send(chat, Msg.VIEW_ONLY);
            return true;
        }
        String input = text.trim();
        try {
            switch (p.action()) {
                case "rn" -> {
                    Member target = repo.member(Integer.parseInt(p.arg())).orElseThrow();
                    if (input.isEmpty() || input.length() > 50) throw new IllegalArgumentException("❌ Tên phải từ 1 đến 50 ký tự.");
                    repo.renameMember(target.id(), input);
                    log(me, "Đổi tên " + target.name() + " → " + input);
                    tg.send(chat, "✅ Đã đổi tên: " + target.name() + " → " + input);
                }
                case "tg" -> {
                    Member target = repo.member(Integer.parseInt(p.arg())).orElseThrow();
                    long newId;
                    try {
                        newId = Long.parseLong(input);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("❌ ID phải là số.");
                    }
                    Optional<Member> other = repo.memberByTg(newId);
                    if (other.isPresent() && other.get().id() != target.id()) {
                        throw new IllegalArgumentException("❌ ID này đang thuộc về " + other.get().name() + ".");
                    }
                    repo.changeTelegramId(target.id(), newId);
                    log(me, "Đổi Telegram ID " + target.name() + ": " + target.telegramId() + " → " + newId);
                    tg.send(chat, "✅ Đã đổi ID của " + target.name() + " → " + newId
                            + ". Người này cần nhắn /start cho bot.");
                }
                case "setcnt" -> {
                    Member target = repo.member(Integer.parseInt(p.arg())).orElseThrow();
                    int n;
                    try {
                        n = Integer.parseInt(input);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("❌ Nhập một số nguyên ≥ 0.");
                    }
                    if (n < 0) throw new IllegalArgumentException("❌ Nhập một số nguyên ≥ 0.");
                    repo.setCount(target.id(), n);
                    log(me, "Đặt lượt nấu " + target.name() + " = " + n);
                    tg.send(chat, "✅ Đã đặt lượt nấu của " + target.name() + " = " + n);
                    View v = counts(me);
                    tg.send(chat, v.text(), v.kb());
                }
                case "cfg" -> {
                    String result = settings.update(p.arg(), input, time.now());
                    log(me, "Cấu hình " + p.arg() + " = " + input);
                    tg.send(chat, result);
                    View v = config(me);
                    tg.send(chat, v.text(), v.kb());
                }
                case "pause" -> {
                    String[] parts = input.split("\\s*-\\s*");
                    if (parts.length != 2) throw new IllegalArgumentException("❌ Sai định dạng. Ví dụ: 20/11/2026-25/11/2026");
                    LocalDate from, to;
                    try {
                        from = LocalDate.parse(parts[0].trim(), DMY);
                        to = LocalDate.parse(parts[1].trim(), DMY);
                    } catch (DateTimeParseException e) {
                        throw new IllegalArgumentException("❌ Sai định dạng ngày. Ví dụ: 20/11/2026-25/11/2026");
                    }
                    if (to.isBefore(from)) throw new IllegalArgumentException("❌ Ngày kết thúc phải sau ngày bắt đầu.");
                    settings.setPause(from, to);
                    log(me, "Tạm dừng " + from + " → " + to);
                    tg.send(chat, "✅ Bot tạm dừng từ " + from.format(DMY_OUT) + " đến " + to.format(DMY_OUT) + ".");
                }
                default -> tg.send(chat, "Không rõ thao tác.");
            }
        } catch (IllegalArgumentException e) {
            // cho nhập lại
            pending.put(chat, p);
            tg.send(chat, e.getMessage() + "\nNhập lại hoặc gõ /huy để huỷ.");
        }
        return true;
    }
}
