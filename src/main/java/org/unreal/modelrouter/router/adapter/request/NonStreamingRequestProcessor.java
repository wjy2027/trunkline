package org.unreal.modelrouter.router.adapter.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import org.unreal.modelrouter.auth.sanitization.SanitizationService;
import org.unreal.modelrouter.auth.security.quota.QuotaReservation;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.common.exception.DownstreamServiceException;
import org.unreal.modelrouter.common.util.LogSanitizer;
import org.unreal.modelrouter.monitor.callhistory.config.CallHistoryProperties;
import org.unreal.modelrouter.monitor.callhistory.config.RecordLevel;
import org.unreal.modelrouter.monitor.tracing.TracingContextHolder;
import org.unreal.modelrouter.router.adapter.builder.RequestBuilder;
import org.unreal.modelrouter.router.adapter.error.ErrorTargetResolver;
import org.unreal.modelrouter.router.adapter.handler.MultipartRequestHandler;
import org.unreal.modelrouter.router.adapter.metrics.AdapterMetricsRecorder;
import org.unreal.modelrouter.router.cache.ResponseCacheService;
import org.unreal.modelrouter.router.handler.ServiceRequestHandler;
import org.unreal.modelrouter.router.model.ModelRouterProperties.ModelInstance;
import org.unreal.modelrouter.router.model.ModelServiceRegistry.ServiceType;

import java.util.Map;
import java.util.function.Function;

/**
 * 非流式请求处理器 - v2.26.6
 *
 * 从 BaseAdapter 提取的非流式请求处理逻辑，支持：
 * 1. 请求转换（transformRequest）
 * 2. 响应转换（transformResponse）
 * 3. RouterResponse 包装
 * 4. 二进制响应处理
 * 5. 指标记录
 */
import org.springframework.stereotype.Component;

@Component
public class NonStreamingRequestProcessor {

    private static final Logger logger = LoggerFactory.getLogger(NonStreamingRequestProcessor.class);

    private final ObjectMapper objectMapper;
    private final RequestBuilder requestBuilder;
    private final AdapterMetricsRecorder metricsRecorder;
    private final TokenUsageExtractor tokenUsageExtractor;

    @Autowired(required = false)
    private CallHistoryProperties callHistoryProperties;

    @Autowired(required = false)
    private SanitizationService sanitizationService;

    /**
     * v2.9.9: 响应缓存门面（可选注入，未装配时跳过缓存写）
     */
    @Autowired(required = false)
    private ResponseCacheService responseCacheService;

    public NonStreamingRequestProcessor(
            final ObjectMapper objectMapper,
            final RequestBuilder requestBuilder,
            final AdapterMetricsRecorder metricsRecorder,
            final TokenUsageExtractor tokenUsageExtractor) {
        this.objectMapper = objectMapper;
        this.requestBuilder = requestBuilder;
        this.metricsRecorder = metricsRecorder;
        this.tokenUsageExtractor = tokenUsageExtractor;
    }

    /**
     * 处理非流式请求（统一入口）
     *
     * @param request            原始请求对象
     * @param authorization      授权头
     * @param client             WebClient 实例
     * @param path               请求路径
     * @param selectedInstance   选中的实例
     * @param serviceType        服务类型
     * @param responseType       响应类型（String.class 或 byte[].class）
     * @param adapterType        适配器类型
     * @param transformRequestFn 请求转换函数
     * @param transformResponseFn 响应转换函数
     * @param multipartHandler   Multipart 请求处理器
     * @return 响应实体
     */
    public <T> Mono<? extends ResponseEntity<?>> processRequest(
            final T request,
            final String authorization,
            final WebClient client,
            final String path,
            final ModelInstance selectedInstance,
            final ServiceType serviceType,
            final Class<?> responseType,
            final String adapterType,
            final Function<Object, Object> transformRequestFn,
            final Function<Object, Object> transformResponseFn,
            final MultipartRequestHandler multipartHandler,
            final ServerHttpRequest httpRequest,
            final Map<String, String> additionalHeaders) {

        // 0. 从请求属性中获取 API Key ID（由 ServiceRequestHandler 在认证阶段存入）
        final String capturedKeyId = tokenUsageExtractor.extractKeyIdFromRequest(httpRequest);
        // v3.1 PR-2: 配额预留凭据（handler 在 selectInstance 前挂载；未预扣/账本未启用时为 null）
        final QuotaReservation quotaReservation = QuotaReservation.from(httpRequest);
        // v2.9.9: 从请求属性读取响应缓存键（由 ServiceRequestHandler 认证阶段生成；
        // 流式/非确定性/未启用请求键为空 → 写缓存自然跳过）
        final String responseCacheKey = extractResponseCacheKey(httpRequest);

        // 1. 请求转换
        Object transformedRequest = transformRequestFn.apply(request);
        String instanceName = selectedInstance.getName();
        // v2.8.9: 池路由后,下游请求 model 改写为实际实例模型名(虚拟模型名不再透传上游)
        String requestedModel = tokenUsageExtractor.extractRequestModel(request);
        // 网关凭据优先：处理器线程上租户上下文不可达时 isPoolName 误判，池名会透传上游(404)
        transformedRequest = tokenUsageExtractor.rewriteModelField(transformedRequest, requestedModel,
                instanceName, gatewayRewriteTarget(httpRequest));
        long requestStartTime = System.currentTimeMillis();

        // v2.9.2: 记录治理 - 序列化转换后的请求体用于调用历史捕获
        String capturedRequestBody = null;
        RecordLevel recordLevel = resolveRecordLevel();
        if (recordLevel != RecordLevel.METADATA_ONLY) {
            capturedRequestBody = serializeAndTruncate(transformedRequest);
        }

        logger.debug("发送请求到下游服务: instance={}, path={}, auth={}",
                instanceName, path, authorization != null ? "***" : "null");

        // 2. 构建请求 - 不在这里设置 Authorization，统一由实例 headers 处理
        WebClient.RequestBodySpec requestSpec = client.post()
                .uri(path);

        // 3. 配置请求头
        if (multipartHandler != null) {
            requestSpec = multipartHandler.configureRequestHeaders(requestSpec, request, selectedInstance);
        } else {
            // 对于普通JSON请求，设置Content-Type
            requestSpec = requestSpec.contentType(MediaType.APPLICATION_JSON);

            // 优先使用实例自定义headers中的Authorization，如果没有则使用参数传入的authorization
            boolean hasInstanceAuth = false;
            if (selectedInstance != null && selectedInstance.getHeaders() != null) {
                for (Map.Entry<String, String> header : selectedInstance.getHeaders().entrySet()) {
                    requestSpec = requestSpec.header(header.getKey(), header.getValue());
                    logger.debug("应用实例自定义请求头: {} = {}", header.getKey(), "***");
                    if ("Authorization".equalsIgnoreCase(header.getKey())) {
                        hasInstanceAuth = true;
                    }
                }
            }

            // 如果实例没有自定义Authorization，且参数提供了authorization，则使用参数值
            if (!hasInstanceAuth && authorization != null && !authorization.isEmpty()) {
                requestSpec = requestSpec.header("Authorization", authorization);
                logger.debug("使用参数Authorization头");
            }

            // 应用适配器特有的额外请求头
            if (additionalHeaders != null && !additionalHeaders.isEmpty()) {
                for (Map.Entry<String, String> header : additionalHeaders.entrySet()) {
                    requestSpec = requestSpec.header(header.getKey(), header.getValue());
                    logger.debug("应用适配器额外请求头: {} = {}", header.getKey(), "***");
                }
            }
        }

        // C-06：非幂等 POST 重试去重——把网关在入口生成、贯穿重试/故障转移的稳定幂等键
        // 透传给下游。多实例故障转移时下游可据此识别"已处理但响应丢失"的重复投递，
        // 避免同一笔模型调用被重复生成、重复计费。JSON 与 multipart 两条分支都注入。
        if (httpRequest != null) {
            Object idemKey = httpRequest.getAttributes()
                    .get(org.unreal.modelrouter.router.adapter.BaseAdapter.IDEMPOTENCY_KEY_ATTRIBUTE);
            if (idemKey instanceof String key && !key.isEmpty()) {
                requestSpec = requestSpec.header("Idempotency-Key", key);
                logger.debug("注入幂等键用于下游重试去重");
            }
        }

        // 4. 根据响应类型处理
        // #719：attempt 级结算已删除——本 Mono 会被 BaseAdapter 的重试/故障转移消费，
        // 首次可重试错误在此结算（CAS 置 settled=true）会导致后续 attempt 成功时
        // settleSuccess 被 CAS 拒绝、账本漏计。结算收口到整条链末端：
        // 成功 → token 落库点（TokenUsageExtractor）；最终失败/取消 → ServiceRequestHandler
        // 末端的 doOnError/doOnCancel；重试耗尽转入降级 → BaseAdapter 降级入口。
        Mono<? extends ResponseEntity<?>> result;
        if (responseType == byte[].class) {
            result = processBinaryResponse(requestSpec, transformedRequest, path,
                    instanceName, adapterType, serviceType, requestStartTime, multipartHandler,
                    selectedInstance, requestedModel);
        } else {
            result = processJsonResponse(requestSpec, transformedRequest, instanceName,
                    adapterType, serviceType, requestStartTime, path, transformResponseFn, multipartHandler,
                    capturedKeyId, responseCacheKey, requestedModel, capturedRequestBody, selectedInstance,
                    quotaReservation, isNativeResponse(httpRequest), httpRequest);
        }
        return result;
    }

    /**
     * 处理二进制响应（TTS/STT 等）
     */
    private Mono<? extends ResponseEntity<?>> processBinaryResponse(
            final WebClient.RequestBodySpec requestSpec,
            final Object transformedRequest,
            final String path,
            final String instanceName,
            final String adapterType,
            final ServiceType serviceType,
            final long requestStartTime,
            final MultipartRequestHandler multipartHandler,
            final ModelInstance selectedInstance,
            final String requestedModel) {

        // #1011: 二进制路径同口径的错误目标（host:port 脱敏），只作 logger 占位参数
        final String errorTarget = ErrorTargetResolver.hostPortFromUrl(selectedInstance.getBaseUrl());

        BodyInserter<?, ? super ClientHttpRequest> requestBody;
        if (multipartHandler != null) {
            requestBody = multipartHandler.createRequestBody(transformedRequest);
        } else {
            // 对于普通JSON请求，使用JSON body
            requestBody = org.springframework.web.reactive.function.BodyInserters.fromValue(transformedRequest);
        }

        return requestSpec
                .body(requestBody)
                .exchangeToMono(clientResponse -> {
                    // 处理 5xx 服务器错误
                    if (clientResponse.statusCode().is5xxServerError()) {
                        return Mono.error(new ResponseStatusException(clientResponse.statusCode()));
                    }

                    // 处理 4xx 客户端错误
                    if (clientResponse.statusCode().is4xxClientError()) {
                        handleClientError(clientResponse.statusCode(), instanceName, errorTarget, path);
                        return Mono.error(new ResponseStatusException(clientResponse.statusCode()));
                    }

                    // 获取响应体和响应头
                    return clientResponse.bodyToMono(byte[].class)
                            .map(body -> buildBinaryResponseEntity(clientResponse, body))
                            .switchIfEmpty(Mono.fromSupplier(() -> buildEmptyResponseEntity(clientResponse)));
                })
                .doOnSuccess(responseEntity -> {
                    if (metricsRecorder != null && responseEntity != null) {
                        long responseSize = responseEntity.getBody() != null
                                ? ((byte[]) responseEntity.getBody()).length : 0;
                        metricsRecorder.recordResponseTime(serviceType, "POST",
                                System.currentTimeMillis() - requestStartTime, "200");
                        // v2.9.2: 二进制响应记录调用历史（仅元数据，body 为二进制不捕获）
                        long duration = System.currentTimeMillis() - requestStartTime;
                        // 统计由 BaseAdapter.doOnSuccess 的 recordCompleteCallStats 统一记一次，
                        // 此处只记录调用历史，避免一次成功请求被记成两次（LB 在途双减等）。
                        metricsRecorder.recordCallHistoryOnly(adapterType, instanceName, duration, true,
                                null, requestedModel, serviceType, selectedInstance);
                    }
                })
                .doOnError(throwable -> {
                    if (metricsRecorder != null) {
                        metricsRecorder.recordError(adapterType, instanceName,
                                throwable.getClass().getSimpleName(), throwable,
                                System.currentTimeMillis() - requestStartTime, serviceType);
                    }
                    // #1011: 连接级失败（WebClientRequestException 等）此前无任何日志；
                    // 响应级 5xx/4xx 已在 onStatus 记过，此处只补记其余异常。
                    // target 沿 cause 链取真实 URI 的 host:port；不打印 getMessage() 原文（可能含凭证）。
                    if (!(throwable instanceof ResponseStatusException)) {
                        logger.error("下游调用失败: instance={}, target={}, error={}",
                                instanceName,
                                ErrorTargetResolver.resolveTarget(throwable, selectedInstance.getBaseUrl()),
                                throwable.getClass().getSimpleName());
                    }
                });
    }

    /**
     * 处理 JSON 响应（v2.9.2: 支持请求/响应体捕获）
     */
    private Mono<? extends ResponseEntity<?>> processJsonResponse(
            final WebClient.RequestBodySpec requestSpec,
            final Object transformedRequest,
            final String instanceName,
            final String adapterType,
            final ServiceType serviceType,
            final long requestStartTime,
            final String path,
            final Function<Object, Object> transformResponseFn,
            final MultipartRequestHandler multipartHandler,
            final String capturedKeyId,
            final String responseCacheKey,
            final String requestedModel,
            final String capturedRequestBody,
            final ModelInstance selectedInstance,
            final QuotaReservation quotaReservation,
            final boolean nativeResponse,
            final ServerHttpRequest httpRequest) {

        // 关联链路追踪：在请求处理线程（追踪上下文存活）同步捕获 traceId。
        // WebClient 响应回调线程上 ThreadLocal 已丢失，异步落库时再取只会拿到空串，
        // 导致 token_usage / 调用历史无法通过 traceId 反查追踪与账单对账。
        final String traceIdForRecord = TracingContextHolder.getCurrentTraceId();

        // #1011: 下游错误日志目标（host:port，脱敏：丢 userinfo/query/fragment，不携带凭证）。
        // 只作 logger 占位参数，不拼进异常 message/reason——全局异常处理器会把 reason 回显给客户端。
        final String errorTarget = ErrorTargetResolver.hostPortFromUrl(selectedInstance.getBaseUrl());

        // 支持multipart请求（如STT）
        BodyInserter<?, ? super ClientHttpRequest> requestBody;
        if (multipartHandler != null && multipartHandler.isMultipartRequest(transformedRequest)) {
            requestBody = multipartHandler.createRequestBody(transformedRequest);
            logger.debug("使用multipart请求体处理STT请求");
        } else {
            requestBody = org.springframework.web.reactive.function.BodyInserters.fromValue(transformedRequest);
        }

        return requestSpec
                .body(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError, clientResponse -> {
                    logger.error("下游服务5xx错误: instance={}, target={}, path={}, status={}",
                            instanceName, errorTarget, path, clientResponse.statusCode());
                    return Mono.error(new ResponseStatusException(clientResponse.statusCode()));
                })
                .onStatus(HttpStatusCode::is4xxClientError, clientResponse -> {
                    return handle4xxError(clientResponse, instanceName, errorTarget, path);
                })
                .toEntity(String.class)
                .flatMap(responseEntity -> {
                    if (!responseEntity.getStatusCode().is2xxSuccessful()) {
                        String bodyStr = responseEntity.getBody() != null ? responseEntity.getBody() : "";
                        return Mono.<ResponseEntity<?>>error(new ResponseStatusException(
                                responseEntity.getStatusCode(), "下游服务异常: " + bodyStr));
                    }

                    try {
                        String bodyStr = responseEntity.getBody();
                        Object downstreamData;

                        // v2.9.2: 记录治理 - 捕获原始下游响应体（在转换前）
                        String capturedResponseBody = null;
                        RecordLevel level = resolveRecordLevel();
                        if (level != RecordLevel.METADATA_ONLY && bodyStr != null && !bodyStr.isEmpty()) {
                            capturedResponseBody = truncate(bodyStr);
                        }

                        if (bodyStr == null || bodyStr.isEmpty()) {
                            downstreamData = null;
                        } else {
                            downstreamData = objectMapper.readValue(bodyStr, Object.class);

                            // 提取并记录 token 使用量（v3.1 PR-2: 同时完成配额结算）
                            // P1-4: 透传下游响应耗时,token_usage.responseTimeMs 供平均响应时间统计
                            // traceId: 传入方法入口同步捕获的值（回调线程无追踪上下文）
                            tokenUsageExtractor.extractAndRecordTokenUsage(bodyStr, adapterType, instanceName,
                                    capturedKeyId, quotaReservation,
                                    System.currentTimeMillis() - requestStartTime, httpRequest,
                                    traceIdForRecord);
                        }

                        // 响应转换
                        Object transformedData = transformResponseFn.apply(downstreamData);
                        // v2.8.9: 池路由后,响应 model 回显实际实例模型名（网关凭据优先，理由同请求侧）
                        transformedData = tokenUsageExtractor.rewriteModelField(transformedData, requestedModel,
                                instanceName, gatewayRewriteTarget(httpRequest));

                        // v2.9.9: 响应缓存写 — 仅 2xx 成功分支到达此处(非2xx 已先行短路)；
                        // 流式/非确定性/未启用请求键为空，此处不写
                        cacheSuccessfulResponse(responseCacheKey, transformedData);

                        // v2.9.2: 记录治理 - 异步记录含请求/响应体的调用历史
                        // traceId: 传入同步捕获的值，保证调用历史与追踪系统同源（不再退化为随机 UUID）
                        if (metricsRecorder != null) {
                            // 统计归 BaseAdapter.recordCompleteCallStats（恰一次），此处只落调用历史；
                            // 若继续用 recordCompleteCall 会让 Registry/MetricsCollector/StatsRepository 全双记。
                            metricsRecorder.recordCallHistoryOnly(
                                    adapterType, instanceName,
                                    System.currentTimeMillis() - requestStartTime,
                                    true, null, requestedModel, serviceType, selectedInstance,
                                    capturedRequestBody, capturedResponseBody, traceIdForRecord);
                        }

                        // v3.1: OpenAI 原生面（/v1）——不包 RouterResponse，直接回下游原生 JSON
                        if (nativeResponse) {
                            return Mono.just(
                                    ResponseEntity.status(responseEntity.getStatusCode())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .body(nativeJson(transformedData, downstreamData)));
                        }

                        // 包装 RouterResponse（控制台面 /api/v1）
                        RouterResponse<Object> finalResponse = RouterResponse.success(transformedData, "请求成功");

                        return Mono.just(
                                ResponseEntity.status(responseEntity.getStatusCode())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(finalResponse));

                    } catch (JsonProcessingException e) {
                        // issue #725: 不打全量正文——坏 JSON 的 Jackson 异常 message 常带原文片段
                        //（可能含密钥），正文只打脱敏摘要（前 512 字符），异常只打类名不打 getMessage() 原文
                        final String bodyText = responseEntity.getBody() != null
                                ? responseEntity.getBody().toString() : "";
                        final String bodyPreview = LogSanitizer.sanitize(
                                bodyText.substring(0, Math.min(bodyText.length(), 512)));
                        logger.error("无法解析下游服务的响应体: traceId={}, bodyLen={}, status={}, bodyPreview={}, exception={}",
                                traceIdForRecord, bodyText.length(),
                                responseEntity.getStatusCode(), bodyPreview,
                                e.getClass().getSimpleName());
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR, "无法解析下游服务响应"));
                    }
                })
                .doOnSuccess(responseEntity -> {
                    if (metricsRecorder != null && responseEntity != null) {
                        String bodyStr = responseEntity.getBody() != null
                                ? responseEntity.getBody().toString() : "";
                        metricsRecorder.recordRequestSize(serviceType,
                                transformedRequest.toString().getBytes().length, bodyStr.getBytes().length);
                        metricsRecorder.recordResponseTime(serviceType, "POST",
                                System.currentTimeMillis() - requestStartTime,
                                responseEntity.getStatusCode().toString());
                    }
                })
                .doOnError(throwable -> {
                    if (metricsRecorder != null) {
                        metricsRecorder.recordError(adapterType, instanceName,
                                throwable.getClass().getSimpleName(), throwable,
                                System.currentTimeMillis() - requestStartTime, serviceType);
                    }
                    // #1011: 同 processBinaryResponse——只补记非响应级异常，target 沿 cause 链解析
                    if (!(throwable instanceof ResponseStatusException)) {
                        logger.error("下游调用失败: instance={}, target={}, error={}",
                                instanceName,
                                ErrorTargetResolver.resolveTarget(throwable, selectedInstance.getBaseUrl()),
                                throwable.getClass().getSimpleName());
                    }
                });
    }

    /**
     * v2.9.9: 从请求属性提取响应缓存键.
     *
     * @param httpRequest HTTP 请求（可能为 null）
     * @return 缓存键；无键或属性为空返回 null
     */
    /**
     * v3.1: 是否 OpenAI 原生响应（由 {@code /v1} 原生控制器在 exchange 上标记）.
     *
     * @param httpRequest 当前请求（与 exchange 共享 attribute map）
     * @return 标记为 {@link Boolean#TRUE} 时返回 true
     */
    private boolean isNativeResponse(final ServerHttpRequest httpRequest) {
        final Object flag = httpRequest.getAttributes().get(ServiceRequestHandler.NATIVE_RESPONSE_ATTRIBUTE);
        return Boolean.TRUE.equals(flag);
    }

    /**
     * v3.1: 生成 OpenAI 原生响应体 JSON.
     *
     * <p>变换结果已是合法 JSON 文本时原样返回；若为 {@code Map.toString()} 形式（部分适配器默认
     * 变换的行为）或结构化对象，则回退到解析后的下游对象并以 ObjectMapper 序列化，保证输出为
     * 合法 JSON 对象（OpenAI SDK 可直接解析）。</p>
     *
     * @param transformed 响应变换结果
     * @param downstream  下游原始解析对象
     * @return 原生 JSON 文本
     */
    private String nativeJson(final Object transformed, final Object downstream) {
        if (transformed instanceof String text) {
            try {
                objectMapper.readTree(text);
                return text;
            } catch (Exception ignored) {
                logger.debug("原生响应: 变换结果非合法 JSON, 回退结构化下游对象");
            }
        }
        final Object source = (transformed instanceof String || transformed == null) ? downstream : transformed;
        try {
            return objectMapper.writeValueAsString(source);
        } catch (JsonProcessingException e) {
            logger.warn("原生响应序列化失败, 回退字符串输出: {}", e.getMessage());
            return String.valueOf(source);
        }
    }

    private String extractResponseCacheKey(final ServerHttpRequest httpRequest) {        if (httpRequest == null) {
            return null;
        }
        Object cacheKey = httpRequest.getAttributes().get(ServiceRequestHandler.CACHE_KEY_ATTRIBUTE);
        if (cacheKey instanceof String key && !key.isBlank()) {
            return key;
        }
        return null;
    }

    /**
     * v2.9.9: 写入响应缓存.
     *
     * <p>仅在 2xx 成功路径（processJsonResponse 内、非 2xx 已先行短路）调用；
     * 键为空（流式/非确定性/缓存未启用，由 handler 侧判定）或数据为 null 时不写。
     *
     * @param cacheKey 缓存键
     * @param transformedData 下游转换后的 data（缓存命中时原样返回的内容）
     */
    void cacheSuccessfulResponse(final String cacheKey, final Object transformedData) {
        if (cacheKey == null || transformedData == null || responseCacheService == null) {
            return;
        }
        responseCacheService.store(cacheKey, transformedData);
    }

    /**
     * 处理 4xx 错误
     */
    private Mono<Throwable> handle4xxError(final ClientResponse clientResponse,
                                            final String instanceName,
                                            final String target,
                                            final String path) {
        HttpStatusCode statusCode = clientResponse.statusCode();

        if (statusCode.value() == 401) {
            logger.error("下游服务认证失败 (401): instance={}, target={}, path={}", instanceName, target, path);
            return Mono.error(new DownstreamServiceException(
                    "下游服务认证失败，请检查下游服务的认证配置", HttpStatus.valueOf(401)));
        } else if (statusCode.value() == 400) {
            logger.error("下游服务请求错误 (400): instance={}, target={}, path={}", instanceName, target, path);
            return Mono.error(new DownstreamServiceException(
                    "下游服务请求参数错误，请检查请求内容", HttpStatus.valueOf(400)));
        } else if (statusCode.value() == 503) {
            logger.error("下游服务不可用 (503): instance={}, target={}, path={}", instanceName, target, path);
            return Mono.error(new DownstreamServiceException(
                    "下游服务暂时不可用，请稍后重试", HttpStatus.valueOf(503)));
        }

        logger.error("下游服务4xx错误: instance={}, target={}, path={}, status={}",
                instanceName, target, path, statusCode);
        return Mono.error(new ResponseStatusException(statusCode));
    }

    /**
     * 处理客户端错误（记录日志）
     */
    private void handleClientError(final HttpStatusCode statusCode,
                                    final String instanceName,
                                    final String target,
                                    final String path) {
        if (statusCode.value() == 401) {
            logger.error("下游服务认证失败 (401): instance={}, target={}, path={}", instanceName, target, path);
        } else if (statusCode.value() == 400) {
            logger.error("下游服务请求错误 (400): instance={}, target={}, path={}", instanceName, target, path);
        } else if (statusCode.value() == 503) {
            logger.error("下游服务不可用 (503): instance={}, target={}, path={}", instanceName, target, path);
        }
    }

    /**
     * 构建二进制响应实体
     */
    private ResponseEntity<byte[]> buildBinaryResponseEntity(final ClientResponse clientResponse, final byte[] body) {
        ResponseEntity.BodyBuilder responseBuilder = clientResponse.statusCode().is2xxSuccessful()
                ? ResponseEntity.ok()
                : ResponseEntity.status(clientResponse.statusCode());

        HttpHeaders downstreamHeaders = clientResponse.headers().asHttpHeaders();

        // 复制重要响应头
        if (downstreamHeaders.getContentType() != null) {
            responseBuilder.contentType(downstreamHeaders.getContentType());
        }
        if (downstreamHeaders.getContentLength() > 0) {
            responseBuilder.contentLength(downstreamHeaders.getContentLength());
        }

        // 复制 Content-Disposition（文件下载）
        String contentDisposition = downstreamHeaders.getFirst("Content-Disposition");
        if (contentDisposition != null) {
            responseBuilder.header("Content-Disposition", contentDisposition);
        }

        // 复制缓存相关头
        String cacheControl = downstreamHeaders.getFirst("Cache-Control");
        if (cacheControl != null) {
            responseBuilder.header("Cache-Control", cacheControl);
        }
        String etag = downstreamHeaders.getFirst("ETag");
        if (etag != null) {
            responseBuilder.header("ETag", etag);
        }
        String lastModified = downstreamHeaders.getFirst("Last-Modified");
        if (lastModified != null) {
            responseBuilder.header("Last-Modified", lastModified);
        }

        return responseBuilder.body(body);
    }

    /**
     * 构建空响应实体
     */
    private ResponseEntity<byte[]> buildEmptyResponseEntity(final ClientResponse clientResponse) {
        ResponseEntity.BodyBuilder responseBuilder = clientResponse.statusCode().is2xxSuccessful()
                ? ResponseEntity.ok()
                : ResponseEntity.status(clientResponse.statusCode());

        HttpHeaders downstreamHeaders = clientResponse.headers().asHttpHeaders();
        if (downstreamHeaders.getContentType() != null) {
            responseBuilder.contentType(downstreamHeaders.getContentType());
        }

        return responseBuilder.build();
    }

    // ==================== v2.9.2: 记录治理辅助方法 ====================

    /**
     * 解析当前记录级别（默认 METADATA_ONLY）
     */
    private RecordLevel resolveRecordLevel() {
        if (callHistoryProperties != null) {
            return callHistoryProperties.getRecordLevel();
        }
        return RecordLevel.METADATA_ONLY;
    }

    /**
     * 读取网关下发的池名改写凭据（见 {@link TokenUsageExtractor#GATEWAY_MODEL_REWRITE_ATTRIBUTE}）。
     * 请求属性随 ServerHttpRequest 走，与执行线程无关，规避 boundedElastic 上租户上下文缺失。
     */
    private String gatewayRewriteTarget(final ServerHttpRequest httpRequest) {
        if (httpRequest == null) {
            return null;
        }
        final Object target = httpRequest.getAttributes()
                .get(TokenUsageExtractor.GATEWAY_MODEL_REWRITE_ATTRIBUTE);
        return target instanceof String ? (String) target : null;
    }

    /**
     * 将对象序列化为 JSON 字符串并截断到 maxContentLength
     */
    private String serializeAndTruncate(final Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(obj);
            return truncate(json);
        } catch (JsonProcessingException e) {
            logger.debug("序列化请求体失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 截断字符串到 maxContentLength
     */
    private String truncate(final String content) {
        if (content == null) {
            return null;
        }
        int maxLen = callHistoryProperties != null ? callHistoryProperties.getMaxContentLength() : 65536;
        if (content.length() > maxLen) {
            return content.substring(0, maxLen);
        }
        return content;
    }
}
