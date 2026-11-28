package org.unreal.modelrouter.common.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 审计事件类型枚举
 */
public enum AuditEventType {
    // JWT令牌相关事件
    JWT_TOKEN_ISSUED,       // JWT令牌颁发
    JWT_TOKEN_REFRESHED,    // JWT令牌刷新
    JWT_TOKEN_REVOKED,      // JWT令牌撤销
    JWT_TOKEN_VALIDATED,    // JWT令牌验证
    JWT_TOKEN_EXPIRED,      // JWT令牌过期
    
    // API Key相关事件
    API_KEY_CREATED,        // API Key创建
    API_KEY_USED,           // API Key使用
    API_KEY_REVOKED,        // API Key撤销
    API_KEY_EXPIRED,        // API Key过期
    API_KEY_UPDATED,        // API Key更新
    
    // 安全事件
    SECURITY_ALERT,         // 安全告警
    SUSPICIOUS_ACTIVITY,    // 可疑活动
    AUTHENTICATION_FAILED,  // 认证失败
    AUTHORIZATION_FAILED,   // 授权失败
    DATA_SANITIZATION,      // 数据脱敏处理
    
    // 数据主体权利事件
    DATA_ERASURE,           // 被遗忘权擦除（#10，擦除事实不可篡改留痕）

    // 账号生命周期事件（身份全生命周期举证）
    ACCOUNT_PROVISIONED,    // 账号开通（SSO 自动开通等）
    ACCOUNT_DEPROVISIONED,  // 账号停用（IdP 移除联动等）
    ACCOUNT_CREATED,        // 管理员创建账号
    ACCOUNT_UPDATED,        // 管理员修改账号（角色/部门/状态/密码）
    ACCOUNT_DELETED,        // 管理员删除账号
    ACCOUNT_VERIFIED,       // 管理员校验账号凭据

    // SCIM 同步事件
    SCIM_USER_CREATED,      // SCIM 创建用户
    SCIM_USER_UPDATED,      // SCIM 更新用户
    SCIM_USER_DELETED,      // SCIM 删除用户
    SCIM_USER_PATCHED,      // SCIM 部分更新用户
    SCIM_GROUP_CREATED,     // SCIM 创建组
    SCIM_GROUP_UPDATED,     // SCIM 更新组
    SCIM_GROUP_DELETED,     // SCIM 删除组
    SCIM_RECONCILE,         // SCIM 对账
    SCIM_TOKEN_CREATED,     // SCIM Bearer Token 签发

    // 密钥治理事件
    KMS_KEY_ROTATED,        // KMS 主密钥热轮换
    KMS_KEY_ROLLBACK,       // KMS 写版本应急回退
    API_KEY_ROTATED,        // API Key 轮换（自动调度 / 强制）
    PRICING_RULE_CHANGED,   // 高级计价规则变更（企业私有化内部成本测算）
    QUOTA_AMOUNT_CHANGED,   // 组织维金额额度变更（企业私有化内部成本治理，非 persistable）
    APPROVAL_SUBMITTED,     // API Key 审批申请提交
    APPROVAL_DECIDED,       // API Key 审批决定（通过 / 拒绝）

    // 系统事件
    SYSTEM_CLEANUP,         // 系统清理
    SYSTEM_MAINTENANCE;     // 系统维护

    /**
     * 可落 security_audit.event_type 列的事件类型全集。
     *
     * <p>必须与两库 db/migration 目录下 V1__baseline.sql 中 security_audit.event_type 的
     * enum/CHECK 定义保持一致（当前 17 项）。其余枚举值——身份生命周期、SCIM、KMS、
     * 审批以及 DATA_ERASURE——走 B1 哈希审计链（eventType 为字符串，落 canonicalJson），
     * 不写入该列。若把它们塞进该表的 IN 查询参数，H2 enum 列会报
     * SQL 22030 Value not permitted for column（表现为审计查询端点 500）。</p>
     */
    private static final List<AuditEventType> PERSISTABLE_TYPES = List.of(
            JWT_TOKEN_ISSUED, JWT_TOKEN_REFRESHED, JWT_TOKEN_REVOKED,
            JWT_TOKEN_VALIDATED, JWT_TOKEN_EXPIRED,
            API_KEY_CREATED, API_KEY_USED, API_KEY_REVOKED, API_KEY_EXPIRED, API_KEY_UPDATED,
            SECURITY_ALERT, SUSPICIOUS_ACTIVITY, AUTHENTICATION_FAILED,
            AUTHORIZATION_FAILED, DATA_SANITIZATION,
            SYSTEM_CLEANUP, SYSTEM_MAINTENANCE);

    /** 该类型是否可落 security_audit.event_type 列。 */
    public static boolean isPersistable(AuditEventType type) {
        return type != null && PERSISTABLE_TYPES.contains(type);
    }

    /**
     * 归一化审计查询的 eventTypes 参数：
     * null/空 → 全部可落库类型（等价“不过滤”，因为本表只可能存这 17 类）；
     * 非空 → 剔除不可落库类型；返回空列表表示请求的类型均不在本表，调用方应直接返回空结果。
     */
    public static List<AuditEventType> normalizeForQuery(List<AuditEventType> requested) {
        if (requested == null || requested.isEmpty()) {
            return PERSISTABLE_TYPES;
        }
        List<AuditEventType> result = new ArrayList<>();
        for (AuditEventType type : requested) {
            if (isPersistable(type) && !result.contains(type)) {
                result.add(type);
            }
        }
        return result;
    }
}