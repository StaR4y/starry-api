<script setup lang="ts">
  /**
   * 页面外壳组件。
   *
   * 统一各业务页面的标题、副标题与右侧操作区，自身使用磨砂玻璃面板。
   * 内容通过默认 slot 渲染，操作区通过 #actions 提供。
   */
  defineProps<{
    title: string;
    subtitle?: string;
  }>();
</script>

<template>
  <section class="page-section glass-panel">
    <header class="page-section__header">
      <div>
        <h2 class="page-section__title">{{ title }}</h2>
        <p v-if="subtitle" class="page-section__subtitle text-secondary">{{ subtitle }}</p>
      </div>
      <div class="page-section__actions">
        <slot name="actions" />
      </div>
    </header>
    <div class="page-section__body">
      <slot />
    </div>
  </section>
</template>

<style scoped lang="scss">
  .page-section {
    padding: 24px 28px;
    margin-bottom: 24px;

    &__header {
      display: flex;
      justify-content: space-between;
      align-items: flex-end;
      gap: 16px;
      flex-wrap: wrap;
      margin-bottom: 18px;
    }

    &__title {
      font-size: 20px;
      font-weight: 600;
      margin: 0;
      letter-spacing: 0.2px;
    }

    &__subtitle {
      margin: 4px 0 0;
      font-size: 13px;
    }

    &__actions {
      display: flex;
      gap: 12px;
      align-items: center;
      flex-wrap: wrap;
    }

    &__body {
      width: 100%;
    }
  }
</style>
