/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.persistence.jpa.entity;

import java.util.Set;
import org.unreal.modelrouter.enterprise.config.TenantJpaFilterDefinition;

/**
 * 全局共享配置键登记表。
 *
 * <p><b>为什么需要这张名单</b>：{@code config_data} 是通用 KV 表，同时承载两类语义完全相反
 * 的配置。行级过滤器按实体生效，无法按行区分，因此需要一份显式的「哪些键是全局的」清单，
 * 让写入侧把全局配置统一落到哨兵租户
 * {@link TenantJpaFilterDefinition#SHARED_TENANT_ID}，读取侧再通过
 * {@code tenant_id in (:tenantIds)} 让每个租户都能看到它。</p>
 *
 * <p><b>两类配置的判定标准</b>（不是凭感觉，按语义判断）：
 * <ul>
 *   <li><b>按租户隔离</b>——配置描述"这个租户自己怎么干活"，各租户取值本就不同，
 *       互相看见即越权。如虚拟模型池（各租户自建模型名）、路由规则（各租户自己的分流策略）。</li>
 *   <li><b>全局共享</b>——配置是"东西本身的定义/字典/审批流"，全平台只有一份，
 *       各租户看到的必须一致。如模型分类（文本/音频/图像是协议事实，不是租户偏好）、
 *       提示词模板、Key 审批单、组-角色映射、适配器生命周期。</li>
 * </ul></p>
 *
 * <p><b>维护约定</b>：新增走 {@code config_data} 的管理功能时，必须在此二选一登记，
 * 不允许"默认归某一类"——漏登记会静默落进当前租户，表现为"切租户后配置消失"。
 * 单测 {@code GlobalConfigKeysTest} 会校验前缀白名单与隔离名单不重叠。</p>
 *
 * @author Trunkline Team
 */
public final class GlobalConfigKeys {

    /**
     * 全局共享配置键的完整名单（精确匹配）。
     *
     * <p>均为全部署唯一、与租户无关的字典 / 模板 / 审批流数据。</p>
     */
    private static final Set<String> GLOBAL_EXACT = Set.of(
            // 提示词模板：全平台共用一套，租户只选用不改定义
            "console.prompt-templates",
            // Key 审批单：审批是平台方动作，申请单必须在同一张队列里对所有租户可见
            "console.api-key-requests",
            // 组-角色映射：组织架构来自同一套 SSO / 目录服务
            "console.group-role-mappings",
            "console.dept-registry",
            // 适配器生命周期：适配器是进程级资产，运行状态全平台一份
            "console.adapter-lifecycle",
            // 组织维额度（用户/部门/租户）：额度定义是平台级策略，各租户看到同一份限额表；
            // 用量仍按各自 dimension 的租户哨兵记账，配置与用量是两个维度
            "quota.scope.users",
            "quota.scope.departments",
            "quota.scope.tenants"
    );

    /**
     * 全局共享配置键的前缀白名单。
     *
     * <p>按前缀匹配，用于覆盖 {@code console.*} 这类"扩展功能统一命名空间"，
     * 避免每新增一个 console 功能都要改常量。任何落在 {@code console.} 下的键
     * 都视为控制台扩展配置——按设计它们都是平台级能力，不随租户切分。</p>
     */
    private static final Set<String> GLOBAL_PREFIXES = Set.of("console.");

    /**
     * 明确按租户隔离的配置键（仅用于自检/文档化，不参与判定）。
     *
     * <p>列在这里是为了让"哪些必须隔离"也是显式声明，防止后人把 {@code console.}
     * 前缀白名单扩大化时误伤。单测断言这些键<b>不</b>命中全局规则。</p>
     */
    static final Set<String> TENANT_SCOPED_KEYS = Set.of(
            // 虚拟模型池：各租户自建，模型名可能重名，必须隔离
            "pool_definitions",
            // 路由规则：各租户自己的分流策略，必须隔离
            "rule_definitions"
    );

    private GlobalConfigKeys() {
    }

    /**
     * 判断配置键是否为全局共享。
     *
     * @param configKey 配置键（可为 null）
     * @return true = 写入哨兵租户、所有租户可见；false = 按当前租户隔离
     */
    public static boolean isGlobal(final String configKey) {
        if (configKey == null || configKey.isBlank()) {
            return false;
        }
        if (TENANT_SCOPED_KEYS.contains(configKey)) {
            return false;
        }
        if (GLOBAL_EXACT.contains(configKey)) {
            return true;
        }
        for (final String prefix : GLOBAL_PREFIXES) {
            if (configKey.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}