/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.auth.security.quota;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 部门维度额度解析器（按 holder 所属行政部门归集）。
 *
 * <p>限额配置走 {@code config_data} 的 {@code quota.scope.departments} 键（scopeId 为部门名），
 * 热路径走 {@link ScopeQuotaStore} 缓存。holder→department 的解析在认证时机完成并写入
 * 请求属性（见 ServiceRequestHandler），本解析器只按部门名取限额。以
 * {@code @Qualifier("department")} 注入执行层。</p>
 *
 * @author Trunkline Team
 */
@Component
@Qualifier("department")
public final class DepartmentQuotaLimitResolver implements QuotaLimitResolver {

    private final ScopeQuotaStore store;

    public DepartmentQuotaLimitResolver(final ScopeQuotaStore store) {
        this.store = store;
    }

    @Override
    public Optional<QuotaLimitSet> resolve(final String department) {
        return store.resolve(ScopeQuotaStore.DEPARTMENTS_KEY, department);
    }
}
