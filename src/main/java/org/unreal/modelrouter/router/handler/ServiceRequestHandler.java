/*
 * Copyright 2024 JAiRouter Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.unreal.modelrouter.router.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.unreal.modelrouter.auth.security.model.ApiKeyAuthentication;
import org.unreal.modelrouter.auth.security.config.properties.ApiKey;
import org.unreal.modelrouter.auth.security.model.JwtAuthentication;
import org.unreal.modelrouter.auth.security.quota.QuotaDimension;
import org.unreal.modelrouter.auth.security.quota.QuotaEnforcementService;
import org.unreal.modelrouter.auth.security.quota.QuotaLimitSet;
import org.unreal.modelrouter.auth.security.quota.QuotaLimitViolation;
import org.unreal.modelrouter.auth.security.quota.QuotaReservation;
import org.unreal.modelrouter.auth.security.quota.QuotaTokenEstimator;
import org.unreal.modelrouter.billing.service.BillingPrecheckService;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.common.util.IpUtils;
import org.unreal.modelrouter.enterprise.spi.BillingProvider.PrecheckResult;
import org.unreal.modelrouter.enterprise.spi.ClassificationContextHolder;
import org.unreal.modelrouter.enterprise.spi.DataClassification;
import org.unreal.modelrouter.monitor.monitoring.collector.MetricsCollector;
import org.unreal.modelrouter.monitor.tracing.TracingConstants;
import org.unreal.modelrouter.monitor.tracing.TracingContext;
import org.unreal.modelrouter.monitor.tracing.TracingContextHolder;
import org.unreal.modelrouter.monitor.tracing.interceptor.ControllerTracingInterceptor;
import org.unreal.modelrouter.router.adapter.AdapterRegistry;
import org.unreal.modelrouter.router.adapter.ServiceCapability;
import org.unreal.modelrouter.router.cache.CachedStreamingResponse;
import org.unreal.modelrouter.router.cache.ResponseCacheService;
import org.unreal.modelrouter.router.checker.ServiceStateManager;
import org.unreal.modelrouter.router.ratelimit.RateLimitManager;
import org.unreal.modelrouter.router.ratelimit.ServiceRateLimitHolder;
import org.unreal.modelrouter.router.loadbalancer.AffinityContextHolder;
import org.unreal.modelrouter.router.loadbalancer.AffinityKeyResolver;
import org.unreal.modelrouter.router.loadbalancer.SelectedInstanceHolder;
import org.unreal.modelrouter.router.model.ModelRouterProperties;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.model.ModelServiceRegistry.ServiceType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.unreal.modelrouter.auth.security.quota.QuotaEnforcementService.QUOTA_DEPARTMENT_ATTRIBUTE;
import static org.unreal.modelrouter.auth.security.quota.QuotaEnforcementService.QUOTA_HOLDER_ATTRIBUTE;
import static org.unreal.modelrouter.auth.security.quota.QuotaEnforcementService.QUOTA_TENANT_ATTRIBUTE;

/**
 * 通用服务请求处理器.
 *
 * <p>封装所有服务端点的通用处理逻辑，包括：
 * <ul>
 *   <li>服务健康状态检查</li>
 *   <li>实例选择与负载均衡</li>
 *   <li>适配器获取与调用</li>
 *   <li>追踪信息记录</li>
 *   <li>指标收集</li>
 * </ul>
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Component
public class ServiceRequestHandler {

    private static final Logger logger = LoggerFactory.getLogger(ServiceRequestHandler.class);

    /**
     * ServerWebExchange attribute key for storing the authenticated API Key ID.
     */
    public static final String API_KEY_ID_ATTRIBUTE = "API_KEY_ID";

    /**
     * issue #77: 请求属性 key —— 解析后的调用主体标识.
     *
     * <p>API Key 调用 = {@code keyId}；控制台 JWT 登录态 = {@code jwt:<用户名>}。仅供粘性路由键
     * 与响应缓存租户键使用（避免控制台用户间共用缓存租户）。</p>
     *
     * <p>JWT 身份<b>不</b>写入 {@link #API_KEY_ID_ATTRIBUTE}：该属性会被用量归因链路
     * （{@code TokenUsageExtractor} / {@code StreamingRequestProcessor}）当作 API Key ID
     * 读取，而 JWT 用户名在 API Key 存储中不存在，会产生无意义的 WARN 与错误归属。</p>
     */
    public static final String CALLER_ID_ATTRIBUTE = "TRUNKLINE_CALLER_ID";

    /**
     * issue #77: JWT 调用主体标识前缀（与 API Key ID 的键空间隔离）。
     *
     * <p>v3.5 Trunkline：public 化以供调用历史落库解析「员工」——
     * {@code CALLER_ID_ATTRIBUTE} 形如 {@code jwt:<用户名>}，去前缀即员工用户名。</p>
     */
    public static final String JWT_CALLER_ID_PREFIX = "jwt:";

    /**
     * ADMIN 角色 authority（JwtAuthentication/ApiKeyAuthentication 均以 {@code ROLE_} 前缀写入）。
     */
    private static final String ROLE_ADMIN_AUTHORITY = "ROLE_ADMIN";

    /**
     * v2.9.9: ServerWebExchange attribute key for storing the original request DTO.
     * Controller 在调用 handleRequest 前放入，handler 认证后读取以构建缓存键。
     */
    public static final String REQUEST_DTO_ATTRIBUTE = "TRUNKLINE_REQUEST_DTO";

    /**
     * v2.9.9: ServerHttpRequest attribute key for storing the response cache key.
     * handler 认证后生成并放入，processor 写缓存前读取（仿 API_KEY_ID_ATTRIBUTE 传递先例）。
     */
    public static final String CACHE_KEY_ATTRIBUTE = "TRUNKLINE_RESPONSE_CACHE_KEY";

    /**
     * 认证后解析出的<b>调用归属租户</b>（多租户）。
     *
     * <p>为什么需要它：API Key 调用（智能体/SDK）通常不带 {@code X-Tenant-Id}，租户必须从
     * 已认证 Key 的归属反查。企业侧 {@code TenantContextWebFilter} 虽然也做这件事，但它在
     * Security 链下游执行、认证可能尚未完成，读 {@code ReactiveSecurityContextHolder} 拿不到
     * 认证主体；而处理器构建调用历史/用量记录时是在业务链路上，异步线程又读不到实时
     * ThreadLocal。因此在<b>认证成功的那一刻</b>把租户同步写进请求属性，是唯一在整条链路
     * （含 boundedElastic 异步落库）都稳定可用的传递方式。</p>
     *
     * <p>写入来源：{@code ApiKeyAuthentication#getTenantId()}；JWT 调用方不写入
     * （控制台请求由 {@code X-Tenant-Id} 头经企业过滤器解析）。</p>
     */
    public static final String TENANT_ID_ATTRIBUTE = "TRUNKLINE_TENANT_ID";

    /**
     * v3.5 Trunkline: ServerHttpRequest attribute key for storing the resolved
     * <b>virtual model name</b> (model group / pool name) of the request.
     *
     * <p>Written by the handler after routing decision, read by the token-usage
     * recorders (streaming / non-streaming) so that {@code token_usage.virtual_model}
     * records <b>which virtual model the caller asked for</b> — the dimension
     * employees actually see ("公司主力", "便宜日常").</p>
     *
     * <p>Why recorded at request time instead of reverse-mapping instance → group:
     * one instance may be referenced by several virtual models, so a reverse lookup
     * is ambiguous. A request hits exactly one virtual model (decided during
     * routing), so the forward value is unambiguous.</p>
     *
     * <p>Absent when the request did not hit any group (internal rule-pinned
     * instance paths); consumers must treat {@code null} as "未归类".</p>
     */
    public static final String VIRTUAL_MODEL_ATTRIBUTE = "TRUNKLINE_VIRTUAL_MODEL";

    /**
     * v3.1: OpenAI 原生响应标记（{@code /v1} 原生面）.
     *
     * <p>由 {@code OpenAiNativeController} 在 exchange 上置为 {@link Boolean#TRUE}。非流式处理器
     * 读到该标记时不再包 {@code RouterResponse}，直接返回下游原生 JSON，使 {@code /v1/**} 可直接
     * 作为 OpenAI SDK / LangChain / LlamaIndex 的 {@code base_url}；{@code /api/v1/**}（控制台面）
     * 不受影响。</p>
     */
    public static final String NATIVE_RESPONSE_ATTRIBUTE = "TRUNKLINE_NATIVE_RESPONSE";

    /**
     * v3.1 PR-2: 429 响应头 — 本次命中的限额值（{@code ApiKey} 限额配置值）。
     */
    public static final String QUOTA_HEADER_LIMIT = "X-Quota-Limit";

    /**
     * v3.1 PR-2: 429 响应头 — 判定时该窗口剩余额度（非负钳制）。
     */
    public static final String QUOTA_HEADER_REMAINING = "X-Quota-Remaining";

    /**
     * v3.1 PR-2: 429 响应头 — 被命中的窗口（{@code DAY} / {@code MINUTE}）。
     */
    public static final String QUOTA_HEADER_WINDOW = "X-Quota-Window";

    private final AdapterRegistry adapterRegistry;
    private final ModelServiceRegistry registry;
    private final ServiceStateManager serviceStateManager;
    private final MetricsCollector metricsCollector;
    private final ControllerTracingInterceptor tracingInterceptor;

    /**
     * v2.9.9: 响应缓存门面（可选注入，监控/缓存未装配时为空则跳过缓存路径）
     */
    @Autowired(required = false)
    private ResponseCacheService responseCacheService;

    /**
     * v2.9.10: 限流管理器（可选注入，用于缓存命中前服务级限流预扣）。
     * 未注入时缓存路径不执行预扣——限流由 selectInstance 内部执行（行为与现状一致）。
     */
    @Autowired(required = false)
    private RateLimitManager rateLimitManager;

    /**
     * v3.1 PR-2: 配额限额判定服务（可选注入）。
     *
     * <p>未注入或账本未启用（{@code trunkline.quota.enabled=false}，默认）时不做任何判定、
     * 不记账、不设置响应头——请求行为与 v3.0.x 完全一致。判定开启且超限时返回 429 +
     * {@code Retry-After} / {@code X-Quota-*} 响应头（响应体沿用全局异常处理器的既有错误格式）。</p>
     */
    @Autowired(required = false)
    private QuotaEnforcementService quotaEnforcementService;

    /**
     * 组织维归属解析器（optional）。仅在用户 / 部门额度启用时用于认证后解析 holder→department。
     *
     * <p>字段注入而非构造器：避免改动既有 5 参构造器与全部测试构造点；未装配时本段逻辑跳过，
     * 零行为变化。</p>
     */
    @Autowired(required = false)
    private org.unreal.modelrouter.auth.security.quota.DepartmentResolver departmentResolver;

    /**
     * B2 / 方案 3.7：预算前置校验服务（可选注入）。
     *
     * <p>必须在实例选择与下游调用之前执行——{@code BillingProvider.charge()} 是请求成功、
     * 事务提交之后的事后记账，无法拒绝已发生的调用。社区版 Noop 走 SPI default 恒放行；
     * 本服务未装配（精简上下文 / 直接 new 的单测）时整段跳过，零行为变化。</p>
     */
    @Autowired(required = false)
    private BillingPrecheckService billingPrecheckService;

    /**
     * v3.1 PR-4c: 全局 ObjectMapper（可选注入）。
     *
     * <p>仅用于「原生面（{@code /v1/**}）+ 响应缓存命中」时把缓存值序列化为<b>原生 JSON</b>
     * （而非 {@code RouterResponse} 包裹体）。未注入（如直接 new 的单测）时该分支退化为
     * 既有包裹行为，控制台面（{@code /api/**}）行为完全不受影响。</p>
     */
    @Autowired(required = false)
    private ObjectMapper objectMapper;

    /**
     * 构造函数.
     *
     * @param adapterRegistry 适配器注册表
     * @param registry 模型服务注册表
     * @param serviceStateManager 服务状态管理器
     * @param metricsCollector 指标收集器（可选）
     * @param tracingInterceptor 追踪拦截器（可选）
     */
    public ServiceRequestHandler(
            final AdapterRegistry adapterRegistry,
            final ModelServiceRegistry registry,
            final ServiceStateManager serviceStateManager,
            @Autowired(required = false) final MetricsCollector metricsCollector,
            @Autowired(required = false) final ControllerTracingInterceptor tracingInterceptor) {
        this.adapterRegistry = adapterRegistry;
        this.registry = registry;
        this.serviceStateManager = serviceStateManager;
        this.metricsCollector = metricsCollector;
        this.tracingInterceptor = tracingInterceptor;
    }

    /**
     * 处理服务请求（模板方法）.
     *
     * <p>统一的请求处理流程，适用于所有服务类型。
     *
     * @param endpoint 服务端点配置
     * @param modelName 模型名称
     * @param authorization 认证头信息
     * @param exchange ServerWebExchange对象
     * @param executor 服务请求执行器
     * @return 响应实体的Mono
     */
    public Mono<ResponseEntity<?>> handleRequest(
            final ServiceEndpoint endpoint,
            final String modelName,
            final String authorization,
            final ServerWebExchange exchange,
            final ServiceRequestExecutor executor) {

        ServerHttpRequest httpRequest = exchange.getRequest();
        TracingContext tracingContext = getTracingContext(exchange);

        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .flatMap(auth -> {
                // issue #77: 控制台 JWT 登录态与 API Key 同为合法调用主体，此处不再只认
                // ApiKeyAuthentication（曾用 instanceof 过滤，导致页面请求全部走不通）。
                String callerId = resolveCallerId(auth);
                if (callerId == null) {
                    logger.warn("请求缺少可识别的认证主体: {}",
                        httpRequest.getPath().value());
                    return Mono.error(new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authentication required"));
                }

                // 检查服务类型权限
                if (!hasServicePermission(auth, endpoint.getServiceType())) {
                    logger.warn("调用主体 '{}' 无权访问服务类型: {}",
                        callerId, endpoint.getServiceType());
                    // P2-5: 报错带指引——缺哪个权限、去哪配(新建密钥默认未勾选任何 permissions,开箱即 403)
                    return Mono.error(new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "API key lacks required permission '" + endpoint.getServiceType()
                            + "'. Grant it in console > API Keys > edit this key (permissions list), then retry."));
                }

                // 模型/分组白名单校验（仅对网关 API Key 生效；控制台 JWT 调用方豁免，
                // 与 hasServicePermission 的 JWT 同源处理一致，避免 Playground 被误拦）
                if (auth instanceof ApiKeyAuthentication
                        && !hasModelPermission((ApiKeyAuthentication) auth, modelName)) {
                    logger.warn("调用主体 '{}' 无权访问模型: {}", callerId, modelName);
                    return Mono.error(new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Caller does not have permission for model: " + modelName));
                }

                // API Key 调用保留原属性（用量归因链路依赖）；JWT 登录态只写 CALLER_ID_ATTRIBUTE，
                // 避免用户名被当作不存在的 API Key ID 记账。
                if (!isJwtCaller(callerId)) {
                    exchange.getAttributes().put(API_KEY_ID_ATTRIBUTE, callerId);
                    httpRequest.getAttributes().put(API_KEY_ID_ATTRIBUTE, callerId);
                }
                exchange.getAttributes().put(CALLER_ID_ATTRIBUTE, callerId);
                httpRequest.getAttributes().put(CALLER_ID_ATTRIBUTE, callerId);

                // 多租户：认证成功当即把「调用归属租户」写入请求属性。
                // API Key 调用不带 X-Tenant-Id，租户只能从已认证 Key 的归属反查；此处是唯一
                // 能稳定拿到 ApiKeyAuthentication 的时机，后续异步落库（boundedElastic）也能读到。
                if (auth instanceof ApiKeyAuthentication apiKeyAuth) {
                    final String keyTenantId = apiKeyAuth.getTenantId();
                    if (keyTenantId != null && !keyTenantId.isBlank()) {
                        exchange.getAttributes().put(TENANT_ID_ATTRIBUTE, keyTenantId);
                        httpRequest.getAttributes().put(TENANT_ID_ATTRIBUTE, keyTenantId);
                        // 组织维「租户配额」判定在 boundedElastic 线程执行，TenantContextHolder
                        // 的 ThreadLocal 跨线程丢失；写入请求属性供配额层跨线程安全读取（与
                        // holder / department 同构）。TENANT_ID_ATTRIBUTE 仅供用量打标等复用。
                        exchange.getAttributes().put(QUOTA_TENANT_ATTRIBUTE, keyTenantId);
                        httpRequest.getAttributes().put(QUOTA_TENANT_ATTRIBUTE, keyTenantId);
                        // 同时补绑到当前线程的租户上下文：企业侧过滤器在认证完成前已执行，
                        // 读不到 Key 归属而回落 default；此处补绑后，本条链路上的配额账本、
                        // 调用历史/用量打标、JPA 行级过滤都得到正确租户。
                        org.unreal.modelrouter.enterprise.config.TenantContextHolder.resolve(keyTenantId);
                    }
                }

                // 组织维额度（用户 / 部门）：认证后一次性解析归属写入请求属性，配额执行层从属性读，
                // 零热路径查库。仅在总开关 + 组织维开关打开时执行（其余请求零开销）；整条链已
                // subscribeOn(boundedElastic)，department 解析的一次 DB 读不占 Netty EventLoop。
                if (quotaEnforcementService != null
                        && quotaEnforcementService.isOrgScopeResolutionNeeded()
                        && auth instanceof ApiKeyAuthentication quotaKeyAuth) {
                    // holder 来源：已认证 ApiKey 的使用者；getDetails 用模式匹配保护，
                    // 非 ApiKey 时不强转（认证热路径不得因 ClassCastException 破链）
                    final String holder = quotaKeyAuth.getDetails() instanceof ApiKey apiKey
                            && apiKey.getHolder() != null ? apiKey.getHolder().trim() : "";
                    if (!holder.isEmpty()) {
                        exchange.getAttributes().put(QUOTA_HOLDER_ATTRIBUTE, holder);
                        httpRequest.getAttributes().put(QUOTA_HOLDER_ATTRIBUTE, holder);
                        // 部门维单独门控：仅部门额度开关打开才解析 holder→department（缓存 + 负缓存）
                        if (quotaEnforcementService.isDepartmentScopeEnabled() && departmentResolver != null) {
                            final String dept = departmentResolver.resolve(holder);
                            if (dept != null && !dept.isEmpty()) {
                                exchange.getAttributes().put(QUOTA_DEPARTMENT_ATTRIBUTE, dept);
                                httpRequest.getAttributes().put(QUOTA_DEPARTMENT_ATTRIBUTE, dept);
                            }
                        }
                    }
                }

                // #9 数据分级出境：认证成功后从服务端可信源注入请求密级。
                // 唯一信任锚是已认证 ApiKey 的 metadata.classification（getDetails 即 ApiKey）；
                // 请求头自报密级一律不采信。JWT 登录态无 ApiKey metadata，首版保持 PUBLIC。
                ClassificationContextHolder.set(resolveClassification(auth));

                // v2.9.9: 认证通过后为可缓存请求生成响应缓存键并放入请求属性
                // （供 handleWithInstanceAdapter 缓存读短路与 processor 写缓存使用）
                // #9: 带密级（非 PUBLIC）或缓存键无法内嵌密级时，直接不生成缓存键以绕过缓存——
                // 缓存命中会短路整个路由，若不绕过则出境策略形同虚设。
                if (ClassificationContextHolder.get() == DataClassification.PUBLIC) {
                    prepareResponseCacheKey(exchange, httpRequest, callerId, endpoint);
                }

                return handleWithInstanceAdapter(
                    endpoint,
                    modelName,
                    authorization,
                    httpRequest,
                    tracingContext,
                    executor,
                    exchange
                );
            });
    }

    /**
     * 处理服务请求（简化版本，不带ServerWebExchange）.
     *
     * @param endpoint 服务端点配置
     * @param modelName 模型名称
     * @param authorization 认证头信息
     * @param httpRequest HTTP请求对象
     * @param executor 服务请求执行器
     * @return 响应实体的Mono
     */
    public Mono<ResponseEntity<?>> handleRequest(
            final ServiceEndpoint endpoint,
            final String modelName,
            final String authorization,
            final ServerHttpRequest httpRequest,
            final ServiceRequestExecutor executor) {

        return handleWithInstanceAdapter(
            endpoint,
            modelName,
            authorization,
            httpRequest,
            null,
            executor,
            null
        );
    }

    /**
     * 支持实例级适配器选择的服务请求处理器.
     *
     * <p>v3.1 PR-2: 在 {@code selectInstance} 之前插入配额限额判定与预留；超限时以
     * {@link HttpStatus#TOO_MANY_REQUESTS} 短路（响应头 {@code Retry-After} /
     * {@code X-Quota-Limit} / {@code X-Quota-Remaining} / {@code X-Quota-Window}
     * 直接写在响应上，响应体由全局异常处理器按既有错误格式渲染）。
     * 判定发生在响应缓存读之后，因此缓存命中不消耗配额、也不产生预留凭据。</p>
     *
     * @param exchange 原始交换对象（读取原始 DTO 属性、写 429 响应头），可为 {@code null}
     */
    private Mono<ResponseEntity<?>> handleWithInstanceAdapter(
            final ServiceEndpoint endpoint,
            final String modelName,
            final String authorization,
            final ServerHttpRequest httpRequest,
            final TracingContext tracingContext,
            final ServiceRequestExecutor executor,
            final ServerWebExchange exchange) {

        // 限流/缓存/配额/实例选择均为同步逻辑（含可能的 JPA/Redis block），订阅时放到
        // boundedElastic，避免在 Netty EventLoop 上执行并卡死整条 IO 线程
        // issue #119 / #719：本链是含全部重试/故障转移/降级的最终出口——结算收口到此处：
        // 最终错误回滚预留；「重试中取消」不判成功也不重试，在末端按失败回滚（恰一次 CAS 去重）。
        // 处理器自身不再 attempt 级结算（#719），降级入口的终端结算亦会与本处 CAS 去重。
        return Mono.defer(() -> handleWithInstanceAdapterSync(
                        endpoint, modelName, authorization, httpRequest,
                        tracingContext, executor, exchange))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> QuotaReservation.settleFailure(QuotaReservation.from(httpRequest)))
                .doOnCancel(() -> QuotaReservation.settleFailure(QuotaReservation.from(httpRequest)));
    }

    private Mono<ResponseEntity<?>> handleWithInstanceAdapterSync(
            final ServiceEndpoint endpoint,
            final String modelName,
            final String authorization,
            final ServerHttpRequest httpRequest,
            final TracingContext tracingContext,
            final ServiceRequestExecutor executor,
            final ServerWebExchange exchange) {

        String clientIp = IpUtils.getClientIp(httpRequest);
        ServiceType serviceType = endpoint.getServiceType();

        // v2.9.0: 存储亲和性上下文原始组件(callerId, clientIp, serviceType, modelName)
        // 在 ModelServiceRegistry 中按 sticky.scope 配置动态解析为正确粒度的亲和性键
        String callerId = extractCallerId(httpRequest);
        AffinityContextHolder.set(callerId, clientIp, serviceType.name(), modelName);

        // v2.8.5: 提取请求头用于规则引擎路由(仅用于规则匹配,不影响出站转发)
        Map<String, String> requestHeaders = new HashMap<>();
        if (httpRequest.getHeaders() != null) {
            httpRequest.getHeaders().forEach((key, values) -> {
                if (!values.isEmpty()) {
                    requestHeaders.put(key, values.get(0));
                }
            });
        }

        // v2.9.10: 服务级限流预扣 + 缓存提前短路
        // 当缓存键存在时（缓存启用+请求可缓存），在 selectInstance 前：
        //   1. 预扣服务级限流（限流是硬边界，超限 429 优先于缓存——即使缓存有值也不放行）
        //   2. 标记已限流（selectInstance 内部跳过重复扣减，保持恰一次语义）
        //   3. 查缓存：命中直接返回（省去实例选择开销）；未命中继续 selectInstance
        // 无缓存键时（disabled/非确定性/无 DTO）：不预扣、不标记——selectInstance 内部限流（行为与现状完全一致）
        Object cacheKey = httpRequest != null
                ? httpRequest.getAttributes().get(CACHE_KEY_ATTRIBUTE) : null;
        boolean cachePathActive = cacheKey instanceof String key && !key.isBlank();

        if (cachePathActive && rateLimitManager != null) {
            if (!rateLimitManager.tryAcquireService(serviceType, clientIp, modelName)) {
                return Mono.error(new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Service rate limit exceeded for service type '" + serviceType + "'"));
            }
            ServiceRateLimitHolder.markAcquired();
        }

        // v3.5: 恢复追踪上下文到当前工作线程。
        // 本同步段经 Mono.defer + subscribeOn(boundedElastic) 在 boundedElastic 工作线程上执行，
        // TracingWebFilter 设置在 Netty 线程上的 ThreadLocal 在这里不可见——
        // 不恢复的话，适配器同步段（含 NonStreamingRequestProcessor 的 traceId 同步捕获、
        // 调用历史/用量落库的运行时取值）拿到的是空串，账单/调用历史将与追踪系统失去关联。
        // 传入的 tracingContext 在 Netty 线程上同步取出，随参数到达本线程。
        // 必须在 finally 清理：boundedElastic 工作线程会被复用，残留会污染后续无关请求。
        TracingContextHolder.setCurrentContext(tracingContext);

        try {
            // v2.9.10: 响应缓存读 — 限流预扣后、selectInstance 之前(命中直接跳过实例选择)
            ResponseEntity<?> cachedResponse =
                    tryReadCachedResponse(httpRequest, serviceType, modelName, exchange);
            if (cachedResponse != null) {
                return Mono.just(cachedResponse);
            }

            // B2 / 方案 3.7: 预算前置熔断 — 必须早于实例选择与下游调用。
            // 缓存命中已先行短路（不产生新费用，无需校验）；社区版 Noop default 放行。
            if (billingPrecheckService != null) {
                final Optional<PrecheckResult> billingDenial =
                        billingPrecheckService.evaluate(toUsername(callerId), modelName);
                if (billingDenial.isPresent()) {
                    return rejectByBillingPrecheck(billingDenial.get(), callerId, modelName);
                }
            }

            // v3.1 PR-2: 配额限额判定 + 预留 — selectInstance 之前（缓存命中已先行短路）
            Optional<QuotaLimitViolation> quotaViolation =
                    reserveQuota(exchange, httpRequest, callerId, serviceType, modelName);
            if (quotaViolation.isPresent()) {
                return rejectByQuota(exchange, quotaViolation.get());
            }

            // 1. 选择实例
            ModelRouterProperties.ModelInstance selectedInstance;
            try {
                selectedInstance = selectInstance(
                        serviceType, modelName, clientIp, tracingContext, requestHeaders);
            } catch (Exception e) {
                logger.error("Failed to select instance for service: {}, model: {}",
                        serviceType, modelName, e);
                return Mono.error(e);
            } finally {
                // v2.9.0: 请求实例选择完成后清理亲和性上下文
                AffinityContextHolder.clear();
            }

            // v3.5 Trunkline: 记录本次请求命中的<b>虚拟模型</b>（模型分组 / 池名）——
            // token_usage.virtual_model 的数据来源，团队用量按虚拟模型统计的归属依据。
            //
            // 在此（路由决策后、调用下游前）写入，请求属性随 Reactor 上下文传递到
            // 流式 / 非流式两处落库点。取不到分组名（规则锁定实例等内部路径）时不写，
            // 落库时归入「未归类」，不丢数据。
            attachVirtualModel(httpRequest, serviceType, modelName);

            // v3.5 治理收口:选实例后补记「实例」维度 ——
            // 预留阶段只知道分组(虚拟模型名),实例是选完才知道的。
            // 此处把「分组 + 实例」维度追加进预留凭据,账本明细行同时带
            // group_id(治理主键)与 model(观测明细),GROUP BY group_id 即得分组聚合。
            try {
                appendInstanceQuotaDimension(httpRequest, serviceType, modelName, selectedInstance);
            } catch (Exception e) {
                logger.debug("补记配额实例维度失败（忽略，不影响请求）: {}", e.toString());
            }

            // v3.5 Trunkline:厂商模型（实例）维度的额度与限速 ——
            // 额度与限速是「厂商模型」的配置,只有选完实例才知道落到哪个模型,
            // 因此在此处做判定:超额返回 429(与 Key/分组维度同一响应形态)。
            // 未配置限额的模型只记账不判定,保证模型维度用量连续。
            try {
                final Optional<QuotaLimitViolation> modelQuotaViolation =
                        enforceModelQuota(httpRequest, selectedInstance);
                if (modelQuotaViolation.isPresent()) {
                    return rejectByQuota(exchange, modelQuotaViolation.get());
                }
            } catch (Exception e) {
                logger.debug("厂商模型维度配额判定失败（忽略，不影响请求）: {}", e.toString());
            }

            // P3-11: 路由决策调试头(仅响应头,不影响 body;调试用,未来可能扩展)
            try {
                exchange.getResponse().getHeaders()
                        .add("X-Trunkline-Instance", selectedInstance.getName());
                String poolName = registry.resolvePoolName(serviceType, modelName);
                if (poolName != null) {
                    exchange.getResponse().getHeaders().add("X-Trunkline-Pool", poolName);
                    // v2.8.9 池名改写凭据：池名判定依赖租户隔离的池快照，此处网关线程租户上下文
                    // 可靠；下游适配器处理器在 boundedElastic 线程上租户上下文不可达，isPoolName
                    // 会误判，虚拟模型名将原样透传上游（上游按未知模型回 404 → 降级 503）。
                    // 改写决策在这里做出，随请求属性下发，处理器按凭据无条件改写 model 字段。
                    if (!poolName.equals(selectedInstance.getName())) {
                        final String rewriteTarget = selectedInstance.getName();
                        // 2026-11-27（中风险整改）：凭据两侧都写（exchange + httpRequest），
                        // 与上方 QUOTA_TENANT_ATTRIBUTE 的写法对齐——万一请求对象被包装 /
                        // 重建（装饰器、重试路径），下游仍能从 exchange 属性读到改写凭据
                        exchange.getAttributes().put(
                                org.unreal.modelrouter.router.adapter.request.TokenUsageExtractor
                                        .GATEWAY_MODEL_REWRITE_ATTRIBUTE,
                                rewriteTarget);
                        httpRequest.getAttributes().put(
                                org.unreal.modelrouter.router.adapter.request.TokenUsageExtractor
                                        .GATEWAY_MODEL_REWRITE_ATTRIBUTE,
                                rewriteTarget);
                    }
                }
                String strategy = registry.resolveRoutingStrategy(serviceType, modelName);
                if (strategy != null) {
                    exchange.getResponse().getHeaders().add("X-Trunkline-Strategy", strategy);
                }
            } catch (Exception headerEx) {
                logger.debug("写入路由调试头失败: {}", headerEx.getMessage());
            }

            // 2. 获取适配器
            ServiceCapability adapter;
            String adapterName;
            try {
                // v2.8.5: 规则引擎 TARGET_ADAPTER 动作 — 规则指定适配器名时按名取用
                String ruleAdapterName = registry.resolveRuleAdapterName(
                        serviceType, modelName, clientIp, requestHeaders);
                if (ruleAdapterName != null && !ruleAdapterName.isBlank()) {
                    ServiceCapability ruleAdapter = adapterRegistry.getAdapterByName(ruleAdapterName);
                    if (ruleAdapter != null) {
                        adapter = ruleAdapter;
                        adapterName = ruleAdapterName;
                        logger.info("Rule selected adapter '{}' for instance '{}' in service '{}'",
                                adapterName, selectedInstance.getName(), serviceType);
                    } else {
                        logger.warn("Rule target adapter '{}' not registered, fallback to instance adapter",
                                ruleAdapterName);
                        adapter = adapterRegistry.getAdapter(serviceType, selectedInstance);
                        adapterName = selectedInstance.getAdapter() != null
                                ? selectedInstance.getAdapter()
                                : "default";
                    }
                } else {
                    adapter = adapterRegistry.getAdapter(serviceType, selectedInstance);
                    adapterName = selectedInstance.getAdapter() != null
                        ? selectedInstance.getAdapter()
                        : "default";
                }
                logger.info("Selected adapter '{}' for instance '{}' in service '{}'",
                           adapterName, selectedInstance.getName(), serviceType);
            } catch (Exception e) {
                logger.error("Failed to get adapter for instance: {}", selectedInstance.getName(), e);
                return Mono.error(e);
            }

            // 3. 执行请求（带追踪和指标收集）
            return executeWithTracingAndMetrics(
                endpoint,
                adapter,
                adapterName,
                authorization,
                httpRequest,
                tracingContext,
                selectedInstance,
                executor
            );
        } finally {
            // v2.9.10: 清理服务级限流预扣标志（防 ThreadLocal 泄漏）
            ServiceRateLimitHolder.clear();
            // 缓存命中提前返回时 AffinityHolder 未被 selectInstance finally 清理，此处防御性清理
            AffinityContextHolder.clear();
            // 清理请求级已选实例（适配器已同步复用完毕）
            SelectedInstanceHolder.clear();
            // #9: 清理请求密级上下文（boundedElastic 线程复用，防密级泄漏到下一请求）
            ClassificationContextHolder.clear();
            // v3.5: 清理本方法恢复的追踪上下文（boundedElastic 工作线程会被复用，防泄漏污染）
            TracingContextHolder.clearCurrentContext();
        }
    }

    /**
     * 选择实例.
     */
    private ModelRouterProperties.ModelInstance selectInstance(
            final ServiceType serviceType,
            final String modelName,
            final String clientIp,
            final TracingContext tracingContext,
            final Map<String, String> requestHeaders) {

        ModelRouterProperties.ModelInstance instance = registry.selectInstance(
                serviceType, modelName, clientIp, requestHeaders);
        // 透传给适配器复用，避免同一请求内二次选择（重复推进 LB 状态、重复计入路由监控）
        SelectedInstanceHolder.set(instance);
        // 追踪实例选择
        if (tracingInterceptor != null && tracingContext != null && tracingContext.isActive()) {
            tracingInterceptor.traceInstanceSelection(tracingContext, serviceType, modelName, clientIp, instance);
        }

        return instance;
    }

    /**
     * 执行请求（带追踪和指标收集）.
     */
    private Mono<ResponseEntity<?>> executeWithTracingAndMetrics(
            final ServiceEndpoint endpoint,
            final ServiceCapability adapter,
            final String adapterName,
            final String authorization,
            final ServerHttpRequest httpRequest,
            final TracingContext tracingContext,
            final ModelRouterProperties.ModelInstance instance,
            final ServiceRequestExecutor requestExecutor) {

        ServiceType serviceType = endpoint.getServiceType();
        String serviceName = serviceType.name();
        long startTime = System.currentTimeMillis();
        String method = httpRequest.getMethod().name();

        // v3.5 治理收口：此处原有的「服务级健康闸门」已移除。
        //
        // 原实现的问题（三层叠加）：
        //   ① 位置错乱 —— 闸门在 selectInstance **之后**执行，只能否决一次已经成功路由的请求；
        //   ② 粒度错配 —— 闸门以「分类」为一刀切，而路由层已按**实例级**健康位
        //      （ServiceStateManager.instanceHealthBits）过滤过候选，粒度更细也更准；
        //   ③ 数据陈旧 —— serviceHealthStatus 仅由 ServerChecker 每 30s 刷新，
        //      且只对「有实例」的分类刷新；实例新增后最长 30s 内仍是上一轮的 false，
        //      导致「配置完立刻调用」被 503 否决。
        //
        // 移除后可用性判定完全下沉到路由层（ModelServiceRegistry.selectInstance）：
        //   · 池内全部成员不健康 → 抛 PoolUnavailableException（503 + Retry-After +
        //     逐成员 status/health/circuitBreaker 明细）；
        //   · 分类下无实例 → 404 No instances configured for service type；
        //   · 全部正常 → 正常路由。
        // 三种情形的 HTTP 语义与原实现一致，且错误信息更可定位。

        // 执行请求
        return executeRequest(adapter, authorization, httpRequest,
                tracingContext, adapterName, serviceType, instance, requestExecutor)
            .doOnSuccess(response -> {
                long duration = System.currentTimeMillis() - startTime;
                String status = getResponseStatus(response);
                long requestSize = estimateRequestSize(httpRequest);
                long responseSize = estimateResponseSize(response);
                recordRequestMetrics(serviceName, method, duration, status, requestSize, responseSize);
            })
            .doOnError(error -> {
                long duration = System.currentTimeMillis() - startTime;
                String status = getErrorStatus(error);
                long requestSize = estimateRequestSize(httpRequest);
                recordRequestMetrics(serviceName, method, duration, status, requestSize, 0);
            })
            .onErrorMap(UnsupportedOperationException.class, e ->
                new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "Service not supported by current adapter: " + e.getMessage()))
            .onErrorMap(IllegalArgumentException.class, e ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Adapter configuration error: " + e.getMessage()));
    }

    /**
     * 执行请求（带追踪包装）.
     */
    private Mono<ResponseEntity<?>> executeRequest(
            final ServiceCapability adapter,
            final String authorization,
            final ServerHttpRequest httpRequest,
            final TracingContext tracingContext,
            final String adapterName,
            final ServiceType serviceType,
            final ModelRouterProperties.ModelInstance instance,
            final ServiceRequestExecutor requestExecutor) {

        try {
            if (tracingInterceptor != null && tracingContext != null && tracingContext.isActive()) {
                return tracingInterceptor.traceAdapterCall(
                    tracingContext,
                    adapterName,
                    serviceType,
                    instance,
                    () -> {
                        try {
                            return requestExecutor.execute(adapter, authorization, httpRequest);
                        } catch (Exception e) {
                            return Mono.error(e);
                        }
                    }
                );
            } else {
                return requestExecutor.execute(adapter, authorization, httpRequest);
            }
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    /**
     * 从请求属性中提取调用主体标识.
     *
     * <p>v2.9.0: 用于会话亲和性键解析；issue #77 起主体可能是 API Key ID 或
     * {@code jwt:<用户名>}（{@link #CALLER_ID_ATTRIBUTE} 优先，兼容仍只写
     * {@link #API_KEY_ID_ATTRIBUTE} 的调用路径）。</p>
     */
    private String extractCallerId(final ServerHttpRequest httpRequest) {
        if (httpRequest == null) {
            return null;
        }
        Object callerId = httpRequest.getAttributes().get(CALLER_ID_ATTRIBUTE);
        if (!(callerId instanceof String value) || value.isBlank()) {
            callerId = httpRequest.getAttributes().get(API_KEY_ID_ATTRIBUTE);
        }
        if (callerId instanceof String key && !key.isBlank()) {
            return key;
        }
        return null;
    }

    /**
     * 解析调用主体标识（issue #77）.
     *
     * <p>只接受网关实际产生的两种认证对象：API Key 取 {@code keyId}（原语义不变）；控制台 JWT
     * 登录态取用户名并加 {@link #JWT_CALLER_ID_PREFIX} 前缀，与 API Key ID 的键空间隔离。
     * 其他认证类型一律返回 {@code null}（拒绝），不因 principal 恰为字符串而放宽准入。</p>
     *
     * @param authentication 已认证主体
     * @return 调用主体标识；不可识别时返回 {@code null}
     */
    private static String resolveCallerId(final Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof String value) || value.isBlank()) {
            return null;
        }
        if (authentication instanceof JwtAuthentication) {
            return JWT_CALLER_ID_PREFIX + value;
        }
        return authentication instanceof ApiKeyAuthentication ? value : null;
    }

    /**
     * #9 数据分级出境：从服务端可信源解析请求密级。
     *
     * <p>信任锚只有一处——已认证 {@link ApiKeyAuthentication} 的 {@code getDetails()}
     * （认证管理器把校验通过的 {@link ApiKey} 放在 details 里）的
     * {@code metadata.classification}。请求头自报密级一律不采信。JWT 登录态无 ApiKey
     * metadata，首版保持 {@link DataClassification#PUBLIC}（分组/部门映射留待后续）。</p>
     *
     * <p>metadata 是自由 Map，取值非字符串或解析失败都按 PUBLIC 处理；真正的拒绝由
     * {@code ClassificationRoutingPolicy} 决定，本方法只负责如实传递服务端声明。</p>
     */
    private static DataClassification resolveClassification(final Authentication authentication) {
        if (authentication instanceof ApiKeyAuthentication && authentication.getDetails() instanceof ApiKey apiKey
                && apiKey.getMetadata() != null) {
            Object raw = apiKey.getMetadata().get("classification");
            if (raw instanceof String value) {
                return DataClassification.fromCode(value);
            }
        }
        return DataClassification.PUBLIC;
    }

    /**
     * 判断调用主体是否来自控制台 JWT 登录态（issue #77）.
     */
    private static boolean isJwtCaller(final String callerId) {
        return callerId != null && callerId.startsWith(JWT_CALLER_ID_PREFIX);
    }

    /**
     * B2：把调用主体标识还原成可读用户名（去掉 {@code jwt:} 前缀）。
     * API Key 调用保留 keyId 原值；仅用于预算前置校验的上下文，不参与用量归因。
     */
    private static String toUsername(final String callerId) {
        if (callerId == null) {
            return null;
        }
        return isJwtCaller(callerId)
                ? callerId.substring(JWT_CALLER_ID_PREFIX.length()) : callerId;
    }

    /**
     * v3.5 Trunkline: 把本次请求命中的<b>虚拟模型</b>（模型分组 / 池名）挂到请求属性。
     *
     * <p>解析口径与配额分组维度一致（{@code resolvePoolName} → 显式创建的分组），
     * 保证「团队用量」的虚拟模型归属与配额账本的分组口径同源。</p>
     *
     * <p>解析失败（未命中任何分组）时静默跳过：落库时该行归入「未归类」，
     * 不影响用量与花费统计。</p>
     *
     * @param httpRequest HTTP 请求（写入属性）
     * @param serviceType 服务类型
     * @param modelName   请求中的模型名（对外即虚拟模型名）
     */
    private void attachVirtualModel(final ServerHttpRequest httpRequest,
                                    final ModelServiceRegistry.ServiceType serviceType,
                                    final String modelName) {
        if (httpRequest == null || modelName == null || modelName.isEmpty()) {
            return;
        }
        try {
            String poolName = registry != null ? registry.resolvePoolName(serviceType, modelName) : null;
            if (poolName != null && !poolName.isEmpty()) {
                httpRequest.getAttributes().put(VIRTUAL_MODEL_ATTRIBUTE, poolName);
            }
        } catch (Exception e) {
            logger.debug("解析虚拟模型名失败（忽略，不影响用量记录）: {}", e.toString());
        }
    }

    /**
     * v3.1 PR-2: 配额限额判定与预留.
     *
     * <p>开关前置判断：配额服务未装配或账本未启用（默认）时立即返回，不估算 token、
     * 不访问账本、不挂载结算凭据——零行为变更。判定与记账过程中的异常由
     * {@link QuotaEnforcementService} 内部按 fail-open 吞掉，本方法不会抛出。</p>
     *
     * <p>估算输入取认证前 Controller 放入的原始请求 DTO（{@link #REQUEST_DTO_ATTRIBUTE}）；
     * 简化入口（无 exchange）或不可估算的服务类型按 0 处理，预留只计请求数。</p>
     *
     * @param exchange   原始交换对象（读取原始 DTO），可为 {@code null}
     * @param httpRequest HTTP 请求（挂载结算凭据）
     * @param callerId   调用主体标识（API Key ID 或 {@code jwt:<用户名>}）
     * @return 超限结果；放行（含降级放行）时为 {@link Optional#empty()}
     */
    private Optional<QuotaLimitViolation> reserveQuota(final ServerWebExchange exchange,
                                                       final ServerHttpRequest httpRequest,
                                                       final String callerId,
                                                       final ServiceType serviceType,
                                                       final String modelName) {
        if (quotaEnforcementService == null) {
            return Optional.empty();
        }
        // issue #77: 控制台 JWT 登录态（页面请求，无 API Key）不参与 API Key 配额账本，
        // 既不预留也不产生结算凭据；调用历史仍按 jwt:<用户名> 记归属。
        if (isJwtCaller(callerId)) {
            return Optional.empty();
        }
        Object requestDto = exchange != null ? exchange.getAttribute(REQUEST_DTO_ATTRIBUTE) : null;
        if (!quotaEnforcementService.isEnabled()) {
            // N-2: 账本未启用时,老链路(认证按历史校验+响应后入账)对单次请求无约束,
            // 按 prompt 估算+max_tokens 上限做请求前预估校验兜底(超限 429 + X-Quota-* 响应头)
            return quotaEnforcementService.checkEstimatedTokenQuota(callerId, requestDto);
        }
        long estimatedTokens = QuotaTokenEstimator.estimate(requestDto);
        // v3.5 治理收口：账本明细「分组 + 实例并存」——
        //   groupId = 请求命中的模型分组（虚拟模型名，治理主键）
        //   model   = 实际落到的已接入模型（实例名，观测明细）
        //
        // 注意：预留发生在 selectInstance 之前，此时尚不知道最终落到哪个实例，
        // 因此这里先按「请求的虚拟模型名」写入<b>分组桶</b>行（groupId=分组名, model 为空），
        // 「实例」维度在选完实例后由处理器<b>补记为独立的成员桶行</b>（groupId=分组名, model=实例名）。
        // 两行并列存在、八列唯一键不同：分组聚合只统计分组桶行，组内明细只统计成员桶行，
        // 两个口径互斥、不可相加（相加会让分组用量翻倍）。
        String groupId = "";
        if (registry != null && modelName != null && !modelName.isEmpty()) {
            final String poolName = registry.resolvePoolName(serviceType, modelName);
            if (poolName != null && !poolName.isEmpty()) {
                groupId = poolName;
            }
        }
        return quotaEnforcementService.tryReserve(httpRequest, callerId, groupId, "", estimatedTokens);
    }

    /**
     * v3.5 治理收口：选实例后把「分组 + 实例」维度补记进配额账本。
     *
     * <p>预留阶段（{@code selectInstance} 之前）只知道请求命中的分组（虚拟模型名），
     * 实际落到哪个实例要选完才知道。此处补记<b>成员桶行</b>（{@code group_id} = 分组名、
     * {@code model} = 实例名），与预留期写入的<b>分组桶行</b>（{@code group_id} = 分组名、
     * {@code model} 为空）并列存在——分组聚合只取分组桶行，组内下钻只取成员桶行，
     * 两者互斥不可相加。</p>
     *
     * <p>该维度只做记账、不做限额判定（分组级限额已在预留阶段判定过），
     * 因此不会因为实例维度超限而拒绝请求。</p>
     *
     * @param httpRequest      请求（承载预留凭据）
     * @param serviceType      服务类型
     * @param modelName        请求中的虚拟模型名（分组名）
     * @param selectedInstance 选中的实例
     */
    private void appendInstanceQuotaDimension(final ServerHttpRequest httpRequest,
                                              final ServiceType serviceType,
                                              final String modelName,
                                              final ModelRouterProperties.ModelInstance selectedInstance) {
        if (quotaEnforcementService == null || !quotaEnforcementService.isEnabled()) {
            return;
        }
        final QuotaReservation reservation = QuotaReservation.from(httpRequest);
        if (reservation == null || selectedInstance == null) {
            return;
        }
        final String instanceName = selectedInstance.getName();
        if (instanceName == null || instanceName.isEmpty()) {
            return;
        }
        // 解析分组名（治理主键）：显式池优先，其次系统分组
        String groupId = "";
        if (registry != null && modelName != null && !modelName.isEmpty()) {
            final String poolName = registry.resolvePoolName(serviceType, modelName);
            if (poolName != null && !poolName.isEmpty()) {
                groupId = poolName;
            }
        }
        if (groupId.isEmpty()) {
            // 未命中任何分组：不是分组路由（规则锁定实例等内部路径），不补记维度
            return;
        }
        reservation.appendDimension(QuotaDimension.ofGroupAndModel(groupId, instanceName));
    }

    /**
     * v3.5 Trunkline:厂商模型（实例）维度的额度与限速判定。
     *
     * <p>额度与限速是<b>厂商模型</b>的配置（接入管理「价格与额度」表的一行 = 一个厂商模型），
     * 而厂商模型只有选完实例才确定——预留阶段（{@code selectInstance} 之前）只知道虚拟模型名。
     * 因此本方法在选实例后调用：取该模型的限额集合，走
     * {@link QuotaReservation#appendDimensionWithLimits(QuotaDimension, QuotaLimitSet)}
     * 做「判定 + 记账」一次完成。</p>
     *
     * <p>三种情形：</p>
     * <ul>
     *   <li><b>未配置额度/限速</b>（限额为 {@code null} 或各窗口均 {@code <= 0}）：
     *       <b>不建该维度的账本行</b>——没有可判定的限制，写行只是徒增账本体积与结算次数；
     *       厂商模型的用量观测以 {@code token_usage} 为准；</li>
     *   <li><b>未超限</b>：该维度计数已入账，正常放行；</li>
     *   <li><b>超限</b>：CAS 预扣已回滚本次增量且该维度未登记，调用方据此返回 429。</li>
     * </ul>
     *
     * <p>已预留的 Key / 分组维度在返回 429 时由错误链路的
     * {@link QuotaReservation#settleFailure(QuotaReservation)} 统一回滚，不会留下残量。</p>
     *
     * @param httpRequest      请求（承载预留凭据）
     * @param selectedInstance 选中的实例（厂商模型）
     * @return 超限结果；放行时为 {@link Optional#empty()}
     */
    private Optional<QuotaLimitViolation> enforceModelQuota(
            final ServerHttpRequest httpRequest,
            final ModelRouterProperties.ModelInstance selectedInstance) {
        if (quotaEnforcementService == null || !quotaEnforcementService.isEnabled()) {
            return Optional.empty();
        }
        final QuotaReservation reservation = QuotaReservation.from(httpRequest);
        if (reservation == null || selectedInstance == null) {
            return Optional.empty();
        }
        final String instanceName = selectedInstance.getName();
        if (instanceName == null || instanceName.isEmpty()) {
            return Optional.empty();
        }
        final QuotaLimitSet limits = quotaEnforcementService.resolveModelLimit(instanceName);
        if (limits == null || limits.equals(QuotaLimitSet.UNLIMITED)) {
            // 未配置额度/限速：没有可判定的限制，不建模型维度账本行
            return Optional.empty();
        }
        final Optional<QuotaLimitViolation> violation =
                reservation.appendDimensionWithLimits(QuotaDimension.ofModel(instanceName), limits);
        if (violation.isPresent()) {
            logger.warn("厂商模型额度超限，返回 429: model={}, {}",
                    instanceName, violation.get().describeDetailed());
        }
        return violation;
    }

    /**
     * B2 / 方案 3.7：预算耗尽响应（402 Payment Required）。
     *
     * <p>与配额 429 区分：429 是「请求太密 / token 超额」，402 是「钱不够」。
     * 响应体沿用 {@link ResponseStatusException} → 全局异常处理器的既有错误格式，
     * {@code reason} 由企业实现给出可读原因（不得含密钥、余额明细等敏感信息）。</p>
     *
     * <p>此处位于实例选择之前，因此没有配额预留需要回滚（{@code reserveQuota} 尚未调用）。</p>
     */
    private Mono<ResponseEntity<?>> rejectByBillingPrecheck(final PrecheckResult denial,
                                                            final String callerId,
                                                            final String modelName) {
        final String reason = denial.reason() != null && !denial.reason().isBlank()
                ? denial.reason() : "预算已耗尽，请求已被拒绝";
        logger.warn("预算前置校验拒绝请求: caller={}, model={}, reason={}", callerId, modelName, reason);
        return Mono.error(new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, reason));
    }

    /**
     * v3.1 PR-2: 配额超限响应（429 + Retry-After + X-Quota-*）.
     *
     * <p>响应头直接写在响应对象上（全局异常处理器只设置状态码与 Content-Type，不清空已有头），
     * 响应体沿用仓库既有 429 惯例——{@link ResponseStatusException} 交给
     * {@code ReactiveGlobalExceptionHandler} 渲染为 {@code RouterResponse.error(...)}。
     * 无 exchange（简化入口）时降级为“无响应头 + 相同状态码/响应体”。</p>
     *
     * @param exchange  原始交换对象，可为 {@code null}
     * @param violation 超限结果
     * @return 429 错误的 Mono
     */
    private Mono<ResponseEntity<?>> rejectByQuota(final ServerWebExchange exchange,
                                                  final QuotaLimitViolation violation) {
        if (exchange != null) {
            try {
                HttpHeaders headers = exchange.getResponse().getHeaders();
                headers.set(QUOTA_HEADER_LIMIT, String.valueOf(violation.limit()));
                headers.set(QUOTA_HEADER_REMAINING, String.valueOf(violation.remaining()));
                headers.set(QUOTA_HEADER_WINDOW, violation.window().name());
                headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(violation.retryAfterSeconds()));
            } catch (Exception e) {
                logger.debug("设置配额超限响应头失败: {}", e.getMessage());
            }
        }
        // 2026-11-27（中风险整改）：日志带 scopeType/scopeId 便于定位命中桶；
        // 响应体只放 describe()（脱敏），内部标识不外泄给客户端
        logger.warn("API Key 配额超限，返回 429: {}", violation.describeDetailed());
        return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, violation.describe()));
    }

    /**
     * v2.9.9: 响应缓存键生成（读挂载前置准备）.
     *
     * <p>认证通过后调用：从 exchange attribute 读取 Controller 放入的原始 DTO；
     * 缓存启用 + 请求可缓存（确定性/非流式，由 ResponseCacheService 判定）时
     * 构建键并放入 httpRequest attribute，供处理器缓存读与 processor 写缓存使用。
     * 任一条件不满足则不生成键（缓存读写天然关闭）。
     *
     * @param exchange ServerWebExchange（含原始 DTO attribute）
     * @param httpRequest HTTP 请求（缓存键存放处）
     * @param callerId 调用主体标识（API Key ID 或 {@code jwt:<用户名>}）
     * @param endpoint 服务端点
     */
    private void prepareResponseCacheKey(final ServerWebExchange exchange, final ServerHttpRequest httpRequest,
                                         final String callerId, final ServiceEndpoint endpoint) {
        if (responseCacheService == null || !responseCacheService.isEnabled()) {
            return;
        }
        if (exchange == null || httpRequest == null) {
            return;
        }
        Object requestDto = exchange.getAttribute(REQUEST_DTO_ATTRIBUTE);
        if (requestDto == null) {
            return;
        }
        // 租户键: callerId 缺省回退 clientIp（复用 AffinityKeyResolver 语义，防跨租户泄漏）
        String tenantKey = AffinityKeyResolver.resolveTenantKey(callerId, IpUtils.getClientIp(httpRequest));
        if (tenantKey == null) {
            return;
        }
        String cacheKey = responseCacheService.buildKey(tenantKey, endpoint.getServiceType(), requestDto);
        if (cacheKey != null) {
            httpRequest.getAttributes().put(CACHE_KEY_ATTRIBUTE, cacheKey);
        }
    }

    /**
     * v2.9.9/v2.9.10: 响应缓存读（在 selectInstance 之后调用）.
     *
     * <p>从 httpRequest attribute 读取缓存键并查询缓存；命中时按缓存值类型分支：
     * <ul>
     *   <li>{@link CachedStreamingResponse}（v2.9.10）：构造同构
     *       {@code text/event-stream + Flux<ServerSentEvent<String>>} 逐块 SSE 回放</li>
     *   <li>原生面（{@code /v1/**}，v3.1 PR-4c）：缓存值直接序列化为<b>原生 JSON</b>
     *       ——OpenAI/Anthropic 客户端拿不到 {@code RouterResponse} 包裹体</li>
     *   <li>控制台面（{@code /api/**}）：构造 {@code RouterResponse} 200 JSON 响应</li>
     * </ul>
     * 未命中返回 null 继续原流程。
     *
     * @param httpRequest HTTP 请求
     * @param serviceType 服务类型
     * @param modelName 模型名称
     * @param exchange 原始交换对象（读取原生面标记），可为 {@code null}
     * @return 缓存命中时的响应实体；未命中或缓存不可用时返回 null
     */
    private ResponseEntity<?> tryReadCachedResponse(final ServerHttpRequest httpRequest,
                                                    final ServiceType serviceType,
                                                    final String modelName,
                                                    final ServerWebExchange exchange) {
        if (responseCacheService == null || httpRequest == null) {
            return null;
        }
        Object cacheKey = httpRequest.getAttributes().get(CACHE_KEY_ATTRIBUTE);
        if (!(cacheKey instanceof String key) || key.isBlank()) {
            return null;
        }
        String serviceName = serviceType != null ? serviceType.name() : "unknown";
        Optional<Object> cached = responseCacheService.lookup(key, serviceName, modelName);
        if (cached.isEmpty()) {
            return null;
        }
        logger.info("Response cache hit: service={}, model={}", serviceName, modelName);
        Object value = cached.get();
        // v2.9.10: 流式缓存值 → SSE 逐块回放
        if (value instanceof CachedStreamingResponse streamingResponse) {
            return buildStreamingCacheResponse(streamingResponse);
        }
        // v3.1 PR-4c: 原生面缓存命中 → 原生 JSON（不包 RouterResponse）
        if (isNativeResponse(exchange, httpRequest)) {
            ResponseEntity<?> nativeResponse = buildNativeCacheResponse(value);
            if (nativeResponse != null) {
                return nativeResponse;
            }
            logger.warn("原生面缓存命中但缓存值无法序列化为原生 JSON, 回退 RouterResponse 包裹体: type={}",
                    value.getClass().getName());
        }
        // v2.9.9: 非流式缓存值 → RouterResponse JSON（回归；控制台面行为不变）
        RouterResponse<Object> body = RouterResponse.success(value, "请求成功");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * v3.1 PR-4c: 是否原生面请求（{@code /v1/**}）.
     *
     * <p>标记由 {@code OpenAiNativeController} / {@code AnthropicMessagesController} 置入
     * exchange attribute（{@link #NATIVE_RESPONSE_ATTRIBUTE}）；同时兼容处理器侧读
     * {@code httpRequest} attribute 的既有语义（非流式处理器即从此处读取），
     * 两处任一命中即为原生面。</p>
     *
     * @param exchange 原始交换对象，可为 {@code null}
     * @param httpRequest HTTP 请求，可为 {@code null}
     * @return 原生面返回 true
     */
    private boolean isNativeResponse(final ServerWebExchange exchange, final ServerHttpRequest httpRequest) {
        if (exchange != null && Boolean.TRUE.equals(exchange.getAttribute(NATIVE_RESPONSE_ATTRIBUTE))) {
            return true;
        }
        return httpRequest != null
                && Boolean.TRUE.equals(httpRequest.getAttributes().get(NATIVE_RESPONSE_ATTRIBUTE));
    }

    /**
     * v3.1 PR-4c: 原生面缓存命中响应（原生 JSON 文本）.
     *
     * @param value 缓存值（下游转换后的数据：JSON 文本或结构化对象）
     * @return 原生 JSON 响应；无法序列化（未注入 ObjectMapper 或序列化失败）时返回 {@code null}
     *         ——由调用方回退包裹体，保证不会返回破损响应
     */
    private ResponseEntity<?> buildNativeCacheResponse(final Object value) {
        String json = toNativeJson(value);
        if (json == null) {
            return null;
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    /**
     * v3.1 PR-4c: 缓存值 → 原生 JSON 文本.
     *
     * <p>与 {@code NonStreamingRequestProcessor#nativeJson} 同口径：已是合法 JSON 文本时原样返回
     * （保留下游原始字段/顺序），否则以 ObjectMapper 序列化；未注入 ObjectMapper 时仅文本可原样返回。</p>
     *
     * @param value 缓存值
     * @return JSON 文本；不可得返回 {@code null}
     */
    private String toNativeJson(final Object value) {
        if (objectMapper == null) {
            return value instanceof String text ? text : null;
        }
        if (value instanceof String text) {
            try {
                objectMapper.readTree(text);
                return text;
            } catch (JsonProcessingException e) {
                logger.debug("原生面缓存值非合法 JSON 文本, 转为 JSON 字符串: {}", e.getOriginalMessage());
            }
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            logger.warn("原生面缓存值序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * v2.9.10: 将流式缓存值构建为 SSE 响应.
     *
     * <p>逐块回放 {@link CachedStreamingResponse#chunks()} 中的变换后 data 串，
     * 每块包装为 {@code ServerSentEvent}，由 Spring WebFlux SSE 序列化器
     * 自动添加 {@code data:} 前缀和事件分隔，与正常流式路径出站结构一致。
     *
     * @param cachedResponse 流式缓存值
     * @return {@code text/event-stream} 响应实体
     */
    private ResponseEntity<?> buildStreamingCacheResponse(final CachedStreamingResponse cachedResponse) {
        Flux<ServerSentEvent<String>> flux = Flux.fromIterable(cachedResponse.chunks())
                .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(flux);
    }

    /**
     * 从 ServerWebExchange 获取追踪上下文.
     */
    private TracingContext getTracingContext(final ServerWebExchange exchange) {
        if (exchange != null) {
            return exchange.getAttribute(TracingConstants.ContextKeys.TRACING_CONTEXT);
        }
        return null;
    }

    /**
     * 检查调用主体是否具有访问指定服务类型的权限.
     *
     * <p>权限检查逻辑：
     * <ul>
     *   <li>API Key：具有 ADMIN 权限则放行所有服务；否则要求对应服务类型权限
     *       （如 ROLE_CHAT, ROLE_EMBEDDING 等）</li>
     *   <li>控制台 JWT：一律放行——测试场属「我的」菜单分组，所有登录角色可用
     *       （固定权限矩阵语义；{@code /v1/**} 入口已由 SecurityConfiguration 要求
     *       authenticated，控制台语义下无需再查服务角色）</li>
     * </ul>
     *
     * @param authentication 已认证主体（API Key 或 JWT）
     * @param serviceType 服务类型
     * @return 是否具有权限
     */
    private boolean hasServicePermission(final Authentication authentication, final ServiceType serviceType) {
        // 固定权限矩阵（2026-11）：控制台登录态（JWT）按「我的」分组语义放行。
        // 旧实现要求 ROLE_ADMIN 或 ai:playground:use 权限码 authority，而权限码体系已移除，
        // JWT 不再携带该 authority —— 继续检查会把全部非 ADMIN 控制台用户误判 403。
        if (authentication instanceof JwtAuthentication) {
            return true;
        }

        String requiredRole = "ROLE_" + serviceType.name().toUpperCase();

        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String authorityName = authority.getAuthority();

            // ADMIN 权限允许访问所有服务
            if (ROLE_ADMIN_AUTHORITY.equals(authorityName)) {
                return true;
            }

            // 检查是否具有对应服务类型的权限
            if (requiredRole.equals(authorityName)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检查调用主体是否可访问指定模型分组（API Key 分组级白名单）。
     * 白名单为空（null 或空集合）表示放行该服务类型下所有分组（兼容老 Key）。
     *
     * <p><b>v3.5 治理收口：</b>模型名与分组名合并为单一语义——请求的 {@code model} 参数
     * 必须是模型分组（虚拟模型名），实例名不再有独立授权通道，因此这里只查分组白名单。</p>
     *
     * @param auth      已认证的 API Key 主体
     * @param modelName 调用方请求中的 model 参数（模型分组名）
     * @return 是否具有权限
     */
    private boolean hasModelPermission(final ApiKeyAuthentication auth, final String modelName) {
        final List<String> allowedGroups = auth.getAllowedGroups();
        if (allowedGroups == null || allowedGroups.isEmpty()) {
            return true;
        }
        return allowedGroups.contains(modelName);
    }

    /**
     * 判断已认证主体是否携带指定 authority.
     */
    private static boolean hasAuthority(final Authentication authentication, final String authority) {
        if (authentication.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority grantedAuthority : authentication.getAuthorities()) {
            if (authority.equals(grantedAuthority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 记录请求指标.
     */
    private void recordRequestMetrics(
            final String service,
            final String method,
            final long duration,
            final String status,
            final long requestSize,
            final long responseSize) {
        if (metricsCollector == null) {
            return;
        }
        try {
            metricsCollector.recordRequest(service, method, duration, status);
            if (requestSize > 0 || responseSize > 0) {
                metricsCollector.recordRequestSize(service, requestSize, responseSize);
            }
        } catch (Exception e) {
            logger.debug("Failed to record metrics: {}", e.getMessage());
        }
    }

    /**
     * 获取响应状态码.
     */
    private String getResponseStatus(final ResponseEntity<?> response) {
        if (response == null) {
            return "unknown";
        }
        return String.valueOf(response.getStatusCode().value());
    }

    /**
     * 获取错误状态码.
     */
    private String getErrorStatus(final Throwable error) {
        if (error instanceof ResponseStatusException) {
            return String.valueOf(((ResponseStatusException) error).getStatusCode().value());
        }
        if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException webEx) {
            return String.valueOf(webEx.getStatusCode().value());
        }
        if (error instanceof org.unreal.modelrouter.common.exception.DownstreamServiceException dsEx) {
            return String.valueOf(dsEx.getStatusCode().value());
        }
        return "500";
    }

    /**
     * 估算请求大小.
     */
    private long estimateRequestSize(final ServerHttpRequest request) {
        try {
            String contentLength = request.getHeaders().getFirst("Content-Length");
            if (contentLength != null) {
                return Long.parseLong(contentLength);
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 估算响应大小.
     */
    private long estimateResponseSize(final ResponseEntity<?> response) {
        try {
            if (response == null || response.getBody() == null) {
                return 0;
            }
            String body = response.getBody().toString();
            return body.getBytes().length;
        } catch (Exception e) {
            return 0;
        }
    }
}
