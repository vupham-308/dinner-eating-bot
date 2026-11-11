package com.kai.dinnerbot.dto;

import java.time.LocalDate;

/** Lịch đặt trước. willEat / canCook = null nghĩa là chưa chọn. */
public record Schedule(LocalDate date, int memberId, Boolean willEat, Boolean canCook) {
}
