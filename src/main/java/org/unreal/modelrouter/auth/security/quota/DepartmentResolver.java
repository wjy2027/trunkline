/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */
package org.unreal.modelrouter.auth.security.quota;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.service.JwtAccountService;
import org.unreal.modelrouter.common.dto.JwtAccountDTO;

import java.util.concurrent.TimeUnit;

/**
 * 组织维归属解析器（holder → 行政部门）。
 *
 * <p>把「部门额度」所需的 holder→department 解析从热路径剥离：认证成功后一次性解析并写入
 * 请求属性（见 ServiceRequestHandler），配额执行层从属性读，零热路径查库。holder 是
 * {@code ApiKey.holder}，部门取自 {@code jwt_accounts.department}（账号的行政归属，既有字段，
 * 不新建列）。</p>
 *
 * <p><b>缓存语义</b>：Caffeine TTL 60s + 负缓存（查无账号/无部门也缓存空串，避免每个 Key 反复
 * 查库），命中路径零 DB；账号部门变更按 TTL 自然失效。冷未命中是一次 DB 读，但调用点在
 * {@code ServiceRequestHandler} 的 boundedElastic 链上（整条 {@code handleWithInstanceAdapter}
 * 已 subscribeOn(boundedElastic)），不会卡 Netty EventLoop。</p>
 *
 * <p><b>门控</b>：仅当 {@code trunkline.quota.enabled=true} 且
 * {@code department-scope-enabled=true} 时由调用方触发本解析器；其余请求零开销、零 DB。</p>
 *
 * <p><b>fail-soft</b>：JwtAccountService 未装配、账号不存在、部门为空或任何异常，一律返回空串
 * （该 Key 不参与部门额度判定），绝不打断认证链路。</p>
 *
 * @author Trunkline Team
 */
@Component
public class DepartmentResolver {

    private static final long CACHE_TTL_SECONDS = 60L;
    private static final long MAX_CACHE_ENTRIES = 4096L;
    private static final String NEGATIVE = "";

    /** 未装配 JwtAccountService 时仍可构造（fail-soft），用 @Autowired(required=false) 字段注入。 */
    @Autowired(required = false)
    private JwtAccountService jwtAccountService;

    private final Cache<String, String> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL_SECONDS, TimeUnit.SECONDS)
            .maximumSize(MAX_CACHE_ENTRIES)
            .build();

    /**
     * 解析 holder 所属行政部门。
     *
     * @param holder 使用者标识（ApiKey.holder）
     * @return 部门名；查无 / 异常 / 无部门时返回空串（不参与部门额度判定）
     */
    public String resolve(final String holder) {
        if (holder == null || holder.isEmpty()) {
            return NEGATIVE;
        }
        try {
            return cache.get(holder, key -> load(key));
        } catch (RuntimeException e) {
            return NEGATIVE;
        }
    }

    /** 主动失效某 holder 的缓存（账号部门变更时可由账号管理端点调用，可选）。 */
    public void invalidate(final String holder) {
        if (holder != null) {
            cache.invalidate(holder);
        }
    }

    private String load(final String holder) {
        if (jwtAccountService == null) {
            return NEGATIVE;
        }
        try {
            final JwtAccountDTO account = jwtAccountService.getAccount(holder);
            if (account == null || account.getDepartment() == null || account.getDepartment().isBlank()) {
                return NEGATIVE;
            }
            return account.getDepartment().trim();
        } catch (RuntimeException e) {
            // 账号不存在时 getAccount 抛 RuntimeException → 负缓存空串，避免反复查库
            return NEGATIVE;
        }
    }
}
