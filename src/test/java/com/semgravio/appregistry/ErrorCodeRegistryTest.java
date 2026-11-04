package com.semgravio.appregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.web.doc.CodeMessageRegistry;
import com.cartisan.web.doc.ErrorCodes;

/**
 * 错误码注册表守护测试：应用全部 CodeMessage 枚举经 registry 扫描不冲突 +
 * {@code @ErrorCodes} 引用的串全部可解析（#25）。
 *
 * <p>镜像 {@code CartisanWebAutoConfiguration#codeMessageRegistry} 的装配调用
 * （缺省扫描应用主包）。同 code 串映射到不同常量会在启动期由
 * {@code CodeMessageRegistry.register} 直接失败——本测试把该失败前移到单测：
 * 任何上下文再引入重复错误码串，这里秒级变红，不用等应用启动炸。</p>
 *
 * <p>{@code @ErrorCodes} 侧镜像启动期 {@code ErrorCodesValidator}：controller 声明了
 * registry 解析不到的 code 串（typo / 未注册）应用启动即失败——同样前移到单测。</p>
 */
class ErrorCodeRegistryTest {

    /** 扫描根包——镜像 CartisanWebAutoConfiguration 缺省装配（应用主包）。 */
    private static final String BASE_PACKAGE = "com.semgravio.appregistry";

    @Test
    void given_allCodeMessageEnums_when_scanRegistry_then_noConflict() {
        assertThatCode(() -> CodeMessageRegistry.scan(BASE_PACKAGE))
            .doesNotThrowAnyException();
    }

    @Test
    void given_controllerErrorCodes_when_resolveInRegistry_then_allResolvable() throws Exception {
        CodeMessageRegistry registry = CodeMessageRegistry.scan(BASE_PACKAGE);
        List<String> unresolved = new ArrayList<>();
        for (Class<?> controller : scanRestControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                ErrorCodes errorCodes = method.getAnnotation(ErrorCodes.class);
                if (errorCodes == null) {
                    continue;
                }
                for (String code : errorCodes.value()) {
                    try {
                        registry.require(code);
                    } catch (Exception ex) {
                        unresolved.add(controller.getSimpleName() + "#" + method.getName() + ": " + code);
                    }
                }
            }
        }
        assertThat(unresolved)
            .as("controller @ErrorCodes 声明的串必须能在 registry 解析（镜像启动期 ErrorCodesValidator）")
            .isEmpty();
    }

    /** 扫应用主包下的 @RestController（含子包）——新上下文 controller 自动纳入守护。 */
    private static List<Class<?>> scanRestControllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> controllers = new ArrayList<>();
        for (var beanDefinition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            controllers.add(Class.forName(beanDefinition.getBeanClassName()));
        }
        return controllers;
    }
}
