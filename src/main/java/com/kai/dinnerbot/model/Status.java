package com.kai.dinnerbot.model;

import java.util.Set;

public final class Status {
    public static final String PRECHECK = "PRECHECK";               // 13:00 đã tạo phiên
    public static final String POLLING = "POLLING";                 // 14:00 đang hỏi ăn
    public static final String ASKING_COOK = "ASKING_COOK";         // đang hỏi người nấu
    public static final String COOK_CONFIRMED = "COOK_CONFIRMED";   // đã có người nấu, chờ người đi chợ
    public static final String SHOPPER_ASSIGNED = "SHOPPER_ASSIGNED";
    public static final String NO_DINNER = "NO_DINNER";             // có người không ăn
    public static final String NO_COOK = "NO_COOK";                 // không ai nấu
    public static final String CANCELLED = "CANCELLED";             // 19:00 không ai đi chợ / admin huỷ / rollback
    public static final String EXPIRED = "EXPIRED";                 // phiên cũ chưa xong, tự đóng

    public static final Set<String> ACTIVE = Set.of(PRECHECK, POLLING, ASKING_COOK, COOK_CONFIRMED);
    public static final Set<String> COOKED = Set.of(COOK_CONFIRMED, SHOPPER_ASSIGNED);

    public static String label(String s) {
        if (s == null) return "chưa bắt đầu";
        return switch (s) {
            case PRECHECK -> "chờ hỏi ăn";
            case POLLING -> "đang hỏi ăn tối";
            case ASKING_COOK -> "đang tìm người nấu";
            case COOK_CONFIRMED -> "đã có người nấu, chờ người đi chợ";
            case SHOPPER_ASSIGNED -> "đã đủ người nấu và đi chợ";
            case NO_DINNER -> "không nấu (có người không ăn)";
            case NO_COOK -> "không nấu (không ai nấu)";
            case CANCELLED -> "đã huỷ, không nấu";
            case EXPIRED -> "đã hết hạn";
            default -> s;
        };
    }

    private Status() {
    }
}
