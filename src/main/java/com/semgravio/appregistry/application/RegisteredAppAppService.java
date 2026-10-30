package com.semgravio.appregistry.application;

import com.semgravio.appregistry.application.dto.command.CreateAppCommand;
import com.semgravio.appregistry.application.dto.command.UpdateAppCommand;
import com.semgravio.appregistry.application.dto.query.AppQuery;
import com.semgravio.appregistry.application.dto.response.AppResponse;
import com.semgravio.appregistry.application.mapper.RegisteredAppMapper;
import com.semgravio.appregistry.domain.app.aggregate.RegisteredApp;
import com.semgravio.appregistry.domain.app.repository.RegisteredAppRepository;
import com.semgravio.appregistry.domain.error.AppRegistryMessage;
import com.cartisan.core.exception.BaseCodeMessage;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.util.Assertions;
import com.cartisan.data.jpa.specification.ConditionSpecifications;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应用登记应用服务——应用的创建 / 查询 / 更新 / 禁用 / 启用。
 *
 * <p>撞名主路径：应用层 {@code existsByAppCode}（看含软删全行）先查 + 抛 {@link DomainException}(409)；
 * DB 普通唯一约束作并发兜底（{@code DuplicateKeyException → 409}）。</p>
 *
 * @since 0.1.0
 */
@Service
public class RegisteredAppAppService {

    /** 列表默认排序（不传 sort 时）：createdAt 降序——分页无 ORDER BY 窗口不稳定，admin BFF 不传 sort 依赖此默认。 */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final RegisteredAppRepository appRepository;
    private final RegisteredAppMapper appMapper;

    public RegisteredAppAppService(RegisteredAppRepository appRepository, RegisteredAppMapper appMapper) {
        this.appRepository = appRepository;
        this.appMapper = appMapper;
    }

    /**
     * 创建应用。
     *
     * <p>app_code 撞名（含软删行）返 409。创建成功后 flush 以回填审计时间戳。</p>
     */
    @Transactional
    public AppResponse create(CreateAppCommand command) {
        Assertions.require(
                !appRepository.existsByAppCode(command.appCode()),
                BaseCodeMessage.DUPLICATE, command.appCode());

        RegisteredApp app = RegisteredApp.create(command.appCode(), command.name(), command.description());
        appRepository.saveAndFlush(app);
        return appMapper.convert(app);
    }

    /**
     * 查询应用详情。不存在返 404。
     */
    @Transactional(readOnly = true)
    public AppResponse findById(Long id) {
        return appMapper.convert(loadApp(id));
    }

    /**
     * 禁用应用。重复禁用返 409。admin-console 不可禁用。
     */
    @Transactional
    public AppResponse disable(Long id) {
        RegisteredApp app = loadApp(id);
        if (PlatformSeedAppService.PLATFORM_APP_CODE.equals(app.getAppCode())) {
            throw new DomainException(AppRegistryMessage.ADMIN_CONSOLE_CANNOT_DISABLE, app.getAppCode());
        }
        app.disable();
        appRepository.saveAndFlush(app);
        return appMapper.convert(app);
    }

    /**
     * 启用应用。重复启用返 409。
     */
    @Transactional
    public AppResponse enable(Long id) {
        RegisteredApp app = loadApp(id);
        app.enable();
        appRepository.saveAndFlush(app);
        return appMapper.convert(app);
    }

    /**
     * 更新应用基本信息（name、description）。已禁用应用仍可修改。
     */
    @Transactional
    public AppResponse update(Long id, UpdateAppCommand command) {
        RegisteredApp app = loadApp(id);
        app.update(command.name(), command.description());
        appRepository.saveAndFlush(app);
        return appMapper.convert(app);
    }

    /**
     * 分页查询应用列表。支持 keyword（appCode + name 模糊）和 status 筛选。
     *
     * <p>分页走框架 {@link Pagination} 契约（#24 起全链 1-based）：wire page/size/sort 由框架绑定 +
     * clamp（page≥1、size∈[1,100] 默认 20），本方法零页码算术；<b>不传 sort 时默认 createdAt 降序</b>
     *（分页无 ORDER BY 窗口不稳定，且 admin BFF 不传 sort 依赖此默认）。
     * 回显经 {@link PageResponse#of(Page)} 集中做 1-based（替代原手写 {@code +1}）。
     * 请求页超出尾页 → 空页（200，请求页码原样回显）。</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<AppResponse> list(AppQuery query, Pagination pagination) {
        PageRequest pageRequest = pagination.toPageRequest();
        if (pageRequest.getSort().isUnsorted()) {
            pageRequest = PageRequest.of(pageRequest.getPageNumber(), pageRequest.getPageSize(), DEFAULT_SORT);
        }
        Page<RegisteredApp> page = appRepository.findAll(
                ConditionSpecifications.fromAnnotation(query), pageRequest);
        return PageResponse.of(page.map(appMapper::convert));
    }

    private RegisteredApp loadApp(Long id) {
        return appRepository.findById(id)
                .orElseThrow(() -> new DomainException(BaseCodeMessage.RESOURCE_NOT_FOUND, id));
    }
}
