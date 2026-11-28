/**
 * 组织维额度 API（用户 / 部门 / 租户三层 token 额度）。
 *
 * - GET    /api/config/quota-scope/{scope}
 * - PUT    /api/config/quota-scope/{scope}/{scopeId}
 * - DELETE /api/config/quota-scope/{scope}/{scopeId}
 */
import request from '@/utils/request'
import type { RouterResponse } from '@/types'

export type OrgScope = 'users' | 'departments' | 'tenants'

export interface OrgScopeQuotaRow {
  scopeId: string
  minuteRequests: number
  hourRequests: number
  hourTokens: number
  dayRequests: number
  dayTokens: number
  monthRequests: number
  monthTokens: number
  [key: string]: unknown
}

export interface OrgScopeQuotaPayload {
  minuteRequests?: number
  hourRequests?: number
  hourTokens?: number
  dayRequests?: number
  dayTokens?: number
  monthRequests?: number
  monthTokens?: number
}

export const getOrgScopeQuotas = async (scope: OrgScope): Promise<OrgScopeQuotaRow[]> => {
  const response = await request.get<RouterResponse<OrgScopeQuotaRow[]>>(
    `/config/quota-scope/${scope}`)
  return response.data.data ?? []
}

export const upsertOrgScopeQuota = async (
  scope: OrgScope,
  scopeId: string,
  payload: OrgScopeQuotaPayload
): Promise<OrgScopeQuotaRow> => {
  const response = await request.put<RouterResponse<OrgScopeQuotaRow>>(
    `/config/quota-scope/${scope}/${encodeURIComponent(scopeId)}`, payload)
  return response.data.data as OrgScopeQuotaRow
}

export const deleteOrgScopeQuota = async (scope: OrgScope, scopeId: string): Promise<void> => {
  await request.delete<RouterResponse<void>>(
    `/config/quota-scope/${scope}/${encodeURIComponent(scopeId)}`)
}
