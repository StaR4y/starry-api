<script setup lang="ts">
  /**
   * 登录账号管理。
   *
   * 关键点：
   * - 创建/重置密码后返回的随机密码只展示一次，单独弹窗保留。
   * - PATCH /admin/users/{id} 仅更新 email / displayName / role / balance / enabled，禁止改用户名。
   */
  import { onMounted, reactive, ref } from 'vue';
  import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
  import PageSection from '@/components/PageSection.vue';
  import { usersApi } from '@/api';
  import type { AdminUser, AdminUserRole, CreateAdminUserRequest } from '@/types/api';
  import { formatDateTime, formatNumber } from '@/utils/format';

  const loading = ref(false);
  const users = ref<AdminUser[]>([]);
  const registrationSettingsVisible = ref(false);
  const registrationSettingsSubmitting = ref(false);
  const registrationSettings = reactive({
    registrationEnabled: false,
    emailVerifyEnabled: false,
    smtpHost: '',
    smtpPort: 587,
    smtpUsername: '',
    smtpPassword: '',
    smtpPasswordConfigured: false,
    smtpFromEmail: '',
    smtpFromName: '',
    smtpUseTls: false,
  });

  async function loadUsers() {
    loading.value = true;
    try {
      users.value = await usersApi.list();
    } finally {
      loading.value = false;
    }
  }

  /* ============= 新建 / 编辑 ============= */

  const formVisible = ref(false);
  const formMode = ref<'create' | 'edit'>('create');
  const formRef = ref<FormInstance>();
  const formSubmitting = ref(false);
  const editingId = ref<string | null>(null);

  const form = reactive<{
    username: string;
    email: string;
    displayName: string;
    role: AdminUserRole;
    balance: number | undefined;
    password: string;
    enabled: boolean;
  }>({
    username: '',
    email: '',
    displayName: '',
    role: 'USER',
    balance: 0,
    password: '',
    enabled: true,
  });

  const rules: FormRules = {
    username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
    email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  };

  function resetForm() {
    Object.assign(form, {
      username: '',
      email: '',
      displayName: '',
      role: 'USER',
      balance: 0,
      password: '',
      enabled: true,
    });
  }

  function openCreate() {
    formMode.value = 'create';
    editingId.value = null;
    resetForm();
    formVisible.value = true;
  }

  function openEdit(row: AdminUser) {
    formMode.value = 'edit';
    editingId.value = row.userId;
    Object.assign(form, {
      username: row.username,
      email: row.email ?? '',
      displayName: row.displayName ?? '',
      role: row.role,
      balance: row.balance ?? 0,
      password: '',
      enabled: row.enabled,
    });
    formVisible.value = true;
  }

  function roleLabel(role: string) {
    return role === 'ADMIN' ? '管理员' : '用户';
  }

  function roleTagType(role: string) {
    return role === 'ADMIN' ? 'danger' : 'success';
  }

  function balancePayload() {
    return form.role === 'USER' ? form.balance ?? 0 : undefined;
  }

  /* 一次性密码弹窗 */
  const credVisible = ref(false);
  const credInfo = ref<{ username: string; password: string } | null>(null);

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      ElMessage.success('已复制');
    } catch {
      ElMessage.warning('请手动复制');
    }
  }

  async function submitForm() {
    if (!formRef.value) return;
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return;

    formSubmitting.value = true;
    try {
      if (formMode.value === 'create') {
        const payload: CreateAdminUserRequest = {
          username: form.username,
          email: form.email || undefined,
          displayName: form.displayName || undefined,
          role: form.role || undefined,
          balance: balancePayload(),
          password: form.password || undefined,
        };
        const result = await usersApi.create(payload);
        formVisible.value = false;
        // password 仅在后端自动生成时返回
        if (result.password) {
          credInfo.value = { username: result.user.username, password: result.password };
          credVisible.value = true;
        } else {
          ElMessage.success('已创建');
        }
      } else if (editingId.value) {
        await usersApi.update(editingId.value, {
          email: form.email,
          displayName: form.displayName || undefined,
          role: form.role || undefined,
          balance: balancePayload(),
          enabled: form.enabled,
        });
        ElMessage.success('已更新');
        formVisible.value = false;
      }
      await loadUsers();
    } finally {
      formSubmitting.value = false;
    }
  }

  async function toggleEnabled(row: AdminUser) {
    await usersApi.update(row.userId, { enabled: !row.enabled });
    ElMessage.success(row.enabled ? '已停用' : '已启用');
    await loadUsers();
  }

  async function resetPassword(row: AdminUser) {
    await ElMessageBox.confirm(
      `确认重置 ${row.username} 的密码？将生成新随机密码。`,
      '重置密码',
      { confirmButtonText: '重置', cancelButtonText: '取消', type: 'warning' },
    ).catch(() => Promise.reject(new Error('cancel')));
    const result = await usersApi.resetPassword(row.userId);
    credInfo.value = { username: result.username, password: result.password };
    credVisible.value = true;
    await loadUsers();
  }

  async function loadRegistrationSettings() {
    const settings = await usersApi.registrationSettings();
    registrationSettings.registrationEnabled = settings.registrationEnabled;
    registrationSettings.emailVerifyEnabled = settings.emailVerifyEnabled;
    registrationSettings.smtpHost = settings.smtpHost ?? '';
    registrationSettings.smtpPort = settings.smtpPort ?? 587;
    registrationSettings.smtpUsername = settings.smtpUsername ?? '';
    registrationSettings.smtpPassword = '';
    registrationSettings.smtpPasswordConfigured = Boolean(settings.smtpPasswordConfigured);
    registrationSettings.smtpFromEmail = settings.smtpFromEmail ?? '';
    registrationSettings.smtpFromName = settings.smtpFromName ?? '';
    registrationSettings.smtpUseTls = Boolean(settings.smtpUseTls);
  }

  async function openRegistrationSettings() {
    await loadRegistrationSettings();
    registrationSettingsVisible.value = true;
  }

  async function submitRegistrationSettings() {
    registrationSettingsSubmitting.value = true;
    try {
      const settings = await usersApi.updateRegistrationSettings({
        registrationEnabled: registrationSettings.registrationEnabled,
        emailVerifyEnabled: registrationSettings.emailVerifyEnabled,
        registrationEmailSuffixWhitelist: [],
        smtpHost: registrationSettings.smtpHost,
        smtpPort: registrationSettings.smtpPort,
        smtpUsername: registrationSettings.smtpUsername,
        smtpPassword: registrationSettings.smtpPassword || undefined,
        smtpFromEmail: registrationSettings.smtpFromEmail,
        smtpFromName: registrationSettings.smtpFromName,
        smtpUseTls: registrationSettings.smtpUseTls,
      });
      registrationSettings.registrationEnabled = settings.registrationEnabled;
      registrationSettings.emailVerifyEnabled = settings.emailVerifyEnabled;
      registrationSettings.smtpHost = settings.smtpHost ?? '';
      registrationSettings.smtpPort = settings.smtpPort ?? 587;
      registrationSettings.smtpUsername = settings.smtpUsername ?? '';
      registrationSettings.smtpPassword = '';
      registrationSettings.smtpPasswordConfigured = Boolean(settings.smtpPasswordConfigured);
      registrationSettings.smtpFromEmail = settings.smtpFromEmail ?? '';
      registrationSettings.smtpFromName = settings.smtpFromName ?? '';
      registrationSettings.smtpUseTls = Boolean(settings.smtpUseTls);
      ElMessage.success('注册设置已更新');
      registrationSettingsVisible.value = false;
    } finally {
      registrationSettingsSubmitting.value = false;
    }
  }

  onMounted(() => {
    loadUsers();
    loadRegistrationSettings().catch(() => null);
  });
</script>

<template>
  <PageSection title="账号" subtitle="维护可登录控制台的管理员与普通用户账号">
    <template #actions>
      <el-button @click="openRegistrationSettings">注册设置</el-button>
      <el-button type="primary" @click="openCreate">添加账号</el-button>
    </template>

    <el-table v-loading="loading" :data="users" stripe>
      <el-table-column prop="username" label="用户名" min-width="160" />
      <el-table-column label="邮箱" min-width="220">
        <template #default="{ row }">{{ row.email || '—' }}</template>
      </el-table-column>
      <el-table-column prop="displayName" label="显示名" min-width="160">
        <template #default="{ row }">{{ row.displayName || '—' }}</template>
      </el-table-column>
      <el-table-column label="账号类型" width="120">
        <template #default="{ row }">
          <el-tag :type="roleTagType(row.role)" effect="light" round>
            {{ roleLabel(row.role) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="余额" width="140" align="right">
        <template #default="{ row }">
          <span v-if="row.role === 'USER'">{{ formatNumber(row.balance) }}</span>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" effect="light" round>
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="首次登录" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.firstLogin" type="warning" effect="light" round>需改密</el-tag>
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="最近登录" width="180">
        <template #default="{ row }">{{ formatDateTime(row.lastLoginAt) }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="toggleEnabled(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
          <el-button link type="warning" @click="resetPassword(row)">重置密码</el-button>
        </template>
      </el-table-column>
    </el-table>
  </PageSection>

  <el-dialog
    v-model="formVisible"
    :title="formMode === 'create' ? '添加账号' : '编辑账号'"
    width="480px"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="用户名" prop="username">
        <el-input v-model="form.username" :disabled="formMode === 'edit'" />
      </el-form-item>
      <el-form-item label="邮箱" prop="email">
        <el-input v-model="form.email" placeholder="user@example.com" />
      </el-form-item>
      <el-form-item label="显示名">
        <el-input v-model="form.displayName" />
      </el-form-item>
      <el-form-item label="账号类型">
        <el-select v-model="form.role" style="width: 100%">
          <el-option label="普通用户" value="USER" />
          <el-option label="管理员" value="ADMIN" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="form.role === 'USER'" label="账号余额">
        <el-input-number
          v-model="form.balance"
          :min="0"
          :precision="6"
          :step="10"
          controls-position="right"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item v-if="formMode === 'create'" label="初始密码（留空则后端生成）">
        <el-input v-model="form.password" show-password />
      </el-form-item>
      <el-form-item v-if="formMode === 'edit'" label="启用">
        <el-switch v-model="form.enabled" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="formVisible = false">取消</el-button>
      <el-button type="primary" :loading="formSubmitting" @click="submitForm">提交</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="registrationSettingsVisible" title="注册设置" width="520px">
    <div class="settings-minimal">
      <!-- 公开注册 Row -->
      <div class="setting-row">
        <div class="setting-info">
          <div class="setting-title">公开注册</div>
          <div class="setting-desc">允许外部用户自主创建账户并登录系统</div>
        </div>
        <div class="setting-control">
          <el-switch v-model="registrationSettings.registrationEnabled" />
        </div>
      </div>

      <!-- 安全提示 Banner (Minimal) -->
      <transition name="fade">
        <div v-if="registrationSettings.registrationEnabled" class="setting-warning">
          <div class="warning-text">
            <strong>安全提示：</strong>启用后将允许任何人注册。建议配置相应的网络防护与限频策略，防范恶意自动批量注册。
          </div>
        </div>
      </transition>

      <div class="setting-divider"></div>

      <!-- 注册邮箱验证 Row -->
      <div class="setting-row">
        <div class="setting-info">
          <div class="setting-title">注册邮箱验证</div>
          <div class="setting-desc">新账户需通过接收邮箱验证码或激活链接进行二次验证</div>
        </div>
        <div class="setting-control">
          <el-switch v-model="registrationSettings.emailVerifyEnabled" />
        </div>
      </div>

      <!-- SMTP 配置区域 (Minimal) -->
      <transition name="fade-slide">
        <div v-if="registrationSettings.emailVerifyEnabled" class="smtp-minimal-section">
          <div class="smtp-section-header">SMTP 发送服务配置</div>
          
          <div class="smtp-grid">
            <div class="smtp-row">
              <div class="smtp-col col-8">
                <label class="smtp-label">SMTP 主机</label>
                <el-input v-model="registrationSettings.smtpHost" placeholder="smtp.example.com" />
              </div>
              <div class="smtp-col col-4">
                <label class="smtp-label">SMTP 端口</label>
                <el-input-number
                  v-model="registrationSettings.smtpPort"
                  :min="1"
                  :max="65535"
                  controls-position="right"
                  style="width: 100%"
                />
              </div>
            </div>

            <div class="smtp-row mt-12">
              <div class="smtp-col col-6">
                <label class="smtp-label">SMTP 用户名</label>
                <el-input v-model="registrationSettings.smtpUsername" placeholder="user@example.com" />
              </div>
              <div class="smtp-col col-6">
                <label class="smtp-label">SMTP 密码</label>
                <el-input
                  v-model="registrationSettings.smtpPassword"
                  type="password"
                  show-password
                  :placeholder="registrationSettings.smtpPasswordConfigured ? '已配置，留空保持不变' : '请输入密码'"
                  autocomplete="new-password"
                />
              </div>
            </div>

            <div class="smtp-row mt-12">
              <div class="smtp-col col-6">
                <label class="smtp-label">发件邮箱</label>
                <el-input v-model="registrationSettings.smtpFromEmail" placeholder="noreply@example.com" />
              </div>
              <div class="smtp-col col-6">
                <label class="smtp-label">发件名称</label>
                <el-input v-model="registrationSettings.smtpFromName" placeholder="Starry API" />
              </div>
            </div>

            <div class="smtp-row mt-16 align-center">
              <div class="smtp-col col-12 flex-between" style="flex-direction: row; align-items: center;">
                <div class="switch-label-group">
                  <span class="switch-title">启用 TLS / STARTTLS 加密</span>
                  <span class="switch-subtitle">强制作出安全传输（SSL/TLS 端口一般为 465，STARTTLS 为 587）</span>
                </div>
                <el-switch v-model="registrationSettings.smtpUseTls" />
              </div>
            </div>
          </div>
        </div>
      </transition>
    </div>
    <template #footer>
      <el-button @click="registrationSettingsVisible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="registrationSettingsSubmitting"
        @click="submitRegistrationSettings"
      >
        保存
      </el-button>
    </template>
  </el-dialog>

  <!-- 一次性密码弹窗 -->
  <el-dialog v-model="credVisible" title="保存随机密码" width="480px" :close-on-click-modal="false">
    <el-alert type="warning" :closable="false" show-icon>
      此密码只会显示一次，请发给对应账号后立即关闭窗口。
    </el-alert>
    <div v-if="credInfo" class="cred-block glass-panel mt-16">
      <div>
        <div class="text-muted">用户名</div>
        <code class="mono">{{ credInfo.username }}</code>
      </div>
      <div>
        <div class="text-muted">密码</div>
        <code class="mono">{{ credInfo.password }}</code>
      </div>
      <el-button size="small" type="primary" @click="copy(credInfo.password)">复制密码</el-button>
    </div>
    <template #footer>
      <el-button type="primary" @click="credVisible = false">已记录</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
  .cred-block {
    padding: 12px 16px;
    display: flex;
    flex-direction: column;
    gap: 8px;

    code {
      display: inline-block;
      margin-top: 4px;
      word-break: break-all;
    }
  }

  .settings-minimal {
    padding: 4px 8px;
  }

  .setting-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 12px 0;
    gap: 24px;
  }

  .setting-info {
    flex: 1;
    min-width: 0;
  }

  .setting-title {
    font-size: 14.5px;
    font-weight: 550;
    color: var(--kp-text);
    margin-bottom: 2px;
  }

  .setting-desc {
    font-size: 12px;
    color: var(--kp-text-muted);
    line-height: 1.4;
  }

  .setting-control {
    flex-shrink: 0;
  }

  .setting-divider {
    height: 1px;
    background-color: var(--kp-divider);
    margin: 8px 0;
  }

  .setting-warning {
    margin: 4px 0 12px 0;
    padding: 10px 14px;
    background-color: rgba(240, 168, 77, 0.06);
    border-left: 3px solid var(--kp-warning);
    border-radius: 10px;
  }

  .warning-text {
    font-size: 12px;
    color: #a06619;
    line-height: 1.5;

    strong {
      font-weight: 600;
      color: #b56e07;
    }
  }

  .smtp-minimal-section {
    margin-top: 16px;
    padding-top: 16px;
    border-top: 1px solid var(--kp-divider);
  }

  .smtp-section-header {
    font-size: 13.5px;
    font-weight: 600;
    color: var(--kp-text-secondary);
    margin-bottom: 14px;
  }

  .smtp-grid {
    display: flex;
    flex-direction: column;
  }

  .smtp-row {
    display: flex;
    gap: 16px;
    width: 100%;

    &.align-center {
      align-items: center;
    }
  }

  .smtp-col {
    display: flex;
    flex-direction: column;
    gap: 6px;

    &.col-12 { flex: 0 0 100%; width: 100%; }
    &.col-8  { flex: 0 0 calc(66.666% - 8px); width: calc(66.666% - 8px); }
    &.col-6  { flex: 0 0 calc(50% - 8px); width: calc(50% - 8px); }
    &.col-4  { flex: 0 0 calc(33.333% - 8px); width: calc(33.333% - 8px); }
  }

  .smtp-label {
    font-size: 12px;
    color: var(--kp-text-secondary);
    padding-left: 2px;
  }

  .switch-label-group {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .switch-title {
    font-size: 12.5px;
    font-weight: 550;
    color: var(--kp-text);
  }

  .switch-subtitle {
    font-size: 11px;
    color: var(--kp-text-muted);
  }

  // Transitions
  .fade-enter-active,
  .fade-leave-active {
    transition: opacity 0.2s ease;
  }
  .fade-enter-from,
  .fade-leave-to {
    opacity: 0;
  }

  .fade-slide-enter-active,
  .fade-slide-leave-active {
    transition: all 0.25s ease;
  }
  .fade-slide-enter-from,
  .fade-slide-leave-to {
    opacity: 0;
    transform: translateY(-8px);
  }
</style>
