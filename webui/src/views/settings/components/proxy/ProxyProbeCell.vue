<script setup lang="ts">
  /**
   * 节点最近一次出口探测：成功显示出口 IP、延迟与时间；失败显示固定的失败说明；
   * 完整说明和精确时间放在悬浮提示里。
   */
  import { computed } from 'vue';
  import { Loading } from '@element-plus/icons-vue';
  import { t } from '@/i18n';
  import type { ProxyChannel } from '@/api/proxyChannels';
  import { formatDateTime } from '@/utils/format';
  import { formatRelative, probeView } from './helpers';

  const props = defineProps<{ node: ProxyChannel; testing: boolean }>();
  const view = computed(() => probeView(props.node));
  const successMeta = (latencyMs: number | null, at: string) =>
    latencyMs === null ? formatRelative(at) : `${latencyMs} ms · ${formatRelative(at)}`;
</script>

<template>
  <span v-if="testing" class="probe-cell is-testing">
    <el-icon class="is-loading"><Loading /></el-icon>{{ t('测试中…') }}
  </span>
  <span v-else-if="view.state === 'never'" class="probe-cell is-never">{{ t('未测试') }}</span>
  <div v-else-if="view.state === 'success'" class="probe-cell">
    <div class="probe-cell__main is-ok">
      <i class="probe-cell__dot" aria-hidden="true" />
      <span class="probe-cell__ip">{{ view.exitIp }}</span>
    </div>
    <div class="probe-cell__meta" :title="formatDateTime(view.at)">
      {{ successMeta(view.latencyMs, view.at) }}
    </div>
  </div>
  <el-tooltip v-else placement="top" :show-after="200">
    <template #content>
      <div class="probe-tip">{{ view.message }}</div>
      <div class="probe-tip probe-tip--time">{{ formatDateTime(view.at) }}</div>
    </template>
    <div class="probe-cell" role="status">
      <div class="probe-cell__main is-bad">
        <i class="probe-cell__dot" aria-hidden="true" />{{ t('测试失败') }}
      </div>
      <div class="probe-cell__meta">{{ formatRelative(view.at) }}</div>
      <div class="probe-cell__message">{{ view.message }}</div>
    </div>
  </el-tooltip>
</template>

<style scoped lang="scss">
  .probe-cell {
    min-width: 0;
    font-size: 13px;
    line-height: 1.4;

    &.is-never {
      color: var(--kp-text-muted);
    }

    &.is-testing {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      color: var(--kp-text-secondary);
    }

    &__main {
      display: flex;
      align-items: center;
      gap: 6px;
      color: var(--kp-text);
      white-space: nowrap;

      &.is-bad {
        color: var(--el-color-danger);
      }
    }

    &__ip {
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 12.5px;
      overflow-wrap: anywhere;
    }

    &__dot {
      flex: 0 0 auto;
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--kp-success);
    }

    &__main.is-bad &__dot {
      background: var(--kp-danger);
    }

    &__meta {
      margin-top: 2px;
      color: var(--kp-text-muted);
      font-size: 12px;
    }

    &__message {
      display: -webkit-box;
      margin-top: 2px;
      overflow: hidden;
      color: var(--kp-text-secondary);
      font-size: 12px;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
    }
  }

  .probe-tip {
    max-width: 320px;

    &--time {
      margin-top: 4px;
      opacity: 0.75;
    }
  }
</style>
