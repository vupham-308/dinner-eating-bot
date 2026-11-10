package com.kai.dinnerbot.service;

import java.time.LocalTime;

public record BotSettings(LocalTime precheck, LocalTime poll, LocalTime close, LocalTime shopDeadline,
                          int cookTimeoutMin, LocalTime weekly) {
}
