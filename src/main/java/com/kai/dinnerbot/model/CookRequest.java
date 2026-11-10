package com.kai.dinnerbot.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record CookRequest(LocalDate date, int memberId, String response, LocalDateTime askedAt, Integer messageId) {
}
