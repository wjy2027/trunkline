<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * 全局配置 → 其他 tab（2026-11-28）。
 *
 * <p>暂未归类的零散功能开关集合：</p>
 * <ol>
 *   <li><b>高级计价热开关</b>（企业版）：后端 {@code /api/config/pricing-advanced}
 *       直改 EnterpriseProperties 内存单例，features 端点与取价链路即时生效（不重启）；
 *       重启后回落到 yml/启动参数静态值。<b>灰度锁三态</b>：billing 功能位解锁 → 可切换；
 *       企业端点在但 billing 未解锁 → info 提示；纯 OSS 部署（端点 404）→ 卡片展示但
 *       开关禁用加锁提示（企业版功能，当前部署灰度未解锁），不弹加载失败报错。</li>
 *   <li><b>PII 脱敏热配置</b>（OSS 原生）：{@code /api/config/sanitization} 支持部分
 *       热改（PUT {@code {request:{enabled}}} / {@code {response:{enabled}}}），
 *       只暴露「请求记录脱敏 / 响应记录脱敏」两个开关与当前生效规则数；
 *       规则明细与试脱敏属独立管理面，不在本 tab 展开。</li>
 * </ol>
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Lock } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { useEnterpriseStore } from '@/stores/enterprise'

const { t } = useI18n()
const enterpriseStore = useEnterpriseStore()

// ── 高级计价（企业版，灰度锁三态） ──────────────────────────────
const loading = ref(false)
const switching = ref(false)
/** endpoint 状态：available=企业端点在 / absent=纯 OSS（404） / unknown=加载中或非 404 错误 */
const pricingEndpoint = ref<'available' | 'absent' | 'unknown'>('unknown')
/** billing 功能位未解锁时开关不展示（后端也会拒绝写入） */
const billingUnlocked = ref(false)
const enabled = ref(false)

/** 高级计价卡片形态：OSS 纯社区版 → 灰度锁（展示但禁用） */
const pricingLocked = computed(() => pricingEndpoint.value === 'absent')
/** billing 未解锁且端点在 → info 提示（企业部署但许可未含 billing） */
const pricingNeedBilling = computed(() => pricingEndpoint.value === 'available' && !billingUnlocked.value)

const loadPricing = async (): Promise<void> => {
  try {
    const res = await request.get('/config/pricing-advanced')
    const data = (res.data as { data?: { advancedEnabled?: boolean; billing?: boolean } })?.data
    pricingEndpoint.value = 'available'
    billingUnlocked.value = data?.billing === true
    enabled.value = data?.advancedEnabled === true
  } catch (err: unknown) {
    const status = (err as { response?: { status?: number } })?.response?.status
    if (status === 404) {
      // 纯 OSS 部署无企业控制器 → 灰度锁形态，不是错误
      pricingEndpoint.value = 'absent'
      billingUnlocked.value = false
      enabled.value = false
    } else {
      pricingEndpoint.value = 'unknown'
      ElMessage.error(t('globalSettings.pricingAdvanced.loadFailed'))
    }
  }
}

const onPricingSwitch = async (target: boolean | string | number): Promise<void> => {
  const to = target === true
  try {
    await ElMessageBox.confirm(
      to ? t('globalSettings.pricingAdvanced.savedOn') : t('globalSettings.pricingAdvanced.savedOff'),
      t('globalSettings.pricingAdvanced.title'),
      { type: 'warning', confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel') }
    )
  } catch {
    enabled.value = !to   // 取消 → 回弹
    return
  }
  switching.value = true
  try {
    const res = await request.put('/config/pricing-advanced', { advancedEnabled: to })
    const data = (res.data as { data?: { advancedEnabled?: boolean } })?.data
    enabled.value = data?.advancedEnabled === true
    ElMessage.success(to ? t('globalSettings.pricingAdvanced.savedOn') : t('globalSettings.pricingAdvanced.savedOff'))
    // 让「模型管理」页的高级计价入口立即跟随（features 缓存刷新）
    void enterpriseStore.refresh()
  } catch {
    enabled.value = !to   // 失败 → 回弹
    ElMessage.error(t('globalSettings.pricingAdvanced.saveFailed'))
  } finally {
    switching.value = false
  }
}

// ── PII 脱敏（OSS 原生热配置） ─────────────────────────────────
const sanLoading = ref(false)
const sanAvailable = ref(true)
const requestEnabled = ref(false)
const responseEnabled = ref(false)
const ruleCount = ref(0)
const sanSwitching = ref<'request' | 'response' | null>(null)

const loadSanitization = async (): Promise<void> => {
  sanLoading.value = true
  try {
    const res = await request.get('/config/sanitization')
    const data = (res.data as {
      data?: { request?: { enabled?: boolean }; response?: { enabled?: boolean }; ruleCount?: number }
    })?.data
    requestEnabled.value = data?.request?.enabled === true
    responseEnabled.value = data?.response?.enabled === true
    ruleCount.value = typeof data?.ruleCount === 'number' ? data.ruleCount : 0
    sanAvailable.value = true
  } catch {
    sanAvailable.value = false
  } finally {
    sanLoading.value = false
  }
}

const onSanitizationSwitch = async (side: 'request' | 'response', target: boolean | string | number): Promise<void> => {
  const to = target === true
  const label = t(side === 'request'
    ? 'globalSettings.sanitization.requestLabel'
    : 'globalSettings.sanitization.responseLabel')
  try {
    await ElMessageBox.confirm(
      (to ? t('globalSettings.sanitization.savedOn') : t('globalSettings.sanitization.savedOff'))
        .replace('{name}', label),
      t('globalSettings.sanitization.title'),
      { type: 'warning', confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel') }
    )
  } catch {
    // 取消 → 回弹
    if (side === 'request') requestEnabled.value = !to
    else responseEnabled.value = !to
    return
  }
  sanSwitching.value = side
  try {
    const body = side === 'request' ? { request: { enabled: to } } : { response: { enabled: to } }
    await request.put('/config/sanitization', body)
    if (side === 'request') requestEnabled.value = to
    else responseEnabled.value = to
    ElMessage.success((to ? t('globalSettings.sanitization.savedOn') : t('globalSettings.sanitization.savedOff'))
      .replace('{name}', label))
  } catch {
    // 失败 → 回弹
    if (side === 'request') requestEnabled.value = !to
    else responseEnabled.value = !to
    ElMessage.error(t('globalSettings.sanitization.saveFailed'))
  } finally {
    sanSwitching.value = null
  }
}

onMounted(() => {
  void loadPricing()
  void loadSanitization()
})
</script>

<template>
  <div v-loading="loading">
    <!-- 高级计价（企业版；纯 OSS 灰度锁） -->
    <el-alert
      v-if="pricingNeedBilling"
      type="info"
      :closable="false"
      show-icon
      :title="t('globalSettings.pricingAdvanced.needBilling')"
      class="misc-alert"
    />
    <el-card v-else shadow="never" class="misc-card">
      <template #header>
        <span>{{ t('globalSettings.pricingAdvanced.title') }}</span>
      </template>
      <div class="pa-row">
        <div class="pa-text">
          <div class="pa-switch-label">
            {{ t('globalSettings.pricingAdvanced.switchLabel') }}
            <el-icon v-if="pricingLocked" class="pa-lock-icon"><Lock /></el-icon>
          </div>
          <div class="pa-desc">{{ t('globalSettings.pricingAdvanced.description') }}</div>
          <div class="pa-hint">
            {{ pricingLocked
              ? t('globalSettings.pricingAdvanced.lockedHint')
              : t('globalSettings.pricingAdvanced.hotHint') }}
          </div>
        </div>
        <el-switch
          v-model="enabled"
          :disabled="pricingLocked || switching"
          @change="onPricingSwitch"
        />
      </div>
    </el-card>

    <!-- PII 脱敏（OSS 原生热配置） -->
    <el-card v-if="sanAvailable" shadow="never" class="misc-card">
      <template #header>
        <span>{{ t('globalSettings.sanitization.title') }}</span>
      </template>
      <div v-loading="sanLoading">
        <div class="pa-row">
          <div class="pa-text">
            <div class="pa-switch-label">{{ t('globalSettings.sanitization.requestLabel') }}</div>
            <div class="pa-desc">{{ t('globalSettings.sanitization.requestDesc') }}</div>
          </div>
          <el-switch
            v-model="requestEnabled"
            :disabled="sanSwitching !== null"
            @change="(v: boolean | string | number) => onSanitizationSwitch('request', v)"
          />
        </div>
        <el-divider class="pa-divider" />
        <div class="pa-row">
          <div class="pa-text">
            <div class="pa-switch-label">{{ t('globalSettings.sanitization.responseLabel') }}</div>
            <div class="pa-desc">{{ t('globalSettings.sanitization.responseDesc') }}</div>
          </div>
          <el-switch
            v-model="responseEnabled"
            :disabled="sanSwitching !== null"
            @change="(v: boolean | string | number) => onSanitizationSwitch('response', v)"
          />
        </div>
        <el-divider class="pa-divider" />
        <div class="pa-meta">
          <span>{{ t('globalSettings.sanitization.ruleCount', { count: ruleCount }) }}</span>
          <span class="pa-hint">{{ t('globalSettings.sanitization.hotHint') }}</span>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.misc-alert {
  margin-bottom: 12px;
}
.misc-card {
  margin-bottom: 12px;
}
.pa-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.pa-switch-label {
  font-weight: 600;
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.pa-lock-icon {
  color: var(--el-text-color-secondary);
}
.pa-desc {
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  max-width: 640px;
}
.pa-hint {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
  max-width: 640px;
}
.pa-divider {
  margin: 14px 0;
}
.pa-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
</style>
