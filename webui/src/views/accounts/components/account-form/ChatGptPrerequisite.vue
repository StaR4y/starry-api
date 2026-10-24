<script setup lang="ts">
  /**
   * ChatGPT 授权的开始页：开启设备授权的说明，以及登录要使用的出站代理节点。
   */
  import { computed, nextTick, onMounted, ref, watch } from 'vue';
  import { Lock } from '@element-plus/icons-vue';
  import { t } from '@/i18n';
  import type { ProxyCatalog } from '@/api/proxyChannels';
  import ProxyChannelSelect from '../ProxyChannelSelect.vue';
  import { emptyProxyLabel } from './chatGptProxy';

  const props = defineProps<{
    modelValue: string;
    /** 重新授权已有账号时的账号 ID */
    accountId?: string | null;
    catalog: ProxyCatalog | null;
    loading: boolean;
    failed: boolean;
    /** 上一次发起授权时因所选节点而失败的说明 */
    error: string;
  }>();

  const emit = defineEmits<{
    (e: 'update:modelValue', value: string): void;
    (e: 'refresh'): void;
    (e: 'start'): void;
  }>();

  const proxyBlock = ref<HTMLElement>();
  const hint = computed(() =>
    props.accountId
      ? t('未选择时沿用该账号已绑定的节点；选择后，登录与账号都改用所选节点。')
      : t('登录请求和授权后生成的账号都会使用所选节点；不选择则直连。'),
  );

  /** 失败说明出现在节点选择下方，必要时滚动到可见位置 */
  function revealError() {
    if (props.error) void nextTick(() => proxyBlock.value?.scrollIntoView({ block: 'nearest' }));
  }
  watch(() => props.error, revealError);
  onMounted(revealError);
</script>

<template>
  <div class="oauth-prerequisite">
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
        <span>{{ t('若 OpenAI 向邮箱发送六位验证码，请在随后打开的 OpenAI 官方页面填写。') }}</span>
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
    <div ref="proxyBlock" class="oauth-proxy">
      <span class="oauth-proxy__label">{{ t('出站代理') }}</span>
      <ProxyChannelSelect
        :model-value="modelValue"
        :catalog="catalog"
        :loading="loading"
        :failed="failed"
        :empty-label="emptyProxyLabel(accountId)"
        @update:model-value="(value: string) => emit('update:modelValue', value)"
        @refresh="emit('refresh')"
      />
      <p class="oauth-proxy__hint">{{ hint }}</p>
      <el-alert v-if="error" type="error" show-icon :closable="false" :title="error" />
    </div>
    <div class="oauth-prerequisite__actions">
      <el-link
        href="https://learn.chatgpt.com/docs/auth#mainContent"
        target="_blank"
        rel="noopener noreferrer"
      >
        {{ t('查看 OpenAI 官方说明') }}
      </el-link>
      <el-button type="primary" @click="emit('start')">{{ t('已开启，获取设备授权码') }}</el-button>
    </div>
  </div>
</template>

<style scoped lang="scss">
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

  .oauth-proxy {
    width: 100%;
    margin-top: 16px;
    text-align: left;

    &__label {
      display: block;
      margin-bottom: 6px;
      color: var(--kp-text-secondary);
      font-size: 13px;
      font-weight: 600;
    }

    &__hint {
      margin: 6px 0 0;
      color: var(--kp-text-muted);
      font-size: 12px;
      line-height: 1.5;
    }

    .el-alert {
      margin-top: 10px;
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
  }
</style>
