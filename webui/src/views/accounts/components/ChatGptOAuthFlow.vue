<script setup lang="ts">
  import { t } from '@/i18n';
  import { computed, onBeforeUnmount, ref, watch } from 'vue';
  import { ElMessage } from 'element-plus';
  import {
    CircleCheckFilled,
    CopyDocument,
    Link,
    Loading,
    Lock,
    Refresh,
    WarningFilled,
  } from '@element-plus/icons-vue';
  import { accountsApi } from '@/api';
  import type { Account, ChatGptOAuthStartResult, ChatGptOAuthStatusResult } from '@/types/api';
  import { ApiError } from '@/utils/http';

  const props = defineProps<{
    active: boolean;
    accountId?: string | null;
  }>();

  const emit = defineEmits<{
    (e: 'saved', account: Account): void;
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
  let timer: number | undefined;
  let generation = 0;

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
  }

  async function begin() {
    const currentGeneration = ++generation;
    clearTimer();
    phase.value = 'starting';
    startResult.value = null;
    statusResult.value = null;
    errorMessage.value = '';
    pollMessage.value = '';
    try {
      const result = await accountsApi.startChatGptOAuth(
        props.accountId ? { accountId: props.accountId } : {},
      );
      if (currentGeneration !== generation || !props.active) {
        await accountsApi.cancelChatGptOAuth(result.loginId).catch(() => undefined);
        return;
      }
      startResult.value = result;
      phase.value = 'waiting';
      schedulePoll(result.pollIntervalSeconds);
    } catch (error) {
      if (currentGeneration !== generation) return;
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
    <div v-if="phase === 'prerequisite'" class="oauth-prerequisite">
      <el-icon class="oauth-prerequisite__icon" :size="32"><Lock /></el-icon>
      <h3>{{ t('通过 OpenAI 官方页面登录') }}</h3>
      <p>
        {{
          t(
            'Starry API 使用 Codex 设备授权连接 ChatGPT。账号登录、密码和六位验证码都只在 auth.openai.com 完成。',
          )
        }}
      </p>
      <ol class="oauth-guide">
        <li>
          <strong>{{ t('登录 ChatGPT') }}</strong>
          <span>{{
            t('若 OpenAI 向邮箱发送六位验证码，请在随后打开的 OpenAI 官方页面填写。')
          }}</span>
        </li>
        <li>
          <strong>{{ t('批准设备授权') }}</strong>
          <span>{{ t('登录完成后，再输入本页面生成的设备授权码并确认授权。') }}</span>
        </li>
      </ol>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        :title="
          t(
            '开始前，请在 ChatGPT「设置 → 安全」中开启 Codex 设备代码授权；工作区账号需由管理员开启。',
          )
        "
      />
      <div class="oauth-prerequisite__actions">
        <el-link
          href="https://learn.chatgpt.com/docs/auth#mainContent"
          target="_blank"
          rel="noopener noreferrer"
        >
          {{ t('查看 OpenAI 官方说明') }}
        </el-link>
        <el-button type="primary" @click="begin">{{ t('已开启，获取设备授权码') }}</el-button>
      </div>
    </div>

    <div v-else-if="phase === 'starting'" class="oauth-loading" aria-live="polite">
      <el-icon class="is-loading" :size="30"><Loading /></el-icon>
      <span>{{ t('正在向 OpenAI 申请设备授权码') }}</span>
    </div>

    <div v-else-if="startResult && isPolling" class="oauth-flow">
      <div class="oauth-origin">
        <span class="oauth-origin__label">{{ t('授权站点') }}</span>
        <strong>auth.openai.com</strong>
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

  .oauth-prerequisite {
    min-height: 260px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    text-align: center;

    &__icon {
      color: var(--kp-primary);
    }

    h3 {
      margin: 14px 0 8px;
      color: var(--kp-text);
      font-size: 18px;
    }

    > p {
      max-width: 480px;
      margin: 0 0 16px;
      color: var(--kp-text-secondary);
      font-size: 14px;
      line-height: 1.7;
    }

    .el-alert {
      text-align: left;
    }

    &__actions {
      width: 100%;
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 16px;
      margin-top: 18px;
    }
  }

  .oauth-guide {
    width: 100%;
    margin: 0 0 18px;
    padding: 0;
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 18px;
    counter-reset: oauth-step;
    list-style: none;
    text-align: left;

    li {
      position: relative;
      min-width: 0;
      padding-left: 34px;
      counter-increment: oauth-step;

      &::before {
        content: counter(oauth-step);
        position: absolute;
        top: 1px;
        left: 0;
        width: 23px;
        height: 23px;
        display: grid;
        place-items: center;
        border: 1px solid var(--el-color-primary-light-5);
        border-radius: 50%;
        color: var(--el-color-primary);
        font-size: 12px;
        font-weight: 700;
      }
    }

    strong,
    span {
      display: block;
    }

    strong {
      margin-bottom: 4px;
      color: var(--kp-text);
      font-size: 14px;
    }

    span {
      color: var(--kp-text-secondary);
      font-size: 13px;
      line-height: 1.55;
    }
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

  .oauth-origin {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding-bottom: 12px;
    border-bottom: 1px solid var(--el-border-color-lighter);

    &__label {
      color: var(--kp-text-secondary);
      font-size: 13px;
    }

    strong {
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 13px;
      color: var(--kp-success);
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
    .oauth-prerequisite {
      min-height: 300px;

      &__actions {
        align-items: stretch;
        flex-direction: column-reverse;
        gap: 12px;
      }
    }

    .oauth-guide {
      grid-template-columns: 1fr;
      gap: 14px;
    }

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
