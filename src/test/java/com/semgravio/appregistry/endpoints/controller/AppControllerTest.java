package com.semgravio.appregistry.endpoints.controller;

import com.semgravio.appregistry.application.dto.command.CreateAppCommand;
import com.semgravio.appregistry.common.TestSignatureHelper;
import com.semgravio.appregistry.domain.signature.port.ApiSecretEncrypter;
import com.cartisan.test.base.ApiTestAssertions;
import com.cartisan.test.base.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 应用登记端到端测试——MockMvc + 真实 PostgreSQL，@Transactional 每用例回滚。
 *
 * @since 0.1.0
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = NONE)
@ActiveProfiles("test")
@Transactional
class AppControllerTest extends ApiTestBase {

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ApiSecretEncrypter encrypter;

    private TestSignatureHelper signer;

    AppControllerTest(ObjectMapper objectMapper, JdbcTemplate jdbcTemplate,
                      ApiSecretEncrypter encrypter) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.encrypter = encrypter;
    }

    @BeforeEach
    void setUpCaller() {
        signer = TestSignatureHelper.setupCaller(jdbcTemplate, encrypter);
    }

    @Test
    void givenValidCommand_whenCreate_thenCreatedAndPersisted() throws Exception {
        String json = ApiTestAssertions.toJson(new CreateAppCommand("payment-service", "支付服务", "desc"));
        String body = mvc.perform(signer.sign(post("/api/app-registry/apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json), json))
                .andExpect(status().isOk())
                .andExpect(ApiTestAssertions.assertOk())
                .andExpect(jsonPath("$.data.appCode").value("payment-service"))
                .andExpect(jsonPath("$.data.name").value("支付服务"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.statusName").value("启用"))
                .andReturn().getResponse().getContentAsString();

        Long id = objectMapper.readTree(body).path("data").path("id").asLong();
        assertThat(id).isNotNull();

        // DB 断言：行已落库（saveAndFlush 在同事务内 flush）
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ar_registered_apps WHERE id = ? AND app_code = ? AND deleted = false",
                Integer.class, id, "payment-service");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void givenExistingApp_whenGetById_thenReturned() throws Exception {
        long id = createApp("payment-service");

        mvc.perform(signer.sign(get("/api/app-registry/apps/{id}", id), null))
                .andExpect(status().isOk())
                .andExpect(ApiTestAssertions.assertOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.appCode").value("payment-service"));
    }

    @Test
    void givenAppCodeTaken_whenCreate_then409() throws Exception {
        createApp("payment-service");

        String json = ApiTestAssertions.toJson(new CreateAppCommand("payment-service", "另一个", null));
        mvc.perform(signer.sign(post("/api/app-registry/apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json), json))
                .andExpect(status().isConflict())
                .andExpect(ApiTestAssertions.assertError(409));
    }

    @Test
    void givenAppCodeTakenBySoftDeletedRow_whenCreate_then409() throws Exception {
        // 直接插入一行软删记录——native 撞名查询必须看到它（绕过 @SQLRestriction）
        jdbcTemplate.update(
                "INSERT INTO ar_registered_apps (id, app_code, name, status, created_at, updated_at, deleted) " +
                        "VALUES (?, ?, ?, ?, now(), now(), true)",
                99001L, "ghost-app", "幽灵", 1);

        String json = ApiTestAssertions.toJson(new CreateAppCommand("ghost-app", "新幽灵", null));
        mvc.perform(signer.sign(post("/api/app-registry/apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json), json))
                .andExpect(status().isConflict())
                .andExpect(ApiTestAssertions.assertError(409));
    }

    @Test
    void givenInvalidAppCode_whenCreate_then400() throws Exception {
        String json = ApiTestAssertions.toJson(new CreateAppCommand("Bad_Code!", "名", null));
        mvc.perform(signer.sign(post("/api/app-registry/apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json), json))
                .andExpect(status().isBadRequest())
                .andExpect(ApiTestAssertions.assertError(400));
    }

    @Test
    void givenActiveApp_whenDisableThenEnable_thenStatusTogglesAndAppCodeUnchanged() throws Exception {
        long id = createApp("payment-service");

        mvc.perform(signer.sign(put("/api/app-registry/apps/{id}/disable", id), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(0))
                .andExpect(jsonPath("$.data.statusName").value("禁用"))
                .andExpect(jsonPath("$.data.appCode").value("payment-service"));

        mvc.perform(signer.sign(put("/api/app-registry/apps/{id}/enable", id), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.appCode").value("payment-service"));
    }

    @Test
    void givenDisabledApp_whenDisableAgain_then409() throws Exception {
        long id = createApp("payment-service");

        mvc.perform(signer.sign(put("/api/app-registry/apps/{id}/disable", id), null))
                .andExpect(status().isOk());

        mvc.perform(signer.sign(put("/api/app-registry/apps/{id}/disable", id), null))
                .andExpect(status().isConflict())
                .andExpect(ApiTestAssertions.assertError(409));
    }

    @Test
    void givenAdminConsoleApp_whenDisable_then403() throws Exception {
        long id = createApp("admin-console");

        mvc.perform(signer.sign(put("/api/app-registry/apps/{id}/disable", id), null))
                .andExpect(status().isForbidden())
                .andExpect(ApiTestAssertions.assertError(403));
    }

    @Test
    void givenMissingApp_whenGet_then404() throws Exception {
        mvc.perform(signer.sign(get("/api/app-registry/apps/{id}", 88888888888L), null))
                .andExpect(status().isNotFound())
                .andExpect(ApiTestAssertions.assertError(404));
    }

    // ---- list (paginated) ----

    @Test
    void givenMultipleApps_whenListPaged_then1BasedTwoPageTraversal() throws Exception {
        // keyword 隔离本用例的行（seed 与签名 caller 不含 "page-svc"），total 断言精确到 3
        createApp("page-svc-a");
        createApp("page-svc-b");
        createApp("page-svc-c");

        // wire page=1（1-based，#24 起）取第一页 2 项 / total=3 / 回显 page=1
        mvc.perform(signer.sign(get("/api/app-registry/apps")
                        .param("keyword", "page-svc")
                        .param("page", "1")
                        .param("size", "2"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items[0].appCode").isNotEmpty())
                .andExpect(jsonPath("$.items[0].apiSecret").doesNotExist());

        // wire page=2 取第二页 1 项 / 回显 page=2
        mvc.perform(signer.sign(get("/api/app-registry/apps")
                        .param("keyword", "page-svc")
                        .param("page", "2")
                        .param("size", "2"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(2));

        // 框架 clamp（#24）：wire page=0（旧 0-based 调用方的"第一页"）静默贴边为 page=1 → 回第一页、回显 1
        mvc.perform(signer.sign(get("/api/app-registry/apps")
                        .param("keyword", "page-svc")
                        .param("page", "0")
                        .param("size", "2"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    void givenSizeOverLimit_whenList_thenClampedTo100() throws Exception {
        // 框架 clamp（#24）：size 上限 100，越界静默贴边 → 回显 100
        mvc.perform(signer.sign(get("/api/app-registry/apps")
                        .param("size", "500"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void givenNoSortParam_whenList_thenDefaultsToCreatedAtDesc() throws Exception {
        // 默认排序契约（admin BFF 不传 sort）：createdAt 降序（最新登记在前）。
        // createdAt 由审计填充、连续创建可能同毫秒，故显式把先建的应用拨早一天，消除时序巧合。
        createApp("sort-older");
        createApp("sort-newer");
        jdbcTemplate.update(
                "UPDATE ar_registered_apps SET created_at = now() - interval '1 day' WHERE app_code = ?",
                "sort-older");

        mvc.perform(signer.sign(get("/api/app-registry/apps")
                        .param("keyword", "sort-"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].appCode").value("sort-newer"))
                .andExpect(jsonPath("$.items[1].appCode").value("sort-older"));
    }

    @Test
    void givenPageOutOfBounds_whenList_thenEmptyPage200() throws Exception {
        int baseline = countApps(); // 含 seed 与签名 caller

        // 分页越界（取第 99 页）→ 空页（items=[]、total 不变），200 不报错，请求页码原样回显（1-based 99）
        mvc.perform(signer.sign(get("/api/app-registry/apps")
                        .param("page", "99")
                        .param("size", "2"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(baseline))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page").value(99));
    }

    @Test
    void givenKeyword_whenList_thenFilterByAppCodeAndName() throws Exception {
        // 用不与平台 seed（identity/payment）碰撞的 keyword，保证过滤结果对 seed 行免疫。
        createApp("checkout-svc");
        createApp("order-service");
        createApp("other-app");

        mvc.perform(signer.sign(get("/api/app-registry/apps")
                        .param("keyword", "checkout"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].appCode").value("checkout-svc"));
    }

    @Test
    void givenStatusFilter_whenList_thenReturnMatchingOnly() throws Exception {
        int baselineEnabled = countByStatus(1); // 含 seed（identity）与签名 caller
        int baselineDisabled = countByStatus(0);
        long id = createApp("payment-service");
        createApp("order-service");

        // 禁用 payment-service
        mvc.perform(signer.sign(put("/api/app-registry/apps/{id}/disable", id), null))
                .andExpect(status().isOk());

        // 查禁用
        mvc.perform(signer.sign(get("/api/app-registry/apps")
                        .param("status", "0"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(baselineDisabled + 1))
                .andExpect(jsonPath("$.items[0].appCode").value("payment-service"));

        // 查启用
        mvc.perform(signer.sign(get("/api/app-registry/apps")
                        .param("status", "1"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(baselineEnabled + 1));
    }

    @Test
    void givenNoParams_whenList_thenReturnAll() throws Exception {
        int baseline = countApps();
        createApp("svc-a");
        createApp("svc-b");

        mvc.perform(signer.sign(get("/api/app-registry/apps"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(baseline + 2))
                .andExpect(jsonPath("$.total").value(baseline + 2))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void givenNoSignature_whenCallRequireSignatureEndpoint_then401() throws Exception {
        // 对 @RequireSignature 端点发无签名请求 → 拦截器返回 401
        mvc.perform(get("/api/app-registry/apps/{id}", 1))
                .andExpect(status().isUnauthorized());
    }

    // ---- update ----

    @Test
    void givenValidUpdate_whenUpdate_thenNameAndDescriptionUpdated() throws Exception {
        long id = createApp("payment-service");

        String json = ApiTestAssertions.toJson(Map.of("name", "新支付服务", "description", "新描述"));
        mvc.perform(signer.sign(put("/api/app-registry/apps/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json), json))
                .andExpect(status().isOk())
                .andExpect(ApiTestAssertions.assertOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.appCode").value("payment-service"))
                .andExpect(jsonPath("$.data.name").value("新支付服务"))
                .andExpect(jsonPath("$.data.description").value("新描述"))
                .andExpect(jsonPath("$.data.status").value(1));
    }

    @Test
    void givenNullDescription_whenUpdate_thenDescriptionCleared() throws Exception {
        long id = createApp("payment-service");

        String json = ApiTestAssertions.toJson(Map.of("name", "新支付服务"));
        mvc.perform(signer.sign(put("/api/app-registry/apps/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json), json))
                .andExpect(status().isOk())
                .andExpect(ApiTestAssertions.assertOk())
                .andExpect(jsonPath("$.data.name").value("新支付服务"))
                .andExpect(jsonPath("$.data.description").doesNotExist());
    }

    @Test
    void givenDisabledApp_whenUpdate_thenStillAllowed() throws Exception {
        long id = createApp("payment-service");

        // 先禁用
        mvc.perform(signer.sign(put("/api/app-registry/apps/{id}/disable", id), null))
                .andExpect(status().isOk());

        // 更新
        String json = ApiTestAssertions.toJson(Map.of("name", "新名", "description", "desc"));
        mvc.perform(signer.sign(put("/api/app-registry/apps/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json), json))
                .andExpect(status().isOk())
                .andExpect(ApiTestAssertions.assertOk())
                .andExpect(jsonPath("$.data.name").value("新名"))
                .andExpect(jsonPath("$.data.status").value(0));
    }

    @Test
    void givenMissingApp_whenUpdate_then404() throws Exception {
        String json = ApiTestAssertions.toJson(Map.of("name", "新名"));
        mvc.perform(signer.sign(put("/api/app-registry/apps/{id}", 88888888888L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json), json))
                .andExpect(status().isNotFound())
                .andExpect(ApiTestAssertions.assertError(404));
    }

    @Test
    void givenBlankName_whenUpdate_then400() throws Exception {
        long id = createApp("payment-service");

        String json = ApiTestAssertions.toJson(Map.of("name", "  "));
        mvc.perform(signer.sign(put("/api/app-registry/apps/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json), json))
                .andExpect(status().isBadRequest())
                .andExpect(ApiTestAssertions.assertError(400));
    }

    @Test
    void givenNoSignature_whenUpdate_then401() throws Exception {
        String json = ApiTestAssertions.toJson(Map.of("name", "新名"));
        mvc.perform(put("/api/app-registry/apps/{id}", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnauthorized());
    }

    private long createApp(String appCode) throws Exception {
        String json = ApiTestAssertions.toJson(new CreateAppCommand(appCode, "n", null));
        String body = mvc.perform(signer.sign(post("/api/app-registry/apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json), json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return node.path("data").path("id").asLong();
    }

    /** 未软删应用总数（含 seed 与签名 caller）——分页 total 断言的基线，使其对 seed 行免疫。*/
    private int countApps() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ar_registered_apps WHERE deleted = false", Long.class);
        return count != null ? count.intValue() : 0;
    }

    /** 按状态统计未软删应用数（1=启用 / 0=禁用）。*/
    private int countByStatus(int status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ar_registered_apps WHERE deleted = false AND status = ?",
                Long.class, status);
        return count != null ? count.intValue() : 0;
    }
}
