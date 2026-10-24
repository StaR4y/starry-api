<script setup lang="ts">
  /**
   * 凭证：只显示当前账号类型用到的字段。
   * Codex 新建时可以改用 ChatGPT 浏览器授权，授权流程在这里内嵌显示。
   */
  import { computed } from 'vue';
  import { t } from '@/i18n';
  import type { Account } from '@/types/api';
  import ChatGptOAuthFlow from '../ChatGptOAuthFlow.vue';
  import FormSection from './FormSection.vue';
  import {
    accessTokenPlaceholder,
    baseUrlHint,
    baseUrlLabel,
    baseUrlPlaceholder,
  } from './accountTypes';
  import { useAccountFormContext } from './context';

  defineProps<{
    /** 对话框处于打开状态 */
    active: boolean;
  }>();

  const emit = defineEmits<{
    (e: 'oauth-saved', account: Account): void;
  }>();

  const { mode, accountType, createMethod, form, spec } = useAccountFormContext();

  const methodOptions = [
    {
      get label() {
        return t('ChatGPT 浏览器授权');
      },
      value: 'chatgpt',
    },
    {
      get label() {
        return t('手动凭证');
      },
      value: 'credentials',
    },
  ];

  const kiroAuthMethods = ['idc', 'social'];
  const otherAuthMethods = ['idc', 'social', 'api-key', 'codex'];

  const showMethodSwitch = computed(() => mode.value === 'create' && accountType.value === 'codex');
  const isChatGptLogin = computed(() => showMethodSwitch.value && createMethod.value === 'chatgpt');
  const authMethodOptions = computed(() =>
    accountType.value === 'kiro' ? kiroAuthMethods : otherAuthMethods,
  );
  const accessTokenLabel = computed(() => {
    const suffix = mode.value === 'edit' ? t('（留空则不修改）') : '';
    return `${spec.value.apiKey ? 'API Key' : 'Access Token'}${suffix}`;
  });
  const hint = computed(() => baseUrlHint(accountType.value));
</script>

<template>
  <FormSection :title="t('凭证')">
    <el-segmented
      v-if="showMethodSwitch"
      v-model="createMethod"
      class="credential-method"
      :class="{ 'is-centered': isChatGptLogin }"
      :options="methodOptions"
    />

    <ChatGptOAuthFlow
      v-if="isChatGptLogin"
      :active="active"
      :proxy-channel-id="form.proxyChannelId"
      @update:proxy-channel-id="(value: string) => (form.proxyChannelId = value)"
      @saved="(account: Account) => emit('oauth-saved', account)"
    />

    <div v-else class="af-grid">
      <el-form-item v-if="spec.authMethod" :label="t('认证方式')">
        <el-select v-model="form.authMethod" allow-create filterable class="af-full">
          <el-option v-for="item in authMethodOptions" :key="item" :label="item" :value="item" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="spec.region" :label="t('区域')">
        <el-input v-model="form.region" :placeholder="t('如 us-east-1')" />
      </el-form-item>

      <el-form-item class="af-span-2" :label="accessTokenLabel" prop="accessToken">
        <el-input
          v-model="form.accessToken"
          type="textarea"
          :rows="3"
          :placeholder="accessTokenPlaceholder(accountType, spec.apiKey)"
        />
      </el-form-item>

      <el-form-item v-if="spec.refreshToken" class="af-span-2" label="Refresh Token">
        <el-input v-model="form.refreshToken" :placeholder="t('输入 Refresh Token，可选')" />
      </el-form-item>
      <el-form-item v-if="spec.idToken" class="af-span-2" label="ID Token">
        <el-input
          v-model="form.idToken"
          type="textarea"
          :rows="2"
          :placeholder="t('Codex id_token，可选')"
        />
      </el-form-item>
      <el-form-item v-if="spec.profileArn" class="af-span-2" label="Profile ARN">
        <el-input v-model="form.profileArn" :placeholder="t('AWS Profile ARN，可选')" />
      </el-form-item>
      <el-form-item v-if="spec.upstreamAccountId" class="af-span-2" :label="t('上游账号 ID')">
        <el-input
          v-model="form.upstreamAccountId"
          :placeholder="t('Codex ChatGPT Account ID，可选')"
        />
      </el-form-item>

      <el-form-item
        v-if="spec.baseUrl"
        class="af-span-2"
        :label="baseUrlLabel(accountType)"
        prop="baseUrl"
      >
        <el-input v-model="form.baseUrl" :placeholder="baseUrlPlaceholder(accountType)" />
        <p v-if="hint" class="af-hint">{{ hint }}</p>
      </el-form-item>
    </div>
  </FormSection>
</template>

<style scoped lang="scss">
  .credential-method {
    width: min(360px, 100%);
    margin-bottom: 16px;

    &.is-centered {
      display: flex;
      margin-right: auto;
      margin-left: auto;
    }

    :deep(.el-segmented__item) {
      min-width: 0;
    }
  }
</style>
