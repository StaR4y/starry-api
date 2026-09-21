<script setup lang="ts">
  /** 上下文窗口字段：常用档位快捷选择 + 自定义数值输入。 */
  const props = defineProps<{
    modelValue: number;
  }>();

  const emit = defineEmits<{
    'update:modelValue': [value: number];
  }>();

  const presets = [
    { label: '32K', value: 32000 },
    { label: '128K', value: 128000 },
    { label: '200K', value: 200000 },
    { label: '1M', value: 1000000 },
  ];

  function choose(value: number) {
    emit('update:modelValue', value);
  }

  function update(value: number | undefined) {
    emit('update:modelValue', value ?? props.modelValue);
  }
</script>

<template>
  <div class="context-window-field">
    <div class="presets" role="listbox" aria-label="常用上下文窗口">
      <button
        v-for="preset in presets"
        :key="preset.value"
        type="button"
        :class="{ 'is-active': modelValue === preset.value }"
        :aria-selected="modelValue === preset.value"
        @click="choose(preset.value)"
      >
        {{ preset.label }}
      </button>
    </div>
    <el-input-number
      :model-value="modelValue"
      :min="1"
      :max="10000000"
      :step="1000"
      controls-position="right"
      class="custom-input"
      @update:model-value="update"
    />
  </div>
</template>

<style scoped lang="scss">
  .context-window-field {
    display: flex;
    align-items: center;
    width: 100%;
    gap: 10px;
  }

  .presets {
    display: flex;
    flex: 0 0 auto;
    gap: 8px;

    button {
      min-height: 34px;
      padding: 6px 14px;
      border: 1px solid var(--kp-divider);
      border-radius: 6px;
      background: #fff;
      color: var(--kp-text-secondary);
      cursor: pointer;
      font-size: 12px;
      transition:
        border-color 0.16s ease,
        color 0.16s ease,
        background 0.16s ease;

      &:hover {
        border-color: var(--el-color-primary);
        color: var(--el-color-primary);
      }

      &.is-active {
        border-color: var(--el-color-primary);
        background: rgba(235, 243, 255, 0.72);
        color: var(--el-color-primary);
        font-weight: 600;
      }
    }
  }

  .custom-input {
    flex: 1 1 auto;
    min-width: 0;
  }

  @media (max-width: 560px) {
    .context-window-field {
      align-items: flex-start;
      flex-direction: column;
    }

    .custom-input {
      width: 100%;
    }
  }
</style>
