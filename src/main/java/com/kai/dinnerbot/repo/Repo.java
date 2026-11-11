package com.kai.dinnerbot.repo;

import com.kai.dinnerbot.dto.*;
import com.kai.dinnerbot.model.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Toàn bộ truy vấn SQL Server. */
@Repository
public class Repo {

    private final JdbcTemplate jdbc;

    public Repo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Date d(LocalDate date) {
        return Date.valueOf(date);
    }

    private static LocalDate ld(ResultSet rs, String col) throws SQLException {
        Date v = rs.getDate(col);
        return v == null ? null : v.toLocalDate();
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Boolean boolOrNull(ResultSet rs, String col) throws SQLException {
        boolean v = rs.getBoolean(col);
        return rs.wasNull() ? null : v;
    }

    private static final RowMapper<Member> MEMBER = (rs, i) -> new Member(
            rs.getInt("id"), rs.getLong("telegram_id"), rs.getString("name"),
            rs.getString("role"), rs.getBoolean("started"));

    private static final RowMapper<DaySession> SESSION = (rs, i) -> new DaySession(
            ld(rs, "session_date"), rs.getString("status"),
            intOrNull(rs, "cook_member_id"), intOrNull(rs, "shopper_member_id"),
            intOrNull(rs, "preset_cook_member_id"));

    private static final RowMapper<Schedule> SCHEDULE = (rs, i) -> new Schedule(
            ld(rs, "sched_date"), rs.getInt("member_id"),
            boolOrNull(rs, "will_eat"), boolOrNull(rs, "can_cook"));

    private static final RowMapper<CookRequest> COOK_REQ = (rs, i) -> new CookRequest(
            ld(rs, "session_date"), rs.getInt("member_id"), rs.getString("response"),
            rs.getTimestamp("asked_at").toLocalDateTime(), intOrNull(rs, "message_id"));

    // ======================= members =======================

    public List<Member> members() {
        return jdbc.query("SELECT * FROM members ORDER BY id", MEMBER);
    }

    public Optional<Member> member(Integer id) {
        if (id == null) return Optional.empty();
        return jdbc.query("SELECT * FROM members WHERE id = ?", MEMBER, id).stream().findFirst();
    }

    public Optional<Member> memberByTg(long tgId) {
        return jdbc.query("SELECT * FROM members WHERE telegram_id = ?", MEMBER, tgId).stream().findFirst();
    }

    public int countMembers() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM members", Integer.class);
        return n == null ? 0 : n;
    }

    public void insertMember(long tgId, String name, String role) {
        jdbc.update("INSERT INTO members (telegram_id, name, role) VALUES (?, ?, ?)", tgId, name, role);
        ensureCookStats();
    }

    public void ensureCookStats() {
        jdbc.update("""
                INSERT INTO cook_stats (member_id)
                SELECT m.id FROM members m
                 WHERE NOT EXISTS (SELECT 1 FROM cook_stats c WHERE c.member_id = m.id)""");
    }

    public void setStarted(int id) {
        jdbc.update("UPDATE members SET started = 1 WHERE id = ?", id);
    }

    public void renameMember(int id, String name) {
        jdbc.update("UPDATE members SET name = ? WHERE id = ?", name, id);
    }

    public void changeTelegramId(int id, long tgId) {
        jdbc.update("UPDATE members SET telegram_id = ?, started = 0 WHERE id = ?", tgId, id);
    }

    // ======================= join requests =======================

    private static final RowMapper<JoinRequest> JOIN_REQ = (rs, i) -> new JoinRequest(
            rs.getLong("telegram_id"), rs.getString("name"), rs.getString("username"),
            rs.getTimestamp("last_seen").toLocalDateTime());

    /** Ghi nhận người lạ vừa nhắn bot; xoá các yêu cầu cũ hơn {@code keepSince}. */
    @Transactional
    public void touchJoinRequest(long tgId, String name, String username, LocalDateTime now, LocalDateTime keepSince) {
        Timestamp ts = Timestamp.valueOf(now);
        int n = jdbc.update("UPDATE join_requests SET name = ?, username = ?, last_seen = ? WHERE telegram_id = ?",
                name, username, ts, tgId);
        if (n == 0) {
            try {
                jdbc.update("INSERT INTO join_requests (telegram_id, name, username, last_seen) VALUES (?, ?, ?, ?)",
                        tgId, name, username, ts);
            } catch (DuplicateKeyException ignored) {
                // đã có người khác chèn cùng lúc — bỏ qua
            }
        }
        jdbc.update("DELETE FROM join_requests WHERE last_seen < ?", Timestamp.valueOf(keepSince));
    }

    /** Người lạ nhắn bot gần đây (chưa là thành viên), mới nhất trước. */
    public List<JoinRequest> joinRequests(LocalDateTime since, int limit) {
        return jdbc.query("""
                SELECT TOP (?) j.* FROM join_requests j
                 WHERE j.last_seen >= ?
                   AND NOT EXISTS (SELECT 1 FROM members m WHERE m.telegram_id = j.telegram_id)
                 ORDER BY j.last_seen DESC""", JOIN_REQ, limit, Timestamp.valueOf(since));
    }

    public Optional<JoinRequest> joinRequest(long tgId) {
        return jdbc.query("SELECT * FROM join_requests WHERE telegram_id = ?", JOIN_REQ, tgId).stream().findFirst();
    }

    public void deleteJoinRequest(long tgId) {
        jdbc.update("DELETE FROM join_requests WHERE telegram_id = ?", tgId);
    }

    // ======================= sessions =======================

    public Optional<DaySession> session(LocalDate date) {
        return jdbc.query("SELECT * FROM daily_sessions WHERE session_date = ?", SESSION, d(date))
                .stream().findFirst();
    }

    /** @return true nếu tạo mới, false nếu đã tồn tại */
    public boolean createSession(LocalDate date, String status) {
        try {
            jdbc.update("INSERT INTO daily_sessions (session_date, status) VALUES (?, ?)", d(date), status);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** Chỉ đổi khi đang ở trạng thái expected (chống chạy trùng). */
    public boolean setStatus(LocalDate date, String status, String expected) {
        return jdbc.update("UPDATE daily_sessions SET status = ? WHERE session_date = ? AND status = ?",
                status, d(date), expected) == 1;
    }

    public void forceStatus(LocalDate date, String status) {
        jdbc.update("UPDATE daily_sessions SET status = ? WHERE session_date = ?", status, d(date));
    }

    public void setPresetCook(LocalDate date, Integer memberId) {
        jdbc.update("UPDATE daily_sessions SET preset_cook_member_id = ? WHERE session_date = ?", memberId, d(date));
    }

    public List<DaySession> staleSessions(LocalDate today) {
        return jdbc.query("""
                SELECT * FROM daily_sessions
                 WHERE session_date < ? AND status IN ('PRECHECK','POLLING','ASKING_COOK','COOK_CONFIRMED')""",
                SESSION, d(today));
    }

    /** Các ngày đã chốt người nấu trong khoảng [from, to] (dùng cho rollback). */
    public List<DaySession> cookedSessions(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT * FROM daily_sessions
                 WHERE session_date BETWEEN ? AND ?
                   AND cook_member_id IS NOT NULL
                   AND status IN ('COOK_CONFIRMED','SHOPPER_ASSIGNED')
                 ORDER BY session_date DESC""", SESSION, d(from), d(to));
    }

    /** Chốt người nấu: +1 lượt, cập nhật phiên. */
    @Transactional
    public void confirmCook(LocalDate date, int memberId) {
        jdbc.update("""
                UPDATE daily_sessions SET cook_member_id = ?, status = 'COOK_CONFIRMED'
                 WHERE session_date = ?""", memberId, d(date));
        jdbc.update("""
                UPDATE cook_stats SET cook_count = cook_count + 1, last_cooked = ?
                 WHERE member_id = ?""", d(date), memberId);
    }

    /**
     * Huỷ ngày (19:00 không ai đi chợ / admin huỷ / rollback).
     * Nếu ngày đó đã chốt người nấu thì trừ lại 1 lượt.
     * @return id người nấu bị hoàn lượt, hoặc null
     */
    @Transactional
    public Integer cancelDay(LocalDate date) {
        DaySession s = session(date).orElse(null);
        if (s == null) {
            createSession(date, Status.CANCELLED);
            return null;
        }
        Integer refunded = null;
        if (s.cookId() != null && Status.COOKED.contains(s.status())) {
            refunded = s.cookId();
        }
        jdbc.update("UPDATE daily_sessions SET status = 'CANCELLED' WHERE session_date = ?", d(date));
        if (refunded != null) {
            jdbc.update("""
                    UPDATE cook_stats SET cook_count = CASE WHEN cook_count > 0 THEN cook_count - 1 ELSE 0 END
                     WHERE member_id = ?""", refunded);
            recomputeLastCooked(refunded);
        }
        return refunded;
    }

    private void recomputeLastCooked(int memberId) {
        jdbc.update("""
                UPDATE cook_stats SET last_cooked =
                    (SELECT MAX(session_date) FROM daily_sessions
                      WHERE cook_member_id = ? AND status IN ('COOK_CONFIRMED','SHOPPER_ASSIGNED'))
                 WHERE member_id = ?""", memberId, memberId);
    }

    /** Ai bấm "Tôi" trước thì nhận (atomic). */
    public boolean claimShopper(LocalDate date, int memberId) {
        return jdbc.update("""
                UPDATE daily_sessions SET shopper_member_id = ?, status = 'SHOPPER_ASSIGNED'
                 WHERE session_date = ? AND status = 'COOK_CONFIRMED' AND shopper_member_id IS NULL""",
                memberId, d(date)) == 1;
    }

    // ======================= eat responses =======================

    public void upsertEat(LocalDate date, int memberId, boolean willEat, String source, LocalDateTime now) {
        int n = jdbc.update("""
                UPDATE eat_responses SET will_eat = ?, source = ?, updated_at = ?
                 WHERE session_date = ? AND member_id = ?""",
                willEat, source, Timestamp.valueOf(now), d(date), memberId);
        if (n == 0) {
            jdbc.update("""
                    INSERT INTO eat_responses (session_date, member_id, will_eat, source, updated_at)
                    VALUES (?, ?, ?, ?, ?)""", d(date), memberId, willEat, source, Timestamp.valueOf(now));
        }
    }

    public Map<Integer, EatResponse> eats(LocalDate date) {
        Map<Integer, EatResponse> map = new HashMap<>();
        jdbc.query("SELECT * FROM eat_responses WHERE session_date = ?", rs -> {
            map.put(rs.getInt("member_id"),
                    new EatResponse(rs.getInt("member_id"), rs.getBoolean("will_eat"), rs.getString("source")));
        }, d(date));
        return map;
    }

    // ======================= schedules =======================

    public Map<Integer, Schedule> schedules(LocalDate date) {
        Map<Integer, Schedule> map = new HashMap<>();
        for (Schedule s : jdbc.query("SELECT * FROM schedules WHERE sched_date = ?", SCHEDULE, d(date))) {
            map.put(s.memberId(), s);
        }
        return map;
    }

    public Optional<Schedule> schedule(LocalDate date, int memberId) {
        return jdbc.query("SELECT * FROM schedules WHERE sched_date = ? AND member_id = ?",
                SCHEDULE, d(date), memberId).stream().findFirst();
    }

    public void setScheduleEat(LocalDate date, int memberId, Boolean willEat) {
        int n = jdbc.update("UPDATE schedules SET will_eat = ? WHERE sched_date = ? AND member_id = ?",
                willEat, d(date), memberId);
        if (n == 0) {
            jdbc.update("INSERT INTO schedules (sched_date, member_id, will_eat) VALUES (?, ?, ?)",
                    d(date), memberId, willEat);
        }
    }

    public void setScheduleCook(LocalDate date, int memberId, Boolean canCook) {
        int n = jdbc.update("UPDATE schedules SET can_cook = ? WHERE sched_date = ? AND member_id = ?",
                canCook, d(date), memberId);
        if (n == 0) {
            jdbc.update("INSERT INTO schedules (sched_date, member_id, can_cook) VALUES (?, ?, ?)",
                    d(date), memberId, canCook);
        }
    }

    public void deleteSchedule(LocalDate date, int memberId) {
        jdbc.update("DELETE FROM schedules WHERE sched_date = ? AND member_id = ?", d(date), memberId);
    }

    // ======================= cook requests =======================

    public void addCookRequest(LocalDate date, int memberId, String response, Integer messageId, LocalDateTime now) {
        int n = jdbc.update("""
                UPDATE cook_requests SET response = ?, asked_at = ?, message_id = ?
                 WHERE session_date = ? AND member_id = ?""",
                response, Timestamp.valueOf(now), messageId, d(date), memberId);
        if (n == 0) {
            jdbc.update("""
                    INSERT INTO cook_requests (session_date, member_id, response, asked_at, message_id)
                    VALUES (?, ?, ?, ?, ?)""", d(date), memberId, response, Timestamp.valueOf(now), messageId);
        }
    }

    public void setCookRequestMessage(LocalDate date, int memberId, Integer messageId) {
        jdbc.update("UPDATE cook_requests SET message_id = ? WHERE session_date = ? AND member_id = ?",
                messageId, d(date), memberId);
    }

    public Optional<CookRequest> pendingCookRequest(LocalDate date) {
        return jdbc.query("SELECT * FROM cook_requests WHERE session_date = ? AND response = 'pending'",
                COOK_REQ, d(date)).stream().findFirst();
    }

    /** Chỉ ghi khi còn pending -> tránh trùng giữa người bấm và timeout. */
    public boolean answerCookRequest(LocalDate date, int memberId, String response) {
        return jdbc.update("""
                UPDATE cook_requests SET response = ?
                 WHERE session_date = ? AND member_id = ? AND response = 'pending'""",
                response, d(date), memberId) == 1;
    }

    public Set<Integer> requestedMembers(LocalDate date) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT member_id FROM cook_requests WHERE session_date = ?", Integer.class, d(date)));
    }

    // ======================= cook stats =======================

    /** Sắp theo: count tăng dần, lâu chưa nấu nhất (chưa nấu bao giờ đứng đầu), id. */
    public List<CookStat> cookStats() {
        return jdbc.query("""
                SELECT m.*, s.cook_count, s.last_cooked
                  FROM members m JOIN cook_stats s ON s.member_id = m.id
                 ORDER BY s.cook_count ASC,
                          CASE WHEN s.last_cooked IS NULL THEN 0 ELSE 1 END,
                          s.last_cooked ASC,
                          m.id ASC""",
                (rs, i) -> new CookStat(MEMBER.mapRow(rs, i), rs.getInt("cook_count"), ld(rs, "last_cooked")));
    }

    public void adjustCount(int memberId, int delta) {
        jdbc.update("""
                UPDATE cook_stats SET cook_count = CASE WHEN cook_count + ? < 0 THEN 0 ELSE cook_count + ? END
                 WHERE member_id = ?""", delta, delta, memberId);
    }

    public void setCount(int memberId, int count) {
        jdbc.update("UPDATE cook_stats SET cook_count = ? WHERE member_id = ?", Math.max(0, count), memberId);
    }

    // ======================= bot messages =======================

    public void saveMessage(LocalDate date, int memberId, String kind, Integer messageId) {
        if (messageId == null) return;
        jdbc.update("DELETE FROM bot_messages WHERE session_date = ? AND member_id = ? AND kind = ?",
                d(date), memberId, kind);
        jdbc.update("INSERT INTO bot_messages (session_date, member_id, kind, message_id) VALUES (?, ?, ?, ?)",
                d(date), memberId, kind, messageId);
    }

    /** memberId -> messageId */
    public Map<Integer, Integer> messages(LocalDate date, String kind) {
        Map<Integer, Integer> map = new HashMap<>();
        jdbc.query("SELECT member_id, message_id FROM bot_messages WHERE session_date = ? AND kind = ?",
                rs -> {
                    map.put(rs.getInt("member_id"), rs.getInt("message_id"));
                }, d(date), kind);
        return map;
    }

    // ======================= settings / logs =======================

    public Map<String, String> settings() {
        Map<String, String> map = new HashMap<>();
        jdbc.query("SELECT k, v FROM settings", rs -> {
            map.put(rs.getString("k"), rs.getString("v"));
        });
        return map;
    }

    public void setSetting(String k, String v) {
        int n = jdbc.update("UPDATE settings SET v = ? WHERE k = ?", v, k);
        if (n == 0) jdbc.update("INSERT INTO settings (k, v) VALUES (?, ?)", k, v);
    }

    public void deleteSetting(String k) {
        jdbc.update("DELETE FROM settings WHERE k = ?", k);
    }

    public void adminLog(int memberId, String action) {
        jdbc.update("INSERT INTO admin_logs (member_id, action) VALUES (?, ?)", memberId, action);
    }
}
