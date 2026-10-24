<script setup lang="ts">
  /**
   * 基本信息：邮箱、账号 ID，兼容厂商账号的厂商分类，编辑时的启用开关。
   */
  import { computed } from 'vue';
  import { t } from '@/i18n';
  import type { CompatibleVendorOption } from '../../utils';
  import ProviderBadge from '../ProviderBadge.vue';
  import FormSection from './FormSection.vue';
  import { useAccountFormContext } from './context';
  import { applyVendorToForm } from './formModel';

  const props = defineProps<{
    vendors: CompatibleVendorOption[];
    vendorsLoading: boolean;
  }>();

  const emit = defineEmits<{
    (e: 'add-vendor'): void;
  }>();

  const { mode, accountType, form } = useAccountFormContext();

  const isCompatible = computed(() => accountType.value === 'compatible');
  const selectedVendor = computed(
    () => props.vendors.find((vendor) => vendor.name === form.catalogVendor) ?? null,
  );
  /** 账号当前的厂商不在模型管理里时（例如已被删除），仍要显示它 */
  const vendorOptions = computed(() => {
    if (form.catalogVendor && !selectedVendor.value) {
      return [
        { name: form.catalogVendor, icon: null, color: null, modelCount: 0 },
        ...props.vendors,
      ];
    }
    return props.vendors;
  });
</script>

<template>
  <FormSection :title="t('基本信息')">
    <div class="af-grid">
      <el-form-item v-if="mode === 'create'" :label="t('账号 ID（可选）')">
        <el-input v-model="form.accountId" :placeholder="t('留空时自动生成')" />
      </el-form-item>
      <el-form-item :label="t('邮箱')" :class="{ 'af-span-2': mode === 'edit' }">
        <el-input v-model="form.email" :placeholder="t('如 email@example.com')" />
      </el-form-item>

      <template v-if="isCompatible">
        <el-form-item :label="t('厂商分类')" prop="catalogVendor">
          <el-select
            :model-value="form.catalogVendor"
            filterable
            class="af-full"
            :loading="vendorsLoading"
            :placeholder="t('选择已添加的 OpenAI 兼容厂商')"
            @change="(vendor: string) => applyVendorToForm(form, vendor)"
          >
            <el-option
              v-for="vendor in vendorOptions"
              :key="vendor.name"
              :label="vendor.name"
              :value="vendor.name"
            >
              <span class="vendor-option">
                <el-image
                  v-if="vendor.icon"
                  class="vendor-option__icon"
                  :src="vendor.icon"
                  fit="cover"
                />
                <span v-else class="vendor-option__icon vendor-option__icon--fallback">
                  {{ vendor.name.slice(0, 1).toUpperCase() }}
                </span>
                <span class="vendor-option__name">{{ vendor.name }}</span>
                <span class="vendor-option__count">{{ vendor.modelCount }} {{ t('个模型') }}</span>
              </span>
            </el-option>
          </el-select>
          <p v-if="!vendorsLoading && vendors.length === 0" class="af-hint">
            {{ t('还没有第三方 OpenAI 兼容厂商。') }}
            <el-button link type="primary" @click="emit('add-vendor')">
              {{ t('去添加模型') }}
            </el-button>
          </p>
        </el-form-item>
        <el-form-item v-if="form.catalogVendor" :label="t('账号类型预览')">
          <ProviderBadge
            :provider="form.catalogVendor"
            auth-method="api-key"
            :color="selectedVendor?.color"
            :icon="selectedVendor?.icon"
          />
          <p class="af-hint">{{ t('图标和配色来自该厂商在模型管理中的设置。') }}</p>
        </el-form-item>
      </template>

      <el-form-item v-if="mode === 'edit'" :label="t('启用')">
        <el-switch v-model="form.enabled" />
      </el-form-item>
    </div>
  </FormSection>
</template>

<style scoped lang="scss">
  .vendor-option {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;

    &__icon {
      flex: 0 0 auto;
      width: 18px;
      height: 18px;
      border-radius: 4px;

      &--fallback {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        background: var(--el-fill-color-light);
        color: var(--kp-text-secondary);
        font-size: 11px;
        font-weight: 800;
      }
    }

    &__name {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &__count {
      margin-left: auto;
      color: var(--kp-text-muted);
      font-size: 12px;
    }
  }
</style>
