import request from '@/utils/request'
import type { RouterResponse } from '@/types'

/**
 * 配额管理 API 封装
 *
 * 对应后端：
 * - GET  /api/config/quota                获取配额运行时配置
 * - PUT  /api/config/quota                热改配额运行时配置（仅 hot-editable 字段）
 * - GET  /api/monitoring/quota/status      获取配额运行态
 * - GET  /api/monitoring/quota/usage       查询配额多维用量
 */

export interface QuotaRetention {
  minute: string
  hour: string
  day: string
  month: string
}

export interface QuotaDistributed {
  enabled: boolean
  keyPrefix: string
  timeoutMs: number
  degradeToLocal: boolean
}

export interface QuotaConfig {
  enabled: boolean
  failOpen: boolean
  windows: string[]
  flushIntervalSeconds: number
  retention: QuotaRetention
  distributed: QuotaDistributed
  backendName: string
  hotEditableFields: string[]
  restartRequiredFields: string[]
  /** 组织维额度灰度开关（默认 false，后端 v2.5+ 提供；旧后端缺失时按 false 处理） */
  userScopeEnabled?: boolean
  departmentScopeEnabled?: boolean
  tenantScopeEnabled?: boolean
}

/** 热改请求体（全部可空，null/省略 = 不改） */
export interface QuotaConfigPayload {
  enabled?: boolean | null
  failOpen?: boolean | null
  windows?: string[] | null
  userScopeEnabled?: boolean | null
  departmentScopeEnabled?: boolean | null
  tenantScopeEnabled?: boolean | null
}

export interface RedisProbe {
  reachable: boolean
  status: string
  reason?: string
}

export interface CounterMetrics {
  degradationCount: number
}

export interface QuotaStatus {
  enabled: boolean
  backendName: string
  degraded: boolean
  degradedReason?: string
  failOpen: boolean
  windows: string[]
  distributed: QuotaDistributed
  redisProbe?: RedisProbe
  counterMetrics?: CounterMetrics
}

/**
 * 配额用量维度（对应后端 QuotaDimension 六维）。
 *
 * v3.5 治理收口：新增 groupId（模型分组 / 池名，治理主键）。
 * 单线治理下账本明细行同时带 groupId（分组桶，model 为空）与 model（成员桶，实例名）。
 */
export interface QuotaUsageDimensions {
  tenantId: string
  apiKeyId: string
  userId: string
  serviceType: string
  model: string
  groupId: string
}

/**
 * 用量行类型。
 *
 * - GROUP：分组聚合行（该分组的总用量，与分组级限额判定口径一致）
 * - MEMBER：组内明细行（按实例拆分的用量分布）
 * - MODEL：厂商模型维度行（已接入模型自己的额度与限速用量）
 * - KEY：API Key 维度行（密钥总闸）
 *
 * GROUP 与 MEMBER 是互斥的两套口径，不可相加（相加会让分组用量翻倍）。
 */
export type QuotaUsageScope = 'GROUP' | 'MEMBER' | 'MODEL' | 'KEY'

export interface QuotaUsageItem {
  dimensions: QuotaUsageDimensions
  scope?: QuotaUsageScope | string
  window: string
  windowStart: string
  requestCount: number
  tokenCount: number
}

export interface QuotaUsageQuery {
  tenantId?: string
  apiKeyId?: string
  userId?: string
  serviceType?: string
  model?: string
  /** 模型分组（池）名；指定后后端返回「分组含组内明细」 */
  groupId?: string
  window?: string
}

/**
 * 获取配额运行时配置
 * GET /api/config/quota → RouterResponse<QuotaConfig>
 */
export const getQuotaConfig = async (): Promise<QuotaConfig | null> => {
  try {
    const response = await request.get<RouterResponse<QuotaConfig>>('/config/quota')
    return response.data.data ?? null
  } catch {
    return null
  }
}

/**
 * 热改配额运行时配置
 * PUT /api/config/quota
 * 400 时抛出 AxiosError，调用方需从 error.response.data 提取后端 message。
 */
export const updateQuotaConfig = async (payload: QuotaConfigPayload): Promise<QuotaConfig> => {
  const response = await request.put<RouterResponse<QuotaConfig>>('/config/quota', payload)
  return response.data.data as QuotaConfig
}

/**
 * 获取配额运行态
 * GET /api/monitoring/quota/status → RouterResponse<QuotaStatus>
 */
export const getQuotaStatus = async (): Promise<QuotaStatus | null> => {
  try {
    const response = await request.get<RouterResponse<QuotaStatus>>('/monitoring/quota/status')
    return response.data.data ?? null
  } catch {
    return null
  }
}

/**
 * 查询配额多维用量
 * GET /api/monitoring/quota/usage?tenantId=&groupId=&... → RouterResponse<QuotaUsageItem[]>
 * 配额未启用时返回 data=[] 且 message="配额账本未启用"（HTTP 200，非错误）。
 *
 * 指定 groupId 时，后端返回「分组含组内明细」：分组聚合行（scope=GROUP）
 * + 组内成员明细行（scope=MEMBER），二者口径互斥、不可相加。
 */
export const getQuotaUsage = async (query: QuotaUsageQuery): Promise<QuotaUsageItem[]> => {
  try {
    const params: Record<string, string> = {}
    if (query.tenantId) params.tenantId = query.tenantId
    if (query.apiKeyId) params.apiKeyId = query.apiKeyId
    if (query.userId) params.userId = query.userId
    if (query.serviceType) params.serviceType = query.serviceType
    if (query.model) params.model = query.model
    if (query.groupId) params.groupId = query.groupId
    if (query.window) params.window = query.window

    const response = await request.get<RouterResponse<QuotaUsageItem[]>>(
      '/monitoring/quota/usage',
      { params }
    )
    return response.data.data ?? []
  } catch {
    return []
  }
}

/**
 * 查询「我的密钥」的用量（员工自助端点）
 * GET /api/monitoring/quota/mine/usage?apiKeyId=&window=MONTH
 *
 * <p>与 getQuotaUsage 的区别：全局 /usage 是运维观测面（OPS，可传任意维度、
 * 不做属主校验），普通用户调用会 403；本端点权限为 SELF，服务端按登录用户
 * 校验 keyId 的有效持有人，只返回本人密钥的用量，其余维度一律不可指定。</p>
 *
 * <p>故意不吞异常：调用方（MyKeysView）需要把失败的密钥标记为「—」，
 * 而不是像旧实现那样把 403 静默当成「用量 0」。</p>
 */
export const getMyQuotaUsage = async (
  apiKeyId: string,
  window = 'MONTH'
): Promise<QuotaUsageItem[]> => {
  const response = await request.get<RouterResponse<QuotaUsageItem[]>>(
    '/monitoring/quota/mine/usage',
    { params: { apiKeyId, window } }
  )
  return response.data.data ?? []
}
