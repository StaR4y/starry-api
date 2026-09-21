<script setup lang="ts">
  import { onMounted, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import { ElMessage } from 'element-plus';
  import { Check, Refresh, Service } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { ticketApi, type TicketSettingsView, type TicketSettings } from '@/api/tickets';
  import { formatDateTime } from '@/utils/format';
  const router = useRouter();
  const snapshot = ref<TicketSettingsView>();
  const settings = ref<TicketSettings>({
    enabled: false,
    mode: 'SUMMARY',
    recipients: [],
    reminderMinutes: 240,
    consoleUrl: '',
  });
  const loading = ref(false);
  const saving = ref(false);
  const retrying = ref('');
  async function retry(id: string) {
    retrying.value = id;
    try {
      await ticketApi.retryNotification(id);
      ElMessage.success('已加入重试队列');
      snapshot.value = await ticketApi.settings();
    } catch {
      /* Shared HTTP client reports errors. */
    } finally {
      retrying.value = '';
    }
  }
  async function load() {
    loading.value = true;
    try {
      snapshot.value = await ticketApi.settings();
      settings.value = {
        ...snapshot.value.settings,
        recipients: [...snapshot.value.settings.recipients],
      };
    } catch {
      /* Shared HTTP client reports errors. */
    } finally {
      loading.value = false;
    }
  }
  async function save() {
    saving.value = true;
    try {
      snapshot.value = await ticketApi.saveSettings(settings.value);
      settings.value = { ...snapshot.value.settings };
      ElMessage.success('工单通知设置已保存');
    } catch {
      /* Retain unsaved settings. */
    } finally {
      saving.value = false;
    }
  }
  const reason = (value: string) =>
    ({
      SMTP_NOT_CONFIGURED: 'SMTP 未配置',
      NO_ADMIN_RECIPIENTS: '没有可用管理员邮箱',
      TICKET_EMAIL_FAILED: 'SMTP 发送失败',
      DELIVERY_FAILED: '发送超时或连接失败',
    })[value] ?? '发送失败';
  onMounted(load);
</script>

<template>
  <PageSection title="工单通知" subtitle="访问与通知" :icon="Service">
    <template #actions>
      <el-tag v-if="snapshot" :type="snapshot.settings.enabled ? 'success' : 'info'" effect="plain">
        {{ snapshot.settings.enabled ? '通知已开启' : '通知已关闭' }}
      </el-tag>
      <el-button
        :icon="Refresh"
        :loading="loading"
        :disabled="saving || !!retrying"
        aria-label="重新加载通知设置"
        @click="load"
      >
        刷新
      </el-button>
      <el-button
        type="primary"
        :icon="Check"
        :loading="saving"
        :disabled="loading || !snapshot || !!retrying"
        aria-label="保存设置"
        @click="save"
      >
        保存
      </el-button>
    </template>

    <el-skeleton v-if="loading && !snapshot" :rows="6" animated />
    <el-form
      v-else-if="snapshot"
      v-loading="loading"
      :model="settings"
      label-position="top"
      :disabled="saving || loading"
      class="ticket-form"
      @submit.prevent="save"
    >
      <section class="ticket-section">
        <h3>邮件通知</h3>
        <div class="ticket-grid">
          <el-form-item label="启用工单通知">
            <el-switch v-model="settings.enabled" aria-label="启用工单邮件通知" />
          </el-form-item>
          <el-form-item label="邮件内容">
            <el-radio-group v-model="settings.mode" class="ticket-mode" aria-label="邮件内容">
              <el-radio-button value="SUMMARY">仅待处理提醒</el-radio-button>
              <el-radio-button value="CONTENT">包含工单内容</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </div>
        <div v-if="!snapshot.smtpConfigured" class="ticket-notice">
          <span>尚未配置 SMTP</span>
          <el-button link type="primary" @click="router.push({ name: 'settings-smtp' })">
            前往配置
          </el-button>
        </div>
      </section>

      <section class="ticket-section">
        <h3>发送配置</h3>
        <div class="ticket-grid">
          <el-form-item label="收件管理员">
            <el-select
              v-model="settings.recipients"
              multiple
              clearable
              placeholder="全部已启用管理员的邮箱"
              aria-label="收件管理员"
            >
              <el-option
                v-for="email in snapshot.availableRecipients"
                :key="email"
                :label="email"
                :value="email"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="提醒间隔（分钟）">
            <el-input-number
              v-model="settings.reminderMinutes"
              :min="15"
              :max="10080"
              :step="15"
              controls-position="right"
              aria-label="提醒间隔（分钟）"
            />
          </el-form-item>
          <el-form-item label="控制台地址（邮件中的处理链接）" class="ticket-grid__full">
            <el-input
              v-model="settings.consoleUrl"
              placeholder="https://api.example.com"
              :maxlength="1000"
              aria-label="控制台地址"
            />
          </el-form-item>
        </div>
      </section>

      <section class="ticket-section">
        <h3>发送异常</h3>
        <p v-if="!snapshot.failures.length" class="ticket-empty">暂无发送异常</p>
        <div
          v-for="failure in snapshot.failures"
          :key="failure.ticketId"
          class="ticket-failure"
        >
          <div class="ticket-failure__content">
            <el-button
              link
              type="primary"
              class="ticket-failure__title"
              @click="router.push({ name: 'ticket-detail', params: { id: failure.ticketId } })"
            >
              {{ failure.title }}
            </el-button>
            <span>{{ reason(failure.reason) }} · 已尝试 {{ failure.attempts }} 次</span>
            <small>下次重试 {{ formatDateTime(failure.nextAttemptAt) }}</small>
          </div>
          <el-button
            :icon="Refresh"
            :loading="retrying === failure.ticketId"
            :disabled="!snapshot.settings.enabled || !!retrying || saving || loading"
            @click="retry(failure.ticketId)"
          >
            立即重试
          </el-button>
        </div>
      </section>
    </el-form>
    <el-empty v-else-if="!loading" description="通知设置加载失败">
      <el-button :icon="Refresh" @click="load">重新加载</el-button>
    </el-empty>
  </PageSection>
</template>

<style scoped lang="scss">
  .ticket-form {
    display: grid;
    gap: 22px;
  }

  .ticket-section {
    min-width: 0;

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

  .ticket-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;

    &__full {
      grid-column: 1 / -1;
    }

    :deep(.el-form-item) {
      min-width: 0;
      margin-bottom: 0;
    }

    :deep(.el-input-number),
    :deep(.el-select) {
      width: 100%;
    }
  }

  .ticket-mode {
    display: flex;
    flex-wrap: nowrap;
    width: 100%;
    max-width: 100%;

    :deep(.el-radio-button) {
      min-width: 0;
      flex: 1;
    }

    :deep(.el-radio-button__inner) {
      width: 100%;
      padding-inline: 12px;
      white-space: normal;
      line-height: 18px;
    }
  }

  .ticket-notice {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    margin-top: 14px;
    color: var(--el-color-warning);
    font-size: 13px;
  }

  .ticket-empty {
    margin: 0;
    color: var(--kp-text-muted);
    font-size: 13px;
  }

  .ticket-failure {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 16px;

    & + & {
      margin-top: 14px;
      padding-top: 14px;
      border-top: 1px solid var(--kp-divider);
    }

    &__content {
      display: grid;
      justify-items: start;
      min-width: 0;
      gap: 6px;
      overflow-wrap: anywhere;
      color: var(--kp-text-muted);
      font-size: 12px;
    }

    &__title {
      max-width: 100%;
      height: auto;
      line-height: 1.5;
      white-space: normal;
      text-align: left;
    }

    > .el-button {
      flex-shrink: 0;
    }
  }

  @media (max-width: 720px) {
    .ticket-grid {
      grid-template-columns: 1fr;
    }

    .ticket-failure {
      flex-direction: column;
      align-items: flex-start;
      gap: 10px;
    }
  }
</style>
