<script setup lang="ts">
  /** 节点与订阅共用的重命名对话框。 */
  import { ref, watch } from 'vue';
  import { t } from '@/i18n';

  const props = defineProps<{
    modelValue: boolean;
    title: string;
    name: string;
    maxLength: number;
    saving?: boolean;
  }>();
  const emit = defineEmits<{
    'update:modelValue': [value: boolean];
    confirm: [name: string];
  }>();

  const draft = ref('');
  watch(
    () => props.modelValue,
    (open) => {
      if (open) draft.value = props.name;
    },
    { immediate: true },
  );

  const trimmed = () => draft.value.trim();
  const valid = () => trimmed().length > 0 && trimmed() !== props.name;
  function submit() {
    if (valid() && !props.saving) emit('confirm', trimmed());
  }
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    append-to-body
    :title="title"
    width="min(420px, 94vw)"
    :close-on-click-modal="!saving"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item :label="t('名称')">
        <el-input v-model="draft" :maxlength="maxLength" show-word-limit @keyup.enter="submit" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">
        {{ t('取消') }}
      </el-button>
      <el-button type="primary" :loading="saving" :disabled="!valid()" @click="submit">
        {{ t('保存') }}
      </el-button>
    </template>
  </el-dialog>
</template>
