package com.semgravio.appregistry.application.mapper;

import com.semgravio.appregistry.application.dto.response.SsoClientCreatedResponse;
import com.semgravio.appregistry.application.dto.response.SsoClientResponse;
import com.semgravio.appregistry.domain.sso.aggregate.SsoClient;
import com.cartisan.web.mapper.DomainMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * {@link SsoClient} ↔ 响应 DTO 映射。
 *
 * @since 0.1.0
 */
@Mapper(componentModel = "spring")
public interface SsoClientMapper extends DomainMapper<SsoClient, SsoClientResponse> {

    // status → status：BaseEnum 枚举自动映射（序列化为 Integer code）。
    // statusName ← status.getName()：MapStruct 不会自动调用 getName()，需显式配置。
    // redirectUris / postLogoutRedirectUris / scopes / grants：同类型（List/Set<String>）自动映射。
    @Override
    @Mapping(target = "statusName", source = "status.name")
    SsoClientResponse convert(SsoClient client);

    /**
     * 凭证接口响应（创建 / 重置）：明文 {@code client_secret} 来自额外入参（不在实体上）。
     */
    @Mapping(target = "clientSecret", source = "plaintextSecret")
    @Mapping(target = "statusName", source = "client.status.name")
    SsoClientCreatedResponse toCreated(SsoClient client, String plaintextSecret);
}
