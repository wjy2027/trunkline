-- security_audit.event_type 白名单扩容：补 PRICING_RULE_CHANGED、QUOTA_AMOUNT_CHANGED。
--
-- 背景：企业侧 QuotaAmountController / PricingRuleController 的变更审计经
-- ExtendedSecurityAuditService.auditSecurityEvent 落 security_audit，但本列
-- 的 H2 enum 值集仍是 17 项（V1 基线），不含这两个事件 → INSERT 报
-- SQL 22030 Value not permitted for column，每次金额额度/计价规则变更审计
-- 都落库失败、只留 AUDIT_LOSS 兜底日志（FINAL16 实测确认）。
--
-- AuditEventType.PERSISTABLE_TYPES（17→19 项）与本脚本同步扩容，
-- 三处（本列 / postgres V3 check / Java 白名单）必须一致，见 AuditEventType javadoc。
--
-- 注意：H2 ENUM 按 ordinal 存值，新值只能追加到值集末尾，不得插在既有值之间，
-- 否则存量行的 ordinal 映射错位、读出的值全乱。
alter table security_audit alter column event_type set data type
    enum ('API_KEY_CREATED','API_KEY_EXPIRED','API_KEY_REVOKED','API_KEY_UPDATED','API_KEY_USED','AUTHENTICATION_FAILED','AUTHORIZATION_FAILED','DATA_SANITIZATION','JWT_TOKEN_EXPIRED','JWT_TOKEN_ISSUED','JWT_TOKEN_REFRESHED','JWT_TOKEN_REVOKED','JWT_TOKEN_VALIDATED','SECURITY_ALERT','SUSPICIOUS_ACTIVITY','SYSTEM_CLEANUP','SYSTEM_MAINTENANCE','PRICING_RULE_CHANGED','QUOTA_AMOUNT_CHANGED');
alter table security_audit alter column event_type set not null;
