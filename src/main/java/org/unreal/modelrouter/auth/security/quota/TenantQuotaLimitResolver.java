/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.auth.security.quota;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 租户维度额度解析器（按业务归属单元归集：产品线 / 项目组 / 虚拟团队等）。
 *
 * <p>限额配置走 {@code config_data} 的 {@code quota.scope.tenants} 键（scopeId 为 tenantId），
 * 热路径走 {@link ScopeQuotaStore} 缓存。租户维只在 Key 绑定非 default 租户时参与判定，
 * 该门控由执行层 {@code QuotaEnforcementService.tryReserve} 控制。以
 * {@code @Qualifier("tenant")} 注入执行层。</p>
 *
 * @author Trunkline Team
 */
@Component
@Qualifier("tenant")
public final class TenantQuotaLimitResolver implements QuotaLimitResolver {

    private final ScopeQuotaStore store;

    public TenantQuotaLimitResolver(final ScopeQuotaStore store) {
        this.store = store;
    }

    @Override
    public Optional<QuotaLimitSet> resolve(final String tenantId) {
        return store.resolve(ScopeQuotaStore.TENANTS_KEY, tenantId);
    }
}
