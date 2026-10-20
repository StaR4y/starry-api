<script setup lang="ts">
  import { t } from '@/i18n';
  /**
   * 登录账号管理。
   *
   * 关键点：
   * - 创建/重置密码后返回的随机密码只展示一次，单独弹窗保留。
   * - PATCH /admin/users/{id} 仅更新 email / displayName / role / balance / enabled，禁止改用户名。
   */
  import { onBeforeUnmount, onMounted, reactive, ref } from 'vue';
  import { onBeforeRouteLeave, useRouter } from 'vue-router';
  import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
  import { Plus, User } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { usersApi } from '@/api';
  import { useAuthStore } from '@/stores/auth';
  import type { AdminUser, AdminUserRole, CreateAdminUserRequest } from '@/types/api';
  import { formatDateTime, formatNumber } from '@/utils/format';

  const auth = useAuthStore();
  const router = useRouter();
  const loading = ref(false);
  const users = ref<AdminUser[]>([]);
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
  const editingRoot = ref(false);
  /** Balance shown when the edit form was opened; used to send the balance only when it was changed. */
  const originalBalance = ref(0);

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
    username: [
      {
        required: true,
        get message() {
          return t('请输入用户名');
        },
        trigger: 'blur',
      },
    ],
    email: [
      {
        type: 'email',
        get message() {
          return t('邮箱格式不正确');
        },
        trigger: 'blur',
      },
    ],
    password: [
      {
        validator: (_, value, callback) => {
          if (!value || (value.length >= 12 && value.length <= 128)) {
            callback();
            return;
          }
          callback(new Error(t('密码长度须为 12–128 位，或留空自动生成')));
        },
        trigger: 'blur',
      },
    ],
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
    editingRoot.value = false;
    resetForm();
    formVisible.value = true;
  }

  function openEdit(row: AdminUser) {
    if (!canManageAccount(row)) {
      ElMessage.warning(t('仅系统 Root 可操作此账号'));
      return;
    }
    formMode.value = 'edit';
    editingId.value = row.userId;
    editingRoot.value = isApplicationRoot(row);
    Object.assign(form, {
      username: row.username,
      email: row.email ?? '',
      displayName: row.displayName ?? '',
      role: row.role,
      balance: row.balance ?? 0,
      password: '',
      enabled: row.enabled,
    });
    originalBalance.value = row.balance ?? 0;
    formVisible.value = true;
  }

  function roleLabel(role: string) {
    return role === 'ADMIN' ? t('管理员') : t('用户');
  }

  function roleTagType(role: string) {
    return role === 'ADMIN' ? 'danger' : 'success';
  }

  function isApplicationRoot(row: AdminUser) {
    return row.username.trim().toLowerCase() === 'admin';
  }

  function isCurrentApplicationRoot(row: AdminUser) {
    const current = auth.user;
    if (!current?.userId || !current.username) return false;
    return (
      isApplicationRoot(row) &&
      current.userId === row.userId &&
      current.username.trim().toLowerCase() === 'admin'
    );
  }

  function canManageAccount(row: AdminUser) {
    return !isApplicationRoot(row) || isCurrentApplicationRoot(row);
  }

  function balancePayload() {
    return form.role === 'USER' ? (form.balance ?? 0) : undefined;
  }

  /* 一次性密码弹窗 */
  const credVisible = ref(false);
  const credInfo = ref<{ username: string; password: string } | null>(null);
  const credentialAcknowledged = ref(false);
  const logoutAfterCredentialClose = ref(false);

  function handleCredentialClosed() {
    const shouldLogout = logoutAfterCredentialClose.value;
    logoutAfterCredentialClose.value = false;
    credentialAcknowledged.value = false;
    credInfo.value = null;
    if (!shouldLogout) return;
    auth.logout();
    ElMessage.info(t('当前账号的全部后台会话已撤销，请使用新密码重新登录'));
    router.replace({ name: 'login' });
  }

  function closeCredentialDialog() {
    if (!credentialAcknowledged.value) {
      ElMessage.warning(t('请先确认一次性密码已妥善保存'));
      return;
    }
    credVisible.value = false;
  }

  function hasUnacknowledgedCredential() {
    return Boolean(credVisible.value && credInfo.value && !credentialAcknowledged.value);
  }

  function handleCredentialBeforeUnload(event: BeforeUnloadEvent) {
    if (!hasUnacknowledgedCredential()) return;
    event.preventDefault();
    event.returnValue = '';
  }

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      ElMessage.success(t('已复制'));
    } catch {
      ElMessage.warning(t('请手动复制'));
    }
  }

  function affiliateRegisterUrl(code?: string | null) {
    if (!code) return '';
    return `${window.location.origin}${window.location.pathname}#/register?aff=${encodeURIComponent(code)}`;
  }

  async function copyAffiliateLink(row: AdminUser) {
    if (!row.affiliateCode) {
      ElMessage.warning(t('该账号暂无推广码'));
      return;
    }
    await copy(affiliateRegisterUrl(row.affiliateCode));
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
          logoutAfterCredentialClose.value = false;
          credentialAcknowledged.value = false;
          credInfo.value = { username: result.user.username, password: result.password };
          credVisible.value = true;
        } else {
          ElMessage.success(t('已创建'));
        }
      } else if (editingId.value) {
        // Sending the unchanged balance back would overwrite credits added or reserved since the form opened.
        const balanceEdited = form.role === 'USER' && Number(form.balance ?? 0) !== Number(originalBalance.value);
        const payload = {
          email: form.email,
          displayName: form.displayName || undefined,
          ...(balanceEdited
            ? { balance: balancePayload(), expectedBalance: originalBalance.value }
            : {}),
          ...(editingRoot.value
            ? {}
            : {
                role: form.role || undefined,
                enabled: form.enabled,
              }),
        };
        await usersApi.update(editingId.value, payload);
        ElMessage.success(t('已更新'));
        formVisible.value = false;
      }
      await loadUsers();
    } finally {
      formSubmitting.value = false;
    }
  }

  async function toggleEnabled(row: AdminUser) {
    if (isApplicationRoot(row)) {
      ElMessage.warning(t('最高管理员权限 不能被停用'));
      return;
    }
    await usersApi.update(row.userId, { enabled: !row.enabled });
    ElMessage.success(row.enabled ? t('已停用') : t('已启用'));
    await loadUsers();
  }

  async function resetPassword(row: AdminUser) {
    if (!canManageAccount(row)) {
      ElMessage.warning(t('仅最高管理员 可重置此账号密码'));
      return;
    }
    await ElMessageBox.confirm(
      t('确认重置 {p0} 的密码？将生成新随机密码。', { p0: row.username }),
      t('重置密码'),
      {
        get confirmButtonText() {
          return t('重置');
        },
        get cancelButtonText() {
          return t('取消');
        },
        type: 'warning',
      },
    ).catch(() => Promise.reject(new Error('cancel')));
    const result = await usersApi.resetPassword(row.userId);
    const resetsCurrentSession = auth.user?.userId === row.userId;
    logoutAfterCredentialClose.value = resetsCurrentSession;
    credentialAcknowledged.value = false;
    credInfo.value = { username: result.username, password: result.password };
    credVisible.value = true;
    if (!resetsCurrentSession) {
      await loadUsers();
    }
  }

  onMounted(() => {
    window.addEventListener('beforeunload', handleCredentialBeforeUnload);
    loadUsers();
  });

  onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', handleCredentialBeforeUnload);
  });

  onBeforeRouteLeave(() => {
    if (!hasUnacknowledgedCredential()) return true;
    return window.confirm(t('一次性密码尚未确认保存。确定离开当前页面？'));
  });
</script>

<template>
  <div class="user-list-view">
    <PageSection :title="t('账号')" :subtitle="t('身份与权限')" :icon="User">
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="openCreate">{{ t('添加账号') }}</el-button>
      </template>

      <el-table v-loading="loading" :data="users">
        <el-table-column :label="t('用户名')" min-width="180">
          <template #default="{ row }">
            <div class="account-identity">
              <span>{{ row.username }}</span>
              <el-tag v-if="isApplicationRoot(row)" type="danger" effect="plain" size="small">
                Root
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('推广码')" min-width="170">
          <template #default="{ row }">
            <div v-if="row.affiliateCode" class="invite-code-cell">
              <span class="mono-code">{{ row.affiliateCode }}</span>
              <el-button link type="primary" size="small" @click.stop="copyAffiliateLink(row)">
                {{ t('复制链接') }}
              </el-button>
            </div>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('邮箱')" min-width="220">
          <template #default="{ row }">{{ row.email || '—' }}</template>
        </el-table-column>
        <el-table-column prop="displayName" :label="t('显示名')" min-width="160">
          <template #default="{ row }">{{ row.displayName || '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('账号类型')" width="120">
          <template #default="{ row }">
            <el-tag :type="roleTagType(row.role)" effect="light" round>
              {{ roleLabel(row.role) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('余额')" width="140" align="right">
          <template #default="{ row }">
            <span v-if="row.role === 'USER'">{{ formatNumber(row.balance) }}</span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('启用')" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" effect="light" round>
              {{ row.enabled ? t('启用') : t('停用') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('首次登录')" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.firstLogin" type="warning" effect="light" round>
              {{
                t('需改密')
              }}
            </el-tag>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('最近登录')" width="180">
          <template #default="{ row }">{{ formatDateTime(row.lastLoginAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('创建时间')" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('操作')" width="240" fixed="right">
          <template #default="{ row }">
            <template v-if="canManageAccount(row)">
              <el-button link type="primary" @click="openEdit(row)">{{ t('编辑') }}</el-button>
              <el-button
                v-if="!isApplicationRoot(row)"
                link
                type="primary"
                @click="toggleEnabled(row)"
              >
                {{ row.enabled ? t('停用') : t('启用') }}
              </el-button>
              <el-button link type="warning" @click="resetPassword(row)">
                {{
                  t('重置密码')
                }}
              </el-button>
            </template>
            <span v-else class="text-muted">{{ t('Root 保护') }}</span>
          </template>
        </el-table-column>
      </el-table>
    </PageSection>

    <el-dialog
      v-model="formVisible"
      :title="formMode === 'create' ? t('添加账号') : t('编辑账号')"
      width="min(480px, calc(100vw - 32px))"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item :label="t('用户名')" prop="username">
          <el-input v-model="form.username" :disabled="formMode === 'edit'" />
        </el-form-item>
        <el-form-item :label="t('邮箱')" prop="email">
          <el-input v-model="form.email" placeholder="user@example.com" />
        </el-form-item>
        <el-form-item :label="t('显示名')">
          <el-input v-model="form.displayName" />
        </el-form-item>
        <el-form-item :label="t('账号类型')">
          <el-select v-model="form.role" :disabled="editingRoot" style="width: 100%">
            <el-option :label="t('普通用户')" value="USER" />
            <el-option :label="t('管理员')" value="ADMIN" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.role === 'USER'" :label="t('账号余额')">
          <el-input-number
            v-model="form.balance"
            :min="0"
            :precision="6"
            :step="10"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item
          v-if="formMode === 'create'"
          :label="t('初始密码（留空则后端生成）')"
          prop="password"
        >
          <el-input v-model="form.password" type="password" maxlength="128" show-password />
        </el-form-item>
        <el-form-item v-if="formMode === 'edit' && !editingRoot" :label="t('启用')">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">{{ t('取消') }}</el-button>
        <el-button type="primary" :loading="formSubmitting" @click="submitForm">
          {{
            t('提交')
          }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 一次性密码弹窗 -->
    <el-dialog
      v-model="credVisible"
      :title="t('保存随机密码')"
      width="min(480px, calc(100vw - 32px))"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
      @closed="handleCredentialClosed"
    >
      <el-alert
        :title="
          logoutAfterCredentialClose
            ? t('Root 的全部后台会话已撤销；关闭后必须使用此新密码重新登录。')
            : t('此密码只会显示一次，请立即妥善保存或安全交付。')
        "
        type="warning"
        :closable="false"
        show-icon
      />
      <div v-if="credInfo" class="cred-block glass-panel mt-16">
        <div>
          <div class="text-muted">{{ t('用户名') }}</div>
          <code class="mono">{{ credInfo.username }}</code>
        </div>
        <div>
          <div class="text-muted">{{ t('密码') }}</div>
          <code class="mono">{{ credInfo.password }}</code>
        </div>
        <el-button size="small" type="primary" @click="copy(credInfo.password)">
          {{
            t('复制密码')
          }}
        </el-button>
      </div>
      <el-checkbox v-model="credentialAcknowledged" class="mt-16">
        {{ t('我已将此一次性密码保存到受控密码管理器或完成安全交付，知悉关闭后无法再次查看') }}
      </el-checkbox>
      <template #footer>
        <el-button
          type="primary"
          :disabled="!credentialAcknowledged"
          @click="closeCredentialDialog"
        >
          {{ t('已妥善保存') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
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

  .account-identity {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;

    > span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .invite-code-cell {
    display: flex;
    align-items: center;
    gap: 8px;
  }
</style>
