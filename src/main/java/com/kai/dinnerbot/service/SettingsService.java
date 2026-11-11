package com.kai.dinnerbot.service;

import com.kai.dinnerbot.dto.BotSettings;
import com.kai.dinnerbot.repo.Repo;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cấu hình giờ lưu trong bảng settings.
 * Đổi giờ: nếu giờ mới hôm nay chưa tới -> áp dụng ngay; đã qua -> lưu vào khoá "<key>__next" và áp dụng từ ngày mai.
 */
@Service
public class SettingsService {

    public static final String PRECHECK = "precheck_time";
    public static final String POLL = "poll_time";
    public static final String CLOSE = "close_time";
    public static final String SHOP_DEADLINE = "shop_deadline";
    public static final String COOK_TIMEOUT = "cook_timeout_min";
    public static final String WEEKLY = "weekly_time";
    public static final String PAUSE_FROM = "pause_from";
    public static final String PAUSE_TO = "pause_to";
    public static final String LAST_WEEKLY = "last_weekly_report";
    private static final String NEXT = "__next";

    /** key -> nhãn hiển thị (giữ thứ tự) */
    public static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(PRECHECK, "Nhắc lịch không ăn");
        LABELS.put(POLL, "Hỏi ăn tối");
        LABELS.put(CLOSE, "Chốt danh sách");
        LABELS.put(SHOP_DEADLINE, "Hạn đi chợ");
        LABELS.put(COOK_TIMEOUT, "Chờ trả lời nấu");
        LABELS.put(WEEKLY, "Báo cáo tuần (T2)");
    }

    public static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter HM_IN = DateTimeFormatter.ofPattern("H:mm");

    private final Repo repo;

    public SettingsService(Repo repo) {
        this.repo = repo;
    }

    /** Đọc cấu hình hiệu lực cho ngày `today` (tự áp dụng giá trị chờ nếu tới ngày). */
    public BotSettings load(LocalDate today) {
        Map<String, String> raw = repo.settings();
        for (String k : LABELS.keySet()) {
            String next = raw.get(k + NEXT);
            if (next != null) {
                String[] p = next.split("\\|");
                if (p.length == 2 && !today.isBefore(LocalDate.parse(p[1]))) {
                    repo.setSetting(k, p[0]);
                    repo.deleteSetting(k + NEXT);
                    raw.put(k, p[0]);
                    raw.remove(k + NEXT);
                }
            }
        }
        return fromMap(raw);
    }

    private static BotSettings fromMap(Map<String, String> m) {
        return new BotSettings(
                LocalTime.parse(m.getOrDefault(PRECHECK, "13:00"), HM_IN),
                LocalTime.parse(m.getOrDefault(POLL, "14:00"), HM_IN),
                LocalTime.parse(m.getOrDefault(CLOSE, "14:30"), HM_IN),
                LocalTime.parse(m.getOrDefault(SHOP_DEADLINE, "19:00"), HM_IN),
                Integer.parseInt(m.getOrDefault(COOK_TIMEOUT, "20")),
                LocalTime.parse(m.getOrDefault(WEEKLY, "10:00"), HM_IN));
    }

    /** Giá trị chờ áp dụng từ ngày mai (để hiển thị). key -> "HH:mm" */
    public Map<String, String> pendingChanges() {
        Map<String, String> raw = repo.settings();
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : LABELS.keySet()) {
            String next = raw.get(k + NEXT);
            if (next != null) out.put(k, next.split("\\|")[0]);
        }
        return out;
    }

    /**
     * Đổi 1 giá trị cấu hình.
     * @return câu thông báo kết quả
     * @throws IllegalArgumentException nếu không hợp lệ (message là câu báo lỗi)
     */
    public String update(String key, String input, LocalDateTime now) {
        if (!LABELS.containsKey(key)) throw new IllegalArgumentException("❌ Không có cấu hình này.");
        String value = input.trim();
        boolean applyToday;
        if (COOK_TIMEOUT.equals(key)) {
            int n;
            try {
                n = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("❌ Nhập số phút, ví dụ: 20");
            }
            if (n < 1 || n > 240) throw new IllegalArgumentException("❌ Số phút phải từ 1 đến 240.");
            value = String.valueOf(n);
            applyToday = true;
        } else {
            LocalTime t;
            try {
                t = LocalTime.parse(value, HM_IN);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("❌ Sai định dạng giờ. Nhập dạng HH:mm, ví dụ: 14:45");
            }
            value = t.format(HM);
            applyToday = now.toLocalTime().isBefore(t);
        }

        // Kiểm tra thứ tự giờ với giá trị sau khi đổi
        Map<String, String> raw = repo.settings();
        Map<String, String> finalValues = new LinkedHashMap<>(raw);
        for (String k : LABELS.keySet()) {
            String next = raw.get(k + NEXT);
            if (next != null) finalValues.put(k, next.split("\\|")[0]);
        }
        finalValues.put(key, value);
        validateOrder(fromMap(finalValues));
        if (applyToday) {
            Map<String, String> todayValues = new LinkedHashMap<>(raw);
            todayValues.put(key, value);
            validateOrder(fromMap(todayValues));
        }

        String label = LABELS.get(key);
        String shown = COOK_TIMEOUT.equals(key) ? value + " phút" : value;
        if (applyToday) {
            repo.setSetting(key, value);
            repo.deleteSetting(key + NEXT);
            return "✅ Đã đổi \"" + label + "\" → " + shown + ". Áp dụng ngay hôm nay.";
        } else {
            repo.setSetting(key + NEXT, value + "|" + now.toLocalDate().plusDays(1));
            return "✅ Đã đổi \"" + label + "\" → " + shown + ". Giờ này hôm nay đã qua nên áp dụng từ ngày mai.";
        }
    }

    private static void validateOrder(BotSettings s) {
        if (!(s.precheck().isBefore(s.poll()) && s.poll().isBefore(s.close()) && s.close().isBefore(s.shopDeadline()))) {
            throw new IllegalArgumentException("❌ Giờ không hợp lệ: phải theo thứ tự "
                    + s.precheck().format(HM) + " < " + s.poll().format(HM) + " < "
                    + s.close().format(HM) + " < " + s.shopDeadline().format(HM)
                    + " (Nhắc lịch < Hỏi ăn < Chốt < Hạn đi chợ).");
        }
    }

    // ---------- tạm dừng ----------

    public boolean isPaused(LocalDate date) {
        Map<String, String> raw = repo.settings();
        String f = raw.get(PAUSE_FROM), t = raw.get(PAUSE_TO);
        if (f == null || t == null) return false;
        return !date.isBefore(LocalDate.parse(f)) && !date.isAfter(LocalDate.parse(t));
    }

    public LocalDate[] pauseRange() {
        Map<String, String> raw = repo.settings();
        String f = raw.get(PAUSE_FROM), t = raw.get(PAUSE_TO);
        if (f == null || t == null) return null;
        return new LocalDate[]{LocalDate.parse(f), LocalDate.parse(t)};
    }

    public void setPause(LocalDate from, LocalDate to) {
        repo.setSetting(PAUSE_FROM, from.toString());
        repo.setSetting(PAUSE_TO, to.toString());
    }

    public void clearPause() {
        repo.deleteSetting(PAUSE_FROM);
        repo.deleteSetting(PAUSE_TO);
    }

    public String get(String key) {
        return repo.settings().get(key);
    }

    public void set(String key, String value) {
        repo.setSetting(key, value);
    }
}
