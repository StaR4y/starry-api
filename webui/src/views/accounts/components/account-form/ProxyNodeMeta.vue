<script setup lang="ts">
  /**
   * 代理节点的一行摘要：线路类型、协议、状态、最近一次出口探测。
   * 用在节点选择器的下拉项与已选节点的说明里。
   */
  import { computed } from 'vue';
  import { t } from '@/i18n';
  import type { ProxyChannel } from '@/api/proxyChannels';
  import { nodeStatus, probeSummary, routeLabel } from './proxyNodes';

  const props = defineProps<{
    node: ProxyChannel;
    mihomoEnabled: boolean;
  }>();

  const status = computed(() => nodeStatus(props.node, props.mihomoEnabled));
  const probe = computed(() => probeSummary(props.node));
</script>

<template>
  <span class="node-meta">
    <el-tag
      size="small"
      effect="light"
      :type="node.route === 'MIHOMO' ? 'warning' : 'success'"
      class="node-meta__route"
    >
      {{ routeLabel(node.route) }}
    </el-tag>
    <span class="node-meta__protocol">{{ node.protocol }}</span>
    <span class="node-meta__status" :class="`is-${status.tone}`">
      <i class="node-meta__dot" />{{ status.label }}
    </span>
    <span v-if="probe" class="node-meta__probe" :class="`is-${probe.tone}`" :title="probe.title">
      {{ probe.text }}
    </span>
    <span v-else class="node-meta__probe">{{ t('未探测') }}</span>
  </span>
</template>

<style scoped lang="scss">
  .node-meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 2px 10px;
    min-width: 0;
    color: var(--kp-text-muted);
    font-size: 12px;
    line-height: 1.5;

    &__protocol {
      text-transform: uppercase;
      letter-spacing: 0.02em;
    }

    &__status {
      display: inline-flex;
      align-items: center;
      gap: 5px;
    }

    &__dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: currentColor;
    }

    &__probe {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .is-success {
      color: var(--kp-success);
    }

    .is-warning {
      color: var(--kp-warning);
    }

    .is-danger {
      color: var(--kp-danger);
    }
  }
</style>
