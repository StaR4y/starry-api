<script setup lang="ts">
  /**
   * 更多选项（默认折叠）：配额上限，以及 Kiro 类账号的 Machine ID。
   */
  import { t } from '@/i18n';
  import FormSection from './FormSection.vue';
  import { useAccountFormContext } from './context';

  const open = defineModel<boolean>('open', { default: false });

  const { form, spec } = useAccountFormContext();
</script>

<template>
  <FormSection v-model:open="open" collapsible :title="t('更多选项')">
    <div class="af-grid">
      <el-form-item :label="t('配额上限')">
        <el-input-number v-model="form.quotaLimit" :min="0" :step="1000" class="af-full" />
      </el-form-item>
      <el-form-item v-if="spec.machineId" label="Machine ID">
        <el-input v-model="form.machineId" :placeholder="t('机器 ID，可选')" />
      </el-form-item>
    </div>
  </FormSection>
</template>
