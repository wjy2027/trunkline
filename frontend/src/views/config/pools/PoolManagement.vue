<template>
  <PageSkeleton>
    <template #title>
      <div class="page-heading">
        <h2>{{ t('pool.title') }}</h2>
        <p class="page-heading__sub">{{ t(menuSubtitle('/model-pools')) }}</p>
      </div>
    </template>
    <template #actions>
      <el-button :icon="Refresh" circle @click="fetchPools" :title="t('pool.refreshList')" />
      <el-button v-if="canWrite" @click="handleCreate">
        <el-icon><Plus /></el-icon>&nbsp;{{ t('pool.addPool') }}
      </el-button>
      <el-button v-if="canWrite" type="primary" @click="openScenario">
        <el-icon><MagicStick /></el-icon>&nbsp;{{ t('pool.createFromScenario') }}
      </el-button>
    </template>

    <div class="vm-explain">
      <b class="vm-explain__title">{{ t('pool.explainTitle') }}</b>
      <div class="vm-explain__body">{{ t('pool.explainBodyV2') }}</div>
    </div>

    <el-table ref="poolsTableRef" :data="pools" :max-height="poolsMaxHeight" v-loading="loading" style="width: 100%" row-key="poolName">
        <el-table-column min-width="200">
          <template #header>{{ t('pool.externalName') }} <TermHelp term="virtualModel" /></template>
          <template #default="{ row }">
            <div class="vm-name">
              <span class="vm-name__title">{{ row.name || row.poolName }}</span>
            </div>
            <div class="vm-name__sub" v-if="row.name && row.name !== row.poolName">model-name: {{ row.poolName }}</div>
            <div class="vm-name__desc" v-if="row.description">{{ row.description }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('pool.backingModels')" min-width="420">
          <template #default="{ row }">
            <div class="vm-members">
              <span
                v-for="(m, idx) in (row.members || [])"
                :key="idx"
                class="model-chip"
                :class="{ 'model-chip--primary': idx === 0 }"
              >
                <i v-if="idx === 0" class="model-chip__badge model-chip__badge--primary">{{ t('pool.primaryBadge') }}</i>
                <i v-else class="model-chip__badge">{{ t('pool.backupBadge', { n: idx }) }}</i>
                <span class="model-chip__name">{{ m.instanceId }}</span>
                <i class="model-chip__price" :class="'model-chip__price--' + memberPriceTier(m.instanceId)">
                  {{ memberPriceLabel(m.instanceId) }}
                </i>
              </span>
              <span v-if="!row.members || row.members.length === 0" class="cell-sub">{{ t('pool.memberEmptyHint') }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column min-width="170">
          <template #header>{{ t('pool.strategy') }} <TermHelp term="loadBalancer" /></template>
          <template #default="{ row }">
            <el-tooltip :content="t('pool.strategyHints.' + (row.strategy || 'weighted-random'))" placement="top">
              <span class="strategy-name">
                <el-tag size="small" type="primary" effect="plain">{{ t('pool.strategies.' + (row.strategy || 'weighted-random')) }}</el-tag>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column :label="t('pool.columnPriceStatus')" width="150" align="center">
          <template #default="{ row }">
            <el-tooltip
              placement="top"
              :content="priceStatusTooltip(row)"
            >
              <div class="price-status">
                <el-tag size="small" :type="priceStatusType(row)" effect="plain">
                  {{ priceStatusLabel(row) }}
                </el-tag>
                <div v-if="priceStatusSub(row)" class="price-status__sub">{{ priceStatusSub(row) }}</div>
              </div>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column width="170">
          <template #header>{{ t('pool.columnQuota') }} <TermHelp term="monthlyQuota" /></template>
          <template #default="{ row }">
            <div class="quota-summary">
              <span v-for="(q, qi) in quotaSummary(row)" :key="qi" class="quota-chip" :class="q.cls">
                {{ q.text }}
              </span>
              <span v-if="quotaSummary(row).length === 0" class="cell-sub">{{ t('pool.quotaNoneHint') }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('pool.columnStatus')" width="100" align="center" fixed="right">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled ? 'success' : 'info'" effect="plain">
              {{ row.enabled ? t('pool.statusEnabled') : t('pool.statusDisabled') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('pool.monthlyCalls')" width="110" align="center" fixed="right">
          <template #default="{ row }">
            <span v-if="poolMonthlyCalls(row.poolName) !== null" class="vm-calls">
              {{ formatNumber(poolMonthlyCalls(row.poolName)) }}
            </span>
            <span v-else class="cell-sub">{{ t('pool.monthlyCallsNone') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('pool.actions')" width="220" align="center" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canWrite" size="small" @click="openQuota(row)">{{ t('pool.quotaButton') }}</el-button>
            <el-button v-if="canWrite" size="small" @click="handleEdit(row)">{{ t('pool.edit') }}</el-button>
            <el-button v-if="canWrite" size="small" type="danger" @click="handleDelete(row)">{{ t('pool.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!loading && pools.length === 0" class="list-empty">
        <el-empty :description="t('pool.emptyText')">
          <el-button v-if="canWrite" @click="handleCreate">{{ t('pool.addPool') }}</el-button>
          <el-button v-if="canWrite" type="primary" @click="openScenario">{{ t('pool.createFromScenario') }}</el-button>
        </el-empty>
      </div>
  </PageSkeleton>

  <!-- 按场景创建：内嵌场景向导，应用成功后 emit applied 刷新上方列表 -->
  <el-dialog
    v-model="scenarioVisible"
    :title="t('pool.scenarioDialogTitle')"
    width="min(1080px, 94vw)"
    top="5vh"
    destroy-on-close
  >
    <ScenarioWizard @applied="onScenarioApplied" />
  </el-dialog>

  <!-- 新建 / 编辑 对话框（原型风格：名称 + 模型清单 + 故障转移策略） -->
  <el-dialog
    v-model="dialogVisible"
    :title="dialogTitle"
    width="min(640px, 92vw)"
    class="pool-edit-dialog"
    top="6vh"
    :body-style="dialogBodyStyle"
    destroy-on-close
  >
    <div class="vm-dialog-desc">{{ dialogDesc }}</div>
    <el-form :model="form" label-width="0" class="vm-form">
      <!-- 名称：员工在 AI 工具里填的模型名 = 后端 poolName（引用名） -->
      <div class="vm-field">
        <label class="vm-label">{{ isEdit ? t('pool.nameFieldLabelEdit') : t('pool.nameFieldLabelCreate') }}</label>
        <el-input v-model="form.poolName" :placeholder="t('pool.nameFieldPlaceholder')" />
        <!-- 固定解释句：创建与编辑都给（解决「参照系不明 / 该填什么 / 为何看不到厂商模型」） -->
        <div class="form-tip">{{ t('pool.nameFieldHelp') }}</div>
      </div>

      <!-- 中文描述：给虚拟模型写一句人能看懂的说明，测试场模型下拉会在英文名旁展示它 -->
      <div class="vm-field">
        <label class="vm-label">{{ t('pool.description') }}</label>
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="2"
          maxlength="200"
          show-word-limit
          resize="none"
          :placeholder="t('pool.descriptionPlaceholder')"
        />
        <div class="form-tip">{{ t('pool.descriptionHelp') }}</div>
      </div>

      <!-- 创建：模型勾选清单（带单次价格标签）；顺序即勾选顺序 -->
      <div class="vm-field" v-if="!isEdit">
        <label class="vm-label">{{ t('pool.modelPickLabel', { n: allInstanceCount }) }}</label>
        <div class="model-check-list" v-loading="instancesLoading">
          <label
            v-for="opt in createCandidates"
            :key="opt.value"
            class="model-check-row"
            :class="{ 'is-locked': isOptionLocked(opt.value) }"
          >
            <el-checkbox
              :model-value="selectedNames.includes(opt.value)"
              :disabled="isOptionLocked(opt.value)"
              @change="(v: unknown) => toggleName(opt.value, !!v)"
            />
            <span class="model-check-name">{{ opt.label }}</span>
            <span class="price-tag" :class="'price-tag--' + priceInfo(opt.value).tier">{{ priceInfo(opt.value).label }}</span>
          </label>
          <div v-if="!instancesLoading && createCandidates.length === 0" class="member-empty">{{ t('pool.noInstancesTip') }}</div>
        </div>
        <!-- 同分类约束：虚拟模型按「分类+池名」路由，混入其他分类的成员永远不会被路由到 -->
        <div v-if="lockedServiceType" class="form-tip">{{ t('pool.categoryLockedHint', { type: serviceTypeLabel(lockedServiceType) }) }}</div>
      </div>

      <!-- 编辑：真实模型优先级有序列表（主 / 备 N）+ 故障转移策略 -->
      <div class="vm-field" v-else>
        <label class="vm-label">{{ t('pool.modelOrderLabel') }}</label>
        <div class="model-order-list">
          <div v-for="(m, idx) in form.members" :key="idx" class="model-order-row">
            <div class="model-order-main">
              <span class="model-order-badge" :class="{ 'model-order-badge--primary': idx === 0 }">
                {{ idx === 0 ? t('pool.primaryBadge') : t('pool.backupBadge', { n: idx }) }}
              </span>
              <span class="model-order-name">{{ m.instanceId }}</span>
              <span class="member-weight" v-if="showMemberWeight">
                <span class="member-weight__label">{{ t('pool.memberWeightLabel') }}</span>
                <el-input-number
                  :model-value="m.weight ?? 1"
                  :min="1"
                  :max="100"
                  :step="1"
                  size="small"
                  controls-position="right"
                  style="width: 96px"
                  @update:model-value="(v: number | undefined) => setMemberWeight(idx, v)"
                />
              </span>
              <span class="model-order-ops">
                <el-button text size="small" :disabled="idx === 0" @click="moveUp(idx)">↑</el-button>
                <el-button text size="small" :disabled="idx === form.members.length - 1" @click="moveDown(idx)">↓</el-button>
                <el-button text size="small" type="danger" @click="removeMember(idx)">✕</el-button>
              </span>
            </div>
            <div class="model-order-desc">{{ idx === 0 ? t('pool.memberPrimaryDesc') : t('pool.memberBackupDesc') }}</div>
          </div>
          <div class="model-order-add">
            <el-select v-model="addPick" :placeholder="t('pool.addModel')" size="small" filterable clearable style="width: 240px">
              <el-option v-for="o in addCandidates" :key="o.value" :value="o.value" :label="o.label" />
            </el-select>
            <el-button size="small" :disabled="!addPick" @click="addToMembers">{{ t('pool.addModel') }}</el-button>
          </div>
        </div>
      </div>

      <!-- 模型分配策略（编辑时配置；创建默认按权重随机） -->
      <div class="vm-field" v-if="isEdit">
        <label class="vm-label">{{ t('pool.failoverLabel') }}</label>
        <div class="form-tip">{{ t('pool.failoverDesc') }}</div>
        <el-select v-model="form.strategy" style="width: 100%">
          <el-option v-for="s in STRATEGY_OPTIONS" :key="s.value" :value="s.value" :label="t(s.label)">
            <div class="strategy-option">
              <span class="strategy-option-name">{{ t(s.label) }}</span>
              <span class="strategy-option-desc">{{ t(s.hint) }}</span>
            </div>
          </el-option>
        </el-select>
        <div class="form-tip">{{ t('pool.strategyHints.' + (form.strategy || 'weighted-random')) }}</div>
        <div class="form-tip" v-if="showMemberWeight">{{ t('pool.memberWeightDesc') }} · {{ t('pool.memberWeightTip') }}</div>
      </div>

      <!-- 启用开关（编辑时允许停用） -->
      <div class="vm-field" v-if="isEdit">
        <el-switch v-model="form.enabled" :active-text="t('pool.enabled')" :inactive-text="t('pool.disabled')" />
      </div>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">{{ t('pool.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">{{ t('pool.save') }}</el-button>
    </template>
  </el-dialog>

  <!-- 配额对话框（列表操作列单独设置，不进新建/编辑表单） -->
  <el-dialog
    v-model="quotaVisible"
    :title="t('pool.quotaDialogTitle', { name: quotaTargetName })"
    width="min(640px, 92vw)"
    class="pool-quota-dialog"
    top="6vh"
    :body-style="dialogBodyStyle"
    destroy-on-close
  >
      <div class="quota-block">
        <div class="form-tip">{{ t('pool.quotaSectionHint') }}</div>
        <div class="quota-grid">
          <!-- 2026-11-28：字段随全局统计窗口联动（窗口关闭则不显示） -->
          <div v-if="isQuotaFieldVisible('minuteRequests')" class="quota-field">
            <span class="quota-label">{{ t('pool.quotaRatePerMinute') }}</span>
            <el-input-number v-model="quotaForm.rateLimitPerMinute" :min="0" :step="10" size="small" />
          </div>
          <div v-if="isQuotaFieldVisible('hourRequests')" class="quota-field">
            <span class="quota-label">{{ t('pool.quotaHourlyRequest') }}</span>
            <el-input-number v-model="quotaForm.hourlyRequestLimit" :min="0" :step="100" size="small" :disabled="!multiWindowEnforced" />
          </div>
          <div v-if="isQuotaFieldVisible('hourTokens')" class="quota-field">
            <span class="quota-label">{{ t('pool.quotaHourlyToken') }}</span>
            <el-input-number v-model="quotaForm.hourlyTokenLimit" :min="0" :step="1000" size="small" :disabled="!multiWindowEnforced" />
          </div>
          <div v-if="isQuotaFieldVisible('dayRequests')" class="quota-field">
            <span class="quota-label">{{ t('pool.quotaDailyRequest') }}</span>
            <el-input-number v-model="quotaForm.dailyRequestLimit" :min="0" :step="100" size="small" />
          </div>
          <div v-if="isQuotaFieldVisible('dayTokens')" class="quota-field">
            <span class="quota-label">{{ t('pool.quotaDailyToken') }}</span>
            <el-input-number v-model="quotaForm.dailyTokenLimit" :min="0" :step="1000" size="small" />
          </div>
          <div v-if="isQuotaFieldVisible('monthRequests')" class="quota-field">
            <span class="quota-label">{{ t('pool.quotaMonthlyRequest') }}</span>
            <el-input-number v-model="quotaForm.monthlyRequestLimit" :min="0" :step="1000" size="small" :disabled="!multiWindowEnforced" />
          </div>
          <div v-if="isQuotaFieldVisible('monthTokens')" class="quota-field">
            <span class="quota-label">{{ t('pool.quotaMonthlyToken') }}</span>
            <el-input-number v-model="quotaForm.monthlyTokenLimit" :min="0" :step="10000" size="small" :disabled="!multiWindowEnforced" />
          </div>
        <div v-if="!multiWindowEnforced" class="quota-field quota-field--wide">
          <span class="quota-hint quota-hint--warn">{{ t('apiKeys.quotaForm.inertFieldHint') }}</span>
        </div>
        <div class="quota-field quota-field--wide">
          <span class="quota-label">{{ t('pool.quotaAlertThreshold') }}</span>
          <el-slider
            v-model="quotaForm.quotaAlertThreshold"
            :min="0.1"
            :max="1"
            :step="0.05"
            :format-tooltip="(v: number) => `${Math.round(v * 100)}%`"
            style="width: 220px"
          />
          <span class="quota-hint">{{ t('pool.quotaAlertHint') }}</span>
        </div>
      </div>
      <div class="form-tip">{{ t('pool.quotaUnlimitedHint') }}</div>
    </div>
    <template #footer>
      <el-button @click="quotaVisible = false">{{ t('pool.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="saveQuota">{{ t('pool.quotaSave') }}</el-button>
    </template>
  </el-dialog>

</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { MagicStick, Plus, Refresh } from '@element-plus/icons-vue'
import { ALL_SERVICE_TYPES as SERVICE_TYPES, getServiceTypeLabel } from '@/constants/serviceTypes'
import {
  getPoolList,
  getPool,
  createPool,
  updatePool,
  deletePool,
  getPoolQuotaUsage,
  type PoolDefinition,
  type PoolMember as _PoolMember
} from '@/api/pools'
import { getServiceInstances, type InstanceConfig } from '@/api/instance'
import { getPriceCatalog, type ModelPriceCatalogItem } from '@/api/providerPrice'
import { confirmDanger } from '@/utils/dangerConfirm'
import { useDependencyGuard } from '@/composables/useDependencyGuard'
import PageSkeleton from '@/components/PageSkeleton.vue'
import TermHelp from '@/components/common/TermHelp.vue'
import { usePermission } from '@/composables/usePermission'
import { useQuotaLedger } from '@/composables/useQuotaLedger'
import { useQuotaWindows } from '@/composables/useQuotaWindows'
import { useTableMaxHeight } from '@/composables/useTableMaxHeight'
import { useEnterpriseStore } from '@/stores/enterprise'
import { useTenantStore } from '@/stores/tenant'
import { menuSubtitle } from '@/config/menu'
import ScenarioWizard from '@/views/tools/ScenarioWizard.vue'

const { t } = useI18n()
const route = useRoute()
const enterpriseStore = useEnterpriseStore()
const tenantStore = useTenantStore()
// 配额账本开关（单例探针）：关闭时额度配置弹窗里的每小时 / 每月字段禁用
const { multiWindowEnforced, loadQuotaLedgerState } = useQuotaLedger()
// 统计窗口联动（2026-11-28）：全局 windows 关闭的窗口，分组配额字段不显示
const { isFieldVisible: isQuotaFieldVisible } = useQuotaWindows()
const { hasPermission } = usePermission()
const canWrite = hasPermission('config:pools:write')

/** 服务类型本地化显示名 */
const serviceTypeLabel = (type: string): string => getServiceTypeLabel(type)

const dialogBodyStyle = {
  maxHeight: 'calc(90vh - 120px)',
  overflowY: 'auto',
  paddingRight: '8px'
} as const

const pools = ref<PoolDefinition[]>([])
const loading = ref(false)

const poolsTableRef = ref()
const { maxHeight: poolsMaxHeight } = useTableMaxHeight(poolsTableRef)
const saving = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)

// 「按场景创建」弹窗：内嵌场景向导，应用成功后刷新池列表
const scenarioVisible = ref(false)
const openScenario = () => { scenarioVisible.value = true }
const onScenarioApplied = () => { void fetchPools() }

/** 各虚拟模型的「本月调用」次数（按池名索引；null = 暂无用量 / 取数失败） */
const poolUsageMap = ref<Record<string, number | null>>({})
const poolMonthlyCalls = (poolName: string): number | null => poolUsageMap.value[poolName] ?? null
const formatNumber = (n: number | null): string => (n === null ? '' : n.toLocaleString('zh-CN'))

const instances = ref<InstanceConfig[]>([])
const instancesLoading = ref(false)
/** 实例名 → 分类（用于创建时推导分组所属分类 + 价格分层） */
const instanceServiceType = ref<Record<string, string>>({})
/** 实例名 → 价格信息（来自价格与额度目录） */
const priceMeta = ref<Record<string, { hasPrice: boolean; priceInput: number; priceOutput: number }>>({})

const form = reactive<PoolDefinition>({
  poolName: '',
  name: '',
  serviceType: 'chat',
  enabled: true,
  strategy: 'weighted-random',
  description: '',
  members: [],
  rateLimitPerMinute: 0,
  hourlyRequestLimit: 0,
  hourlyTokenLimit: 0,
  dailyRequestLimit: 0,
  dailyTokenLimit: 0,
  monthlyRequestLimit: 0,
  monthlyTokenLimit: 0,
  quotaAlertThreshold: 0.8
})

/** 创建态：勾选的模型名集合（顺序即优先级） */
const selectedNames = ref<string[]>([])
/** 编辑态：补加模型时的临时选择 */
const addPick = ref<string>('')

const allInstanceCount = computed(() => instances.value.filter(i => Boolean(i.name)).length)
const createCandidates = computed(() =>
  instances.value.filter(i => Boolean(i.name)).map(i => ({ value: i.name as string, label: i.name as string }))
)
const addCandidates = computed(() => {
  const used = new Set(form.members.map(m => m.instanceId))
  return instances.value
    .filter(i => i.name && !used.has(i.name))
    // 编辑态池分类固定：分类不匹配的实例不进候选（混入也不会被路由到）
    .filter(i => !form.serviceType || !instanceServiceType.value[i.name!] || instanceServiceType.value[i.name!] === form.serviceType)
    .map(i => ({ value: i.name as string, label: i.name as string }))
})

/** 创建态：已勾选模型锁定的分类（第一个勾选决定），其他分类的候选置灰禁选 */
const lockedServiceType = computed<string>(() => {
  for (const n of selectedNames.value) {
    const t = instanceServiceType.value[n]
    if (t) return t
  }
  return ''
})
const isOptionLocked = (name: string): boolean => {
  if (selectedNames.value.includes(name)) return false
  const t = instanceServiceType.value[name]
  return !!lockedServiceType.value && !!t && t !== lockedServiceType.value
}

/** 单次价格信息：分层（便宜 / 中等 / 贵 / 向量模型 / 无价格）+ 约单价（单价目录已统一为 ¥/百万 tokens） */
const priceInfo = (name: string): { tier: string; label: string } => {
  const st = instanceServiceType.value[name]
  const pm = priceMeta.value[name]
  const hasPrice = !!pm && pm.hasPrice && (pm.priceInput > 0 || pm.priceOutput > 0)
  if (st === 'embedding') return { tier: 'vector', label: t('pool.priceVector') }
  if (!hasPrice) return { tier: 'none', label: t('pool.priceNone') }
  const cost = (pm!.priceInput || 0) + (pm!.priceOutput || 0)
  // 阈值按 ¥/M 口径：原 ¥/K 的 0.5 / 0.1 分别对应 500 / 100
  const tier = cost > 500 ? 'expensive' : cost > 100 ? 'medium' : 'cheap'
  const tierLabel = tier === 'cheap' ? t('pool.priceCheap') : tier === 'medium' ? t('pool.priceMedium') : t('pool.priceExpensive')
  return { tier, label: `${tierLabel} · ${t('pool.pricePerCall', { price: (pm!.priceInput || 0).toFixed(2) })}` }
}

/* ==================== P0-F：列表三列的计算逻辑 ==================== */

/** 池内成员里「已配价」的个数 */
const pricedMemberCount = (row: PoolDefinition): number => {
  const list = row.members || []
  return list.filter(m => {
    const pm = priceMeta.value[m.instanceId]
    return !!pm && pm.hasPrice && (pm.priceInput > 0 || pm.priceOutput > 0)
  }).length
}

/** 价格状态标签文案 */
const priceStatusLabel = (row: PoolDefinition): string => {
  const total = (row.members || []).length
  if (total === 0) return t('pool.priceStatusNoMembers')
  const priced = pricedMemberCount(row)
  if (priced === 0) return t('pool.priceStatusNone')
  if (priced === total) return t('pool.priceStatusAllPriced')
  return t('pool.priceStatusPartiallyPriced', { priced, total })
}

/** 价格状态标签的补充说明（部分配价时显示均价） */
const priceStatusSub = (row: PoolDefinition): string => {
  const total = (row.members || []).length
  if (total === 0) return ''
  const priced = pricedMemberCount(row)
  if (priced === 0 || priced === total) return ''
  const costs = (row.members || [])
    .map(m => priceMeta.value[m.instanceId])
    .filter(pm => pm && pm.hasPrice)
    .map(pm => (pm.priceInput || 0) + (pm.priceOutput || 0))
  if (!costs.length) return ''
  const avg = costs.reduce((a, b) => a + b, 0) / costs.length
  return t('pool.memberPriceTag', { price: avg.toFixed(3) })
}

/** 价格状态标签颜色：全未配 → danger；部分配 → warning；全配 → success */
const priceStatusType = (row: PoolDefinition): 'danger' | 'warning' | 'success' | 'info' => {
  const total = (row.members || []).length
  if (total === 0) return 'info'
  const priced = pricedMemberCount(row)
  if (priced === 0) return 'danger'
  if (priced === total) return 'success'
  return 'warning'
}

/** 价格状态 tooltip */
const priceStatusTooltip = (row: PoolDefinition): string => {
  const total = (row.members || []).length
  if (total === 0) return ''
  const priced = pricedMemberCount(row)
  if (priced === 0) return t('pool.priceStatusTooltipNone')
  return t('pool.priceStatusTooltipPriced', { count: priced, total })
}

/** 成员 chip 上的价格标签（与创建清单同一套分层逻辑） */
const memberPriceTier = (name: string): string => priceInfo(name).tier
const memberPriceLabel = (name: string): string => {
  const pm = priceMeta.value[name]
  const hasPrice = !!pm && pm.hasPrice && (pm.priceInput > 0 || pm.priceOutput > 0)
  if (!hasPrice) return t('pool.memberNoPriceTag')
  return t('pool.memberPriceTag', { price: (pm!.priceInput || 0).toFixed(3) })
}

/** 池级额度摘要（只列真正设了上限的维度；全 0 → 空数组 → 显示「没设限制」） */
const quotaSummary = (row: PoolDefinition): { text: string; cls: string }[] => {
  const out: { text: string; cls: string }[] = []
  const push = (text: string, cls: string) => out.push({ text, cls })
  if (row.rateLimitPerMinute && row.rateLimitPerMinute > 0) {
    push(t('pool.quotaPerMinute', { n: row.rateLimitPerMinute }), 'quota-chip--rate')
  }
  if (row.hourlyRequestLimit && row.hourlyRequestLimit > 0) {
    push(t('pool.quotaPerHour', { n: row.hourlyRequestLimit }), '')
  }
  if (row.dailyRequestLimit && row.dailyRequestLimit > 0) {
    push(t('pool.quotaPerDay', { n: row.dailyRequestLimit }), '')
  }
  if (row.monthlyRequestLimit && row.monthlyRequestLimit > 0) {
    push(t('pool.quotaPerMonth', { n: row.monthlyRequestLimit }), '')
  }
  if (row.monthlyTokenLimit && row.monthlyTokenLimit > 0) {
    push(t('pool.quotaTokens', { n: row.monthlyTokenLimit }), 'quota-chip--token')
  }
  return out
}

/* ==================== P0-F：成员流量权重 ==================== */

/** 只有「按权重随机」才用得上权重输入框，其余策略隐藏以免误导 */
const showMemberWeight = computed(() => (form.strategy || 'weighted-random') === 'weighted-random')

/** 设置某成员的流量权重（后端 PoolMember.weight，缺省 1） */
const setMemberWeight = (idx: number, v: number | undefined) => {
  if (!form.members[idx]) return
  form.members[idx].weight = v && v > 0 ? v : 1
}

const dialogTitle = computed(() => isEdit.value ? t('pool.editDialogTitle', { name: form.poolName }) : t('pool.createDialogTitle'))
const dialogDesc = computed(() => isEdit.value ? t('pool.editDesc', { name: form.poolName }) : t('pool.createDesc'))

/** 负载均衡策略选项（白话名 + 一句话说明），作为「故障转移策略」下拉呈现 */
const STRATEGY_OPTIONS = [
  { value: 'weighted-random', label: 'pool.strategies.weighted-random', hint: 'pool.strategyHints.weighted-random' },
  { value: 'round-robin', label: 'pool.strategies.round-robin', hint: 'pool.strategyHints.round-robin' },
  { value: 'least-connections', label: 'pool.strategies.least-connections', hint: 'pool.strategyHints.least-connections' },
  { value: 'ip-hash', label: 'pool.strategies.ip-hash', hint: 'pool.strategyHints.ip-hash' },
  { value: 'consistent-hash', label: 'pool.strategies.consistent-hash', hint: 'pool.strategyHints.consistent-hash' }
] as const

const quotaVisible = ref(false)
const quotaTargetName = ref('')
const quotaForm = reactive({
  poolName: '',
  rateLimitPerMinute: 0,
  hourlyRequestLimit: 0,
  hourlyTokenLimit: 0,
  dailyRequestLimit: 0,
  dailyTokenLimit: 0,
  monthlyRequestLimit: 0,
  monthlyTokenLimit: 0,
  quotaAlertThreshold: 0.8
})

const fetchPools = async () => {
  loading.value = true
  try {
    const res = await getPoolList()
    pools.value = res.data?.data || []
    await loadMonthlyCalls(pools.value)
  } catch (_e) {
    ElMessage.error(t('pool.messages.fetchListFailed'))
  } finally {
    loading.value = false
  }
}

/** 逐池查询本月（MONTHLY 窗口）请求次数，写入 poolUsageMap。 */
const loadMonthlyCalls = async (list: PoolDefinition[]) => {
  const next: Record<string, number | null> = {}
  await Promise.all(list.map(async (p) => {
    try {
      const res = await getPoolQuotaUsage(p.poolName)
      const usages = res.data?.data?.usage || []
      const monthly = usages.find(u => u.window === 'MONTHLY')
      next[p.poolName] = monthly ? (monthly.requestCount || 0) : 0
    } catch {
      next[p.poolName] = null
    }
  }))
  poolUsageMap.value = next
}

/** 拉取全部可用实例（跨分类合并，供创建勾选清单 / 编辑补加用） */
const loadAllInstances = async () => {
  instancesLoading.value = true
  try {
    const results = await Promise.all(
      SERVICE_TYPES.map(st => getServiceInstances(st).then(r => r.data?.data || []).catch(() => []))
    )
    const merged = results.flat() as InstanceConfig[]
    const seen = new Set<string>()
    const dedup: InstanceConfig[] = []
    for (const i of merged) {
      if (!i.name || seen.has(i.name)) continue
      seen.add(i.name)
      dedup.push(i)
    }
    instances.value = dedup
    const stMap: Record<string, string> = {}
    for (const i of dedup) if (i.name && i.serviceType) stMap[i.name] = i.serviceType
    instanceServiceType.value = stMap
  } catch {
    ElMessage.warning(t('pool.messages.loadInstancesFailed'))
    instances.value = []
  } finally {
    instancesLoading.value = false
  }
}

/** 拉取价格与额度目录（按厂商模型名计价），仅用于创建清单的价格分层展示 */
const loadPriceCatalog = async () => {
  try {
    const res = await getPriceCatalog()
    const list = (res.data?.data || []) as ModelPriceCatalogItem[]
    const map: Record<string, { hasPrice: boolean; priceInput: number; priceOutput: number }> = {}
    for (const it of list) {
      map[it.modelName] = { hasPrice: !!it.hasPrice, priceInput: it.priceInput || 0, priceOutput: it.priceOutput || 0 }
    }
    priceMeta.value = map
  } catch {
    /* 价格仅展示用，失败不阻断 */
  }
}

const resetForm = () => {
  Object.assign(form, {
    poolName: '',
    name: '',
    serviceType: 'chat',
    enabled: true,
    strategy: 'weighted-random',
    description: '',
    members: [],
    rateLimitPerMinute: 0,
    hourlyRequestLimit: 0,
    hourlyTokenLimit: 0,
    dailyRequestLimit: 0,
    dailyTokenLimit: 0,
    monthlyRequestLimit: 0,
    monthlyTokenLimit: 0,
    quotaAlertThreshold: 0.8
  })
  selectedNames.value = []
  addPick.value = ''
}

/** 勾选/取消勾选某个模型，并同步到 form.members（保持勾选顺序 = 优先级） */
const toggleName = (name: string, checked: boolean) => {
  const set = new Set(selectedNames.value)
  if (checked) set.add(name); else set.delete(name)
  selectedNames.value = Array.from(set)
  const existing = new Map(form.members.map(m => [m.instanceId, m]))
  form.members = selectedNames.value.map(n => existing.get(n) || { instanceId: n, weight: 1 })
}

const addToMembers = () => {
  if (!addPick.value) return
  if (form.members.some(m => m.instanceId === addPick.value)) return
  form.members.push({ instanceId: addPick.value, weight: 1 })
  addPick.value = ''
}

const moveUp = (idx: number) => {
  if (idx <= 0) return
  const arr = form.members
  ;[arr[idx - 1], arr[idx]] = [arr[idx], arr[idx - 1]]
  form.members = [...arr]
}

const moveDown = (idx: number) => {
  if (idx >= form.members.length - 1) return
  const arr = form.members
  ;[arr[idx + 1], arr[idx]] = [arr[idx], arr[idx + 1]]
  form.members = [...arr]
}

const removeMember = (idx: number) => {
  if (form.members.length <= 1) {
    ElMessage.warning(t('pool.messages.atLeastOneMember'))
    return
  }
  form.members.splice(idx, 1)
}

const handleCreate = () => {
  resetForm()
  isEdit.value = false
  dialogVisible.value = true
  loadAllInstances()
  loadPriceCatalog()
}

const handleEdit = async (row: PoolDefinition) => {
  Object.assign(form, {
    poolName: row.poolName,
    name: row.name || '',
    serviceType: row.serviceType,
    enabled: row.enabled,
    strategy: row.strategy || 'weighted-random',
    description: row.description || '',
    members: (row.members || []).map(m => ({ instanceId: m.instanceId, weight: m.weight })),
    rateLimitPerMinute: row.rateLimitPerMinute ?? 0,
    hourlyRequestLimit: row.hourlyRequestLimit ?? 0,
    hourlyTokenLimit: row.hourlyTokenLimit ?? 0,
    dailyRequestLimit: row.dailyRequestLimit ?? 0,
    dailyTokenLimit: row.dailyTokenLimit ?? 0,
    monthlyRequestLimit: row.monthlyRequestLimit ?? 0,
    monthlyTokenLimit: row.monthlyTokenLimit ?? 0,
    quotaAlertThreshold: row.quotaAlertThreshold ?? 0.8
  })
  selectedNames.value = (form.members || []).map(m => m.instanceId).filter(Boolean) as string[]
  addPick.value = ''
  isEdit.value = true
  dialogVisible.value = true
  loadAllInstances()
  loadPriceCatalog()
}

const handleSave = async () => {
  if (!form.poolName.trim()) {
    ElMessage.warning(t('pool.messages.poolNameRequired'))
    return
  }
  if (!form.members.length || form.members.some(m => !m.instanceId)) {
    ElMessage.warning(t('pool.messages.memberRequired'))
    return
  }
  // 编辑态同样不允许混分类：池的 serviceType 固定，混入其他分类的成员永远不会被路由到
  if (isEdit.value && form.serviceType) {
    const bad = form.members.find(m => {
      const t = instanceServiceType.value[m.instanceId]
      return t && t !== form.serviceType
    })
    if (bad) {
      ElMessage.warning(t('pool.messages.serviceTypeMismatch', {
        a: serviceTypeLabel(form.serviceType),
        b: serviceTypeLabel(instanceServiceType.value[bad.instanceId])
      }))
      return
    }
  }
  // 创建态无显式分类字段：从所选模型推导，并要求全部同分类（后端按分类路由）
  if (!isEdit.value) {
    const types = Array.from(new Set(form.members.map(m => instanceServiceType.value[m.instanceId]).filter(Boolean)))
    if (types.length === 0) {
      ElMessage.warning(t('pool.messages.memberRequired'))
      return
    }
    if (types.length > 1) {
      ElMessage.warning(t('pool.messages.serviceTypeMismatch', { a: serviceTypeLabel(types[0]), b: serviceTypeLabel(types[1]) }))
      return
    }
    form.serviceType = types[0] as string
  }
  // 员工用的模型名 = 引用名（员工在 AI 工具里填的就是它）
  form.name = form.poolName
  saving.value = true
  try {
    const payload: PoolDefinition = { ...form, members: form.members.map(m => ({ ...m })) }
    if (isEdit.value) {
      await updatePool(form.poolName, payload)
      ElMessage.success(t('pool.messages.updated'))
    } else {
      await createPool(payload)
      ElMessage.success(t('pool.messages.created'))
    }
    dialogVisible.value = false
    await fetchPools()
  } catch (_e) {
    ElMessage.error(t('pool.messages.saveFailed'))
  } finally {
    saving.value = false
  }
}

/** 列表操作列「配额」：单独弹出配额对话框，不进新建/编辑表单 */
const openQuota = (row: PoolDefinition) => {
  quotaTargetName.value = row.name || row.poolName
  Object.assign(quotaForm, {
    poolName: row.poolName,
    rateLimitPerMinute: row.rateLimitPerMinute ?? 0,
    hourlyRequestLimit: row.hourlyRequestLimit ?? 0,
    hourlyTokenLimit: row.hourlyTokenLimit ?? 0,
    dailyRequestLimit: row.dailyRequestLimit ?? 0,
    dailyTokenLimit: row.dailyTokenLimit ?? 0,
    monthlyRequestLimit: row.monthlyRequestLimit ?? 0,
    monthlyTokenLimit: row.monthlyTokenLimit ?? 0,
    quotaAlertThreshold: row.quotaAlertThreshold ?? 0.8
  })
  quotaVisible.value = true
}

const saveQuota = async () => {
  if (!quotaForm.poolName) return
  saving.value = true
  try {
    // 取全量定义后只覆盖额度字段，避免误清成员/策略等
    const full = await getPool(quotaForm.poolName)
    const base = (full.data?.data || {}) as PoolDefinition
    const payload: PoolDefinition = {
      ...base,
      poolName: quotaForm.poolName,
      rateLimitPerMinute: quotaForm.rateLimitPerMinute,
      hourlyRequestLimit: quotaForm.hourlyRequestLimit,
      hourlyTokenLimit: quotaForm.hourlyTokenLimit,
      dailyRequestLimit: quotaForm.dailyRequestLimit,
      dailyTokenLimit: quotaForm.dailyTokenLimit,
      monthlyRequestLimit: quotaForm.monthlyRequestLimit,
      monthlyTokenLimit: quotaForm.monthlyTokenLimit,
      quotaAlertThreshold: quotaForm.quotaAlertThreshold
    }
    await updatePool(quotaForm.poolName, payload)
    ElMessage.success(t('pool.messages.updated'))
    quotaVisible.value = false
    await fetchPools()
  } catch (_e) {
    ElMessage.error(t('pool.messages.saveFailed'))
  } finally {
    saving.value = false
  }
}

/** 删除前依赖预检：密钥白名单引用该分组时禁止删除 */
const { precheckDeletion } = useDependencyGuard()

const handleDelete = async (row: PoolDefinition) => {
  const pre = await precheckDeletion('pool', row.poolName)
  if (pre.blocked) return
  const warnText = (pre.report?.warnings || []).join('；')
  const impact = warnText
    ? `${t('pool.deleteDialog.impact')}（${warnText}）`
    : t('pool.deleteDialog.impact')
  const confirmed = await confirmDanger({
    title: t('pool.deleteDialog.title'),
    target: row.poolName,
    impact,
    remedy: t('pool.deleteDialog.remedy'),
    requireType: row.poolName,
    confirmText: t('pool.deleteDialog.confirm'),
    cancelText: t('pool.deleteDialog.cancel'),
    type: 'warning'
  })
  if (!confirmed) return
  try {
    await deletePool(row.poolName)
    ElMessage.success(t('pool.messages.deleted'))
    await fetchPools()
  } catch (_e) {
    ElMessage.error(t('pool.messages.deleteFailed'))
  }
}

/**
 * 多租户下切换顶部租户 → 重新拉取虚拟模型列表。
 * 后端 config_data 按 X-Tenant-Id 行级过滤，列表必须跟随当前租户重取；
 * 价格目录 / 实例清单是平台级数据，不受租户影响，无需重拉。
 * 社区版 enterpriseStore.multiTenant=false，不重载，行为零变化。
 */
watch(
  () => tenantStore.currentTenantId,
  (next, prev) => {
    if (enterpriseStore.multiTenant && next !== prev) {
      void fetchPools()
    }
  }
)

onMounted(() => {
  fetchPools()
  loadPriceCatalog()
  // 探测配额账本开关 → 决定额度配置弹窗里每小时 / 每月字段是否禁用
  void loadQuotaLedgerState()
  // 旧 /scenarios 链接与指南页 CTA 重定向到 ?create=scenario，自动打开向导弹窗
  if (canWrite && route.query.create === 'scenario') {
    scenarioVisible.value = true
  }
})
</script>

<style scoped>
.page-heading h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

.page-heading__sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--ja-text-secondary);
}

.form-tip {
  width: 100%;
  margin-top: 6px;
  font-size: 12px;
  color: var(--ja-text-secondary);
  line-height: 1.5;
}

.strategy-name {
  cursor: help;
}

.strategy-option {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 0;
}

.strategy-option-name {
  font-size: 13px;
  color: var(--ja-text-primary);
}

.strategy-option-desc {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

/* 列表列的额度摘要 */
.limit-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.limit-chip {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--el-fill-color-light);
  color: var(--ja-text-regular);
}

.cell-sub {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

/* ---------- 虚拟模型说明面板（原型风格） ---------- */
.vm-explain {
  background: rgba(23, 108, 107, 0.06);
  border: 1px dashed var(--ja-primary, #176C6B);
  border-radius: 12px;
  padding: 14px 16px;
  margin-bottom: 16px;
}

.vm-explain__title {
  font-size: 13.5px;
  color: var(--ja-text-primary);
}

.vm-explain__body {
  font-size: 12.5px;
  color: var(--ja-text-secondary);
  margin-top: 4px;
  line-height: 1.7;
}

/* 员工用的模型名：显示名 + model-name 副标题 + 已停用标签 */
.vm-name__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ja-text-primary);
  margin-right: 6px;
}

.vm-name__sub {
  font-size: 11px;
  color: var(--ja-text-tertiary, #6b7d7c);
  font-family: var(--ja-font-mono);
  margin-top: 2px;
}

.vm-name__desc {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-top: 3px;
  line-height: 1.4;
}

/* 背后挂的模型：有序 chip（主 / 备 N） */
.vm-members {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.model-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12.5px;
  padding: 2px 8px 2px 6px;
  border-radius: 999px;
  background: var(--el-fill-color-light);
  color: var(--ja-text-regular);
  border: 1px solid var(--ja-border-light, rgba(16, 38, 38, 0.08));
}

.model-chip--primary {
  background: rgba(23, 108, 107, 0.10);
  border-color: rgba(23, 108, 107, 0.25);
}

.model-chip__badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 999px;
  font-size: 10px;
  font-style: normal;
  background: var(--ja-text-tertiary, #6b7d7c);
  /* 白字只在亮色成立：暗色下底色变浅灰，改用卡片底色文字（亮=白 / 暗=深）保证对比 */
  color: var(--ja-bg-card);
}

.model-chip__badge--primary {
  background: var(--ja-primary, #176C6B);
}

.vm-calls {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  color: var(--ja-text-primary);
}

/* ---------- 对话框：名称 + 模型清单 + 故障转移策略（原型风格） ---------- */
.vm-dialog-desc {
  font-size: 12.5px;
  color: var(--ja-text-secondary);
  line-height: 1.7;
  margin-bottom: 14px;
  padding: 10px 12px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
}

.vm-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.vm-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.vm-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

/* 创建：模型勾选清单（带价格分层标签） */
.model-check-list {
  max-height: 300px;
  overflow-y: auto;
  border: 1px solid var(--ja-border-light, rgba(16, 38, 38, 0.12));
  border-radius: 8px;
  padding: 4px;
}

.model-check-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
}

.model-check-row:hover {
  background: var(--el-fill-color-light);
}

/* 分类锁定：与已选主模型分类不一致的候选置灰不可选 */
.model-check-row.is-locked {
  opacity: 0.45;
  cursor: not-allowed;
}
.model-check-row.is-locked:hover {
  background: transparent;
}

.model-check-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--ja-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 价格分层标签 */
.price-tag {
  flex-shrink: 0;
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  white-space: nowrap;
}

.price-tag--cheap {
  background: var(--ja-chip-success-bg);
  color: var(--ja-chip-success-text);
}

.price-tag--medium {
  background: var(--ja-chip-warning-bg);
  color: var(--ja-chip-warning-text);
}

.price-tag--expensive {
  background: var(--ja-chip-danger-bg);
  color: var(--ja-chip-danger-text);
}

.price-tag--vector,
.price-tag--none {
  background: var(--el-fill-color);
  color: var(--ja-text-secondary);
}

/* ==================== P0-F：成员 chip 价格标签 ==================== */
.model-chip__name {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-chip__price {
  font-style: normal;
  font-size: 11px;
  padding: 0 6px;
  border-radius: 999px;
  white-space: nowrap;
  background: var(--el-fill-color);
  color: var(--ja-text-secondary);
}

.model-chip__price--cheap {
  background: var(--ja-chip-success-bg);
  color: var(--ja-chip-success-text);
}

.model-chip__price--medium {
  background: var(--ja-chip-warning-bg);
  color: var(--ja-chip-warning-text);
}

.model-chip__price--expensive {
  background: var(--ja-chip-danger-bg);
  color: var(--ja-chip-danger-text);
}

.model-chip__price--vector {
  background: var(--ja-chip-info-bg);
  color: var(--ja-chip-info-text);
}

.model-chip__price--none {
  background: var(--ja-chip-danger-soft-bg);
  color: var(--ja-chip-danger-soft-text);
}

/* ==================== P0-F：价格状态列 ==================== */
.price-status {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.price-status__sub {
  font-size: 11px;
  color: var(--ja-text-secondary);
}

/* ==================== P0-F：配额摘要 ==================== */
.quota-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.quota-chip {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 999px;
  white-space: nowrap;
  background: var(--el-fill-color-light);
  color: var(--ja-text-regular);
  border: 1px solid var(--ja-border-light, rgba(16, 38, 38, 0.08));
}

.quota-chip--rate {
  background: var(--ja-chip-rate-bg);
  color: var(--ja-chip-rate-text);
  border-color: var(--ja-chip-rate-border);
}

.quota-chip--token {
  background: var(--ja-chip-info-bg);
  color: var(--ja-chip-info-text);
  border-color: var(--ja-chip-info-border);
}

/* ==================== P0-F：编辑弹窗里的权重输入 ==================== */
.member-weight {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.member-weight__label {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

/* 编辑：真实模型优先级有序列表 */
.model-order-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.model-order-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 10px;
  border: 1px solid var(--ja-border-light, rgba(16, 38, 38, 0.12));
  border-radius: 8px;
}

/* 上行：徽章 + 模型名 + 权重 + 操作按钮 */
.model-order-main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.model-order-badge {
  flex-shrink: 0;
  min-width: 30px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--ja-text-tertiary, #6b7d7c);
  /* 白字只在亮色成立：暗色下底色变浅灰，改用卡片底色文字（亮=白 / 暗=深）保证对比 */
  color: var(--ja-bg-card);
}

.model-order-badge--primary {
  background: var(--ja-primary, #176C6B);
}

.model-order-name {
  /* 保底宽度：模型名是这行的主信息，不能被描述文字挤没 */
  flex: 1 1 auto;
  min-width: 130px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ja-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-order-desc {
  /* 描述独占第二行，整宽显示 —— 不与模型名抢宽度 */
  font-size: 11px;
  line-height: 1.35;
  color: var(--ja-text-secondary);
  padding-left: 40px;
}

.model-order-ops {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 2px;
}

.model-order-add {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}

.member-empty {
  padding: 16px 4px;
  text-align: center;
  font-size: 12px;
  color: var(--ja-text-secondary);
}

/* ---------- 额度与限速（独立配额对话框） ---------- */
.quota-block {
  width: 100%;
}

.quota-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 18px;
  margin: 8px 0 6px;
}

.quota-field {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.quota-field--wide {
  grid-column: 1 / -1;
}

.quota-label {
  font-size: 12px;
  color: var(--ja-text-secondary);
  white-space: nowrap;
}

.quota-hint {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.quota-hint--warn {
  color: var(--el-color-warning);
}
</style>
