-- security_audit.event_type 白名单扩容：补 PRICING_RULE_CHANGED、QUOTA_AMOUNT_CHANGED。
--
-- 背景：企业侧 QuotaAmountController / PricingRuleController 的变更审计经
-- ExtendedSecurityAuditService.auditSecurityEvent 落 security_audit，但 V1 基线
-- 的 check (event_type in (...)) 仍是 17 项，不含这两个事件 → INSERT 违反
-- check 约束，每次金额额度/计价规则变更审计都落库失败、只留 AUDIT_LOSS 兜底
-- 日志（FINAL16 实测确认）。
--
-- AuditEventType.PERSISTABLE_TYPES（17→19 项）与本脚本同步扩容，
-- 三处（h2 V3 enum / 本 check / Java 白名单）必须一致，见 AuditEventType javadoc。
--
-- V1 的 check 为内联匿名约束，PostgreSQL 默认命名 {table}_{column}_check；
-- 用 IF EXISTS 兜底以防命名规则差异。
alter table security_audit drop constraint if exists security_audit_event_type_check;
alter table security_audit add constraint security_audit_event_type_check
    check (event_type in ('JWT_TOKEN_ISSUED','JWT_TOKEN_REFRESHED','JWT_TOKEN_REVOKED','JWT_TOKEN_VALIDATED','JWT_TOKEN_EXPIRED','API_KEY_CREATED','API_KEY_USED','API_KEY_REVOKED','API_KEY_EXPIRED','API_KEY_UPDATED','SECURITY_ALERT','SUSPICIOUS_ACTIVITY','AUTHENTICATION_FAILED','AUTHORIZATION_FAILED','DATA_SANITIZATION','SYSTEM_CLEANUP','SYSTEM_MAINTENANCE','PRICING_RULE_CHANGED','QUOTA_AMOUNT_CHANGED'));
