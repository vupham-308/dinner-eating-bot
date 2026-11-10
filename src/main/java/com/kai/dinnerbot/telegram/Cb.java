package com.kai.dinnerbot.telegram;

import com.kai.dinnerbot.model.Member;

/** Ngữ cảnh 1 lần bấm nút. Đảm bảo luôn trả lời callback đúng 1 lần. */
public class Cb {
    public final String id;
    public final Member member;
    public final Integer messageId;
    public final String[] parts;
    private final TelegramClient tg;
    private boolean answered;

    public Cb(String id, Member member, Integer messageId, String data, TelegramClient tg) {
        this.id = id;
        this.member = member;
        this.messageId = messageId;
        this.parts = data.split(":");
        this.tg = tg;
    }

    public String part(int i) {
        return i < parts.length ? parts[i] : "";
    }

    public int intPart(int i) {
        return Integer.parseInt(part(i));
    }

    /** Thông báo nhỏ phía trên màn hình. */
    public void toast(String text) {
        answer(text, false);
    }

    /** Popup cần bấm OK. */
    public void alert(String text) {
        answer(text, true);
    }

    public void done() {
        answer(null, false);
    }

    private void answer(String text, boolean alert) {
        if (answered) return;
        answered = true;
        tg.answerCallback(id, text, alert);
    }

    /** Sửa chính tin nhắn chứa nút vừa bấm. */
    public void editSelf(String text, java.util.List<java.util.List<Btn>> kb) {
        tg.edit(member.telegramId(), messageId, text, kb);
    }
}
