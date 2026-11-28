/**
 * 配额统计窗口 ↔ 表单字段映射（2026-11-28）。
 *
 * 全局「统计窗口」配置（QuotaProperties.windows，热改）决定账本启用哪些窗口；
 * 各维度配额表单（组织维用户/部门/租户、密钥、虚拟模型、厂商模型）的字段
 * 必须与已启用窗口一致——窗口关闭时对应字段隐藏（后端不判定该窗口，填了也无意义）。
 *
 * 拉取失败或尚未加载时显示全部字段（保守降级：宁可多显示也不锁死编辑入口）。
 */
import { ref, onMounted } from 'vue'
import { getQuotaConfig } from '@/api/quota'

/** 每个配额字段对应的后端窗口（与 QuotaWindow 枚举名一致） */
export const FIELD_WINDOWS: Record<string, string[]> = {
  minuteRequests: ['MINUTE'],
  hourRequests: ['HOUR'],
  hourTokens: ['HOUR'],
  dayRequests: ['DAY'],
  dayTokens: ['DAY'],
  monthRequests: ['MONTH'],
  monthTokens: ['MONTH'],
}

/** 全部配额字段（固定顺序，弹窗/表格共用） */
export const ALL_QUOTA_FIELDS = [
  'minuteRequests',
  'hourRequests',
  'hourTokens',
  'dayRequests',
  'dayTokens',
  'monthRequests',
  'monthTokens',
] as const

export type QuotaField = (typeof ALL_QUOTA_FIELDS)[number]

/**
 * 拉取当前启用的统计窗口，并按窗口过滤字段。
 *
 * @returns fields 当前窗口下可见的字段列表；windows 原始窗口名列表
 */
export function useQuotaWindows() {
  const windows = ref<string[]>([])
  const loaded = ref(false)

  const load = async () => {
    try {
      const c = await getQuotaConfig()
      windows.value = c?.windows ?? []
    } catch {
      windows.value = []
    } finally {
      loaded.value = true
    }
  }

  const isFieldVisible = (field: string): boolean => {
    if (!loaded.value || windows.value.length === 0) return true
    const required = FIELD_WINDOWS[field]
    if (!required) return true
    return required.some(w => windows.value.includes(w))
  }

  /** 当前窗口下可见字段（保持 ALL_QUOTA_FIELDS 顺序） */
  const visibleFields = () => ALL_QUOTA_FIELDS.filter(f => isFieldVisible(f))

  onMounted(load)

  return { windows, loaded, load, isFieldVisible, visibleFields }
}
