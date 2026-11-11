package com.kai.dinnerbot.dto;

import java.time.LocalDate;

public record CookStat(Member member, int count, LocalDate lastCooked) {
}
