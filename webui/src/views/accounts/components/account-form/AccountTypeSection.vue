<script setup lang="ts">
  /**
   * 第一步：账号类型。新建时从卡片中选择，编辑时只读（类型创建后不能更改）。
   */
  import { computed } from 'vue';
  import { CircleCheckFilled } from '@element-plus/icons-vue';
  import { t } from '@/i18n';
  import type { CompatibleVendorOption } from '../../utils';
  import AccountTypeLogo from './AccountTypeLogo.vue';
  import FormSection from './FormSection.vue';
  import {
    CREATE_ACCOUNT_TYPES,
    accountTypeCaption,
    accountTypeLabel,
    accountTypeNotice,
    type AccountTypeKey,
  } from './accountTypes';
  import { useAccountFormContext } from './context';

  const props = defineProps<{
    /** 编辑已有的兼容厂商账号时，用于显示该厂商的图标 */
    vendor: CompatibleVendorOption | null;
  }>();

  const emit = defineEmits<{
    (e: 'select', type: AccountTypeKey): void;
  }>();

  const { mode, accountType, form } = useAccountFormContext();

  const notice = computed(() => accountTypeNotice(accountType.value));
  const otherName = computed(() => form.provider || null);
  const vendorIcon = computed(() => props.vendor?.icon ?? null);
  const vendorName = computed(() => props.vendor?.name ?? form.catalogVendor ?? null);
</script>

<template>
  <FormSection
    :title="t('账号类型')"
    :description="mode === 'create' ? t('先选择账号类型，下面只显示该类型需要的字段。') : undefined"
  >
    <div v-if="mode === 'create'" class="type-grid" role="group" :aria-label="t('账号类型')">
      <button
        v-for="item in CREATE_ACCOUNT_TYPES"
        :key="item"
        type="button"
        class="type-card"
        :class="{ 'is-selected': item === accountType }"
        :aria-pressed="item === accountType"
        @click="emit('select', item)"
      >
        <AccountTypeLogo :type="item" :size="34" />
        <span class="type-card__text">
          <strong>{{ accountTypeLabel(item) }}</strong>
          <small>{{ accountTypeCaption(item) }}</small>
        </span>
        <el-icon v-if="item === accountType" class="type-card__check" :size="18">
          <CircleCheckFilled />
        </el-icon>
      </button>
    </div>

    <div v-else class="type-fixed">
      <AccountTypeLogo
        :type="accountType"
        :icon="vendorIcon"
        :name="accountType === 'other' ? otherName : vendorName"
        :size="34"
      />
      <span class="type-card__text">
        <strong>{{
          accountType === 'other' && otherName ? otherName : accountTypeLabel(accountType)
        }}</strong>
        <small>{{ t('账号类型创建后不能更改。') }}</small>
      </span>
    </div>

    <el-alert
      v-if="notice"
      class="type-notice"
      type="info"
      show-icon
      :closable="false"
      :title="notice"
    />
  </FormSection>
</template>

<style scoped lang="scss">
  .type-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(min(250px, 100%), 1fr));
    gap: 10px;
  }

  .type-card,
  .type-fixed {
    position: relative;
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
    padding: 10px 12px;
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    background: transparent;
    color: inherit;
    font: inherit;
    text-align: left;
  }

  .type-card {
    cursor: pointer;
    transition:
      border-color 0.16s ease,
      background-color 0.16s ease;

    &:hover {
      border-color: var(--el-color-primary-light-5);
    }

    &:focus-visible {
      outline: 2px solid var(--kp-primary);
      outline-offset: 2px;
    }

    &.is-selected {
      border-color: var(--kp-primary);
      background: var(--el-color-primary-light-9);
    }

    &__text {
      display: flex;
      flex: 1;
      flex-direction: column;
      gap: 2px;
      min-width: 0;

      strong {
        padding-right: 18px;
        color: var(--kp-text);
        font-size: 13px;
        font-weight: 700;
        line-height: 1.35;
        overflow-wrap: anywhere;
      }

      small {
        display: -webkit-box;
        overflow: hidden;
        color: var(--kp-text-muted);
        font-size: 12px;
        line-height: 1.4;
        -webkit-box-orient: vertical;
        -webkit-line-clamp: 2;
      }
    }

    &__check {
      position: absolute;
      top: 8px;
      right: 8px;
      color: var(--kp-primary);
    }
  }

  .type-fixed {
    background: var(--el-fill-color-light);
  }

  .type-notice {
    margin-top: 12px;
  }

  @media (prefers-reduced-motion: reduce) {
    .type-card {
      transition: none;
    }
  }
</style>
