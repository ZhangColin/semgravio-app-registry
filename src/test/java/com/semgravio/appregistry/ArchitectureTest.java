package com.semgravio.appregistry;

import com.cartisan.test.archunit.CartisanArchRules;
import com.tngtech.archunit.junit.AnalyzeClasses;

/**
 * 架构守护——继承 {@link CartisanArchRules} 获得 cartisan-boot 全量 ArchUnit 规则
 * （分层依赖方向、命名规范、编码规范、禁用项）。
 *
 * <p>扫描 {@code com.semgravio.appregistry} 全包。随各 ticket 落码，本测试持续守护
 * 六边形架构边界：domain 不碰 Spring/infra、controller 只依赖应用层、应用服务不经 DB、
 * 聚合根实现 AggregateRoot、domain 枚举实现 BaseEnum、MapStruct Mapper 继承 DomainMapper。
 *
 * @since 0.1.0
 */
@AnalyzeClasses(packages = "com.semgravio.appregistry")
public class ArchitectureTest extends CartisanArchRules {
}
