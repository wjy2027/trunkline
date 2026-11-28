<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<template>
  <div>
    <PageHeader
      :title="t('nav.globalConfig.title')"
      :subtitle="t(menuSubtitle('/settings/global'))"
      help-link="/docs?path=system/global"
    />

    <el-card shadow="never">
      <el-tabs v-model="active">
        <!-- 日志记多细：调用历史记录什么粒度 -->
        <el-tab-pane :label="t('globalSettings.tabs.record')" name="record">
          <div class="tab-hint">
            <p class="tab-hint__affects">{{ t('globalSettings.hints.record.affects') }}</p>
            <p class="tab-hint__when">{{ t('globalSettings.hints.record.when') }}</p>
          </div>
          <RecordGovernanceSettings />
        </el-tab-pane>

        <!-- 流量怎么分：全局默认策略（兜底）+ 各模型类型的策略（逐类覆盖） -->
        <el-tab-pane :label="t('globalSettings.tabs.lb')" name="lb">
          <div class="tab-hint">
            <p class="tab-hint__affects">{{ t('globalSettings.hints.lb.affects') }}</p>
            <p class="tab-hint__when">{{ t('globalSettings.hints.lb.when') }}</p>
          </div>
          <LoadBalancerGlobalConfig />
          <!-- v3.6 Trunkline UI 改造：原「策略中心 → 多模型分工」tab 的内容迁至本页 -->
          <ServiceStrategyList />
        </el-tab-pane>

        <!-- 模型连续出错怎么办：全局默认阈值 -->
        <el-tab-pane :label="t('globalSettings.tabs.circuit')" name="circuit">
          <div class="tab-hint">
            <p class="tab-hint__affects">{{ t('globalSettings.hints.circuit.affects') }}</p>
            <p class="tab-hint__when">{{ t('globalSettings.hints.circuit.when') }}</p>
          </div>
          <CircuitBreakerGlobalConfig />
        </el-tab-pane>

        <!-- 额度怎么记：账本后端与 fail-open 策略 -->
        <el-tab-pane :label="t('globalSettings.tabs.quota')" name="quota">
          <div class="tab-hint">
            <p class="tab-hint__affects">{{ t('globalSettings.hints.quota.affects') }}</p>
            <p class="tab-hint__when">{{ t('globalSettings.hints.quota.when') }}</p>
          </div>
          <QuotaConfigManagement />
        </el-tab-pane>

        <!-- 其他：暂未归类的零散功能开关（2026-11-28，目前只有高级计价热开关） -->
        <el-tab-pane :label="t('globalSettings.tabs.misc')" name="misc">
          <div class="tab-hint">
            <p class="tab-hint__affects">{{ t('globalSettings.hints.misc.affects') }}</p>
            <p class="tab-hint__when">{{ t('globalSettings.hints.misc.when') }}</p>
          </div>
          <MiscSettings />
        </el-tab-pane>

        <!-- 底部出口：说明这些是默认值，单个模型要去接入管理调。
             「其他」tab（高级计价 / PII 脱敏）不是按模型的配置，不适用本提示，2026-11-28 起隐藏。 -->
        <el-alert
          v-if="active !== 'misc'"
          type="info"
          :closable="false"
          show-icon
          class="defaults-footer"
          :title="t('globalSettings.defaultsFooter')"
        />
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
/**
 * 全局配置（v3.5 Trunkline UI 改造）
 *
 * <p>把原先散落在多个页面里的<b>网关级默认配置</b>集中到一处，
 * 面向管理员回答「这个网关默认是怎么跑的」：</p>
 * <ul>
 *   <li><b>记录治理</b> —— 调用历史记录到什么粒度（仅元数据 / 摘要 / 完整）。
 *       原在「调用观测」页内联，现抽为 {@link RecordGovernanceSettings} 组件，两处共用。</li>
 *   <li><b>负载均衡</b> —— 全局默认策略 / 哈希算法 / 虚拟节点数，
 *       以及各模型类型的策略列表（逐类覆盖全局默认）。
 *       原为「策略中心 → 负载均衡」tab 的两张卡片，v3.6 起全部收敛到本页。</li>
 *   <li><b>熔断阈值</b> —— 默认失败阈值 / 成功阈值 / 超时等。
 *       原为「策略中心 → 熔断阈值」tab，已收敛到本页。</li>
 *   <li><b>用量配额</b> —— 账本后端、fail-open 策略、落盘间隔。
 *       原为「策略中心 → 用量配额」tab，已收敛到本页。</li>
 * </ul>
 *
 * <p>各 tab 的内容都是<b>原有组件的直接复用</b>，操作的是同一份后端配置，
 * 迁移只改入口位置，不改行为与数据。</p>
 *
 * <p>tab 状态写 URL query，刷新/分享不丢上下文。</p>
 *
 * <p><b>P0-G（2026-11-02）</b>：tab 名由内部术语改为运维管理员能直接理解的说法
 * （记录治理→日志记多细 / 负载均衡→流量怎么分 / 熔断阈值→模型连续出错怎么办 /
 * 用量配额→额度怎么记），并为每个 tab 补「影响什么 / 何时该改」两行说明 +
 * 底部「这些是默认值，单个模型去接入管理调」的出口。tab 的 <code>name</code> 值
 * （record/lb/circuit/quota）与 URL query 保持不变，深链不受影响。</p>
 */
import { ref, watch, provide, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import PageHeader from '@/components/common/PageHeader.vue'
import RecordGovernanceSettings from '@/views/config/RecordGovernanceSettings.vue'
import LoadBalancerGlobalConfig from '@/views/load-balancer/LoadBalancerGlobalConfig.vue'
import ServiceStrategyList from '@/views/load-balancer/StrategyConfig.vue'
import CircuitBreakerGlobalConfig from '@/views/circuit-breaker/GlobalConfig.vue'
import QuotaConfigManagement from '@/views/config/QuotaConfigManagement.vue'
import MiscSettings from '@/views/config/MiscSettings.vue'
import { menuSubtitle } from '@/config/menu'
import {
  LOAD_BALANCER_GLOBAL_CONFIG_KEY,
  loadBalancerGlobalConfig,
  fetchLoadBalancerGlobalConfig
} from '@/views/load-balancer/globalConfigShared'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

// 负载均衡 tab 内两张卡片（全局默认配置 / 服务级配置）是兄弟节点，
// Vue 的 provide 只对子孙生效 → 必须在共同父级（本组件）provide，
// 否则「服务级配置」卡片 inject 拿不到全局默认值。
provide(LOAD_BALANCER_GLOBAL_CONFIG_KEY, loadBalancerGlobalConfig)

const TABS = ['record', 'lb', 'circuit', 'quota', 'misc'] as const
type TabKey = (typeof TABS)[number]

const active = ref<TabKey>(TABS.includes(route.query.tab as TabKey) ? (route.query.tab as TabKey) : 'record')

watch(active, tab => {
  router.replace({ query: { ...route.query, tab } })
})
watch(
  () => route.query.tab,
  tab => {
    if (TABS.includes(tab as TabKey) && tab !== active.value) active.value = tab as TabKey
  }
)

// 全局默认负载均衡配置：一次性拉取，供同 tab 两张卡片共享（provide 在上方）
onMounted(() => {
  fetchLoadBalancerGlobalConfig()
})
</script>

<style scoped>
/* ---------- P0-G：每个 tab 的「影响什么 / 何时该改」说明 ---------- */
.tab-hint {
  margin-bottom: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  border-left: 3px solid var(--ja-primary, #176C6B);
}

.tab-hint__affects,
.tab-hint__when {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--ja-text-secondary);
}

.tab-hint__when {
  margin-top: 4px;
}

/* ---------- 底部「这些是默认值」出口 ---------- */
.defaults-footer {
  margin-top: 16px;
}
</style>
