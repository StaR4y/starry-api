<script setup lang="ts">
  /**
   * 账号类型的方形标志：内置平台用品牌 Logo，Kiro / 兼容厂商 / 其他类型用图标或首字母。
   */
  import { computed } from 'vue';
  import { Connection, Cpu } from '@element-plus/icons-vue';
  import ProviderLogo from '@/components/ProviderLogo.vue';
  import { providerInitials } from '../../utils';
  import type { AccountTypeKey } from './accountTypes';

  const props = withDefaults(
    defineProps<{
      type: AccountTypeKey;
      /** 兼容厂商在模型管理中配置的图标 */
      icon?: string | null;
      /** 兼容厂商或其他类型的名称，用于首字母 */
      name?: string | null;
      size?: number;
    }>(),
    { icon: null, name: null, size: 36 },
  );

  const brand = computed(() => {
    if (props.type === 'openai' || props.type === 'codex') return 'OpenAI';
    if (props.type === 'anthropic') return 'Anthropic';
    if (props.type === 'deepseek') return 'DeepSeek';
    return '';
  });
  const box = computed(() => ({ width: `${props.size}px`, height: `${props.size}px` }));
</script>

<template>
  <ProviderLogo v-if="brand" :provider="brand" :size="size" />
  <span v-else class="type-logo" :class="`type-logo--${type}`" :style="box" aria-hidden="true">
    <el-icon v-if="type === 'kiro'" :size="size * 0.5"><Cpu /></el-icon>
    <el-image v-else-if="icon" class="type-logo__image" :src="icon" fit="cover" />
    <span v-else-if="type === 'other' || name">{{ providerInitials(name) }}</span>
    <el-icon v-else :size="size * 0.5"><Connection /></el-icon>
  </span>
</template>

<style scoped lang="scss">
  .type-logo {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    border: 1px solid rgba(15, 23, 42, 0.1);
    border-radius: 7px;
    background: rgba(15, 23, 42, 0.05);
    color: var(--kp-text-secondary);
    font-size: 12px;
    font-weight: 800;

    &__image {
      width: 100%;
      height: 100%;
    }

    &--kiro {
      border-color: rgba(124, 58, 237, 0.2);
      background: rgba(124, 58, 237, 0.08);
      color: #7c3aed;
    }
  }
</style>
