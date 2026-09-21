<script setup lang="ts">
  /** 注册邮件 SMTP 发送服务配置。 */
  import { computed, onMounted, reactive, ref } from 'vue';
  import { ElMessage } from 'element-plus';
  import { Check, Message, Refresh } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { usersApi } from '@/api';
  import { useAuthStore } from '@/stores/auth';
  import type { SmtpSecurityMode, SmtpVerifyStatus } from '@/types/api';

  const auth = useAuthStore();
  const loading = ref(false);
  const saving = ref(false);
  const resending = ref(false);
  const passwordConfigured = ref(false);
  const verifyStatus = ref<SmtpVerifyStatus>('UNCONFIGURED');
  const verifySentTo = ref('');
  const verifyLastError = ref('');

  const form = reactive({
    smtpHost: '',
    smtpPort: 587,
    smtpUsername: '',
    smtpPassword: '',
    smtpFromEmail: '',
    smtpFromName: '',
    smtpSecurity: 'auto' as SmtpSecurityMode,
    smtpVerifyEnabled: true,
  });

  const securityOptions: Array<{ label: string; value: SmtpSecurityMode }> = [
    { label: '自动', value: 'auto' },
    { label: 'SSL / TLS', value: 'ssl' },
    { label: 'STARTTLS', value: 'starttls' },
    { label: '不加密', value: 'none' },
  ];

  function normalizeSecurity(
    value: string | null | undefined,
    legacyUseTls: boolean | null | undefined,
  ): SmtpSecurityMode {
    if (value === 'auto' || value === 'ssl' || value === 'starttls' || value === 'none') {
      return value;
    }
    return legacyUseTls ? 'auto' : 'none';
  }

  function apply(settings: Awaited<ReturnType<typeof usersApi.registrationSettings>>) {
    form.smtpHost = settings.smtpHost ?? '';
    form.smtpPort = settings.smtpPort ?? 587;
    form.smtpUsername = settings.smtpUsername ?? '';
    form.smtpPassword = '';
    form.smtpFromEmail = settings.smtpFromEmail ?? '';
    form.smtpFromName = settings.smtpFromName ?? '';
    form.smtpSecurity = normalizeSecurity(settings.smtpSecurity, settings.smtpUseTls);
    form.smtpVerifyEnabled = settings.smtpVerifyEnabled !== false;
    passwordConfigured.value = Boolean(settings.smtpPasswordConfigured);
    verifyStatus.value = settings.smtpVerifyStatus ?? 'UNCONFIGURED';
    verifySentTo.value = settings.smtpVerifySentTo ?? '';
    verifyLastError.value = settings.smtpVerifyLastError ?? '';
  }

  const verifyTagType = computed(() => {
    switch (verifyStatus.value) {
      case 'VERIFIED':
        return 'success';
      case 'PENDING':
        return 'warning';
      case 'FAILED':
        return 'danger';
      case 'SKIPPED':
        return 'info';
      default:
        return 'info';
    }
  });

  const verifyLabel = computed(() => {
    switch (verifyStatus.value) {
      case 'VERIFIED':
        return '已验证';
      case 'PENDING':
        return '待验证';
      case 'FAILED':
        return '发送失败';
      case 'SKIPPED':
        return '已跳过';
      default:
        return '未配置';
    }
  });

  const adminEmail = computed(() => auth.user?.email?.trim() || '');

  async function load() {
    loading.value = true;
    try {
      apply(await usersApi.registrationSettings());
    } finally {
      loading.value = false;
    }
  }

  function handleSecurityChange(value: SmtpSecurityMode) {
    if (value === 'ssl' && form.smtpPort === 587) form.smtpPort = 465;
    if (value === 'starttls' && form.smtpPort === 465) form.smtpPort = 587;
  }

  async function save(sendNow = false) {
    const busy = sendNow ? resending : saving;
    busy.value = true;
    try {
      const settings = await usersApi.updateRegistrationSettings({
        smtpHost: form.smtpHost.trim(),
        smtpPort: form.smtpPort,
        smtpUsername: form.smtpUsername.trim(),
        smtpPassword: form.smtpPassword.trim() || undefined,
        smtpFromEmail: form.smtpFromEmail.trim(),
        smtpFromName: form.smtpFromName.trim(),
        smtpUseTls: form.smtpSecurity !== 'none',
        smtpSecurity: form.smtpSecurity,
        smtpVerifyEnabled: form.smtpVerifyEnabled,
        smtpVerifySendNow: sendNow || undefined,
      });
      apply(settings);
      if (!form.smtpVerifyEnabled) {
        ElMessage.success('SMTP 设置已保存，已跳过验证步骤');
      } else if (settings.smtpVerifyStatus === 'VERIFIED') {
        ElMessage.success('SMTP 设置已保存');
      } else if (settings.smtpVerifyStatus === 'PENDING') {
        ElMessage.success(
          settings.smtpVerifySentTo
            ? `SMTP 设置已保存，验证邮件已发送至 ${settings.smtpVerifySentTo}`
            : 'SMTP 设置已保存，正在等待验证',
        );
      } else if (settings.smtpVerifyStatus === 'FAILED') {
        ElMessage.warning(settings.smtpVerifyLastError || '验证邮件发送失败');
      } else {
        ElMessage.success('SMTP 设置已保存');
      }
    } finally {
      busy.value = false;
    }
  }

  onMounted(load);
</script>

<template>
  <PageSection title="SMTP 设置" subtitle="访问与通知" :icon="Message">
    <template #actions>
      <el-tag :type="passwordConfigured ? 'success' : 'info'" effect="plain">
        {{ passwordConfigured ? '凭据已配置' : '凭据未配置' }}
      </el-tag>
      <el-tag :type="verifyTagType" effect="plain">{{ verifyLabel }}</el-tag>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Check" :loading="saving" @click="save()">保存</el-button>
    </template>

    <el-skeleton v-if="loading" :rows="6" animated />
    <el-form v-else :model="form" label-position="top" class="smtp-form">
      <section class="smtp-section">
        <h3>服务器</h3>
        <div class="smtp-grid smtp-grid--host">
          <el-form-item label="SMTP 主机">
            <el-input v-model="form.smtpHost" placeholder="smtp.example.com" />
          </el-form-item>
          <el-form-item label="端口">
            <el-input-number
              v-model="form.smtpPort"
              :min="1"
              :max="65535"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="加密方式">
            <el-select v-model="form.smtpSecurity" @change="handleSecurityChange">
              <el-option
                v-for="option in securityOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
        </div>
      </section>

      <section class="smtp-section">
        <h3>身份凭据</h3>
        <div class="smtp-grid">
          <el-form-item label="SMTP 用户名">
            <el-input
              v-model="form.smtpUsername"
              autocomplete="username"
              placeholder="user@example.com"
            />
          </el-form-item>
          <el-form-item label="SMTP 密码">
            <el-input
              v-model="form.smtpPassword"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="passwordConfigured ? '已配置，留空保持不变' : '请输入密码'"
            />
          </el-form-item>
        </div>
      </section>

      <section class="smtp-section">
        <h3>发件信息</h3>
        <div class="smtp-grid">
          <el-form-item label="发件邮箱">
            <el-input v-model="form.smtpFromEmail" placeholder="与 SMTP 用户名相同，如 user@company.com" />
            <p class="smtp-verify__hint">腾讯企业邮、QQ 邮箱要求发件邮箱与 SMTP 用户名完全一致。</p>
          </el-form-item>
          <el-form-item label="发件名称">
            <el-input v-model="form.smtpFromName" placeholder="Starry API" />
          </el-form-item>
        </div>
      </section>

      <section class="smtp-section">
        <h3>SMTP 验证</h3>
        <div class="smtp-verify">
          <el-switch v-model="form.smtpVerifyEnabled" />
          <p v-if="adminEmail" class="smtp-verify__hint">
            开启后，保存时会用上面的发件邮箱，向你的管理员邮箱 {{ adminEmail }} 发送验证链接。
            请打开该邮箱点链接，用来确认 SMTP 能发出信。关闭则跳过验证，保存即可使用 SMTP。
          </p>
          <p v-else class="smtp-verify__hint">
            验证信会发到当前登录管理员能打开的邮箱，不会发到发件邮箱。
            当前账号没有绑定邮箱，请先绑定管理员邮箱，或关闭此项后保存。
          </p>
          <p v-if="verifyStatus === 'PENDING' && verifySentTo" class="smtp-verify__hint">
            已发送至 {{ verifySentTo }}，请打开邮件中的链接完成验证。链接 30 分钟内有效。
          </p>
          <p v-if="verifyStatus === 'FAILED' && verifyLastError" class="smtp-verify__error">
            {{ verifyLastError }}
          </p>
          <el-button
            v-if="form.smtpVerifyEnabled && ['PENDING', 'FAILED'].includes(verifyStatus)"
            :loading="resending"
            @click="save(true)"
          >
            重新发送验证邮件
          </el-button>
        </div>
      </section>
    </el-form>
  </PageSection>
</template>

<style scoped lang="scss">
  .smtp-form {
    display: grid;
    gap: 22px;
  }

  .smtp-section {
    & + & {
      padding-top: 22px;
      border-top: 1px solid var(--kp-divider);
    }

    h3 {
      margin: 0 0 14px;
      font-size: 15px;
      font-weight: 650;
      letter-spacing: 0;
    }
  }

  .smtp-verify {
    display: grid;
    gap: 10px;
    justify-items: start;

    &__hint,
    &__error {
      margin: 0;
      font-size: 13px;
      line-height: 1.55;
    }

    &__hint {
      color: var(--kp-text-muted);
    }

    &__error {
      color: var(--el-color-danger);
    }
  }

  .smtp-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;

    &--host {
      grid-template-columns: minmax(240px, 1fr) 150px 190px;
    }

    :deep(.el-form-item) {
      margin-bottom: 0;
    }

    :deep(.el-input-number),
    :deep(.el-select) {
      width: 100%;
    }
  }

  @media (max-width: 720px) {
    .smtp-grid,
    .smtp-grid--host {
      grid-template-columns: 1fr;
    }
  }
</style>
