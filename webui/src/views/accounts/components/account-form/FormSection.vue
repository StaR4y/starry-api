<script setup lang="ts">
  /**
   * 账号表单的一个分区：标题、说明，可选地折叠。
   * 折叠时内容仍保留在 DOM 中，所以其中的表单校验照常生效。
   */
  import { ArrowDown } from '@element-plus/icons-vue';

  defineProps<{
    title: string;
    description?: string;
    collapsible?: boolean;
  }>();

  const open = defineModel<boolean>('open', { default: true });
</script>

<template>
  <section class="form-section">
    <button
      v-if="collapsible"
      type="button"
      class="form-section__head form-section__head--toggle"
      :aria-expanded="open"
      @click="open = !open"
    >
      <span class="form-section__title">
        <strong>{{ title }}</strong>
        <small v-if="description">{{ description }}</small>
      </span>
      <span class="form-section__summary"><slot name="summary" /></span>
      <el-icon class="form-section__chevron" :class="{ 'is-open': open }"><ArrowDown /></el-icon>
    </button>
    <div v-else class="form-section__head">
      <span class="form-section__title">
        <strong>{{ title }}</strong>
        <small v-if="description">{{ description }}</small>
      </span>
      <span class="form-section__summary"><slot name="summary" /></span>
    </div>

    <el-collapse-transition v-if="collapsible">
      <div v-show="open" class="form-section__body"><slot /></div>
    </el-collapse-transition>
    <div v-else class="form-section__body"><slot /></div>
  </section>
</template>

<style scoped lang="scss">
  .form-section {
    padding: 18px 0 4px;
    border-top: 1px solid var(--kp-divider);

    &:first-child {
      padding-top: 4px;
      border-top: 0;
    }

    &__head {
      display: flex;
      align-items: center;
      gap: 12px;
      width: 100%;
      min-height: 28px;
      margin: 0 0 14px;
      padding: 0;
      border: 0;
      background: transparent;
      color: inherit;
      font: inherit;
      text-align: left;

      &--toggle {
        margin-bottom: 0;
        padding-bottom: 14px;
        cursor: pointer;
        border-radius: 6px;

        &:focus-visible {
          outline: 2px solid var(--kp-primary);
          outline-offset: 2px;
        }
      }
    }

    &__title {
      display: flex;
      flex: 1;
      flex-direction: column;
      gap: 2px;
      min-width: 0;

      strong {
        color: var(--kp-text);
        font-size: 14px;
        font-weight: 700;
      }

      small {
        color: var(--kp-text-muted);
        font-size: 12px;
        line-height: 1.45;
      }
    }

    &__summary {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      min-width: 0;
      overflow: hidden;
      color: var(--kp-text-secondary);
      font-size: 12px;
      white-space: nowrap;
      text-overflow: ellipsis;
    }

    &__chevron {
      flex: 0 0 auto;
      color: var(--kp-text-muted);
      transition: transform 0.2s ease;

      &.is-open {
        transform: rotate(180deg);
      }
    }

    &__body {
      padding-bottom: 8px;
    }
  }

  @media (max-width: 560px) {
    .form-section__summary {
      display: none;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .form-section__chevron {
      transition: none;
    }
  }
</style>
