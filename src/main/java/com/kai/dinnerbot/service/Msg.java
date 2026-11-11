package com.kai.dinnerbot.service;

import com.kai.dinnerbot.dto.Member;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/** Toàn bộ nội dung tin nhắn — sửa câu chữ ở đây. */
public final class Msg {

    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    public static String dm(LocalDate d) {
        return d.format(DM);
    }

    public static String hm(LocalTime t) {
        return t.format(HM);
    }

    public static String dayName(LocalDate d) {
        return switch (d.getDayOfWeek()) {
            case MONDAY -> "Thứ Hai";
            case TUESDAY -> "Thứ Ba";
            case WEDNESDAY -> "Thứ Tư";
            case THURSDAY -> "Thứ Năm";
            case FRIDAY -> "Thứ Sáu";
            case SATURDAY -> "Thứ Bảy";
            case SUNDAY -> "Chủ Nhật";
        };
    }

    public static String dayShort(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.SUNDAY ? "CN" : "T" + (w.getValue() + 1);
    }

    public static String names(List<Member> ms) {
        return ms.stream().map(Member::name).collect(Collectors.joining(", "));
    }

    // ================= 13:00 =================

    public static String precheck(LocalDate d, LocalTime poll) {
        return "📅 Lịch hôm nay (" + dayName(d) + ", " + dm(d) + ")\n"
                + "Bạn đã đăng ký KHÔNG ĂN TỐI hôm nay → hôm nay sẽ không nấu.\n"
                + "Bạn có thể đổi đến " + hm(poll) + ".";
    }

    public static final String BTN_PRECHECK_CHANGE = "🔄 Đổi thành Có ăn";

    public static String precheckChanged(LocalTime poll) {
        return "✅ Đã đổi: Bạn CÓ ĂN tối hôm nay. " + hm(poll) + " sẽ chốt danh sách như bình thường.";
    }

    public static String tooLate(LocalTime t) {
        return "⏰ Đã chốt lúc " + hm(t) + ", không thể thay đổi.";
    }

    // ================= 14:00 =================

    public static String noDinnerPreset(LocalDate d, List<Member> notEating) {
        return "📢 Hôm nay (" + dm(d) + ") " + names(notEating) + " không ăn tối → hôm nay KHÔNG NẤU.";
    }

    public static String poll(LocalDate d, LocalTime close) {
        return "🍽 Hôm nay (" + dm(d) + ") bạn có ăn tối không?\n\n"
                + "⏰ Chốt lúc " + hm(close) + ".\n"
                + "⚠️ Không trả lời = CÓ ĂN.";
    }

    public static String pollChosen(LocalDate d, boolean yes, LocalTime close) {
        return "🍽 Hôm nay (" + dm(d) + ") bạn có ăn tối không?\n"
                + "→ Bạn đã chọn: " + (yes ? "✅ CÓ" : "❌ KHÔNG") + "\n"
                + "Có thể đổi đến " + hm(close) + ".";
    }

    public static final String BTN_YES = "✅ Có";
    public static final String BTN_NO = "❌ Không";

    public static String presetEat(LocalDate d, LocalTime close) {
        return "🍽 Hôm nay (" + dm(d) + ") bạn đã đăng ký: ✅ CÓ ĂN tối.\n"
                + "Có thể sửa đến " + hm(close) + ".";
    }

    public static final String BTN_CHANGE_TO_NO = "❌ Đổi thành Không ăn";

    public static String presetCook(LocalTime close) {
        return "👨‍🍳 Nếu hôm nay mọi người đều ăn, BẠN sẽ là người nấu.\n"
                + "Có thể rút lại đến " + hm(close) + ".";
    }

    public static final String BTN_WITHDRAW = "🙅 Hôm nay không nấu được";
    public static final String WITHDRAWN = "👌 Đã ghi nhận: hôm nay bạn không nấu.";

    // ================= 14:30 =================

    public static String closeNoDinner(LocalDate d, List<Member> eaters, List<Member> nonEaters) {
        StringBuilder sb = new StringBuilder("📢 Chốt ăn tối hôm nay (" + dm(d) + "):\n");
        if (!eaters.isEmpty()) sb.append("✅ ").append(names(eaters)).append("\n");
        sb.append("❌ ").append(names(nonEaters)).append(" — không ăn\n");
        sb.append("→ Hôm nay KHÔNG NẤU.");
        return sb.toString();
    }

    public static String closeAllEat(LocalDate d, List<Member> all, List<Member> auto, Member presetCook) {
        StringBuilder sb = new StringBuilder("📢 Chốt ăn tối hôm nay (" + dm(d) + "):\n");
        sb.append("✅ ").append(names(all));
        if (!auto.isEmpty()) sb.append(" (").append(names(auto)).append(" không trả lời → tính là có)");
        sb.append("\n");
        if (presetCook != null) {
            sb.append("🎉 Cả nhà đều ăn! 👨‍🍳 Người nấu: ").append(presetCook.name()).append(" (đã đăng ký trước).");
        } else {
            sb.append("🎉 Cả nhà đều ăn! Đang tìm người nấu...");
        }
        return sb.toString();
    }

    public static String askCook(int timeoutMin) {
        return "👨‍🍳 Tối nay bạn có thể nấu ăn không?\n"
                + "⏰ Trả lời trong " + timeoutMin + " phút, quá thời gian sẽ chuyển người khác.";
    }

    public static final String COOK_DECLINED = "👌 Đã ghi nhận, sẽ hỏi người tiếp theo.";
    public static final String COOK_TIMEOUT = "⌛ Hết thời gian trả lời, đã chuyển sang người khác.";
    public static final String COOK_SKIPPED = "ℹ️ Admin đã giao việc nấu hôm nay cho người khác.";

    public static String noCook(LocalDate d) {
        return "😢 Hôm nay (" + dm(d) + ") không ai nấu được → KHÔNG NẤU.";
    }

    public static final String COOK_THANKS = "🙏 Cảm ơn bạn! Tối nay bạn nấu.\nĐang hỏi 2 bạn còn lại ai đi chợ.";
    public static final String COOK_ASSIGNED_BY_ADMIN = "👨‍🍳 Admin đã giao: tối nay bạn nấu.\nĐang hỏi 2 bạn còn lại ai đi chợ.";

    // ================= đi chợ =================

    public static String askShop(Member cook, LocalTime deadline) {
        return "🛒 Tối nay " + cook.name() + " nấu.\n"
                + "Hôm nay ai sẽ đi chợ?\n"
                + "⏰ Nếu đến " + hm(deadline) + " chưa ai nhận → hôm nay không nấu.";
    }

    public static final String BTN_ME = "🙋 Tôi";
    public static final String SHOP_MINE = "✅ Bạn sẽ đi chợ hôm nay. Cảm ơn nhé!";
    public static final String SHOP_ASSIGNED_BY_ADMIN = "🛒 Admin đã giao: hôm nay bạn đi chợ.";

    public static String shopOther(Member shopper) {
        return "🛒 " + shopper.name() + " sẽ đi chợ hôm nay.";
    }

    public static String shopTaken(Member shopper) {
        return shopper.name() + " đã nhận đi chợ rồi.";
    }

    public static String shopFallback(LocalDate d, LocalTime deadline, Member cook) {
        String s = "📢 Đến " + hm(deadline) + " chưa ai nhận đi chợ → hôm nay (" + dm(d) + ") KHÔNG NẤU.";
        if (cook != null) s += "\nLượt nấu của " + cook.name() + " hôm nay đã được hoàn lại.";
        return s;
    }

    public static String adminCancelled(LocalDate d, Member refunded) {
        String s = "📢 Admin đã huỷ: hôm nay (" + dm(d) + ") KHÔNG NẤU.";
        if (refunded != null) s += "\nLượt nấu của " + refunded.name() + " hôm nay đã được hoàn lại.";
        return s;
    }

    // ================= khác =================

    public static String start(Member m, LocalTime poll) {
        return "👋 Chào " + m.name() + "! Bot sẽ hỏi ăn tối lúc " + hm(poll) + " hằng ngày.\n"
                + "/lich — đặt lịch 3 ngày tới\n"
                + "/admin — xem thông tin hệ thống";
    }

    public static String unauthorized(long tgId, boolean full) {
        String base = "⛔ Bạn không có trong danh sách thành viên.\nID Telegram của bạn: " + tgId + "\n";
        return base + (full
                ? "Bot đã đủ " + Member.MAX + " thành viên."
                : "Nhờ admin vào /admin → 👥 Thành viên → ➕ Thêm để thêm bạn.");
    }

    public static String firstAdmin(String name) {
        return "👑 Chào " + name + "! Bạn là người đầu tiên nên được đặt làm admin.\n"
                + "Thêm người khác: nhờ họ nhắn bot một tin bất kỳ, rồi vào /admin → 👥 Thành viên → ➕ Thêm "
                + "(tối đa " + Member.MAX + " người).";
    }

    public static final String VIEW_ONLY = "👁 Bạn chỉ có quyền xem.";

    private Msg() {
    }
}
