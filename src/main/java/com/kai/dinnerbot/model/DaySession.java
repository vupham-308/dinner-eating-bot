package com.kai.dinnerbot.model;

import java.time.LocalDate;

public record DaySession(LocalDate date, String status, Integer cookId, Integer shopperId, Integer presetCookId) {
    public boolean is(String s) {
        return s.equals(status);
    }
}
