package com.kai.dinnerbot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.*;

@Component
public class TimeService {
    private final ZoneId zone;

    public TimeService(@Value("${bot.timezone:Asia/Ho_Chi_Minh}") String tz) {
        this.zone = ZoneId.of(tz);
    }

    public LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    public LocalTime time() {
        return LocalTime.now(zone);
    }
}
