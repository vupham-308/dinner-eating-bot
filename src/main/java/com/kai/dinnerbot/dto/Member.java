package com.kai.dinnerbot.dto;

public record Member(int id, long telegramId, String name, String role, boolean started) {
    /** Số thành viên tối đa của bot. */
    public static final int MAX = 3;

    public boolean isAdmin() {
        return "admin".equalsIgnoreCase(role);
    }
}
