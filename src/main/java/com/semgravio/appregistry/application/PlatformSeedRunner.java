package com.semgravio.appregistry.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 启动时执行平台预置应用 seed——确保 admin-console 存在且签名凭证可用。
 *
 * <p>薄调用者，仅委托 {@link PlatformSeedAppService#seed()}。seed 逻辑幂等，多次启动安全。
 * 测试环境跳过（测试自行调用 seed 服务）。</p>
 *
 * @since 0.1.0
 */
@Component
@Profile("!test")
public class PlatformSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformSeedRunner.class);

    private final PlatformSeedAppService seedService;

    public PlatformSeedRunner(PlatformSeedAppService seedService) {
        this.seedService = seedService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Running platform seed...");
        seedService.seed();
        log.info("Platform seed complete");
    }
}
