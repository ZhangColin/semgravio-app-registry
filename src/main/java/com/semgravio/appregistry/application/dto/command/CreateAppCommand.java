package com.semgravio.appregistry.application.dto.command;

import com.semgravio.appregistry.domain.app.aggregate.RegisteredApp;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建应用命令。
 *
 * @param appCode     稳定公开 slug，创建后不可修改（4-64 位小写字母/数字/连字符，不以连字符开头或结尾）
 * @param name        应用名
 * @param description 描述（可空）
 */
public record CreateAppCommand(

        @NotBlank(message = "应用编码不能为空")
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{2,62}[a-z0-9]$", message = "应用编码须为 4-64 位小写字母、数字、连字符，且不以连字符开头或结尾")
        String appCode,

        @NotBlank(message = "应用名称不能为空")
        @Size(max = RegisteredApp.NAME_MAX_LENGTH, message = "应用名称长度不能超过 128")
        String name,

        @Size(max = RegisteredApp.DESCRIPTION_MAX_LENGTH, message = "应用描述长度不能超过 512")
        String description
) {
}
