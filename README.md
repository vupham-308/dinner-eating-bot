# Dinner Bot — Telegram bot hỏi ăn tối & phân công nấu ăn

Java 17 · Spring Boot 3.3 · MS SQL Server · Telegram Bot API (long polling, không cần mở port)

## 1. Chuẩn bị

1. **JDK 17+** và **Maven 3.9+**.
2. **SQL Server**: tạo database
   ```sql
   CREATE DATABASE dinner_bot;
   ```
   Các bảng sẽ được bot tự tạo khi khởi động (`src/main/resources/schema.sql`).
3. **Tạo bot**: chat với `@BotFather` → `/newbot` → lấy **token**.

## 2. Cấu hình

Đặt biến môi trường (PowerShell):

```powershell
$env:BOT_TOKEN   = "123456:ABC..."
$env:DB_URL      = "jdbc:sqlserver://localhost:1433;databaseName=dinner_bot;encrypt=true;trustServerCertificate=true"
$env:DB_USER     = "sa"
$env:DB_PASSWORD = "matkhau"
```

Hoặc sửa thẳng trong `src/main/resources/application.yml`.

> **Thành viên** (tối đa 3) không cấu hình bằng biến môi trường:
> 1. Chạy bot với DB trống → **người đầu tiên nhắn bot** trở thành admin. Hãy nhắn ngay sau khi deploy.
> 2. Người khác nhắn bot một tin bất kỳ (bot báo chưa có quyền và ghi lại họ trong 7 ngày).
> 3. Admin vào `/admin → 👥 Thành viên → ➕ Thêm thành viên` → bấm tên người đó.
>
> Đổi tên / ID dùng `/admin → Thành viên`. Muốn đổi role admin thì sửa cột `role` trong bảng `members`.

## 3. Chạy

```powershell
cd D:\Kai\dinner-bot
mvn spring-boot:run
```

hoặc build file jar:

```powershell
mvn clean package
java -jar target\dinner-bot-1.0.0.jar
```

**Quan trọng:** cả 3 người phải nhắn `/start` cho bot một lần — Telegram không cho bot nhắn trước cho người chưa từng chat với nó.

## 4. Luồng hằng ngày (giờ mặc định, sửa được qua `/admin → Cấu hình giờ`)

| Giờ | Việc |
|---|---|
| 13:00 | Ai đã đặt trước **Không ăn** → nhắc riêng, cho đổi thành Có ăn đến 14:00 |
| 14:00 | Còn người Không ăn → báo 2 người còn lại "không nấu", kết thúc ngày. Ngược lại: hỏi ăn tối (người đã đặt trước thì chỉ nhắc lại). Người đặt trước **Nấu được** có count thấp nhất được báo "bạn sẽ là người nấu" (có thể rút lại đến 14:30) |
| 14:30 | Chốt: không trả lời = Có. Có người Không → báo cả 3 kèm tên. Cả 3 ăn → người nấu định sẵn được xác nhận, hoặc hỏi lần lượt theo count thấp nhất (bỏ qua người "Không nấu được"/đã rút; 20 phút không trả lời = Không) |
| sau đó | Có người nấu (+1 count) → hỏi 2 người còn lại "Ai đi chợ?" — ai bấm **Tôi** trước thì nhận, báo người còn lại |
| 19:00 | Chưa ai đi chợ → không nấu, **trừ lại 1 count** người nấu, báo cả 3 |
| T2 10:00 | Gửi bảng chênh lệch lượt nấu (so với người đứng giữa) cho cả 3 |

Bot kiểm tra trạng thái mỗi 20 giây và lưu mọi thứ trong DB, nên tắt/bật lại vẫn chạy tiếp đúng bước.
Nếu bot được bật **sau giờ chốt** mà hôm đó chưa chạy thì ngày đó được bỏ qua.

## 5. Lệnh

| Lệnh | Ai dùng | Mô tả |
|---|---|---|
| `/start` | mọi người | Chào + hướng dẫn |
| `/lich` | thành viên | Đặt lịch ăn / nấu cho 3 ngày tới (từ ngày mai) |
| `/admin` | thành viên | Mọi người xem được; chỉ `role = admin` mới sửa được |
| `/huy` | admin | Huỷ thao tác đang nhập |

**Admin:** thành viên (thêm từ danh sách người nhắn gần đây, đổi tên/ID), lượt nấu (±1, đặt số, rollback ngày đã nấu trong 3 ngày gần nhất),
hôm nay (sửa ăn/không, chốt sớm, giao nấu, giao đi chợ, huỷ hôm nay), lịch đặt trước của mọi người,
cấu hình giờ (giờ chưa tới → áp dụng ngay, đã qua → từ ngày mai), tạm dừng theo khoảng ngày, gửi báo cáo ngay.
Mọi thao tác admin được ghi vào bảng `admin_logs`.

## 6. Cấu trúc code

```
src/main/java/com/kai/dinnerbot
├── DinnerBotApplication.java
├── dto/              Member, JoinRequest, DaySession, Schedule, CookStat, CookRequest, EatResponse, BotSettings
├── model/            Status (hằng số trạng thái phiên)
├── repo/Repo.java    toàn bộ SQL (JdbcTemplate)
├── telegram/         TelegramClient (gọi Bot API), UpdatePoller (long polling), Btn, Cb
└── service/
    ├── Msg.java              ← toàn bộ câu chữ tin nhắn, sửa ở đây
    ├── DinnerFlowService     luồng hằng ngày + báo cáo tuần
    ├── ScheduleService       /lich
    ├── AdminService          /admin
    ├── SettingsService       cấu hình giờ, tạm dừng
    ├── BotRouter             nhận tin/nút bấm + tick 20s (dùng chung 1 khoá)
    └── MemberSeeder          kiểm tra lúc khởi động (bảng members trống → người nhắn đầu tiên là admin)
```

## 7. Bảng dữ liệu

`members` (có `role`), `cook_stats` (số lần nấu), `daily_sessions` (trạng thái từng ngày),
`eat_responses`, `schedules` (lịch đặt trước), `cook_requests` (lịch sử hỏi nấu),
`bot_messages` (để tắt nút), `settings`, `admin_logs`, `join_requests` (người lạ nhắn bot, chờ admin thêm).
