# cartisan-boot 使用手册

> **版本**：v2.1 | **日期**：2026-04-12
> **更新内容**：
> - 新增 cartisan-openapi 模块文档（模块能力、API、使用示例、配置）
> - 新增 OpenAPI 服务间通信使用指南链接
> - 修正文档结尾版本号
>
> **定位**：框架使用指南（怎么用）
> **目标读者**：使用 cartisan-boot 框架的业务项目开发者

---

## 一、快速开始

### 1.1 依赖引入

在业务项目的 `pom.xml` 中引入 cartisan-boot BOM：

```xml
<!-- Maven -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.cartisan</groupId>
            <artifactId>cartisan-dependencies</artifactId>
            <version>0.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.cartisan</groupId>
        <artifactId>cartisan-core</artifactId>
    </dependency>
    <dependency>
        <groupId>com.cartisan</groupId>
        <artifactId>cartisan-web</artifactId>
    </dependency>
    <dependency>
        <groupId>com.cartisan</groupId>
        <artifactId>cartisan-data-jpa</artifactId>
    </dependency>
    <!-- 根据需要添加其他模块 -->
</dependencies>
```

### 1.2 编译器配置（重要）

#### ⚠️ 使用 cartisan-security 模块时需要启用预览特性

如果业务项目引入了 `cartisan-security` 模块（使用 `TenantContext`、`TenantContextFilter`），必须在 `pom.xml` 中启用 Java 预览特性：

```xml
<build>
    <plugins>
        <!-- 编译时启用预览特性 -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.13.0</version>
            <configuration>
                <source>21</source>
                <target>21</target>
                <compilerArgs>
                    <arg>--enable-preview</arg>
                </compilerArgs>
            </configuration>
        </plugin>

        <!-- 测试运行时启用预览特性 -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>3.5.2</version>
            <configuration>
                <argLine>--enable-preview --add-opens java.base/java.lang=ALL-UNNAMED</argLine>
            </configuration>
        </plugin>
    </plugins>
</build>
```

**原因**：`cartisan-security` 使用了 Java 21 的 `ScopedValue` 预览 API 实现多租户上下文，业务项目编译引用这些类时也需要启用预览特性。

**哪些模块需要此配置**：
- ✅ `cartisan-security`：需要（使用 ScopedValue）
- ❌ `cartisan-core`：不需要
- ❌ `cartisan-web`：不需要
- ❌ `cartisan-data-jpa`：不需要
- ❌ `cartisan-data-query`：不需要

**如果项目不使用多租户功能**：可以不引入 `cartisan-security`，也无需配置 `--enable-preview`。

### 1.3 自动配置

cartisan-boot 模块支持 Spring Boot 自动配置，引入依赖后自动启用，无需手动配置。

### 1.3 基础配置

在 `application.yml` 中添加基础配置（可选）：

```yaml
cartisan:
  web:
    enum-controller:
      enabled: true  # 启用默认枚举 Controller
```

---

## 二、模块能力清单

### 1.1 cartisan-core 模块

| 能力 | 说明 |
|------|------|
| **DDD 基础类型** | 聚合根、实体、值对象、应用事件、标识符 |
| **异常体系** | 统一错误码接口 + 业务异常层次 |
| **架构注解** | DDD 分层标记注解（限界上下文、聚合、端口、适配器） |
| **断言工具** | Design by Contract 风格的前置/后置条件断言 |
| **RedisKey 工具** | Redis Key 管理（前缀、过期时间） |

### 1.2 cartisan-test 模块

| 能力 | 说明 |
|------|------|
| **ArchUnit 规则（v1.2）** | 15 条规则：分层、命名、禁止、编码规范规则的自动验证 |
| **集成测试基类** | IntegrationTestBase（需手动启动测试环境） |
| **环境检查工具** | TestEnvironmentChecker 检查 PostgreSQL/Redis 是否可用 |
| **API 测试** | MockMvc 测试基类 + 断言辅助 |
| **Fixture 工具** | 随机数据生成器 + 对象构建器 |

**迁移策略（v1.2 新规则）**：

如果您是现有项目，新规则可能会导致测试失败。以下是迁移建议：

**1. 分阶段启用**：
- Week 1：在本地环境运行新规则，发现违规
- Week 2：修复关键违规，对历史代码添加 `@ArchIgnore` 豁免
- Week 3：在 CI 中启用新规则，强制执行

**2. 豁免机制**（用于历史代码）：

```java
@ArchIgnore(reason = "Legacy code, will be refactored in v2.0")
@ArchTest
static final ArchRule some_rule = ...;
```

**3. 选择性继承**：

```java
// 业务项目可以选择性继承规则
public class ArchitectureTest extends CartisanLayeringRules {
    // 不继承命名规范规则
    // 不继承编码规范规则
}
```

> **详细迁移指南**参见设计文档《cartisan-boot 文档重构设计方案》第七章。

#### 架构测试使用方法

**继承全部规则**

业务平台通过继承 `CartisanArchRules` 即可获得完整的架构守护：

```java
@AnalyzeClasses(packages = "com.yourcompany")
public class ArchitectureTest extends CartisanArchRules {
    // 完了。所有规则自动生效。
}
```

**规则列表**

当前框架提供 **15 条架构规则**：

**分层规则**（5 条）：
- 领域层不能依赖基础设施层
- 领域层不能依赖 Spring
- Controller 只能依赖应用层
- 应用服务不能直接操作数据库
- Controller 不应依赖聚合根

**命名规则**（5 条）：
- Controller 命名规范
- 应用服务命名规范
- 领域服务命名规范
- Repository 命名规范
- 外部 API Controller 版本号

**禁止规则**（3 条）：
- 禁止字段注入
- 禁止使用 java.util.Date
- 禁止金额字段使用浮点数

**编码规范规则**（2 条）：
- 领域层枚举必须实现 BaseEnum
- MapStruct Mapper 必须继承 DomainMapper

**自定义规则**

如需添加平台特有的规则，可以在测试类中追加 `@ArchTest` 字段：

```java
@AnalyzeClasses(packages = "com.yourcompany")
public class ArchitectureTest extends CartisanArchRules {

    @ArchTest
    static final ArchRule myCustomRule = classes()
        .that()
        .resideInAPackage("..mypackage..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("..mypackage..", "java..");
}
```

**选择性继承**

如只需部分规则，可只继承特定的规则类：

```java
@AnalyzeClasses(packages = "com.yourcompany")
public class LayeringTest extends CartisanLayeringRules {
    // 只继承分层规则
}
```

或者不继承，直接在测试类中声明需要的规则字段。

### 1.3 cartisan-web 模块

| 能力 | 说明 |
|------|------|
| **统一响应体** | `ApiResponse<T>`、`PageResponse<T>`、`FieldError` |
| **全局异常处理** | `@ControllerAdvice` 自动捕获异常并转换为响应 |
| **请求上下文** | `RequestContext` 存储 requestId、clientIp（ThreadLocal） |
| **DomainMapper** | MapStruct 批量转换默认方法（List/Set） |
| **TreeNode** | 树结构数据支持（前端树组件） |
| **防重提交** | `@PreventResubmit` 注解（基于 Redis） |
| **请求日志** | `RequestLogFilter` 记录请求信息 |
| **MDC 集成** | requestId 自动放入 MDC |
| **Jackson 配置** | 全局序列化配置（Long→String、日期格式等） |
| **自动配置** | Spring Boot AutoConfiguration 零配置启用 |
| **枚举选项** | `EnumOption`、`EnumOptionUtils`、`EnumController` 支持前端获取枚举选项列表 |

**注意事项**：

| 规则 | 说明 |
|------|------|
| **WEB-001** | `@PreventResubmit` 需要 Redis 环境，无 Redis 时不生效 |
| **WEB-002** | `TreeNodeBuilder` 需要 ID 类型转换，使用 Function 映射 |
| **WEB-004** | `RequestLogFilter` 自动排除 swagger、druid、actuator 路径 |
| **WEB-005** | MDC requestId 自动清理，请求结束无需手动处理 |

> **更多规则和详细说明**参见 PITFALLS.md。

##### @Condition 注解详细说明

`@Condition` 注解用于标注查询 DTO 字段，指定查询条件类型，配合 JPA Specification 使用。

**ConditionType 枚举（11 种查询类型）**：

| 类型 | SQL 示例 | 说明 |
|------|----------|------|
| `EQUAL` | `WHERE field = value` | 相等查询（默认） |
| `NOT_EQUAL` | `WHERE field != value` | 不相等查询 |
| `GREATER_EQUAL` | `WHERE field >= value` | 大于等于 |
| `LESS_EQUAL` | `WHERE field <= value` | 小于等于 |
| `INNER_LIKE` | `WHERE field LIKE '%value%'` | 中间模糊查询 |
| `LEFT_LIKE` | `WHERE field LIKE '%value'` | 左模糊查询 |
| `RIGHT_LIKE` | `WHERE field LIKE 'value%'` | 右模糊查询 |
| `IN` | `WHERE field IN (value1, value2, ...)` | IN 查询 |
| `BETWEEN` | `WHERE field BETWEEN value1 AND value2` | 区间查询 |

**使用示例**：

```java
// 定义查询 DTO
public record ProductQuery(
    @Condition(type = ConditionType.INNER_LIKE) String name,
    @Condition(propName = "stock", type = ConditionType.GREATER_EQUAL) Integer minStock
) {}

// Repository 使用
public interface ProductRepository extends BaseRepository<Product, Long> {
    default List<Product> findByCondition(ProductQuery query) {
        return findAll(ConditionSpecifications.fromAnnotation(query));
    }
}
```

> **注意**：null 和空字符串自动跳过，不会生成查询条件。

### 1.4 cartisan-data-jpa 模块

| 能力 | 说明 |
|------|------|
| **BaseRepository** | 约束 T 必须是 `AggregateRoot<?>`，继承 JPA + Specification |
| **事件发布** | 应用服务通过 ApplicationEventPublisher 发布应用事件 |
| **审计支持** | `@CreatedDate`、`@LastModifiedDate`、`@CreatedBy`、`@LastModifiedBy` |
| **软删除** | `@SQLRestriction` 自动过滤已删除记录 |
| **分布式 ID** | TSID 生成器（42 位时间戳 + 22 位随机数） |
| **@Condition 注解** | 简化 JPA Specification 查询（11 种条件类型） |
| **Druid 集成** | 支持 Druid 数据源，提供 SQL 监控、慢 SQL 记录、防火墙功能 |
| **枚举增强** | `BaseEnum` + 内部 `JpaConverter` 实现 Enum ↔ Integer 自动转换 |

##### 枚举增强详细说明

**BaseEnum 接口**：业务枚举必须实现 `BaseEnum<T>` 接口，提供 `code`/`name` 映射。

**内部 JpaConverter 模式**：枚举内部声明静态内部类 `JpaConverter`，使用 `@Converter(autoApply = true)` 注解，Hibernate 自动应用。

**自动转换**：
- 数据库 → Java：Integer code 自动转换为枚举
- Java → 数据库：枚举自动转换为 Integer code
- JSON → Java：Integer code 自动反序列化为枚举
- Java → JSON：枚举自动序列化为 Integer code

**实体字段零注解**：实体类中的枚举字段无需任何 JPA 注解，`@Converter(autoApply = true)` 自动应用。

> **📖 详细使用指南**：[BaseEnum枚举持久化使用指南.md](./BaseEnum枚举持久化使用指南.md)

**注意事项**：

| 规则 | 说明 |
|------|------|
| **DATA-001** | JPA `save()` 后必须用原始 entity 发布事件，而非返回值 |
| **DATA-002** | Repository 不是 Spring Bean，依赖注入用静态持有者模式 |
| **DATA-003** | `@MappedSuperclass` 需要添加 `@EntityListeners(AuditingEntityListener.class)` |
| **DATA-004** | `@SQLRestriction` 在 `@MappedSuperclass` 上可能无法正确继承，子类重复声明才保险 |
| **DATA-005** | JPQL `@Query` 查询不受 `@SQLRestriction` 影响，需手动添加软删除条件 |
| **DATA-006** | 自动软删除通过 `instanceof` 判断类型 |
| **DATA-007** | 枚举持久化使用内部 `JpaConverter`，实体字段零注解 |
| **DATA-008** | BaseEnum Jackson 序列化为 code，反序列化通过 `ContextualDeserializer` |

> **更多规则和详细说明**参见 PITFALLS.md。

### 1.5 cartisan-event 模块

| 能力 | 说明 |
|------|------|
| **事件发布器** | `ApplicationEventPublisher` 接口 + Spring/RabbitMQ 实现 |
| **事务监听** | 支持 `@TransactionalEventListener(phase=AFTER_COMMIT)` |
| **自动配置** | Spring Boot AutoConfiguration 零配置启用 |

### 1.6 cartisan-security 模块

| 能力 | 说明 |
|------|------|
| **权限注解** | `@RequireAuth`、`@RequireRole`、`@RequirePermission` |
| **权限扫描** | `PermissionScanner` 扫描代码中的权限注解，自动采集权限定义 |
| **MVC 拦截器** | `SecurityInterceptor` 处理鉴权逻辑 |
| **异常处理** | `SecurityExceptionHandler` 处理 Sa-Token 异常（401/403） |
| **安全上下文** | `SecurityContext` 获取当前用户信息 |
| **多租户上下文** | `TenantContext` 获取租户 ID（Header > Session 优先级） |
| **租户过滤器** | `TenantContextFilter` 解析租户 ID，兼容 Virtual Threads |
| **认证服务** | `AuthenticationService` 接口 + Sa-Token 实现 |
| **@CurrentUser 注解** | Controller 方法参数直接注入当前用户 ID |
| **自动配置** | Spring Boot AutoConfiguration 零配置启用 |

**注意事项**：

| 规则 | 说明 |
|------|------|
| **SECURITY-001** | Sa-Token 包路径是 `cn.dev33.satoken`，不是 `cn.dev33.sa-token` |
| **SECURITY-002** | Sa-Token Session 类是 `SaSession`，不是 `Session` |
| **SECURITY-003** | TenantContext 使用 `ScopedValue`，先 `isBound()` 再 `get()` |
| **SECURITY-004** | MockMvc 集成测试需要测试专用 Controller |
| **SECURITY-005** | `@Component` Bean 名称需显式指定避免冲突 |
| **SECURITY-006** | `@CurrentUser Long` 未登录时调用 `StpUtil.checkLogin()` 抛异常 |

> **更多规则和详细说明**参见 PITFALLS.md。

### 1.7 cartisan-data-query 模块

| 能力 | 说明 |
|------|------|
| **jOOQ 自动配置** | `DSLContext` Bean 自动配置（PostgreSQL 方言、SQL 日志） |
| **多租户查询** | `JooqTenantSupport.eqTenantId()` 租户过滤条件生成 |
| **自动配置** | Spring Boot AutoConfiguration 零配置启用 |

**注意事项**：

| 规则 | 说明 |
|------|------|
| **QUERY-001** | `generateJooq` 任务必须依赖 `flywayMigrate` |
| **QUERY-002** | jOOQ 代码生成目录为 `build/generated/jooq` |
| **QUERY-003** | `JooqTenantSupport` 需要 `cartisan-security` 可选依赖 |
| **QUERY-004** | jOOQ 版本由 `cartisan-dependencies` BOM 管理 |
| **QUERY-005** | `@Condition` 注解 BigDecimal 类型有类型推断限制 |
| **QUERY-006** | `@Condition` 的 `blurry` 属性使用 OR 连接多字段 |
| **QUERY-007** | `@Condition` 注解 null 和空字符串自动跳过 |

> **更多规则和详细说明**参见 PITFALLS.md。

### 1.8 cartisan-ai 模块

| 能力 | 说明 |
|------|------|
| **统一对话模型** | `ChatMessage`、`ChatRequest`、`ChatResponse`、`TokenUsage`、`ChatStreamEvent` |
| **Provider SPI** | `ModelProvider` 接口，支持同步调用和流式调用 |
| **Provider Registry** | 按提供商 ID 或模型名称查找 Provider |
| **OpenAI Provider** | 支持 OpenAI API（同步 + SSE 流式） |
| **DeepSeek Provider** | 兼容 OpenAI 协议，支持 DeepSeek API |
| **Anthropic Provider** | 支持 Anthropic Claude API（独立协议） |
| **SSE 流式工具** | `SseHelper` 将 `Flux<ChatStreamEvent>` 转换为 `SseEmitter` |
| **ModelUsageListener** | Token 使用量监听扩展点 |
| **自动配置** | Spring Boot AutoConfiguration 零配置启用 |

**注意事项**：

| 规则 | 说明 |
|------|------|
| **AI-001** | `ChatRequest.withStream()` 创建副本，避免修改原请求 |
| **AI-002** | `ModelProviderRegistry` 的 Listener 异常不中断流程 |
| **AI-003** | `SseHelper` 的 `usageCallback` 仅在流完成且有 usage 时触发 |
| **AI-004** | Provider 条件装配基于 `api-key` 配置，无 key 则不创建 Bean |

> **更多规则和详细说明**参见 PITFALLS.md。

### 1.9 cartisan-openapi 模块

| 能力 | 说明 |
|------|------|
| **签名客户端** | `OpenApiClient` 自动签名 + 上下文传递 |
| **验签 Filter** | `SignatureVerificationFilter` 验签 + 写入 caller 信息到 RequestContext |
| **权限 Interceptor** | `SignatureVerificationInterceptor` 注解解析 + 权限检查 |
| **API Key 管理** | `ApiKeyProvider` + Caffeine 缓存 |
| **请求体缓存** | `CachingRequestBodyFilter` 缓存 body + 大小限制 |
| **防重放** | nonce + timestamp 校验 |
| **自动配置** | Spring Boot AutoConfiguration 零配置启用 |

**注意事项**：

| 规则 | 说明 |
|------|------|
| **OPENAPI-001** | `@NoSignature` 仅对有 `@RequireSignature` 的类或方法有意义 |
| **OPENAPI-002** | `OpenApiClient` 使用同步阻塞调用，高并发场景考虑异步改造 |
| **OPENAPI-003** | `NonceRepository` 需要业务项目提供 Redis 实现 |
| **OPENAPI-004** | `RemoteApiKeyProvider` 缓存是本地 Caffeine，多实例有短暂不一致（30 分钟） |
| **OPENAPI-005** | GET 请求签名包含 query 参数，URL 变更会影响签名验证 |

> **📖 详细使用指南**：[OpenAPI服务间通信使用指南.md](./OpenAPI服务间通信使用指南.md)

---

## 三、核心概念和 API

### 2.1 DDD 基础类型（com.cartisan.core.domain）

| 接口/类 | 方法 | 说明 |
|---------|------|------|
| `AggregateRoot` | - | 聚合根标记接口 |
| `AggregateRoot<T, ID>` | `getId()` | 获取聚合根 ID |
| `DomainEntity<T, ID>` | `getId()` | 获取实体 ID |
| | `sameIdentityAs(other)` | 判断是否为同一实体 |
| `ValueObject<T>` | `sameValueAs(other)` | 判断值是否相等 |
| `Identity<T>` | `value()` | 获取标识符值 |
| `ApplicationEvent` | `eventId()` | 事件 ID（UUID） |
| | `eventType()` | 事件类型标识 |
| | `occurredAt()` | 发生时间 |

### 2.1.1 BaseEnum 接口（com.cartisan.core.domain）

| 接口/方法 | 说明 |
|-----------|------|
| `BaseEnum<T>` | 业务枚举基础接口，包含 code/name 映射 |
| `getCode()` | 获取枚举的整数值（存储到数据库） |
| `getName()` | 获取枚举的显示名称 |
| `parseByCode(Class, Integer)` | 根据 code 查找枚举（找不到返回 null） |
| `requireByCode(Class, Integer)` | 根据 code 查找枚举（找不到抛异常） |

**📖 详细使用指南**：[BaseEnum枚举持久化使用指南.md](./BaseEnum枚举持久化使用指南.md)

包含内容：
- ✅ 为什么需要 BaseEnum（避免 ordinal 存储问题）
- ✅ 快速开始（完整示例）
- ✅ JPA 持久化配置（内部 JpaConverter 模式）
- ✅ 重要注意事项（命名冲突、code=0 边缘情况）
- ✅ 迁移指南（从旧模式迁移）
- ✅ 最佳实践和常见问题

### 2.1.2 BaseEnumConverter（com.cartisan.data.jpa.converter）

| 类 | 说明 |
|----|------|
| `BaseEnumConverter<E>` | BaseEnum JPA 转换器抽象基类，所有枚举 Converter 继承此类 |

**关键改进**（2026-04-07）：
- ✅ 从 `UniversalEnumConverter` 重命名为 `BaseEnumConverter`
- ✅ 改为抽象类，使用内部 `JpaConverter` 模式
- ✅ Hibernate 自动扫描并应用 `@Converter(autoApply = true)`
- ✅ 实体字段无需任何注解

### 2.1.3 BaseEnum 序列化支持（com.cartisan.web.config）

| 类 | 说明 |
|----|------|
| `BaseEnumSerializer` | Jackson 序列化器：BaseEnum → Integer code |
| `BaseEnumDeserializer` | Jackson 反序列化器：Integer → BaseEnum（使用 ContextualDeserializer） |
| `BaseEnumConverter` | Spring MVC Converter：String(Integer code) → BaseEnum，支持 `@RequestParam`、`@PathVariable` |

**BaseEnum 参数绑定**：

业务枚举实现 `BaseEnum` 接口后，**零配置**即可在 Controller 中直接使用枚举类型：

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    // GET /api/users?status=1  → status 自动转换为 UserStatus.ACTIVE
    @GetMapping
    public List<UserDTO> getUsers(@RequestParam UserStatus status) {
        return userService.getUsersByStatus(status);
    }

    // PUT /api/users/123?status=0  → status 自动转换为 UserStatus.DISABLED
    @PutMapping("/{id}")
    public void updateUserStatus(
            @PathVariable Long id,
            @RequestParam UserStatus status) {
        userService.updateStatus(id, status);
    }
}
```

**特性**：
- ✅ **零配置**：引入 `cartisan-web` 依赖即生效
- ✅ **只支持 Integer code**：如 `?status=1`，不支持 name 格式（如 `?status=ACTIVE`）
- ✅ **null 处理**：null/空字符串返回 null，由 `@NotNull` 等校验处理
- ✅ **错误处理**：无效 code 返回 400 Bad Request（而非 404）

**设计取舍**：
1. **只支持 code 不支持 name**：API 传输应该是稳定的 code 值，而不是可能变化的 name
2. **null 返回 null**：Converter 负责类型转换，校验由业务层注解处理（职责分离）
3. **自动注册**：通过 `CartisanWebAutoConfiguration` 实现 `WebMvcConfigurer` 自动注册

### 2.2 异常体系（com.cartisan.core.exception）

| 类 | 说明 |
|----|------|
| `CodeMessage` | 错误码接口：`code()`, `message()`, `httpStatus()` |
| `BaseCodeMessage` | HTTP 规范错误码枚举（11 个）+ 通用业务错误码（4 个） |
| `CartisanException` | 异常基类，支持参数化消息 |
| `DomainException` | 领域层异常（业务规则违反） |
| `ApplicationException` | 应用层异常（用例/流程问题） |

### 2.3 架构注解（com.cartisan.core.stereotype）

| 注解 | 目标 | 用途 |
|------|------|------|
| `@BoundedContext` | PACKAGE | 标注限界上下文 |
| `@Aggregate` | TYPE | 标注聚合根 |
| `@DomainService` | TYPE | 标注领域服务 |
| `@Port(PortType)` | TYPE | 标注端口接口 |
| `@Adapter(PortType)` | TYPE | 标注适配器实现 |

**PortType 类型**：

| 类型 | 用途 | 示例 |
|------|------|------|
| `REPOSITORY` | 仓储端口：聚合根持久化 | `OrderRepository` |
| `CLIENT` | 客户端端口：调用外部服务 | `PasswordEncoderPort`、`SmsSenderPort` |
| `PUBLISHER` | 发布者端口：发布应用事件 | `ApplicationEventPublisher` |

### 2.3.1 Repository 模式 vs Service Port 模式

| 特性 | Repository 模式 | Service Port 模式 |
|------|-----------------|-------------------|
| **用途** | 数据持久化 | 外部服务调用 |
| **PortType** | `PortType.REPOSITORY` | `PortType.CLIENT` |
| **操作** | CRUD 操作 | 调用/发送/查询等 |
| **返回值** | 聚合根/值对象 | 响应 DTO 或基础类型 |
| **示例** | `AdminUserRepository` | `SmsSenderPort`、`PaymentGatewayPort` |

**Service Port 适用场景**：
- **跨限界上下文调用**：如订单上下文调用库存上下文
- **外部 API 调用**：如短信服务、支付网关、OSS 存储
- **中间件交互**：如消息队列、缓存、搜索引擎

**不适合 Service Port 的场景**：
- 纯工具类（如 BCryptPasswordEncoder、UUID 生成器）- 直接注入使用
- 领域业务逻辑 - 应在聚合根或领域服务中
- 应用服务编排 - 应在 Application Service 中

### 2.4 断言工具（com.cartisan.core.util.Assertions）

| 方法 | 异常类型 | 用途 |
|------|---------|------|
| `require(condition, codeMessage, args)` | `DomainException` | 前置条件断言 |
| `ensure(condition, message)` | `IllegalStateException` | 后置条件断言 |
| `requirePresent(optional)` | `DomainException` | Optional 存在性断言（快捷版） |
| `requirePresent(optional, codeMessage)` | `DomainException` | Optional 存在性断言（完整版） |

### 2.5 ArchUnit 规则（com.cartisan.test.archunit）

| 类 | 规则数 | 说明 |
|----|--------|------|
| `CartisanLayeringRules` | 5 | DDD 分层规则（新增：Controller 不应依赖聚合根） |
| `CartisanNamingRules` | 5 | 命名规范规则（新增：外部 API Controller 版本号） |
| `CartisanProhibitionRules` | 3 | 禁止规则 |
| `CartisanCodingStandardsRules` | 1 | 编码规范规则（新增：领域层枚举实现 BaseEnum） |
| `CartisanArchRules` | 14 | 聚合全部规则 |

### 2.6 测试基类（com.cartisan.test.base）

| 类 | 继承关系 | 提供能力 |
|----|----------|----------|
| `IntegrationTestBase` | - | JdbcTemplate + 环境配置 |
| `ApiTestBase` | - | MockMvc |

### 2.7 环境检查工具（com.cartisan.test.base）

| 类 | 方法 | 说明 |
|----|------|------|
| `TestEnvironmentChecker` | `checkPostgreSQL(...)` | 检查 PostgreSQL 连接 |
| | `checkRedis(...)` | 检查 Redis 连接 |
| | `checkFromEnvironment()` | 从环境变量读取配置并检查 |
| `TestEnvironmentCheckerMain` | `main(...)` | 可直接运行的环境检查工具 |

### 2.8 Fixture 工具（com.cartisan.test.fixture）

| 类 | 方法示例 | 说明 |
|----|----------|------|
| `FixtureStrings` | `randomString()`, `randomEmail()` | 字符串随机生成 |
| `FixtureNumbers` | `randomInt()`, `randomAmount()` | 数字/金额随机生成 |
| `FixtureDates` | `pastDays(7)`, `futureDays(3)` | 日期随机生成 |
| `FixtureBuilder<T>` | `of(clazz).with(name, value).build()` | 对象构建器 |

### 2.9 Web 响应体（com.cartisan.web.response）

| 类/Record | 方法/字段 | 说明 |
|-----------|----------|------|
| `ApiResponse<T>` | `code`, `message`, `data`, `requestId`, `errors` | 统一响应字段 |
| | `ok(T data)` | 成功响应（带数据） |
| | `ok()` | 成功响应（无数据） |
| | `error(CodeMessage)` | 错误响应（枚举） |
| | `error(CodeMessage, Object...)` | 错误响应（参数化） |
| | `error(int, String)` | 错误响应（自定义） |
| | `validationError(List<FieldError>)` | 校验失败响应 |
| `PageResponse<T>` | `items`, `total`, `page`, `size` | 分页响应字段 |
| `FieldError` | `field`, `message`, `errorCode` | 字段级错误 |

### 2.10 请求上下文（com.cartisan.web.context）

| 类 | 方法 | 说明 |
|----|------|------|
| `RequestContext` | `getRequestId()` | 获取请求追踪 ID（可能为 null） |
| | `getClientIp()` | 获取客户端 IP（可能为 null） |
| `RequestContextFilter` | - | 自动初始化 RequestContext（@Component） |

### 2.11 BaseRepository（com.cartisan.data.jpa.repository）

| 接口 | 约束 | 说明 |
|----|------|------|
| `BaseRepository<T, ID>` | `T extends AggregateRoot<?>` | 继承 JpaRepository + JpaSpecificationExecutor |
| | `ID extends Serializable` | ID 类型约束 |
| `BaseRepository` | `save()` | JPA 保存聚合根 |

### 2.12 审计与软删除（com.cartisan.data.jpa.domain）

| 类/接口 | 字段/注解/方法 | 说明 |
|----|----------|------|
| `Auditable` | `@CreatedDate createdAt` | 创建时间（LocalDateTime，自动填充） |
| | `@LastModifiedDate lastModifiedDate` | 修改时间（LocalDateTime，自动更新） |
| | `@CreatedBy createdBy` | 创建人ID（Long，需 AuditorAware） |
| | `@LastModifiedBy lastModifiedBy` | 修改人ID（Long，需 AuditorAware） |
| `AuditableSoftDeletable` | 继承 `Auditable`，实现 `SoftDeletable` | 可审计且可软删除实体基类 |
| | `boolean deleted` | 软删除标记 |
| | `@SQLRestriction("deleted = false")` | 查询自动过滤 |
| | `markAsDeleted()` | 标记为已删除（领域方法），供 Repository.delete() 调用 |
| `SoftDeletable` | `markAsDeleted()` | 软删除接口方法 |
| | `getDeleted()` | 获取软删除标记值 |

**自动软删除支持**：

| 方法 | 行为 |
|------|------|
| `BaseRepositoryImpl.delete(T)` | 实现 `SoftDeletable` 接口的实体调用 `markAsDeleted()` + `save()`，其他实体物理删除 |
| `BaseRepositoryImpl.deleteById(ID)` | 先查找实体，再调用 `delete()` |
| `BaseRepositoryImpl.deleteAll(Iterable)` | 混合处理：软删除实体 UPDATE，其他 DELETE |
| `BaseRepositoryImpl.deleteAll()` | 软删除实体批量 UPDATE，其他物理删除 |
| `BaseRepositoryImpl.deleteAllById()` | 批量按 ID 删除 |

**重要设计取舍**：
- 软删除复用 `save()` 的事件发布逻辑
- 通过 `JpaRepositoryFactoryEntryCustomizer` 全局配置，业务端无需手动指定 `repositoryBaseClass`
- JPQL `@Query` 查询不受 `@SQLRestriction` 影响，需手动添加软删除条件（见 DATA-005）

### 2.13 TSID 生成器（com.cartisan.data.jpa.id）

| 类 | 方法 | 说明 |
|----|------|------|
| `TsidGenerator` | `generate()` | 生成时间排序的全局唯一 Long ID |
| | `toInstant(long tsid)` | 从 TSID 提取生成时间 |
| | `newInstance()` | 创建默认实例（ThreadLocalRandom） |
| | `withRandom(Random)` | 测试用：指定随机数源 |

### 2.14 应用事件发布器（com.cartisan.event）

| 接口/类 | 方法 | 说明 |
|---------|------|------|
| `ApplicationEventPublisher` | `publishApplicationEvent(ApplicationEvent)` | 发布应用事件 |
| `CompositeApplicationEventPublisher` | - | 复合发布器，支持多渠道发布 |
| `SpringApplicationEventPublisher` | - | Spring 事件发布实现 |
| `RabbitMQApplicationEventPublisher` | - | RabbitMQ 发布实现（按需） |

### 2.15 权限注解（com.cartisan.security.annotation）

| 注解 | 目标 | 说明 |
|------|------|------|
| `@RequireAuth` | TYPE/METHOD | 需要登录 |
| `@RequireRole` | TYPE/METHOD | 需要指定角色（OR 逻辑） |
| `@RequirePermission` | METHOD | 需要指定权限（单值，支持 name 和 scope 属性） |

**@RequirePermission 属性说明：**
- `value`: 权限 code，格式 `{context}:{module}:{action}`
- `name`: 权限显示名称（可选，空字符串时使用 code）
- `scope`: 权限作用域（可选，空字符串时转为 null）

### 2.16 SecurityContext（com.cartisan.security.context）

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `getCurrentUserId()` | `Long` / `null` | 获取当前用户 ID |
| `getCurrentUsername()` | `String` / `null` | 获取当前用户名（登录 ID） |
| `hasRole(String role)` | `boolean` | 判断是否拥有角色 |
| `hasPermission(String permission)` | `boolean` | 判断是否拥有权限 |
| `isAuthenticated()` | `boolean` | 判断是否已登录 |

### 2.17 TenantContext（com.cartisan.security.context）

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `getCurrentTenantId()` | `Long` / `null` | 获取当前租户 ID |
| `hasTenant()` | `boolean` | 判断是否有租户上下文 |
| `requireTenant()` | `Long` | 获取租户 ID，不存在抛异常 |

**存储机制**：使用 `ScopedValue`（Java 21+），兼容 Virtual Threads，作用域结束自动清理。

### 2.18 AuthenticationService（com.cartisan.security.authentication）

| 接口 | 方法 | 说明 |
|------|------|------|
| `AuthenticationService` | `login(Long loginId)` | 创建登录会话 |
| | `logout()` | 销毁当前会话 |
| | `getTokenInfo()` | 获取当前 Token 信息 |
| | `authenticate(username, password)` | 业务层扩展点（默认抛异常） |

### 2.19 TokenInfo（com.cartisan.security.authentication）

| 字段 | 类型 | 说明 |
|------|------|------|
| `token` | `String` | Token 值 |
| `loginId` | `Long` | 用户标识 |
| `expireTime` | `Instant` | 过期时间 |

### 2.20 异常处理器（com.cartisan.security.config）

| 异常类型 | HTTP 状态码 | 响应消息 |
|---------|------------|---------|
| `NotLoginException` | 401 UNAUTHORIZED | 未登录或登录已过期 |
| `NotRoleException` | 403 FORBIDDEN | 无权限访问 |
| `NotPermissionException` | 403 FORBIDDEN | 无权限访问 |

### 2.21 配置属性（com.cartisan.security.config.properties）

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `cartisan.security.interceptor.path-patterns` | `List<String>` | `["/**"]` | 拦截器生效路径 |
| `cartisan.security.interceptor.exclude-path-patterns` | `List<String>` | `["/error", "/actuator/**"]` | 排除路径 |

### 2.22 @CurrentUser 注解（com.cartisan.security.annotation）

| 注解/类 | 目标/方法 | 说明 |
|---------|----------|------|
| `@CurrentUser` | PARAMETER | Controller 方法参数注解，注入当前用户 ID |
| `CurrentUserMethodArgumentResolver` | `supportsParameter()` | 判断参数是否支持解析（有注解 + 类型为 Long 或 Optional&lt;Long&gt;） |
| | `resolveArgument()` | 从 SecurityContext 获取用户 ID 并注入 |

**支持的参数类型**：
- `@CurrentUser Long userId` — 必需登录，未登录抛 `NotLoginException`（401）
- `@CurrentUser Optional<Long> userId` — 可选登录，未登录返回 `Optional.empty()`

**执行时序**：Filter → Interceptor（@RequireAuth 检查）→ 参数解析（@CurrentUser）→ Controller

### 2.23 PermissionScanner（com.cartisan.security.permission）

| 接口/类 | 方法 | 说明 |
|---------|------|------|
| `PermissionScanner` | `scanAll()` → `List<Permission>` | 扫描全部权限 |
| | `scanByScope(String scope)` → `List<Permission>` | 按作用域扫描权限（null = 无作用域） |
| `Permission` | `code()` → `String` | 权限 code |
| | `name()` → `String` | 显示名称 |
| | `scope()` → `String` | 作用域（可能为 null） |
| `DefaultPermissionScanner` | - | 默认实现，自动注册为 Spring Bean |

**使用示例：**

```java
@Service
public class PermissionInitService {
    private final PermissionScanner permissionScanner;

    public void initPermissions() {
        // 扫描指定作用域
        List<Permission> adminPermissions = permissionScanner.scanByScope("admin");

        // 扫描全部权限
        List<Permission> allPermissions = permissionScanner.scanAll();

        // 同步到权限管理表
        permissionRepository.syncPermissions(allPermissions);
    }
}
```

### 2.24 DSLContext 自动配置（com.cartisan.data.query.config）

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `cartisan.data-query.jooq.sql-logging` | `boolean` | `false` | 是否启用 SQL 执行日志 |

**自动配置类**：`JooqAutoConfiguration`
- 条件：存在 `DataSource` 且无用户自定义 `DSLContext`
- 方言：固定为 `SQLDialect.POSTGRES`
- Bean：可被用户自定义配置覆盖

### 2.25 JooqTenantSupport（com.cartisan.data.query.support）

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `eqTenantId(TableField<?, Long>)` | `Condition` | 生成租户等值过滤条件 |

**行为**：
- 有租户上下文时：返回 `tenantIdField.eq(tenantId)`
- 无租户上下文时：返回 `DSL.noCondition()`（不添加过滤）

**依赖说明**：需要 `cartisan-security` 模块（可选依赖）

### 2.26 AI 对话模型（com.cartisan.ai.model）

| 类/Record | 字段/方法 | 说明 |
|----------|----------|------|
| `Role` | `SYSTEM / USER / ASSISTANT` | 消息角色枚举 |
| `ChatMessage` | `role()`, `content()` | 单条对话消息 |
| `ChatRequest` | `model`, `messages`, `temperature`, `maxTokens`, `stream` | 对话请求 |
| | `withStream(boolean)` | 创建流式/非流式请求副本 |
| `ChatResponse` | `content()`, `model()`, `usage()` | 对话响应 |
| `TokenUsage` | `promptTokens()`, `completionTokens()`, `totalTokens()` | Token 使用统计 |
| `ChatStreamEvent` | `delta()`, `finished()`, `usage()` | 流式事件 |

### 2.27 ModelProvider SPI（com.cartisan.ai.provider）

| 接口/类 | 方法 | 说明 |
|---------|------|------|
| `ModelProvider` | `id()` → `String` | 提供商标识（openai/anthropic/deepseek） |
| | `supportedModels()` → `List<String>` | 支持的模型列表 |
| | `chat(ChatRequest)` → `ChatResponse` | 同步调用 |
| | `chatStream(ChatRequest)` → `Flux<ChatStreamEvent>` | 流式调用 |
| `ModelProviderRegistry` | `getProvider(providerId)` → `ModelProvider` | 按 ID 查找 Provider |
| | `getProviderByModel(modelName)` → `ModelProvider` | 按模型名查找 Provider |
| | `listProviders()` → `List<ModelProvider>` | 列出所有 Provider |
| | `chat(providerId, request)` → `ChatResponse` | 通过 Registry 调用 |
| | `chatStream(providerId, request)` → `Flux<ChatStreamEvent>` | 通过 Registry 流式调用 |
| `ModelUsageListener` | `onUsage(providerId, model, usage)` | Token 使用监听器（扩展点） |

### 2.28 SSE 流式工具（com.cartisan.ai.sse）

| 类 | 方法 | 说明 |
|----|------|------|
| `SseHelper` | `toSse(Flux<ChatStreamEvent>)` → `SseEmitter` | 转换为 SSE（无回调） |
| | `toSse(Flux<ChatStreamEvent>, Consumer<TokenUsage>)` → `SseEmitter` | 转换为 SSE（usage 回调） |
| `SseProperties` | `timeout`（默认 30 秒） | SSE 超时配置 |

### 2.29 OpenApiClient（com.cartisan.openapi.client）

| 类/方法 | 说明 |
|---------|------|
| `OpenApiClient` | 服务间 HTTP 客户端，自动签名 |
| `post(url, body, responseType)` | 发送签名 POST 请求 |
| `get(url, responseType)` | 发送签名 GET 请求（query 参数参与签名） |
| `OpenApiClientException` | 非 2xx 响应异常，含 statusCode 和 body 字段 |

### 2.30 签名注解（com.cartisan.openapi.annotation）

| 注解 | 目标 | 说明 |
|------|------|------|
| `@RequireSignature(permission)` | TYPE/METHOD | 需要验签，可选 permission 属性进行权限检查 |
| `@NoSignature` | TYPE/METHOD | 跳过验签 |

### 2.31 ApiKeyProvider（com.cartisan.openapi.provider）

| 接口/类 | 方法 | 说明 |
|---------|------|------|
| `ApiKeyProvider` | `findByAppId(appId)` → `ApiKeyInfo` | API Key 查询接口 |
| `RemoteApiKeyProvider` | - | 远程服务 + Caffeine 缓存实现 |
| `ApiKeyInfo` | `appId()`, `appSecret()`, `appName()`, `permissions()`, `isActive()` | API Key 信息 Record |

### 2.32 NonceRepository（com.cartisan.openapi.nonce）

| 接口 | 方法 | 说明 |
|------|------|------|
| `NonceRepository` | `tryAcquire(nonce, ttl)` → `boolean` | Nonce 防重放接口 |

### 2.33 OpenAPI 配置属性（com.cartisan.openapi.config）

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `cartisan.openapi.self.app-id` | String | "" | 本服务 appId |
| `cartisan.openapi.self.app-secret` | String | "" | 本服务 appSecret |
| `cartisan.openapi.apikey-service-url` | String | "" | API Key 查询地址 |
| `cartisan.openapi.timestamp-tolerance` | int | 300 | 时间戳容差（秒） |
| `cartisan.openapi.nonce-ttl` | int | 300 | nonce 有效期（秒） |
| `cartisan.openapi.max-body-size` | DataSize | 1MB | 请求体大小限制 |
| `cartisan.openapi.cache.expire-after-access` | Duration | 30m | 缓存过期时间 |
| `cartisan.openapi.cache.maximum-size` | long | 1000 | 最大缓存条目数 |

### 2.34 RedisKey 工具（com.cartisan.core.util.RedisKey）

| 类/方法 | 说明 |
|---------|------|
| `RedisKey.of(prefix, expireSeconds)` | 创建带过期时间的 Key |
| `RedisKey.permanent(prefix)` | 创建永不过期的 Key |
| `key(suffix)` | 生成完整的 Redis Key（格式：`prefix:suffix`） |
| `expireSeconds()` | 获取过期时间（秒），0 表示永不过期 |
| `isPermanent()` | 判断是否为永久 Key |

### 2.30 DomainMapper（com.cartisan.web.mapper）

| 接口/方法 | 说明 |
|----------|------|
| `DomainMapper<S, T>` | MapStruct 转换器基接口 |
| `convert(S source)` | 转换单个对象（需由 MapStruct 生成） |
| `convertList(List<S> sources)` | 批量转换 List（默认方法） |
| `convertSet(Set<S> sources)` | 批量转换 Set（默认方法） |

**注意**：`convertList` 和 `convertSet` 在输入为 null 或空时返回空集合。

### 2.31 TreeNode（com.cartisan.web.support）

| 类/方法 | 说明 |
|---------|------|
| `TreeNode<T>` | 树节点泛型类 |
| `TreeNode(id, name, parentId)` | 基本构造 |
| `TreeNode(id, name, parentId, children)` | 完整构造 |
| `TreeNodeBuilder.build(nodes, idMapper, parentIdMapper, rootParentId)` | 构建树形结构 |

### 2.32 @PreventResubmit（com.cartisan.web.resubmit）

| 注解/类 | 属性/方法 | 说明 |
|---------|----------|------|
| `@PreventResubmit` | `delaySeconds`（默认 20） | 防重提交时间窗口（秒） |
| | `prefix`（默认 ""） | Redis key 前缀 |
| `ResubmitLock` | `lock(key, delaySeconds)` | 基于 Redis 的分布式锁 |
| `ResubmitAspect` | - | AOP 切面，拦截注解方法 |

### 2.33 Jackson 全局配置（com.cartisan.web.config）

| 配置项 | 说明 |
|--------|------|
| `Long → String` | 解决 JavaScript Long 精度问题 |
| `LocalDateTime → ISO 8601` | 标准日期时间格式 |
| `BigDecimal → 禁止科学计数法` | 保持金额精度 |
| `Enum → 字符串` | 枚举值序列化为字符串 |
| `忽略未知属性` | 反序列化时忽略未知字段 |

### 2.34 RequestLogFilter（com.cartisan.web.filter）

| 类 | 说明 |
|----|------|
| `RequestLogFilter` | 记录 HTTP 请求基本信息（requestId、IP、方法、URI） |

**排除路径**：`/swagger-ui`、`/v3/api-docs`、`/swagger-resources`、`/druid`、`/actuator`

### 2.35 @Condition 注解（com.cartisan.data.jpa.specification）

| 注解/枚举 | 说明 |
|----------|------|
| `@Condition` | 查询条件注解（propName、type、blurry） |
| `ConditionType` | 11 种查询类型：EQUAL、NOT_EQUAL、GREATER、GREATER_EQUAL、LESS、LESS_EQUAL、INNER_LIKE、LEFT_LIKE、RIGHT_LIKE、IN、BETWEEN |
| `ConditionSpecifications.fromAnnotation(query)` | 从注解生成 Specification |

**详细使用指南**：[condition-annotation.md](condition-annotation.md)

### 2.36 枚举选项支持（com.cartisan.web.response）

| 类/方法 | 说明 |
|--------|------|
| `EnumOption` | 枚举选项 DTO，包含 code 和 name 字段 |
| `EnumOptionUtils.fromEnum(Class)` | 将枚举类转换为选项列表 |
| `EnumOptionUtils.fromEnumGeneric(Class)` | 将枚举类转换为选项列表（通配符版本） |
| `EnumOptionUtils.fromEnums(E...)` | 将枚举数组转换为选项列表 |
| `EnumRegistry` | 枚举注册表，维护枚举类名到 Class 的映射 |
| `EnumController` | 默认 Controller，提供 `/api/enums/{enumName}` 和 `/api/enums/batch` 端点 |
| `EnumControllerBase` | Controller 基类，可继承自定义 |

**配置属性**：

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `cartisan.web.enum-controller.enabled` | `boolean` | `true` | 是否启用默认 Controller |
| `cartisan.web.enum-controller.path` | `String` | `/api/enums` | Controller 路径 |
| `cartisan.web.enum-controller.scan-packages` | `String[]` | - | 要扫描的包列表（默认：com.cartisan, com.example） |

---

## 四、使用示例

### 3.1 定义聚合根

```java
// ID 定义（推荐使用 Record）
public record OrderId(String value) implements Identity<String> {
    public OrderId {
        Objects.requireNonNull(value, "orderId cannot be null");
    }
}

// 聚合根
@Aggregate
public class Order implements AggregateRoot<Order, OrderId> {
    private OrderId id;
    private OrderStatus status;
    private List<OrderItem> items;

    public Order(String customerId, List<OrderItem> items) {
        this.id = new OrderId(UUID.randomUUID().toString());
        this.status = OrderStatus.PENDING;
        this.items = new ArrayList<>(items);
    }

    public void ship() {
        Assertions.require(
            this.status != OrderStatus.SHIPPED,
            OrderError.CANNOT_SHIP_SHIPPED
        );
        this.status = OrderStatus.SHIPPED;
    }

    @Override
    public OrderId getId() {
        return id;
    }
}
```

### 3.2 使用异常体系

```java
// 领域层 - 业务规则违反
public class Order implements AggregateRoot<Order, OrderId> {
    public void cancel() {
        Assertions.require(
            this.status != OrderStatus.COMPLETED,
            OrderError.CANNOT_CANCEL_COMPLETED
        );
        this.status = OrderStatus.CANCELLED;
    }

// 应用层 - 用例前置条件
public class OrderApplicationService {
    public OrderDto getOrder(Long orderId) {
        // 快捷版：标准 404 场景
        Order order = Assertions.requirePresent(
            orderRepository.findById(orderId)
        );
        return OrderDto.from(order);
    }

    public void cancelOrder(Long orderId, Long userId) {
        // 完整版：区分不同资源类型
        Order order = Assertions.requirePresent(
            orderRepository.findById(orderId),
            OrderError.ORDER_NOT_FOUND
        );

        Assertions.require(
            order.belongsToUser(userId),
            OrderError.NOT_ORDER_OWNER
        );

        order.cancel();
    }
}
```

### 3.3 使用架构注解

#### 3.4.1 Repository 模式（数据持久化）

```java
// package-info.java - 标注限界上下文
@BoundedContext(name = "OrderManagement", subDomain = SubDomain.CORE)
package com.cartisan.order;

// 端口接口
@Port(PortType.REPOSITORY)
public interface OrderRepository extends BaseRepository<Order, OrderId> {
}

// 适配器实现
@Adapter(PortType.REPOSITORY)
public class JpaOrderRepository implements OrderRepository {
    // ...
}

// 领域服务
@DomainService
public class OrderPricingService {
    // 不属于任何聚合根的定价逻辑
}
```

#### 3.4.2 Service Port 模式（领域服务 + 南向接口）

当领域层需要调用外部服务（如短信、支付、OSS）或跨限界上下文时，应使用**领域服务 + 南向接口模式**：

```java
// ========== 领域层 ==========
// Step 1: 定义南向接口（端口）
@Port(PortType.CLIENT)
public interface SmsSenderPort {
    void sendVerificationCode(String phoneNumber, String code);
    void sendNotification(String phoneNumber, String message);
}

// Step 2: 创建领域服务
@DomainService
public class NotificationService {
    private final SmsSenderPort smsSender;

    public NotificationService(SmsSenderPort smsSender) {
        this.smsSender = smsSender;
    }

    public void sendLoginCode(User user, String code) {
        smsSender.sendVerificationCode(user.getPhoneNumber(), code);
    }
}

// ========== 基础设施层 ==========
// Step 3: 实现适配器（阿里云短信）
@Component("aliyunSmsSender")
@Adapter(PortType.CLIENT)
public class AliyunSmsSenderAdapter implements SmsSenderPort {
    private final AliyunSmsClient client;

    public AliyunSmsSenderAdapter(AliyunSmsClient client) {
        this.client = client;
    }

    @Override
    public void sendVerificationCode(String phoneNumber, String code) {
        client.sendWithTemplate(phoneNumber, "VERIFY_CODE_TEMPLATE", Map.of("code", code));
    }

    @Override
    public void sendNotification(String phoneNumber, String message) {
        client.send(phoneNumber, message);
    }
}

// ========== 应用层 ==========
// Step 4: 应用服务使用
@ApplicationService
public class UserAuthAppService {
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    public void sendLoginCode(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        String code = generateRandomCode();
        notificationService.sendLoginCode(user, code);
        // 保存验证码到 Redis...
    }
}
```

**关键点**：

| 要点 | 说明 |
|------|------|
| `@Port(PortType.CLIENT)` | 标记客户端端口接口 |
| `@DomainService` | 领域服务封装外部服务调用 |
| `@Component` | 适配器必须添加，Spring 才能发现 Bean |
| `@Adapter(PortType.CLIENT)` | 标记适配器类型 |
| 构造函数注入 | 所有依赖字段声明为 final |
| 可替换性 | 可轻松切换阿里云/腾讯云/云片短信 |

### 3.4 使用 ArchUnit 规则

```java
// 业务项目中继承即可获得全部规则
@AnalyzeClasses(packages = "com.semgravio")
public class ArchitectureTest extends CartisanArchRules {
    // 完成！所有规则自动生效
}

// 或选择性使用
@AnalyzeClasses(packages = "com.semgravio")
public class ArchitectureTest {
    @ArchTest
    static final ArchRules layering = ArchRules.in(CartisanLayeringRules.class);

    @ArchTest
    static final ArchRules codingStandards = ArchRules.in(CartisanCodingStandardsRules.class);
    // 不使用 naming 和 prohibition 规则
}
```

### 3.5 使用审计和软删除基类

```java
// 仅审计
@Entity
public class Product extends Auditable {
    @Id private Long id;
    private String name;
    // 自动拥有：createdAt, lastModifiedDate, createdBy, lastModifiedBy
}

// 审计 + 软删除
@Entity
public class Order extends AuditableSoftDeletable {
    @Id private Long id;
    private String status;
    // 自动拥有：审计字段 + deleted（带 @SQLRestriction）
}

// 软删除操作
orderRepository.delete(order);  // UPDATE SET deleted = true
orderRepository.findAll();      // 自动过滤 deleted = true
```

### 3.6 使用 TSID 生成器

```java
@Entity
public class Order extends AbstractAggregateRoot<Order> {

    @Id
    private Long id;

    @PrePersist
    void generateId() {
        if (id == null) {
            id = tsidGenerator.generate();
        }
    }
}

// 或在 Service 层生成
@Service
public class OrderService {
    private final TsidGenerator tsidGenerator;

    public Long createOrder() {
        Long orderId = tsidGenerator.generate();
        Instant createTime = tsidGenerator.toInstant(orderId);
        // ...
        return orderId;
    }
}
```

### 3.7 发布和监听应用事件

应用事件使用 Spring 的事件机制，通过 ApplicationEventPublisher 发布：

```java
// 1. 定义应用事件（Record + @PublishTo）
@PublishTo("spring")
public record OrderCreatedEvent(
    String eventId,
    Instant occurredAt,
    Long orderId,
    String customerId
) implements ApplicationEvent {
    public OrderCreatedEvent(Long orderId, String customerId) {
        this(UUID.randomUUID().toString(), Instant.now(), orderId, customerId);
    }

    @Override
    public String eventType() {
        return "order.created";
    }
}

// 2. 应用服务发布事件
@Service
public class OrderService {
    private final ApplicationEventPublisher eventPublisher;

    public void createOrder(CreateOrderRequest request) {
        Order order = new Order(request.getCustomerId(), request.getItems());
        orderRepository.save(order);

        // 发布应用事件
        eventPublisher.publishApplicationEvent(
            new OrderCreatedEvent(order.getId(), request.getCustomerId())
        );
    }
}

// 3. 监听应用事件
@Component
public class OrderEventHandler {

    // 事务提交后执行（推荐）
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(OrderCreatedEvent event) {
        // 发送通知、调用外部服务等
        notificationService.sendOrderCreated(event);
    }

    // 事务内同步执行
    @EventListener
    public void handle2(OrderShippedEvent event) {
        // 同库操作，如更新其他聚合根
    }
}
```

> **注意**：应用事件的详细使用说明和最佳实践，请参考 `docs/PITFALLS.md` 文档。

### 3.8 使用权限注解

```java
// 类级别注解
@RestController
@RequireAuth  // 类内所有方法都需要登录
@RequestMapping("/api/v1/users")
public class UserController {
    @GetMapping("/me")
    public ApiResponse<User> getCurrentUser() { ... }
}

// 方法级别注解
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    @RequireRole({"admin"})
    @PostMapping("/users")
    public ApiResponse<Void> createUser() { ... }

    @RequirePermission(
        value = "admin:user:read",
        name = "平台管理 / 用户管理 / 查看",
        scope = "admin"
    )
    @GetMapping("/users")
    public ApiResponse<List<User>> listUsers() { ... }

    @RequirePermission("admin:user:write")
    @PostMapping("/users")
    public ApiResponse<Void> createUser() { ... }
}

// 方法覆盖类注解
@RestController
@RequireAuth  // 默认需要登录
@RequestMapping("/api/v1/public")
public class PublicController {

    @GetMapping("/info")
    public ApiResponse<Info> getInfo() { ... }  // 需要登录

    @RequireAuth(false)  // 覆盖类注解，允许匿名访问
    @GetMapping("/ping")
    public ApiResponse<String> ping() { ... }
}
```

**权限 Code 规范：** 采用 3 级结构 `{context}:{module}:{action}`
- context: 限界上下文（如 admin）
- module: 业务模块（如 user）
- action: 操作（如 read/write/delete）

### 3.9 使用 SecurityContext

```java
@Service
public class OrderService {

    public void createOrder(CreateOrderRequest request) {
        // 推荐用法：先检查是否登录
        if (SecurityContext.isAuthenticated()) {
            Long userId = SecurityContext.getCurrentUserId();
            String username = SecurityContext.getCurrentUsername();

            // 判断角色/权限
            boolean isAdmin = SecurityContext.hasRole("admin");
            boolean canCreate = SecurityContext.hasPermission("order:create");

            // 使用用户信息...
        }
    }

    // 或者：对返回值做 null 检查
    public void updateOrder(Long orderId, UpdateOrderRequest request) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId != null) {
            // 使用 userId...
        }
    }
}
```

### 3.10 使用 TenantContext

```java
@Service
public class OrderService {

    public void createOrder(CreateOrderRequest request) {
        // 获取租户 ID（可能为 null）
        Long tenantId = TenantContext.getCurrentTenantId();

        // 判断是否有租户上下文
        if (TenantContext.hasTenant()) {
            // 使用租户 ID...
            Order order = new Order(tenantId, request);
            orderRepository.save(order);
        }
    }

    // 强制必须有租户上下文
    public void deleteOrder(Long orderId) {
        Long tenantId = TenantContext.requireTenant();  // 无租户抛异常
        Order order = orderRepository.findByIdAndTenantId(orderId, tenantId)
            .orElseThrow();
        orderRepository.delete(order);
    }
}
```

### 3.11 使用 AuthenticationService

```java
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authService;

    // 业务层验证密码后调用 login
    @PostMapping("/login")
    public ApiResponse<TokenInfo> login(@RequestBody LoginRequest request) {
        // 1. 业务层验证密码
        User user = userService.validatePassword(request.getUsername(), request.getPassword());

        // 2. 调用认证服务创建会话
        TokenInfo tokenInfo = authService.login(user.getId());

        // 3. 可选：设置租户 ID 到 Session
        StpUtil.getSession().set("tenantId", user.getTenantId());

        return ApiResponse.ok(tokenInfo);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.ok();
    }

    @GetMapping("/token-info")
    public ApiResponse<TokenInfo> getTokenInfo() {
        TokenInfo tokenInfo = authService.getTokenInfo();
        if (tokenInfo == null) {
            return ApiResponse.error(401, "未登录");
        }
        return ApiResponse.ok(tokenInfo);
    }
}
```

### 3.12 使用 @CurrentUser 注解

```java
// 必需登录场景
@RestController
@RequestMapping("/api/users")
public class UserController {

    // 方式一：只用 @CurrentUser
    @GetMapping("/profile")
    public ApiResponse<UserProfile> getProfile(@CurrentUser Long userId) {
        // 未登录会在参数解析时抛 NotLoginException → 401
        return ApiResponse.ok(userService.getProfile(userId));
    }

    // 方式二：@RequireAuth + @CurrentUser（推荐，语义更明确）
    @RequireAuth
    @GetMapping("/profile")
    public ApiResponse<UserProfile> getProfile(@CurrentUser Long userId) {
        // 未登录会在拦截器阶段被拦截，不会到达参数解析
        return ApiResponse.ok(userService.getProfile(userId));
    }

    @PutMapping("/profile")
    public ApiResponse<Void> updateProfile(@CurrentUser Long userId,
                                           @RequestBody UpdateProfileCommand cmd) {
        userService.updateProfile(userId, cmd);
        return ApiResponse.ok();
    }
}

// 可选登录场景（允许匿名访问）
@RestController
@RequestMapping("/api/preferences")
public class PreferencesController {

    @GetMapping
    public ApiResponse<Preferences> getPreferences(@CurrentUser Optional<Long> userId) {
        if (userId.isPresent()) {
            return ApiResponse.ok(preferencesService.getForUser(userId.get()));
        }
        return ApiResponse.ok(preferencesService.getDefault());
    }

    // 简化写法
    @GetMapping("/widgets")
    public ApiResponse<Widgets> getWidgets(@CurrentUser Optional<Long> userId) {
        return ApiResponse.ok(widgetsService.getWidgets(userId.orElse(null)));
    }
}
```

**@CurrentUser 与 @RequireAuth 的区别**：

| 注解 | 作用时机 | 适用场景 |
|------|---------|---------|
| `@RequireAuth` | 拦截器阶段 | 整个接口需要登录 |
| `@CurrentUser Long userId` | 参数解析阶段 | 需要使用 userId，未登录抛异常 |
| `@CurrentUser Optional<Long> userId` | 参数解析阶段 | 允许匿名访问，已登录可获取 userId |

### 3.13 配置拦截器路径

```yaml
# 仅保护 API 路径（默认是 /**）
cartisan:
  security:
    interceptor:
      path-patterns:
        - "/api/**"
        - "/admin/**"

# 完整配置示例
cartisan:
  security:
    interceptor:
      path-patterns:
        - "/api/**"
        - "/admin/**"
        - "/internal/**"
      exclude-path-patterns:
        - "/api/public/**"
        - "/api/health"
        - "/error"
        - "/actuator/**"
```

### 3.14 使用 jOOQ 自动配置

```java
// 引入依赖后，DSLContext 自动注入可用
@Service
public class UserService {
    private final DSLContext dsl;

    public UserService(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<User> findActiveUsers() {
        return dsl.selectFrom(USER)
            .where(USER.STATUS.eq("ACTIVE"))
            .orderBy(USER.CREATED_AT.desc())
            .fetchInto(User.class);
    }

    // 复杂查询示例：JOIN + 聚合
    public List<OrderSummary> getOrderSummaries(LocalDate startDate) {
        return dsl.select(
                USER.ID,
                USER.NAME,
                DSL.count.ORDER_ID().as("orderCount"),
                DSL.sum(ORDER.TOTAL_AMOUNT).as("totalAmount")
            )
            .from(USER)
            .leftJoin(ORDER).on(ORDER.USER_ID.eq(USER.ID))
            .where(ORDER.CREATED_AT.ge(startDate))
            .groupBy(USER.ID, USER.NAME)
            .fetchInto(OrderSummary.class);
    }
}
```

### 3.15 启用 SQL 日志

```yaml
# application.yml
cartisan:
  data-query:
    jooq:
      sql-logging: true  # 启用 SQL 执行日志
```

### 3.16 使用多租户查询

```java
import static com.cartisan.data.query.support.JooqTenantSupport.eqTenantId;

@Service
public class UserService {
    private final DSLContext dsl;

    // 查询时自动添加租户过滤
    public List<User> listUsers() {
        return dsl.selectFrom(USER)
            .where(eqTenantId(USER.TENANT_ID))  // 自动根据当前租户过滤
            .fetchInto(User.class);
    }

    // 组合条件查询
    public List<User> listActiveUsers() {
        return dsl.selectFrom(USER)
            .where(
                USER.STATUS.eq("ACTIVE")
                .and(eqTenantId(USER.TENANT_ID))  // 租户过滤 + 其他条件
            )
            .fetchInto(User.class);
    }

    // 无租户上下文时，eqTenantId 返回 noCondition()，不影响查询
    public List<User> listAllUsersForAdmin() {
        return dsl.selectFrom(USER)
            .where(eqTenantId(USER.TENANT_ID))  // 管理员可能无租户限制
            .fetchInto(User.class);
    }
}
```

### 3.17 jOOQ 代码生成配置

在业务项目 `pom.xml` 中添加 jOOQ 代码生成插件：

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.jooq</groupId>
            <artifactId>jooq-codegen-maven</artifactId>
            <version>${jooq.version}</version>
            <executions>
                <execution>
                    <id>generate-jooq</id>
                    <phase>generate-sources</phase>
                    <goals>
                        <goal>generate</goal>
                    </goals>
                    <configuration>
                        <generator>
                            <database>
                                <name>org.jooq.meta.postgres.PostgresDatabase</name>
                                <includes>.*</includes>
                                <excludes></excludes>
                            </database>
                            <generate>
                                <javaTimeTypes>true</javaTimeTypes>
                            </generate>
                            <target>
                                <packageName>com.example.db</packageName>
                                <directory>target/generated-sources/jooq</directory>
                            </target>
                        </generator>
                    </configuration>
                </execution>
            </executions>
            <dependencies>
                <dependency>
                    <groupId>org.postgresql</groupId>
                    <artifactId>postgresql</artifactId>
                    <version>${postgresql.version}</version>
                </dependency>
            </dependencies>
        </plugin>
    </plugins>
</build>
```

运行 `mvn generate-sources` 生成代码后使用：

```java
import static com.example.db.Tables.*;

// 类型安全的 DSL 查询
List<UserRecord> users = dsl.selectFrom(USER)
    .where(USER.AGE.gt(18))
    .fetch();
```

### 3.18 使用 cartisan-ai 同步调用

```java
@Service
public class AiService {
    private final ModelProviderRegistry registry;

    // 通过 Registry 调用（推荐，支持动态切换 Provider）
    public String chat(String providerId, String userMessage) {
        ChatRequest request = new ChatRequest(
            "gpt-4o-mini",  // 或其他模型名
            List.of(
                new ChatMessage(Role.SYSTEM, "You are a helpful assistant."),
                new ChatMessage(Role.USER, userMessage)
            ),
            0.7,    // temperature
            null,   // maxTokens
            false   // stream
        );

        ChatResponse response = registry.chat(providerId, request);
        return response.content();
    }

    // 直接注入特定 Provider
    public String chatWithOpenAi(String userMessage) {
        // OpenAiProvider 会自动注入
        ModelProvider provider = registry.getProvider("openai");
        // ... 同上
    }
}
```

### 3.19 使用 cartisan-ai 流式调用（SSE）

```java
@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final ModelProviderRegistry registry;
    private final SseHelper sseHelper;

    // 返回 SSE 流
    @GetMapping("/chat/stream")
    public SseEmitter chatStream(
            @RequestParam(defaultValue = "openai") String providerId,
            @RequestParam String message) {

        ChatRequest request = new ChatRequest(
            "gpt-4o-mini",
            List.of(new ChatMessage(Role.USER, message)),
            null, null, true  // stream = true
        );

        Flux<ChatStreamEvent> events = registry.chatStream(providerId, request);

        // 转换为 SSE，流结束时记录 Token 使用
        return sseHelper.toSse(events, usage -> {
            log.info("Token usage: prompt={}, completion={}",
                usage.promptTokens(), usage.completionTokens());
        });
    }

    // 或者返回 Flux（让客户端处理 Reactor 类型）
    @GetMapping(value = "/chat/flux", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatStreamEvent> chatFlux(@RequestParam String message) {
        ChatRequest request = new ChatRequest(
            "gpt-4o-mini",
            List.of(new ChatMessage(Role.USER, message)),
            null, null, true
        );

        return registry.chatStream("openai", request);
    }
}
```

### 3.20 配置 cartisan-ai Provider

```yaml
# application.yml
cartisan:
  ai:
    # OpenAI 配置
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com/v1  # 可选，支持代理/Azure
    # DeepSeek 配置
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com/v1
    # Anthropic 配置
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      base-url: https://api.anthropic.com
    # SSE 超时配置
    sse:
      timeout: 30s  # 默认 30 秒
```

**条件装配规则**：
- 只有配置了对应 `api-key` 的 Provider 才会被创建
- 至少需要配置一个 Provider，`ModelProviderRegistry` 才会被创建

### 3.21 实现 ModelUsageListener

```java
@Component
public class TokenUsageLogger implements ModelUsageListener {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageLogger.class);

    @Override
    public void onUsage(String providerId, String model, TokenUsage usage) {
        log.info("AI Usage - Provider: {}, Model: {}, Prompt: {}, Completion: {}, Total: {}",
            providerId, model, usage.promptTokens(), usage.completionTokens(), usage.totalTokens());

        // 可以写入数据库、发送监控告警等
    }
}
```

### 3.22 使用 RedisKey 工具

```java
@Service
@RequiredArgsConstructor
public class UserService {
    private final StringRedisTemplate redisTemplate;

    // 定义带过期时间的 Key（1 小时）
    private static final RedisKey USER_CACHE_KEY = RedisKey.of("user:cache", 3600);

    // 定义永不过期的 Key
    private static final RedisKey SYSTEM_CONFIG_KEY = RedisKey.permanent("system:config");

    public void cacheUser(Long userId, String userData) {
        String key = USER_CACHE_KEY.key(String.valueOf(userId));
        redisTemplate.opsForValue().set(key, userData, USER_CACHE_KEY.expireSeconds(), TimeUnit.SECONDS);
    }

    public String getUserCache(Long userId) {
        String key = USER_CACHE_KEY.key(String.valueOf(userId));
        return redisTemplate.opsForValue().get(key);
    }
}
```

### 3.23 使用 DomainMapper 批量转换

```java
@Mapper(componentModel = "spring")
public interface UserMapper extends DomainMapper<User, UserResponse> {
    // convert 方法由 MapStruct 自动生成
}

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserMapper userMapper;

    public UserResponse getUser(Long id) {
        User user = userRepository.findById(id);
        return userMapper.convert();  // 单个对象
    }

    public List<UserResponse> listUsers() {
        List<User> users = userRepository.findAll();
        return userMapper.convertList(users);  // List 批量转换
    }

    public Set<UserResponse> setUsers() {
        Set<User> users = userRepository.findAllAsSet();
        return userMapper.convertSet(users);  // Set 批量转换
    }
}
```

### 3.24 使用 TreeNode 构建树结构

```java
@Service
@RequiredArgsConstructor
public class DepartmentService {
    private final DepartmentRepository departmentRepository;

    public List<TreeNode<Long>> getDepartmentTree() {
        // 1. 从数据库获取扁平的部门列表
        List<Department> departments = departmentRepository.findAll();

        // 2. 转换为 TreeNode
        List<TreeNode<Long>> nodes = departments.stream()
            .map(dept -> new TreeNode<>(
                dept.getId(),
                dept.getName(),
                dept.getParentId()
            ))
            .toList();

        // 3. 构建树形结构
        return TreeNodeBuilder.build(
            nodes,
            id -> String.valueOf(id),           // ID 映射函数
            parentId -> String.valueOf(parentId), // 父 ID 映射函数
            0L                                    // 根节点的父 ID
        );
    }
}
```

### 3.25 使用 @PreventResubmit 防重提交

```java
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @PostMapping
    @PreventResubmit(delaySeconds = 10, prefix = "createUser")
    public ApiResponse<Void> createUser(@RequestBody CreateUserRequest request) {
        userService.create(request);
        return ApiResponse.ok();
    }

    @PostMapping("/batch")
    @PreventResubmit(delaySeconds = 30)  // 使用默认前缀
    public ApiResponse<Void> batchCreate(@RequestBody List<CreateUserRequest> requests) {
        userService.batchCreate(requests);
        return ApiResponse.ok();
    }
}
```

### 3.26 使用 @Condition 注解查询

```java
// 定义查询 DTO
public record ProductQuery(
    @Condition(type = ConditionType.INNER_LIKE) String name,
    @Condition(propName = "stock", type = ConditionType.GREATER_EQUAL) Integer minStock
) {}

// Repository 使用
public interface ProductRepository extends BaseRepository<Product, Long> {
    default List<Product> findByCondition(ProductQuery query) {
        return findAll(ConditionSpecifications.fromAnnotation(query));
    }
}
```

> **详细说明**：参见 cartisan-web 模块章节中的 @Condition 注解详细说明

### 3.27 使用枚举选项工具

```java
// 转换单个枚举
List<EnumOption> options = EnumOptionUtils.fromEnum(UserStatus.class);

// 转换枚举数组
List<EnumOption> options = EnumOptionUtils.fromEnums(
    UserStatus.ACTIVE,
    UserStatus.INACTIVE
);

// 在 Response 中包含选项
public record UserResponse(
    Long id,
    UserStatus status,
    List<EnumOption> statusOptions
) {}

@Mapper(componentModel = "spring")
public interface UserMapper extends DomainMapper<User, UserResponse> {
    @Mapping(target = "statusOptions",
             expression = "java(EnumOptionUtils.fromEnum(UserStatus.class))")
    UserResponse toResponse(User user);
}
```

### 3.29 使用默认枚举 Controller

```yaml
# application.yml（默认配置）
cartisan:
  web:
    enum-controller:
      enabled: true
      path: /api/enums
```

```javascript
// 前端调用示例
const fetchEnums = async () => {
  // 单个枚举
  const response1 = await fetch('/api/enums/UserStatus');
  const data1 = await response1.json();
  // data1.data = [{code: 1, name: "启用"}, {code: 0, name: "禁用"}]

  // 批量获取
  const response2 = await fetch('/api/enums/batch', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({enums: ['UserStatus', 'OrderStatus']})
  });
  const data2 = await response2.json();
  // data2.data.enums = {UserStatus: [...], OrderStatus: [...]}
};
```

### 3.30 自定义枚举 Controller

```yaml
# 禁用默认实现
cartisan:
  web:
    enum-controller:
      enabled: false
```

```java
@RestController
@RequestMapping("/api/v2/dict")
public class DictController extends EnumControllerBase {

    public DictController(EnumRegistry enumRegistry) {
        super(enumRegistry);
    }

    @GetMapping("/{enumName}")
    public ApiResponse<List<EnumOption>> getEnum(@PathVariable String enumName) {
        return ApiResponse.ok(enumRegistry.getEnumOptions(enumName));
    }

    @PostMapping("/batch")
    public ApiResponse<Map<String, List<EnumOption>>> batch(
            @RequestBody EnumBatchRequest request) {
        return ApiResponse.ok(batchEnums(request.enums()));
    }
}
```

### 3.31 使用 OpenApiClient 调用远程服务

```java
@Service
@RequiredArgsConstructor
public class UserClient {
    private final OpenApiClient openApiClient;

    // GET 请求
    public UserInfo getUser(Long userId) {
        return openApiClient.get(
            "http://user-service/api/users/" + userId,
            UserInfo.class
        );
    }

    // POST 请求
    public OrderDTO createOrder(CreateOrderRequest request) {
        return openApiClient.post(
            "http://order-service/api/orders",
            request,
            OrderDTO.class
        );
    }
}
```

**错误处理**：

```java
try {
    return openApiClient.get(url, responseType);
} catch (OpenApiClientException e) {
    log.error("远程调用失败: status={}, body={}", e.getStatusCode(), e.getBody());
    throw new ApplicationException("远程服务调用失败: " + e.getStatusCode());
}
```

### 3.32 配置 OpenAPI 签名验签

```yaml
# application.yml
cartisan:
  openapi:
    self:
      app-id: "order-service"
      app-secret: "${OPENAPI_SECRET}"
    apikey-service-url: "http://auth-service/api/apikeys"
    timestamp-tolerance: 300
    nonce-ttl: 300
    max-body-size: 1MB
    cache:
      expire-after-access: 30m
      maximum-size: 1000
```

### 3.33 服务端验签 + 权限控制

```java
// 类级别：所有方法需验签
@RestController
@RequireSignature
@RequestMapping("/api/internal/orders")
public class InternalOrderController {

    @GetMapping("/{id}")
    public ApiResponse<OrderDTO> getOrder(@PathVariable Long id) {
        return ApiResponse.ok(orderService.getOrder(id));
    }

    // 需要特定权限
    @RequireSignature(permission = "order:write")
    @PostMapping
    public ApiResponse<OrderDTO> create(@RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(orderService.create(request));
    }

    // 跳过验签
    @NoSignature
    @GetMapping("/health")
    public ApiResponse<String> health() {
        return ApiResponse.ok("ok");
    }
}
```

### 3.34 获取调用方信息

```java
@GetMapping("/info")
public ApiResponse<Map<String, String>> getCallerInfo() {
    return ApiResponse.ok(Map.of(
        "callerAppId", String.valueOf(RequestContext.getCallerAppId()),
        "callerAppName", String.valueOf(RequestContext.getCallerAppName())
    ));
}
```

---

## 五、CQRS 架构说明

### 5.1 读写分离设计

| 模块 | 职责 | 技术 |
|------|------|------|
| **cartisan-data-jpa** | 写侧（Command） | JPA + Hibernate |
| **cartisan-data-query** | 读侧（Query） | jOOQ + DSL |

### 5.2 典型使用场景

```java
// 写：使用 JPA 保存聚合根
@Service
public class OrderService {
    private final OrderRepository orderRepository;  // JPA

    public void createOrder(CreateOrderRequest request) {
        Order order = new Order(request.getCustomerId(), request.getItems());
        orderRepository.save(order);

        // 发布应用事件（如需要）
        eventPublisher.publishApplicationEvent(
            new OrderCreatedEvent(order.getId(), request.getCustomerId())
        );
    }
}

// 读：使用 jOOQ 高效查询
@Service
public class OrderQueryService {
    private final DSLContext dsl;  // jOOQ

    public Page<OrderDto> queryOrders(OrderQuery query, Pageable pageable) {
        // 类型安全的 DSL 查询
        List<OrderDto> orders = dsl.select(
                ORDER.ID,
                ORDER.CUSTOMER_ID,
                ORDER.STATUS,
                ORDER.TOTAL_AMOUNT
            )
            .from(ORDER)
            .where(buildConditions(query))
            .orderBy(OrderConstant)
            .limit(pageable.getPageSize())
            .offset(pageable.getOffset())
            .fetchInto(OrderDto.class);

        long total = dsl.fetchCount(ORDER);
        return new PageImpl<>(orders, pageable, total);
    }
}
```

---

#### TOOL-008 / SECURITY-001：Sa-Token 包路径

```java
// ❌ 错误：包路径不是 cn.dev33.sa-token
import cn.dev33.sa-token.stp.StpUtil;

// ✅ 正确：包路径是 cn.dev33.satoken
import cn.dev33.satoken.stp.StpUtil;
```

#### TOOL-009 / SECURITY-002：Sa-Token Session 类

```java
// ❌ 错误：没有 cn.dev33.satoken.session.Session
import cn.dev33.satoken.session.Session;

// ✅ 正确：Session 类是 SaSession
import cn.dev33.satoken.session.SaSession;
```

#### SECURITY-003：ScopedValue 使用方式

```java
// ❌ 错误：直接 get() 可能抛 NoSuchElementException
public static Long getCurrentTenantId() {
    return TENANT_ID.get();
}

// ✅ 正确：先检查 isBound()，再 get()
public static Long getCurrentTenantId() {
    if (!TENANT_ID.isBound()) {
        return null;
    }
    return TENANT_ID.get();
}

// ✅ 或使用 getOrDefault()
public static Long getCurrentTenantId() {
    return ScopedValue.getOrDefault(TENANT_ID, null);
}
```

#### TEST-004 / SECURITY-004：MockMvc 集成测试方式

```java
// ❌ 错误：直接调用 StpUtil.login()，Sa-Token 上下文未初始化
@Test
void test() {
    StpUtil.login(100L);
    mvc.perform(get("/api/users"))
        .andExpect(status().isOk());
}

// ✅ 正确：创建测试专用 Controller，通过 HTTP 请求触发登录
@RestController
@RequestMapping("/test/auth")
class TestAuthController {
    @PostMapping("/login")
    public ApiResponse<Void> login(@RequestParam Long userId) {
        StpUtil.login(userId);
        return ApiResponse.ok();
    }
}

@Test
void test() throws Exception {
    mvc.perform(post("/test/auth/login?userId=100"))
        .andExpect(status().isOk());
    // 现在 Sa-Token 上下文已正确初始化
}
```

---

## 六、模块依赖关系

### 6.1 cartisan-core

```
零外部依赖，仅使用 JDK 标准库
```

### 6.2 cartisan-test

```
api 依赖：
- JUnit 5
- AssertJ
- Mockito
- ArchUnit
- Spring Boot Test
- Testcontainers

implementation 依赖：
- Spring Test
- Spring Boot Starter Data Redis
```

### 6.3 cartisan-web

```
api 依赖：
- cartisan-core

implementation 依赖：
- Spring Boot Starter Web
- Spring Boot Starter Validation
```

### 6.4 cartisan-data-jpa

```
api 依赖：
- cartisan-core

implementation 依赖：
- Spring Boot Starter Data JPA
- Hibernate Core（传递）

compileOnly 依赖：
- Druid Spring Boot 3 Starter 1.2.23（可选，业务需显式配置才生效）
```

### 6.5 cartisan-event

```
api 依赖：
- cartisan-core

implementation 依赖：
- Spring Context
- Spring Boot AutoConfigure
```

### 6.6 cartisan-security

```
api 依赖：
- cartisan-core
- cartisan-web

implementation 依赖：
- Sa-Token 1.45.0（sa-token-spring-boot3-starter）
```

### 6.7 cartisan-data-query

```
api 依赖：
- cartisan-web

implementation 依赖：
- jOOQ 3.19.29

compileOnly 依赖：
- cartisan-security（可选，用于 JooqTenantSupport）
```

### 6.8 cartisan-ai

```
api 依赖：
- cartisan-core
- spring-webflux（Flux 类型出现在公开 SPI 中）

implementation 依赖：
- spring-boot-starter

可选依赖（由使用方提供）：
- spring-boot-starter-web（SseEmitter 需要）
- spring-boot-starter-webflux（WebClient 需要）
```

### 6.9 cartisan-openapi

```
api 依赖：
- cartisan-core（RequestContext）

implementation 依赖：
- spring-boot-starter-web
- caffeine（API Key 缓存）
- jackson

可选依赖（由使用方提供）：
- spring-boot-starter-data-redis（NonceRepository Redis 实现）
```

---

## 七、参考文档

### 7.1 设计文档

- [cartisan-boot-设计文档.md](../cartisan-boot-设计文档.md)

### 7.2 开发指南

- [AI协作开发SOP.md](../sop/AI协作开发SOP.md)
- [团队踩坑经验库 (PITFALLS.md)](../PITFALLS.md)
- [OpenAPI服务间通信使用指南.md](./OpenAPI服务间通信使用指南.md)

> **说明**：@Condition、枚举增强等模块详细使用说明已整合到对应模块章节中。

---

---

## 八、设计理念

### 8.1 六边形架构（端口适配器）

cartisan-boot 基于 DDD 六边形架构（端口适配器架构），清晰划分层次职责：

```
北向接口（Driving Side）：
- REST API | GraphQL | gRPC | MQ

应用层（Application Layer）：
- 北向接口适配层 | 上下文出入口

领域层（Domain Layer）：
- 核心业务逻辑 | 南向端口接口

南向接口（Driven Side）：
- 密码加密 | 持久化 | 缓存 | 外部服务

基础设施层（Infrastructure）：
- 南向接口的适配器实现
```

**依赖方向**：
- 北向接口 → 应用层 → 领域层
- 领域层定义南向端口接口
- 基础设施层实现南向端口接口

> **详细设计说明**参见《cartisan-boot-设计文档》

### 8.2 CQRS 架构（读写分离）

cartisan-boot 支持 CQRS 架构，读写分离：

| 模块 | 职责 | 技术 |
|------|------|------|
| **cartisan-data-jpa** | 写侧（Command） | JPA + Hibernate |
| **cartisan-data-query** | 读侧（Query） | jOOQ + DSL |

**典型使用场景**：
- 写：使用 JPA 保存聚合根，按需发布应用事件
- 读：使用 jOOQ 高效查询，类型安全的 DSL

### 8.3 DDD 设计原则（精简版）

cartisan-boot 提供 DDD 基础设施，但不强制 DDD 教条。

**务实的设计取舍**：
- 聚合根是否必须避免使用 `@Setter`？否，简单属性直接用 `@Setter`
- 应用服务层可以直接调用聚合根的 setter？可以，务实做法
- ID 是否必须用强类型值对象？否，不强求

**原则总结**：提供能力，不强求风格。

> **详细编码规范**参见《限界上下文代码编写规范》

---

## 九、配置说明

### 9.1 application.yml 配置项

```yaml
cartisan:
  web:
    enum-controller:
      enabled: true  # 启用默认枚举 Controller
      path: /api/enums  # Controller 路径
      scan-packages:  # 要扫描的包列表
        - com.cartisan
        - com.example
  data-query:
    jooq:
      sql-logging: false  # 启用 SQL 执行日志
  ai:
    sse:
      timeout: 30s  # SSE 超时配置
```

### 9.2 可选功能开关

| 功能 | 配置项 | 默认值 |
|------|--------|--------|
| 枚举 Controller | `cartisan.web.enum-controller.enabled` | `true` |
| jOOQ SQL 日志 | `cartisan.data-query.jooq.sql-logging` | `false` |

### 9.3 Druid 数据源配置

```yaml
spring:
  datasource:
    type: com.alibaba.druid.pool.DruidDataSource
    url: jdbc:postgresql://localhost:5432/mydb
    username: user
    password: pass
    druid:
      stat-view-servlet:
        enabled: true
        login-username: admin
        login-password: admin
```

---

## 十、常见问题

### 10.1 如何运行 ArchUnit 测试？

```bash
# 在 cartisan-core 模块
cd cartisan-core
mvn test -Dtest=ArchitectureTest

# 在业务项目中
mvn test -Dtest=ArchitectureTest
```

### 10.2 如何配置 jOOQ 代码生成？

参见使用手册 3.18 节 jOOQ 代码生成配置和 cartisan-data-query 模块章节的 QUERY-001 规则。

### 10.3 如何枚举实现 BaseEnum？

参见使用手册 2.1.1 节和《限界上下文代码编写规范》3.6.1 节。

### 10.4 更多问题？

参见《PITFALLS.md - 团队踩坑经验库》

---

**文档结束** | **版本**：v2.1 | **更新日期**：2026-04-12
