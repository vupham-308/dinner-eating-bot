package com.kai.dinnerbot.telegram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Nút inline. callback_data tối đa 64 byte. */
public record Btn(String text, String data) {

    public static Btn of(String text, String data) {
        return new Btn(text, data);
    }

    /** Một hàng nút. */
    public static List<Btn> row(Btn... btns) {
        return new ArrayList<>(Arrays.asList(btns));
    }

    @SafeVarargs
    public static List<List<Btn>> kb(List<Btn>... rows) {
        List<List<Btn>> list = new ArrayList<>();
        for (List<Btn> r : rows) {
            if (r != null && !r.isEmpty()) list.add(r);
        }
        return list;
    }
}
