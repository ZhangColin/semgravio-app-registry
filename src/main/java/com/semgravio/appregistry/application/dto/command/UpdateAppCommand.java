package com.semgravio.appregistry.application.dto.command;

import com.semgravio.appregistry.domain.app.aggregate.RegisteredApp;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 更新应用命令。
 *
 * @param name        新应用名
 * @param description 新描述（可空）
 */
public record UpdateAppCommand(

        @NotBlank(message = "应用名称不能为空")
        @Size(max = RegisteredApp.NAME_MAX_LENGTH, message = "应用名称长度不能超过 128")
        String name,

        @Size(max = RegisteredApp.DESCRIPTION_MAX_LENGTH, message = "应用描述长度不能超过 512")
        String description
) {
}
