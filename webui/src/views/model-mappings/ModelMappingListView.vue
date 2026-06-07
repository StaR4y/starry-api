<script setup lang="ts">
  /**
   * 模型映射管理。
   *
   * 三种类型：
   * - replace / alias：把客户端模型替换为目标模型
   * - loadbalance：在多个目标模型间按权重分流（weights 与 targetModels 等长才生效）
   *
   * UI 上对 targetModels / weights / apiKeyIds 用动态列表录入，
   * 提交时会把空字符串清理掉，避免后端校验失败。
   */
  import { onMounted, reactive, ref } from 'vue';
  import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
  import PageSection from '@/components/PageSection.vue';
  import { modelMappingsApi } from '@/api';
  import type {
    CreateModelMappingRequest,
    MappingType,
    ModelMapping,
    UpdateModelMappingRequest,
  } from '@/types/api';
  import { formatDateTime } from '@/utils/format';

  const loading = ref(false);
  const mappings = ref<ModelMapping[]>([]);

  async function loadMappings() {
    loading.value = true;
    try {
      mappings.value = await modelMappingsApi.list();
    } finally {
      loading.value = false;
    }
  }

  /* ============= 表单 ============= */

  const formVisible = ref(false);
  const formMode = ref<'create' | 'edit'>('create');
  const formRef = ref<FormInstance>();
  const formSubmitting = ref(false);
  const editingId = ref<string | null>(null);

  const form = reactive({
    name: '',
    mappingType: 'replace' as MappingType,
    sourceModel: '',
    targetModels: [''] as string[],
    weights: [] as number[],
    priority: 100,
    apiKeyIds: [] as string[],
    enabled: true,
  });

  const rules: FormRules = {
    name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
    sourceModel: [{ required: true, message: '请输入源模型', trigger: 'blur' }],
  };

  function resetForm() {
    Object.assign(form, {
      name: '',
      mappingType: 'replace',
      sourceModel: '',
      targetModels: [''],
      weights: [],
      priority: 100,
      apiKeyIds: [],
      enabled: true,
    });
  }

  function openCreate() {
    formMode.value = 'create';
    editingId.value = null;
    resetForm();
    formVisible.value = true;
  }

  function openEdit(row: ModelMapping) {
    formMode.value = 'edit';
    editingId.value = row.mappingId;
    Object.assign(form, {
      name: row.name,
      mappingType: row.mappingType,
      sourceModel: row.sourceModel,
      targetModels: row.targetModels.length ? [...row.targetModels] : [''],
      weights: row.weights ? [...row.weights] : [],
      priority: row.priority ?? 100,
      apiKeyIds: row.apiKeyIds ? [...row.apiKeyIds] : [],
      enabled: row.enabled,
    });
    formVisible.value = true;
  }

  function addTarget() {
    form.targetModels.push('');
    if (form.mappingType === 'loadbalance') form.weights.push(1);
  }
  function removeTarget(idx: number) {
    form.targetModels.splice(idx, 1);
    if (form.weights.length) form.weights.splice(idx, 1);
  }

  function onTypeChange(t: MappingType) {
    if (t === 'loadbalance') {
      form.weights = form.targetModels.map((_, i) => form.weights[i] ?? 1);
    } else {
      form.weights = [];
    }
  }

  async function submitForm() {
    if (!formRef.value) return;
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return;

    const targetModels = form.targetModels.map((s) => s.trim()).filter(Boolean);
    if (!targetModels.length) {
      ElMessage.error('至少配置一个目标模型');
      return;
    }
    const apiKeyIds = form.apiKeyIds.map((s) => s.trim()).filter(Boolean);

    const payload: CreateModelMappingRequest & UpdateModelMappingRequest = {
      name: form.name,
      mappingType: form.mappingType,
      sourceModel: form.sourceModel,
      targetModels,
      priority: form.priority,
      ...(form.mappingType === 'loadbalance' && form.weights.length === targetModels.length
        ? { weights: form.weights }
        : {}),
      ...(apiKeyIds.length ? { apiKeyIds } : {}),
    };

    formSubmitting.value = true;
    try {
      if (formMode.value === 'create') {
        await modelMappingsApi.create(payload);
        ElMessage.success('已创建');
      } else if (editingId.value) {
        await modelMappingsApi.update(editingId.value, { ...payload, enabled: form.enabled });
        ElMessage.success('已更新');
      }
      formVisible.value = false;
      await loadMappings();
    } finally {
      formSubmitting.value = false;
    }
  }

  async function toggleEnabled(row: ModelMapping) {
    await modelMappingsApi.update(row.mappingId, { enabled: !row.enabled });
    await loadMappings();
  }

  async function removeMapping(row: ModelMapping) {
    await ElMessageBox.confirm(`确认删除映射 ${row.name}？`, '危险操作', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'error',
    }).catch(() => Promise.reject(new Error('cancel')));
    await modelMappingsApi.remove(row.mappingId);
    ElMessage.success('已删除');
    await loadMappings();
  }

  onMounted(loadMappings);
</script>

<template>
  <PageSection title="模型映射" subtitle="将客户端模型 ID 映射到真实上游模型，并支持按权重分流">
    <template #actions>
      <el-button type="primary" @click="openCreate">新建映射</el-button>
    </template>

    <el-table v-loading="loading" :data="mappings" stripe>
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="类型" width="120">
        <template #default="{ row }">
          <el-tag effect="light" round>{{ row.mappingType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sourceModel" label="源模型" min-width="160" />
      <el-table-column label="目标模型" min-width="220">
        <template #default="{ row }">
          <span class="mono">{{ row.targetModels.join(', ') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="权重" width="140">
        <template #default="{ row }">
          {{ row.weights?.length ? row.weights.join(' / ') : '—' }}
        </template>
      </el-table-column>
      <el-table-column prop="priority" label="优先级" width="90" />
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" effect="light" round>
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="180">
        <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="toggleEnabled(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
          <el-button link type="danger" @click="removeMapping(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </PageSection>

  <el-dialog
    v-model="formVisible"
    :title="formMode === 'create' ? '新建映射' : '编辑映射'"
    width="640px"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="类型">
            <el-select v-model="form.mappingType" style="width: 100%" @change="onTypeChange">
              <el-option label="replace（替换）" value="replace" />
              <el-option label="alias（别名）" value="alias" />
              <el-option label="loadbalance（按权重分流）" value="loadbalance" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="优先级（数值越小越优先）">
            <el-input-number v-model="form.priority" :min="0" :step="10" style="width: 100%" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="源模型（支持通配符如 gpt-*）" prop="sourceModel">
        <el-input v-model="form.sourceModel" />
      </el-form-item>

      <el-form-item label="目标模型">
        <div class="target-list">
          <div
            v-for="(_, idx) in form.targetModels"
            :key="idx"
            class="target-list__row"
          >
            <el-input v-model="form.targetModels[idx]" placeholder="claude-sonnet-4.5" />
            <el-input-number
              v-if="form.mappingType === 'loadbalance'"
              v-model="form.weights[idx]"
              :min="1"
              :step="1"
              style="width: 120px"
              placeholder="权重"
            />
            <el-button link type="danger" :disabled="form.targetModels.length === 1" @click="removeTarget(idx)">
              移除
            </el-button>
          </div>
          <el-button size="small" @click="addTarget">添加</el-button>
        </div>
      </el-form-item>

      <el-form-item label="作用 API Key（留空表示全部）">
        <div class="target-list">
          <div v-for="(_, idx) in form.apiKeyIds" :key="idx" class="target-list__row">
            <el-input v-model="form.apiKeyIds[idx]" placeholder="API Key ID" />
            <el-button link type="danger" @click="form.apiKeyIds.splice(idx, 1)">移除</el-button>
          </div>
          <el-button size="small" @click="form.apiKeyIds.push('')">添加</el-button>
        </div>
      </el-form-item>

      <el-form-item v-if="formMode === 'edit'" label="启用">
        <el-switch v-model="form.enabled" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="formVisible = false">取消</el-button>
      <el-button type="primary" :loading="formSubmitting" @click="submitForm">提交</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
  .target-list {
    display: flex;
    flex-direction: column;
    gap: 8px;

    &__row {
      display: flex;
      gap: 8px;
      align-items: center;
    }
  }
</style>
