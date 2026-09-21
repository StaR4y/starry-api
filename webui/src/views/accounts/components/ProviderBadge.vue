<script setup lang="ts">
  /**
   * 账号列表「账号类型」列的平台徽章。
   *
   * 内置平台使用固定 Logo 与配色；第三方 OpenAI 兼容厂商使用
   * 模型目录里的图标，以及账号设置时选择的颜色。
   */
  import { computed } from 'vue';
  import {
    badgeColorFromName,
    colorBadgeStyle,
    getProviderDisplayName,
    isCustomOpenAiCompatibleProvider,
    providerInitials,
  } from '../utils';

  const props = defineProps<{
    provider: string | null | undefined;
    authMethod?: string | null;
    color?: string | null;
    icon?: string | null;
  }>();

  const upperProvider = computed(() => (props.provider || '').toUpperCase());
  const customVendor = computed(() =>
    isCustomOpenAiCompatibleProvider(props.provider, props.authMethod),
  );
  const displayName = computed(() => getProviderDisplayName(props.provider));

  function getProviderTagStyle(provider: string | null | undefined) {
    if (customVendor.value) {
      return colorBadgeStyle(props.color || badgeColorFromName(props.provider));
    }
    const p = (provider || '').toUpperCase();
    if (p === 'DEEPSEEK') {
      return {
        background: 'rgba(77, 107, 254, 0.08)',
        color: '#4d6bfe',
        border: '1px solid rgba(77, 107, 254, 0.18)',
      };
    }
    if (p === 'OPENAI' || p === 'OPENAI-COMPATIBLE') {
      return {
        background: 'rgba(16, 163, 127, 0.08)',
        color: '#10a37f',
        border: '1px solid rgba(16, 163, 127, 0.15)',
      };
    }
    if (p === 'GEMINI' || p === 'GOOGLE') {
      return {
        background: 'rgba(66, 133, 244, 0.08)',
        color: '#4285f4',
        border: '1px solid rgba(66, 133, 244, 0.15)',
      };
    }
    if (p === 'ANTHROPIC') {
      return {
        background: 'rgba(217, 119, 87, 0.08)',
        color: '#d97757',
        border: '1px solid rgba(217, 119, 87, 0.15)',
      };
    }
    if (p.includes('BUILDERID') || p.includes('KIRO')) {
      return {
        background: 'rgba(124, 58, 237, 0.08)',
        color: '#7c3aed',
        border: '1px solid rgba(124, 58, 237, 0.15)',
      };
    }
    return {
      background: 'rgba(15, 23, 42, 0.05)',
      color: 'var(--kp-text-secondary)',
      border: '1px solid rgba(15, 23, 42, 0.1)',
    };
  }
</script>

<template>
  <span class="provider-badge" :style="getProviderTagStyle(provider)">
    <img
      v-if="customVendor && icon"
      class="provider-logo provider-logo--image"
      :src="icon"
      alt=""
    />
    <span
      v-else-if="customVendor"
      class="provider-logo provider-logo--initials"
    >
      {{ providerInitials(provider) }}
    </span>
    <span v-else-if="upperProvider === 'DEEPSEEK'" class="provider-logo provider-logo--deepseek">D</span>
    <!-- OpenAI Logo -->
    <svg
      v-else-if="['OPENAI', 'OPENAI-COMPATIBLE'].includes(upperProvider)"
      class="provider-logo"
      viewBox="0 0 24 24"
      fill="currentColor"
    >
      <path
        d="M22.2819 9.8211a5.9847 5.9847 0 0 0-.5157-4.9108 6.0462 6.0462 0 0 0-6.5098-2.9A6.0651 6.0651 0 0 0 4.9807 4.1818a5.9847 5.9847 0 0 0-3.9977 2.9 6.0462 6.0462 0 0 0 .7427 7.0966 5.98 5.98 0 0 0 .511 4.9107 6.051 6.051 0 0 0 6.5146 2.9001A5.9847 5.9847 0 0 0 13.2599 24a6.0557 6.0557 0 0 0 5.7718-4.2058 5.9894 5.9894 0 0 0 3.9977-2.9001 6.0557 6.0557 0 0 0-.7475-7.0729zm-9.022 12.6081a4.4755 4.4755 0 0 1-2.8764-1.0408l.1419-.0804 4.7783-2.7582a.7948.7948 0 0 0 .3927-.6813v-6.7369l2.02 1.1686a.071.071 0 0 1 .038.052v5.5826a4.504 4.504 0 0 1-4.4945 4.4944zm-9.6607-4.1254a4.4708 4.4708 0 0 1-.5346-3.0137l.142.0852 4.783 2.7582a.7712.7712 0 0 0 .7806 0l5.8428-3.3685v2.3324a.0804.0804 0 0 1-.0332.0615L9.74 19.9502a4.4992 4.4992 0 0 1-6.1408-1.6464zM2.3408 7.8956a4.485 4.485 0 0 1 2.3655-1.9728V11.6a.7664.7664 0 0 0 .3879.6765l5.8144 3.3543-2.0201 1.1685a.0757.0757 0 0 1-.071 0l-4.8303-2.7865A4.504 4.504 0 0 1 2.3408 7.872zm16.5963 3.8558L13.1038 8.364 15.1192 7.2a.0757.0757 0 0 1 .071 0l4.8303 2.7913a4.4944 4.4944 0 0 1-.6765 8.1042v-5.6772a.79.79 0 0 0-.407-.667zm2.0107-3.0231l-.142-.0852-4.7735-2.7818a.7759.7759 0 0 0-.7854 0L9.409 9.2297V6.8974a.0662.0662 0 0 1 .0284-.0615l4.8303-2.7866a4.4992 4.4992 0 0 1 6.6802 4.66zM8.3065 12.863l-2.02-1.1638a.0804.0804 0 0 1-.038-.0567V6.0742a4.4992 4.4992 0 0 1 7.3757-3.4537l-.142.0805L8.704 5.459a.7948.7948 0 0 0-.3927.6813zm1.0976-2.3654l2.602-1.4998 2.6069 1.4998v2.9994l-2.5974 1.4997-2.6067-1.4997Z"
      />
    </svg>
    <!-- Gemini / Google Logo -->
    <svg
      v-else-if="['GEMINI', 'GOOGLE'].includes(upperProvider)"
      class="provider-logo"
      viewBox="0 0 24 24"
      fill="currentColor"
    >
      <path
        d="M12 2c0 5.5-4.5 10-10 10 5.5 0 10 4.5 10 10 0-5.5 4.5-10 10-10-5.5 0-10-4.5-10-10z"
      />
    </svg>
    <!-- Anthropic Logo -->
    <svg
      v-else-if="upperProvider === 'ANTHROPIC'"
      class="provider-logo"
      viewBox="0 0 24 24"
      fill="currentColor"
    >
      <path
        d="M12.5 3L4.2 19h4l2.1-4.2h5.4l2.1 4.2h4L13.5 3h-1zM11.3 12l1.7-3.4 1.7 3.4h-3.4z"
      />
    </svg>
    <!-- Other Shield Logo -->
    <svg
      v-else
      class="provider-logo"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2.5"
      stroke-linecap="round"
      stroke-linejoin="round"
    >
      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
    </svg>
    <span class="provider-name">{{ displayName }}</span>
  </span>
</template>

<style scoped lang="scss">
  .provider-badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 3px 10px;
    border-radius: 6px;
    font-size: 11px;
    font-weight: 600;
    white-space: nowrap;
    vertical-align: middle;
    max-width: 100%;
    box-sizing: border-box;

    .provider-logo {
      width: 13px;
      height: 13px;
      flex-shrink: 0;

      &--deepseek,
      &--initials {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        border: 1px solid currentColor;
        border-radius: 4px;
        font-size: 9px;
        font-weight: 800;
        line-height: 1;
      }

      &--image {
        border-radius: 3px;
        object-fit: cover;
        background: #fff;
      }
    }

    .provider-name {
      line-height: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }
</style>
