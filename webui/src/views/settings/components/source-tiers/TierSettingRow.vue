<script setup lang="ts">
  /**
   * 厂商开关的一行：左边是名称和说明，右边是控件（开关或数字框）。
   * note 用来说明控件为什么不可用（例如当前版本不支持），以警示色显示在说明下方。
   */
  defineProps<{
    title: string;
    description?: string;
    note?: string;
  }>();
</script>

<template>
  <div class="setting-row">
    <div class="setting-row__text">
      <strong class="setting-row__title">{{ title }}</strong>
      <p v-if="description" class="setting-row__desc">{{ description }}</p>
      <p v-if="note" class="setting-row__note">{{ note }}</p>
    </div>
    <div class="setting-row__control">
      <slot />
    </div>
  </div>
</template>

<style scoped lang="scss">
  .setting-row {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    min-width: 0;
    padding: 14px 18px;

    &__text {
      min-width: 0;
    }

    &__title {
      font-size: 14px;
      color: var(--kp-text);
    }

    &__desc,
    &__note {
      margin: 4px 0 0;
      font-size: 12px;
      line-height: 1.55;
      overflow-wrap: anywhere;
    }

    &__desc {
      color: var(--kp-text-muted);
    }

    &__note {
      color: var(--el-color-warning);
    }

    &__control {
      display: flex;
      flex: 0 0 auto;
      align-items: center;
      min-height: 22px;
    }
  }
</style>
