package com.semgravio.appregistry;

import com.semgravio.appregistry.application.PlatformSeedAppService;
import com.semgravio.appregistry.domain.signature.port.ApiSecretEncrypter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * 平台预置应用 seed 集成测试——真实 PostgreSQL，@Transactional 每用例回滚。
 *
 * @since 0.1.0
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = NONE)
@ActiveProfiles("test")
@Transactional
class PlatformSeedAppServiceTest {

    private final PlatformSeedAppService seedService;
    private final JdbcTemplate jdbcTemplate;
    private final ApiSecretEncrypter encrypter;
    private final EntityManager entityManager;

    PlatformSeedAppServiceTest(PlatformSeedAppService seedService, JdbcTemplate jdbcTemplate,
                               ApiSecretEncrypter encrypter, EntityManager entityManager) {
        this.seedService = seedService;
        this.jdbcTemplate = jdbcTemplate;
        this.encrypter = encrypter;
        this.entityManager = entityManager;
    }

    @Test
    void givenEmptyDb_whenSeed_thenAdminConsoleExistsWithDecryptableSecret() {
        seedService.seed();

        // 断言：admin-console 应用已创建
        Integer appCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ar_registered_apps WHERE app_code = 'admin-console' AND deleted = false",
                Integer.class);
        assertThat(appCount).isEqualTo(1);

        String appName = jdbcTemplate.queryForObject(
                "SELECT name FROM ar_registered_apps WHERE app_code = 'admin-console' AND deleted = false",
                String.class);
        assertThat(appName).isEqualTo("管理后台");

        // 断言：admin-console ApiKey 已创建，secret 可解密
        String storedSecret = jdbcTemplate.queryForObject(
                "SELECT api_secret FROM ar_api_keys WHERE api_key = 'admin-console' AND deleted = false",
                String.class);
        assertThat(storedSecret).isNotNull();
        assertThat(encrypter.decrypt(storedSecret)).isNotBlank();
    }

    @Test
    void givenExistingAdminConsole_whenSeedAgain_thenNoDuplicates() {
        seedService.seed();

        String secretBefore = jdbcTemplate.queryForObject(
                "SELECT api_secret FROM ar_api_keys WHERE api_key = 'admin-console' AND deleted = false",
                String.class);

        // 再次 seed → 幂等，不重复创建
        seedService.seed();

        Integer appCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ar_registered_apps WHERE app_code = 'admin-console' AND deleted = false",
                Integer.class);
        assertThat(appCount).isEqualTo(1);

        Integer keyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ar_api_keys WHERE api_key = 'admin-console' AND deleted = false",
                Integer.class);
        assertThat(keyCount).isEqualTo(1);

        // secret 不变（未轮换）
        String secretAfter = jdbcTemplate.queryForObject(
                "SELECT api_secret FROM ar_api_keys WHERE api_key = 'admin-console' AND deleted = false",
                String.class);
        assertThat(secretAfter).isEqualTo(secretBefore);
    }

    @Test
    void givenCorruptedCiphertext_whenSeed_thenAutoRotatesAndSecretDecryptable() {
        // 首次 seed 创建 admin-console
        seedService.seed();

        Long appId = jdbcTemplate.queryForObject(
                "SELECT id FROM ar_registered_apps WHERE app_code = 'admin-console' AND deleted = false",
                Long.class);
        String secretBefore = jdbcTemplate.queryForObject(
                "SELECT api_secret FROM ar_api_keys WHERE app_id = ? AND deleted = false",
                String.class, appId);

        // 模拟主密钥轮换：把密文改成垃圾（不可解密）
        jdbcTemplate.update("UPDATE ar_api_keys SET api_secret = 'corrupted-ciphertext' WHERE app_id = ?", appId);
        // 清除 JPA 一级缓存，让后续 find 读到 JDBC 直改的值（模拟跨重启场景）
        entityManager.flush();
        entityManager.clear();

        // 再次 seed → 应检测到解密失败，自动轮换
        seedService.seed();

        // 断言：仍然只有一条记录
        Integer keyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ar_api_keys WHERE app_id = ? AND deleted = false",
                Integer.class, appId);
        assertThat(keyCount).isEqualTo(1);

        // 断言：新 secret 可解密
        String secretAfter = jdbcTemplate.queryForObject(
                "SELECT api_secret FROM ar_api_keys WHERE app_id = ? AND deleted = false",
                String.class, appId);
        assertThat(secretAfter).isNotEqualTo(secretBefore);
        assertThat(secretAfter).isNotEqualTo("corrupted-ciphertext");
        assertThat(encrypter.decrypt(secretAfter)).isNotBlank();
    }
}
