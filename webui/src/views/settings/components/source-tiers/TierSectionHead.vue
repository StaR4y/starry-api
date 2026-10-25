<script setup lang="ts">
  /** 号源分级页各区块共用的标题行：标题、一句说明，右侧放这个区块的操作按钮 */
  defineProps<{ title: string; description: string }>();
</script>

<template>
  <header class="section-head">
    <div>
      <h3>{{ title }}</h3>
      <p>{{ description }}</p>
    </div>
    <div v-if="$slots.default" class="section-head__actions">
      <slot />
    </div>
  </header>
</template>

<style scoped lang="scss">
  .section-head {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-end;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 10px;

    h3 {
      margin: 0;
      font-size: 15px;
      font-weight: 600;
      color: var(--kp-text);
    }

    p {
      margin: 4px 0 0;
      max-width: 640px;
      font-size: 12.5px;
      line-height: 1.55;
      color: var(--kp-text-secondary);
    }

    &__actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
  }
</style>
