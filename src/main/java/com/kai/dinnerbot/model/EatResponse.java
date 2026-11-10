package com.kai.dinnerbot.model;

public record EatResponse(int memberId, boolean willEat, String source) {
}
