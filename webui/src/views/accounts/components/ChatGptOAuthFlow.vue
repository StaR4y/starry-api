<script setup lang="ts">
  import { t } from '@/i18n';
  import { computed, onBeforeUnmount, ref, watch } from 'vue';
  import { ElMessage } from 'element-plus';
  import {
    CircleCheckFilled,
    CopyDocument,
    Link,
    Loading,
    Refresh,
    WarningFilled,
  } from '@element-plus/icons-vue';
  import { accountsApi } from '@/api';
  import type {
    Account,
    ChatGptOAuthStartRequest,
    ChatGptOAuthStartResult,
    ChatGptOAuthStatusResult,
  } from '@/types/api';
  import { ApiError } from '@/utils/http';
  import ChatGptPrerequisite from './account-form/ChatGptPrerequisite.vue';
  import { emptyProxyLabel, proxyErrorMessage } from './account-form/chatGptProxy';
  import { useProxyCatalog } from './account-form/useProxyCatalog';

  const props = defineProps<{
    active: boolean;
    accountId?: string | null;
    /** 预选的出站代理节点（例如已在账号表单里选好）；空字符串表示不使用代理 */
    proxyChannelId?: string;
  }>();

  const emit = defineEmits<{
    (e: 'saved', account: Account): void;
    (e: 'update:proxyChannelId', value: string): void;
  }>();

  type Phase =
    | 'idle'
    | 'prerequisite'
    | 'starting'
    | 'waiting'
    | 'completing'
    | 'success'
    | 'error';

  const phase = ref<Phase>('idle');
  const startResult = ref<ChatGptOAuthStartResult | null>(null);
  const statusResult = ref<ChatGptOAuthStatusResult | null>(null);
  const errorMessage = ref('');
  const pollMessage = ref('');
  const proxyError = ref('');
  const selectedProxy = ref(props.proxyChannelId ?? '');
  /** 本次授权实际使用的出口，授权码页面上提示给管理员 */
  const usedProxyName = ref('');
  const proxyCatalog = useProxyCatalog();
  let timer: number | undefined;
  let generation = 0;

  watch(
    () => props.proxyChannelId,
    (value) => {
      selectedProxy.value = value ?? '';
    },
  );
  watch(selectedProxy, (value) => {
    proxyError.value = '';
    emit('update:proxyChannelId', value);
  });

  const isPolling = computed(() => phase.value === 'waiting' || phase.value === 'completing');

  function clearTimer() {
    if (timer !== undefined) {
      window.clearTimeout(timer);
      timer = undefined;
    }
  }

  function showPrerequisite() {
    ++generation;
    clearTimer();
    phase.value = 'prerequisite';
    startResult.value = null;
    statusResult.value = null;
    errorMessage.value = '';
    pollMessage.value = '';
    void proxyCatalog.load();
  }

  function selectedProxyName() {
    const node = proxyCatalog.catalog.value?.channels.find(
      (channel) => channel.channelId === selectedProxy.value,
    );
    return node?.name ?? emptyProxyLabel(props.accountId) ?? t('直连');
  }

  async function begin() {
    const currentGeneration = ++generation;
    clearTimer();
    phase.value = 'starting';
    startResult.value = null;
    statusResult.value = null;
    errorMessage.value = '';
    pollMessage.value = '';
    proxyError.value = '';
    usedProxyName.value = selectedProxyName();
    const payload: ChatGptOAuthStartRequest = {};
    if (props.accountId) payload.accountId = props.accountId;
    if (selectedProxy.value) payload.proxyChannelId = selectedProxy.value;
    try {
      const result = await accountsApi.startChatGptOAuth(payload);
      if (currentGeneration !== generation || !props.active) {
        await accountsApi.cancelChatGptOAuth(result.loginId).catch(() => undefined);
        return;
      }
      startResult.value = result;
      phase.value = 'waiting';
      schedulePoll(result.pollIntervalSeconds);
    } catch (error) {
      if (currentGeneration !== generation) return;
      const proxyMessage = proxyErrorMessage(error);
      if (proxyMessage) {
        // 节点问题回到选择页，让管理员直接改选
        showPrerequisite();
        proxyError.value = proxyMessage;
        return;
      }
      phase.value = 'error';
      errorMessage.value = error instanceof ApiError ? error.message : t('无法发起 ChatGPT 授权');
    }
  }

  function schedulePoll(seconds: number | null | undefined) {
    clearTimer();
    const delay = Math.max(1, seconds ?? startResult.value?.pollIntervalSeconds ?? 5);
    const currentGeneration = generation;
    timer = window.setTimeout(() => void poll(currentGeneration), delay * 1000);
  }

  async function poll(currentGeneration = generation) {
    const loginId = startResult.value?.loginId;
    if (!loginId || currentGeneration !== generation || !props.active) return;
    clearTimer();
    try {
      const result = await accountsApi.pollChatGptOAuth(loginId);
      if (currentGeneration !== generation || !props.active) return;
      statusResult.value = result;
      pollMessage.value = '';
      if (result.status === 'SUCCEEDED' && result.account) {
        phase.value = 'success';
        emit('saved', result.account);
        return;
      }
      if (result.status === 'PENDING') {
        phase.value = 'waiting';
        schedulePoll(result.retryAfterSeconds);
        return;
      }
      if (result.status === 'COMPLETING') {
        phase.value = 'completing';
        schedulePoll(1);
        return;
      }
      phase.value = 'error';
      errorMessage.value = result.message || t('授权未完成，请重新发起');
    } catch (error) {
      if (currentGeneration !== generation || !props.active) return;
      if (error instanceof ApiError && error.code === 'CHATGPT_OAUTH_LOGIN_NOT_FOUND') {
        phase.value = 'error';
        errorMessage.value = t('授权会话已过期，请重新发起');
        return;
      }
      pollMessage.value = t('状态检查暂时失败，将自动重试');
      schedulePoll(3);
    }
  }

  function openVerificationPage() {
    const value = startResult.value?.verificationUrl;
    if (!value) return;
    try {
      const url = new URL(value);
      if (url.protocol !== 'https:' || url.hostname !== 'auth.openai.com') {
        throw new Error('unexpected authorization host');
      }
      window.open(url.toString(), '_blank', 'noopener,noreferrer');
    } catch {
      ElMessage.error(t('授权地址校验失败'));
    }
  }

  async function copyCode() {
    const code = startResult.value?.userCode;
    if (!code) return;
    try {
      await navigator.clipboard.writeText(code);
      ElMessage.success(t('授权码已复制'));
    } catch {
      ElMessage.error(t('复制失败，请手动选择授权码'));
    }
  }

  async function cancelPending() {
    const loginId = startResult.value?.loginId;
    ++generation;
    clearTimer();
    if (loginId && phase.value === 'waiting') {
      await accountsApi.cancelChatGptOAuth(loginId).catch(() => undefined);
    }
  }

  watch(
    () => props.active,
    (active) => {
      if (active) {
        showPrerequisite();
      } else {
        void cancelPending();
        phase.value = 'idle';
      }
    },
    { immediate: true },
  );

  onBeforeUnmount(() => {
    void cancelPending();
  });
</script>

<template>
  <div class="chatgpt-oauth-flow">
    <ChatGptPrerequisite
      v-if="phase === 'prerequisite'"
      v-model="selectedProxy"
      :account-id="accountId"
      :catalog="proxyCatalog.catalog.value"
      :loading="proxyCatalog.loading.value"
      :failed="proxyCatalog.failed.value"
      :error="proxyError"
      @refresh="proxyCatalog.load()"
      @start="begin"
    />

    <div v-else-if="phase === 'starting'" class="oauth-loading" aria-live="polite">
      <el-icon class="is-loading" :size="30"><Loading /></el-icon>
      <span>{{ t('正在向 OpenAI 申请设备授权码') }}</span>
    </div>

    <div v-else-if="startResult && isPolling" class="oauth-flow">
      <div class="oauth-origins">
        <div class="oauth-origin">
          <span class="oauth-origin__label">{{ t('授权站点') }}</span>
          <strong>auth.openai.com</strong>
        </div>
        <div class="oauth-origin">
          <span class="oauth-origin__label">{{ t('出站代理') }}</span>
          <strong class="oauth-origin__plain">{{ usedProxyName }}</strong>
        </div>
      </div>

      <div class="oauth-browser-guide">
        <strong>{{ t('请在打开的 OpenAI 页面完成登录') }}</strong>
        <span>{{ t('如收到六位验证码，请先在该页面填写；登录后再输入下方设备授权码。') }}</span>
      </div>

      <span class="oauth-code-label">{{ t('设备授权码') }}</span>
      <button class="oauth-code" type="button" :title="t('复制授权码')" @click="copyCode">
        <span>{{ startResult.userCode }}</span>
        <el-icon :size="17"><CopyDocument /></el-icon>
      </button>

      <div class="oauth-actions">
        <el-button type="primary" @click="openVerificationPage">
          <template #icon>
            <el-icon>
              <Link />
            </el-icon>
          </template>
          {{ t('打开 OpenAI 授权页面') }}
        </el-button>
        <el-button :loading="phase === 'completing'" @click="poll()">
          <template #icon>
            <el-icon>
              <Refresh />
            </el-icon>
          </template>
          {{ t('检查授权状态') }}
        </el-button>
      </div>

      <div class="oauth-status" aria-live="polite">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>{{ statusResult?.message || pollMessage || t('等待完成浏览器授权') }}</span>
      </div>

      <el-alert
        type="warning"
        :closable="false"
        show-icon
        :title="
          t(
            '六位登录验证码与设备授权码不是同一个代码。两者都只应填写在 auth.openai.com；Starry API 不会要求你提交密码或六位验证码。',
          )
        "
      />
    </div>

    <div v-else-if="phase === 'success'" class="oauth-result oauth-result--success">
      <el-icon :size="38"><CircleCheckFilled /></el-icon>
      <h3>{{ statusResult?.message || t('授权完成') }}</h3>
      <p>{{ statusResult?.account?.email || statusResult?.account?.accountId }}</p>
    </div>

    <div v-else-if="phase === 'error'" class="oauth-result oauth-result--error">
      <el-icon :size="34"><WarningFilled /></el-icon>
      <h3>{{ t('授权未完成') }}</h3>
      <p>{{ errorMessage }}</p>
      <div class="oauth-result__actions">
        <el-button @click="showPrerequisite">{{ t('查看授权说明') }}</el-button>
        <el-button type="primary" plain @click="begin">{{ t('重新获取设备授权码') }}</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
  .chatgpt-oauth-flow {
    width: 100%;
    max-width: 520px;
    min-width: 0;
    margin: 0 auto;
  }

  .oauth-loading {
    min-height: 230px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 14px;
    color: var(--kp-text-secondary);
  }

  .oauth-flow {
    display: flex;
    flex-direction: column;
    gap: 18px;
  }

  .oauth-browser-guide {
    strong,
    span {
      display: block;
    }

    strong {
      margin-bottom: 5px;
      color: var(--kp-text);
      font-size: 14px;
    }

    span {
      color: var(--kp-text-secondary);
      font-size: 13px;
      line-height: 1.6;
    }
  }

  .oauth-code-label {
    margin-bottom: -10px;
    color: var(--kp-text-secondary);
    font-size: 12px;
    font-weight: 600;
  }

  .oauth-origins {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding-bottom: 12px;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  .oauth-origin {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;

    &__label {
      color: var(--kp-text-secondary);
      font-size: 13px;
    }

    strong {
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 13px;
      color: var(--kp-success);
    }

    strong.oauth-origin__plain {
      font-family: inherit;
      color: var(--kp-text);
      text-align: right;
      overflow-wrap: anywhere;
    }
  }

  .oauth-code {
    width: 100%;
    min-height: 74px;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 14px;
    border: 1px solid var(--el-border-color);
    border-radius: 6px;
    background: var(--el-fill-color-light);
    color: var(--el-text-color-primary);
    cursor: pointer;
    transition:
      border-color 160ms ease,
      background-color 160ms ease,
      transform 160ms ease;

    span {
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 30px;
      font-weight: 700;
      letter-spacing: 0;
      overflow-wrap: anywhere;
    }

    &:hover {
      border-color: var(--el-color-primary);
      background: var(--el-color-primary-light-9);
      transform: translateY(-1px);
    }
  }

  .oauth-actions {
    display: flex;
    justify-content: center;
    gap: 10px;
    flex-wrap: wrap;
  }

  .oauth-status {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    min-height: 24px;
    color: var(--kp-text-secondary);
    font-size: 13px;
  }

  .oauth-result {
    min-height: 230px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    text-align: center;

    h3 {
      margin: 12px 0 6px;
      font-size: 18px;
    }

    p {
      margin: 0 0 18px;
      color: var(--kp-text-secondary);
      overflow-wrap: anywhere;
    }

    &--success {
      color: var(--kp-success);
    }

    &--error > .el-icon {
      color: var(--kp-warning);
    }

    &__actions {
      display: flex;
      justify-content: center;
      gap: 10px;
      flex-wrap: wrap;
    }
  }

  @media (max-width: 560px) {
    .oauth-code span {
      font-size: 22px;
    }

    .oauth-actions {
      align-items: stretch;
      flex-direction: column;

      .el-button {
        margin: 0;
      }
    }

    .oauth-result__actions {
      width: 100%;
      flex-direction: column;

      .el-button {
        margin: 0;
      }
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .oauth-code {
      transition: none;
    }
  }
</style>
