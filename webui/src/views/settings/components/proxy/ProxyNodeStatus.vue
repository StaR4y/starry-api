<script setup lang="ts">
  /** 节点状态标签；原因（配置问题、已从订阅移除等）放在悬浮提示里。 */
  import { computed } from 'vue';
  import StatusTag from '@/components/StatusTag.vue';
  import type { ProxyChannel } from '@/api/proxyChannels';
  import { nodeStatus } from './helpers';

  const props = defineProps<{ node: ProxyChannel; mihomoEnabled: boolean }>();
  const status = computed(() => nodeStatus(props.node, props.mihomoEnabled));
</script>

<template>
  <el-tooltip :content="status.hint" :disabled="!status.hint" placement="top" :show-after="200">
    <span class="node-status"><StatusTag :label="status.label" :tone="status.tone" /></span>
  </el-tooltip>
</template>

<style scoped>
  .node-status {
    display: inline-block;
    max-width: 100%;
  }

  /* 较长的状态文字（尤其是英文）换行显示，而不是被截断 */
  .node-status :deep(.el-tag) {
    height: auto;
    min-height: 22px;
    max-width: 100%;
    padding-block: 2px;
    line-height: 1.3;
    white-space: normal;
    text-align: left;
  }
</style>
