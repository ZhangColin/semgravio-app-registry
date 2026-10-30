package com.semgravio.appregistry.application.mapper;

import com.semgravio.appregistry.application.dto.response.AppResponse;
import com.semgravio.appregistry.domain.app.aggregate.RegisteredApp;
import com.cartisan.web.mapper.DomainMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * {@link RegisteredApp} ↔ {@link AppResponse} 映射。
 *
 * @since 0.1.0
 */
@Mapper(componentModel = "spring")
public interface RegisteredAppMapper extends DomainMapper<RegisteredApp, AppResponse> {

    // status → status：BaseEnum 枚举自动映射（序列化为 Integer code）。
    // statusName ← status.getName()：MapStruct 不会自动调用 getName()，需显式配置。
    @Override
    @Mapping(target = "statusName", source = "status.name")
    AppResponse convert(RegisteredApp app);
}
