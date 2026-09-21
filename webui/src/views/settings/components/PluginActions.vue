<script setup lang="ts">
  import { computed } from 'vue';
  import { VideoPlay, VideoPause, Refresh, Setting, Tools, Delete } from '@element-plus/icons-vue';
  import type { PluginInfo } from '@/api/plugins';
  const props = defineProps<{ plugin: PluginInfo; disabled: boolean }>();
  defineEmits<{ action: [operation: string]; configure: []; debug: [] }>();
  const blocked = computed(() => props.plugin.state === 'RESTART_REQUIRED');
  const transitioning = computed(() => ['STARTING', 'STOPPING'].includes(props.plugin.state));
  const stoppable = computed(() => props.plugin.state === 'STARTED' || blocked.value);
  const toggleLabel = computed(() => blocked.value ? '重试清理' : stoppable.value ? '停止' : '启动');
</script>

<template>
  <div class="plugin-actions">
    <el-tooltip :content="toggleLabel">
      <el-button
        :icon="stoppable ? VideoPause : VideoPlay" :disabled="disabled || transitioning"
        :aria-label="toggleLabel"
        @click="$emit('action', stoppable ? 'stop' : 'start')"
      />
    </el-tooltip>
    <el-tooltip content="重载"><el-button :icon="Refresh" :disabled="disabled || blocked || transitioning" aria-label="重载" @click="$emit('action', 'reload')" /></el-tooltip>
    <el-tooltip content="配置"><el-button :icon="Setting" :disabled="disabled" aria-label="配置" @click="$emit('configure')" /></el-tooltip>
    <el-tooltip content="调试"><el-button :icon="Tools" :disabled="disabled || plugin.state !== 'STARTED'" aria-label="调试" @click="$emit('debug')" /></el-tooltip>
    <el-tooltip content="移除"><el-button :icon="Delete" :disabled="disabled || transitioning" aria-label="移除" @click="$emit('action', 'remove')" /></el-tooltip>
  </div>
</template>

<style scoped lang="scss">
  .plugin-actions { display: flex; gap: 6px; }
  .el-button { margin: 0; width: 34px; height: 32px; padding: 0; flex: 0 0 34px; }
</style>
