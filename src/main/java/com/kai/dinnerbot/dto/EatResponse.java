package com.kai.dinnerbot.dto;

public record EatResponse(int memberId, boolean willEat, String source) {
}
