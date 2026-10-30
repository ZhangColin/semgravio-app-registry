package com.semgravio.appregistry.application.mapper;

import com.semgravio.appregistry.application.dto.response.ApiKeyCreatedResponse;
import com.semgravio.appregistry.application.dto.response.ApiKeyResponse;
import com.semgravio.appregistry.domain.signature.aggregate.ApiKey;
import com.cartisan.web.mapper.DomainMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * {@link ApiKey} ↔ 响应 DTO 映射。
 *
 * @since 0.1.0
 */
@Mapper(componentModel = "spring")
public interface ApiKeyMapper extends DomainMapper<ApiKey, ApiKeyResponse> {

    // status → status：BaseEnum 枚举自动映射（序列化为 Integer code）。
    // statusName ← status.getName()：MapStruct 不会自动调用 getName()，需显式配置。
    @Override
    @Mapping(target = "statusName", source = "status.name")
    ApiKeyResponse convert(ApiKey key);

    /**
     * 创建/轮换响应：明文 {@code apiSecret} 来自额外入参（不在实体上）。
     */
    @Mapping(target = "apiSecret", source = "plaintextSecret")
    @Mapping(target = "statusName", source = "key.status.name")
    ApiKeyCreatedResponse toCreated(ApiKey key, String plaintextSecret);
}
