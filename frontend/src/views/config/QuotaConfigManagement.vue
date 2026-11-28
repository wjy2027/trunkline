<template>
  <PageSkeleton :title="t('quotaConfig.pageTitle')">
    <!-- 聚合页嵌入模式下 PageSkeleton 隐藏标题区（含 actions 插槽），按钮需自绘工具行 -->
    <div class="page-actions-row">
      <el-button @click="loadConfig" :loading="loading" size="default">
        <el-icon><Refresh /></el-icon>
        {{ t('quotaConfig.refreshStatus') }}
      </el-button>
    </div>

    <!-- 顶部状态卡片 -->
    <template #stats>
      <el-row :gutter="16">
        <el-col :span="6">
          <StatCard
            :icon="config?.enabled ? 'CircleCheck' : 'CircleClose'"
            :label="t('quotaConfig.enabledLabel')"
            :value="config?.enabled ? t('quotaConfig.enabled') : t('quotaConfig.disabled')"
            :tone="config?.enabled ? 'success' : 'info'"
          />
        </el-col>
        <el-col :span="6">
          <StatCard
            icon="Coin"
            :label="t('quotaConfig.backendLabel')"
            :value="config?.backendName ?? '—'"
            tone="primary"
          />
        </el-col>
        <el-col :span="6">
          <StatCard
            icon="Warning"
            :label="t('quotaConfig.failOpenLabel')"
            :value="config?.failOpen ? t('quotaConfig.failOpenEnabled') : t('quotaConfig.failOpenDisabled')"
            :tone="config?.failOpen ? 'warning' : 'info'"
          />
        </el-col>
        <el-col :span="6">
          <StatCard
            icon="Timer"
            :label="t('quotaConfig.flushIntervalLabel')"
            :value="config?.flushIntervalSeconds ?? '—'"
            :unit="t('quotaConfig.flushIntervalUnit')"
            tone="primary"
          />
        </el-col>
      </el-row>
    </template>

    <!-- 未就绪提示 -->
    <el-alert
      v-if="!config"
      :title="t('quotaConfig.pageTitle')"
      :description="t('quotaConfig.refreshStatus')"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    />

    <!-- 热编辑配置（2026-11-28 按产品要求移除卡片标题栏） -->
    <el-card v-if="config" shadow="hover" style="margin-bottom: 16px">
      <div class="runtime-config-grid">
        <div class="config-switch-item">
          <span class="config-switch-label">{{ t('quotaConfig.enableQuotaLabel') }}</span>
          <el-switch v-model="runtimeForm.enabled" />
        </div>
        <div class="config-switch-item">
          <span class="config-switch-label">{{ t('quotaConfig.failOpenSwitchLabel') }}</span>
          <el-switch v-model="runtimeForm.failOpen" />
        </div>
        <div class="config-switch-item">
          <span class="config-switch-label">{{ t('quotaConfig.windowsLabel') }}</span>
          <el-select
            v-model="runtimeForm.windows"
            multiple
            :placeholder="t('quotaConfig.windowsPlaceholder')"
            style="width: 220px"
          >
            <el-option
              v-for="w in windowOptions"
              :key="w.value"
              :label="t(w.labelKey)"
              :value="w.value"
            />
          </el-select>
        </div>
        <div class="config-switch-item">
          <span class="config-switch-label">{{ t('quotaConfig.userScopeLabel') }}</span>
          <el-switch v-model="runtimeForm.userScopeEnabled" />
        </div>
        <div class="config-switch-item">
          <span class="config-switch-label">{{ t('quotaConfig.departmentScopeLabel') }}</span>
          <el-switch v-model="runtimeForm.departmentScopeEnabled" />
        </div>
        <div class="config-switch-item">
          <span class="config-switch-label">{{ t('quotaConfig.tenantScopeLabel') }}</span>
          <el-switch v-model="runtimeForm.tenantScopeEnabled" />
        </div>
      </div>

      <div class="config-actions">
        <el-button
          v-if="canWrite"
          type="primary"
          :loading="saving"
          :disabled="!configChanged"
          @click="handleSaveConfig"
        >
          <el-icon><Check /></el-icon>
          {{ t('quotaConfig.saveRuntimeConfig') }}
        </el-button>
        <el-button :disabled="saving" @click="resetRuntimeForm">
          {{ t('quotaConfig.reset') }}
        </el-button>
      </div>
    </el-card>

    <!-- 组织维额度配置（用户 / 部门 / 租户） -->
    <OrgScopeQuotaPanel v-if="config?.enabled" />

    <!-- 需重启生效（只读区） -->
    <el-card v-if="config" shadow="hover">
      <template #header>
        <span class="card-title">{{ t('quotaConfig.restartRequiredTitle') }}</span>
      </template>

      <div class="readonly-grid">
        <!-- backendName -->
        <div class="readonly-item">
          <span class="readonly-label">{{ t('quotaConfig.backendNameLabel') }}</span>
          <span class="readonly-value">{{ config.backendName }}</span>
        </div>
        <!-- distributed.enabled -->
        <div class="readonly-item">
          <span class="readonly-label">
            {{ t('quotaConfig.distributedEnabledLabel') }}
            <el-tag v-if="isRestartRequired('distributed.enabled')" size="small" type="warning">
              {{ t('quotaConfig.restartRequiredBadge') }}
            </el-tag>
          </span>
          <span class="readonly-value">{{ config.distributed.enabled ? '✓' : '✗' }}</span>
        </div>
        <!-- distributed.keyPrefix -->
        <div class="readonly-item">
          <span class="readonly-label">
            {{ t('quotaConfig.distributedKeyPrefixLabel') }}
            <el-tag v-if="isRestartRequired('distributed.keyPrefix')" size="small" type="warning">
              {{ t('quotaConfig.restartRequiredBadge') }}
            </el-tag>
          </span>
          <span class="readonly-value">{{ config.distributed.keyPrefix }}</span>
        </div>
        <!-- distributed.timeoutMs -->
        <div class="readonly-item">
          <span class="readonly-label">
            {{ t('quotaConfig.distributedTimeoutLabel') }}
            <el-tag v-if="isRestartRequired('distributed.timeoutMs')" size="small" type="warning">
              {{ t('quotaConfig.restartRequiredBadge') }}
            </el-tag>
          </span>
          <span class="readonly-value">{{ config.distributed.timeoutMs }} {{ t('quotaConfig.distributedTimeoutUnit') }}</span>
        </div>
        <!-- distributed.degradeToLocal -->
        <div class="readonly-item">
          <span class="readonly-label">
            {{ t('quotaConfig.distributedDegradeLabel') }}
            <el-tag v-if="isRestartRequired('distributed.degradeToLocal')" size="small" type="warning">
              {{ t('quotaConfig.restartRequiredBadge') }}
            </el-tag>
          </span>
          <span class="readonly-value">{{ config.distributed.degradeToLocal ? '✓' : '✗' }}</span>
        </div>
        <!-- retention.* -->
        <div class="readonly-item">
          <span class="readonly-label">
            {{ t('quotaConfig.retentionMinuteLabel') }}
            <el-tag v-if="isRestartRequired('retention.minute')" size="small" type="warning">
              {{ t('quotaConfig.restartRequiredBadge') }}
            </el-tag>
          </span>
          <span class="readonly-value">{{ config.retention.minute }}</span>
        </div>
        <div class="readonly-item">
          <span class="readonly-label">
            {{ t('quotaConfig.retentionHourLabel') }}
            <el-tag v-if="isRestartRequired('retention.hour')" size="small" type="warning">
              {{ t('quotaConfig.restartRequiredBadge') }}
            </el-tag>
          </span>
          <span class="readonly-value">{{ config.retention.hour }}</span>
        </div>
        <div class="readonly-item">
          <span class="readonly-label">
            {{ t('quotaConfig.retentionDayLabel') }}
            <el-tag v-if="isRestartRequired('retention.day')" size="small" type="warning">
              {{ t('quotaConfig.restartRequiredBadge') }}
            </el-tag>
          </span>
          <span class="readonly-value">{{ config.retention.day }}</span>
        </div>
        <div class="readonly-item">
          <span class="readonly-label">
            {{ t('quotaConfig.retentionMonthLabel') }}
            <el-tag v-if="isRestartRequired('retention.month')" size="small" type="warning">
              {{ t('quotaConfig.restartRequiredBadge') }}
            </el-tag>
          </span>
          <span class="readonly-value">{{ config.retention.month }}</span>
        </div>
        <!-- flushIntervalSeconds -->
        <div class="readonly-item">
          <span class="readonly-label">
            {{ t('quotaConfig.flushIntervalSecondsLabel') }}
            <el-tag v-if="isRestartRequired('flushIntervalSeconds')" size="small" type="warning">
              {{ t('quotaConfig.restartRequiredBadge') }}
            </el-tag>
          </span>
          <span class="readonly-value">{{ config.flushIntervalSeconds }} {{ t('quotaConfig.flushIntervalUnit') }}</span>
        </div>
      </div>
    </el-card>
  </PageSkeleton>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { usePermission } from '@/composables/usePermission'
import { ElMessage } from 'element-plus'
import { Refresh, CircleCheck as _CircleCheck, CircleClose as _CircleClose, Coin as _Coin, Warning as _Warning, Timer as _Timer, Check } from '@element-plus/icons-vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import StatCard from '@/components/StatCard.vue'
import OrgScopeQuotaPanel from '@/views/config/OrgScopeQuotaPanel.vue'
import { effectMessage } from '@/utils/effectScope'
import {
  getQuotaConfig,
  updateQuotaConfig,
  type QuotaConfig,
  type QuotaConfigPayload,
} from '@/api/quota'

const { t } = useI18n()
const { hasPermission } = usePermission()
const canWrite = hasPermission('config:quota:write')

const loading = ref(false)
const saving = ref(false)
const config = ref<QuotaConfig | null>(null)

// 统计窗口选项：value 为后端窗口名，labelKey 多语言显示（2026-11-28）
const windowOptions = [
  { value: 'MINUTE', labelKey: 'quotaConfig.window.minute' },
  { value: 'HOUR', labelKey: 'quotaConfig.window.hour' },
  { value: 'DAY', labelKey: 'quotaConfig.window.day' },
  { value: 'MONTH', labelKey: 'quotaConfig.window.month' },
]

// ── 运行时表单 ──

interface RuntimeForm {
  enabled: boolean
  failOpen: boolean
  windows: string[]
  userScopeEnabled: boolean
  departmentScopeEnabled: boolean
  tenantScopeEnabled: boolean
}

const runtimeForm = ref<RuntimeForm>({
  enabled: false,
  failOpen: false,
  windows: [],
  userScopeEnabled: false,
  departmentScopeEnabled: false,
  tenantScopeEnabled: false,
})

const syncFormFromConfig = (c: QuotaConfig) => {
  runtimeForm.value.enabled = c.enabled
  runtimeForm.value.failOpen = c.failOpen
  runtimeForm.value.windows = [...c.windows]
  runtimeForm.value.userScopeEnabled = c.userScopeEnabled ?? false
  runtimeForm.value.departmentScopeEnabled = c.departmentScopeEnabled ?? false
  runtimeForm.value.tenantScopeEnabled = c.tenantScopeEnabled ?? false
}

const resetRuntimeForm = () => {
  if (config.value) syncFormFromConfig(config.value)
}

/** 判断字段是否在重启生效列表中 */
const isRestartRequired = (fieldPath: string): boolean => {
  if (!config.value) return false
  return config.value.restartRequiredFields.some(pattern => {
    if (pattern.endsWith('.*')) {
      const prefix = pattern.slice(0, -2)
      return fieldPath === prefix || fieldPath.startsWith(`${prefix  }.`)
    }
    return pattern === fieldPath
  })
}

/** 判断是否有变更 */
const configChanged = computed(() => {
  if (!config.value) return false
  const c = config.value
  if (runtimeForm.value.enabled !== c.enabled) return true
  if (runtimeForm.value.failOpen !== c.failOpen) return true
  if (
    runtimeForm.value.windows.length !== c.windows.length ||
    runtimeForm.value.windows.some(w => !c.windows.includes(w))
  ) {
    return true
  }
  if (runtimeForm.value.userScopeEnabled !== (c.userScopeEnabled ?? false)) return true
  if (runtimeForm.value.departmentScopeEnabled !== (c.departmentScopeEnabled ?? false)) return true
  if (runtimeForm.value.tenantScopeEnabled !== (c.tenantScopeEnabled ?? false)) return true
  return false
})

// ── 数据加载 ──

const loadConfig = async () => {
  loading.value = true
  try {
    const c = await getQuotaConfig()
    config.value = c
    if (c) syncFormFromConfig(c)
  } catch {
    config.value = null
  } finally {
    loading.value = false
  }
}

// ── 保存 ──

const handleSaveConfig = async () => {
  if (!config.value) return
  const c = config.value
  const payload: QuotaConfigPayload = {}

  if (runtimeForm.value.enabled !== c.enabled) {
    payload.enabled = runtimeForm.value.enabled
  }
  if (runtimeForm.value.failOpen !== c.failOpen) {
    payload.failOpen = runtimeForm.value.failOpen
  }
  if (
    runtimeForm.value.windows.length !== c.windows.length ||
    runtimeForm.value.windows.some(w => !c.windows.includes(w))
  ) {
    payload.windows = [...runtimeForm.value.windows]
  }
  if (runtimeForm.value.userScopeEnabled !== (c.userScopeEnabled ?? false)) {
    payload.userScopeEnabled = runtimeForm.value.userScopeEnabled
  }
  if (runtimeForm.value.departmentScopeEnabled !== (c.departmentScopeEnabled ?? false)) {
    payload.departmentScopeEnabled = runtimeForm.value.departmentScopeEnabled
  }
  if (runtimeForm.value.tenantScopeEnabled !== (c.tenantScopeEnabled ?? false)) {
    payload.tenantScopeEnabled = runtimeForm.value.tenantScopeEnabled
  }

  if (Object.keys(payload).length === 0) {
    ElMessage.warning(t('quotaConfig.noChangesWarning'))
    return
  }

  saving.value = true
  try {
    await updateQuotaConfig(payload)
    ElMessage.success(effectMessage(t('quotaConfig.configUpdatedSuccess'), 'config'))
    await loadConfig()
  } catch (err: unknown) {
    // 提取后端 400 原文消息
    const axiosErr = err as { response?: { data?: { message?: string } } }
    const msg = axiosErr.response?.data?.message
    if (msg) {
      ElMessage.error(msg)
    } else {
      ElMessage.error(t('quotaConfig.saveFailedError'))
    }
  } finally {
    saving.value = false
  }
}

onMounted(loadConfig)
</script>

<style scoped>
.page-actions-row {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.card-title {
  font-weight: 600;
  font-size: 15px;
}

.runtime-config-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px 32px;
  margin-bottom: 16px;
}

.config-switch-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: var(--ja-radius-md, 8px);
  background: var(--ja-bg-hover, var(--el-fill-color-light));
}

.config-switch-label {
  font-size: 14px;
  color: var(--ja-text-primary);
  font-weight: 500;
}

.config-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.readonly-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px 32px;
}

.readonly-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: var(--ja-radius-md, 8px);
  background: var(--ja-bg-hover, var(--el-fill-color-light));
}

.readonly-label {
  font-size: 14px;
  color: var(--ja-text-primary);
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 6px;
}

.readonly-value {
  font-size: 14px;
  color: var(--el-text-color-regular);
  font-family: var(--ja-font-mono);
}
</style>
