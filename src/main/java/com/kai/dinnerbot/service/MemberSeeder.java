package com.kai.dinnerbot.service;

import com.kai.dinnerbot.repo.Repo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** Khởi tạo 3 thành viên từ cấu hình bot.members — chỉ chạy khi bảng members còn trống. */
@Component
public class MemberSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MemberSeeder.class);

    private final Repo repo;
    private final String members;

    public MemberSeeder(Repo repo, @Value("${bot.members:}") String members) {
        this.repo = repo;
        this.members = members;
    }

    @Override
    public void run(String... args) {
        repo.ensureCookStats();
        if (repo.countMembers() > 0 || members == null || members.isBlank()) return;
        for (String item : members.split(",")) {
            String[] p = item.trim().split(":");
            if (p.length < 2) continue;
            String role = p.length >= 3 ? p[2].trim().toLowerCase() : "member";
            repo.insertMember(Long.parseLong(p[0].trim()), p[1].trim(), role);
            log.info("Đã thêm thành viên {} ({})", p[1].trim(), role);
        }
    }
}
