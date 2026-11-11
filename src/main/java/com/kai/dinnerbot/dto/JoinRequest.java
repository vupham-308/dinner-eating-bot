package com.kai.dinnerbot.dto;

import java.time.LocalDateTime;

/** Người chưa là thành viên đã nhắn bot gần đây. */
public record JoinRequest(long telegramId, String name, String username, LocalDateTime lastSeen) {
}
