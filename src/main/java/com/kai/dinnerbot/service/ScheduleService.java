package com.kai.dinnerbot.service;

import com.kai.dinnerbot.dto.Member;
import com.kai.dinnerbot.dto.Schedule;
import com.kai.dinnerbot.repo.Repo;
import com.kai.dinnerbot.telegram.Btn;
import com.kai.dinnerbot.telegram.Cb;
import com.kai.dinnerbot.telegram.TelegramClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.kai.dinnerbot.telegram.Btn.row;

/**
 * /lich — đặt lịch ăn / nấu cho 3 ngày tới (tính từ ngày mai).
 * callback: lich:l:<memberId>            danh sách 3 ngày
 *           lich:d:<memberId>:<date>     xem 1 ngày
 *           lich:e:<memberId>:<date>:1|0 ăn / không ăn
 *           lich:c:<memberId>:<date>:1|0 nấu được / không nấu được
 *           lich:x:<memberId>:<date>     xoá lịch ngày đó
 * Ai cũng xem được lịch của người khác; chỉ chủ lịch hoặc admin được sửa.
 */
@Service
public class ScheduleService {

    public static final int DAYS = 3;

    private final Repo repo;
    private final TelegramClient tg;
    private final TimeService time;

    public ScheduleService(Repo repo, TelegramClient tg, TimeService time) {
        this.repo = repo;
        this.tg = tg;
        this.time = time;
    }

    private List<LocalDate> days() {
        List<LocalDate> list = new ArrayList<>();
        LocalDate today = time.today();
        for (int i = 1; i <= DAYS; i++) list.add(today.plusDays(i));
        return list;
    }

    public static String describe(Schedule s) {
        if (s == null || (s.willEat() == null && s.canCook() == null)) return "— chưa đặt —";
        if (Boolean.FALSE.equals(s.willEat())) return "❌ Không ăn";
        String eat = Boolean.TRUE.equals(s.willEat()) ? "🍽 Ăn" : "🍽 (chưa chọn)";
        if (s.canCook() == null) return eat;
        return eat + " · " + (s.canCook() ? "👨‍🍳 Nấu được" : "🙅 Không nấu được");
    }

    // ---------------- màn hình ----------------

    /** Gửi mới (khi gõ /lich). */
    public void sendList(Member viewer) {
        tg.send(viewer.telegramId(), listText(viewer, viewer), listKb(viewer, viewer));
    }

    private String listText(Member viewer, Member target) {
        return viewer.id() == target.id()
                ? "📅 Lịch 3 ngày tới — chọn ngày:"
                : "📅 Lịch 3 ngày tới của " + target.name() + " — chọn ngày:";
    }

    private List<List<Btn>> listKb(Member viewer, Member target) {
        List<List<Btn>> kb = new ArrayList<>();
        for (LocalDate d : days()) {
            Schedule s = repo.schedule(d, target.id()).orElse(null);
            kb.add(row(Btn.of(Msg.dayShort(d) + " " + Msg.dm(d) + ": " + describe(s),
                    "lich:d:" + target.id() + ":" + d)));
        }
        if (viewer.id() != target.id()) kb.add(row(Btn.of("⬅️ Quay lại", "adm:sch")));
        return kb;
    }

    private String dayText(Member viewer, Member target, LocalDate d) {
        Schedule s = repo.schedule(d, target.id()).orElse(null);
        String who = viewer.id() == target.id() ? "" : " — " + target.name();
        String eat = s == null || s.willEat() == null ? "(chưa chọn)" : s.willEat() ? "✅ Ăn" : "❌ Không ăn";
        StringBuilder sb = new StringBuilder("📅 " + Msg.dayName(d) + ", " + Msg.dm(d) + who + "\n");
        sb.append("Ăn tối: ").append(eat);
        if (s == null || !Boolean.FALSE.equals(s.willEat())) {
            String cook = s == null || s.canCook() == null ? "(chưa chọn)" : s.canCook() ? "👨‍🍳 Nấu được" : "🙅 Không nấu được";
            sb.append("\nNấu ăn: ").append(cook);
        }
        if (!canEdit(viewer, target)) sb.append("\n\n").append(Msg.VIEW_ONLY);
        return sb.toString();
    }

    private List<List<Btn>> dayKb(Member viewer, Member target, LocalDate d) {
        List<List<Btn>> kb = new ArrayList<>();
        String p = target.id() + ":" + d;
        if (canEdit(viewer, target)) {
            Schedule s = repo.schedule(d, target.id()).orElse(null);
            Boolean eat = s == null ? null : s.willEat();
            Boolean cook = s == null ? null : s.canCook();
            kb.add(row(Btn.of(mark(Boolean.TRUE.equals(eat)) + "✅ Ăn", "lich:e:" + p + ":1"),
                    Btn.of(mark(Boolean.FALSE.equals(eat)) + "❌ Không ăn", "lich:e:" + p + ":0")));
            if (!Boolean.FALSE.equals(eat)) {
                kb.add(row(Btn.of(mark(Boolean.TRUE.equals(cook)) + "👨‍🍳 Nấu được", "lich:c:" + p + ":1"),
                        Btn.of(mark(Boolean.FALSE.equals(cook)) + "🙅 Không nấu được", "lich:c:" + p + ":0")));
            }
            kb.add(row(Btn.of("🗑 Xoá lịch ngày này", "lich:x:" + p),
                    Btn.of("⬅️ Quay lại", "lich:l:" + target.id())));
        } else {
            kb.add(row(Btn.of("⬅️ Quay lại", "lich:l:" + target.id())));
        }
        return kb;
    }

    private static String mark(boolean selected) {
        return selected ? "• " : "";
    }

    private static boolean canEdit(Member viewer, Member target) {
        return viewer.id() == target.id() || viewer.isAdmin();
    }

    // ---------------- callback ----------------

    public void onCallback(Cb cb) {
        String action = cb.part(1);
        Member viewer = cb.member;
        Member target = repo.member(cb.intPart(2)).orElse(null);
        if (target == null) {
            cb.alert("Không tìm thấy thành viên.");
            return;
        }
        if ("l".equals(action)) {
            cb.editSelf(listText(viewer, target), listKb(viewer, target));
            cb.done();
            return;
        }

        LocalDate d = LocalDate.parse(cb.part(3));
        if (!days().contains(d)) {
            cb.alert("Ngày này không còn trong khoảng đặt lịch.");
            cb.editSelf(listText(viewer, target), listKb(viewer, target));
            return;
        }
        if (!"d".equals(action) && !canEdit(viewer, target)) {
            cb.alert(Msg.VIEW_ONLY);
            return;
        }
        switch (action) {
            case "e" -> {
                boolean yes = "1".equals(cb.part(4));
                repo.setScheduleEat(d, target.id(), yes);
                if (!yes) repo.setScheduleCook(d, target.id(), null);
                log(viewer, target, d, "ăn tối = " + (yes ? "Ăn" : "Không ăn"));
            }
            case "c" -> {
                boolean yes = "1".equals(cb.part(4));
                repo.setScheduleCook(d, target.id(), yes);
                log(viewer, target, d, "nấu = " + (yes ? "Nấu được" : "Không nấu được"));
            }
            case "x" -> {
                repo.deleteSchedule(d, target.id());
                log(viewer, target, d, "xoá lịch");
            }
            default -> {
            }
        }
        cb.editSelf(dayText(viewer, target, d), dayKb(viewer, target, d));
        cb.toast("x".equals(action) ? "Đã xoá" : "d".equals(action) ? null : "Đã lưu");
    }

    private void log(Member viewer, Member target, LocalDate d, String what) {
        if (viewer.id() != target.id()) {
            repo.adminLog(viewer.id(), "Sửa lịch " + target.name() + " ngày " + d + ": " + what);
        }
    }
}
