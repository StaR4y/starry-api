<script setup lang="ts">
  /** 导入或同步订阅的结果：导入数量、不可用数量与逐条问题。 */
  import { t } from '@/i18n';
  import type { ProxySyncResult } from '@/api/proxyChannels';
  import { syncIssueMessage } from '@/utils/egressDiagnostics';

  defineProps<{ result: ProxySyncResult }>();
</script>

<template>
  <div class="sync-result">
    <div class="sync-result__stats">
      <div class="sync-stat">
        <strong class="sync-stat__value is-ok">{{ result.imported }}</strong>
        <span>{{ t('已导入') }}</span>
      </div>
      <div class="sync-stat">
        <strong class="sync-stat__value" :class="{ 'is-warn': result.unavailable > 0 }">
          {{ result.unavailable }}
        </strong>
        <span>{{ t('不可用') }}</span>
      </div>
    </div>
    <template v-if="result.issues.length">
      <p class="sync-result__title">{{ t('以下节点存在问题') }}</p>
      <ul class="sync-result__issues">
        <li v-for="(issue, index) in result.issues" :key="index">{{ syncIssueMessage(issue) }}</li>
      </ul>
    </template>
  </div>
</template>

<style scoped lang="scss">
  .sync-result {
    display: flex;
    flex-direction: column;
    gap: 12px;

    &__stats {
      display: flex;
      gap: 28px;
    }

    &__title {
      margin: 0;
      font-size: 13px;
      font-weight: 600;
      color: var(--kp-text-secondary);
    }

    &__issues {
      max-height: 240px;
      margin: 0;
      padding: 8px 12px 8px 28px;
      overflow-y: auto;
      border: 1px solid var(--kp-divider);
      border-radius: var(--kp-radius-sm);
      font-size: 12.5px;
      line-height: 1.6;
      color: var(--kp-text-secondary);
      overflow-wrap: anywhere;
    }
  }

  .sync-stat {
    display: flex;
    flex-direction: column;
    gap: 2px;
    font-size: 12px;
    color: var(--kp-text-muted);

    &__value {
      font-size: 24px;
      line-height: 1.2;
      color: var(--kp-text);

      &.is-ok {
        color: var(--el-color-success);
      }

      &.is-warn {
        color: var(--el-color-warning);
      }
    }
  }
</style>
