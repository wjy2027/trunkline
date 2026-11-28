/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.persistence.jpa.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GlobalConfigKeys} 判定规则单测。
 *
 * <p>守住的边界：<b>不能把必须隔离的配置错判成全局</b>。虚拟模型池与路由规则一旦被判成全局，
 * 各租户会互相看到、覆盖对方的模型与分流策略——这是跨租户数据泄露，比"切租户看不见配置"
 * 严重得多。因此这里对隔离键做反向断言。</p>
 */
class GlobalConfigKeysTest {

    @Test
    @DisplayName("console.* 扩展配置 → 全局共享")
    void consoleKeysAreGlobal() {
        assertTrue(GlobalConfigKeys.isGlobal("console.prompt-templates"));
        assertTrue(GlobalConfigKeys.isGlobal("console.api-key-requests"));
        assertTrue(GlobalConfigKeys.isGlobal("console.group-role-mappings"));
        assertTrue(GlobalConfigKeys.isGlobal("console.dept-registry"));
        assertTrue(GlobalConfigKeys.isGlobal("console.adapter-lifecycle"));
    }

    @Test
    @DisplayName("组织维额度配置 → 全局共享（额度定义是平台级策略）")
    void orgScopeQuotaKeysAreGlobal() {
        assertTrue(GlobalConfigKeys.isGlobal("quota.scope.users"));
        assertTrue(GlobalConfigKeys.isGlobal("quota.scope.departments"));
        assertTrue(GlobalConfigKeys.isGlobal("quota.scope.tenants"));
    }

    @Test
    @DisplayName("console.* 前缀未来新增的功能自动归入全局，无需改常量")
    void newConsolePrefixKeyIsGlobalByPrefix() {
        assertTrue(GlobalConfigKeys.isGlobal("console.some-future-feature"),
                "console. 命名空间都是平台级扩展能力，默认全局");
    }

    @Test
    @DisplayName("虚拟模型池 / 路由规则 → 必须按租户隔离（错判为全局即跨租户泄露）")
    void tenantScopedKeysAreNotGlobal() {
        assertFalse(GlobalConfigKeys.isGlobal("pool_definitions"),
                "池必须隔离：A 租户的模型名不能被 B 租户看见或覆盖");
        assertFalse(GlobalConfigKeys.isGlobal("rule_definitions"),
                "路由规则必须隔离：A 租户的分流策略不能作用于 B 租户");
    }

    @Test
    @DisplayName("未登记的键默认按租户隔离（fail-closed，宁可看不见也不越权）")
    void unknownKeysDefaultToTenantScoped() {
        assertFalse(GlobalConfigKeys.isGlobal("some.unknown.key"),
                "未登记 = 按租户隔离；若确为全局，必须显式加入白名单");
        assertFalse(GlobalConfigKeys.isGlobal(null));
        assertFalse(GlobalConfigKeys.isGlobal(""));
        assertFalse(GlobalConfigKeys.isGlobal("   "));
    }

    @Test
    @DisplayName("前缀白名单不能误伤隔离键：console. 前缀与隔离名单不冲突")
    void prefixesDoNotSwallowTenantScopedKeys() {
        for (final String scoped : GlobalConfigKeys.TENANT_SCOPED_KEYS) {
            assertFalse(scoped.startsWith("console."),
                    "隔离键不得落在 console. 前缀下，否则会被前缀白名单误判为全局：" + scoped);
        }
    }
}