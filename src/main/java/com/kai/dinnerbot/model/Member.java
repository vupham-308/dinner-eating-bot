package com.kai.dinnerbot.model;

public record Member(int id, long telegramId, String name, String role, boolean started) {
    public boolean isAdmin() {
        return "admin".equalsIgnoreCase(role);
    }
}
