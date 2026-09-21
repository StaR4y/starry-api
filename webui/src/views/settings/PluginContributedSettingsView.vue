<script setup lang="ts">
  import { computed, onMounted, reactive, ref, watch } from 'vue';
  import { useRoute } from 'vue-router';
  import { ElMessage } from 'element-plus';
  import { Check, Refresh, Cpu } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { pluginsApi, type PluginModuleSettings, type PluginSettingField } from '@/api/plugins';

  const route = useRoute();
  const loading = ref(false);
  const saving = ref(false);
  const missing = ref(false);
  const page = ref<PluginModuleSettings | null>(null);
  const form = reactive<Record<string, any>>({});

  const pluginId = computed(() => String(route.params.pluginId ?? ''));

  function fieldDefault(field: PluginSettingField) {
    if (field.default !== undefined && field.default !== null) return field.default;
    if (field.type === 'switch') return false;
    if (field.type === 'number' || field.type === 'slider') return field.min ?? 0;
    return '';
  }

  function apply(next: PluginModuleSettings) {
    page.value = next;
    Object.keys(form).forEach((key) => delete form[key]);
    next.fields.forEach((field) => {
      const stored = next.config[field.key];
      form[field.key] = stored === undefined ? fieldDefault(field) : stored;
    });
  }

  async function load() {
    if (!pluginId.value) return;
    loading.value = true;
    missing.value = false;
    try {
      apply(await pluginsApi.moduleSettings(pluginId.value));
    } catch {
      page.value = null;
      missing.value = true;
    } finally {
      loading.value = false;
    }
  }

  async function save() {
    if (!pluginId.value) return;
    saving.value = true;
    try {
      apply(await pluginsApi.saveModuleSettings(pluginId.value, { ...form }));
      ElMessage.success('设置已保存');
    } catch {
      /* Retain unsaved settings. */
    } finally {
      saving.value = false;
    }
  }

  watch(pluginId, () => {
    void load();
  });
  onMounted(load);
</script>

<template>
  <PageSection :title="page?.title || '插件设置'" subtitle="内容与界面" :icon="Cpu">
    <template #actions>
      <el-tooltip content="重新加载">
        <el-button :icon="Refresh" aria-label="重新加载插件设置" :disabled="saving" @click="load" />
      </el-tooltip>
      <el-button type="primary" :icon="Check" :loading="saving" :disabled="missing" @click="save">保存</el-button>
    </template>

    <p v-if="missing" class="hint">插件未启动或没有设置页。</p>
    <el-form v-else v-loading="loading" label-position="top" :disabled="saving" @submit.prevent="save">
      <p v-if="page?.hint" class="hint">{{ page.hint }}</p>
      <el-form-item v-for="field in page?.fields ?? []" :key="field.key" :label="field.label">
        <el-switch v-if="field.type === 'switch'" v-model="form[field.key]" :aria-label="field.label" />
        <el-input
          v-else-if="field.type === 'textarea'"
          v-model="form[field.key]"
          type="textarea"
          :rows="field.rows || 6"
          :maxlength="field.max || 8000"
          show-word-limit
        />
        <el-select
          v-else-if="field.type === 'model'"
          v-model="form[field.key]"
          filterable
          allow-create
          default-first-option
          placeholder="选择模型"
        >
          <el-option
            v-for="model in page?.models ?? []"
            :key="model.id"
            :label="`${model.id} · ${model.provider}`"
            :value="model.id"
          />
        </el-select>
        <el-input-number
          v-else-if="field.type === 'number'"
          v-model="form[field.key]"
          :min="field.min ?? 1"
          :max="field.max ?? 100"
          controls-position="right"
        />
        <el-slider
          v-else-if="field.type === 'slider'"
          v-model="form[field.key]"
          :min="field.min ?? 0"
          :max="field.max ?? 2"
          :step="field.step ?? 0.1"
        />
        <el-input v-else v-model="form[field.key]" :maxlength="field.max || 256" show-word-limit />
      </el-form-item>
    </el-form>
  </PageSection>
</template>

<style scoped lang="scss">
  .hint {
    margin: 0 0 16px;
    color: var(--kp-text-muted);
    font-size: 12px;
  }

  :deep(.el-select) {
    width: min(100%, 420px);
  }

  :deep(.el-slider) {
    width: min(100%, 320px);
    margin: 0 12px;
  }
</style>
