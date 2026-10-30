/**
 * AppRegistry Context。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>平台「应用 / 消费方」的单一登记处（基础能力层）</li>
 *   <li>一个应用一行（{@code RegisteredApp} 治理锚点），持两个独立、可空的凭证：
 *       ApiKey（{@code apiKey}/{@code apiSecret}）与 SsoClient（OIDC client 元数据）</li>
 *   <li>为 {@code cartisan-openapi} 提供机机验签所需的 {@code ApiKeyProvider}（Local，直读表）</li>
 *   <li>为 identity（IdP）提供 SSO client 元数据</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>由 {@code semgravio-openapi} 升级而来（identity SSO 驱动）。
 * 设计权威见 {@code CONTEXT.md} 与 {@code docs/adr/0001-0003}；
 * 架构权威在兄弟仓库 {@code ../semgravio-architecture/}。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain - 领域层：聚合根（RegisteredApp / ApiKey / SsoClient）、仓储接口、枚举、端口接口、错误定义</li>
 *   <li>application - 应用层：应用服务（{@code *AppService}）、DTO、Mapper</li>
 *   <li>infrastructure - 基础设施层：AES / argon2 / 凭证生成等南向端口适配器、{@code LocalApiKeyProvider}</li>
 *   <li>endpoints - 北向接口适配器层：REST API（admin 管理 + bootstrap 数据提供端点）</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "AppRegistry", subDomain = SubDomain.GENERIC)
package com.semgravio.appregistry;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
