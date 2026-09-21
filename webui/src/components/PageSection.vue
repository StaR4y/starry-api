<script setup lang="ts">
  /**
   * 页面外壳。磨砂玻璃（classic）与 API Key 线框（editorial）由外观设置切换。
   */
  import type { Component } from 'vue';
  import { computed } from 'vue';
  import { useAppearanceStore } from '@/stores/appearance';

  withDefaults(
    defineProps<{
      title: string;
      subtitle?: string;
      eyebrow?: string;
      icon?: Component;
      variant?: 'page' | 'section';
    }>(),
    { variant: 'page' },
  );

  const appearance = useAppearanceStore();
  const chrome = computed(() => appearance.chrome);
  const editorial = computed(() => appearance.isEditorial);
</script>

<template>
  <section
    class="page-section"
    :class="[`is-${variant}`, `is-${chrome}`, { 'glass-panel': !editorial }]"
  >
    <header class="page-section__header">
      <div class="page-section__identity">
        <span v-if="icon && variant === 'page'" class="page-section__symbol">
          <el-icon><component :is="icon" /></el-icon>
        </span>
        <div>
          <p v-if="editorial && (eyebrow || subtitle)" class="page-section__eyebrow">
            {{ eyebrow || subtitle }}
          </p>
          <component :is="variant === 'section' ? 'h2' : 'h1'" class="page-section__title">
            {{ title }}
          </component>
          <p v-if="!editorial && subtitle" class="page-section__subtitle text-secondary">
            {{ subtitle }}
          </p>
        </div>
      </div>
      <div v-if="$slots.actions" class="page-section__actions">
        <slot name="actions" />
      </div>
    </header>
    <div v-if="$slots.summary" class="page-section__summary">
      <slot name="summary" />
    </div>
    <div v-if="$slots.toolbar" class="page-section__toolbar">
      <slot name="toolbar" />
    </div>
    <div class="page-section__body">
      <slot />
    </div>
  </section>
</template>

<style scoped lang="scss">
  .page-section {
    width: 100%;
    max-width: 100%;
    min-width: 0;

    &.is-classic {
      padding: 24px 28px;
      margin-bottom: 24px;

      .page-section__header {
        display: flex;
        justify-content: space-between;
        align-items: flex-end;
        gap: 16px;
        flex-wrap: wrap;
        margin-bottom: 18px;
        padding: 0;
      }

      .page-section__symbol {
        display: none;
      }

      .page-section__title {
        margin: 0;
        font-size: 20px;
        font-weight: 600;
        letter-spacing: 0.2px;
      }

      .page-section__subtitle {
        margin: 4px 0 0;
        font-size: 13px;
      }

      .page-section__actions {
        display: flex;
        gap: 12px;
        align-items: center;
        flex-wrap: wrap;
      }

      .page-section__summary {
        margin-bottom: 18px;
      }

      .page-section__toolbar {
        display: flex;
        gap: 12px;
        align-items: center;
        flex-wrap: wrap;
        margin: 0 0 12px;
      }

      &.is-section {
        margin-top: 0;
      }
    }

    &.is-editorial {
      margin-bottom: 2px;

      &.is-section {
        margin-top: 14px;
        padding-top: 2px;
      }

      .page-section__header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: 10px;
        flex-wrap: wrap;
        padding: 0 0 10px;
        max-width: 100%;
      }

      .page-section__identity {
        display: flex;
        align-items: center;
        gap: 10px;
        min-width: 0;
        flex: 1 1 auto;

        > div {
          min-width: 0;
        }
      }

      .page-section__symbol {
        display: grid;
        place-items: center;
        width: 32px;
        height: 32px;
        flex: 0 0 auto;
        border: 1px solid var(--el-border-color);
        border-radius: 6px;
        background: transparent;
        color: var(--kp-primary);
        font-size: 16px;
      }

      .page-section__eyebrow {
        margin: 0;
        color: var(--kp-text-muted);
        font-size: 10px;
        letter-spacing: 0.06em;
        line-height: 1.2;
      }

      .page-section__title {
        margin: 0;
        font-size: 20px;
        font-weight: 600;
        letter-spacing: 0;
        line-height: 1.2;
      }

      .page-section__actions {
        display: flex;
        gap: 6px;
        align-items: center;
        flex-wrap: wrap;
        flex: 0 1 auto;
        max-width: 100%;
      }

      .page-section__summary {
        display: block;
        width: 100%;
        max-width: 100%;
        min-width: 0;
        padding: 8px 0;
        border-top: 1px solid var(--el-border-color);
        border-bottom: 1px solid var(--el-border-color);
        color: var(--kp-text-secondary);
        font-size: 12px;

        :deep(.metrics-panel) {
          width: 100%;
          max-width: 100%;
          min-width: 0;
        }

        > span {
          display: inline-flex;
          gap: 6px;
          align-items: center;
          margin-right: 16px;
        }

        strong {
          color: var(--kp-text);
          font-size: 16px;
        }
      }

      .page-section__toolbar {
        display: flex;
        gap: 8px;
        align-items: center;
        flex-wrap: wrap;
        width: 100%;
        max-width: 100%;
        min-width: 0;
        margin: 10px 0 6px;
      }

      &.is-section .page-section__header {
        padding: 0 0 8px;
        align-items: flex-end;
      }

      &.is-section .page-section__title {
        font-size: 15px;
      }

      &.is-section .page-section__eyebrow {
        font-size: 10px;
        letter-spacing: 0;
      }

      &.is-section .page-section__symbol {
        display: none;
      }
    }

    &__body {
      width: 100%;
      max-width: 100%;
      min-width: 0;
    }
  }

  @media (max-width: 640px) {
    .page-section.is-classic {
      padding: 18px 16px;
      margin-bottom: 16px;

      .page-section__header {
        align-items: flex-start;
        margin-bottom: 16px;
      }

      .page-section__actions {
        width: 100%;
      }
    }

    .page-section.is-editorial {
      .page-section__header {
        align-items: flex-start;
        padding-bottom: 10px;
      }

      .page-section__title {
        font-size: 18px;
      }

      .page-section__actions,
      .page-section__toolbar {
        width: 100%;
      }

      .page-section__toolbar > :deep(.el-input) {
        width: 100%;
        max-width: none;
      }
    }
  }
</style>
