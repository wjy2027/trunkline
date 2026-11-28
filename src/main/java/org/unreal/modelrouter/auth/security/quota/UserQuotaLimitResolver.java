/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.auth.security.quota;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 用户维度额度解析器（按 {@code ApiKey.holder} 跨 Key 归集）。
 *
 * <p>限额配置走 {@code config_data} 的 {@code quota.scope.users} 键；热路径由
 * {@link ScopeQuotaStore} 的 Caffeine 缓存命中，零 DB 读。以 {@code @Qualifier("user")}
 * 注入执行层，与既有 group / model 解析器同构。</p>
 *
 * @author Trunkline Team
 */
@Component
@Qualifier("user")
public final class UserQuotaLimitResolver implements QuotaLimitResolver {

    private final ScopeQuotaStore store;

    public UserQuotaLimitResolver(final ScopeQuotaStore store) {
        this.store = store;
    }

    @Override
    public Optional<QuotaLimitSet> resolve(final String holder) {
        return store.resolve(ScopeQuotaStore.USERS_KEY, holder);
    }
}
