<template>
  <PageSkeleton :title="t('apiKeys.title')" help-link="/docs?path=team/all-keys">
    <!-- 操作区：嵌入模式下 PageSkeleton 页头整体隐藏，#actions 插槽不会渲染，
         故「创建密钥」按钮改为内容顶部自渲染 -->
    <div class="page-actions-row">
      <el-button v-if="canWrite" icon="Plus" type="primary" @click="handleCreateApiKey">{{ t('apiKeys.createApiKey') }}</el-button>
    </div>

    <!-- 统计卡片（原型口径：总数 / 本月活跃 / 即将到期 / 发了没人用） -->
    <el-row :gutter="20" class="stats-row">
      <el-col :span="6">
        <StatCard :icon="Key" :label="t('apiKeys.stats.total')" :value="keyStats.total" tone="primary" />
      </el-col>
      <el-col :span="6">
        <StatCard :icon="CircleCheck" :label="t('apiKeys.stats.activeThisMonth')" :value="keyStats.activeThisMonth" tone="success" />
      </el-col>
      <el-col :span="6">
        <StatCard :icon="Warning" :label="t('apiKeys.stats.expiringSoon')" :value="keyStats.expiringSoon" tone="warning" />
      </el-col>
      <el-col :span="6">
        <StatCard :icon="CircleClose" :label="t('apiKeys.stats.unused')" :value="keyStats.unused" tone="danger" />
      </el-col>
    </el-row>

    <!-- 主卡片 -->
    <el-card class="main-card" shadow="hover">
      <template #header>
        <div class="main-card__header">
          <span class="main-card__title">{{ t('apiKeys.listTitle') }}</span>
          <span class="main-card__hint">{{ t('apiKeys.listHint') }}</span>
        </div>
      </template>

      <div class="main-card__toolbar">
        <el-input
          v-model="searchKey"
          :placeholder="t('apiKeys.searchPlaceholder')"
          clearable
          size="default"
          class="key-search"
          @input="onSearchInput"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select
          v-model="statusFilter"
          class="key-status-filter"
          size="default"
          @change="onSearchInput"
        >
          <el-option
            v-for="o in STATUS_FILTER_OPTIONS"
            :key="o.value"
            :label="t(o.labelKey)"
            :value="o.value"
          />
        </el-select>
        <span class="main-card__count">{{ t('apiKeys.listCount', { count: pagination.total }) }}</span>
        <!-- 创建入口统一收口到卡片顶部「创建密钥」按钮，工具栏不再重复放置 -->
        <!-- 批量操作条：选中后浮现，避免常驻按钮造成视觉噪音 -->
        <div v-if="canWrite && selectedKeys.length > 0" class="batch-bar">
          <span class="batch-bar__count">{{ t('apiKeys.batch.selected', { count: selectedKeys.length }) }}</span>
          <el-button size="small" type="success" plain @click="handleBatchEnable">
            {{ t('apiKeys.batch.enable') }}
          </el-button>
          <el-button size="small" type="warning" plain @click="handleBatchDisable">
            {{ t('apiKeys.batch.disable') }}
          </el-button>
          <el-button size="small" type="danger" plain @click="handleBatchDelete">
            {{ t('apiKeys.batch.delete') }}
          </el-button>
          <el-button size="small" link @click="clearSelection">{{ t('apiKeys.batch.clear') }}</el-button>
        </div>
      </div>

      <div class="table-wrapper">
        <el-table
          ref="keysTableRef"
          v-loading="loading"
          :data="pagedApiKeys"
          :max-height="keysMaxHeight"
          border
          stripe
          style="width: 100%"
          row-key="keyId"
          @selection-change="onSelectionChange"
        >
        <el-table-column v-if="canWrite" type="selection" width="46" :reserve-selection="false" />
        <el-table-column :label="t('apiKeys.columns.description')" min-width="200" show-overflow-tooltip>
          <template #default="scope">
            <div class="key-desc-cell">
              <span class="key-name">{{ scope.row.description || '-' }}</span>
              <span class="key-desc-cell__id">
                {{ scope.row.keyId }}
                <el-button
                  link
                  type="primary"
                  size="small"
                  :icon="'CopyDocument'"
                  @click.stop="copyKeyId(scope.row.keyId)"
                />
              </span>
            </div>
          </template>
        </el-table-column>
        <!-- 数据密级（#9 数据分级出境）：商业版能力，未解锁时整列隐藏（社区版零暴露） -->
        <el-table-column v-if="classificationEnabled" :label="t('apiKeys.columns.classification')" width="120" align="center">
          <template #default="scope">
            <el-tag size="small" :type="classificationTagType(scope.row.classification)" effect="plain">
              {{ scope.row.classification || 'PUBLIC' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('apiKeys.columns.holder')" min-width="110" show-overflow-tooltip>
          <template #default="scope">
            <span class="key-holder">{{ scope.row.holder || scope.row.createdBy || t('apiKeys.unknownHolder') }}</span>
          </template>
        </el-table-column>
        <!-- 归属租户（仅多租户视图展示）：单租户恒 default，无需占用列宽 -->
        <el-table-column v-if="multiTenant" :label="t('apiKeys.columns.tenant')" min-width="110" show-overflow-tooltip>
          <template #default="scope">
            <el-tag size="small" type="info" effect="plain">{{ scope.row.tenantId || 'default' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('apiKeys.columns.allowedGroups')" min-width="200">
          <template #default="scope">
            <div class="group-chips">
              <el-tag
                v-for="g in (scope.row.allowedGroups || [])"
                :key="g"
                size="small"
                type="primary"
                effect="plain"
              >{{ groupDisplay(g) }}</el-tag>
              <span v-if="!scope.row.allowedGroups || scope.row.allowedGroups.length === 0" class="cell-sub">不限制</span>
            </div>
          </template>
        </el-table-column>
        <!-- 每月额度：token 维度常显；可用模型配了价格时并列显示金额估算 -->
        <el-table-column :label="t('apiKeys.columns.monthlyQuota')" min-width="150" align="right">
          <template #default="scope">
            <div class="dual-cell">
              <span v-if="!scope.row.monthlyTokenLimit" class="dual-cell__main">{{ t('apiKeys.quotaUnlimited') }}</span>
              <template v-else>
                <span class="dual-cell__main">{{ formatTokens(scope.row.monthlyTokenLimit) }} tokens</span>
                <span v-if="keyMixedPrice(scope.row) !== null" class="dual-cell__sub">
                  ≈ {{ formatAmount(tokenToAmount(scope.row.monthlyTokenLimit, keyMixedPrice(scope.row))) }}
                </span>
              </template>
            </div>
          </template>
        </el-table-column>
        <!-- 本月已用：账本 KEY 桶（自然月） -->
        <el-table-column :label="t('apiKeys.columns.monthlyUsed')" min-width="150" align="right">
          <template #default="scope">
            <div class="dual-cell">
              <span v-if="monthlyTokenUsed(scope.row.keyId) === null" class="dual-cell__main">-</span>
              <template v-else>
                <span class="dual-cell__main">{{ formatTokens(monthlyTokenUsed(scope.row.keyId)) }} tokens</span>
                <span v-if="keyMixedPrice(scope.row) !== null" class="dual-cell__sub">
                  ≈ {{ formatAmount(tokenToAmount(monthlyTokenUsed(scope.row.keyId), keyMixedPrice(scope.row))) }}
                </span>
              </template>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('apiKeys.columns.expiresAt')" width="130">
          <template #default="scope">
            <span :class="{ 'expired-text': scope.row.expired }">
              {{ scope.row.expiresAt ? formatDateTime(scope.row.expiresAt, { dateOnly: true }) : t('apiKeys.neverExpires') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('apiKeys.columns.status')" width="120" align="center" fixed="right">
          <template #default="scope">
            <el-tag :type="keyStatus(scope.row).type" size="small" effect="plain">
              {{ keyStatus(scope.row).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column fixed="right" :label="t('apiKeys.columns.actions')" width="300">
          <template #default="scope">
            <div class="action-buttons">
              <el-button link type="primary" size="small" @click="openTroubleshoot(scope.row)">{{ t('apiKeys.actions.troubleshoot') }}</el-button>
              <el-button
                v-if="canWrite"
                link
                type="primary"
                size="small"
                @click="handleEdit(scope.row)"
              >{{ t('apiKeys.actions.edit') }}</el-button>
              <el-button
                v-if="canWrite"
                link
                type="primary"
                size="small"
                @click="openQuotaDrawer(scope.row)"
              >{{ t('apiKeys.quota') }}</el-button>
              <el-button
                v-if="canWrite && scope.row.enabled && (!scope.row.totalRequests || scope.row.totalRequests === 0)"
                link
                type="danger"
                size="small"
                @click="openRecycle(scope.row)"
              >{{ t('apiKeys.actions.recycle') }}</el-button>
              <el-dropdown v-if="canWrite" trigger="click" @command="onRowCommand($event, scope.row)">
                <el-button link type="primary" size="small">{{ t('apiKeys.actions.more') }}</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="advanced">{{ t('apiKeys.actions.advancedSettings') }}</el-dropdown-item>
                    <el-dropdown-item command="detail">{{ t('apiKeys.actions.detail') }}</el-dropdown-item>
                    <el-dropdown-item command="delete">{{ t('apiKeys.actions.delete') }}</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <!-- 分页组件 -->
      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.currentPage"
          v-model:page-size="pagination.pageSize"
          :page-sizes="pagination.pageSizes"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
      </div>
    </el-card>

    <!-- 调整额度（原型 modal5 形态：token + 金额双维度） -->
    <el-dialog
      v-model="quotaDrawerVisible"
      :title="t('apiKeys.quotaDialogTitle', { holder: quotaDrawer.holder })"
      width="min(480px, 92vw)"
      append-to-body
    >
      <div class="quota-dialog__sub">{{ quotaDrawer.keyId }} · {{ quotaDrawer.description || '-' }}</div>

      <!-- P0-A 方案 B：账本关闭时，「每月 / 每小时」类字段不会真的拦截请求 -->
      <el-alert
        v-if="!multiWindowEnforced"
        type="warning"
        :closable="false"
        show-icon
        class="quota-inert-alert"
        :title="t('apiKeys.quotaForm.ledgerOffTitle')"
        :description="t('apiKeys.quotaForm.ledgerOffDesc')"
      />

      <!-- 概览：当前已用 / 本月额度 -->
      <div class="quota-overview">
        <div class="quota-overview__row">
          <span class="quota-overview__k">{{ t('apiKeys.quotaForm.usedThisMonth') }}</span>
          <span class="quota-overview__v">
            <template v-if="billingEnabled && quotaDrawerPrice !== null">
              {{ formatAmount(tokenToAmount(monthlyTokenUsed(quotaDrawer.keyId) || 0, quotaDrawerPrice)) }}
            </template>
            <template v-else>—</template>
            <span class="quota-overview__tokens">{{ formatTokens(monthlyTokenUsed(quotaDrawer.keyId) || 0) }} tokens</span>
          </span>
        </div>
        <div class="quota-overview__row">
          <span class="quota-overview__k">{{ t('apiKeys.quotaForm.monthlyQuota') }}</span>
          <span class="quota-overview__v">
            <template v-if="billingEnabled && quotaDrawerPrice !== null">
              {{ formatAmount(tokenToAmount(quotaDrawer.form.monthlyTokenLimit, quotaDrawerPrice)) }}
            </template>
            <template v-else>—</template>
            <span class="quota-overview__tokens">{{ formatTokens(quotaDrawer.form.monthlyTokenLimit) }} tokens</span>
          </span>
        </div>
        <div v-if="quotaUsedPercent !== null" class="quota-overview__row">
          <span class="quota-overview__k">{{ t('apiKeys.quotaForm.usedPercent') }}</span>
          <span class="quota-overview__v quota-overview__v--warn">{{ quotaUsedPercent }}%</span>
        </div>
      </div>

      <!-- 每月额度：账本开启时 token 上限始终可编辑、金额按「是否配价」决定；
           账本关闭时两者禁用（token/金额字段不会被真实拦截，仅为记录） -->
      <div class="quota-field" :class="{ 'is-inert': !multiWindowEnforced }">
        <div class="quota-field__label">{{ t('apiKeys.quotaForm.monthlyTokenLimit') }}</div>
        <el-input-number
          v-model="quotaDrawer.form.monthlyTokenLimit"
          :min="0"
          :step="10000"
          :disabled="!multiWindowEnforced"
          style="width: 100%"
          @change="onQuotaTokenChange"
        />
        <div v-if="!multiWindowEnforced" class="quota-field__hint quota-field__hint--warn">
          {{ t('apiKeys.quotaForm.inertFieldHint') }}
        </div>
      </div>
      <!-- 金额维度仅企业版（billing 功能位）可见；社区版整块隐藏，只留 token 维度 -->
      <div v-if="billingEnabled" class="quota-field" :class="{ 'is-inert': !multiWindowEnforced }">
        <div class="quota-field__label">{{ t('apiKeys.quotaForm.monthlyAmountLimit') }}</div>
        <el-input-number
          v-model="quotaDrawerAmount"
          :min="0"
          :step="100"
          :precision="2"
          :disabled="quotaDrawerPrice === null || !multiWindowEnforced"
          :placeholder="quotaDrawerPrice === null ? '—' : ''"
          style="width: 100%"
          @change="onQuotaAmountChange"
        />
        <div v-if="quotaDrawerPrice === null && multiWindowEnforced" class="quota-field__hint">
          {{ t('apiKeys.quotaForm.amountUnavailableHint') }}
        </div>
        <div v-else-if="multiWindowEnforced" class="quota-field__hint">{{ t('apiKeys.quotaForm.amountEstimateHint') }}</div>
      </div>

      <!-- 高级限制（一般不用动） -->
      <el-collapse class="quota-advanced">
        <el-collapse-item :title="t('apiKeys.quotaForm.advancedLimits')" name="quota-advanced">
          <div class="quota-grid">
            <div v-if="isQuotaFieldVisible('dayRequests')" class="quota-grid__item">
              <div class="quota-field__label">{{ t('apiKeys.quotaForm.dailyRequestLimit') }}</div>
              <el-input-number v-model="quotaDrawer.form.dailyRequestLimit" :min="0" :step="100" style="width: 100%" />
            </div>
            <div v-if="isQuotaFieldVisible('hourRequests')" class="quota-grid__item">
              <div class="quota-field__label">{{ t('apiKeys.quotaForm.hourlyRequestLimit') }}</div>
              <el-input-number v-model="quotaDrawer.form.hourlyRequestLimit" :min="0" :step="50" :disabled="!multiWindowEnforced" style="width: 100%" />
            </div>
            <div v-if="isQuotaFieldVisible('dayRequests')" class="quota-grid__item">
              <div class="quota-field__label">{{ t('apiKeys.quotaForm.dailyTokenLimit') }}</div>
              <el-input-number v-model="quotaDrawer.form.dailyTokenLimit" :min="0" :step="1000" style="width: 100%" />
            </div>
            <div v-if="isQuotaFieldVisible('hourRequests')" class="quota-grid__item">
              <div class="quota-field__label">{{ t('apiKeys.quotaForm.hourlyTokenLimit') }}</div>
              <el-input-number v-model="quotaDrawer.form.hourlyTokenLimit" :min="0" :step="500" :disabled="!multiWindowEnforced" style="width: 100%" />
            </div>
            <div v-if="isQuotaFieldVisible('minuteRequests')" class="quota-grid__item">
              <div class="quota-field__label">{{ t('apiKeys.quotaForm.rateLimitPerMinute') }}</div>
              <el-input-number v-model="quotaDrawer.form.rateLimitPerMinute" :min="0" :step="10" style="width: 100%" />
            </div>
            <div v-if="isQuotaFieldVisible('monthRequests')" class="quota-grid__item">
              <div class="quota-field__label">{{ t('apiKeys.quotaForm.monthlyRequestLimit') }}</div>
              <el-input-number v-model="quotaDrawer.form.monthlyRequestLimit" :min="0" :step="1000" :disabled="!multiWindowEnforced" style="width: 100%" />
            </div>
          </div>
          <div v-if="!multiWindowEnforced" class="quota-field__hint quota-field__hint--warn">
            {{ t('apiKeys.quotaForm.inertFieldHint') }}
          </div>
          <div class="quota-field">
            <div class="quota-field__label">{{ t('apiKeys.quotaForm.applyPreset') }}</div>
            <el-select v-model="quotaDrawer.presetId" style="width: 100%" @change="applyQuotaPreset">
              <el-option
                v-for="p in QUOTA_PRESETS"
                :key="p.id"
                :label="t(p.labelKey)"
                :value="p.id"
              />
            </el-select>
            <div v-if="!multiWindowEnforced" class="quota-field__hint">
              {{ t('apiKeys.quotaForm.presetInertHint') }}
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>

      <!-- 告警比例 -->
      <div class="quota-field">
        <div class="quota-field__label">{{ t('apiKeys.quotaForm.quotaAlertThreshold') }}</div>
        <el-slider
          v-model="quotaDrawer.form.quotaAlertThreshold"
          :min="0.05"
          :max="1"
          :step="0.05"
          show-input
          :format-tooltip="(val: number) => `${Math.round(val * 100)}%`"
        />
      </div>

      <template #footer>
        <el-button @click="quotaDrawerVisible = false">{{ t('apiKeys.actions.cancel') }}</el-button>
        <el-button type="warning" @click="handleResetQuotaFromDrawer">{{ t('apiKeys.quotaForm.resetQuota') }}</el-button>
        <el-button type="primary" :loading="quotaDrawerSaving" @click="saveQuotaFromDrawer">
          {{ t('apiKeys.quotaForm.saveQuota') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 密钥详情抽屉（精简后移出的字段与高级操作） -->
    <el-drawer v-model="detailDrawerVisible" :title="t('apiKeys.detailDrawerTitle')" size="480px">
      <template v-if="detailRow">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item :label="t('apiKeys.columns.keyId')">
            <span class="mono">{{ detailRow.keyId }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.description')">
            {{ detailRow.description || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.permissions')">
            <div class="permission-tags">
              <el-tag v-for="permission in detailRow.permissions" :key="permission"
                :type="getPermissionTagType(permission)" size="small">{{ formatPermission(permission) }}</el-tag>
              <span v-if="!detailRow.permissions?.length">-</span>
            </div>
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.status')">
            <el-tag :type="detailRow.enabled ? 'success' : 'info'" size="small">
              {{ detailRow.enabled ? t('apiKeys.status.enabled') : t('apiKeys.status.disabled') }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.alertStatus')">
            <el-tag :type="detailRow.quotaAlertTriggered ? 'danger' : 'success'" size="small">
              {{ detailRow.quotaAlertTriggered ? t('apiKeys.status.alert') : t('apiKeys.status.normal') }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.todayToken')">
            {{ detailRow.todayTokenUsage != null ? formatTokenCount(detailRow.todayTokenUsage) : '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.todayRequest')">
            {{ detailRow.todayRequestCount ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.quota')">
            <div class="quota-plain">
              <span class="quota-plain__limit">{{ quotaPlain(detailRow) }}</span>
              <span v-if="hasQuotaLimit(detailRow)" class="quota-plain__left">
                {{ remainingPlain(detailRow) }}
              </span>
            </div>
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.usage')">
            {{ detailRow.totalRequests }}（成功 {{ detailRow.successfulRequests }} / 失败 {{ detailRow.failedRequests }}）
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.createdAt')">
            {{ formatDateTime(detailRow.createdAt) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.createdBy')">
            {{ detailRow.createdBy || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.expiresAt')">
            {{ formatDateTime(detailRow.expiresAt) || t('apiKeys.neverExpires') }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="detail-actions" v-if="canWrite">
          <el-button type="warning" :disabled="detailRow.expired" @click="handleRotate(detailRow)">{{ t('apiKeys.actions.rotate') }}</el-button>
          <el-button type="info" @click="handleReset(detailRow)">{{ t('apiKeys.actions.reset') }}</el-button>
        </div>
      </template>
    </el-drawer>
  </PageSkeleton>

    <!-- 创建/编辑API密钥对话框（原型 modal6：用途 / 分配给谁 / 可用虚拟模型 / 每月额度） -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" center width="min(640px, 92vw)" top="6vh">
      <div class="proto-sub">{{ dialogSubtitle }}</div>

      <div class="proto-form">
        <!-- 这个密钥做什么用的 -->
        <div class="proto-field">
          <div class="proto-field__label">{{ t('apiKeys.form.purposeLabel') }}</div>
          <el-input
            v-model="form.description"
            maxlength="128"
            show-word-limit
            :placeholder="t('apiKeys.form.descriptionPlaceholder')"
          />
        </div>

        <!-- 分配给谁 -->
        <div class="proto-field">
          <div class="proto-field__label">{{ t('apiKeys.form.holderLabel') }}</div>
          <el-select
            v-model="form.holder"
            filterable
            clearable
            :placeholder="t('apiKeys.form.holderPlaceholder')"
            style="width: 100%"
          >
            <el-option
              v-for="a in accountOptions"
              :key="a.username"
              :label="accountLabel(a)"
              :value="a.username"
            >
              <span>{{ a.username }}</span>
              <span v-if="a.department" class="proto-option-sub">{{ a.department }}</span>
            </el-option>
          </el-select>
          <div class="proto-field__hint">{{ t('apiKeys.form.holderHint') }}</div>
        </div>

        <!-- 数据密级（#9 数据分级出境）：商业版能力；未解锁显示「商业版」灰态锁，不可设置 -->
        <div class="proto-field">
          <div class="proto-field__label">
            {{ t('apiKeys.form.classificationLabel') }}
            <el-tooltip v-if="!classificationEnabled" :content="t('nav.feature.lockedTooltip')" placement="top">
              <el-tag size="small" type="warning" class="proto-enterprise-tag">
                <el-icon><Lock /></el-icon>{{ t('nav.feature.enterpriseBadge') }}
              </el-tag>
            </el-tooltip>
          </div>
          <el-select v-if="classificationEnabled" v-model="form.classification" style="width: 100%">
            <el-option
              v-for="opt in classificationOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <button v-else type="button" class="proto-locked-control" @click="onClassificationLockedClick">
            <el-icon><Lock /></el-icon>
            <span>{{ form.classification || 'PUBLIC' }}</span>
          </button>
          <div v-if="classificationEnabled" class="proto-field__hint">{{ t('apiKeys.form.classificationHint') }}</div>
        </div>

        <!-- 能用哪些虚拟模型 -->
        <div class="proto-field">
          <div class="proto-field__label">
            {{ t('apiKeys.form.groupsLabel') }}
            <el-button link type="primary" size="small" @click="toggleAllGroups">
              {{ allGroupsSelected ? t('apiKeys.form.groupsDeselectAll') : t('apiKeys.form.groupsSelectAll') }}
            </el-button>
          </div>
          <el-checkbox-group v-model="form.allowedGroups" class="proto-model-list">
            <el-checkbox v-for="g in modelPickerOptions" :key="g.value" :value="g.value" class="proto-model-item">
              <span class="proto-model-item__name">{{ g.label }}</span>
              <span v-if="g.desc" class="proto-model-item__desc">{{ g.desc }}</span>
            </el-checkbox>
          </el-checkbox-group>
          <div v-if="modelPickerOptions.length === 0" class="proto-field__hint">
            {{ t('apiKeys.form.groupsEmpty') }}
          </div>
          <div class="proto-field__hint">{{ t('apiKeys.form.groupsHint') }}</div>
        </div>

        <!-- 每月额度（仅创建弹窗）：金额行仅企业版可见；社区版只留 token 上限 -->
        <div v-if="!isEdit" class="proto-field" :class="{ 'is-inert': !multiWindowEnforced }">
          <div class="proto-field__label">{{ t('apiKeys.form.monthlyQuotaLabel') }}</div>
          <div v-if="billingEnabled" class="proto-amount-row">
            <span class="proto-amount-row__symbol">¥</span>
            <el-input-number
              v-model="formAmount"
              :min="0"
              :step="100"
              :precision="2"
              :disabled="formPrice === null || !multiWindowEnforced"
              :placeholder="formPrice === null ? '—' : ''"
              controls-position="right"
              class="proto-amount-row__input"
              @change="onFormAmountChange"
            />
            <span class="proto-amount-row__unit">/ {{ t('apiKeys.form.perMonth') }}</span>
          </div>
          <div v-if="billingEnabled" class="proto-field__hint">{{ t('apiKeys.form.monthlyQuotaHint') }}</div>
          <div v-if="billingEnabled && formPrice === null && multiWindowEnforced" class="proto-field__hint proto-field__hint--warn">
            {{ t('apiKeys.quotaForm.amountUnavailableHint') }}
          </div>
          <div class="proto-field__sub-label">{{ t('apiKeys.quotaForm.monthlyTokenLimit') }}</div>
          <el-input-number
            v-model="form.monthlyTokenLimit"
            :min="0"
            :step="10000"
            :disabled="!multiWindowEnforced"
            controls-position="right"
            style="width: 100%"
            @change="onFormTokenChange"
          />
          <div v-if="formPrice !== null && multiWindowEnforced" class="proto-field__hint">
            {{ t('apiKeys.form.monthlyTokenHint', { tokens: formatTokens(form.monthlyTokenLimit) }) }}
          </div>
          <div v-else-if="multiWindowEnforced" class="proto-field__hint">{{ t('apiKeys.form.monthlyTokenOnlyHint') }}</div>
          <div v-if="!multiWindowEnforced" class="proto-field__hint proto-field__hint--warn">
            {{ t('apiKeys.quotaForm.ledgerOffDesc') }}
          </div>
        </div>

        <!-- 编辑弹窗：配额只读摘要 + 跳转配额抽屉（改限额统一走「配额」入口，避免双入口不一致） -->
        <div v-else class="proto-field">
          <div class="proto-field__label">{{ t('apiKeys.form.monthlyQuotaLabel') }}</div>
          <div class="quota-edit-summary">
            <div class="quota-edit-summary__row">
              <span class="quota-edit-summary__k">{{ t('apiKeys.quotaForm.usedThisMonth') }}</span>
              <span class="quota-edit-summary__v">{{ editUsedText }}</span>
            </div>
            <div class="quota-edit-summary__row">
              <span class="quota-edit-summary__k">{{ t('apiKeys.quotaForm.monthlyQuota') }}</span>
              <span class="quota-edit-summary__v">{{ editLimitText }}</span>
            </div>
            <el-button class="quota-edit-summary__btn" type="primary" plain size="small" @click="openQuotaFromEdit">
              {{ t('apiKeys.form.adjustQuota') }}
            </el-button>
          </div>
        </div>

        <!-- 高级设置（密钥ID / 有效期 / IP白名单 / 轮换周期）已拆为列表「更多 → 高级设置」独立弹窗，避免创建/编辑表单过长 -->
      </div>

      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false">{{ t('apiKeys.actions.cancel') }}</el-button>
          <el-button type="primary" @click="handleSave" :loading="saveLoading">{{ t('apiKeys.actions.save') }}</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- 高级设置弹窗（从创建/编辑表单拆出，列表「更多 → 高级设置」入口） -->
    <el-dialog
      v-model="advancedVisible"
      :title="t('apiKeys.advancedDialog.title', { keyId: advancedForm.keyId })"
      width="min(560px, 92vw)"
      append-to-body
    >
      <div class="proto-sub">{{ advancedRow?.description || '-' }}</div>
      <div class="proto-form">
        <!-- 密钥ID 为唯一标识，不可编辑（创建时确定，编辑态原表单亦禁用） -->
        <div class="proto-field">
          <div class="proto-field__label">{{ t('apiKeys.form.keyId') }}</div>
          <el-input :model-value="advancedForm.keyId" disabled />
        </div>
        <div class="proto-field">
          <div class="proto-field__label">{{ t('apiKeys.form.expiresAtGate') }}</div>
          <el-date-picker
            v-model="advancedForm.expiresAt"
            clearable
            format="YYYY-MM-DD HH:mm:ss"
            :placeholder="t('apiKeys.form.expiresAtPlaceholder')"
            style="width: 100%"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
          />
          <div class="proto-field__hint">{{ t('apiKeys.form.expiresAtHint') }}</div>
        </div>
        <div class="proto-field">
          <div class="proto-field__label">{{ t('apiKeys.form.ipWhitelistGate') }}</div>
          <el-select
            v-model="advancedForm.allowedIpAddresses"
            allow-create
            clearable
            filterable
            multiple
            :placeholder="t('apiKeys.form.ipWhitelistPlaceholder')"
            style="width: 100%"
          />
          <div class="proto-field__hint">{{ t('apiKeys.form.ipWhitelistHint') }}</div>
        </div>
        <div class="proto-field">
          <div class="proto-field__label">{{ t('apiKeys.form.rotationPeriod') }}</div>
          <el-input-number v-model="advancedForm.rotationPeriodDays" :min="0" :step="30" controls-position="right" style="width: 100%" />
          <div class="proto-field__hint">{{ t('apiKeys.form.rotationPeriodHint') }}</div>
        </div>
      </div>
      <template #footer>
        <el-button @click="advancedVisible = false">{{ t('apiKeys.actions.cancel') }}</el-button>
        <el-button type="primary" :loading="advancedSaving" @click="saveAdvanced">
          {{ t('apiKeys.actions.save') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 密钥排查弹窗（原型 modal7） -->
    <el-dialog
      v-model="troubleshootVisible"
      :title="t('apiKeys.troubleshoot.title', { description: troubleshootRow?.description || '-' })"
      width="min(640px, 92vw)"
      append-to-body
    >
      <div class="proto-sub">
        {{ troubleshootRow?.keyId }} ·
        {{ t('apiKeys.troubleshoot.holder', { holder: troubleshootHolder }) }} ·
        {{ troubleshootStatusText }}
      </div>
      <div class="proto-check-list">
        <div
          v-for="(c, idx) in troubleshootChecks"
          :key="idx"
          class="proto-check"
          :class="'proto-check--' + c.tone"
        >
          <span class="proto-check__icon">{{ c.icon }}</span>
          <span class="proto-check__text">{{ c.text }}</span>
        </div>
        <div class="proto-check proto-check--hint">
          <div class="proto-check__hint-title">{{ t('apiKeys.troubleshoot.commonTitle') }}</div>
          <ul class="proto-check__hints">
            <li>{{ t('apiKeys.troubleshoot.hint1') }}</li>
            <li>{{ t('apiKeys.troubleshoot.hint2') }}</li>
            <li>{{ t('apiKeys.troubleshoot.hint3') }}</li>
          </ul>
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="troubleshootVisible = false">{{ t('apiKeys.actions.gotIt') }}</el-button>
      </template>
    </el-dialog>

    <!-- 回收确认弹窗（原型 modal8） -->
    <el-dialog v-model="recycleVisible" width="min(480px, 92vw)" append-to-body>
      <template #header>
        <span class="recycle-title">{{ t('apiKeys.recycle.title') }}</span>
      </template>
      <div class="proto-sub">{{ recycleSubtitle }}</div>
      <div class="recycle-warning">
        <p class="recycle-warning__line">{{ t('apiKeys.recycle.line1', { holder: recycleHolder }) }}</p>
        <p class="recycle-warning__line">{{ t('apiKeys.recycle.line2', { amount: recycleAmountText }) }}</p>
      </div>
      <div class="recycle-note">{{ t('apiKeys.recycle.recoverable') }}</div>
      <template #footer>
        <el-button @click="recycleVisible = false">{{ t('apiKeys.recycle.thinkAgain') }}</el-button>
        <el-button type="danger" :loading="recycleSaving" @click="confirmRecycle">
          {{ t('apiKeys.recycle.confirm') }}
        </el-button>
      </template>
    </el-dialog>

</template>

<script setup lang="ts">
import { onMounted, ref, reactive, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { usePermission } from '@/composables/usePermission'
import { useQuotaLedger } from '@/composables/useQuotaLedger'
import { useQuotaWindows } from '@/composables/useQuotaWindows'
import { useEnterpriseStore } from '@/stores/enterprise'
import { useTenantStore } from '@/stores/tenant'
import { usePersistedState } from '@/composables/usePersistedState'
import { useTableMaxHeight } from '@/composables/useTableMaxHeight'
import { confirmDanger } from '@/utils/dangerConfirm'
import { effectMessage, revokeMessage } from '@/utils/effectScope'
import { Key, CircleCheck, CircleClose, Warning, Lock } from '@element-plus/icons-vue'
import StatCard from '@/components/StatCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import { formatDateTime } from '@/utils/format'
import {
  createApiKey,
  deleteApiKey,
  disableApiKey,
  enableApiKey,
  getApiKeys,
  updateApiKey,
  resetApiKey,
  rotateApiKey,
  resetApiKeyQuota,
  getQuotaOverview
} from '@/api/apiKey'
import { getApiKeyQuota } from '@/api/apiKey'
import { updateApiKeyQuota } from '@/api/apiKeyQuotaOps'
import { getJwtAccounts } from '@/api/account'
import { queryCallHistory } from '@/api/callHistory'
import { getServiceInstances as _getServiceInstances } from '@/api/instance'
import { getPoolList } from '@/api/pools'
import { getPriceCatalog } from '@/api/providerPrice'
import { getQuotaUsage, getQuotaStatus } from '@/api/quota'
import { getServiceTypeLabel, normalizeServiceType } from '@/constants/serviceTypes'
import { QUOTA_PRESETS, formatQuotaLimit as _formatQuotaLimit, formatRemaining as _formatRemaining, formatQuotaPlain, formatRemainingPlain } from '@/utils/quotaOps'
import type {
  ApiKeyVO,
  ApiKeyListVO,
  ApiKeyCreateRequest,
  ApiKeyUpdateRequest,
  QuotaUsageDetail
} from '@/types'

const { t } = useI18n()
const { hasPermission } = usePermission()
const enterpriseStore = useEnterpriseStore()
const tenantStore = useTenantStore()
/** 计费功能位（open-core 门控）：关（社区版）→ mixedPriceForGroups 恒 null，金额维度整页隐藏 */
const billingEnabled = computed(() => enterpriseStore.billing)
/**
 * 数据密级（#9 数据分级出境）属商业版能力：
 * 未解锁时密级列隐藏、编辑表单显示「商业版」灰态锁且提交不下发 classification，
 * 与社区版 open-core 边界一致（企业版正常可设）。
 */
const classificationEnabled = computed(() => enterpriseStore.isEnabled('dataClassification'))
/** 灰态锁点击：不修改，提示需商业版授权 */
const onClassificationLockedClick = () => {
  ElMessage({ message: t('nav.feature.lockedHint'), type: 'info', showClose: true })
}
/**
 * 多租户功能位（license 闸门）：开（企业版）→「全部密钥」按当前租户隔离，
 * 新建密钥自动绑定顶部当前租户；关（社区版）→ 无租户概念，行为与旧版一致。
 * 与后端 TenantContextHolder.isMultiTenant() 同源。
 */
const multiTenant = computed(() => enterpriseStore.multiTenant)
const canWrite = hasPermission('security:apikeys:manage')
const router = useRouter()
const route = useRoute()

// 列表搜索（按名称/密钥ID 过滤）
const searchKey = ref('')
// U10-5：搜索词持久化，下次进入本页自动恢复
usePersistedState('apiKeys.search', searchKey)
function onSearchInput() {
  pagination.currentPage = 1
}

const copyKeyId = (keyId: string) => {
  navigator.clipboard.writeText(keyId)
    .then(() => ElMessage.success(t('apiKeys.messages.keyCopied')))
    .catch(() => ElMessage.error(t('apiKeys.messages.copyFailed')))
}

// 详情抽屉（承载精简后移出的字段与高级操作）
const detailDrawerVisible = ref(false)
const detailRow = ref<any>(null)
function openDetailDrawer(row: any) {
  detailRow.value = row
  detailDrawerVisible.value = true
}

// ===== v3.2.1 配额抽屉 =====
/**
 * P0-A 方案 B：配额账本开关探针。
 *
 * <p>账本关闭时，「每小时 / 每月」类限额不会被真正拦截（认证链路只校验
 * 每日请求、每日 Token、每分钟速率三项）——详见 {@link useQuotaLedger} 的实测依据。
 * 这里用它把那些字段禁掉并明示，避免管理员「配了以为会拦」。</p>
 */
const { multiWindowEnforced, loadQuotaLedgerState } = useQuotaLedger()

// 统计窗口联动（2026-11-28）：全局 windows 关闭的窗口，高级限额字段不显示
// （后端 enabledWindows 不判定该窗口，配置无意义）
const { isFieldVisible: isQuotaFieldVisible } = useQuotaWindows()

const quotaDrawerVisible = ref(false)
const quotaDrawerLoading = ref(false)
const quotaDrawerSaving = ref(false)
const quotaDrawer = reactive({
  keyId: '',
  /** 持有人（弹窗标题用） */
  holder: '',
  /** 用途描述（弹窗副标题用） */
  description: '',
  presetId: 'standard',
  detail: null as QuotaUsageDetail | null,
  form: {
    dailyRequestLimit: 0,
    dailyTokenLimit: 0,
    hourlyRequestLimit: 0,
    hourlyTokenLimit: 0,
    monthlyRequestLimit: 0,
    monthlyTokenLimit: 0,
    rateLimitPerMinute: 0,
    quotaAlertThreshold: 0.8
  }
})

const openQuotaDrawer = async (row: any) => {
  quotaDrawer.keyId = row.keyId
  quotaDrawer.holder = row.createdBy || t('apiKeys.unknownHolder')
  quotaDrawer.description = row.description || ''
  quotaDrawer.presetId = ''
  quotaDrawer.detail = null
  // 金额维度：按可用虚拟模型的价格折算（无价格时只展示 token 维度）
  quotaDrawerPrice.value = keyMixedPrice(row)
  quotaDrawerAmount.value = tokenToAmount(row.monthlyTokenLimit || 0, quotaDrawerPrice.value)
  // 先用列表数据填表单，立即可操作；详情异步刷新，不阻塞打开
  quotaDrawer.form = {
    dailyRequestLimit: row.dailyRequestLimit || 0,
    dailyTokenLimit: row.dailyTokenLimit || 0,
    hourlyRequestLimit: row.hourlyRequestLimit || 0,
    hourlyTokenLimit: row.hourlyTokenLimit || 0,
    monthlyRequestLimit: row.monthlyRequestLimit || 0,
    monthlyTokenLimit: row.monthlyTokenLimit || 0,
    rateLimitPerMinute: row.rateLimitPerMinute || 0,
    quotaAlertThreshold: row.quotaAlertThreshold ?? 0.8
  }
  quotaDrawerVisible.value = true
  quotaDrawerLoading.value = true
  try {
    const detail = await getApiKeyQuota(row.keyId)
    quotaDrawer.detail = detail
    quotaDrawer.form = {
      dailyRequestLimit: detail.dailyRequestLimit || 0,
      dailyTokenLimit: detail.dailyTokenLimit || 0,
      hourlyRequestLimit: detail.hourlyRequestLimit || 0,
      hourlyTokenLimit: detail.hourlyTokenLimit || 0,
      monthlyRequestLimit: detail.monthlyRequestLimit || 0,
      monthlyTokenLimit: detail.monthlyTokenLimit || 0,
      rateLimitPerMinute: detail.rateLimitPerMinute || 0,
      quotaAlertThreshold: detail.quotaAlertThreshold ?? 0.8
    }
  } catch (error) {
    console.error('获取配额详情失败:', error)
  } finally {
    quotaDrawerLoading.value = false
  }
}

const applyQuotaPreset = (presetId: string) => {
  const preset = QUOTA_PRESETS.find(p => p.id === presetId)
  if (!preset) return
  quotaDrawer.form = { ...preset.limits }
  // P0-A 方案 B：账本关闭时，多窗口字段不生效。
  // 预设若把它们设成非 0，管理员会以为「限了」——实际不会拦。
  // 这里清零并在 UI 上明示，避免「配了却像真的」。
  if (!multiWindowEnforced.value) {
    quotaDrawer.form.hourlyRequestLimit = 0
    quotaDrawer.form.hourlyTokenLimit = 0
    quotaDrawer.form.monthlyRequestLimit = 0
    quotaDrawer.form.monthlyTokenLimit = 0
    quotaDrawerAmount.value = 0
  }
}

const saveQuotaFromDrawer = async () => {
  if (!quotaDrawer.keyId) return
  quotaDrawerSaving.value = true
  try {
    const detail = await updateApiKeyQuota(quotaDrawer.keyId, { ...quotaDrawer.form })
    quotaDrawer.detail = detail
    ElMessage.success(t('apiKeys.quotaForm.saveSuccess'))
    await fetchApiKeys()
  } catch (e: any) {
    ElMessage.error(e?.message || t('apiKeys.quotaForm.saveQuota'))
  } finally {
    quotaDrawerSaving.value = false
  }
}

const handleResetQuotaFromDrawer = async () => {
  if (!quotaDrawer.keyId) return
  try {
    await resetApiKeyQuota(quotaDrawer.keyId)
    if (quotaDrawer.keyId) {
      quotaDrawer.detail = await getApiKeyQuota(quotaDrawer.keyId)
    }
    ElMessage.success(t('apiKeys.quotaForm.resetSuccess'))
    await fetchApiKeys()
  } catch (e: any) {
    ElMessage.error(e?.message || t('apiKeys.quotaForm.resetQuota'))
  }
}

// 列表数据
const listData = reactive<ApiKeyListVO>({
  items: [],
  total: 0,
  enabledCount: 0,
  disabledCount: 0,
  expiredCount: 0,
  summary: {
    todayTotalRequests: 0,
    todaySuccessfulRequests: 0,
    todayFailedRequests: 0
  }
})

const apiKeys = ref<ApiKeyVO[]>([])
const loading = ref(false)

// 主列表表格内部滚动（自适应 max-height）
const keysTableRef = ref()
const { maxHeight: keysMaxHeight } = useTableMaxHeight(keysTableRef)

/**
 * 全部密钥统计卡（原型口径）：总数 / 本月活跃 / 即将到期 / 发了没人用。
 * 全部从密钥列表本身派生，不额外请求。
 */
const keyStats = computed(() => {
  const items = listData.items || []
  const now = Date.now()
  let activeThisMonth = 0
  let expiringSoon = 0
  let unused = 0
  for (const k of items) {
    const lastUsed = k.lastUsedAt ? new Date(k.lastUsedAt).getTime() : 0
    if (lastUsed && now - lastUsed <= 30 * 24 * 3600 * 1000) activeThisMonth++
    if (k.remainingDays !== null && k.remainingDays !== undefined
        && k.remainingDays > 0 && k.remainingDays <= 7 && !k.expired) {
      expiringSoon++
    }
    if (!k.totalRequests || k.totalRequests === 0) unused++
  }
  return {
    total: listData.total,
    activeThisMonth,
    expiringSoon,
    unused
  }
})

/** 模型分组名 → 显示名 映射（用于把 allowedGroups 的池名渲染成「公司主力」这类备注名） */
const poolNameMap = ref<Record<string, string>>({})
const groupDisplay = (name?: string): string => (name ? (poolNameMap.value[name] || name) : '')

// ===== 原型口径：额度 / 用量双维度（token + ¥）=====
// 价格目录：厂商模型名 → 单价（¥ / 百万 tokens，由 providerPrice API 层统一换算）
const priceCatalog = ref<Record<string, { priceInput: number; priceOutput: number; hasPrice: boolean }>>({})
// 虚拟模型成员：池名 → 成员实例（实例名 + 权重）
const poolMembersMap = ref<Record<string, Array<{ instanceId: string; weight: number }>>>({})
// 密钥本月用量：keyId → 本月 token / 请求数（配额账本 KEY 桶，自然月）
const keyMonthlyUsage = ref<Record<string, { tokenCount: number; requestCount: number }>>({})
// 配额账本是否可用（本次查询是否取到任何一行；全部取不到说明账本未启用，用量显示为「-」）
const ledgerAvailable = ref(false)

/**
 * 该密钥的「混合单价」（¥ / 百万 tokens）。
 *
 * 口径：配额账本只记 token 总量、不区分输入与输出，故取输入价与输出价的均值作为混合单价；
 * 密钥可用多个虚拟模型时，按各虚拟模型成员的权重加权平均。
 * 可用模型一个都没配价格时返回 null —— 此时只展示 token 维度，不展示金额。
 */
const mixedPriceForGroups = (groups?: string[] | null): number | null => {
  // open-core 门控：金额是企业版（billing 功能位）能力。社区版无计价口径，
  // 这里恒返回 null —— 全页 ≈¥ 估算 / 金额输入 / 金额文案自动降级为 token 维度。
  if (!enterpriseStore.billing) return null
  let weighted = 0
  let weightSum = 0
  for (const g of groups || []) {
    for (const m of poolMembersMap.value[g] || []) {
      const p = priceCatalog.value[m.instanceId]
      if (!p || !p.hasPrice) continue
      const unit = (p.priceInput + p.priceOutput) / 2
      const w = m.weight > 0 ? m.weight : 1
      weighted += unit * w
      weightSum += w
    }
  }
  return weightSum === 0 ? null : weighted / weightSum
}

const keyMixedPrice = (key: ApiKeyVO): number | null => mixedPriceForGroups(key?.allowedGroups)

/** token 数 → 金额估算（¥）；无价格时返回 null（不展示金额维度）；单价为 ¥/百万 tokens */
const tokenToAmount = (tokens: number | null | undefined, price: number | null): number | null => {
  if (tokens === null || tokens === undefined) return null
  if (price === null) return null
  return (tokens / 1000000) * price
}

/** 本月已用 token（账本 KEY 桶，自然月）；账本可用但该密钥本月无记录时按 0 计 */
const monthlyTokenUsed = (keyId: string): number | null => {
  const u = keyMonthlyUsage.value[keyId]
  if (u) return u.tokenCount
  return ledgerAvailable.value ? 0 : null
}

/** token 数量展示 */
const formatTokens = (n: number | null | undefined): string => {
  if (n === null || n === undefined) return '-'
  return n.toLocaleString('zh-CN')
}

/** 金额展示：整数不带小数，非整数保留两位（¥1,000 / ¥23.50） */
const formatAmount = (n: number | null | undefined): string => {
  if (n === null || n === undefined) return '-'
  if (Number.isInteger(n)) return `¥${  n.toLocaleString('zh-CN')}`
  return `¥${  n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

/** 密钥状态（原型口径派生）：已过期 > 已停用 > 即将到期 > 发了没人用 > 正常 */
const keyStatus = (k: ApiKeyVO): { kind: string; type: 'success' | 'warning' | 'danger' | 'info'; label: string } => {
  if (k.expired) return { kind: 'expired', type: 'danger', label: t('apiKeys.status.expired') }
  if (!k.enabled) return { kind: 'disabled', type: 'info', label: t('apiKeys.status.disabled') }
  const days = k.remainingDays
  if (days !== null && days !== undefined && days >= 0 && days <= 7) {
    return {
      kind: 'expiring',
      type: 'warning',
      label: days === 0 ? t('apiKeys.status.expiresToday') : t('apiKeys.status.expiresInDays', { n: days })
    }
  }
  if (!k.totalRequests || k.totalRequests === 0) return { kind: 'unused', type: 'danger', label: t('apiKeys.status.unused') }
  return { kind: 'normal', type: 'success', label: t('apiKeys.status.normal') }
}

/** 状态筛选（原型下拉） */
const statusFilter = ref<string>('all')
const STATUS_FILTER_OPTIONS = [
  { value: 'all', labelKey: 'apiKeys.filters.all' },
  { value: 'normal', labelKey: 'apiKeys.filters.normal' },
  { value: 'expiring', labelKey: 'apiKeys.filters.expiring' },
  { value: 'unused', labelKey: 'apiKeys.filters.unused' },
  { value: 'disabled', labelKey: 'apiKeys.filters.disabled' }
]

// ===== 调整额度弹窗：金额维度（¥）与 token 维度联动 =====
// 该密钥的混合单价（¥ / 百万 token）；null = 可用模型未配价格，此时只展示 token 维度
const quotaDrawerPrice = ref<number | null>(null)
// 弹窗里的金额输入（¥ / 月）
const quotaDrawerAmount = ref<number | null>(null)

/** 本月已用占本月额度的百分比（token 口径） */
const quotaUsedPercent = computed<number | null>(() => {
  const limit = quotaDrawer.form.monthlyTokenLimit || 0
  if (limit <= 0) return null
  const used = monthlyTokenUsed(quotaDrawer.keyId) || 0
  return Math.round((used / limit) * 1000) / 10
})

/** 改 token 额度 → 反算金额 */
const onQuotaTokenChange = (val: number | null | undefined) => {
  const price = quotaDrawerPrice.value
  if (price === null) {
    quotaDrawerAmount.value = null
    return
  }
  quotaDrawerAmount.value = tokenToAmount(val || 0, price)
}

/** 改金额额度 → 反算 token（金额只是估算视图，最终保存的是 token） */
const onQuotaAmountChange = (val: number | null | undefined) => {
  const price = quotaDrawerPrice.value
  if (price === null || val === null || val === undefined) return
  quotaDrawer.form.monthlyTokenLimit = Math.round((val * 1000000) / price)
}
/** 行内「更多」下拉命令分发 */
const onRowCommand = (cmd: string, row: any): void => {
  if (cmd === 'edit') handleEdit(row)
  else if (cmd === 'quota') void openQuotaDrawer(row)
  else if (cmd === 'advanced') openAdvanced(row)
  else if (cmd === 'detail') openDetailDrawer(row)
  else if (cmd === 'delete') handleDelete(row)
}

// ===== 高级设置弹窗（从创建/编辑表单拆出，列表「更多 → 高级设置」入口） =====
// 仅承载：密钥ID（只读）/ 有效期 / IP 白名单 / 轮换周期，保存时复用 updateApiKey 并保留其余字段不变
const advancedVisible = ref(false)
const advancedSaving = ref(false)
const advancedRow = ref<ApiKeyVO | null>(null)
const advancedForm = reactive({
  keyId: '',
  expiresAt: '',
  allowedIpAddresses: [] as string[],
  rotationPeriodDays: 0
})

/** 打开高级设置弹窗并回填当前密钥的高级字段 */
const openAdvanced = (row: ApiKeyVO) => {
  advancedRow.value = row
  advancedForm.keyId = row.keyId
  advancedForm.expiresAt = row.expiresAt || ''
  advancedForm.allowedIpAddresses = [...(row.allowedIpAddresses || [])]
  advancedForm.rotationPeriodDays = row.rotationPeriodDays || 0
  advancedVisible.value = true
}

/** 保存高级设置：仅更新高级字段，其余字段沿用行内原值（避免误覆盖） */
const saveAdvanced = async () => {
  const row = advancedRow.value
  if (!row) return
  advancedSaving.value = true
  try {
    const updateData: ApiKeyUpdateRequest = {
      description: row.description || '',
      holder: row.holder || row.createdBy || undefined,
      expiresAt: advancedForm.expiresAt || undefined,
      enabled: row.enabled,
      allowedIpAddresses: advancedForm.allowedIpAddresses,
      allowedGroups: row.allowedGroups || [],
      dailyRequestLimit: row.dailyRequestLimit || 0,
      dailyTokenLimit: row.dailyTokenLimit || 0,
      hourlyRequestLimit: row.hourlyRequestLimit || 0,
      hourlyTokenLimit: row.hourlyTokenLimit || 0,
      monthlyRequestLimit: row.monthlyRequestLimit || 0,
      monthlyTokenLimit: row.monthlyTokenLimit || 0,
      rateLimitPerMinute: row.rateLimitPerMinute || 0,
      quotaAlertThreshold: row.quotaAlertThreshold ?? 0.8,
      rotationPeriodDays: advancedForm.rotationPeriodDays
    }
    await updateApiKey(row.keyId, updateData)
    ElMessage.success(t('apiKeys.messages.advancedSaved'))
    advancedVisible.value = false
    await fetchApiKeys()
  } catch (error: any) {
    ElMessage.error(t('apiKeys.messages.advancedSaveFailedDetail', { message: error?.message || '' }))
  } finally {
    advancedSaving.value = false
  }
}

/** 拉取价格目录（厂商模型名 → ¥/百万 tokens 单价），用于把 token 额度折算成金额估算 */
const fetchPriceCatalog = async () => {
  try {
    const res = await getPriceCatalog()
    const map: Record<string, { priceInput: number; priceOutput: number; hasPrice: boolean }> = {}
    for (const item of (res.data?.data || [])) {
      if (!item?.modelName) continue
      map[item.modelName] = {
        priceInput: item.priceInput || 0,
        priceOutput: item.priceOutput || 0,
        hasPrice: !!item.hasPrice
      }
    }
    priceCatalog.value = map
  } catch {
    priceCatalog.value = {}
  }
}

/**
 * 拉取各密钥本月用量（配额账本 KEY 桶，自然月窗口）。
 *
 * 按 apiKeyId 精确查询只命中 key 维度桶行 —— 分组桶 / 成员桶的 apiKeyId 为空不会被查到，
 * 因此各行 token 不会重复计数。
 */
const fetchKeyMonthlyUsage = async () => {
  const keys = apiKeys.value || []
  // 账本是否启用取权威来源 /monitoring/quota/status：
  // 启用但该密钥本月无记录 = 0 次调用（显示 0）；未启用则用量未知（显示「-」）
  try {
    const status = await getQuotaStatus()
    ledgerAvailable.value = !!status?.enabled
  } catch {
    ledgerAvailable.value = false
  }
  if (keys.length === 0) {
    keyMonthlyUsage.value = {}
    return
  }
  const map: Record<string, { tokenCount: number; requestCount: number }> = {}
  await Promise.all(keys.map(async (k) => {
    try {
      const rows = await getQuotaUsage({ apiKeyId: k.keyId, window: 'MONTH' })
      for (const r of rows) {
        map[k.keyId] = {
          tokenCount: (map[k.keyId]?.tokenCount || 0) + (r.tokenCount || 0),
          requestCount: (map[k.keyId]?.requestCount || 0) + (r.requestCount || 0)
        }
      }
    } catch {
      /* 单个密钥查询失败不影响整表 */
    }
  }))
  keyMonthlyUsage.value = map
}

const saveLoading = ref(false)

const dialogVisible = ref(false)
const dialogTitle = ref('')
const isEdit = ref(false)
/** 弹窗副标题（原型：给某个员工发一个 AI 调用密钥……） */
const dialogSubtitle = computed(() =>
  isEdit.value ? t('apiKeys.form.subtitleEdit') : t('apiKeys.form.subtitleCreate')
)
const defaultExpiry = (): string => {
  const d = new Date(Date.now() + 90 * 24 * 60 * 60 * 1000)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

// 分页状态
const pagination = reactive({
  currentPage: 1,
  pageSize: 20,
  pageSizes: [10, 20, 50, 100],
  total: 0
})

// 表单数据
/** 数据密级三档（#9 数据分级出境）；默认 PUBLIC 与服务端兜底一致 */
const classificationOptions = [
  { value: 'PUBLIC', label: 'PUBLIC · 公开（不受出境限制）' },
  { value: 'INTERNAL', label: 'INTERNAL · 内部（仅境内/私有模型）' },
  { value: 'CONFIDENTIAL', label: 'CONFIDENTIAL · 机密（境内/私有 + 强制留痕）' }
]

const form = ref({
  keyId: '',
  description: '',
  /** 分配给谁（密钥持有人）；为空表示不指定，后端回落为创建者 */
  holder: '',
  /** 数据密级：默认 PUBLIC */
  classification: 'PUBLIC',
  expiresAt: '',
  enabled: true,
  allowedIpAddresses: [] as string[],
  allowedGroups: [] as string[],
  dailyRequestLimit: 0,
  dailyTokenLimit: 0,
  hourlyRequestLimit: 0,
  hourlyTokenLimit: 0,
  monthlyRequestLimit: 0,
  monthlyTokenLimit: 0,
  rateLimitPerMinute: 0,
  quotaAlertThreshold: 0.8,
  rotationPeriodDays: 0
})

// 分组白名单可选数据源（v3.5 治理收口：白名单只存模型分组名 / 虚拟模型名）
const groupOptions = ref<string[]>([])

// 加载可选模型分组（pool 名），供密钥白名单多选使用
// v3.5 治理收口：对外调用的唯一入口是模型分组（虚拟模型名），
// 因此白名单只列**已显式创建**的分组——没建分组就没有可调用的虚拟模型。
const loadModelAndGroupOptions = async () => {
  try {
    const pres = await getPoolList()
    const pools = (pres.data?.data || []) as Array<{
      poolName?: string
      name?: string
      description?: string
      serviceType?: string
      enabled?: boolean
    }>
    const names = pools
      .filter((p) => p.enabled !== false)
      .map((p) => p.poolName)
      .filter(Boolean) as string[]
    groupOptions.value = names.sort()
    // 记录 分组名 → 展示元数据（员工用的模型名 / 描述），供「创建密钥」表单的虚拟模型清单使用
    const meta: Record<string, PoolMeta> = {}
    pools.forEach((p) => {
      const st = normalizeServiceType(p.serviceType || '')
      if (p.poolName) {
        meta[p.poolName] = {
          label: p.name || p.poolName,
          desc: p.description || '',
          serviceType: st || ''
        }
      }
    })
    poolMetaMap.value = meta
  } catch { /* ignore */ }
}

// ===== 原型 modal6：创建密钥表单 =====
/** 虚拟模型清单元数据：池名 → 展示名 / 描述 / 分类 */
interface PoolMeta { label: string; desc: string; serviceType: string }
const poolMetaMap = ref<Record<string, PoolMeta>>({})

/** 可选账户（「分配给谁」下拉数据源） */
const accountOptions = ref<Array<{ username: string; department?: string; enabled: boolean }>>([])
/** 账户下拉展示：王小明（市场部） */
const accountLabel = (a: { username: string; department?: string }): string =>
  a.department ? `${a.username}（${a.department}）` : a.username

/** 拉取账户列表（「分配给谁」下拉数据源） */
const fetchAccountOptions = async () => {
  try {
    const list = await getJwtAccounts()
    accountOptions.value = (list || []).map(a => ({
      username: a.username,
      department: a.department,
      enabled: a.enabled
    }))
  } catch {
    accountOptions.value = []
  }
}

/** 「这个人能用哪些虚拟模型？」的清单项 */
const modelPickerOptions = computed(() =>
  (groupOptions.value || []).map(name => {
    const meta = poolMetaMap.value[name]
    return {
      value: name,
      label: meta?.label || name,
      desc: meta?.desc || ''
    }
  })
)

/** 是否已全选虚拟模型 */
const allGroupsSelected = computed(() =>
  modelPickerOptions.value.length > 0 &&
  modelPickerOptions.value.every(g => (form.value.allowedGroups || []).includes(g.value))
)

/** 全选 / 全不选 */
const toggleAllGroups = () => {
  form.value.allowedGroups = allGroupsSelected.value
    ? []
    : modelPickerOptions.value.map(g => g.value)
}

/** 额度金额视图（¥ / 月）——与 token 额度联动 */
const formAmount = ref<number | null>(null)
/** 当前所勾选虚拟模型的混合单价（¥ / 百万 tokens）；null = 未配价格 */
const formPrice = computed(() => mixedPriceForGroups(form.value.allowedGroups))

/** 勾选变化 → 按新的混合单价重算金额视图 */
const onFormGroupsChange = () => {
  const price = formPrice.value
  formAmount.value = price === null ? null : tokenToAmount(form.value.monthlyTokenLimit || 0, price)
}

/** 改金额 → 反算 token（最终保存的仍是 token 额度） */
const onFormAmountChange = (val: number | null | undefined) => {
  const price = formPrice.value
  if (price === null || val === null || val === undefined) return
  form.value.monthlyTokenLimit = Math.round((val * 1000000) / price)
}

/** 改 token 额度 → 反算金额视图（未配价格时金额不可用，置空） */
const onFormTokenChange = () => {
  const price = formPrice.value
  formAmount.value = price === null ? null : tokenToAmount(form.value.monthlyTokenLimit || 0, price)
}

// ===== 编辑弹窗的配额摘要（编辑态不再直接改限额，统一跳「配额」抽屉） =====
const editingRow = ref<ApiKeyVO | null>(null)

/** 本月已用（取不到账本数据时显示 —） */
const editUsedText = computed(() => {
  const u = form.value.keyId ? monthlyTokenUsed(form.value.keyId) : null
  return u === null ? '—' : `${formatTokens(u)} tokens`
})

/** 每月额度：0 显示不限；配了价时附金额估算 */
const editLimitText = computed(() => {
  const limit = form.value.monthlyTokenLimit || 0
  let text = limit > 0 ? `${formatTokens(limit)} tokens` : t('apiKeys.quotaUnlimited')
  if (limit > 0 && formPrice.value !== null) {
    text += ` ≈ ${formatAmount(tokenToAmount(limit, formPrice.value))}`
  }
  return text
})

/** 编辑弹窗 → 关闭编辑弹窗并打开配额抽屉 */
const openQuotaFromEdit = () => {
  if (!editingRow.value) return
  dialogVisible.value = false
  openQuotaDrawer(editingRow.value)
}

// 勾选变化时同步金额视图
watch(
  () => form.value.allowedGroups,
  () => { onFormGroupsChange() },
  { deep: true }
)

// 多租户下切换顶部租户 → 重新拉取「全部密钥」列表（后端按 X-Tenant-Id 行级过滤）。
// 社区版 multiTenant=false，不 watch，行为零变化。
watch(
  () => tenantStore.currentTenantId,
  (next, prev) => {
    if (multiTenant.value && next !== prev) {
      clearSelection()
      pagination.currentPage = 1
      void fetchApiKeys()
    }
  }
)

// ===== 原型 modal7：密钥排查 =====
interface TroubleshootCheck { icon: string; text: string; tone: 'ok' | 'warn' | 'info' }

const troubleshootVisible = ref(false)
const troubleshootRow = ref<ApiKeyVO | null>(null)
const troubleshootCalls = reactive({ success: 0, failed: 0, available: false })

const troubleshootHolder = computed(() =>
  troubleshootRow.value?.holder || troubleshootRow.value?.createdBy || t('apiKeys.unknownHolder')
)

const troubleshootStatusText = computed(() => {
  const row = troubleshootRow.value
  if (!row) return ''
  if (row.expired) return t('apiKeys.status.expired')
  if (!row.enabled) return t('apiKeys.status.disabled')
  return t('apiKeys.status.normal')
})

/** 虚拟模型是否「在线」：分组存在且至少挂了一个实例 */
const isGroupOnline = (groupName: string): boolean => {
  const members = poolMembersMap.value[groupName]
  return Array.isArray(members) && members.length > 0
}

/** 排查结论卡 */
const troubleshootChecks = computed<TroubleshootCheck[]>(() => {
  const row = troubleshootRow.value
  if (!row) return []
  const cards: TroubleshootCheck[] = []

  // ① 密钥状态
  if (row.expired) {
    cards.push({
      icon: '⛔',
      tone: 'warn',
      text: t('apiKeys.troubleshoot.expired', {
        date: row.expiresAt ? formatDateTime(row.expiresAt, { dateOnly: true }) : '-'
      })
    })
  } else if (!row.enabled) {
    cards.push({ icon: '⛔', tone: 'warn', text: t('apiKeys.troubleshoot.disabled') })
  } else {
    cards.push({ icon: '✅', tone: 'ok', text: t('apiKeys.troubleshoot.statusOk') })
  }

  // ② 额度
  const price = keyMixedPrice(row)
  const limit = row.monthlyTokenLimit || 0
  const used = monthlyTokenUsed(row.keyId)
  if (limit <= 0) {
    cards.push({ icon: 'ℹ️', tone: 'info', text: t('apiKeys.troubleshoot.noQuotaLimit') })
  } else if (used === null) {
    cards.push({ icon: 'ℹ️', tone: 'info', text: t('apiKeys.troubleshoot.quotaUnknown') })
  } else {
    const percent = Math.round((used / limit) * 1000) / 10
    const usedText = price === null
      ? `${formatTokens(used)} tokens`
      : `${formatAmount(tokenToAmount(used, price))}（${formatTokens(used)} tokens）`
    const limitText = price === null
      ? `${formatTokens(limit)} tokens`
      : `${formatAmount(tokenToAmount(limit, price))}（${formatTokens(limit)} tokens）`
    cards.push({
      icon: percent >= 80 ? '⚠️' : '✅',
      tone: percent >= 80 ? 'warn' : 'ok',
      text: percent >= 80
        ? t('apiKeys.troubleshoot.quotaLow', { used: usedText, limit: limitText, percent })
        : t('apiKeys.troubleshoot.quotaOk', { used: usedText, limit: limitText, percent })
    })
  }

  // ③ 可用虚拟模型
  const groups = row.allowedGroups || []
  if (groups.length === 0) {
    cards.push({ icon: 'ℹ️', tone: 'info', text: t('apiKeys.troubleshoot.groupsUnlimited') })
  } else {
    const offline = groups.filter(g => !isGroupOnline(g))
    if (offline.length === 0) {
      cards.push({
        icon: '✅',
        tone: 'ok',
        text: t('apiKeys.troubleshoot.groupsOk', {
          names: groups.map(g => groupDisplay(g)).join('、')
        })
      })
    } else {
      cards.push({
        icon: '⚠️',
        tone: 'warn',
        text: t('apiKeys.troubleshoot.groupsOffline', {
          names: offline.map(g => groupDisplay(g)).join('、')
        })
      })
    }
  }

  // ④ 最近 24 小时调用
  if (!troubleshootCalls.available) {
    cards.push({ icon: 'ℹ️', tone: 'info', text: t('apiKeys.troubleshoot.callsUnknown') })
  } else if (troubleshootCalls.failed === 0) {
    cards.push({
      icon: '✅',
      tone: 'ok',
      text: t('apiKeys.troubleshoot.callsOk', {
        success: troubleshootCalls.success,
        failed: troubleshootCalls.failed
      })
    })
  } else {
    cards.push({
      icon: '⚠️',
      tone: 'warn',
      text: t('apiKeys.troubleshoot.callsFailed', {
        success: troubleshootCalls.success,
        failed: troubleshootCalls.failed
      })
    })
  }

  return cards
})

/** 打开排查弹窗 */
const openTroubleshoot = async (row: ApiKeyVO) => {
  troubleshootRow.value = row
  troubleshootCalls.success = 0
  troubleshootCalls.failed = 0
  troubleshootCalls.available = false
  troubleshootVisible.value = true
  await loadTroubleshootCalls(row.keyId)
}

/** 统计最近 24 小时该密钥的成功 / 失败调用次数 */
const loadTroubleshootCalls = async (keyId: string) => {
  try {
    const end = new Date()
    const start = new Date(end.getTime() - 24 * 3600 * 1000)
    const fmt = (d: Date) => {
      const pad = (n: number) => String(n).padStart(2, '0')
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T` +
        `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
    }
    const [ok, fail] = await Promise.all([
      queryCallHistory({
        apiKeyId: keyId, isSuccess: true,
        startTime: fmt(start), endTime: fmt(end), page: 0, size: 1
      }),
      queryCallHistory({
        apiKeyId: keyId, isSuccess: false,
        startTime: fmt(start), endTime: fmt(end), page: 0, size: 1
      })
    ])
    troubleshootCalls.success = ok?.totalElements ?? 0
    troubleshootCalls.failed = fail?.totalElements ?? 0
    troubleshootCalls.available = true
  } catch {
    troubleshootCalls.available = false
  }
}

// ===== 原型 modal8：回收确认 =====
const recycleVisible = ref(false)
const recycleSaving = ref(false)
const recycleRow = ref<ApiKeyVO | null>(null)

const recycleHolder = computed(() =>
  recycleRow.value?.holder || recycleRow.value?.createdBy || t('apiKeys.unknownHolder')
)

/** 副标题：设计部-赵六 · 便宜日常 · 发了 186 天一次没用过 */
const recycleSubtitle = computed(() => {
  const row = recycleRow.value
  if (!row) return ''
  const groups = (row.allowedGroups || []).map(g => groupDisplay(g)).join('、')
  const created = row.createdAt ? new Date(row.createdAt).getTime() : 0
  const days = created ? Math.max(0, Math.floor((Date.now() - created) / 86400000)) : 0
  return t('apiKeys.recycle.subtitle', {
    description: row.description || row.keyId,
    groups: groups || '-',
    days
  })
})

/** 回收前提示里的额度文案 */
const recycleAmountText = computed(() => {
  const row = recycleRow.value
  if (!row) return '-'
  const price = keyMixedPrice(row)
  const limit = row.monthlyTokenLimit || 0
  if (limit <= 0) return t('apiKeys.quotaUnlimited')
  if (price === null) return `${formatTokens(limit)} tokens`
  return `${formatAmount(tokenToAmount(limit, price))} / ${t('apiKeys.form.perMonth')}`
})

const openRecycle = (row: ApiKeyVO) => {
  recycleRow.value = row
  recycleVisible.value = true
}

/** 确认回收：停用这把密钥（可重新启用，不做物理删除） */
const confirmRecycle = async () => {
  const row = recycleRow.value
  if (!row) return
  recycleSaving.value = true
  try {
    await disableApiKey(row.keyId)
    recycleVisible.value = false
    await fetchApiKeys()
    ElMessage.success(revokeMessage(t('apiKeys.recycle.success')))
  } catch (error: any) {
    ElMessage.error(error?.message || t('apiKeys.messages.statusChangeFailed'))
  } finally {
    recycleSaving.value = false
  }
}

// 获取权限标签类型
const getPermissionTagType = (permission: string) => {
  const canonical = normalizeServiceType(permission) ?? permission
  switch (canonical) {
    case 'chat':
      return 'primary'
    case 'embedding':
      return 'success'
    case 'rerank':
      return 'warning'
    case 'tts':
      return 'info'
    case 'stt':
      return 'info'
    case 'imgGen':
      return 'danger'
    case 'imgEdit':
      return 'danger'
    // 兼容旧权限格式
    case 'image':
      return 'danger'
    case 'audio':
      return 'info'
    case 'admin':
      return 'danger'
    default:
      return 'info'
  }
}

// 格式化权限显示（后端权限值与 i18n key 同名；未知值原样返回）
// 存量数据可能是小写 imggen/imgedit，先归一化再查 i18n，避免显示成原始英文
/** 密级标签配色（#9）：PUBLIC 中性 / INTERNAL 警示 / CONFIDENTIAL 危险 */
const classificationTagType = (classification?: string): 'info' | 'warning' | 'danger' => {
  if (classification === 'CONFIDENTIAL') return 'danger'
  if (classification === 'INTERNAL') return 'warning'
  return 'info'
}

const formatPermission = (permission: string) => {
  const canonical = normalizeServiceType(permission)
  if (canonical) {
    return getServiceTypeLabel(canonical)
  }
  return t(`apiKeys.permissions.${permission}`, permission)
}

// 格式化 Token 数量
const formatTokenCount = (count: number): string => {
  if (count >= 1000000) {
    return `${(count / 1000000).toFixed(1)  }M`
  }
  if (count >= 1000) {
    return `${(count / 1000).toFixed(1)  }K`
  }
  return count.toString()
}

/* ---------- U5-4：配额口语化 ---------- */
const hasQuotaLimit = (row: any) =>
  (row.dailyRequestLimit || 0) > 0 || (row.dailyTokenLimit || 0) > 0 || (row.rateLimitPerMinute || 0) > 0

const quotaPlain = (row: any) =>
  formatQuotaPlain(
    row.dailyRequestLimit,
    row.dailyTokenLimit,
    row.rateLimitPerMinute,
    t('apiKeys.quotaUnlimited')
  )

const remainingPlain = (row: any) => {
  if ((row.dailyRequestLimit || 0) > 0) {
    return formatRemainingPlain(row.todayRequestCount, row.dailyRequestLimit, '次', t('apiKeys.quotaUnlimited'))
  }
  if ((row.dailyTokenLimit || 0) > 0) {
    return formatRemainingPlain(row.todayTokenUsage, row.dailyTokenLimit, ' token', t('apiKeys.quotaUnlimited'))
  }
  return formatRemainingPlain(row.todayRequestCount, row.rateLimitPerMinute, '次/分钟', t('apiKeys.quotaUnlimited'))
}

/** 表单内实时预览：把限额翻译成一句人话 */
const _formQuotaPlain = computed(() =>
  formatQuotaPlain(
    form.value.dailyRequestLimit,
    form.value.dailyTokenLimit,
    form.value.rateLimitPerMinute,
    t('apiKeys.quotaUnlimited')
  )
)

// 分页数据

// 搜索 + 状态筛选后的列表（原型口径：搜人名 / 用途 / 密钥前缀）
const filteredApiKeys = computed(() => {
  const q = (searchKey.value || '').trim().toLowerCase()
  const filter = statusFilter.value
  return apiKeys.value.filter(k => {
    if (filter !== 'all' && keyStatus(k).kind !== filter) return false
    if (!q) return true
    return (k.description || '').toLowerCase().includes(q) ||
      (k.keyId || '').toLowerCase().includes(q) ||
      (k.createdBy || '').toLowerCase().includes(q)
  })
})

const pagedApiKeys = computed(() => {
  const start = (pagination.currentPage - 1) * pagination.pageSize
  const end = start + pagination.pageSize
  return filteredApiKeys.value.slice(start, end)
})

// 过滤后同步分页总数
watch(filteredApiKeys, (list) => {
  pagination.total = list.length
})

// 分页处理
const handleSizeChange = (val: number) => {
  pagination.pageSize = val
  pagination.currentPage = 1
}

const handleCurrentChange = (val: number) => {
  pagination.currentPage = val
}

// 获取API密钥列表
const fetchApiKeys = async () => {
  loading.value = true
  try {
    const data = await getApiKeys()
    // 列表行保留后端规范日期字符串（编辑回写/日期选择器需要），表格列内再做本地化展示
    const formattedItems = data.items

    apiKeys.value = formattedItems
    listData.items = formattedItems
    listData.total = data.total
    listData.enabledCount = data.enabledCount
    listData.disabledCount = data.disabledCount
    listData.expiredCount = data.expiredCount
    listData.summary = data.summary
    pagination.total = data.total
  } catch (_error) {
    ElMessage.error(t('apiKeys.messages.fetchListFailed'))
  } finally {
    // 列表 spinner 只绑定主列表请求，避免被配额接口拖死
    loading.value = false
  }
  // 配额概览/告警异步补齐，失败不影响列表
  void fetchQuotaOverview()
  // 本月用量（配额账本 KEY 桶，自然月）：用于「本月已用」列的 token / 金额展示
  void fetchKeyMonthlyUsage()
}

// 获取配额概览数据
const fetchQuotaOverview = async () => {
  try {
    const quotaData = await getQuotaOverview()
    // 创建配额数据映射
    const quotaMap = new Map<string, QuotaUsageDetail>()
    quotaData.forEach(quota => quotaMap.set(quota.keyId, quota))

    // 将配额数据合并到列表项
    apiKeys.value = apiKeys.value.map(key => ({
      ...key,
      todayTokenUsage: quotaMap.get(key.keyId)?.todayTokenUsage ?? key.todayTokenUsage,
      todayRequestCount: quotaMap.get(key.keyId)?.todayRequestCount ?? key.todayRequestCount,
      quotaAlertTriggered: quotaMap.get(key.keyId)?.alertTriggered ?? key.quotaAlertTriggered
    }))
    listData.items = apiKeys.value
  } catch (error) {
    // 配额数据获取失败不影响列表显示
    console.error('获取配额概览失败:', error)
  }
}

// 创建API密钥弹窗
const handleCreateApiKey = () => {
  dialogTitle.value = t('apiKeys.createApiKey')
  isEdit.value = false
  editingRow.value = null
  form.value = {
    keyId: '',
    description: '',
    holder: '',
    classification: 'PUBLIC',
    expiresAt: '',
    enabled: true,
    allowedIpAddresses: [],
    allowedGroups: [],
    dailyRequestLimit: 0,
    dailyTokenLimit: 0,
    hourlyRequestLimit: 0,
    hourlyTokenLimit: 0,
    monthlyRequestLimit: 0,
    monthlyTokenLimit: 0,
    rateLimitPerMinute: 0,
    quotaAlertThreshold: 0.8,
    rotationPeriodDays: 0
  }
  form.value.expiresAt = defaultExpiry()
  formAmount.value = null
  loadModelAndGroupOptions()
  fetchAccountOptions()
  dialogVisible.value = true
}

// 编辑API密钥弹窗
const handleEdit = (row: ApiKeyVO) => {
  dialogTitle.value = t('apiKeys.editApiKey')
  isEdit.value = true
  editingRow.value = row
  form.value = {
    keyId: row.keyId,
    description: row.description || '',
    holder: row.holder || row.createdBy || '',
    classification: row.classification || 'PUBLIC',
    expiresAt: row.expiresAt || '',
    enabled: row.enabled,
    // 关键修复：从列表 VO 回填现有 IP 白名单，不再硬置空（VO 此前漏出该字段，导致编辑保存即清空）
    allowedIpAddresses: row.allowedIpAddresses || [],
    allowedGroups: row.allowedGroups || [],
    dailyRequestLimit: row.dailyRequestLimit || 0,
    dailyTokenLimit: row.dailyTokenLimit || 0,
    hourlyRequestLimit: row.hourlyRequestLimit || 0,
    hourlyTokenLimit: row.hourlyTokenLimit || 0,
    monthlyRequestLimit: row.monthlyRequestLimit || 0,
    monthlyTokenLimit: row.monthlyTokenLimit || 0,
    rateLimitPerMinute: row.rateLimitPerMinute || 0,
    quotaAlertThreshold: row.quotaAlertThreshold ?? 0.8,
    rotationPeriodDays: row.rotationPeriodDays || 0
  }
  loadModelAndGroupOptions()
  fetchAccountOptions()
  dialogVisible.value = true
}

// 删除API密钥
const handleDelete = async (row: ApiKeyVO) => {
  // U10-3：统一危险操作确认模板（影响面 + 补救方式 + 输入名称确认）
  const confirmed = await confirmDanger({
    title: t('apiKeys.confirmations.deleteTitle'),
    target: row.description || row.keyId,
    impact: t('apiKeys.confirmations.deleteImpact'),
    remedy: t('apiKeys.confirmations.deleteRemedy'),
    requireType: row.keyId,
    confirmText: t('apiKeys.confirmations.confirmDelete'),
    cancelText: t('apiKeys.actions.cancel'),
    type: 'warning'
  })
  if (!confirmed) return
  try {
    await deleteApiKey(row.keyId)
    await fetchApiKeys()
    ElMessage.success(t('apiKeys.messages.deleteSuccess'))
  } catch (_error) {
    ElMessage.error(t('apiKeys.messages.deleteFailed'))
  }
}

// 重置API密钥
const handleReset = async (row: ApiKeyVO) => {
  const confirmed = await confirmDanger({
    title: t('apiKeys.confirmations.resetTitle'),
    target: row.keyId,
    action: '重置',
    impact: t('apiKeys.confirmations.resetImpact'),
    remedy: t('apiKeys.confirmations.resetRemedy'),
    type: 'warning'
  })
  if (!confirmed) return
  try {
    await resetApiKey(row.keyId)
    await fetchApiKeys()
    ElMessage.success(t('apiKeys.messages.resetSuccess'))
  } catch (error: any) {
    ElMessage.error(t('apiKeys.messages.resetFailedDetail', { message: error.message || '' }))
  }
}

// 强制轮换API密钥
const handleRotate = async (row: ApiKeyVO) => {
  const confirmed = await confirmDanger({
    title: t('apiKeys.confirmations.rotateTitle'),
    target: row.keyId,
    action: '轮换',
    impact: t('apiKeys.confirmations.rotateImpact'),
    remedy: t('apiKeys.confirmations.rotateRemedy'),
    type: 'warning'
  })
  if (!confirmed) return
  try {
    await rotateApiKey(row.keyId)
    await fetchApiKeys()
    ElMessage.success(t('apiKeys.messages.rotateSuccess'))
  } catch (error: any) {
    ElMessage.error(t('apiKeys.messages.rotateFailedDetail', { message: error.message || '' }))
  }
}

// 重置API密钥每日配额
const _handleResetQuota = async (row: ApiKeyVO) => {
  const confirmed = await confirmDanger({
    title: t('apiKeys.confirmations.quotaResetTitle'),
    target: row.keyId,
    action: '重置',
    type: 'warning'
  })
  if (!confirmed) return
  try {
    await resetApiKeyQuota(row.keyId)
    await fetchQuotaOverview()
    ElMessage.success(t('apiKeys.messages.quotaResetSuccess'))
  } catch (error: any) {
    ElMessage.error(t('apiKeys.messages.quotaResetFailedDetail', { message: error.message || '' }))
  }
}

// 保存API密钥
const handleSave = async () => {
  saveLoading.value = true
  try {
    if (!form.value.description || !form.value.description.trim()) {
      ElMessage.error(t('apiKeys.form.descriptionRequired'))
      return
    }
    // 「可调用的模型分类」已移除：分类权限由后端按白名单自动推导，前端不再提交 permissions

    if (isEdit.value) {
      // 编辑
      const updateData: ApiKeyUpdateRequest = {
        description: form.value.description,
        holder: form.value.holder || undefined,
        ...(classificationEnabled.value ? { classification: form.value.classification } : {}),
        expiresAt: form.value.expiresAt || undefined,
        enabled: form.value.enabled,
        allowedIpAddresses: form.value.allowedIpAddresses,
        allowedGroups: form.value.allowedGroups,
        dailyRequestLimit: form.value.dailyRequestLimit,
        dailyTokenLimit: form.value.dailyTokenLimit,
        hourlyRequestLimit: form.value.hourlyRequestLimit,
        hourlyTokenLimit: form.value.hourlyTokenLimit,
        monthlyRequestLimit: form.value.monthlyRequestLimit,
        monthlyTokenLimit: form.value.monthlyTokenLimit,
        rateLimitPerMinute: form.value.rateLimitPerMinute,
        quotaAlertThreshold: form.value.quotaAlertThreshold,
        rotationPeriodDays: form.value.rotationPeriodDays
      }
      await updateApiKey(form.value.keyId, updateData)
      ElMessage.success(effectMessage(t('apiKeys.messages.editSuccess'), 'apiKey'))
    } else {
      // 新增
      const createData: ApiKeyCreateRequest = {
        keyId: form.value.keyId || undefined,
        description: form.value.description,
        holder: form.value.holder || undefined,
        ...(classificationEnabled.value ? { classification: form.value.classification } : {}),
        expiresAt: form.value.expiresAt || undefined,
        enabled: form.value.enabled,
        allowedIpAddresses: form.value.allowedIpAddresses,
        allowedGroups: form.value.allowedGroups,
        dailyRequestLimit: form.value.dailyRequestLimit,
        dailyTokenLimit: form.value.dailyTokenLimit,
        hourlyRequestLimit: form.value.hourlyRequestLimit,
        hourlyTokenLimit: form.value.hourlyTokenLimit,
        monthlyRequestLimit: form.value.monthlyRequestLimit,
        monthlyTokenLimit: form.value.monthlyTokenLimit,
        rateLimitPerMinute: form.value.rateLimitPerMinute,
        quotaAlertThreshold: form.value.quotaAlertThreshold,
        rotationPeriodDays: form.value.rotationPeriodDays
      }
      // 密钥明文加密落盘，响应不再回传明文（C6）：
      // 管理员侧只提示成功，持有人登录后在「我的密钥」里查看并复制
      await createApiKey(createData)
      ElMessage.success(t('apiKeys.messages.createSuccess'))
    }
    dialogVisible.value = false
    await fetchApiKeys()
  } catch (error: any) {
    ElMessage.error(isEdit.value
      ? t('apiKeys.messages.editFailedDetail', { message: error.message || '' })
      : t('apiKeys.messages.createFailedDetail', { message: error.message || '' }))
  } finally {
    saveLoading.value = false
  }
}

/* ==================== 批量操作 ==================== */
/** 当前选中的密钥行 */
const selectedKeys = ref<ApiKeyVO[]>([])

function onSelectionChange(rows: ApiKeyVO[]) {
  selectedKeys.value = rows || []
}

function clearSelection() {
  selectedKeys.value = []
}

/** 批量启停：逐条调用单条端点，汇总成功/失败（后端暂无批量启停端点） */
async function batchToggle(enable: boolean) {
  const rows = [...selectedKeys.value]
  if (rows.length === 0) return
  const succeeded: string[] = []
  const failed: string[] = []
  for (const row of rows) {
    try {
      if (enable) {
        await enableApiKey(row.keyId)
      } else {
        await disableApiKey(row.keyId)
      }
      succeeded.push(row.keyId)
    } catch (error) {
      console.error(`批量${enable ? '启用' : '停用'}密钥失败 ${row.keyId}:`, error)
      failed.push(row.keyId)
    }
  }
  await fetchApiKeys()
  clearSelection()
  if (failed.length === 0) {
    ElMessage.success(
      t('apiKeys.batch.resultAllOk', { count: succeeded.length, action: t(enable ? 'apiKeys.batch.enable' : 'apiKeys.batch.disable') })
    )
  } else {
    ElMessage.warning(
      t('apiKeys.batch.resultPartial', { ok: succeeded.length, fail: failed.length, action: t(enable ? 'apiKeys.batch.enable' : 'apiKeys.batch.disable') })
    )
  }
}

const handleBatchEnable = () => batchToggle(true)
const handleBatchDisable = () => batchToggle(false)

/** 批量删除：走统一危险确认模板（影响面 + 补救方式 + 输入确认词） */
async function handleBatchDelete() {
  const rows = [...selectedKeys.value]
  if (rows.length === 0) return
  const confirmed = await confirmDanger({
    title: t('apiKeys.batch.deleteTitle'),
    target: t('apiKeys.batch.deleteTarget', { count: rows.length }),
    impact: t('apiKeys.batch.deleteImpact', { count: rows.length }),
    remedy: t('apiKeys.batch.deleteRemedy'),
    requireType: String(rows.length),
    confirmText: t('apiKeys.batch.confirmDelete'),
    cancelText: t('apiKeys.actions.cancel'),
    type: 'warning'
  })
  if (!confirmed) return

  const succeeded: string[] = []
  const failed: string[] = []
  for (const row of rows) {
    try {
      await deleteApiKey(row.keyId)
      succeeded.push(row.keyId)
    } catch (error) {
      console.error(`批量删除密钥失败 ${row.keyId}:`, error)
      failed.push(row.keyId)
    }
  }
  await fetchApiKeys()
  clearSelection()
  if (failed.length === 0) {
    ElMessage.success(t('apiKeys.batch.deleteSuccess', { count: succeeded.length }))
  } else {
    ElMessage.warning(t('apiKeys.batch.deletePartial', { ok: succeeded.length, fail: failed.length }))
  }
}

const _handleStatusChange = async (row: ApiKeyVO) => {
  try {
    if (row.enabled) {
      await enableApiKey(row.keyId)
      ElMessage.success(t('apiKeys.messages.enabledDetail', { keyId: row.keyId }))
    } else {
      await disableApiKey(row.keyId)
      ElMessage.success(t('apiKeys.messages.disabledDetail', { keyId: row.keyId }))
    }
    await fetchApiKeys()
  } catch (_error) {
    row.enabled = !row.enabled
    ElMessage.error(t('apiKeys.messages.statusChangeFailed'))
  }
}

// 挂载时加载
onMounted(() => {
  // 功能位探测：billing 关闭（社区版）→ mixedPriceForGroups 恒 null，金额维度整页隐藏
  void enterpriseStore.ensureLoaded()
  fetchApiKeys()
  // 拉取模型分组列表，用于把密钥 allowedGroups 的池名渲染成「公司主力」这类对外显示名
  fetchPoolNames()
  // 拉取模型价格目录，用于把 token 额度 / 用量折算成金额估算
  void fetchPriceCatalog()
  // P0-A 方案 B：探测配额账本开关 → 决定多窗口限额字段是否禁用
  void loadQuotaLedgerState()
  // 跨页引导：从「接入模型」页跳转过来时，直接打开创建弹窗并预填允许分组
  applyRoutePrefill()
})

/** 拉取全部虚拟模型（池）：建立 池名 → 显示名 映射，并记录成员实例（用于把 token 额度折算成金额） */
const fetchPoolNames = async () => {
  try {
    const res = await getPoolList()
    const map: Record<string, string> = {}
    const members: Record<string, Array<{ instanceId: string; weight: number }>> = {}
    for (const p of (res.data?.data || [])) {
      if (!p?.poolName) continue
      map[p.poolName] = p.name || p.poolName
      members[p.poolName] = (p.members || []).map(m => ({
        instanceId: m.instanceId,
        weight: m.weight
      }))
    }
    poolNameMap.value = map
    poolMembersMap.value = members
  } catch {
    poolNameMap.value = {}
    poolMembersMap.value = {}
  }
}

/**
 * 处理路由 query 预填（引导直链）。
 * 支持：
 *   /keys?action=create            → 打开创建弹窗
 *   /keys?action=create&group=xxx  → 打开创建弹窗并预填「允许分组」
 */
const applyRoutePrefill = () => {
  const action = route.query.action
  if (action !== 'create') return
  const group = typeof route.query.group === 'string' ? route.query.group : ''
  handleCreateApiKey()
  if (group) {
    form.value.allowedGroups = Array.from(new Set([...(form.value.allowedGroups || []), group]))
  }
  // 用 replace 清掉 query，避免刷新后重复弹窗
  const q = { ...route.query }
  delete q.action
  delete q.group
  router.replace({ path: route.path, query: q })
}
</script>

<style scoped>
.api-key-management {
  padding: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-actions-row {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.stats-row {
  flex-shrink: 0;
}

.quota-drawer-body {
  display: flex;
  flex-direction: column;
}

.quota-key-id {
  font-family: var(--ja-font-mono);
  color: var(--ja-text-secondary, #888);
  margin-bottom: 8px;
}

.quota-drawer-actions {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 12px;
  margin-top: 20px;
  padding-top: 8px;
}

.quota-drawer-actions .el-button {
  min-width: 96px;
}

.main-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  margin: 0;
}

.main-card__toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.key-search {
  width: 320px;
  max-width: 60%;
}

.main-card__count {
  font-size: 13px;
  color: var(--ja-text-secondary);
}

/* 批量操作条：选中后浮现，靠右对齐 */
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

.key-id-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.key-name {
  font-weight: 500;
}

.key-holder {
  font-size: 13px;
  color: var(--ja-text-primary);
}

.group-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.expire-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}

.mono {
  font-family: var(--ja-font-mono);
}

.main-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 0;
}

.card-header {
  flex-shrink: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.table-wrapper {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.table-wrapper .el-table {
  flex: 1;
}
/* 横向滚动统一交给 EP 表格内部 el-scrollbar（原生行为，fixed 列 sticky 才能正确贴边）：
   此前把表格根改成 overflow:auto 滚动端口，导致 ①根原生条 + EP overlay 条双滚动条、
   ②fixed 操作列 sticky 失效与过期时间列内容重叠。勿再改回根滚动方案 */

.main-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--ja-text-primary);
  display: flex;
  align-items: center;
  gap: 8px;
}

.desc-text {
  color: var(--ja-text-regular);
  font-size: 14px;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding: 16px;
  border-top: 1px solid var(--ja-border-light);
  flex-shrink: 0;
}

.permission-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  max-width: 140px;
  line-height: 1.8;
}

.permission-tags .el-tag {
  margin: 0;
}

.expired-text {
  color: var(--ja-danger);
}

.usage-stat {
  font-weight: 500;
  color: var(--ja-primary);
}

.usage-detail {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-left: 4px;
}

.form-hint {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-top: 4px;
}

/* 三道闸门总览（表单顶部 alert）：每行一条闸门说明 */
.form-gates-alert {
  margin-bottom: 16px;
}

.form-gates-alert .gate-desc {
  font-size: 12px;
  line-height: 1.7;
  color: var(--ja-text-secondary);
}

/* 高级模式开关行：提示语在左、开关在右 */
.advanced-toggle-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 4px 0 8px;
  padding: 8px 12px;
  border-top: 1px dashed var(--el-border-color);
  border-bottom: 1px dashed var(--el-border-color);
}

.advanced-toggle-hint {
  font-size: 12px;
  color: var(--ja-text-secondary);
  line-height: 1.5;
}

/* 权限为空时的阻断性提示（白名单整体失效） */
.form-hint-warning {
  color: var(--el-color-warning);
}

/* el-option 自定义内容里的「分类不匹配」标签 */
.option-tag {
  margin-left: 8px;
  vertical-align: middle;
}

/* 白名单下拉里的模型名（与厂商标签并排） */
.select-option-name {
  margin-right: 4px;
}

.form-alert {
  margin-top: 8px;
}

.action-buttons {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 10px;
  width: auto;
}

.action-buttons .el-button {
  width: auto;
  margin: 0;
  white-space: nowrap;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

/* U5-4：配额口语化展示 */
.quota-plain {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.4;
}
.quota-plain__limit {
  font-size: 13px;
  color: var(--ja-text-primary);
}
.quota-plain__left {
  font-size: 12px;
  color: var(--ja-text-secondary);
}
.quota-plain-preview {
  margin-top: 8px;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--ja-text-primary);
  background: var(--el-fill-color-light);
  border-radius: 4px;
}

:deep(.el-table) {
  border-radius: 8px;
}

:deep(.el-dialog__header) {
  border-bottom: 1px solid var(--ja-border-light);
  padding-bottom: 16px;
}

:deep(.el-dialog__footer) {
  border-top: 1px solid var(--ja-border-light);
  padding-top: 16px;
}

/* ---------- 全部密钥：原型表格样式 ---------- */
.main-card__header {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.main-card__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

.main-card__hint {
  font-size: 12.5px;
  color: var(--ja-text-secondary);
}

.key-status-filter {
  width: 140px;
  flex-shrink: 0;
}

/* 描述列：用途在上、密钥前缀副标题在下 */
.key-desc-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.key-desc-cell__id {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  color: var(--ja-text-secondary);
  font-family: var(--ja-font-mono);
}

/* 双维度单元格：token 为主、金额估算为辅 */
.dual-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}

.dual-cell__main {
  font-size: 13px;
  color: var(--ja-text-primary);
}

.dual-cell__sub {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

/* ---------- 调整额度弹窗（原型 modal5 形态） ---------- */
.quota-dialog__sub {
  font-size: 12.5px;
  color: var(--ja-text-secondary);
  margin-bottom: 12px;
}

.quota-overview {
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--ja-bg-subtle, rgb(0 0 0 / 3%));
  margin-bottom: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.quota-overview__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
}

.quota-overview__k {
  color: var(--ja-text-secondary);
}

.quota-overview__v {
  font-weight: 600;
  color: var(--ja-text-primary);
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
}

.quota-overview__v--warn {
  color: var(--ja-warning);
}

.quota-overview__tokens {
  font-size: 12px;
  font-weight: 400;
  color: var(--ja-text-secondary);
}

.quota-field {
  margin-bottom: 14px;
}

.quota-field__label {
  font-size: 12.5px;
  color: var(--ja-text-secondary);
  margin-bottom: 5px;
}

.quota-field__hint {
  font-size: 11.5px;
  color: var(--ja-text-tertiary);
  margin-top: 4px;
}

/* P0-A 方案 B：不生效的字段降饱和 + 警告色提示 */
.quota-field__hint--warn,
.proto-field__hint--warn {
  color: var(--el-color-warning, #e6a23c);
  line-height: 1.6;
}

.quota-field.is-inert .quota-field__label,
.proto-field.is-inert .proto-field__label {
  opacity: 0.6;
}

.quota-inert-alert {
  margin-bottom: 14px;
}

.quota-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 12px;
}

.quota-grid__item {
  min-width: 0;
}

.quota-advanced {
  margin-bottom: 14px;
}

/* ===== 原型风格：创建密钥 / 排查 / 回收 ===== */
.proto-sub {
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  margin-bottom: 16px;
  line-height: 1.6;
}

.proto-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.proto-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.proto-field__label {
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  display: flex;
  align-items: center;
  gap: 8px;
}

.proto-field__hint {
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
  line-height: 1.6;
}

/* 商业版灰态锁：label 旁的「商业版」徽章（与菜单/顶栏/设置子页同款） */
.proto-enterprise-tag {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  transform: scale(0.85);
  transform-origin: left center;
}

/* 密级未解锁时的只读占位：灰态不可设置，点击提示需商业版授权 */
.proto-locked-control {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  height: 32px;
  padding: 0 11px;
  border: 1px dashed var(--el-border-color);
  border-radius: 4px;
  background: var(--ja-bg-secondary, #f5f6f8);
  color: var(--ja-text-placeholder, var(--el-text-color-placeholder));
  font-size: 12.5px;
  cursor: not-allowed;
  opacity: 0.85;
  text-align: left;
}
.proto-locked-control:hover {
  opacity: 1;
}

/* 每月额度：金额输入下方的「每月Token上限」小标题 */
.proto-field__sub-label {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--ja-text-primary);
  margin: 10px 0 6px;
}

.proto-alert {
  margin-top: 2px;
}

.proto-option-sub {
  float: right;
  color: var(--el-text-color-placeholder);
  font-size: 12px;
  margin-left: 16px;
}

.proto-model-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.proto-model-list :deep(.el-checkbox) {
  height: auto;
  margin-right: 0;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  transition: border-color 0.2s, background-color 0.2s;
}

.proto-model-list :deep(.el-checkbox.is-checked) {
  border-color: var(--el-color-primary);
  background-color: var(--el-color-primary-light-9);
}

.proto-model-list :deep(.el-checkbox__label) {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  white-space: normal;
}

.proto-model-item__name {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.proto-model-item__desc {
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
}

.proto-model-item__tag {
  transform: translateY(-1px);
}

.proto-amount-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 编辑弹窗：配额只读摘要 */
.quota-edit-summary {
  border: 1px solid var(--ja-border-lighter);
  border-radius: 6px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  background: var(--el-fill-color-lighter, var(--ja-bg-card));
}

.quota-edit-summary__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
}

.quota-edit-summary__k {
  color: var(--ja-text-secondary);
}

.quota-edit-summary__v {
  color: var(--ja-text-primary);
  font-weight: 500;
}

.quota-edit-summary__btn {
  align-self: flex-start;
  margin-top: 2px;
}

.proto-amount-row__symbol {
  font-size: 18px;
  color: var(--el-text-color-secondary);
}

.proto-amount-row__input {
  flex: 1;
}

.proto-amount-row__unit {
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

.proto-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.proto-advanced {
  border-top: 1px dashed var(--el-border-color);
  padding-top: 12px;
}

.proto-advanced :deep(.el-collapse-item__content) {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* 排查结论卡 */
.proto-check-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.proto-check {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 12px 14px;
  border-radius: var(--el-border-radius-base);
  font-size: 13px;
  line-height: 1.6;
}

.proto-check--ok {
  background-color: var(--el-color-success-light-9);
  color: var(--el-text-color-primary);
}

.proto-check--warn {
  background-color: var(--el-color-warning-light-9);
  color: var(--el-text-color-primary);
}

.proto-check--info {
  background-color: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}

.proto-check--hint {
  background-color: var(--el-fill-color-lighter);
  color: var(--el-text-color-secondary);
  font-size: 12.5px;
  display: block;
}

.proto-check__icon {
  flex-shrink: 0;
}

.proto-check__hint-title {
  margin-bottom: 6px;
}

.proto-check__hints {
  margin: 0;
  padding-left: 18px;
  line-height: 1.9;
}

/* 回收弹窗 */
.recycle-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-color-danger);
}

.recycle-warning {
  padding: 14px;
  background-color: var(--el-color-danger-light-9);
  border-radius: var(--el-border-radius-base);
  font-size: 13px;
  color: var(--el-color-danger);
  line-height: 1.7;
}

.recycle-warning__line {
  margin: 0;
}

.recycle-note {
  margin-top: 10px;
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
}
</style>