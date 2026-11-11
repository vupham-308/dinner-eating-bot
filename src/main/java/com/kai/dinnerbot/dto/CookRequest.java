package com.kai.dinnerbot.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record CookRequest(LocalDate date, int memberId, String response, LocalDateTime askedAt, Integer messageId) {
}
