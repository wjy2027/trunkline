<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<template>
  <!--
    「模型配置」合并表（接入管理 · 第 2 个 tab）

    一行 = 一个已接入模型实例（原先分散在「已接入模型」与「价格与额度」两张表的内容合并到一处）：
    - 左半：实例信息（模型名 / 上游厂商地址 / 分类 / 状态 + 健康 + 熔断运行态）
    - 右半：单价（行内可编辑）、额度与限速、计价开关、熔断与限流（开关 + 详细配置）
    - 操作：编辑实例、删除实例（保留原先两张表各自独有的能力，不做功能裁剪）

    数据口径（与后端一致，勿改）：
    - 单价 / 额度：model_price 表，主键 = 模型名（= 实例名 instance_name）
      → 按实例名 join，与 BillingService 按 token_usage.instance_name 计价完全一致
    - 熔断 / 限流：实例级（instance_rate_limit / instance_circuit_breaker 按 instance_id）
      → 直接用实例 DTO 自带的配置（convertToDTO 已装配），比价格目录的「代表实例」口径更准
  -->
  <PageSkeleton>
    <template #toolbar>
      <el-input
        v-model="searchQuery"
        :placeholder="t('modelConfig.searchPlaceholder')"
        clearable
        size="default"
        class="search-input"
        @clear="handleSearchClear"
        @keyup.enter="applySearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select
        v-model="typeFilter"
        :placeholder="t('instance.serviceTypeLabel')"
        clearable
        size="default"
        class="filter-select"
      >
        <el-option :label="t('instance.all')" value="" />
        <el-option v-for="st in serviceTypes" :key="st" :label="serviceTypeLabel(st)" :value="st" />
      </el-select>

      <el-select
        v-model="statusFilter"
        :placeholder="t('modelConfig.statusFilter')"
        clearable
        size="default"
        class="filter-select"
      >
        <el-option :label="t('instance.all')" value="" />
        <el-option :label="t('instance.enabled')" value="active" />
        <el-option :label="t('instance.disabled')" value="inactive" />
      </el-select>

      <el-button text class="refresh-button" :loading="loading" @click="refreshAll">
        <el-icon><Refresh /></el-icon>
      </el-button>

      <el-button text class="refresh-button" :disabled="filtered.length === 0" @click="handleExport">
        <el-icon><Download /></el-icon>
      </el-button>

      <!-- 批量操作条：选中后浮现 -->
      <div v-if="canWrite && selectedRows.length > 0" class="batch-bar">
        <span class="batch-bar__count">
          {{ t('instance.batch.selected', { count: selectedRows.length }) }}
        </span>
        <el-button size="small" type="success" plain @click="handleBatchEnable">
          {{ t('instance.batch.enable') }}
        </el-button>
        <el-button size="small" type="warning" plain @click="handleBatchDisable">
          {{ t('instance.batch.disable') }}
        </el-button>
        <el-button size="small" type="danger" plain @click="handleBatchDelete">
          {{ t('instance.batch.delete') }}
        </el-button>
        <el-button size="small" link @click="clearSelection">{{ t('instance.batch.clear') }}</el-button>
      </div>
    </template>

    <el-alert
      v-if="!canWrite"
      type="info"
      :closable="false"
      class="readonly-tip"
      :title="t('modelConfig.readOnlyTip')"
    />

    <!--
      单表口径（原「额度 / 价格」双 tab 已合并）：两张 tab 除单价/计费两列外完全相同，
      用户会晕——现在一张实例表同时呈现额度与单价。
      - 额度与限速：开源基础能力（/api/config/quota），列常驻。
      - 单价 + 计费开关：商业能力（/api/config/prices），仅当 enterprise.billing 功能位解锁时
        作为两列追加到表尾；社区版不渲染这两列，列表与开源版完全一致。
    -->

    <el-table
      ref="modelConfigTableRef"
      :data="paginated"
      :max-height="modelConfigMaxHeight"
      v-loading="loading"
      size="small"
      style="width: 100%"
      class="model-config-table"
      row-key="id"
      border
      fit
      @selection-change="onSelectionChange"
    >
      <el-table-column v-if="canWrite" type="selection" width="46" :reserve-selection="false" />

      <!-- 模型名：备注名 + 真实模型名 + 模型分类 + 未入组标记 -->
      <el-table-column prop="name" :label="t('instance.columnModelName')" min-width="210" header-align="center">
        <template #default="scope">
          <div class="cell-title">{{ scope.row.tags?.alias || scope.row.name }}</div>
          <div class="cell-sub">
            <span>{{ t('instance.modelPrefix') }}{{ scope.row.name }}</span>
            <el-tag size="small" type="info" effect="plain" class="type-tag">
              {{ scope.row.serviceType ? serviceTypeLabel(scope.row.serviceType) : '-' }}
            </el-tag>
          </div>
          <el-tooltip v-if="isUngrouped(scope.row)" :content="t('instance.ungroupedTip')" placement="top">
            <el-tag type="danger" size="small" effect="plain" class="ungrouped-tag">
              {{ t('instance.ungrouped') }}
            </el-tag>
          </el-tooltip>
        </template>
      </el-table-column>

      <!-- 上游厂商 + 接入地址 -->
      <el-table-column prop="baseUrl" :label="t('instance.columnVendorAddress')" min-width="200" header-align="center">
        <template #default="scope">
          <div class="cell-title">
            {{ providerLabelOf(scope.row) }}
            <el-tag v-if="scope.row.providerName" size="small" type="warning" effect="plain" class="provider-tag">
              {{ t('provider.fromProvider') }}{{ scope.row.providerName }}
            </el-tag>
          </div>
          <div class="cell-sub">
            <el-tooltip :content="`${scope.row.baseUrl}${scope.row.path || ''}`" placement="top">
              <span>{{ extractDomain(scope.row.baseUrl) }}{{ scope.row.path || '' }}</span>
            </el-tooltip>
          </div>
        </template>
      </el-table-column>

      <!-- 状态：启用/停用 + 熔断运行态 + 健康点 -->
      <el-table-column prop="status" :label="t('instance.status')" width="124" align="center">
        <template #default="scope">
          <div class="status-cell">
            <el-tag :type="scope.row.status === 'active' ? 'success' : 'info'" class="table-tag">
              {{ scope.row.status === 'active' ? t('instance.enabled') : t('instance.disabled') }}
            </el-tag>
            <el-tooltip v-if="runtimeStateOf(scope.row).tip" :content="runtimeStateOf(scope.row).tip" placement="top">
              <el-tag :type="runtimeStateOf(scope.row).tagType" size="small" class="table-tag">
                {{ runtimeStateOf(scope.row).label }}
              </el-tag>
            </el-tooltip>
            <span
              v-else-if="scope.row.healthStatus !== undefined && scope.row.healthStatus !== null"
              class="health-dot"
              :class="healthDotClass(scope.row)"
              :title="healthTitleOf(scope.row)"
            />
          </div>
        </template>
      </el-table-column>

      <!-- #9 数据分级出境：地域列为企业版业务 UI，OSS 仅提供空挂载位（契约见 extension/types.ts） -->
      <el-table-column v-if="residencyEnabled" width="150" align="center">
        <template #header>
          <EnterpriseSlot
            kind="residency-header"
            :ctx="{ canWrite, onChanged: reloadAfterResidency }"
            :enabled="residencyEnabled"
          />
        </template>
        <template #default="scope">
          <EnterpriseSlot
            kind="residency-cell"
              :ctx="{
                instanceName: String(scope.row.runtimeInstanceId || scope.row.name),
                canWrite,
                onChanged: reloadAfterResidency
              }"
            :enabled="residencyEnabled"
          />
        </template>
      </el-table-column>

      <!-- 熔断 · 限流：两个实例级开关竖排。
           紧跟「状态」列 —— 原来它们排在第 10/11 列，右侧被固定操作列挡住、
           首屏根本看不见，管理员不知道去哪儿找；现在一眼可见。 -->
      <el-table-column width="178" align="center">
        <template #header>{{ t('modelConfig.columnGuard') }} <TermHelp term="circuitBreaker" /></template>
        <template #default="scope">
          <div class="switch-stack">
            <div class="switch-row">
              <el-switch
                v-model="scope.row.circuitBreakerEnabled"
                :disabled="!canWrite"
                :loading="cbToggling === scope.row.id"
                inline-prompt
                active-text="开"
                inactive-text="关"
                @change="toggleCircuitBreaker(scope.row)"
              />
              <el-button
                link
                type="primary"
                size="small"
                :disabled="!canWrite"
                @click="openCircuitBreakerConfig(scope.row)"
              >
                {{ t('modelConfig.cbShort') }}
              </el-button>
            </div>
            <div class="switch-row">
              <el-switch
                v-model="scope.row.rateLimitEnabled"
                :disabled="!canWrite"
                :loading="rlToggling === scope.row.id"
                inline-prompt
                active-text="开"
                inactive-text="关"
                @change="toggleRateLimit(scope.row)"
              />
              <el-button
                link
                type="primary"
                size="small"
                :disabled="!canWrite"
                @click="openRateLimitConfig(scope.row)"
              >
                {{ t('modelConfig.rlShort') }}
              </el-button>
            </div>
          </div>
        </template>
      </el-table-column>

      <!-- 单价：输入 / 输出 / 缓存 竖排。改完即存（el-input-number 的 change 在值变化并失焦时触发一次），
           不再单列「保存价格」按钮 —— 少一个要找的按钮。
           界面单位统一 ¥ / 百万 tokens（业内口径），API 边界自动换算回后端 ¥/千 token。
           仅 enterprise.billing 功能位解锁时可见（商业能力）。 -->
      <el-table-column v-if="enterpriseStore.billing" width="220" align="right" header-align="center">
        <template #header>
          <div class="price-col-head">
            <span>{{ t('modelConfig.columnPrice') }}</span>
            <span class="price-col-unit">{{ t('modelConfig.priceUnitHeader') }}</span>
            <el-tooltip :content="t('modelConfig.priceUnitTip')" placement="top">
              <el-icon class="col-help"><QuestionFilled /></el-icon>
            </el-tooltip>
          </div>
        </template>
        <template #default="scope">
          <div class="price-stack">
            <div class="price-row">
              <span class="price-label">{{ t('modelConfig.priceInputShort') }}</span>
              <el-input-number
                v-model="scope.row.priceInput"
                :min="0"
                :precision="4"
                :step="0.5"
                :controls="false"
                :disabled="!canWrite"
                size="small"
                class="price-input"
                @change="onPriceChanged(scope.row)"
              />
            </div>
            <div class="price-row">
              <span class="price-label">{{ t('modelConfig.priceOutputShort') }}</span>
              <el-input-number
                v-model="scope.row.priceOutput"
                :min="0"
                :precision="4"
                :step="0.5"
                :controls="false"
                :disabled="!canWrite"
                size="small"
                class="price-input"
                @change="onPriceChanged(scope.row)"
              />
            </div>
            <div class="price-row">
              <span class="price-label">{{ t('modelConfig.priceCacheShort') }}</span>
              <el-input-number
                v-model="scope.row.priceCache"
                :min="0"
                :precision="4"
                :step="0.5"
                :controls="false"
                :disabled="!canWrite"
                size="small"
                class="price-input"
                @change="onPriceChanged(scope.row)"
              />
            </div>
            <!-- 高级计价规则入口：每行单价旁的按钮由企业子应用渲染（阶梯/时段就地编辑，不跳页） -->
            <EnterpriseSlot
              kind="pricing-rules-entry"
              :ctx="{
                instanceName: String(scope.row.name),
                flatPriceInput: Number(scope.row.priceInput) / 1000,
                flatPriceOutput: Number(scope.row.priceOutput) / 1000,
                flatPriceCacheInput: Number(scope.row.priceCache) / 1000
              }"
              :enabled="pricingRulesEnabled"
            />
          </div>
        </template>
      </el-table-column>

      <!-- 计价开关：关闭 → 该模型不参与账单计价（¥0）。仅 enterprise.billing 功能位解锁时可见 -->
      <el-table-column v-if="enterpriseStore.billing" :label="t('modelConfig.columnBilling')" width="86" align="center">
        <template #header>
          <span>{{ t('modelConfig.columnBilling') }}</span>
          <el-tooltip :content="t('modelConfig.billingTip')" placement="top">
            <el-icon class="col-help"><QuestionFilled /></el-icon>
          </el-tooltip>
        </template>
        <template #default="scope">
          <el-switch v-model="scope.row.priceEnabled" :disabled="!canWrite" size="small" />
        </template>
      </el-table-column>

      <!-- 额度与限速（摘要 chips，明细在弹窗里改）。开源基础能力，列常驻 -->
      <el-table-column min-width="190" header-align="center">
        <template #header>{{ t('modelConfig.columnQuota') }} <TermHelp term="rateLimit" /></template>
        <template #default="scope">
          <div class="limit-summary">
            <span v-if="scope.row.rateLimitPerMinute > 0" class="limit-chip">
              {{ t('modelConfig.limitPerMinute', { n: scope.row.rateLimitPerMinute }) }}
            </span>
            <span v-if="scope.row.hourlyRequestLimit > 0" class="limit-chip">
              {{ t('modelConfig.limitHourlyReq', { n: scope.row.hourlyRequestLimit }) }}
            </span>
            <span v-if="scope.row.hourlyTokenLimit > 0" class="limit-chip">
              {{ t('modelConfig.limitHourlyToken', { n: scope.row.hourlyTokenLimit }) }}
            </span>
            <span v-if="scope.row.dailyRequestLimit > 0" class="limit-chip">
              {{ t('modelConfig.limitDailyReq', { n: scope.row.dailyRequestLimit }) }}
            </span>
            <span v-if="scope.row.dailyTokenLimit > 0" class="limit-chip">
              {{ t('modelConfig.limitDailyToken', { n: scope.row.dailyTokenLimit }) }}
            </span>
            <span v-if="scope.row.monthlyRequestLimit > 0" class="limit-chip">
              {{ t('modelConfig.limitMonthlyReq', { n: scope.row.monthlyRequestLimit }) }}
            </span>
            <span v-if="scope.row.monthlyTokenLimit > 0" class="limit-chip">
              {{ t('modelConfig.limitMonthlyToken', { n: scope.row.monthlyTokenLimit }) }}
            </span>
            <span v-if="!hasAnyLimit(scope.row)" class="cell-sub">{{ t('modelConfig.noLimit') }}</span>
            <el-button
              v-if="canWrite"
              link
              type="primary"
              size="small"
              class="quota-config-btn"
              @click="openQuotaDialog(scope.row)"
            >
              {{ t('modelConfig.quotaConfig') }}
            </el-button>
          </div>
        </template>
      </el-table-column>

      <!-- 操作：编辑实例 / 删除实例（地域入口由企业子应用注入空位，OSS 不含业务 UI） -->
      <el-table-column :label="t('instance.actions')" width="160" align="center" fixed="right">
        <template #default="scope">
          <div class="row-actions">
            <EnterpriseSlot
              v-if="residencyEnabled"
              kind="instance-region-action"
              :ctx="regionActionCtx(scope.row)"
              :enabled="residencyEnabled && canWrite"
            />
            <el-button
              v-if="canWrite"
              link
              type="primary"
              size="small"
              @click="handleEdit(scope.row)"
            >
              {{ t('modelConfig.editShort') }}
            </el-button>
            <el-button
              v-if="canWrite"
              link
              type="danger"
              size="small"
              @click="handleDelete(scope.row)"
            >
              {{ t('instance.delete') }}
            </el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="!loading && filtered.length === 0" class="empty-wrap">
      <el-empty :description="t('instance.emptyText')" />
    </div>

    <template #footer>
      <div v-if="filtered.length > 0" class="table-footer">
        <div class="footer-info">
          {{ t('instance.tableSummary', { count: filtered.length, current: currentPage, total: totalPages }) }}
        </div>
        <div class="footer-actions">
          <el-pagination
            v-model:current-page="currentPage"
            :page-size="pageSize"
            :total="filtered.length"
            layout="total, sizes, prev, pager, next, jumper"
            :page-sizes="[10, 20, 50, 100]"
            @size-change="handleSizeChange"
            @current-change="handlePageChange"
          />
        </div>
      </div>
    </template>
  </PageSkeleton>

  <!-- ==================== 额度与限速弹窗 ==================== -->
  <el-dialog v-model="quotaVisible" :title="t('modelConfig.quotaDialogTitle', { name: quotaForm?.name || '' })" width="min(640px, 92vw)">
    <el-alert
      v-if="quotaForm"
      type="info"
      :closable="false"
      class="section-tip"
      :title="quotaForm.name"
    />
    <el-form v-if="quotaForm" label-width="140px">
      <!-- 2026-11-28：各窗口分组随全局统计窗口联动（窗口关闭则整组不显示） -->
      <div v-if="isQuotaFieldVisible('minuteRequests')" class="quota-group-title">{{ t('modelConfig.quotaGroupRate') }}</div>
      <el-form-item v-if="isQuotaFieldVisible('minuteRequests')" label="每分钟请求上限">
        <el-input-number v-model="quotaForm.rateLimitPerMinute" :min="0" :step="10" />
        <span class="field-hint">{{ t('modelConfig.fieldPerMinute') }}</span>
      </el-form-item>

      <div v-if="isQuotaFieldVisible('hourRequests') || isQuotaFieldVisible('hourTokens')" class="quota-group-title">{{ t('modelConfig.quotaGroupHourly') }}</div>
      <el-form-item v-if="isQuotaFieldVisible('hourRequests')" label="请求上限">
        <el-input-number v-model="quotaForm.hourlyRequestLimit" :min="0" :step="100" />
        <span class="field-hint">{{ t('modelConfig.fieldRequests') }}</span>
      </el-form-item>
      <el-form-item v-if="isQuotaFieldVisible('hourTokens')" label="Token 上限">
        <el-input-number v-model="quotaForm.hourlyTokenLimit" :min="0" :step="1000" />
        <span class="field-hint">{{ t('modelConfig.fieldTokens') }}</span>
      </el-form-item>

      <div v-if="isQuotaFieldVisible('dayRequests') || isQuotaFieldVisible('dayTokens')" class="quota-group-title">{{ t('modelConfig.quotaGroupDaily') }}</div>
      <el-form-item v-if="isQuotaFieldVisible('dayRequests')" label="请求上限">
        <el-input-number v-model="quotaForm.dailyRequestLimit" :min="0" :step="100" />
        <span class="field-hint">{{ t('modelConfig.fieldRequests') }}</span>
      </el-form-item>
      <el-form-item v-if="isQuotaFieldVisible('dayTokens')" label="Token 上限">
        <el-input-number v-model="quotaForm.dailyTokenLimit" :min="0" :step="1000" />
        <span class="field-hint">{{ t('modelConfig.fieldTokens') }}</span>
      </el-form-item>

      <div v-if="isQuotaFieldVisible('monthRequests') || isQuotaFieldVisible('monthTokens')" class="quota-group-title">{{ t('modelConfig.quotaGroupMonthly') }}</div>
      <!-- 每月限额仅账本开启时真实生效；账本关闭时禁用（与「全部密钥」页口径一致） -->
      <el-form-item v-if="isQuotaFieldVisible('monthRequests')" label="请求上限">
        <el-input-number v-model="quotaForm.monthlyRequestLimit" :min="0" :step="1000" :disabled="!multiWindowEnforced" />
        <span class="field-hint">{{ t('modelConfig.fieldRequests') }}</span>
      </el-form-item>
      <el-form-item v-if="isQuotaFieldVisible('monthTokens')" label="Token 上限">
        <el-input-number v-model="quotaForm.monthlyTokenLimit" :min="0" :step="10000" :disabled="!multiWindowEnforced" />
        <span class="field-hint">{{ t('modelConfig.fieldTokens') }}</span>
      </el-form-item>
      <div v-if="!multiWindowEnforced" class="field-hint field-hint--warn">
        {{ t('apiKeys.quotaForm.inertFieldHint') }}
      </div>

      <div class="quota-group-title">{{ t('modelConfig.quotaGroupAlert') }}</div>
      <el-form-item label="配额告警阈值">
        <el-slider
          v-model="quotaForm.quotaAlertThreshold"
          :min="0.1"
          :max="1"
          :step="0.05"
          :format-tooltip="(v: number) => `${Math.round(v * 100)}%`"
          style="width: 260px"
        />
        <span class="field-hint">{{ t('modelConfig.fieldAlertHint') }}</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="quotaVisible = false">{{ t('instance.cancel') }}</el-button>
      <el-button type="primary" :loading="savingQuota" @click="saveQuota">{{ t('instance.save') }}</el-button>
    </template>
  </el-dialog>

  <!-- ==================== 熔断器配置弹窗（实例级） ==================== -->
  <CircuitBreakerConfig
    v-model="cbVisible"
    :title="cbTitle"
    :initial-data="cbInitialData"
    @save="handleCircuitBreakerSave"
  />

  <!-- ==================== 限流器配置弹窗（实例级） ==================== -->
  <RateLimitConfig
    v-model="rlVisible"
    :title="rlTitle"
    :initial-data="rlInitialData"
    @save="handleRateLimitSave"
  />

  <!-- ==================== 编辑实例弹窗 ==================== -->
  <el-dialog
    v-model="dialogVisible"
    :title="t('instance.editInstance')"
    width="min(800px, 92vw)"
    :before-close="handleDialogClose"
    class="instance-dialog"
    @opened="onDialogOpened"
  >
    <UpstreamInstanceForm
      ref="instanceFormRef"
      :service-types="serviceTypes"
      :service-type-label="serviceTypeLabel"
      :adapters="adapters"
      :global-adapter="globalAdapter"
      :providers="providerOptions"
      :is-edit="true"
      :advanced="true"
      :simplified="true"
      :region-enabled="residencyEnabled"
    />
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleDialogClose">{{ t('instance.cancel') }}</el-button>
        <el-button type="primary" :loading="saveLoading" @click="handleSave">{{ t('instance.save') }}</el-button>
      </span>
    </template>
  </el-dialog>

  <!-- ==================== 企业业务 UI 已迁至企业子应用 module.mjs，经 EnterpriseSlot 注入 ==================== -->
</template>

<script setup lang="ts">
/**
 * 模型配置（接入管理 · 第 2 个 tab）
 *
 * <b>由原「价格与额度」与「已接入模型」两张表合并而成</b>：
 * - 「价格与额度」：单价、额度与限速、计价开关、熔断/限流开关与详细配置
 * - 「已接入模型」：模型名 / 上游厂商地址 / 分类 / 状态与健康 / 未入组标记、
 *   批量启停与删除、CSV 导出、实例编辑（权重 / 适配器 / 接入地址）、依赖预检删除
 *
 * 两张表的能力全部保留，只是并到一行里——原先同一个模型在两张表里各出现一次，
 * 熔断/限流两列重复配置入口，非技术用户很容易「在 A 表改了，B 表没改」。
 *
 * 数据口径（与后端一致）：
 * - 单价 / 额度 → model_price（主键 model_name = 实例名），按实例名 join，与计价口径一致
 * - 熔断 / 限流 → 实例级配置，用实例 DTO 自带值（convertToDTO 已装配），比价格目录的
 *   「代表实例」口径更准，且省掉一次二次请求
 */
import { computed, onMounted, provide, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, QuestionFilled, Refresh, Search, Setting as _Setting } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { SERVICE_TYPE_LABELS, COMMON_SERVICE_TYPES } from '@/constants/serviceTypes'
import { matchProviderPreset, extractDomain } from '@/constants/providerPresets'
import { useProviderTemplatePresets } from '@/composables/useProviderTemplatePresets'
import {
  deleteServiceInstance,
  getServiceInstances,
  updateServiceInstance,
  saveRateLimitConfig,
  saveCircuitBreakerConfig,
  type InstanceConfig,
  type RateLimitConfig as RateLimitConfigType,
  type CircuitBreakerConfig as CircuitBreakerConfigType
} from '@/api/instance'
import { getServiceTypes } from '@/api/dashboard'
import { getAdapters, getAllConfigurations } from '@/api/service'
import { getProviders, type ModelProvider } from '@/api/provider'
import { getPoolList, type PoolDefinition } from '@/api/pools'
import { getPriceCatalog, upsertModelPrice, type ModelPriceCatalogItem } from '@/api/providerPrice'
import {
  getModelQuotaCatalog,
  upsertModelQuota,
  type ModelQuotaCatalogItem
} from '@/api/modelQuota'
import { useEnterpriseStore } from '@/stores/enterprise'
import { clearCache } from '@/stores/playgroundCache'
import { exportCsv, datedFilename } from '@/utils/csvExport'
import {
  fetchCircuitStates as fetchSharedCircuitStates,
  circuitStateOf as sharedCircuitStateOf
} from '@/composables/useInstanceAvailability'
import CircuitBreakerConfig from '@/components/CircuitBreakerConfig.vue'
import EnterpriseSlot from '@/components/EnterpriseSlot.vue'
import RateLimitConfig from '@/components/RateLimitConfig.vue'
import UpstreamInstanceForm from '@/components/UpstreamInstanceForm.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import TermHelp from '@/components/common/TermHelp.vue'
import { usePermission } from '@/composables/usePermission'
import { usePersistedState } from '@/composables/usePersistedState'
import { confirmDanger } from '@/utils/dangerConfirm'
import { useDependencyGuard } from '@/composables/useDependencyGuard'
import { useQuotaLedger } from '@/composables/useQuotaLedger'
import { useQuotaWindows } from '@/composables/useQuotaWindows'
import { useTableMaxHeight } from '@/composables/useTableMaxHeight'
import type { InstanceRegionActionCtx } from '@/extension/types'

const { t } = useI18n()
// 企业功能位（billing 决定是否显示「价格」tab）
const enterpriseStore = useEnterpriseStore()
/** 单表口径：原 quota/price 双 tab 已合并，不再需要分栏状态 */
// 本面板只作为聚合页（接入管理）的 tab 内容嵌入，自身无页头——
// 走 PageSkeleton 嵌入模式：隐藏空页头、去掉自带 padding，搜索框贴紧 tab 栏
provide('skeletonEmbedded', true)
// 配额账本开关（单例探针）：关闭时额度配置弹窗里的每月字段禁用
const { multiWindowEnforced, loadQuotaLedgerState } = useQuotaLedger()
// 统计窗口联动（2026-11-28）：全局 windows 关闭的窗口，厂商模型配额分组不显示
const { isFieldVisible: isQuotaFieldVisible } = useQuotaWindows()
const { hasPermission } = usePermission()
const canWrite = computed(() => hasPermission('config:instances:write'))

// ---------- 单价行的高级计价入口门控（规则 UI 由企业子应用在挂载位渲染） ----------
const pricingRulesEnabled = computed(
  () => enterpriseStore.billing && enterpriseStore.isEnabled('pricingAdvanced')
)

// ---------- #9 数据出境：OSS 只留功能位门控 + 宿主回调，业务 UI 全在企业子应用 ----------
const residencyEnabled = computed(() => enterpriseStore.isEnabled('dataClassification'))

/** 企业「区域设置」入口的上下文；函数指纹由 EnterpriseSlot 忽略，不会误触发重挂载。 */
const regionActionCtx = (row: ModelRow): InstanceRegionActionCtx => ({
  source: 'table',
  instanceName: String(row.runtimeInstanceId || row.name),
  tags: { ...(row.tags || {}) },
  onApplyTags: (tags: Record<string, string>) => applyRegionTags(row, tags)
})

/**
 * 宿主持久化：只做「合并 tags + PUT 全量保存」，不含任何企业文案/提示。
 * 成功同步本地行数据，返回布尔供企业侧自行 toast。
 */
const applyRegionTags = async (row: ModelRow, tags: Record<string, string>): Promise<boolean> => {
  const config = toInstanceConfig(row, (row.status || 'active').toLowerCase() as 'active' | 'inactive')
  config.tags = tags
  try {
    const res = await updateServiceInstance(row.serviceType, String(row.id), config)
    if (!res.data?.success) return false
    row.tags = tags
    return true
  } catch {
    return false
  }
}

/** 企业侧完成预填/确认后刷新宿主列表。 */
const reloadAfterResidency = (): void => {
  void load()
}

const modelConfigTableRef = ref()
const { maxHeight: modelConfigMaxHeight } = useTableMaxHeight(modelConfigTableRef)

// ---------- 模型分类 ----------
const serviceTypeMap: Record<string, string> = SERVICE_TYPE_LABELS as Record<string, string>
const serviceTypeLabel = (type: string): string => {
  const key = serviceTypeMap[type]
  return key ? t(key) : type
}
const serviceOrder: string[] = COMMON_SERVICE_TYPES as string[]
const serviceTypes = ref<string[]>([])

// ---------- 实例数据 ----------
/** 已接入的模型实例（跨分类平铺，一行 = 一个实例） */
interface ModelRow {
  /** 数据库主键（后端 ServiceInstanceDTO.id），实例级端点都按它定位 */
  id: number
  /** 运行时实例 UUID（老数据可能为空），用于关联熔断运行态 */
  runtimeInstanceId?: string
  serviceType: string
  name: string
  baseUrl: string
  path: string
  weight: number
  status: string
  adapter?: string
  headers?: Record<string, string>
  tags?: Record<string, string>
  providerId?: number
  providerName?: string
  healthStatus?: string
  errorMessage?: string
  /** 实例自带熔断配置（convertToDTO 装配，无配置时为 undefined） */
  circuitBreaker?: {
    enabled: boolean
    failureThreshold: number
    timeout: number
    successThreshold: number
  }
  /** 实例自带限流配置（convertToDTO 装配，无配置时为 undefined） */
  rateLimit?: {
    enabled: boolean
    algorithm: string
    capacity: number
    rate: number
    scope: string
    key: string
    clientIpEnable: boolean
  }
  // ---- 价格 / 额度（来自 model_price，按实例名 join） ----
  priceInput: number
  priceOutput: number
  /** 缓存单价（命中缓存的 token 单独计价） */
  priceCache: number
  currency: string
  /** 计价开关：false → 该模型不参与账单计价（¥0） */
  priceEnabled: boolean
  rateLimitPerMinute: number
  hourlyRequestLimit: number
  hourlyTokenLimit: number
  dailyRequestLimit: number
  dailyTokenLimit: number
  monthlyRequestLimit: number
  monthlyTokenLimit: number
  quotaAlertThreshold: number
  // ---- 实例级开关（由实例自带配置派生） ----
  circuitBreakerEnabled: boolean
  rateLimitEnabled: boolean
}

const rows = ref<ModelRow[]>([])
const loading = ref(false)
const saveLoading = ref(false)
const savingPrice = ref<number | string>('')
const savingQuota = ref(false)

/** 模型分组列表缓存（用于「未入组」标记与删除依赖预检） */
const poolCache = ref<PoolDefinition[]>([])

/** 适配器相关数据（编辑实例表单需要） */
const adapters = ref<any[]>([])
const globalConfig = ref<any>({})
const globalAdapter = ref<string>('')

/** 供应商列表（编辑实例表单「从供应商选择」用） */
const providerOptions = ref<ModelProvider[]>([])
const fetchProviderOptions = async () => {
  try {
    const response = await getProviders()
    if (response.data?.success) {
      providerOptions.value = response.data.data || []
    }
  } catch (error) {
    console.error('获取供应商列表失败:', error)
  }
}

/** 根据 baseUrl/path 识别上游厂商名称 */
const { presets: PROVIDER_TEMPLATES } = useProviderTemplatePresets()
function providerLabelOf(row: ModelRow): string {
  const matched = matchProviderPreset(row.baseUrl || '', row.path || '', PROVIDER_TEMPLATES.value)
  if (matched) return matched.label
  const domain = extractDomain(row.baseUrl || '')
  return domain || '自定义'
}

// ---------- 搜索 / 过滤 / 分页 ----------
/** 深链带来的初始分类过滤（?serviceType=chat）：只取首挂载值，之后不干扰用户手选 */
const props = defineProps<{ initialServiceType?: string }>()

const searchQuery = ref('')
const typeFilter = ref(props.initialServiceType || '')
const statusFilter = ref('')
const pageSize = ref(10)
const currentPage = ref(1)

usePersistedState('modelConfig.search', searchQuery)
usePersistedState('modelConfig.status', statusFilter)

function debounce<T extends (...args: any[]) => void>(fn: T, wait = 300) {
  let timeout: ReturnType<typeof setTimeout> | null = null
  return (...args: Parameters<T>) => {
    if (timeout) clearTimeout(timeout)
    timeout = setTimeout(() => fn(...args), wait)
  }
}
const applySearch = debounce(() => { currentPage.value = 1 }, 350)
const handleSearchClear = () => { currentPage.value = 1 }

const filtered = computed(() => {
  const q = (searchQuery.value || '').trim().toLowerCase()
  return rows.value.filter(item => {
    if (statusFilter.value && item.status !== statusFilter.value) return false
    if (typeFilter.value && item.serviceType !== typeFilter.value) return false
    if (!q) return true
    return (
      (item.name && item.name.toLowerCase().includes(q)) ||
      (item.tags?.alias && item.tags.alias.toLowerCase().includes(q)) ||
      (item.baseUrl && item.baseUrl.toLowerCase().includes(q)) ||
      (item.providerName && item.providerName.toLowerCase().includes(q)) ||
      (providerLabelOf(item).toLowerCase().includes(q))
    )
  })
})

const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize.value)))
const paginated = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filtered.value.slice(start, start + pageSize.value)
})

watch([filtered, pageSize], () => {
  if (currentPage.value > totalPages.value) currentPage.value = totalPages.value
})
const handleSizeChange = (size: number) => { pageSize.value = size; currentPage.value = 1 }
const handlePageChange = (page: number) => { currentPage.value = page }

// ---------- 运行态（熔断 / 健康） ----------
const fetchCircuitStates = async () => { await fetchSharedCircuitStates() }
const circuitStateOf = (row: ModelRow): string | undefined => sharedCircuitStateOf(row)
const healthOf = (row: ModelRow): string => (row.healthStatus || 'UNKNOWN').toUpperCase()
const healthDotClass = (row: ModelRow) => {
  const h = healthOf(row)
  if (h === 'HEALTHY') return 'is-ok'
  if (h === 'UNHEALTHY') return 'is-off'
  return 'is-unknown'
}
const healthTitleOf = (row: ModelRow) => {
  const h = healthOf(row)
  if (h === 'HEALTHY') return t('instance.healthOk')
  if (h === 'UNHEALTHY') {
    return row.errorMessage
      ? `${t('instance.healthOff')}：${row.errorMessage}`
      : t('instance.healthOff')
  }
  return t('instance.healthUnknown')
}
const runtimeStateOf = (row: ModelRow) => {
  const cs = circuitStateOf(row)
  if (cs === 'OPEN') {
    return { label: t('instance.circuitOpen'), tagType: 'danger' as const, tip: t('instance.circuitOpenTip') }
  }
  if (cs === 'HALF_OPEN') {
    return { label: t('instance.circuitHalfOpen'), tagType: 'warning' as const, tip: t('instance.circuitHalfOpenTip') }
  }
  if (healthOf(row) === 'UNHEALTHY') {
    return {
      label: t('instance.healthBad'),
      tagType: 'danger' as const,
      tip: row.errorMessage ? `${t('instance.healthOff')}：${row.errorMessage}` : t('instance.healthOff')
    }
  }
  return { label: '', tagType: 'info' as const, tip: '' }
}

// ==================== 数据加载 ====================

/** 模型分类列表（按指定顺序） */
const fetchServiceTypes = async () => {
  try {
    const response = await getServiceTypes()
    if (response.data?.success) {
      const types: string[] = response.data.data || []
      const ordered = serviceOrder.filter(s => types.includes(s)).concat(types.filter(s => !serviceOrder.includes(s)))
      serviceTypes.value = ordered
    }
  } catch (error) {
    console.error('获取服务类型失败:', error)
    ElMessage.error(t('instance.messages.fetchTypesFailed'))
  }
}

/** 适配器列表 */
const fetchAdapters = async () => {
  try {
    const response = await getAdapters()
    if (response.data?.success) {
      adapters.value = response.data.data || []
    }
  } catch (error) {
    console.error('获取适配器列表失败:', error)
  }
}

/** 全局配置（取默认适配器） */
const fetchGlobalConfig = async () => {
  try {
    const response = await getAllConfigurations()
    if (response.data?.success) {
      globalConfig.value = response.data.data || {}
      globalAdapter.value = globalConfig.value.adapter || ''
    }
  } catch (error) {
    console.error('获取全局配置失败:', error)
  }
}

/** 模型分组列表（未入组标记 + 删除依赖预检；失败静默降级） */
const fetchPools = async () => {
  try {
    const response = await getPoolList()
    if (response.data?.success) {
      poolCache.value = response.data.data || []
    }
  } catch (error) {
    console.error('获取模型分组列表失败:', error)
  }
}

/** 空价格行（实例尚未配置价格时的默认值） */
const emptyPriceOf = (): Pick<
  ModelRow,
  | 'priceInput' | 'priceOutput' | 'priceCache' | 'currency' | 'priceEnabled'
  | 'rateLimitPerMinute' | 'hourlyRequestLimit' | 'hourlyTokenLimit'
  | 'dailyRequestLimit' | 'dailyTokenLimit' | 'monthlyRequestLimit' | 'monthlyTokenLimit'
  | 'quotaAlertThreshold'
> => ({
  priceInput: 0,
  priceOutput: 0,
  priceCache: 0,
  currency: 'CNY',
  priceEnabled: true,
  rateLimitPerMinute: 0,
  hourlyRequestLimit: 0,
  hourlyTokenLimit: 0,
  dailyRequestLimit: 0,
  dailyTokenLimit: 0,
  monthlyRequestLimit: 0,
  monthlyTokenLimit: 0,
  quotaAlertThreshold: 0.8
})

/**
 * 加载全部已接入模型（跨分类平铺）。
 *
 * <p>实例按分类并行取（GET /config/instance/{serviceType}），价格目录一次取全量
 * （GET /config/prices/catalog），再按<b>实例名</b> join —— model_price 的主键是
 * model_name（= 实例名 instance_name），BillingService 也按 token_usage.instance_name
 * 查价，join 口径与计价完全一致。</p>
 *
 * <p>熔断 / 限流不取价格目录的「代表实例」开关（同名实例跨供应商时那个口径不准），
 * 直接用实例 DTO 自带的配置（convertToDTO 已装配）。</p>
 */
const load = async () => {
  loading.value = true
  try {
    const types = serviceTypes.value.length ? serviceTypes.value : ['chat']
    const [instanceResults, priceResult, quotaResult] = await Promise.all([
      Promise.all(
        types.map(async st => {
          try {
            const res = await getServiceInstances(st)
            return res.data?.success ? (res.data.data || []) : []
          } catch (error) {
            console.error(`获取${st}模型实例失败:`, error)
            return []
          }
        })
      ),
      // 单价目录属商业能力：社区版（billing 未解锁）绝不请求 /config/prices/catalog，
      // 否则端点不存在必然 404 并刷控制台错误（方案 3.6 / DoD 8：不渲染、也不 404）。
      // enterpriseStore.billing 在 onMounted 里已 await ensureLoaded() 就绪，不会误伤企业版首屏。
      enterpriseStore.billing
        ? getPriceCatalog().catch(error => {
            console.error('获取模型价格目录失败:', error)
            return null
          })
        : Promise.resolve(null),
      getModelQuotaCatalog().catch(error => {
        console.error('获取模型额度目录失败:', error)
        return null
      })
    ])

    // 单价索引：模型名 → 单价行（商业能力，来自企业版 /api/config/prices）
    const priceIndex = new Map<string, ModelPriceCatalogItem>()
    if (priceResult?.data?.success) {
      for (const item of (priceResult.data.data || [])) {
        if (item?.modelName) priceIndex.set(item.modelName, item)
      }
    }

    // 额度索引：模型名 → 额度行（开源基础能力，来自 /api/config/quota）
    const quotaIndex = new Map<string, ModelQuotaCatalogItem>()
    if (quotaResult?.data?.success) {
      for (const item of (quotaResult.data.data || [])) {
        if (item?.modelName) quotaIndex.set(item.modelName, item)
      }
    }

    const merged: ModelRow[] = []
    instanceResults.forEach((list, idx) => {
      const st = types[idx]
      for (const item of (list || []) as any[]) {
        const name = item.name
        if (!name) continue
        const price = priceIndex.get(name)
        const quota = quotaIndex.get(name)
        merged.push({
          id: item.id,
          runtimeInstanceId: item.runtimeInstanceId,
          serviceType: item.serviceType || st,
          name,
          baseUrl: item.baseUrl || '',
          path: item.path || '',
          weight: item.weight ?? 1,
          status: (item.status || 'active').toLowerCase(),
          adapter: item.adapter,
          headers: item.headers,
          tags: item.tags,
          providerId: item.providerId,
          providerName: item.providerName,
          healthStatus: item.healthStatus,
          errorMessage: item.errorMessage,
          circuitBreaker: item.circuitBreaker,
          rateLimit: item.rateLimit,
          ...emptyPriceOf(),
          // 单价字段仅来自价格目录（商业能力）；未配价时回落 emptyPriceOf 默认值
          ...(price
            ? {
                priceInput: Number(price.priceInput ?? 0),
                priceOutput: Number(price.priceOutput ?? 0),
                priceCache: Number(price.priceCache ?? 0),
                currency: price.currency || 'CNY',
                priceEnabled: price.enabled !== false
              }
            : {}),
          // 额度与限速字段仅来自额度目录（开源基础能力）
          ...(quota
            ? {
                rateLimitPerMinute: Number(quota.rateLimitPerMinute ?? 0),
                hourlyRequestLimit: Number(quota.hourlyRequestLimit ?? 0),
                hourlyTokenLimit: Number(quota.hourlyTokenLimit ?? 0),
                dailyRequestLimit: Number(quota.dailyRequestLimit ?? 0),
                dailyTokenLimit: Number(quota.dailyTokenLimit ?? 0),
                monthlyRequestLimit: Number(quota.monthlyRequestLimit ?? 0),
                monthlyTokenLimit: Number(quota.monthlyTokenLimit ?? 0),
                quotaAlertThreshold: Number(quota.quotaAlertThreshold ?? 0.8)
              }
            : {}),
          circuitBreakerEnabled: item.circuitBreaker?.enabled === true,
          rateLimitEnabled: item.rateLimit?.enabled === true
        })
      }
    })
    rows.value = merged
  } catch (error: any) {
    ElMessage.error(error?.message || '获取模型配置列表失败')
  } finally {
    loading.value = false
  }
}

/** 刷新：实例 + 价格 + 熔断运行态 + 分组 */
const refreshAll = async () => {
  await Promise.all([load(), fetchCircuitStates(), fetchPools()])
  ElMessage.success(t('instance.messages.refreshing'))
}

// ==================== 价格 / 额度 ====================

const hasAnyLimit = (row: ModelRow): boolean =>
  row.rateLimitPerMinute > 0
  || row.hourlyRequestLimit > 0
  || row.hourlyTokenLimit > 0
  || row.dailyRequestLimit > 0
  || row.dailyTokenLimit > 0
  || row.monthlyRequestLimit > 0
  || row.monthlyTokenLimit > 0

/**
 * 保存该行的单价与计价开关。
 *
 * <p>upsert 按模型名（实例名）落库，语义是「部分更新」：null 字段 = 不修改。
 * 这里显式带上 enabled，否则计价开关的改动不会被保存。</p>
 */
const savePrice = async (row: ModelRow) => {
  savingPrice.value = row.id
  try {
    const res = await upsertModelPrice(row.name, {
      serviceType: row.serviceType,
      priceInput: row.priceInput,
      priceOutput: row.priceOutput,
      priceCache: row.priceCache,
      currency: row.currency || 'CNY',
      enabled: row.priceEnabled
    })
    if (res.data?.success) {
      ElMessage.success(t('modelConfig.priceSaved'))
      // 成功不整表重载：页面上的值已是最新，重载只会让整张表闪一下
    } else {
      ElMessage.error(res.data?.message || '保存失败')
      await load()
    }
  } catch (error: any) {
    ElMessage.error(error?.message || '保存失败')
    await load()
  } finally {
    savingPrice.value = ''
  }
}

/**
 * 单价改完即存。
 *
 * <p>el-input-number 的 change 事件在「值确实变了并失焦/回车」时触发一次，
 * 所以既不会在用户输入过程中误存，也不会漏存。</p>
 */
const onPriceChanged = async (row: ModelRow) => {
  if (savingPrice.value === row.id) return
  await savePrice(row)
}

// ---------- 额度与限速弹窗 ----------
const quotaVisible = ref(false)
const quotaForm = ref<ModelRow | null>(null)

const openQuotaDialog = (row: ModelRow) => {
  quotaForm.value = { ...row }
  quotaVisible.value = true
}

/**
 * 保存额度与限速（只提交额度相关字段，不动单价与计价开关）。
 * 后端 upsert 语义为「null = 不修改」，故这里不传价格字段。
 * 额度属开源基础能力，落库到 /api/config/quota（ModelQuotaController）。
 */
const saveQuota = async () => {
  if (!quotaForm.value) return
  savingQuota.value = true
  try {
    const res = await upsertModelQuota(quotaForm.value.name, {
      rateLimitPerMinute: quotaForm.value.rateLimitPerMinute,
      hourlyRequestLimit: quotaForm.value.hourlyRequestLimit,
      hourlyTokenLimit: quotaForm.value.hourlyTokenLimit,
      dailyRequestLimit: quotaForm.value.dailyRequestLimit,
      dailyTokenLimit: quotaForm.value.dailyTokenLimit,
      monthlyRequestLimit: quotaForm.value.monthlyRequestLimit,
      monthlyTokenLimit: quotaForm.value.monthlyTokenLimit,
      quotaAlertThreshold: quotaForm.value.quotaAlertThreshold
    })
    if (res.data?.success) {
      ElMessage.success(t('modelConfig.quotaSaved'))
      quotaVisible.value = false
      await load()
    } else {
      ElMessage.error(res.data?.message || '保存失败')
    }
  } catch (error: any) {
    ElMessage.error(error?.message || '保存失败')
  } finally {
    savingQuota.value = false
  }
}

// ==================== 熔断 / 限流（实例级） ====================

const cbVisible = ref(false)
const cbTitle = ref('')
const cbInitialData = ref<{ circuitBreaker: any }>({ circuitBreaker: { enabled: false } })
/** 当前编辑熔断/限流的实例（弹窗组件的 save 事件只回表单内容，不带实例标识，须外部记住） */
const currentEditInstanceId = ref('')
const currentEditServiceType = ref('')
const rlVisible = ref(false)
const rlTitle = ref('')
const rlInitialData = ref<{ rateLimit: any }>({ rateLimit: { enabled: false } })

const openCircuitBreakerConfig = (row: ModelRow) => {
  currentEditInstanceId.value = String(row.id)
  currentEditServiceType.value = row.serviceType
  cbTitle.value = `${t('circuitBreakerConfig.title')} · ${row.name}`
  const cb = row.circuitBreaker
  cbInitialData.value = {
    circuitBreaker: {
      enabled: cb?.enabled || false,
      failureThreshold: cb?.failureThreshold || 5,
      timeout: cb?.timeout || 60000,
      successThreshold: cb?.successThreshold || 2
    }
  }
  cbVisible.value = true
}

/**
 * 保存熔断配置。
 *
 * <p>注意：CircuitBreakerConfig 组件在「关闭」状态下 emit 的是 {@code circuitBreaker: null}
 * （不是 enabled=false 的对象），因此这里用「取不到配置 → 视为关闭并按默认值提交」处理，
 * 否则用户关掉熔断开关后保存会静默变成「保持原样」。</p>
 */
const handleCircuitBreakerSave = async (configData: any) => {
  if (!currentEditInstanceId.value) return
  const src = configData?.circuitBreaker
  const config: CircuitBreakerConfigType = {
    enabled: src?.enabled === true,
    failureThreshold: src?.failureThreshold || 5,
    timeout: src?.timeout || 60000,
    successThreshold: src?.successThreshold || 2
  }
  try {
    const res = await saveCircuitBreakerConfig(
      currentEditServiceType.value,
      currentEditInstanceId.value,
      config
    )
    if (res.data?.success) {
      ElMessage.success(t('instance.messages.cbSaveSuccess'))
      cbVisible.value = false
      await load()
    } else {
      ElMessage.error(res.data?.message || '保存失败')
    }
  } catch (error: any) {
    ElMessage.error(error?.message || '保存失败')
  }
}

const openRateLimitConfig = (row: ModelRow) => {
  currentEditInstanceId.value = String(row.id)
  currentEditServiceType.value = row.serviceType
  rlTitle.value = `${t('rateLimitConfig.title')} · ${row.name}`
  const rl = row.rateLimit
  rlInitialData.value = {
    rateLimit: {
      enabled: rl?.enabled || false,
      algorithm: rl?.algorithm || 'token-bucket',
      capacity: rl?.capacity ?? 100,
      rate: rl?.rate ?? 10,
      scope: rl?.scope || 'instance',
      key: rl?.key || '',
      clientIpEnable: rl?.clientIpEnable || false
    }
  }
  rlVisible.value = true
}

/** 保存限流配置（同样处理「关闭 → 组件 emit null」的情况） */
const handleRateLimitSave = async (configData: any) => {
  if (!currentEditInstanceId.value) return
  const src = configData?.rateLimit
  const config: RateLimitConfigType = {
    enabled: src?.enabled === true,
    algorithm: src?.algorithm || 'token-bucket',
    capacity: src?.capacity ?? 100,
    rate: src?.rate ?? 10,
    scope: src?.scope || 'instance',
    key: src?.key || '',
    clientIpEnable: src?.clientIpEnable || false
  }
  try {
    const res = await saveRateLimitConfig(
      currentEditServiceType.value,
      currentEditInstanceId.value,
      config
    )
    if (res.data?.success) {
      ElMessage.success(t('instance.messages.rateLimitSaveSuccess'))
      rlVisible.value = false
      await load()
    } else {
      ElMessage.error(res.data?.message || '保存失败')
    }
  } catch (error: any) {
    ElMessage.error(error?.message || '保存失败')
  }
}

/** 熔断/限流快速启停：只提交 enabled，后端为部分更新，不会清掉已有阈值与算法参数 */
const cbToggling = ref<number | string>('')
const rlToggling = ref<number | string>('')

const toggleCircuitBreaker = async (row: ModelRow) => {
  const target = !!row.circuitBreakerEnabled
  cbToggling.value = row.id
  try {
    const res = await saveCircuitBreakerConfig(row.serviceType, String(row.id), { enabled: target })
    if (res.data?.success) {
      ElMessage.success(target ? t('modelConfig.circuitEnabled') : t('modelConfig.circuitDisabled'))
    } else {
      ElMessage.error(res.data?.message || '操作失败')
      row.circuitBreakerEnabled = !target
    }
  } catch (error: any) {
    ElMessage.error(error?.message || '操作失败')
    row.circuitBreakerEnabled = !target
  } finally {
    cbToggling.value = ''
  }
}

const toggleRateLimit = async (row: ModelRow) => {
  const target = !!row.rateLimitEnabled
  rlToggling.value = row.id
  try {
    const res = await saveRateLimitConfig(row.serviceType, String(row.id), { enabled: target })
    if (res.data?.success) {
      ElMessage.success(target ? t('modelConfig.rateLimitEnabled') : t('modelConfig.rateLimitDisabled'))
    } else {
      ElMessage.error(res.data?.message || '操作失败')
      row.rateLimitEnabled = !target
    }
  } catch (error: any) {
    ElMessage.error(error?.message || '操作失败')
    row.rateLimitEnabled = !target
  } finally {
    rlToggling.value = ''
  }
}

// ==================== 实例编辑 / 删除 ====================

const dialogVisible = ref(false)
const isEdit = ref(true)
const instanceFormRef = ref<InstanceType<typeof UpstreamInstanceForm>>()
const editingId = ref<string>('')
const editingRow = ref<ModelRow | null>(null)

const handleEdit = (row: ModelRow) => {
  isEdit.value = true
  editingId.value = String(row.id)
  editingRow.value = row
  dialogVisible.value = true
}

/** 对话框打开动画结束后回填（此时公共表单已稳定挂载） */
const onDialogOpened = () => {
  if (isEdit.value && editingRow.value) {
    instanceFormRef.value?.loadInstance(editingRow.value)
  }
}

const handleDialogClose = () => { dialogVisible.value = false }

const handleSave = async () => {
  const payload = instanceFormRef.value?.buildPayload()
  if (!payload) return
  saveLoading.value = true
  try {
    const currentType = editingRow.value?.serviceType || ''
    const targetType = payload.serviceType || currentType
    const instanceData: InstanceConfig = {
      name: payload.name,
      baseUrl: payload.baseUrl,
      path: payload.path || '',
      weight: payload.weight,
      status: payload.status,
      adapter: payload.adapter || undefined,
      headers: payload.headers || {},
      tags: payload.tags || {},
      providerId: payload.providerId,
      ...(targetType !== currentType ? { serviceType: targetType } : {})
    }
    const res = await updateServiceInstance(currentType, editingId.value, instanceData)
    if (res.data?.success) {
      ElMessage.success(t('instance.messages.editSuccess'))
      dialogVisible.value = false
      await Promise.all([load(), fetchCircuitStates()])
    } else {
      ElMessage.error(res.data?.message || t('instance.messages.editFailed'))
    }
  } catch (error) {
    console.error('保存实例失败:', error)
    ElMessage.error(t('instance.messages.saveFailed'))
  } finally {
    saveLoading.value = false
  }
}

/** 引用了该实例的模型分组（与后端 PoolSelector 口径一致） */
const findPoolsReferencing = (row: ModelRow): string[] => {
  const candidates = new Set<string>()
  if (row.runtimeInstanceId) candidates.add(row.runtimeInstanceId)
  if (row.name) candidates.add(row.name)
  if (!candidates.size) return []
  try {
    return (poolCache.value || [])
      .filter(pool => (pool.members || []).some(m => m.instanceId && candidates.has(m.instanceId)))
      .map(pool => pool.poolName)
  } catch (error) {
    console.error('检查模型分组引用失败:', error)
    return []
  }
}

/** 该实例是否「未入组」（无对外调用入口） */
const isUngrouped = (row: ModelRow): boolean => {
  if (!poolCache.value.length) return false
  return findPoolsReferencing(row).length === 0
}

/** 删除前依赖预检 */
const { precheckDeletion } = useDependencyGuard()

const handleDelete = async (row: ModelRow) => {
  const pre = await precheckDeletion('instance', row.id)
  if (pre.blocked) return

  const confirmed = await confirmDanger({
    title: t('instance.confirmTitle'),
    target: row.name,
    impact: t('instance.deleteImpactNone'),
    remedy: t('instance.deleteRemedy'),
    requireType: row.name,
    confirmText: t('instance.confirm'),
    cancelText: t('instance.cancel'),
    type: 'warning'
  })
  if (!confirmed) {
    ElMessage.info(t('instance.messages.deleteCancelled'))
    return
  }

  try {
    const res = await deleteServiceInstance(row.serviceType, String(row.id))
    if (res.data?.success) {
      clearCache(row.serviceType)
      ElMessage.success(t('instance.messages.deleteSuccess'))
      await Promise.all([load(), fetchCircuitStates()])
    } else {
      ElMessage.error(res.data?.message || t('instance.messages.deleteFailed'))
    }
  } catch (error) {
    console.error('删除实例失败:', error)
    ElMessage.error(t('instance.messages.deleteInstanceFailed'))
  }
}

// ==================== 批量操作 ====================

const selectedRows = ref<ModelRow[]>([])
const onSelectionChange = (list: ModelRow[]) => { selectedRows.value = list || [] }
const clearSelection = () => { selectedRows.value = [] }

/**
 * 由列表行还原为可提交的实例配置（启停是全量 PUT，字段必须完整）。
 * 后端语义为「字段缺省 = 不变」，undefined 的字段一律不下发。
 */
const toInstanceConfig = (row: ModelRow, status: 'active' | 'inactive'): InstanceConfig => ({
  name: row.name,
  baseUrl: row.baseUrl,
  path: row.path || '',
  weight: row.weight ?? 1,
  status,
  adapter: row.adapter || undefined,
  headers: row.headers && Object.keys(row.headers).length > 0 ? row.headers : undefined,
  tags: row.tags && Object.keys(row.tags).length > 0 ? row.tags : undefined,
  providerId: row.providerId
})

/** 批量启停：逐条 PUT（每条用自己的分类），汇总成功/失败 */
const batchToggle = async (enable: boolean) => {
  const list = [...selectedRows.value]
  if (list.length === 0) return
  const succeeded: string[] = []
  const failed: string[] = []
  for (const row of list) {
    try {
      const res = await updateServiceInstance(
        row.serviceType,
        String(row.id),
        toInstanceConfig(row, enable ? 'active' : 'inactive')
      )
      if (res.data?.success) succeeded.push(row.name)
      else failed.push(row.name)
    } catch (error) {
      console.error(`批量${enable ? '启用' : '停用'}模型失败 ${row.name}:`, error)
      failed.push(row.name)
    }
  }
  list.forEach(r => clearCache(r.serviceType))
  await load()
  clearSelection()
  const action = t(enable ? 'instance.batch.enable' : 'instance.batch.disable')
  if (failed.length === 0) {
    ElMessage.success(t('instance.batch.resultAllOk', { count: succeeded.length, action }))
  } else {
    ElMessage.warning(t('instance.batch.resultPartial', { ok: succeeded.length, fail: failed.length, action }))
  }
}

const handleBatchEnable = () => batchToggle(true)
const handleBatchDisable = () => batchToggle(false)

/** 批量删除：任一实例仍被引用即整批中止 */
const handleBatchDelete = async () => {
  const list = [...selectedRows.value]
  if (list.length === 0) return

  const blockedNames: string[] = []
  for (const row of list) {
    const pre = await precheckDeletion('instance', row.id, { silent: true })
    if (pre.blocked) blockedNames.push(row.name)
  }
  if (blockedNames.length > 0) {
    ElMessage.error(t('dependency.batchBlocked', { names: blockedNames.join('、') }))
    return
  }

  const confirmed = await confirmDanger({
    title: t('instance.batch.deleteTitle'),
    target: t('instance.batch.deleteTarget', { count: list.length }),
    impact: t('instance.batch.deleteImpactNone'),
    remedy: t('instance.batch.deleteRemedy'),
    requireType: String(list.length),
    confirmText: t('instance.batch.confirmDelete'),
    cancelText: t('instance.cancel'),
    type: 'warning'
  })
  if (!confirmed) return

  const succeeded: string[] = []
  const failed: string[] = []
  for (const row of list) {
    try {
      const res = await deleteServiceInstance(row.serviceType, String(row.id))
      if (res.data?.success) succeeded.push(row.name)
      else failed.push(row.name)
    } catch (error) {
      console.error(`批量删除模型失败 ${row.name}:`, error)
      failed.push(row.name)
    }
  }
  list.forEach(r => clearCache(r.serviceType))
  await Promise.all([load(), fetchCircuitStates()])
  clearSelection()
  if (failed.length === 0) {
    ElMessage.success(t('instance.batch.deleteSuccess', { count: succeeded.length }))
  } else {
    ElMessage.warning(t('instance.batch.deletePartial', { ok: succeeded.length, fail: failed.length }))
  }
}

// ==================== 导出 ====================

/** 导出当前筛选结果（全量，不受分页限制），内容与页面所见一致 */
const handleExport = () => {
  const list = filtered.value
  if (list.length === 0) {
    ElMessage.info(t('instance.exportEmpty'))
    return
  }
  exportCsv(
    [
      { header: t('instance.columnModelName'), value: r => r.tags?.alias || r.name },
      { header: t('instance.modelPrefix').replace('：', ''), value: r => r.name },
      { header: t('instance.columnVendorAddress'), value: r => `${providerLabelOf(r)} ${r.baseUrl}${r.path || ''}`.trim() },
      { header: t('instance.serviceTypeLabel'), value: r => serviceTypeLabel(r.serviceType) },
      { header: t('instance.status'), value: r => (r.status === 'active' ? t('instance.enabled') : t('instance.disabled')) },
      { header: t('instance.exportColumnHealth'), value: r => healthOf(r) },
      { header: t('instance.exportColumnCircuit'), value: r => circuitStateOf(r) || 'CLOSED' },
      // 单价 / 计价开关仅商业版（billing 功能位解锁）导出；单价列带 ¥/M 单位，避免导出后口径不明
      ...(enterpriseStore.billing
        ? [
            { header: t('modelConfig.priceInputExport'), value: (r: ModelRow) => r.priceInput },
            { header: t('modelConfig.priceOutputExport'), value: (r: ModelRow) => r.priceOutput },
            { header: t('modelConfig.priceCacheExport'), value: (r: ModelRow) => r.priceCache },
            { header: t('modelConfig.columnBilling'), value: (r: ModelRow) => (r.priceEnabled ? 'Y' : 'N') }
          ]
        : []),
      { header: t('circuitBreakerConfig.title'), value: r => (r.circuitBreakerEnabled ? 'Y' : 'N') },
      { header: t('rateLimitConfig.title'), value: r => (r.rateLimitEnabled ? 'Y' : 'N') },
      { header: t('instance.weight'), value: r => r.weight ?? '' }
    ],
    list,
    datedFilename('model-config')
  )
  ElMessage.success(t('instance.exportSuccess', { count: list.length }))
}

// ==================== 初始化 ====================
onMounted(async () => {
  // 探测配额账本开关 → 决定额度配置弹窗里每月字段是否禁用
  void loadQuotaLedgerState()
  // 探测企业功能位（billing 决定是否显示「价格」tab / 是否请求价格目录）。
  // 必须 await：load() 依赖 billing 决定是否取价，fire-and-forget 会让企业版首屏
  // 拿到空单价索引、社区版误发 404 请求。ensureLoaded() 内部已 catch 降级，不会阻断页面。
  await enterpriseStore.ensureLoaded()
  await fetchServiceTypes()
  await Promise.all([
    load(),
    fetchAdapters(),
    fetchGlobalConfig(),
    fetchProviderOptions(),
    fetchCircuitStates(),
    fetchPools()
  ])
})
</script>

<style scoped>
.search-input {
  width: 320px;
  min-width: 200px;
}

.filter-select {
  width: 140px;
  min-width: 120px;
}

.refresh-button {
  color: var(--ja-text-secondary);
}

.readonly-tip {
  margin-bottom: 10px;
}

/* 工具栏（搜索/筛选）紧凑化：贴近 tab 栏，为表格腾出高度 */
:deep(.page-toolbar) {
  margin-bottom: 8px;
}

.model-config-table :deep(.el-table__cell) {
  font-size: 14px;
  padding: 10px;
}

.model-config-table :deep(.el-table__header th) {
  font-size: 13px;
  font-weight: 600;
  color: var(--ja-text-primary);
  background-color: var(--el-fill-color-light);
}

.cell-title {
  font-weight: 600;
  color: var(--ja-text-primary);
}

.cell-sub {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-top: 2px;
}

.provider-tag {
  margin-left: 6px;
  font-weight: 400;
}

.ungrouped-tag {
  margin-top: 4px;
  font-weight: 400;
}

.status-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  flex-wrap: wrap;
}

.table-tag {
  font-size: 13px;
  padding: 6px 10px;
}

.health-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.health-dot.is-ok {
  background: var(--ja-dot-ok, #67c23a);
}
.health-dot.is-off {
  background: var(--ja-dot-off, #909399);
}
.health-dot.is-unknown {
  background: transparent;
  border: 1px solid var(--ja-text-placeholder, #c0c4cc);
}

.price-col-head {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.price-col-unit {
  font-size: 11px;
  font-weight: 400;
  color: var(--ja-text-secondary, #909399);
}

.price-input {
  width: 118px;
}

.limit-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 6px;
}

.limit-chip {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--el-fill-color-light);
  color: var(--ja-text-regular);
}

.col-help {
  color: var(--ja-text-placeholder);
  vertical-align: middle;
  margin-left: 2px;
}

.switch-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 4px;
  justify-content: center;
}

.batch-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  padding: 4px 10px;
  border-radius: 6px;
  background: var(--ja-bg-subtle, rgb(0 0 0 / 3%));
}
.batch-bar__count {
  font-size: 13px;
  color: var(--ja-text-secondary);
  white-space: nowrap;
}

.empty-wrap {
  padding: 30px 0;
  display: flex;
  justify-content: center;
  align-items: center;
}

.table-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--ja-border-lighter);
}

.footer-info {
  color: var(--ja-text-secondary);
  font-size: 13px;
}

.section-tip {
  margin-bottom: 10px;
}

.quota-group-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ja-text-primary);
  margin: 14px 0 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--ja-border-lighter);
}

.quota-group-title:first-child {
  margin-top: 0;
}

.field-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.field-hint--warn {
  margin-left: 0;
  color: var(--el-color-warning);
}

.instance-dialog :deep(.el-dialog__header) {
  background-color: var(--el-fill-color-light);
  border-bottom: 1px solid var(--ja-border-lighter);
  padding: 14px 20px;
}

.instance-dialog :deep(.el-dialog__title) {
  font-weight: 700;
  color: var(--ja-text-primary);
  font-size: 18px;
}

.instance-dialog :deep(.el-dialog__body) {
  padding: 20px;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 10px 20px;
}

/* ---- 模型名列里的模型分类小标签 ---- */
.type-tag {
  margin-left: 6px;
  font-weight: 400;
  vertical-align: middle;
}

/* ---- 熔断 / 限流 竖排两行（合并成一列后仍要一眼看到两个开关） ---- */
.switch-stack {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.switch-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

/* ---- 单价：输入 / 输出 / 缓存 竖排 ---- */
.price-stack {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}

.price-row {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}

.price-label {
  font-size: 12px;
  color: var(--ja-text-secondary);
  /* 「输入」「输出」「缓存」均为两字，给足宽度避免被挤成单字或换行 */
  min-width: 30px;
  white-space: nowrap;
  text-align: right;
}

/* ---- 额度列内的「配置」入口：独占一行，别和额度 chip 挤在一起 ---- */
.quota-config-btn {
  flex-basis: 100%;
  margin-top: 2px;
  margin-left: 0 !important;
  justify-content: flex-start !important;
}
</style>
