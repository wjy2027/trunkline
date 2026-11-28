<template>
  <PageSkeleton :title="t('accounts.title')">
    <!-- 聚合页嵌入模式下 PageSkeleton 隐藏标题区（含 actions 插槽），工具行需自绘 -->
    <div class="page-actions-row">
      <el-input
        v-model="searchKeyword"
        :placeholder="t('accounts.searchPlaceholder')"
        clearable
        style="width: 200px;"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-button v-if="canWrite" type="primary" @click="showCreateDialog = true">
        <el-icon><Plus /></el-icon>
        {{ t('accounts.createAccount') }}
      </el-button>
      <!-- P1 员工激活包①：批量导入账号（无 SSO 过渡方案） -->
      <el-button v-if="canWrite" @click="showBatchImport = true">
        <el-icon><DocumentAdd /></el-icon>
        批量导入
      </el-button>
      <el-button @click="refreshAccounts">
        <el-icon><Refresh /></el-icon>
        {{ t('accounts.refresh') }}
      </el-button>
    </div>

    <!-- 统计信息 -->
    <el-row :gutter="20" style="margin-bottom: 20px;">
      <el-col :span="6">
        <el-statistic :title="t('accounts.totalAccounts')" :value="accounts.length" />
      </el-col>
      <el-col :span="6">
        <el-statistic :title="t('accounts.enabledAccounts')" :value="accounts.filter(a => a.enabled).length" />
      </el-col>
      <el-col :span="6">
        <el-statistic :title="t('accounts.disabledAccounts')" :value="accounts.filter(a => !a.enabled).length" />
      </el-col>
      <el-col :span="6">
        <el-statistic :title="t('accounts.adminAccounts')" :value="accounts.filter(a => hasRoleCode(a.roles, 'ADMIN')).length" />
      </el-col>
    </el-row>

    <!-- 账户列表 -->
    <el-table ref="accountsTableRef" :data="filteredAccounts" :max-height="accountsMaxHeight" style="width: 100%" v-loading="loading" stripe border>
      <el-table-column prop="username" :label="t('accounts.username')" min-width="120" show-overflow-tooltip />
      <el-table-column prop="name" :label="t('accounts.name')" min-width="120" show-overflow-tooltip>
        <template #default="scope">
          <span v-if="scope.row.name">{{ scope.row.name }}</span>
          <span v-else class="cell-muted">{{ t('accounts.nameUnassigned') }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="department" :label="t('accounts.department')" min-width="110" show-overflow-tooltip>
        <template #default="scope">
          <span v-if="scope.row.department">{{ scope.row.department }}</span>
          <span v-else class="cell-muted">{{ t('accounts.departmentUnassigned') }}</span>
        </template>
      </el-table-column>
        <el-table-column :label="t('accounts.roles')" min-width="150">
        <template #default="scope">
          <el-tag
            v-for="(item, idx) in roleDisplayList(scope.row.roles)"
            :key="idx"
            :type="item.type"
            size="small"
            style="margin-right: 5px;"
          >
            {{ item.label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('accounts.status')" min-width="80" align="center">
        <template #default="scope">
          <el-switch
            v-model="scope.row.enabled"
            :disabled="!canWrite"
            @change="handleStatusChange(scope.row)"
            :loading="scope.row.statusLoading"
          />
        </template>
      </el-table-column>
      <el-table-column :label="t('accounts.createdAt')" min-width="140">
        <template #default="scope">
          {{ formatDateTime(scope.row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('accounts.updatedAt')" min-width="140">
        <template #default="scope">
          {{ formatDateTime(scope.row.updatedAt) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('accounts.actions')" min-width="180" fixed="right">
        <template #default="scope">
          <el-button-group>
            <el-button
              v-if="canWrite"
              size="small"
              type="primary"
              :title="t('accounts.editAccount')"
              :aria-label="t('accounts.editAccount')"
              @click="editAccount(scope.row)"
            >
              <el-icon><Edit /></el-icon>
            </el-button>
            <el-button
              v-if="canWrite"
              size="small"
              type="warning"
              :title="t('orgScopeQuota.quotaButton')"
              :aria-label="t('orgScopeQuota.quotaButton')"
              @click="openQuotaDialog(scope.row)"
            >
              <el-icon><Odometer /></el-icon>
            </el-button>
            <el-button
              v-if="canWrite"
              size="small"
              type="danger"
              :title="t('accounts.messages.confirmDelete')"
              :aria-label="t('accounts.messages.confirmDelete')"
              :disabled="scope.row.username === 'admin'"
              @click="deleteAccount(scope.row)"
            >
              <el-icon><Delete /></el-icon>
            </el-button>
          </el-button-group>
        </template>
      </el-table-column>
    </el-table>

    <!-- 用户配额编辑弹窗（scopeId = username，即组织维「用户额度」的 holder） -->
    <OrgScopeQuotaDialog
      v-model="quotaDialogVisible"
      scope="users"
      :scope-id="quotaTarget"
      scope-id-locked
    />

    <!-- 创建/编辑账户对话框 -->
    <el-dialog
      :title="editingAccount ? t('accounts.editAccount') : t('accounts.createAccount')"
      v-model="showCreateDialog"
      width="min(640px, 92vw)"
      @close="resetForm"
      destroy-on-close
    >
      <el-alert
        v-if="editingAccount"
        :title="t('accounts.editHintTitle')"
        type="info"
        :description="t('accounts.editHintDescription')"
        :closable="false"
        show-icon
        style="margin-bottom: 20px;"
      />
      <el-form :model="accountForm" :rules="accountRules" ref="accountFormRef" label-width="80px">
        <el-form-item :label="t('accounts.username')" prop="username">
          <el-input
            v-model="accountForm.username"
            :disabled="!!editingAccount"
            :placeholder="t('accounts.usernamePlaceholder')"
          />
          <div class="form-hint">{{ t('accounts.usernameHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('accounts.name')" prop="name">
          <el-input
            v-model="accountForm.name"
            :placeholder="t('accounts.namePlaceholder')"
            maxlength="64"
            show-word-limit
          />
          <div class="form-hint">{{ t('accounts.nameHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('accounts.password')" prop="password">
          <el-input
            v-model="accountForm.password"
            type="password"
            show-password
            :placeholder="editingAccount ? t('accounts.passwordLeaveBlank') : t('accounts.passwordPlaceholder')"
          />
        </el-form-item>
        <el-form-item v-if="!editingAccount" :label="t('accounts.confirmPassword')" prop="confirmPassword">
          <el-input
            v-model="accountForm.confirmPassword"
            type="password"
            show-password
            :placeholder="t('accounts.confirmPasswordPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('accounts.department')" prop="department">
          <div class="dept-select-row">
            <el-select
              v-model="accountForm.department"
              filterable
              :placeholder="t('accounts.departmentPlaceholder')"
              style="flex: 1;"
            >
              <el-option v-for="d in departmentOptions" :key="d" :label="d" :value="d" />
            </el-select>
            <el-button :icon="Plus" @click="addDepartment">{{ t('accounts.departmentAdd') }}</el-button>
          </div>
          <div class="form-hint">{{ t('accounts.departmentHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('accounts.roles')" prop="role">
          <el-select v-model="accountForm.role" fit-input-width :placeholder="t('accounts.rolesPlaceholder')" style="width: 100%;">
            <el-option
              v-for="role in ROLES"
              :key="role"
              :label="t('accounts.roleLabels.' + role)"
              :value="role"
            >
              <div class="role-option">
                <el-tag :type="roleTagType(role)" size="small">{{ t('accounts.roleLabels.' + role) }}</el-tag>
                <span class="role-option-desc">{{ t(ROLE_DESCRIPTIONS[role]) }}</span>
              </div>
            </el-option>
          </el-select>
          <div v-if="accountForm.role" class="form-hint">
            {{ t(ROLE_DESCRIPTIONS[accountForm.role as RoleName]) }}
          </div>
        </el-form-item>

        <!-- 企业版多租户：ADMIN/OPERATOR 配置"每租户一行角色"（P0-c）。USER/AUDITOR 不显示 -->
        <el-form-item
          v-if="tenantScopedEnabled && roleNeedsTenantScope"
          :label="t('accounts.tenantScope')"
        >
          <div class="role-binding-list">
            <div
              v-for="(binding, index) in accountForm.roleBindings"
              :key="index"
              class="role-binding-row"
            >
              <el-select
                v-model="binding.t"
                size="small"
                :placeholder="t('accounts.tenantScopeTenant')"
                style="width: 55%"
              >
                <el-option
                  v-for="opt in scopeTenantOptions"
                  :key="opt.tenantId"
                  :label="opt.displayName ? `${opt.displayName}（${opt.tenantId}）` : opt.tenantId"
                  :value="opt.tenantId"
                />
              </el-select>
              <el-select v-model="binding.r" size="small" style="width: 35%">
                <!-- 只允许管理员/运维两类管理作用域；USER 的租户归属由密钥自动关联，
                     不在此处配置（配置了也无消费方，纯属误导） -->
                <el-option :label="t('accounts.roleLabels.ADMIN')" value="ADMIN" />
                <el-option :label="t('accounts.roleLabels.OPERATOR')" value="OPERATOR" />
              </el-select>
              <el-button size="small" type="danger" text @click="removeRoleBinding(index)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <el-button size="small" @click="addRoleBinding" :disabled="accountForm.roleBindings.length >= scopeTenantOptions.length">
              <el-icon><Plus /></el-icon>{{ t('accounts.addTenantScope') }}
            </el-button>
            <div class="form-hint">{{ t('accounts.tenantScopeHint') }}</div>
          </div>
        </el-form-item>
        <!-- USER/AUDITOR 提示：归属由密钥自动关联 / 固定全局只读 -->
        <el-form-item
          v-else-if="tenantScopedEnabled && (accountForm.role === 'USER' || accountForm.role === 'AUDITOR')"
          :label="t('accounts.tenantScope')"
        >
          <div class="form-hint">
            {{ accountForm.role === 'USER' ? t('accounts.tenantScopeUserHint') : t('accounts.tenantScopeAuditorHint') }}
          </div>
        </el-form-item>

        <el-form-item :label="t('accounts.status')" prop="enabled">
          <el-switch v-model="accountForm.enabled" :active-text="t('accounts.enabled')" :inactive-text="t('accounts.disabled')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="showCreateDialog = false">{{ t('accounts.cancel') }}</el-button>
          <el-button type="primary" @click="submitAccount">{{ t('accounts.confirm') }}</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- 批量导入账号对话框（P1 员工激活包①） -->
    <BatchImportAccountsDialog
      v-model="showBatchImport"
      :existing-usernames="accounts.map(a => a.username)"
      @imported="refreshAccounts"
    />
  </PageSkeleton>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { confirmDanger } from '@/utils/dangerConfirm'
import { getApiKeys, deleteApiKey } from '@/api/apiKey'
import { getOrgScopeQuotas, deleteOrgScopeQuota } from '@/api/orgScopeQuota'
import request from '@/utils/request'
import type { FormInstance, FormRules } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { usePermission } from '@/composables/usePermission'
import { useTableMaxHeight } from '@/composables/useTableMaxHeight'
import { Plus, Refresh, Search, Edit, Delete, DocumentAdd, Odometer } from '@element-plus/icons-vue'
import OrgScopeQuotaDialog from '@/views/config/OrgScopeQuotaDialog.vue'
import BatchImportAccountsDialog from './BatchImportAccountsDialog.vue'
import { effectMessage } from '@/utils/effectScope'
import PageSkeleton from '@/components/PageSkeleton.vue'
import {
  getJwtAccounts,
  getJwtAccountDepartments,
  createJwtAccount,
  updateJwtAccount,
  deleteJwtAccount,
  toggleJwtAccountStatus
} from '@/api/account'
import type { JwtAccount, CreateJwtAccountRequest, RoleBinding, RoleValue } from '@/api/account'
import { useEnterpriseStore } from '@/stores/enterprise'
import { useTenantStore } from '@/stores/tenant'
/** 可选角色（社区版三档固定角色；企业版多租户额外可选 AUDITOR 审计员） */
const COMMUNITY_ROLES = ['ADMIN', 'OPERATOR', 'USER'] as const
type RoleName = 'ADMIN' | 'OPERATOR' | 'USER' | 'AUDITOR'

const enterpriseStore = useEnterpriseStore()
const tenantStore = useTenantStore()
/** 企业版且开通多租户：显示"每租户一行角色"作用域列表 */
const tenantScopedEnabled = computed(() =>
  enterpriseStore.enterpriseEdition && enterpriseStore.multiTenant && enterpriseStore.tenantManagement
)
/** 当前角色是否需要配置租户作用域（只有 ADMIN/OPERATOR 需要；USER/AUDITOR 不需要） */
const roleNeedsTenantScope = computed(() =>
  accountForm.role === 'ADMIN' || accountForm.role === 'OPERATOR'
)
/** 角色下拉选项：社区版三档逐字不变；企业版多租户追加 AUDITOR */
const ROLES = computed<RoleName[]>(() =>
  tenantScopedEnabled.value ? [...COMMUNITY_ROLES, 'AUDITOR'] : [...COMMUNITY_ROLES]
)

/** 角色说明 i18n key（文案位于 locales 的 permissions.json roleDescriptions 节点） */
const ROLE_DESCRIPTIONS: Record<RoleName, string> = {
  ADMIN: 'permissions.roleDescriptions.ADMIN',
  OPERATOR: 'permissions.roleDescriptions.OPERATOR',
  USER: 'permissions.roleDescriptions.USER',
  AUDITOR: 'permissions.roleDescriptions.AUDITOR'
}

const { t } = useI18n()
const { hasPermission } = usePermission()
const canWrite = hasPermission('system:accounts:manage')

// ── 用户配额编辑弹窗（2026-11-28）：scopeId=账号名（=ApiKey.holder，组织维用户额度键）
const quotaDialogVisible = ref(false)
const quotaTarget = ref('')
const openQuotaDialog = (account: { username: string }) => {
  quotaTarget.value = account.username
  quotaDialogVisible.value = true
}

/** 角色标签颜色：ADMIN 红 / OPERATOR 橙 / USER 蓝 / VIEWER 灰 */
const roleTagType = (role: string) =>
  role === 'ADMIN' ? 'danger'
    : role === 'OPERATOR' ? 'warning'
    : role === 'AUDITOR' ? 'info'
    : role === 'VIEWER' ? 'info' : 'primary'

// 数据状态
const accounts = ref<JwtAccount[]>([])
const loading = ref(false)

// 主列表表格内部滚动（自适应 max-height）
const accountsTableRef = ref()
const { maxHeight: accountsMaxHeight } = useTableMaxHeight(accountsTableRef)
const showCreateDialog = ref(false)
/** P1 员工激活包①：批量导入弹窗 */
const showBatchImport = ref(false)
const editingAccount = ref<JwtAccount | null>(null)
const searchKeyword = ref('')

// 表单数据（角色单选：一个账号一个固定角色，提交时包装为后端要求的数组）
const accountForm = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  department: '',
  name: '',
  role: '' as RoleName | '',
  /** 企业版多租户下 ADMIN/OPERATOR 的每租户角色绑定（P0-c） */
  roleBindings: [] as RoleBinding[],
  enabled: true
})

/** 租户下拉选项（作用域配置用），来自租户 store；社区版不会用到 */
const scopeTenantOptions = computed(() =>
  tenantStore.tenants.filter(t => !t.status || t.status === 'active')
)

/** 新增一行租户角色绑定 */
const addRoleBinding = () => {
  if (!scopeTenantOptions.value.length) return
  const used = new Set(accountForm.roleBindings.map(b => b.t))
  const next = scopeTenantOptions.value.find(t => !used.has(t.tenantId))
  // 默认取当前所选角色；本区域仅 ADMIN/OPERATOR 可用，兜底不落到 USER
  const fallback = accountForm.role === 'ADMIN' || accountForm.role === 'OPERATOR'
    ? accountForm.role : 'OPERATOR'
  accountForm.roleBindings.push({ t: next?.tenantId ?? '', r: fallback })
}

/** 删除一行角色绑定 */
const removeRoleBinding = (index: number) => {
  accountForm.roleBindings.splice(index, 1)
}

/** 编辑回填：存量账号理论上只有一个角色，历史多角色账号取权限最高的一档 */
const pickPrimaryRole = (roles?: RoleValue[]): RoleName | '' => {
  if (!roles || roles.length === 0) return ''
  // 展开新格式的 r 字段 + 旧格式字符串
  const names = roles.map(r => typeof r === 'string' ? r : r.r)
  for (const role of ROLES.value) {
    if (names.includes(role)) return role
  }
  return ''
}

/** 判断 roles 是否含某角色码（兼容旧字符串与新 {t,r}） */
const hasRoleCode = (roles: RoleValue[] | undefined, code: string): boolean =>
  !!roles?.some(r => (typeof r === 'string' ? r : r?.r) === code)

/** 列表展示：把 RoleValue[]（旧字符串 / 新 {t,r}）转成待渲染的标签 */
const roleDisplayList = (roles?: RoleValue[]): { label: string; type: string }[] => {
  if (!roles) return []
  return roles.map(r => {
    if (typeof r === 'string') {
      return { label: t(`accounts.roleLabels.${r}`), type: roleTagType(r) }
    }
    const tenantLabel = r.t === '*'
      ? t('accounts.tenantGlobal')
      : (tenantStore.tenants.find(x => x.tenantId === r.t)?.displayName || r.t)
    return { label: `${t(`accounts.roleLabels.${r.r}`)}@${tenantLabel}`, type: roleTagType(r.r) }
  })
}

/** 从 roles 中提取新格式的租户绑定（旧格式返回空） */
const pickRoleBindings = (roles?: RoleValue[]): RoleBinding[] => {
  if (!roles) return []
  return roles
    .filter((r): r is RoleBinding => typeof r !== 'string' && !!r && typeof r === 'object' && !!r.t && !!r.r)
    // 原样保留存量绑定的角色值，绝不在此处"纠正"——把 USER 改成 OPERATOR 等于静默提权。
    // 本区域下拉只提供 ADMIN/OPERATOR 两个选项；万一存量是 USER，Element Plus 会
    // 原样显示该值，用户可主动改成管理角色或删除该行。
    .map(b => ({ t: b.t, r: b.r }))
}

/** 部门下拉候选（2026-11-28 改源：稳定部门注册表，此前是账号 department 文本去重）。
 *  只允许从登记表选择（不允许自由文本落库），新部门经「新增部门」按钮先登记再选用——
 *  登记动作生成稳定 deptId，团队用量/成本归因的部门维度依赖它。 */
const departmentOptions = ref<string[]>([])

const loadDepartments = async () => {
  try {
    const res = await request.get('/security/group-roles/departments')
    const data = (res.data as { data?: unknown }).data
    const rows: Array<{ name?: string }> = Array.isArray(data)
      ? data
      : ((data as { items?: Array<{ name?: string }> })?.items ?? [])
    departmentOptions.value = rows
      .map(r => String(r.name ?? ''))
      .filter(Boolean)
      .sort((a, b) => a.localeCompare(b))
  } catch {
    // 注册表接口不可用（极老版本）→ 退回账号文本去重口径，保证下拉不空
    try {
      departmentOptions.value = await getJwtAccountDepartments()
    } catch {
      departmentOptions.value = []
    }
  }
}

/** 手动新增登记部门（OSS 无组织页，账号编辑旁就是 OSS 的部门管理入口）：
 *  先登记生成 deptId，再刷新候选并选中，杜绝未登记的自由文本。 */
const addDepartment = async () => {
  try {
    const { value } = await ElMessageBox.prompt(
      t('accounts.departmentAddHint'),
      t('accounts.departmentAdd'),
      { confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel'),
        inputPlaceholder: t('accounts.departmentNamePlaceholder'),
        inputValidator: (v: string) => (v && v.trim() ? true : t('accounts.departmentNameRequired')) }
    )
    const name = value.trim()
    await request.post('/security/group-roles/departments', { name })
    ElMessage.success(t('accounts.departmentAddOk', { name }))
    await loadDepartments()
    accountForm.department = name
  } catch {
    /* 取消或登记失败：失败时 request 拦截器已 toast */
  }
}

// 过滤后的账户列表
const filteredAccounts = computed(() => {
  if (!searchKeyword.value) return accounts.value
  const kw = searchKeyword.value.toLowerCase()
  return accounts.value.filter(a =>
    a.username.toLowerCase().includes(kw) ||
    (a.name && a.name.toLowerCase().includes(kw))
  )
})

// 格式化日期时间
const formatDateTime = (dateStr?: string) => {
  if (!dateStr) return '-'
  try {
    const date = new Date(dateStr)
    return date.toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    })
  } catch {
    return dateStr
  }
}

// 表单验证规则（动态计算）
const accountRules = computed<FormRules>(() => ({
  username: [
    { required: true, message: t('accounts.validation.usernameRequired'), trigger: 'blur' },
    { min: 3, max: 50, message: t('accounts.validation.usernameLength'), trigger: 'blur' }
  ],
  password: [
    { required: !editingAccount.value, message: t('accounts.validation.passwordRequired'), trigger: 'blur' },
    { min: 6, message: t('accounts.validation.passwordMinLength'), trigger: 'blur' }
  ],
  confirmPassword: [
    { required: !editingAccount.value, message: t('accounts.validation.confirmPasswordRequired'), trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (!editingAccount.value && value !== accountForm.password) {
          callback(new Error(t('accounts.validation.passwordMismatch')))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  role: [
    { required: true, message: t('accounts.validation.rolesRequired'), trigger: 'change' }
  ],
  department: [
    { required: !editingAccount.value, message: t('accounts.validation.departmentRequired'), trigger: 'change' }
  ]
}))

// 表单引用
const accountFormRef = ref<FormInstance | null>(null)

// 获取账户列表
const fetchAccounts = async () => {
  loading.value = true
  try {
    accounts.value = await getJwtAccounts()
  } catch (error) {
    console.error('获取账户列表失败:', error)
    ElMessage.error(t('accounts.messages.fetchFailed'))
  } finally {
    loading.value = false
  }
}

// 刷新账户列表
const refreshAccounts = () => {
  fetchAccounts()
}

// 提交账户（创建或更新）
const submitAccount = async () => {
  if (!accountFormRef.value) return

  await accountFormRef.value.validate(async (valid) => {
    if (valid) {
      try {
        // 角色值：企业版多租户 + 需作用域角色 → 新格式 [{t,r}]；否则旧格式 [role]（社区版逐字不变）
        const rolesValue: RoleValue[] = tenantScopedEnabled.value && roleNeedsTenantScope.value
          ? accountForm.roleBindings.map(b => ({ t: b.t, r: b.r }))
          : (accountForm.role ? [accountForm.role] : [])

        if (editingAccount.value) {
          // 更新账户 - 只传需要更新的字段
          const updateData: Partial<JwtAccount> = {
            roles: rolesValue,
            department: accountForm.department,
            name: accountForm.name,
            enabled: accountForm.enabled
          }
          // 只有填写了密码才更新
          if (accountForm.password && accountForm.password.trim()) {
            updateData.password = accountForm.password
          }
          await updateJwtAccount(accountForm.username, updateData as JwtAccount)
          // 角色变更的影响面跨副本：权限缓存 5 分钟，重登可立即刷新
          ElMessage.success(effectMessage(t('accounts.messages.updateSuccess'), 'permission'))
        } else {
          // 创建账户
          const createRequest: CreateJwtAccountRequest = {
            username: accountForm.username,
            password: accountForm.password,
            roles: rolesValue,
            department: accountForm.department,
            name: accountForm.name,
            enabled: accountForm.enabled
          }
          await createJwtAccount(createRequest)
          ElMessage.success(t('accounts.messages.createSuccess'))
        }

        showCreateDialog.value = false
        resetForm()
        fetchAccounts()
      } catch (error: any) {
        console.error('操作失败:', error)
        const errorMsg = error.response?.data?.message || (editingAccount.value ? t('accounts.messages.updateFailed') : t('accounts.messages.createFailed'))
        ElMessage.error(errorMsg)
      }
    }
  })
}

// 编辑账户
const editAccount = (account: JwtAccount) => {
  editingAccount.value = account
  accountForm.username = account.username
  accountForm.password = ''
  accountForm.confirmPassword = ''
  accountForm.role = pickPrimaryRole(account.roles)
  accountForm.roleBindings = pickRoleBindings(account.roles)
  accountForm.department = account.department || ''
  accountForm.name = account.name || ''
  accountForm.enabled = account.enabled
  showCreateDialog.value = true
}

// 处理状态变更（Switch 组件）
const handleStatusChange = async (account: JwtAccount) => {
  // 添加 loading 状态
  account.statusLoading = true
  const newStatus = account.enabled
  try {
    await toggleJwtAccountStatus(account.username, newStatus)
    ElMessage.success(newStatus ? t('accounts.messages.enabledSuccess') : t('accounts.messages.disabledSuccess'))
    fetchAccounts()
  } catch (error: any) {
    // 恢复原状态
    account.enabled = !newStatus
    console.error('状态切换失败:', error)
    ElMessage.error(t('accounts.messages.statusChangeFailed'))
  } finally {
    account.statusLoading = false
  }
}

// 删除账户（2026-11-28：删除前校验关联数据——绑定密钥 + 组织维用户额度配置，
// 有关联时明确提示影响面，用户确认后一并级联删除）
const deleteAccount = async (account: JwtAccount) => {
  // 禁止删除 admin 账户
  if (account.username === 'admin') {
    ElMessage.warning(t('accounts.messages.cannotDeleteAdmin'))
    return
  }

  // 1) 关联数据探测：该账号名下（holder=账号名）的密钥 + 用户额度配置
  let boundKeyIds: string[] = []
  let hasUserQuota = false
  try {
    const listVo = await getApiKeys()
    // ApiKeyListVO 的列表字段是 items；兼容旧 keys 字段与裸数组形态
    const keys = (listVo as { items?: Array<{ keyId?: string; holder?: string }> }).items
      ?? (listVo as { keys?: Array<{ keyId?: string; holder?: string }> }).keys
      ?? (Array.isArray(listVo) ? listVo : [])
    boundKeyIds = keys
      .filter(k => k.holder === account.username && k.keyId)
      .map(k => String(k.keyId))
  } catch {
    // 密钥列表拉不到（如权限形态差异）时不阻断删除主流程，仅不级联密钥
    boundKeyIds = []
  }
  try {
    const userQuotas = await getOrgScopeQuotas('users')
    hasUserQuota = userQuotas.some(r => String(r.scopeId) === account.username)
  } catch {
    hasUserQuota = false
  }
  const hasRelated = boundKeyIds.length > 0 || hasUserQuota

  // 2) 确认弹窗：有关联数据时列明影响面，确认即级联删除
  if (hasRelated) {
    const parts: string[] = []
    if (boundKeyIds.length > 0) parts.push(t('accounts.messages.relatedKeys', { count: boundKeyIds.length }))
    if (hasUserQuota) parts.push(t('accounts.messages.relatedQuota'))
    const confirmed = await confirmDanger({
      title: t('accounts.messages.deleteConfirmTitle'),
      target: account.username,
      impact: t('accounts.messages.relatedImpact', { detail: parts.join('、') }),
      remedy: t('accounts.messages.cascadeRemedy'),
      confirmText: t('accounts.messages.cascadeConfirm'),
      type: 'error'
    })
    if (!confirmed) return

    // 3) 级联清理：先删关联数据（逐项容错，单项失败不阻断其余与账号删除）
    let cascadeFailed = false
    for (const keyId of boundKeyIds) {
      try {
        await deleteApiKey(keyId)
      } catch (error) {
        cascadeFailed = true
        console.error('级联删除密钥失败:', keyId, error)
      }
    }
    if (hasUserQuota) {
      try {
        await deleteOrgScopeQuota('users', account.username)
      } catch (error) {
        cascadeFailed = true
        console.error('级联删除用户额度失败:', error)
      }
    }
    if (cascadeFailed) {
      ElMessage.warning(t('accounts.messages.cascadePartialFailed'))
    }
  } else {
    const confirmed = await confirmDanger({
      title: t('accounts.messages.deleteConfirmTitle'),
      target: account.username,
      type: 'warning'
    })
    if (!confirmed) return
  }

  try {
    await deleteJwtAccount(account.username)
    ElMessage.success(t('accounts.messages.deleteSuccess'))
    fetchAccounts()
  } catch (error: any) {
    console.error('删除失败:', error)
    const errorMsg = error.response?.data?.message || t('accounts.messages.deleteFailed')
    ElMessage.error(errorMsg)
  }
}

// 重置表单
const resetForm = () => {
  accountForm.username = ''
  accountForm.password = ''
  accountForm.confirmPassword = ''
  accountForm.department = ''
  accountForm.name = ''
  accountForm.role = ''
  accountForm.roleBindings = []
  accountForm.enabled = true
  editingAccount.value = null
  if (accountFormRef.value) {
    accountFormRef.value.resetFields()
  }
}

// 组件挂载时获取账户列表
// 打开创建/编辑弹窗时强制刷新租户选项：租户管理在企业子应用（独立 pinia）里，
// 创建/停用后宿主 store 依赖 rev 信号感知；此处兜底重拉，避免「添加租户」按钮
// 因陈旧空列表被误禁用（0 >= 0）
watch(showCreateDialog, async (open) => {
  if (open && tenantScopedEnabled.value) await tenantStore.ensureLoaded(true)
})

onMounted(async () => {
  await enterpriseStore.ensureLoaded()
  if (tenantScopedEnabled.value) {
    await tenantStore.ensureLoaded()
  }
  fetchAccounts()
  loadDepartments()
})
</script>

<style scoped>
.page-actions-row {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.dialog-footer {
  text-align: right;
}

.form-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--ja-text-secondary, var(--el-text-color-secondary));
}

/* 部门下拉 + 新增部门按钮（2026-11-28：只能选登记部门，新部门先登记再选用） */
.dept-select-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

/* 表格内弱化文本（如未分配部门） */
.cell-muted {
  font-size: 12px;
  color: var(--ja-text-secondary, var(--el-text-color-secondary));
}

/* 统计卡片样式 */
.el-statistic {
  text-align: center;
}

/* 企业版多租户：每租户一行角色绑定（P0-c） */
.role-binding-list {
  width: 100%;
}
.role-binding-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

/* 表格样式 */
.el-table {
  margin-top: 20px;
}

/* 角色标签样式 */
.el-tag {
  margin-right: 5px;
}

/* 确保表格操作按钮紧凑 */
.el-button-group .el-button {
  padding: 5px 10px;
}
</style>

<style>
/* 角色下拉选项：浮层挂 body 下（popper），需非 scoped；
   描述较长，允许换行并撑开选项高度 */
.role-option {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.role-option .el-tag {
  flex-shrink: 0;
  margin-top: 2px;
}

.role-option-desc {
  flex: 1;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
  white-space: normal;
}

.el-select-dropdown__item:has(.role-option) {
  height: auto;
  padding-top: 6px;
  padding-bottom: 6px;
  white-space: normal;
}
</style>