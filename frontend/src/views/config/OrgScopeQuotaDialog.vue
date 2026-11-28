<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="520px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <el-form :model="form" label-width="150px" size="small" v-loading="loadingRow">
      <el-form-item :label="t('orgScopeQuota.scopeId')" required>
        <el-input v-model="form.scopeId" :disabled="scopeIdLocked" :placeholder="t('orgScopeQuota.scopeIdPlaceholder')" />
      </el-form-item>
      <el-form-item
        v-for="field in visibleFieldList"
        :key="field"
        :label="t(`orgScopeQuota.${field}`)"
      >
        <el-input-number v-model="form[field]" :min="0" controls-position="right" />
      </el-form-item>
      <!-- 月度金额上限（2026-11-28，对齐全全部密钥配额表单口径）：企业版金额账本，按当月实际花费限制 -->
      <el-form-item v-if="amountAvailable" :label="t('orgScopeQuota.monthlyAmount')">
        <el-input-number
          v-model="amountLimitYuan"
          :min="0"
          :precision="4"
          :step="10"
          controls-position="right"
          :placeholder="t('orgScopeQuota.monthlyAmountPlaceholder')"
        />
        <div class="form-hint">{{ t('orgScopeQuota.monthlyAmountHint') }}</div>
      </el-form-item>
      <el-alert v-if="!amountAvailable" :title="t('orgScopeQuota.monthlyAmountUnavailable')" type="info" :closable="false" show-icon />
      <el-alert :title="t('orgScopeQuota.zeroMeansUnlimited')" type="info" :closable="false" show-icon />
    </el-form>
    <template #footer>
      <el-button @click="visible = false">{{ t('quotaConfig.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">
        {{ t('quotaConfig.confirm') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 组织维配额编辑弹窗（可复用，2026-11-28）。
 *
 * 三类调用场景：
 * - 组织维额度面板（OrgScopeQuotaPanel）：新增 + 编辑，scopeId 可输
 * - 账号管理用户列表「配额」操作：scopeId 锁定为账号 username（= holder）
 * - 企业租户 / 部门列表「配额」操作：scopeId 锁定为租户 ID / 部门名
 *
 * 字段随全局统计窗口联动（useQuotaWindows）：窗口关闭的字段不显示
 * （后端不判定该窗口，配置无意义）。已存在的配置值不受过滤影响——
 * 隐藏字段的值在保存时原样回传，避免「只改一个可见字段把其余清零」。
 */
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { useQuotaWindows, ALL_QUOTA_FIELDS, type QuotaField } from '@/composables/useQuotaWindows'
import {
  getOrgScopeQuotas,
  upsertOrgScopeQuota,
  type OrgScope,
  type OrgScopeQuotaPayload,
} from '@/api/orgScopeQuota'

const props = defineProps<{
  modelValue: boolean
  scope: OrgScope
  /** 编辑目标 scopeId；新增场景允许用户输入 */
  scopeId?: string
  /** true = scopeId 只读（从列表行带入的场景） */
  scopeIdLocked?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved', scopeId: string): void
}>()

const { t } = useI18n()
const { visibleFields, load: loadWindows } = useQuotaWindows()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const loadingRow = ref(false)
const saving = ref(false)

interface FormState {
  scopeId: string
  minuteRequests: number
  hourRequests: number
  hourTokens: number
  dayRequests: number
  dayTokens: number
  monthRequests: number
  monthTokens: number
}
const emptyForm = (): FormState => ({
  scopeId: '', minuteRequests: 0, hourRequests: 0, hourTokens: 0,
  dayRequests: 0, dayTokens: 0, monthRequests: 0, monthTokens: 0,
})
const form = ref<FormState>(emptyForm())

const dialogTitle = computed(() => t('orgScopeQuota.quotaFor', { id: form.value.scopeId || props.scopeId || '' }))

/** 当前窗口下可见字段（保持固定顺序） */
const visibleFieldList = computed<QuotaField[]>(() => visibleFields())

// ── 月度金额上限（2026-11-28）：企业金额账本 /api/config/quota-amount，当月（YYYY-MM）生效 ──
/** org scope → 金额账本 scopeType（与 EnterpriseAmountQuotaExtension.scopesOf 同口径） */
const AMOUNT_SCOPE_TYPE: Record<OrgScope, string> = {
  users: 'USER',
  departments: 'DEPARTMENT',
  tenants: 'TENANT',
}
const currentPeriod = (): string => {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}
/** 金额上限（元）；null = 不限制（保存时删除已有行） */
const amountLimitYuan = ref<number | null>(null)
/** 企业版金额接口是否可用（社区版 404 → 隐藏输入只留提示） */
const amountAvailable = ref(true)

const loadAmountLimit = async (): Promise<void> => {
  const scopeId = props.scopeId
  if (!scopeId) return
  try {
    const res = await request.get('/config/quota-amount/rows', {
      params: { scopeType: AMOUNT_SCOPE_TYPE[props.scope], period: currentPeriod() },
    })
    const rows = (res.data as { data?: Array<{ scopeId?: string; limitMinor?: number }> })?.data ?? []
    const row = rows.find(r => String(r.scopeId) === scopeId)
    amountLimitYuan.value = row && typeof row.limitMinor === 'number'
      ? row.limitMinor / 10000
      : null
    amountAvailable.value = true
  } catch (err: unknown) {
    // 404 = 社区版无企业金额端点 → 隐藏金额输入；其他错误也按不可用降级，不阻塞窗口字段编辑
    amountAvailable.value = false
    amountLimitYuan.value = null
  }
}

const saveAmountLimit = async (): Promise<void> => {
  const scopeId = form.value.scopeId.trim()
  const scopeType = AMOUNT_SCOPE_TYPE[props.scope]
  const period = currentPeriod()
  if (amountLimitYuan.value == null) {
    // 清空 = 解除限制：删除已有行（404/不存在时静默忽略）
    try {
      await request.delete(
        `/config/quota-amount/rows/${scopeType}/${encodeURIComponent(scopeId)}/${period}`
      )
    } catch { /* 行本就不存在，忽略 */ }
    return
  }
  await request.put('/config/quota-amount/rows', {
    scopeType,
    scopeId,
    period,
    // 以字符串传小数：JSON 数值 0.0001 会被 Jackson 按 double 解析成超 4 位小数而 400
    limitYuan: amountLimitYuan.value.toFixed(4),
  })
}

const onOpen = async () => {
  await loadWindows()
  form.value = emptyForm()
  form.value.scopeId = props.scopeId ?? ''
  amountLimitYuan.value = null
  if (props.scopeId) {
    loadingRow.value = true
    try {
      const rows = await getOrgScopeQuotas(props.scope)
      const row = rows.find(r => String(r.scopeId) === props.scopeId)
      if (row) {
        for (const f of ALL_QUOTA_FIELDS) {
          form.value[f] = Number(row[f] ?? 0)
        }
      }
    } catch {
      // 拉不到列表时按全 0 起步（保存即新建）
    } finally {
      loadingRow.value = false
    }
    // 金额上限回填（企业版端点；社区版降级为提示）
    await loadAmountLimit()
  }
}

// scopeId 变化（同一弹窗实例切换目标）时重新回填
watch(() => props.scopeId, (v) => {
  if (visible.value) {
    form.value.scopeId = v ?? ''
  }
})

const handleSave = async () => {
  if (!form.value.scopeId.trim()) {
    ElMessage.warning(t('orgScopeQuota.scopeIdRequired'))
    return
  }
  // 隐藏字段原样回传（保留服务端已有值语义由后端 upsert 覆盖为表单值——
  // 表单初值即来自现有行，故未显示的窗口字段不会被误清零）
  const payload: OrgScopeQuotaPayload = {
    minuteRequests: form.value.minuteRequests,
    hourRequests: form.value.hourRequests,
    hourTokens: form.value.hourTokens,
    dayRequests: form.value.dayRequests,
    dayTokens: form.value.dayTokens,
    monthRequests: form.value.monthRequests,
    monthTokens: form.value.monthTokens,
  }
  saving.value = true
  try {
    await upsertOrgScopeQuota(props.scope, form.value.scopeId.trim(), payload)
    // 金额上限随同一份「保存」落库（企业版端点）；失败不关弹窗，让用户看到具体报错
    if (amountAvailable.value) {
      await saveAmountLimit()
    }
    ElMessage.success(t('orgScopeQuota.saved'))
    visible.value = false
    emit('saved', form.value.scopeId.trim())
  } catch (err: unknown) {
    const axiosErr = err as { response?: { data?: { message?: string } } }
    ElMessage.error(axiosErr.response?.data?.message ?? t('orgScopeQuota.saveFailed'))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.form-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--ja-text-secondary, var(--el-text-color-secondary));
  width: 100%;
}
</style>

