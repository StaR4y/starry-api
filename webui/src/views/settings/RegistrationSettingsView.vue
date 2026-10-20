<script setup lang="ts">
  import { t } from '@/i18n';
  /** 注册入口、邮箱策略、Turnstile 与邀请码管理。SMTP 凭据由独立设置页维护。 */
  import { onMounted, reactive, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { Check, Message, Plus, Postcard, Refresh } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { usersApi } from '@/api';
  import type { InviteCode } from '@/types/api';
  import { formatDateTime, formatNumber } from '@/utils/format';

  type InviteCodeTagType = 'success' | 'warning' | 'info' | 'primary' | 'danger';

  const router = useRouter();
  const loading = ref(false);
  const saving = ref(false);
  const inviteCodeLoading = ref(false);
  const inviteCodeCreating = ref(false);
  const inviteCodes = ref<InviteCode[]>([]);

  const form = reactive({
    registrationEnabled: false,
    emailVerifyEnabled: false,
    inviteCodeEnabled: false,
    registrationEmailSuffixWhitelist: [] as string[],
    turnstileEnabled: false,
    turnstileSiteKey: '',
    turnstileSecretKey: '',
    turnstileSecretConfigured: false,
  });

  const inviteCodeForm = reactive({ count: 1 });

  async function load() {
    loading.value = true;
    try {
      const [settings, codes] = await Promise.all([
        usersApi.registrationSettings(),
        usersApi.inviteCodes(),
      ]);
      form.registrationEnabled = settings.registrationEnabled;
      form.emailVerifyEnabled = settings.emailVerifyEnabled;
      form.inviteCodeEnabled = settings.inviteCodeEnabled;
      form.registrationEmailSuffixWhitelist = [...settings.registrationEmailSuffixWhitelist];
      form.turnstileEnabled = Boolean(settings.turnstileEnabled);
      form.turnstileSiteKey = settings.turnstileSiteKey ?? '';
      form.turnstileSecretKey = '';
      form.turnstileSecretConfigured = Boolean(settings.turnstileSecretConfigured);
      inviteCodes.value = codes;
    } finally {
      loading.value = false;
    }
  }

  async function save() {
    const turnstileSiteKey = form.turnstileSiteKey.trim();
    const turnstileSecretKey = form.turnstileSecretKey.trim();
    if (form.turnstileEnabled && !turnstileSiteKey) {
      ElMessage.warning(t('请填写 Cloudflare Turnstile Site Key'));
      return;
    }
    if (form.turnstileEnabled && !form.turnstileSecretConfigured && !turnstileSecretKey) {
      ElMessage.warning(t('请填写 Cloudflare Turnstile Secret Key'));
      return;
    }

    saving.value = true;
    try {
      const settings = await usersApi.updateRegistrationSettings({
        registrationEnabled: form.registrationEnabled,
        emailVerifyEnabled: form.emailVerifyEnabled,
        inviteCodeEnabled: form.inviteCodeEnabled,
        registrationEmailSuffixWhitelist: form.registrationEmailSuffixWhitelist,
        turnstileEnabled: form.turnstileEnabled,
        turnstileSiteKey,
        turnstileSecretKey: turnstileSecretKey || undefined,
      });
      form.turnstileSecretKey = '';
      form.turnstileSecretConfigured = Boolean(settings.turnstileSecretConfigured);
      ElMessage.success(t('注册设置已保存'));
    } finally {
      saving.value = false;
    }
  }

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      ElMessage.success(t('已复制'));
    } catch {
      ElMessage.warning(t('请手动复制'));
    }
  }

  async function createInviteCodes() {
    if (
      !Number.isInteger(inviteCodeForm.count) ||
      inviteCodeForm.count < 1 ||
      inviteCodeForm.count > 100
    ) {
      ElMessage.warning(t('邀请码生成数量需在 1 到 100 之间'));
      return;
    }
    inviteCodeCreating.value = true;
    try {
      const result = await usersApi.createInviteCodes({ count: inviteCodeForm.count });
      inviteCodes.value = [...result.codes, ...inviteCodes.value];
      await copy(result.codes.map((code) => code.code).join('\n'));
      ElMessage.success(t('已生成 {p0} 个邀请码', { p0: formatNumber(result.total) }));
    } finally {
      inviteCodeCreating.value = false;
    }
  }

  async function revokeInviteCode(row: InviteCode) {
    const confirmed = await ElMessageBox.confirm(
      t('确认停用邀请码 {p0}？', { p0: row.code }),
      t('停用邀请码'),
      {
        get confirmButtonText() {
          return t('停用');
        },
        get cancelButtonText() {
          return t('取消');
        },
        type: 'warning',
      },
    )
      .then(() => true)
      .catch(() => false);
    if (!confirmed) return;
    const updated = await usersApi.revokeInviteCode(row.codeId);
    inviteCodes.value = inviteCodes.value.map((item) =>
      item.codeId === updated.codeId ? updated : item,
    );
    ElMessage.success(t('邀请码已停用'));
  }

  function inviteCodeStatus(row: InviteCode): { label: string; type: InviteCodeTagType } {
    if (row.used)
      return {
        get label() {
          return t('已使用');
        },
        type: 'info',
      };
    if (!row.enabled)
      return {
        get label() {
          return t('已停用');
        },
        type: 'info',
      };
    if (row.expiresAt && new Date(row.expiresAt).getTime() <= Date.now()) {
      return {
        get label() {
          return t('已过期');
        },
        type: 'warning',
      };
    }
    return {
      get label() {
        return t('可用');
      },
      type: 'success',
    };
  }

  onMounted(load);
</script>

<template>
  <PageSection :title="t('注册设置')" :subtitle="t('访问与通知')" :icon="Postcard">
    <template #actions>
      <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('刷新') }}</el-button>
      <el-button type="primary" :icon="Check" :loading="saving" @click="save">
        {{
          t('保存')
        }}
      </el-button>
    </template>

    <el-skeleton v-if="loading" :rows="7" animated />
    <div v-else class="registration-settings">
      <section class="settings-section">
        <h3>{{ t('注册入口') }}</h3>
        <div class="setting-row">
          <div>
            <strong>{{ t('公开注册') }}</strong>
            <span>{{ t('允许外部用户自主创建普通用户账号') }}</span>
          </div>
          <el-switch v-model="form.registrationEnabled" />
        </div>
        <div class="setting-row">
          <div>
            <strong>{{ t('邮箱验证') }}</strong>
            <span>{{ t('注册时发送邮箱验证码或激活链接') }}</span>
          </div>
          <div class="setting-row__control">
            <el-button
              v-if="form.emailVerifyEnabled"
              link
              type="primary"
              :icon="Message"
              @click="router.push({ name: 'settings-smtp' })"
            >
              SMTP
            </el-button>
            <el-switch v-model="form.emailVerifyEnabled" />
          </div>
        </div>
        <label class="field-row">
          <span>
            <strong>{{ t('邮箱后缀白名单') }}</strong>
            <small>{{ t('留空允许所有邮箱域名') }}</small>
          </span>
          <el-select
            v-model="form.registrationEmailSuffixWhitelist"
            multiple
            filterable
            allow-create
            default-first-option
            :placeholder="t('例如 example.com')"
          />
        </label>
      </section>

      <section class="settings-section">
        <h3>{{ t('人机验证') }}</h3>
        <div class="setting-row">
          <div>
            <strong>Cloudflare Turnstile</strong>
            <span>{{ t('保护注册链接发送与直接注册请求') }}</span>
          </div>
          <el-switch v-model="form.turnstileEnabled" />
        </div>
        <div v-if="form.turnstileEnabled" class="field-grid">
          <label>
            <span>Site Key</span>
            <el-input v-model="form.turnstileSiteKey" placeholder="0x4AAAA..." />
          </label>
          <label>
            <span>Secret Key</span>
            <el-input
              v-model="form.turnstileSecretKey"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="
                form.turnstileSecretConfigured ? t('已配置，留空保持不变') : t('请输入 Secret Key')
              "
            />
          </label>
        </div>
      </section>

      <section class="settings-section">
        <div class="section-heading">
          <div>
            <h3>{{ t('邀请码') }}</h3>
            <span>{{ t('邀请码为一次性使用，注册成功后自动失效') }}</span>
          </div>
          <el-switch v-model="form.inviteCodeEnabled" />
        </div>

        <div class="invite-actions">
          <el-input-number
            v-model="inviteCodeForm.count"
            :min="1"
            :max="100"
            :precision="0"
            controls-position="right"
          />
          <el-button
            type="primary"
            :icon="Plus"
            :loading="inviteCodeCreating"
            @click="createInviteCodes"
          >
            {{ t('批量生成') }}
          </el-button>
        </div>

        <el-table v-loading="inviteCodeLoading" :data="inviteCodes" max-height="360" stripe>
          <el-table-column :label="t('邀请码')" min-width="210">
            <template #default="{ row }">
              <div class="code-cell">
                <code>{{ row.code }}</code>
                <el-button link type="primary" @click="copy(row.code)">{{ t('复制') }}</el-button>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('状态')" width="100">
            <template #default="{ row }">
              <el-tag :type="inviteCodeStatus(row).type" effect="light">
                {{ inviteCodeStatus(row).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('使用用户')" min-width="140">
            <template #default="{ row }">{{ row.usedByUserId || '—' }}</template>
          </el-table-column>
          <el-table-column :label="t('创建时间')" width="170">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column :label="t('操作')" width="80" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                type="warning"
                :disabled="row.used || !row.enabled"
                @click="revokeInviteCode(row)"
              >
                {{ t('停用') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>
  </PageSection>
</template>

<style scoped lang="scss">
  .registration-settings {
    display: grid;
    gap: 24px;
  }

  .settings-section {
    display: grid;
    gap: 14px;

    & + & {
      padding-top: 22px;
      border-top: 1px solid var(--kp-divider);
    }

    h3 {
      margin: 0;
      font-size: 15px;
      font-weight: 650;
      letter-spacing: 0;
    }
  }

  .setting-row,
  .field-row,
  .section-heading {
    min-height: 52px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 24px;

    > div:first-child,
    > span:first-child {
      display: grid;
      gap: 2px;
      min-width: 0;
    }

    strong {
      color: var(--kp-text);
      font-size: 14px;
      font-weight: 600;
    }

    span,
    small {
      color: var(--kp-text-muted);
      font-size: 12px;
    }
  }

  .setting-row__control,
  .invite-actions,
  .code-cell {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .field-row :deep(.el-select) {
    width: min(440px, 52%);
  }

  .field-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;

    label {
      display: grid;
      gap: 6px;
      color: var(--kp-text-secondary);
      font-size: 12px;
    }
  }

  .section-heading > div {
    display: grid;
    gap: 2px;
  }

  .invite-actions {
    justify-content: flex-end;
  }

  .code-cell code {
    word-break: break-all;
  }

  @media (max-width: 640px) {
    .setting-row,
    .field-row,
    .section-heading {
      align-items: stretch;
      flex-direction: column;
      gap: 10px;
    }

    .field-row :deep(.el-select),
    .field-grid {
      width: 100%;
      grid-template-columns: 1fr;
    }

    .setting-row__control,
    .section-heading,
    .invite-actions {
      justify-content: space-between;
    }
  }
</style>
