package com.kai.dinnerbot.model;

import java.time.LocalDate;

public record CookStat(Member member, int count, LocalDate lastCooked) {
}
