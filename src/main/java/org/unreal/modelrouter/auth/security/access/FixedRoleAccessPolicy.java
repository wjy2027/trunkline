/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.access;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 固定角色访问矩阵（社区版权权固定化）——权限唯一权威源。
 *
 * <p><b>设计决策（2026-11-04 用户决策）</b>：社区版不做可配置 RBAC。权限写死为
 * 「角色 × 菜单分组」固定矩阵，与前端菜单（{@code frontend/src/config/menu.ts}）严格对应：</p>
 * <ul>
 *   <li><b>USER（员工）</b>＝「我的」分组：我的额度 / 我的密钥 / 快速上手 / 测试场 / 我的账单，
 *       对应各类 {@code /mine} 自助端点 + 网关信息 + 测试场代理面；</li>
 *   <li><b>OPERATOR（运维）</b>＝「我的」+「团队」「策略」分组：技术总览 / 团队用量 /
 *       接入管理 / 虚拟模型 / 全部密钥 / 调用观测 / 链路追踪 / 状态监控 / 策略中心；</li>
 *   <li><b>ADMIN（管理员）</b>＝全部（含「系统」分组与未登记端点的兜底）。</li>
 * </ul>
 *
 * <p><b>规则顺序即匹配顺序</b>（首条命中优先）：更具体的路径（{@code /mine}、
 * {@code /billing/my/recent} 等）必须登记在更宽泛的通配模式之前。</p>
 *
 * <p><b>未登记端点</b>维持 fail-closed（仅 ADMIN 直通），豁免清单见
 * {@link AccessExemptEndpoints}——与替换前的 DENY_ALL 姿态一致，本次改造不放宽任何默认边界。</p>
 *
 * <p><b>已修正的存量不一致</b>（运维实际 403，但菜单承诺可见）：</p>
 * <ul>
 *   <li>「调用观测」（{@code GET /api/call-history/**}）：旧码 {@code callhistory:view}
 *       不在运维模板（不以 :read/:write 结尾）→ 现登记为 OPS；</li>
 *   <li>「全部密钥」（{@code /api/auth/api-keys/**}）：旧码 {@code security:apikeys:manage}
 *       被运维模板排除 → 现登记为 OPS；2026-11-09 又发现控制器方法级
 *       {@code @PreAuthorize} 仍要求 ADMIN，已同步改为 OPERATOR+ADMIN；</li>
 *   <li>「团队用量」（{@code GET /api/billing/team}、{@code /departments}）：方法级注解已
 *       允许 OPERATOR，URL 规则却漏登记而落入 fail-closed → 现登记为 OPS。</li>
 * </ul>
 *
 * @author Trunkline Team
 * @modified Trunkline Team
 */
@Component
public class FixedRoleAccessPolicy {

    private final List<AccessRule> rules = buildRules();

    /**
     * 查找匹配指定方法与路径的访问级别规则。
     *
     * @param method 请求方法
     * @param path   请求路径
     * @return 命中的规则（无匹配返回 empty，调用方按未登记姿态处理）
     */
    public Optional<AccessRule> findRule(final HttpMethod method, final String path) {
        for (final AccessRule rule : rules) {
            if (rule.matches(method, path)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    /** 全部登记规则（只读，供测试与审计逐条断言） */
    public List<AccessRule> getRules() {
        return rules;
    }

    private static List<AccessRule> buildRules() {
        return List.of(
                // ===== 系统管理（ADMIN） =====
                AccessRule.any("/api/security/jwt/accounts/**", AccessLevel.ADMIN),
                AccessRule.any("/api/security/blacklist/**", AccessLevel.ADMIN),

                // ===== 员工自助（SELF，「我的」分组） =====
                // 「我的调用记录」+ 导出（任务 58）：归属由服务端从 principal 强制解析，
                // 必须登记在 /api/call-history/** 通配规则之前（首条命中优先）。
                AccessRule.get("/api/call-history/mine", AccessLevel.SELF),
                AccessRule.get("/api/call-history/mine/export", AccessLevel.SELF),
                // 调用观测（团队分组）：旧码 callhistory:view 不在运维模板导致运维 403 的存量
                // 不一致，此处按菜单承诺登记为 OPS（见类注释）。
                AccessRule.get("/api/call-history/**", AccessLevel.OPS),
                // 调用历史配置（全局配置 tab）：读 OPS / 写 OPS
                AccessRule.get("/api/config/call-history/**", AccessLevel.OPS),
                AccessRule.write("/api/config/call-history/**", AccessLevel.OPS),

                // 追踪安全配置（ADMIN）
                AccessRule.any("/api/config/tracing/security/**", AccessLevel.ADMIN),

                // ===== 团队/策略分组（OPS） =====
                // 服务子路径规则（前端 service.ts 仍在使用）
                AccessRule.get("/api/services/*/circuitbreaker/**", AccessLevel.OPS),
                AccessRule.write("/api/services/*/circuitbreaker/**", AccessLevel.OPS),
                AccessRule.get("/api/services/*/ratelimit/**", AccessLevel.OPS),
                AccessRule.write("/api/services/*/ratelimit/**", AccessLevel.OPS),

                // 实例配置（接入管理/虚拟模型）
                AccessRule.get("/api/config/instance/**", AccessLevel.OPS),
                AccessRule.write("/api/config/instance/**", AccessLevel.OPS),
                // 员工自助：实例清单（脱敏后，不含上游密钥）。注意路径是 /api/instance/mine
                // （不是 /api/config/instance/mine），且必须先于上面的通配语义思考——
                // 两者前缀不同无冲突，这里登记在 providers 之前保持与旧注册表一致的顺序。
                AccessRule.get("/api/instance/mine", AccessLevel.SELF),

                // 模型供应商 / 价格 / 服务类型（接入管理）
                AccessRule.get("/api/config/providers/**", AccessLevel.OPS),
                AccessRule.write("/api/config/providers/**", AccessLevel.OPS),
                // B4 / 方案 3.2 #10：单价即钱。读仍对运维可见（「价格」tab），写收紧到 ADMIN。
                // 首条命中优先：GET 与 write 的方法集合互斥，互不遮蔽；本条须留在任何更宽泛写规则之前。
                AccessRule.get("/api/config/prices/**", AccessLevel.OPS),
                AccessRule.write("/api/config/prices/**", AccessLevel.ADMIN),
                // v4.1 高级计价规则（企业版）：prices 与 pricing 前缀不同，必须独立登记。
                // 读对运维可见、写收紧 ADMIN；OSS 只留权限机制，付费规则 UI 全在企业子应用。
                AccessRule.get("/api/config/pricing/**", AccessLevel.OPS),
                AccessRule.write("/api/config/pricing/**", AccessLevel.ADMIN),
                // 2026-11-28 高级计价热配置开关（全局配置 → 其他）：pricing-advanced 与 pricing
                // 是不同路径段，Ant 通配不覆盖，须独立登记。读 OPS、写 ADMIN（热切换影响计费口径）。
                AccessRule.get("/api/config/pricing-advanced/**", AccessLevel.OPS),
                AccessRule.write("/api/config/pricing-advanced/**", AccessLevel.ADMIN),
                AccessRule.get("/api/config/type/**", AccessLevel.OPS),
                AccessRule.write("/api/config/type/**", AccessLevel.OPS),

                // 熔断器 / 适配器 / 规则 / 资源池配置
                AccessRule.get("/api/config/circuit-breaker/**", AccessLevel.OPS),
                AccessRule.write("/api/config/circuit-breaker/**", AccessLevel.OPS),
                AccessRule.get("/api/config/adapter/**", AccessLevel.OPS),
                AccessRule.write("/api/config/adapter/**", AccessLevel.OPS),
                // 提示词模板配置（PromptTemplateController）：2026-11-22 整改前未登记 URL 规则，
                // OPERATOR 在 URL 层即被 fail-closed 403。删除方法级 @PreAuthorize 后由本条兜底。
                AccessRule.any("/api/config/prompt-templates/**", AccessLevel.OPS),
                AccessRule.get("/api/config/rules/**", AccessLevel.OPS),
                AccessRule.write("/api/config/rules/**", AccessLevel.OPS),
                AccessRule.get("/api/config/pools/**", AccessLevel.OPS),
                AccessRule.write("/api/config/pools/**", AccessLevel.OPS),
                // 模型能力档案（v0.2.0，ModelCapabilityController）：静态画像只读查询。
                // 未登记时 OPERATOR 在 URL 层 fail-closed 403，故显式登记为 OPS。
                AccessRule.get("/api/config/model-capabilities/**", AccessLevel.OPS),
                AccessRule.write("/api/config/model-capabilities/**", AccessLevel.OPS),
                // 一键虚拟模型（v0.2.0，ScenarioTemplateController）：建池/建规则均为 OPS 动作。
                AccessRule.get("/api/config/scenarios/**", AccessLevel.OPS),
                AccessRule.write("/api/config/scenarios/**", AccessLevel.OPS),
                // 数据分级出境（#9）：密级字典/出境策略只读给 OPS，策略写与地域确认收紧到 ADMIN
                // （地域确认即合规裁决，仿价格 write=ADMIN）；未登记时 fail-closed 仅 ADMIN。
                AccessRule.any("/api/config/data-classification/**", AccessLevel.OPS),
                AccessRule.get("/api/config/egress-policies/**", AccessLevel.OPS),
                AccessRule.write("/api/config/egress-policies/**", AccessLevel.ADMIN),
                AccessRule.get("/api/config/data-residency/**", AccessLevel.OPS),
                AccessRule.write("/api/config/data-residency/**", AccessLevel.ADMIN),

                // #10 被遗忘权擦除：本人擦自己=SELF（归属由服务端从 principal 强制解析）；
                // 擦整租户=ADMIN（高危，须平台授权）。两条必须精确优先于任何通配规则。
                AccessRule.any("/api/privacy/erasure/mine", AccessLevel.SELF),
                AccessRule.any("/api/privacy/erasure/tenant", AccessLevel.ADMIN),

                // 监控（Token 用量 / 熔断监控 / 限流监控 / 路由监控 / 指标）
                AccessRule.get("/api/token-usage/**", AccessLevel.OPS),
                AccessRule.get("/api/v1/circuit-breaker-monitor/**", AccessLevel.OPS),
                AccessRule.write("/api/v1/circuit-breaker-monitor/**", AccessLevel.OPS),
                AccessRule.any("/api/rate-limiter/**", AccessLevel.OPS),
                AccessRule.get("/api/v1/routing-monitor/**", AccessLevel.OPS),
                AccessRule.write("/api/v1/routing-monitor/**", AccessLevel.OPS),
                AccessRule.get("/api/monitoring/metrics/**", AccessLevel.OPS),

                // 负载均衡
                AccessRule.get("/api/loadbalancer/**", AccessLevel.OPS),
                AccessRule.write("/api/loadbalancer/**", AccessLevel.OPS),

                // 响应缓存配置
                AccessRule.get("/api/config/cache/response", AccessLevel.OPS),
                AccessRule.write("/api/config/cache/**", AccessLevel.OPS),

                // 网关运行形态（快速上手去硬编码）：只返回当前访问地址与部署形态，无敏感信息
                AccessRule.get("/api/gateway/info", AccessLevel.SELF),

                // 企业能力探测（O6）：前端特性开关唯一数据源，任意已登录角色按能力显隐企业 UI；
                // 响应仅含能力键值对（社区版恒为 {}），无敏感信息。必须显式登记为 SELF：
                // 未登记端点 fail-closed 仅 ADMIN，普通用户/运维会被挡在特性探测之外。
                AccessRule.get("/api/v1/enterprise/features", AccessLevel.SELF),
                // 「我的可见租户」（P2）：前端每个请求都要据此注入 X-Tenant-Id，
                // 必须登记为 SELF——否则未登记落 fail-closed 仅 ADMIN，USER/AUDITOR
                // 拿不到自己的租户集，又会回到「切租户账单不显示」的 bug。身份收窄在
                // 企业侧 VisibleTenantService 内做，URL 层只保证"已登录可读自己的可见集"。
                AccessRule.get("/api/v1/enterprise/tenants/mine", AccessLevel.SELF),
                // 平台级租户管理（A 口径收紧）：开通/停用租户是平台级最高危操作，仅全局作用域
                // ADMIN（{t:"*",r:"ADMIN"} 或旧格式 ["ADMIN"]）可执行。租户级 {t:"t1",r:"ADMIN"}
                // 经 allRoles() 也持有扁平 ROLE_ADMIN，由 FixedRoleAuthorizationManager 的
                // PLATFORM 专门分支读 tenantRoles claim 拒绝（403），不会因落 fail-closed 兜底而放行。
                // 首条命中优先：上面的 /tenants/mine SELF 必须排在本通配规则之前，不能被吞掉。
                AccessRule.any("/api/v1/enterprise/tenants", AccessLevel.PLATFORM),
                AccessRule.any("/api/v1/enterprise/tenants/*", AccessLevel.PLATFORM),
                // 企业用量总览（P1）：只读跨租户汇总，允许平台超管 + 审计员。必须显式登记——
                // 未登记会落 fail-closed 仅 ADMIN，AUDITOR 在 URL 层就被 403 到不了 Controller；
                // 也不能登记为 SELF/OPS，否则 USER/OPERATOR 越权读到跨租户金额。不进豁免清单：
                // 豁免是「已认证即放行」，会把判定后移到 Controller，破坏 fail-closed 默认边界。
                AccessRule.get("/api/v1/enterprise/usage/overview", AccessLevel.AUDIT),

                // API Key 管理：自助端点在前（首条命中优先）
                AccessRule.get("/api/auth/api-keys/mine", AccessLevel.SELF),
                // 员工取回自己的密钥明文（「我的密钥」页复制，2026-11 用户决策）：
                // 归属校验在服务层（holder/createdBy 必须等于当前登录名），此处仅要求登录
                AccessRule.any("/api/auth/api-keys/mine/*/reveal", AccessLevel.SELF),
                // 「全部密钥」菜单对运维可见 → OPS（修正存量 security:apikeys:manage 仅 ADMIN 的不一致）
                AccessRule.any("/api/auth/api-keys/**", AccessLevel.OPS),

                // 密钥申请（ApiKeyApprovalController）：2026-11-22 整改前未登记，USER 提交
                // 申请/查自己申请在 URL 层就被 403。注意 api-keys/** ≠ api-key-requests。
                // 首条命中优先：SELF 两条精确规则必须在 OPS 通配之前；decide 同为 POST，
                // 靠「/mine + 根路径精确 SELF在前、/** OPS 在后」区分，切勿把 write 写宽到 /**，
                // 否则 USER 可越权审批。
                AccessRule.write("/api/auth/api-key-requests", AccessLevel.SELF),
                AccessRule.get("/api/auth/api-key-requests/mine", AccessLevel.SELF),
                AccessRule.any("/api/auth/api-key-requests/**", AccessLevel.OPS),

                // 员工自助：账单/额度（我的额度 / 我的账单）
                AccessRule.get("/api/billing/my/recent", AccessLevel.SELF),
                // 逐笔账单明细下钻：精确 SELF 规则须先于 /api/billing/my 登记，避免被遮蔽。
                AccessRule.get("/api/billing/my/detail", AccessLevel.SELF),
                AccessRule.get("/api/billing/my/ledger/detail", AccessLevel.SELF),
                AccessRule.get("/api/billing/my", AccessLevel.SELF),
                // 团队用量 / 部门下拉：菜单「团队用量」对 ops 可见、BillingController 方法级
                // 也允许 OPERATOR+ADMIN，但此前漏登记 URL 规则 → 未登记端点 fail-closed 仅 ADMIN，
                // 运维打开页面必 403。此处补齐 OPS，与方法级注解、菜单三方对齐（2026-11-09）。
                AccessRule.get("/api/billing/team", AccessLevel.OPS),
                AccessRule.get("/api/billing/departments", AccessLevel.OPS),
                // 管理员跨员工逐笔明细 + 企业账本对照（与 /team 同口径 OPS）
                AccessRule.get("/api/billing/detail", AccessLevel.OPS),
                AccessRule.get("/api/billing/ledger/detail", AccessLevel.OPS),

                // 安全审计扩展（后端维持与旧矩阵一致的 OPS 有效权限；菜单仅管理员可见）
                AccessRule.get("/api/security/audit/extended/**", AccessLevel.OPS),
                AccessRule.write("/api/security/audit/extended/**", AccessLevel.OPS),

                // 组织角色映射（GroupRoleMappingController）：2026-11-22 整改前未登记，USER
                // 查本部门角色在 URL 层就被 403。rolesFor 精确 SELF 在前，其余 OPS 在后。
                AccessRule.get("/api/security/group-roles/mappings/*/roles", AccessLevel.SELF),
                AccessRule.any("/api/security/group-roles/**", AccessLevel.OPS),

                // 慢查询 / 模型统计
                AccessRule.get("/api/monitoring/slow-queries/**", AccessLevel.OPS),
                AccessRule.get("/api/models/stats/**", AccessLevel.OPS),

                // 配置版本 / 状态持久化
                AccessRule.get("/api/config/version/**", AccessLevel.OPS),
                AccessRule.write("/api/config/version/**", AccessLevel.OPS),
                AccessRule.get("/api/state-persistence/**", AccessLevel.OPS),
                AccessRule.write("/api/state-persistence/**", AccessLevel.OPS),

                // 追踪：读 OPS，配置写 ADMIN（旧码 tracing:config:manage 不在运维模板）
                AccessRule.get("/api/tracing/actuator/**", AccessLevel.OPS),
                AccessRule.write("/api/tracing/actuator/**", AccessLevel.ADMIN),
                AccessRule.get("/api/tracing/performance/**", AccessLevel.OPS),
                AccessRule.write("/api/tracing/performance/**", AccessLevel.ADMIN),
                AccessRule.get("/api/tracing/query/**", AccessLevel.OPS),
                AccessRule.write("/api/tracing/query/**", AccessLevel.ADMIN),

                // 内置帮助文档正文（DocsController）：【2026-11-10 用户决策】只对运维/管理员开放。
                // 普通用户的上手路径是「接入指南」(/guide) 与「快速上手」(/quickstart)，不进帮助中心。
                // 必须显式登记为 OPS：未登记端点 fail-closed 仅 ADMIN，会把 OPERATOR 也挡在门外。
                AccessRule.get("/api/docs/content", AccessLevel.OPS),

                // 概览仪表盘（技术总览）
                AccessRule.get("/api/dashboard/**", AccessLevel.OPS),

                // 配额配置 / 配额监控
                AccessRule.get("/api/config/quota/**", AccessLevel.OPS),
                AccessRule.write("/api/config/quota/**", AccessLevel.OPS),
                // 组织维额度（用户/部门/租户）：独立前缀，不落在 /api/config/quota/** 通配下
                // （后者含 ModelQuotaController 的 PUT/DELETE /{modelName}，子路径会被吞）。
                // 读 OPS / 写 ADMIN：额度定义是平台级策略，收紧写入到 ADMIN。
                AccessRule.get("/api/config/quota-scope/**", AccessLevel.OPS),
                AccessRule.write("/api/config/quota-scope/**", AccessLevel.ADMIN),
                // 企业组织维金额额度：独立前缀（quota-amount），不与 quota/** 通配重叠
                AccessRule.get("/api/config/quota-amount/**", AccessLevel.OPS),
                AccessRule.write("/api/config/quota-amount/**", AccessLevel.ADMIN),
                // 员工自助：我的密钥「本月已用」（服务端强制校验 keyId 属主），
                // 必须先于 quota/** 的 OPS 通配规则命中（首条命中优先）
                AccessRule.get("/api/monitoring/quota/mine/usage", AccessLevel.SELF),
                AccessRule.get("/api/monitoring/quota/**", AccessLevel.OPS),

                // 监控配置/运维端点：必须登记在 metrics/slow-queries/quota 的具体 GET 之后（首条命中优先）
                AccessRule.get("/api/monitoring/**", AccessLevel.OPS),
                AccessRule.write("/api/monitoring/**", AccessLevel.OPS),

                // 异常管理
                AccessRule.get("/api/exceptions/**", AccessLevel.OPS),
                AccessRule.write("/api/exceptions/**", AccessLevel.OPS),

                // 配置校验/来源指南
                AccessRule.get("/api/config/sources", AccessLevel.OPS),
                AccessRule.get("/api/config/environment-variables", AccessLevel.OPS),
                AccessRule.get("/api/config/validation-rules", AccessLevel.OPS),

                // PII / 脱敏配置管理（ADMIN）
                AccessRule.any("/api/config/sanitization/**", AccessLevel.ADMIN),

                // JWT 令牌运维端点（ADMIN；自助 refresh/revoke/tokens 走豁免清单 + 方法级 @PreAuthorize）
                AccessRule.get("/api/auth/jwt/blacklist/stats", AccessLevel.ADMIN),
                AccessRule.get("/api/auth/jwt/cleanup/stats", AccessLevel.ADMIN),
                AccessRule.write("/api/auth/jwt/cleanup", AccessLevel.ADMIN),
                AccessRule.write("/api/auth/jwt/revoke/batch", AccessLevel.ADMIN),
                AccessRule.get("/api/auth/jwt/tokens/{tokenId}", AccessLevel.ADMIN)
        );
    }
}
