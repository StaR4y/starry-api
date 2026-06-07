<script setup lang="ts">
  /**
   * 状态标签组件。
   * 把后端布尔/状态字符串映射为统一外观的彩色 Tag。
   */
  type Tone = 'success' | 'danger' | 'warning' | 'info';
  defineProps<{
    label: string;
    tone?: Tone;
  }>();
</script>

<template>
  <el-tag :type="tone || 'info'" effect="light" round>{{ label }}</el-tag>
</template>
