package com.semgravio.appregistry.domain.app.aggregate;

import com.semgravio.appregistry.domain.app.enums.RegisteredAppStatus;
import com.semgravio.appregistry.domain.error.AppRegistryMessage;
import com.cartisan.core.exception.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RegisteredApp} 聚合根领域逻辑单元测试。
 *
 * @since 0.1.0
 */
class RegisteredAppTest {

    private static final String VALID_APP_CODE = "payment-service";

    @Test
    void givenValidInput_whenCreate_thenActiveWithIdAndFields() {
        RegisteredApp app = RegisteredApp.create(VALID_APP_CODE, "支付服务", "desc");

        assertThat(app.getId()).isNotNull();
        assertThat(app.getAppCode()).isEqualTo(VALID_APP_CODE);
        assertThat(app.getName()).isEqualTo("支付服务");
        assertThat(app.getDescription()).isEqualTo("desc");
        assertThat(app.getStatus()).isEqualTo(RegisteredAppStatus.ACTIVE);
    }

    @Test
    void givenNullDescription_whenCreate_thenAllowed() {
        RegisteredApp app = RegisteredApp.create(VALID_APP_CODE, "支付服务", null);

        assertThat(app.getDescription()).isNull();
        assertThat(app.getStatus()).isEqualTo(RegisteredAppStatus.ACTIVE);
    }

    @Test
    void givenInvalidAppCode_whenCreate_thenThrowsAppCodeInvalid() {
        assertThatThrownBy(() -> RegisteredApp.create("Bad_Code", "名", null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCodeMessage())
                .isEqualTo(AppRegistryMessage.APP_CODE_INVALID);
    }

    @Test
    void givenAppCodeTooShort_whenCreate_thenThrowsAppCodeInvalid() {
        // abc 仅 3 位，下界 4 位不满足
        assertThatThrownBy(() -> RegisteredApp.create("abc", "名", null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCodeMessage())
                .isEqualTo(AppRegistryMessage.APP_CODE_INVALID);
    }

    @Test
    void givenAppCodeStartsWithHyphen_whenCreate_thenThrowsAppCodeInvalid() {
        assertThatThrownBy(() -> RegisteredApp.create("-payment", "名", null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCodeMessage())
                .isEqualTo(AppRegistryMessage.APP_CODE_INVALID);
    }

    @Test
    void givenBlankName_whenCreate_thenThrowsNameRequired() {
        assertThatThrownBy(() -> RegisteredApp.create(VALID_APP_CODE, "  ", null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCodeMessage())
                .isEqualTo(AppRegistryMessage.APP_NAME_REQUIRED);
    }

    @Test
    void givenActiveApp_whenDisable_thenDisabled() {
        RegisteredApp app = RegisteredApp.create(VALID_APP_CODE, "名", null);

        app.disable();

        assertThat(app.getStatus()).isEqualTo(RegisteredAppStatus.DISABLED);
    }

    @Test
    void givenDisabledApp_whenDisable_thenThrowsAlreadyDisabled() {
        RegisteredApp app = RegisteredApp.create(VALID_APP_CODE, "名", null);
        app.disable();

        assertThatThrownBy(app::disable)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCodeMessage())
                .isEqualTo(AppRegistryMessage.APP_ALREADY_DISABLED);
    }

    @Test
    void givenDisabledApp_whenEnable_thenActive() {
        RegisteredApp app = RegisteredApp.create(VALID_APP_CODE, "名", null);
        app.disable();

        app.enable();

        assertThat(app.getStatus()).isEqualTo(RegisteredAppStatus.ACTIVE);
    }

    @Test
    void givenActiveApp_whenEnable_thenThrowsAlreadyEnabled() {
        RegisteredApp app = RegisteredApp.create(VALID_APP_CODE, "名", null);

        assertThatThrownBy(app::enable)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCodeMessage())
                .isEqualTo(AppRegistryMessage.APP_ALREADY_ENABLED);
    }

    // ---- update ----

    @Test
    void givenValidInput_whenUpdate_thenNameAndDescriptionUpdated() {
        RegisteredApp app = RegisteredApp.create(VALID_APP_CODE, "旧名", "旧描述");

        app.update("新名", "新描述");

        assertThat(app.getName()).isEqualTo("新名");
        assertThat(app.getDescription()).isEqualTo("新描述");
        assertThat(app.getAppCode()).isEqualTo(VALID_APP_CODE);
        assertThat(app.getStatus()).isEqualTo(RegisteredAppStatus.ACTIVE);
    }

    @Test
    void givenNullDescription_whenUpdate_thenAllowed() {
        RegisteredApp app = RegisteredApp.create(VALID_APP_CODE, "旧名", "旧描述");

        app.update("新名", null);

        assertThat(app.getName()).isEqualTo("新名");
        assertThat(app.getDescription()).isNull();
    }

    @Test
    void givenBlankName_whenUpdate_thenThrowsNameRequired() {
        RegisteredApp app = RegisteredApp.create(VALID_APP_CODE, "旧名", null);

        assertThatThrownBy(() -> app.update("  ", null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCodeMessage())
                .isEqualTo(AppRegistryMessage.APP_NAME_REQUIRED);
    }

    @Test
    void givenDisabledApp_whenUpdate_thenStillAllowed() {
        RegisteredApp app = RegisteredApp.create(VALID_APP_CODE, "旧名", null);
        app.disable();

        app.update("新名", "新描述");

        assertThat(app.getName()).isEqualTo("新名");
        assertThat(app.getStatus()).isEqualTo(RegisteredAppStatus.DISABLED);
    }
}
