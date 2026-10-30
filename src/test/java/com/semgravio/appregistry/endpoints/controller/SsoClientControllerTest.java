package com.semgravio.appregistry.endpoints.controller;

import com.semgravio.appregistry.application.dto.command.CreateAppCommand;
import com.semgravio.appregistry.application.dto.command.UpdateSsoClientConfigCommand;
import com.semgravio.appregistry.common.TestSignatureHelper;
import com.semgravio.appregistry.domain.signature.port.ApiSecretEncrypter;
import com.cartisan.test.base.ApiTestAssertions;
import com.cartisan.test.base.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SsoClient 端到端测试——MockMvc + 真实 PG，@Transactional 每用例回滚。
 *
 * <p>覆盖 ADR-0005 配置/凭证职责分离契约：凭证接口（{@code POST /credentials}：创建 / 重置，{@code client_id}
 * 终身稳定）+ 配置 PUT（整份替换配置、不动凭证、不动 status）。核心回归：连续两次配置 PUT，{@code client_id} 与
 * {@code client_secret} 逐字节不变。</p>
 *
 * <p>注：{@code client_id} 撞名 409 走 SecureRandom 生成路径无法经 API 触发（碰撞概率可忽略），
 * 其应用层安全性由 {@code existsByClientId}（native，看含软删全行，同 app_code / api_key 同款已证）保证；
 * DB 兜底（普通唯一约束 → DuplicateKeyException → 409）在本类用 jdbcTemplate 实测（见
 * {@link #givenDuplicateClientIdRow_whenInsert_thenUniqueConstraintEnforced()}）。</p>
 *
 * @since 0.1.0
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = NONE)
@ActiveProfiles("test")
@Transactional
class SsoClientControllerTest extends ApiTestBase {

    private static final List<String> REDIRECT_URIS = List.of("https://a.example.com/cb", "https://b.example.com/cb");
    private static final List<String> POST_LOGOUT_REDIRECT_URIS =
            List.of("https://a.example.com/logout", "https://b.example.com/logout");
    private static final Set<String> SCOPES = Set.of("openid", "profile");
    private static final Set<String> GRANTS = Set.of("authorization_code", "refresh_token");

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ApiSecretEncrypter encrypter;

    private TestSignatureHelper signer;

    SsoClientControllerTest(ObjectMapper objectMapper, JdbcTemplate jdbcTemplate,
                            ApiSecretEncrypter encrypter) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.encrypter = encrypter;
    }

    @BeforeEach
    void setUpCaller() {
        signer = TestSignatureHelper.setupCaller(jdbcTemplate, encrypter);
    }

    // ===== 凭证接口 POST /credentials =====

    @Test
    void givenAppWithoutSsoClient_whenCredentials_thenCreatesAndReturnsPlaintextOnceWithEmptyConfig() throws Exception {
        long appId = createApp("sso-app");

        // 凭证接口无请求体；创建时配置为空（配置由 PUT 单独编排，ADR-0005）
        String body = mvc.perform(signer.sign(post("/api/app-registry/apps/{appId}/sso-clients/credentials", appId), null))
                .andExpect(status().isOk())
                .andExpect(ApiTestAssertions.assertOk())
                .andExpect(jsonPath("$.data.appId").value(appId))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.clientId").isString())
                .andExpect(jsonPath("$.data.clientSecret").isString())
                .andExpect(jsonPath("$.data.redirectUris.length()").value(0))
                .andExpect(jsonPath("$.data.postLogoutRedirectUris.length()").value(0))
                .andExpect(jsonPath("$.data.scopes.length()").value(0))
                .andExpect(jsonPath("$.data.grants.length()").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(body).path("data");
        String clientId = data.path("clientId").asText();
        String plaintextSecret = data.path("clientSecret").asText();
        assertThat(clientId).isNotBlank();
        assertThat(plaintextSecret).isNotBlank();
        assertThat(plaintextSecret).isNotEqualTo(clientId);

        // DB 断言：存的是 argon2 hash（非明文、hash-only），hash 能比对回明文
        String stored = dbClientSecret(appId);
        assertThat(stored).isNotEqualTo(plaintextSecret);
        assertThat(stored).startsWith("$argon2");
        assertThat(verifier().matches(plaintextSecret, stored)).isTrue();

        // DB 断言：配置列初始为空 jsonb 数组（凭证接口不校验/不写配置）
        assertThat(jsonbLength("redirect_uris", appId)).isZero();
        assertThat(jsonbLength("post_logout_redirect_uris", appId)).isZero();
    }

    @Test
    void givenExistingSsoClient_whenCredentials_thenResetsSecretAndClientIdStable() throws Exception {
        long appId = createApp("sso-app");
        String[] first = credentialsOnly(appId);
        String oldClientId = first[0];
        String oldPlaintext = first[1];

        // 第二次调凭证接口 = 重置：client_id 终身稳定、只换 client_secret
        String body = mvc.perform(signer.sign(post("/api/app-registry/apps/{appId}/sso-clients/credentials", appId), null))
                .andExpect(status().isOk())
                .andExpect(ApiTestAssertions.assertOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");
        String newClientId = data.path("clientId").asText();
        String newPlaintext = data.path("clientSecret").asText();

        assertThat(newClientId).isEqualTo(oldClientId);
        assertThat(newPlaintext).isNotEqualTo(oldPlaintext);

        // 重置后的 hash 仍比对回新明文（非旧明文）
        String stored = dbClientSecret(appId);
        assertThat(verifier().matches(newPlaintext, stored)).isTrue();
        assertThat(verifier().matches(oldPlaintext, stored)).isFalse();
    }

    @Test
    void givenMissingApp_whenCredentials_then404() throws Exception {
        mvc.perform(signer.sign(post("/api/app-registry/apps/{appId}/sso-clients/credentials", 77777777777L), null))
                .andExpect(status().isNotFound())
                .andExpect(ApiTestAssertions.assertError(404));
    }

    // ===== 配置 PUT =====

    @Test
    void givenTwoConfigPuts_whenSecondPut_thenClientIdAndSecretByteIdentical() throws Exception {
        // 核心回归（ADR-0005 痛点）：连续两次配置 PUT，client_id 与 client_secret 逐字节不变。
        long appId = createApp("sso-app");
        String[] cred = credentialsOnly(appId);
        String clientId = cred[0];
        String plaintext = cred[1];
        String hashBefore = dbClientSecret(appId);

        // 第一次配置 PUT
        putConfig(appId, REDIRECT_URIS, POST_LOGOUT_REDIRECT_URIS, SCOPES, GRANTS);
        String clientIdAfterFirst = dbClientId(appId);
        String hashAfterFirst = dbClientSecret(appId);

        // 第二次配置 PUT（换一份完全不同的配置）
        List<String> otherUris = List.of("https://other.example.com/cb");
        List<String> otherPostLogoutUris = List.of("https://other.example.com/logout");
        putConfig(appId, otherUris, otherPostLogoutUris, Set.of("openid"), null);
        String clientIdAfterSecond = dbClientId(appId);
        String hashAfterSecond = dbClientSecret(appId);

        // client_id + client_secret 两次 PUT 逐字节不变（核心回归）
        assertThat(clientIdAfterFirst).isEqualTo(clientId);
        assertThat(clientIdAfterSecond).isEqualTo(clientId);
        assertThat(hashAfterFirst).isEqualTo(hashBefore);
        assertThat(hashAfterSecond).isEqualTo(hashBefore);
        // 凭证未换：hash 仍比对回原明文
        assertThat(verifier().matches(plaintext, hashAfterSecond)).isTrue();
        // 第二次 PUT 的配置确实落地（证明 PUT 生效、非空跑）
        assertThat(jsonbElement("redirect_uris", appId, 0)).isEqualTo("https://other.example.com/cb");
    }

    @Test
    void givenSsoClientWithCredentials_whenUpdateConfig_thenConfigAppliedAndCredentialsUntouched() throws Exception {
        long appId = createApp("sso-app");
        String[] cred = credentialsOnly(appId);
        String clientId = cred[0];
        String plaintext = cred[1];
        String hashBefore = dbClientSecret(appId);

        String body = mvc.perform(signedPutConfig(appId,
                new UpdateSsoClientConfigCommand(REDIRECT_URIS, POST_LOGOUT_REDIRECT_URIS, SCOPES, GRANTS)))
                .andExpect(status().isOk())
                .andExpect(ApiTestAssertions.assertOk())
                .andExpect(jsonPath("$.data.clientId").value(clientId))
                .andExpect(jsonPath("$.data.redirectUris.length()").value(2))
                .andExpect(jsonPath("$.data.postLogoutRedirectUris.length()").value(2))
                .andExpect(jsonPath("$.data.scopes.length()").value(2))
                .andExpect(jsonPath("$.data.grants.length()").value(2))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.clientSecret").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // 配置 PUT 不动凭证：client_id + client_secret 逐字节不变（GET 响应里无 clientSecret，DB 断言 hash）
        assertThat(dbClientId(appId)).isEqualTo(clientId);
        assertThat(dbClientSecret(appId)).isEqualTo(hashBefore);
        assertThat(verifier().matches(plaintext, dbClientSecret(appId))).isTrue();
        // 响应里无明文（配置接口不返 secret）
        assertThat(objectMapper.readTree(body).path("data").has("clientSecret")).isFalse();
    }

    @Test
    void givenNoSsoClient_whenUpdateConfig_then404() throws Exception {
        // 配置 PUT 要求 SsoClient 已存在（client_id 已生成），不存在 → 404
        long appId = createApp("sso-app");

        mvc.perform(signedPutConfig(appId,
                new UpdateSsoClientConfigCommand(REDIRECT_URIS, POST_LOGOUT_REDIRECT_URIS, SCOPES, GRANTS)))
                .andExpect(status().isNotFound())
                .andExpect(ApiTestAssertions.assertError(404));
    }

    @Test
    void givenEmptyRedirectUris_whenUpdateConfig_then400() throws Exception {
        long appId = createApp("sso-app");
        createClient(appId);

        mvc.perform(signedPutConfig(appId,
                new UpdateSsoClientConfigCommand(List.of(), POST_LOGOUT_REDIRECT_URIS, SCOPES, GRANTS)))
                .andExpect(status().isBadRequest())
                .andExpect(ApiTestAssertions.assertError(400));
    }

    @Test
    void givenEmptyPostLogoutRedirectUris_whenUpdateConfig_then400() throws Exception {
        // @NotEmpty 仅在配置接口生效（ADR-0005 不变式迁移）
        long appId = createApp("sso-app");
        createClient(appId);

        mvc.perform(signedPutConfig(appId,
                new UpdateSsoClientConfigCommand(REDIRECT_URIS, List.of(), SCOPES, GRANTS)))
                .andExpect(status().isBadRequest())
                .andExpect(ApiTestAssertions.assertError(400));
    }

    @Test
    void givenMissingApp_whenUpdateConfig_then404() throws Exception {
        mvc.perform(signedPutConfig(77777777777L,
                new UpdateSsoClientConfigCommand(REDIRECT_URIS, POST_LOGOUT_REDIRECT_URIS, SCOPES, GRANTS)))
                .andExpect(status().isNotFound())
                .andExpect(ApiTestAssertions.assertError(404));
    }

    // ===== 旧 createOrRotate 路径已删除 =====

    @Test
    void givenOldCreateOrRotatePath_whenPostRoot_then4xx() throws Exception {
        // 旧 POST 根路径（createOrRotate）已删除（ADR-0005 破坏性契约变更）→ POST 不再被接受 → 4xx。
        long appId = createApp("sso-app");

        mvc.perform(signer.sign(post("/api/app-registry/apps/{appId}/sso-clients", appId), null))
                .andExpect(status().is4xxClientError());
    }

    // ===== GET / bootstrap / 启停（配置由 helper 补齐，断言配置在位）=====

    @Test
    void givenExistingSsoClient_whenGet_thenNoPlaintextSecret() throws Exception {
        long appId = createApp("sso-app");
        String clientId = createClient(appId);

        mvc.perform(signer.sign(get("/api/app-registry/apps/{appId}/sso-clients", appId), null))
                .andExpect(status().isOk())
                .andExpect(ApiTestAssertions.assertOk())
                .andExpect(jsonPath("$.data.clientId").value(clientId))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.redirectUris.length()").value(2))
                .andExpect(jsonPath("$.data.postLogoutRedirectUris.length()").value(2))
                .andExpect(jsonPath("$.data.clientSecret").doesNotExist());
    }

    @Test
    void givenActiveClient_whenBootstrap_thenReturnsHashAndActiveNoPlaintext() throws Exception {
        long appId = createApp("sso-app");
        String[] created = createClientWithSecret(appId);
        String clientId = created[0];
        String plaintext = created[1];

        // SSO bootstrap 端点需要验签
        String body = mvc.perform(signer.sign(get("/api/app-registry/sso-clients/{clientId}", clientId), null))
                .andExpect(status().isOk())
                .andExpect(ApiTestAssertions.assertOk())
                .andExpect(jsonPath("$.data.clientId").value(clientId))
                .andExpect(jsonPath("$.data.clientName").value("n"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.redirectUris.length()").value(2))
                .andExpect(jsonPath("$.data.postLogoutRedirectUris.length()").value(2))
                .andExpect(jsonPath("$.data.scopes.length()").value(2))
                .andExpect(jsonPath("$.data.grants.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        // bootstrap 返 hash、不返明文：hash ≠ 明文，且是 DB 里那份 argon2 hash
        JsonNode data = objectMapper.readTree(body).path("data");
        String hash = data.path("clientSecretHash").asText();
        assertThat(hash).isNotEqualTo(plaintext);
        assertThat(hash).startsWith("$argon2");
        assertThat(hash).isEqualTo(dbClientSecret(appId));
        // 响应里绝无明文字段
        assertThat(data.has("clientSecret")).isFalse();
    }

    @Test
    void givenAppDisabled_whenBootstrap_thenInactiveAndHashWithheld() throws Exception {
        long appId = createApp("sso-app");
        String clientId = createClient(appId);

        // 禁用 app → 组合状态 active=false，hash 被扣留（不返有效元数据）
        mvc.perform(signer.sign(put("/api/app-registry/apps/{appId}/disable", appId), null))
                .andExpect(status().isOk());

        mvc.perform(signer.sign(get("/api/app-registry/sso-clients/{clientId}", clientId), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.clientSecretHash").doesNotExist());

        // 重新启用 app → 恢复 active=true，hash 回来
        mvc.perform(signer.sign(put("/api/app-registry/apps/{appId}/enable", appId), null))
                .andExpect(status().isOk());

        mvc.perform(signer.sign(get("/api/app-registry/sso-clients/{clientId}", clientId), null))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.clientSecretHash").isString());
    }

    @Test
    void givenSsoClientDisabled_whenBootstrap_thenInactiveAndHashWithheld() throws Exception {
        long appId = createApp("sso-app");
        String clientId = createClient(appId);

        mvc.perform(signer.sign(put("/api/app-registry/apps/{appId}/sso-clients/disable", appId), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(0));

        mvc.perform(signer.sign(get("/api/app-registry/sso-clients/{clientId}", clientId), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.clientSecretHash").doesNotExist());
    }

    @Test
    void givenAppWithoutSsoClient_whenGet_then404() throws Exception {
        long appId = createApp("sso-app");

        mvc.perform(signer.sign(get("/api/app-registry/apps/{appId}/sso-clients", appId), null))
                .andExpect(status().isNotFound())
                .andExpect(ApiTestAssertions.assertError(404));
    }

    @Test
    void givenUnknownClientId_whenBootstrap_then404() throws Exception {
        mvc.perform(signer.sign(get("/api/app-registry/sso-clients/{clientId}", "nonexistent-client"), null))
                .andExpect(status().isNotFound())
                .andExpect(ApiTestAssertions.assertError(404));
    }

    @Test
    void givenDisabledSsoClient_whenDisableAgain_then409() throws Exception {
        long appId = createApp("sso-app");
        createClient(appId);

        mvc.perform(signer.sign(put("/api/app-registry/apps/{appId}/sso-clients/disable", appId), null))
                .andExpect(status().isOk());
        mvc.perform(signer.sign(put("/api/app-registry/apps/{appId}/sso-clients/disable", appId), null))
                .andExpect(status().isConflict())
                .andExpect(ApiTestAssertions.assertError(409));

        mvc.perform(signer.sign(put("/api/app-registry/apps/{appId}/sso-clients/enable", appId), null))
                .andExpect(status().isOk());
        mvc.perform(signer.sign(put("/api/app-registry/apps/{appId}/sso-clients/enable", appId), null))
                .andExpect(status().isConflict())
                .andExpect(ApiTestAssertions.assertError(409));
    }

    @Test
    void givenDuplicateClientIdRow_whenInsert_thenUniqueConstraintEnforced() {
        // client_id 撞名的 DB 兜底：普通唯一约束 → DuplicateKeyException（全局异常 → 409）。
        // 应用层先查路径（existsByClientId native）同 app_code / api_key 同款已证。
        // 注：PG 事务内一次约束违例后整事务 aborted（25P02），故活跃/软删两场景分两个测试方法。
        insertFkTargetApp();
        insertSsoClientRow(900001, "taken-client-id", false);

        assertThatThrownBy(() -> insertSsoClientRow(900002, "taken-client-id", false))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void givenSoftDeletedClientIdRow_whenInsert_thenUniqueConstraintStillEnforced() {
        // 软删行也占名额：client_id 不可复用（同 app_code/api_key 语义，ADR-0003 §6）。
        insertFkTargetApp();
        insertSsoClientRow(900001, "taken-client-id", false);

        assertThatThrownBy(() -> insertSsoClientRow(900002, "taken-client-id", true))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ---- helpers ----

    private void insertFkTargetApp() {
        jdbcTemplate.update("INSERT INTO ar_registered_apps (id, app_code, name, status, created_at, updated_at, deleted) "
                + "VALUES (900001, 'fk-target', 'n', 1, now(), now(), false)");
    }

    private void insertSsoClientRow(long id, String clientId, boolean deleted) {
        jdbcTemplate.update("INSERT INTO ar_sso_clients (id, app_id, client_id, client_secret, redirect_uris, scopes, grants, "
                + "status, created_at, updated_at, deleted) VALUES (?, 900001, ?, 'hash', '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, "
                + "1, now(), now(), ?)", id, clientId, deleted);
    }

    private long createApp(String appCode) throws Exception {
        String json = ApiTestAssertions.toJson(new CreateAppCommand(appCode, "n", null));
        String body = mvc.perform(signer.sign(post("/api/app-registry/apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json), json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    /** 调凭证接口建 client（不补配置），返回 [clientId, 明文 secret]。 */
    private String[] credentialsOnly(long appId) throws Exception {
        String body = mvc.perform(signer.sign(post("/api/app-registry/apps/{appId}/sso-clients/credentials", appId), null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");
        return new String[]{data.path("clientId").asText(), data.path("clientSecret").asText()};
    }

    /** 凭证接口建 client + 配置 PUT 补配置，返回 [clientId, 明文 secret]。供需「配置在位」的测试用。 */
    private String[] createClientWithSecret(long appId) throws Exception {
        String[] cred = credentialsOnly(appId);
        putConfig(appId, REDIRECT_URIS, POST_LOGOUT_REDIRECT_URIS, SCOPES, GRANTS);
        return cred;
    }

    private String createClient(long appId) throws Exception {
        return createClientWithSecret(appId)[0];
    }

    private void putConfig(long appId, List<String> redirectUris, List<String> postLogoutRedirectUris,
                           Set<String> scopes, Set<String> grants) throws Exception {
        mvc.perform(signedPutConfig(appId,
                new UpdateSsoClientConfigCommand(redirectUris, postLogoutRedirectUris, scopes, grants)))
                .andExpect(status().isOk());
    }

    /** 构造已签名的配置 PUT 请求（json 既作 body 又参与签名摘要）。 */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signedPutConfig(
            long appId, UpdateSsoClientConfigCommand command) throws Exception {
        String json = ApiTestAssertions.toJson(command);
        return signer.sign(put("/api/app-registry/apps/{appId}/sso-clients", appId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json), json);
    }

    private String dbClientId(long appId) {
        return jdbcTemplate.queryForObject(
                "SELECT client_id FROM ar_sso_clients WHERE app_id = ? AND deleted = false", String.class, appId);
    }

    private String dbClientSecret(long appId) {
        return jdbcTemplate.queryForObject(
                "SELECT client_secret FROM ar_sso_clients WHERE app_id = ? AND deleted = false", String.class, appId);
    }

    private int jsonbLength(String column, long appId) {
        return jdbcTemplate.queryForObject(
                "SELECT jsonb_array_length(" + column + ") FROM ar_sso_clients WHERE app_id = ? AND deleted = false",
                Integer.class, appId);
    }

    private String jsonbElement(String column, long appId, int index) {
        // 索引按 int 绑定 → Postgres 选 jsonb ->> integer（数组下标），非 ->> text（对象键，数组上恒为 null）。
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " ->> ? FROM ar_sso_clients WHERE app_id = ? AND deleted = false",
                String.class, index, appId);
    }

    private static Argon2PasswordEncoder verifier() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
