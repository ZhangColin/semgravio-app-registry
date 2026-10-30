package com.semgravio.appregistry.domain.sso.aggregate;

import com.semgravio.appregistry.domain.error.AppRegistryMessage;
import com.semgravio.appregistry.domain.sso.enums.SsoClientStatus;
import com.cartisan.core.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SsoClient} 聚合根领域逻辑单元测试。
 *
 * <p>覆盖 ADR-0005 的职责分离操作模型：{@code create}（仅凭证）/ {@code resetCredentials}（仅 secret、
 * client_id 终身稳定）/ {@code updateConfig}（仅配置、两 URI 列表 @NotEmpty）/ {@code enable}-{@code disable}（仅状态）。
 * 四个原语正交——任一操作不连带改动其它维度。</p>
 *
 * @since 0.1.0
 */
class SsoClientTest {

    private static final long APP_ID = 1001L;
    private static final List<String> REDIRECT_URIS = List.of("https://a.example.com/cb", "https://b.example.com/cb");
    private static final List<String> POST_LOGOUT_REDIRECT_URIS =
            List.of("https://a.example.com/logout", "https://b.example.com/logout");
    private static final Set<String> SCOPES = Set.of("openid", "profile");
    private static final Set<String> GRANTS = Set.of("authorization_code");

    // ===== create（仅凭证：client_id + hash；配置初始为空）=====

    @Test
    void givenValidInput_whenCreate_thenActiveWithIdAndCredentialsAndEmptyConfig() {
        SsoClient client = SsoClient.create(APP_ID, "cid_abc", "hash_xyz");

        assertThat(client.getId()).isNotNull();
        assertThat(client.getAppId()).isEqualTo(APP_ID);
        assertThat(client.getClientId()).isEqualTo("cid_abc");
        assertThat(client.getClientSecret()).isEqualTo("hash_xyz");
        // 配置接口未调用前为空（允许「凭证已建、配置未 PUT」中间态，ADR-0005）
        assertThat(client.getRedirectUris()).isEmpty();
        assertThat(client.getPostLogoutRedirectUris()).isEmpty();
        assertThat(client.getScopes()).isEmpty();
        assertThat(client.getGrants()).isEmpty();
        assertThat(client.getStatus()).isEqualTo(SsoClientStatus.ACTIVE);
    }

    // ===== resetCredentials（仅 secret：client_id 终身稳定、status 不变）=====

    @Test
    void givenCreatedClient_whenResetCredentials_thenSecretChangesClientIdAndConfigUntouched() {
        SsoClient client = SsoClient.create(APP_ID, "cid_abc", "hash_old");

        client.resetCredentials("hash_new");

        // client_id 终身稳定（ADR-0005：修正旧 createOrRotate 每次换 client_id）
        assertThat(client.getClientId()).isEqualTo("cid_abc");
        assertThat(client.getClientSecret()).isEqualTo("hash_new");
        // 配置不受凭证操作影响
        assertThat(client.getRedirectUris()).isEmpty();
        assertThat(client.getStatus()).isEqualTo(SsoClientStatus.ACTIVE);
    }

    @Test
    void givenDisabledClient_whenResetCredentials_thenStatusStaysDisabled() {
        // 凭证操作与启停正交：reset 不复活已禁用 client（要启用走 enable）
        SsoClient client = SsoClient.create(APP_ID, "cid_abc", "hash_old");
        client.disable();

        client.resetCredentials("hash_new");

        assertThat(client.getStatus()).isEqualTo(SsoClientStatus.DISABLED);
        assertThat(client.getClientSecret()).isEqualTo("hash_new");
        assertThat(client.getClientId()).isEqualTo("cid_abc");
    }

    // ===== updateConfig（仅配置：两 URI 列表 @NotEmpty；凭证/状态不动）=====

    @Test
    void givenCreatedClient_whenUpdateConfig_thenConfigReplacedCredentialsAndStatusUntouched() {
        SsoClient client = SsoClient.create(APP_ID, "cid_abc", "hash_xyz");

        client.updateConfig(REDIRECT_URIS, POST_LOGOUT_REDIRECT_URIS, SCOPES, GRANTS);

        assertThat(client.getRedirectUris()).containsExactlyElementsOf(REDIRECT_URIS);
        assertThat(client.getPostLogoutRedirectUris()).containsExactlyElementsOf(POST_LOGOUT_REDIRECT_URIS);
        assertThat(client.getScopes()).containsExactlyInAnyOrderElementsOf(SCOPES);
        assertThat(client.getGrants()).containsExactlyInAnyOrderElementsOf(GRANTS);
        // 核心回归：改配置不动凭证（client_id + secret 逐字节不变）——本片要修的痛点
        assertThat(client.getClientId()).isEqualTo("cid_abc");
        assertThat(client.getClientSecret()).isEqualTo("hash_xyz");
        assertThat(client.getStatus()).isEqualTo(SsoClientStatus.ACTIVE);
    }

    @Test
    void givenNullScopesAndGrants_whenUpdateConfig_thenEmptySetsNotNull() {
        SsoClient client = SsoClient.create(APP_ID, "cid_abc", "hash_xyz");

        client.updateConfig(REDIRECT_URIS, POST_LOGOUT_REDIRECT_URIS, null, null);

        assertThat(client.getScopes()).isEmpty();
        assertThat(client.getGrants()).isEmpty();
    }

    @Test
    void givenConfigApplied_whenUpdateConfigAgain_thenConfigReplacedCredentialsStillUntouched() {
        // 连续两次配置 PUT：配置生效两次、凭证两轮逐字节不变
        SsoClient client = SsoClient.create(APP_ID, "cid_abc", "hash_xyz");
        client.updateConfig(REDIRECT_URIS, POST_LOGOUT_REDIRECT_URIS, SCOPES, GRANTS);

        List<String> newUris = List.of("https://new.example.com/cb");
        List<String> newPostLogoutUris = List.of("https://new.example.com/logout");
        Set<String> newScopes = Set.of("openid");
        client.updateConfig(newUris, newPostLogoutUris, newScopes, null);

        assertThat(client.getRedirectUris()).containsExactlyElementsOf(newUris);
        assertThat(client.getPostLogoutRedirectUris()).containsExactlyElementsOf(newPostLogoutUris);
        assertThat(client.getScopes()).containsExactlyInAnyOrderElementsOf(newScopes);
        assertThat(client.getGrants()).isEmpty();
        assertThat(client.getClientId()).isEqualTo("cid_abc");
        assertThat(client.getClientSecret()).isEqualTo("hash_xyz");
    }

    @Test
    void givenEmptyRedirectUris_whenUpdateConfig_thenThrowsRequired() {
        SsoClient client = SsoClient.create(APP_ID, "cid_abc", "hash_xyz");

        assertThatThrownBy(() -> client.updateConfig(List.of(), POST_LOGOUT_REDIRECT_URIS, SCOPES, GRANTS))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCodeMessage())
                .isEqualTo(AppRegistryMessage.SSO_REDIRECT_URI_REQUIRED);
    }

    @Test
    void givenNullRedirectUris_whenUpdateConfig_thenThrowsRequired() {
        SsoClient client = SsoClient.create(APP_ID, "cid_abc", "hash_xyz");

        assertThatThrownBy(() -> client.updateConfig(null, POST_LOGOUT_REDIRECT_URIS, SCOPES, GRANTS))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCodeMessage())
                .isEqualTo(AppRegistryMessage.SSO_REDIRECT_URI_REQUIRED);
    }

    @Test
    void givenEmptyPostLogoutRedirectUris_whenUpdateConfig_thenThrowsRequired() {
        // 与 redirect_uris 同款：登出回跳白名单至少一个（ADR-0005，不变式迁移到配置接口）
        SsoClient client = SsoClient.create(APP_ID, "cid_abc", "hash_xyz");

        assertThatThrownBy(() -> client.updateConfig(REDIRECT_URIS, List.of(), SCOPES, GRANTS))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCodeMessage())
                .isEqualTo(AppRegistryMessage.SSO_POST_LOGOUT_REDIRECT_URI_REQUIRED);
    }

    @Test
    void givenNullPostLogoutRedirectUris_whenUpdateConfig_thenThrowsRequired() {
        SsoClient client = SsoClient.create(APP_ID, "cid_abc", "hash_xyz");

        assertThatThrownBy(() -> client.updateConfig(REDIRECT_URIS, null, SCOPES, GRANTS))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCodeMessage())
                .isEqualTo(AppRegistryMessage.SSO_POST_LOGOUT_REDIRECT_URI_REQUIRED);
    }

    // ===== enable / disable（仅状态，不变）=====

    @Test
    void givenActiveClient_whenDisable_thenDisabled() {
        SsoClient client = SsoClient.create(APP_ID, "cid", "hash");

        client.disable();

        assertThat(client.getStatus()).isEqualTo(SsoClientStatus.DISABLED);
    }

    @Test
    void givenDisabledClient_whenDisable_thenThrowsAlreadyDisabled() {
        SsoClient client = SsoClient.create(APP_ID, "cid", "hash");
        client.disable();

        assertThatThrownBy(client::disable)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCodeMessage())
                .isEqualTo(AppRegistryMessage.SSO_CLIENT_ALREADY_DISABLED);
    }

    @Test
    void givenDisabledClient_whenEnable_thenActive() {
        SsoClient client = SsoClient.create(APP_ID, "cid", "hash");
        client.disable();

        client.enable();

        assertThat(client.getStatus()).isEqualTo(SsoClientStatus.ACTIVE);
    }

    @Test
    void givenActiveClient_whenEnable_thenThrowsAlreadyEnabled() {
        SsoClient client = SsoClient.create(APP_ID, "cid", "hash");

        assertThatThrownBy(client::enable)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCodeMessage())
                .isEqualTo(AppRegistryMessage.SSO_CLIENT_ALREADY_ENABLED);
    }
}
