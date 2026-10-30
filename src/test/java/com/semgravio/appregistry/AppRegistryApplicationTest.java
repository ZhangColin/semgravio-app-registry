package com.semgravio.appregistry;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * 上下文加载冒烟测试——用 test profile 跑真实 PostgreSQL，确保骨架可启动、flyway 可起建。
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = NONE)
@ActiveProfiles("test")
class AppRegistryApplicationTest {
    @Test
    void contextLoads() {
    }
}
