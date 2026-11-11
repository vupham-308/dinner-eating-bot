package com.kai.dinnerbot.service;

import com.kai.dinnerbot.repo.Repo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Kiểm tra lúc khởi động. Không còn khởi tạo thành viên từ cấu hình:
 * khi bảng members trống, người đầu tiên nhắn bot sẽ thành admin (xem BotRouter),
 * sau đó admin thêm người khác qua /admin → Thành viên → Thêm.
 */
@Component
public class MemberSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MemberSeeder.class);

    private final Repo repo;

    public MemberSeeder(Repo repo) {
        this.repo = repo;
    }

    @Override
    public void run(String... args) {
        repo.ensureCookStats();
        if (repo.countMembers() == 0) {
            log.warn("Chưa có thành viên — người đầu tiên nhắn bot sẽ trở thành admin.");
        }
    }
}
