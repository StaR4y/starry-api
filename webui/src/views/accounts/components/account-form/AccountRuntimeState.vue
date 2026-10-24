<script setup lang="ts">
  /**
   * 编辑账号时显示的运行状态：生命周期、预热阶段、当前并发、预留令牌、近期错误率。
   */
  import { computed } from 'vue';
  import { locale, t } from '@/i18n';
  import type { AccountAllocationPolicy } from '@/types/api';
  import { lifecycleLabel, lifecycleTagType } from './lifecycle';

  const props = defineProps<{
    runtime: AccountAllocationPolicy;
    /** 表单里当前的并发上限，0 表示不限 */
    concurrencyLimit: number | null;
  }>();

  const concurrencyText = computed(() => {
    const limit = props.concurrencyLimit;
    const shown = limit === null || limit === 0 ? t('不限') : limit;
    return `${props.runtime.activeConcurrency}/${shown}`;
  });
  const reservedTokens = computed(() =>
    new Intl.NumberFormat(locale.value, { notation: 'compact', maximumFractionDigits: 1 }).format(
      props.runtime.reservedInputTokens + props.runtime.reservedOutputTokens,
    ),
  );
</script>

<template>
  <div class="runtime">
    <div class="runtime__state">
      <span class="runtime__label">{{ t('运行状态') }}</span>
      <el-tag :type="lifecycleTagType(runtime.lifecycleState)" effect="light">
        {{ lifecycleLabel(runtime.lifecycleState) }}
      </el-tag>
    </div>
    <div class="runtime__metric">
      <span>{{ t('预热阶段') }}</span>
      <strong>{{ runtime.warmupStage }}/4</strong>
    </div>
    <div class="runtime__metric">
      <span>{{ t('当前并发') }}</span>
      <strong>{{ concurrencyText }}</strong>
    </div>
    <div class="runtime__metric">
      <span>{{ t('预留令牌') }}</span>
      <strong>{{ reservedTokens }}</strong>
    </div>
    <div class="runtime__metric">
      <span>{{ t('近期错误率') }}</span>
      <strong>{{ (runtime.recentErrorRate * 100).toFixed(1) }}%</strong>
    </div>
  </div>
</template>

<style scoped lang="scss">
  .runtime {
    display: grid;
    grid-template-columns: 1.25fr repeat(4, 1fr);
    margin-bottom: 18px;
    padding: 12px 0;
    border-top: 1px solid var(--kp-divider);
    border-bottom: 1px solid var(--kp-divider);
    background: var(--el-fill-color-light);

    &__state,
    &__metric {
      min-width: 0;
      padding: 0 14px;
      border-right: 1px solid var(--kp-divider);
    }

    &__metric:last-child {
      border-right: 0;
    }

    &__state {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      justify-content: center;
      gap: 5px;
    }

    &__label,
    &__metric span {
      color: var(--kp-text-muted);
      font-size: 12px;
      line-height: 1.3;
    }

    &__metric {
      display: flex;
      flex-direction: column;
      justify-content: center;
      gap: 3px;

      strong {
        overflow: hidden;
        color: var(--kp-text);
        font-size: 14px;
        line-height: 1.4;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }
  }

  @media (max-width: 680px) {
    .runtime {
      grid-template-columns: repeat(2, minmax(0, 1fr));

      &__state,
      &__metric {
        min-height: 56px;
        border-bottom: 1px solid var(--kp-divider);
      }

      &__metric:nth-child(even) {
        border-right: 0;
      }

      &__metric:last-child {
        grid-column: span 2;
        border-bottom: 0;
      }
    }
  }
</style>
