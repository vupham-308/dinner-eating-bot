-- Tạo bảng nếu chưa có (chạy mỗi lần khởi động, an toàn khi chạy lại)

IF OBJECT_ID('dbo.members', 'U') IS NULL
CREATE TABLE dbo.members (
    id           INT IDENTITY(1,1) PRIMARY KEY,
    telegram_id  BIGINT        NOT NULL UNIQUE,
    name         NVARCHAR(100) NOT NULL,
    role         VARCHAR(10)   NOT NULL DEFAULT 'member',  -- admin | member
    started      BIT           NOT NULL DEFAULT 0          -- đã /start bot chưa
);

-- Số lần nấu của từng người
IF OBJECT_ID('dbo.cook_stats', 'U') IS NULL
CREATE TABLE dbo.cook_stats (
    member_id    INT  NOT NULL PRIMARY KEY REFERENCES dbo.members(id),
    cook_count   INT  NOT NULL DEFAULT 0,
    last_cooked  DATE NULL
);

-- Mỗi ngày 1 phiên
-- status: PRECHECK, POLLING, ASKING_COOK, COOK_CONFIRMED, SHOPPER_ASSIGNED,
--         NO_DINNER, NO_COOK, CANCELLED, EXPIRED
IF OBJECT_ID('dbo.daily_sessions', 'U') IS NULL
CREATE TABLE dbo.daily_sessions (
    session_date           DATE        NOT NULL PRIMARY KEY,
    status                 VARCHAR(20) NOT NULL,
    cook_member_id         INT NULL REFERENCES dbo.members(id),
    shopper_member_id      INT NULL REFERENCES dbo.members(id),
    preset_cook_member_id  INT NULL REFERENCES dbo.members(id),
    created_at             DATETIME2   NOT NULL DEFAULT SYSDATETIME()
);

-- Trả lời ăn / không ăn của từng ngày. source: click | preset | auto | admin
IF OBJECT_ID('dbo.eat_responses', 'U') IS NULL
CREATE TABLE dbo.eat_responses (
    session_date  DATE        NOT NULL,
    member_id     INT         NOT NULL REFERENCES dbo.members(id),
    will_eat      BIT         NOT NULL,
    source        VARCHAR(10) NOT NULL,
    updated_at    DATETIME2   NOT NULL,
    PRIMARY KEY (session_date, member_id)
);

-- Lịch đặt trước (3 ngày tới)
IF OBJECT_ID('dbo.schedules', 'U') IS NULL
CREATE TABLE dbo.schedules (
    sched_date  DATE NOT NULL,
    member_id   INT  NOT NULL REFERENCES dbo.members(id),
    will_eat    BIT  NULL,
    can_cook    BIT  NULL,
    PRIMARY KEY (sched_date, member_id)
);

-- Lịch sử hỏi nấu trong ngày. response: pending | yes | no | timeout | withdrawn | skipped
IF OBJECT_ID('dbo.cook_requests', 'U') IS NULL
CREATE TABLE dbo.cook_requests (
    session_date  DATE        NOT NULL,
    member_id     INT         NOT NULL REFERENCES dbo.members(id),
    response      VARCHAR(10) NOT NULL,
    asked_at      DATETIME2   NOT NULL,
    message_id    INT         NULL,
    PRIMARY KEY (session_date, member_id)
);

-- Lưu message_id các tin có nút, để tắt nút khi cần. kind: pre | poll | shop | precook
IF OBJECT_ID('dbo.bot_messages', 'U') IS NULL
CREATE TABLE dbo.bot_messages (
    session_date  DATE        NOT NULL,
    member_id     INT         NOT NULL REFERENCES dbo.members(id),
    kind          VARCHAR(20) NOT NULL,
    message_id    INT         NOT NULL,
    PRIMARY KEY (session_date, member_id, kind)
);

IF OBJECT_ID('dbo.settings', 'U') IS NULL
CREATE TABLE dbo.settings (
    k  VARCHAR(50)   NOT NULL PRIMARY KEY,
    v  NVARCHAR(100) NULL
);

IF OBJECT_ID('dbo.admin_logs', 'U') IS NULL
CREATE TABLE dbo.admin_logs (
    id          BIGINT IDENTITY(1,1) PRIMARY KEY,
    member_id   INT           NOT NULL,
    action      NVARCHAR(500) NOT NULL,
    created_at  DATETIME2     NOT NULL DEFAULT SYSDATETIME()
);

-- Người lạ (chưa là thành viên) vừa nhắn bot — admin chọn từ đây để thêm nhanh
IF OBJECT_ID('dbo.join_requests', 'U') IS NULL
CREATE TABLE dbo.join_requests (
    telegram_id  BIGINT        NOT NULL PRIMARY KEY,
    name         NVARCHAR(100) NOT NULL,
    username     NVARCHAR(100) NULL,
    last_seen    DATETIME2     NOT NULL
);

-- Giá trị mặc định
IF NOT EXISTS (SELECT 1 FROM dbo.settings WHERE k = 'precheck_time') INSERT INTO dbo.settings (k, v) VALUES ('precheck_time', '13:00');
IF NOT EXISTS (SELECT 1 FROM dbo.settings WHERE k = 'poll_time') INSERT INTO dbo.settings (k, v) VALUES ('poll_time', '14:00');
IF NOT EXISTS (SELECT 1 FROM dbo.settings WHERE k = 'close_time') INSERT INTO dbo.settings (k, v) VALUES ('close_time', '14:30');
IF NOT EXISTS (SELECT 1 FROM dbo.settings WHERE k = 'shop_deadline') INSERT INTO dbo.settings (k, v) VALUES ('shop_deadline', '19:00');
IF NOT EXISTS (SELECT 1 FROM dbo.settings WHERE k = 'cook_timeout_min') INSERT INTO dbo.settings (k, v) VALUES ('cook_timeout_min', '20');
IF NOT EXISTS (SELECT 1 FROM dbo.settings WHERE k = 'weekly_time') INSERT INTO dbo.settings (k, v) VALUES ('weekly_time', '10:00');
