<template>
  <el-card shadow="hover" style="margin-top: 16px">
    <template #header>
      <span class="card-title">{{ t('orgScopeQuota.title') }}</span>
      <span class="card-hint">{{ t('orgScopeQuota.hint') }}</span>
    </template>

    <el-tabs v-model="activeScope" @tab-change="handleScopeChange">
      <el-tab-pane
        v-for="scope in scopes"
        :key="scope"
        :label="t(`orgScopeQuota.scopes.${scope}`)"
        :name="scope"
      >
        <div class="scope-toolbar">
          <el-button
            v-if="canWrite"
            type="primary"
            size="small"
            @click="openDialog(scope, null)"
          >
            <el-icon><Plus /></el-icon>
            {{ t('orgScopeQuota.add') }}
          </el-button>
          <el-button size="small" :loading="loading" @click="loadRows">
            <el-icon><Refresh /></el-icon>
            {{ t('quotaConfig.refreshStatus') }}
          </el-button>
          <span class="scope-hint">{{ t(`orgScopeQuota.hints.${scope}`) }}</span>
        </div>

        <el-table :data="rows" v-loading="loading" size="small" border>
          <el-table-column prop="scopeId" :label="t('orgScopeQuota.scopeId')" min-width="140" />
          <el-table-column
            v-for="field in visibleColumnList"
            :key="field"
            :prop="field"
            :label="t(`orgScopeQuota.${field}`)"
            width="110"
            align="right"
          />
          <el-table-column :label="t('orgScopeQuota.actions')" width="150" fixed="right" align="center">
            <template #default="{ row }">
              <el-button v-if="canWrite" link type="primary" size="small" @click="openDialog(activeScope, row)">
                {{ t('orgScopeQuota.edit') }}
              </el-button>
              <el-button v-if="canWrite" link type="danger" size="small" @click="handleDelete(row)">
                {{ t('orgScopeQuota.delete') }}
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty :description="t('orgScopeQuota.empty')" :image-size="60" />
          </template>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 新增/编辑弹窗（可复用组件，字段随统计窗口联动） -->
    <OrgScopeQuotaDialog
      v-model="dialogVisible"
      :scope="activeScope"
      :scope-id="editing ? editingScopeId : ''"
      :scope-id-locked="editing"
      @saved="loadRows"
    />
  </el-card>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { usePermission } from '@/composables/usePermission'
import { useQuotaWindows } from '@/composables/useQuotaWindows'
import OrgScopeQuotaDialog from '@/views/config/OrgScopeQuotaDialog.vue'
import {
  getOrgScopeQuotas,
  deleteOrgScopeQuota,
  type OrgScope,
  type OrgScopeQuotaRow,
} from '@/api/orgScopeQuota'

const { t } = useI18n()
const { hasPermission } = usePermission()
const canWrite = hasPermission('config:quota:write')
const { visibleFields } = useQuotaWindows()

const scopes: OrgScope[] = ['users', 'departments', 'tenants']
const activeScope = ref<OrgScope>('users')
const rows = ref<OrgScopeQuotaRow[]>([])
const loading = ref(false)

const dialogVisible = ref(false)
const editing = ref(false)
const editingScopeId = ref('')

/** 当前统计窗口下可见的表格列（字段 ↔ 窗口映射见 useQuotaWindows） */
const visibleColumnList = computed(() => visibleFields())

const loadRows = async () => {
  loading.value = true
  try {
    rows.value = await getOrgScopeQuotas(activeScope.value)
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

const handleScopeChange = () => { loadRows() }

const openDialog = (scope: OrgScope, row: OrgScopeQuotaRow | null) => {
  activeScope.value = scope
  editing.value = !!row
  editingScopeId.value = row ? String(row.scopeId) : ''
  dialogVisible.value = true
}

const handleDelete = async (row: OrgScopeQuotaRow) => {
  try {
    await ElMessageBox.confirm(
      t('orgScopeQuota.deleteConfirm', { id: String(row.scopeId) }),
      t('orgScopeQuota.delete'),
      { type: 'warning' }
    )
  } catch { return }
  try {
    await deleteOrgScopeQuota(activeScope.value, String(row.scopeId))
    ElMessage.success(t('orgScopeQuota.deleted'))
    await loadRows()
  } catch (err: unknown) {
    const axiosErr = err as { response?: { data?: { message?: string } } }
    ElMessage.error(axiosErr.response?.data?.message ?? t('orgScopeQuota.deleteFailed'))
  }
}

onMounted(loadRows)
</script>

<style scoped>
.card-title { font-weight: 600; font-size: 15px; }
.card-hint { margin-left: 12px; font-size: 12px; color: var(--el-text-color-secondary); }
.scope-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.scope-hint { margin-left: 4px; font-size: 12px; color: var(--el-text-color-secondary); }
</style>
