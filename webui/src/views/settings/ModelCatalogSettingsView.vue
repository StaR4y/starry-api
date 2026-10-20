<script setup lang="ts">
  import { t } from '@/i18n';
  import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import {
    ElCollapseTransition,
    ElMessage,
    ElMessageBox,
    type FormInstance,
    type FormRules,
  } from 'element-plus';
  import {
    ArrowDown,
    CopyDocument,
    Cpu,
    Delete,
    Edit,
    Plus,
    Rank,
    Refresh,
    RefreshLeft,
  } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import ProviderLogo from '@/components/ProviderLogo.vue';
  import ContextWindowField from '@/views/models/components/ContextWindowField.vue';
  import ModelCatalogPricingPanel from '@/views/models/components/ModelCatalogPricingPanel.vue';
  import ModelCatalogStylePanel from '@/views/models/components/ModelCatalogStylePanel.vue';
  import ModelIconField from '@/views/models/components/ModelIconField.vue';
  import {
    DEFAULT_MODEL_CATALOG_DISPLAY,
    formatContextWindow,
    modelPriceChip,
    normalizeModelCatalogDisplay,
  } from '@/views/models/catalogDisplay';
  import { modelsApi, systemApi } from '@/api';
  import { canonicalCatalogProvider, getProviderOrder, orderOf } from '@/platforms/models';
  import type {
    ManagedModel,
    ModelCatalogDisplaySettings,
    UpsertManagedModelRequest,
  } from '@/types/api';
  import { isFirstClassAccountProvider } from '@/views/accounts/utils';

  type Workspace = 'catalog' | 'order' | 'pricing' | 'style';

  const router = useRouter();
  const loading = ref(false);
  const submitting = ref(false);
  const orderSaving = ref(false);
  const displaySaving = ref(false);
  const workspace = ref<Workspace>('catalog');
  const models = ref<ManagedModel[]>([]);
  const keyword = ref('');
  const statusFilter = ref('all');
  const vendorFilter = ref('all');
  const display = ref<ModelCatalogDisplaySettings>({ ...DEFAULT_MODEL_CATALOG_DISPLAY });
  const catalogOrder = ref<string[]>([]);
  const orderProvider = ref('');
  const dragModelId = ref('');
  const dropModelId = ref('');
  const dragProvider = ref('');
  const dropProvider = ref('');
  const dialogVisible = ref(false);
  const dialogAdvancedOpen = ref(false);
  const formRef = ref<FormInstance>();
  let orderTimer: ReturnType<typeof setTimeout> | undefined;
  let displayTimer: ReturnType<typeof setTimeout> | undefined;

  const form = reactive<UpsertManagedModelRequest>({
    id: '',
    name: '',
    icon: '',
    provider: 'Custom',
    family: 'Custom',
    targetModelId: '',
    requestFormat: 'OPENAI_CHAT_COMPLETIONS',
    endpoint: '/v1/chat/completions',
    contextWindow: 200000,
    description: '',
    enabled: true,
    badgeColor: '',
  });

  const rules: FormRules = {
    name: [
      {
        required: true,
        get message() {
          return t('请输入模型名称');
        },
        trigger: 'blur',
      },
    ],
    endpoint: [
      {
        required: true,
        get message() {
          return t('请输入 endpoint');
        },
        trigger: 'blur',
      },
    ],
    contextWindow: [
      {
        required: true,
        get message() {
          return t('请输入上下文窗口');
        },
        trigger: 'change',
      },
    ],
    badgeColor: [
      {
        validator: (_rule, value, callback) => {
          if (!isCustomCatalogVendor.value) {
            callback();
            return;
          }
          if (!/^#[0-9A-Fa-f]{6}$/.test(typeof value === 'string' ? value : '')) {
            callback(new Error(t('请为该厂商选择配色')));
            return;
          }
          callback();
        },
        trigger: 'change',
      },
    ],
  };

  const workspaceOptions = [
    {
      get label() {
        return t('目录管理');
      },
      value: 'catalog',
    },
    {
      get label() {
        return t('显示顺序');
      },
      value: 'order',
    },
    {
      get label() {
        return t('价格倍率');
      },
      value: 'pricing',
    },
    {
      get label() {
        return t('界面样式');
      },
      value: 'style',
    },
  ];

  const isCustomCatalogVendor = computed(() =>
    Boolean(form.provider?.trim() && !isFirstClassAccountProvider(form.provider)),
  );

  const summary = computed(() => {
    const enabled = models.value.filter((model) => model.enabled).length;
    const providers = new Set(models.value.map((model) => model.provider)).size;
    return t('{p0} 个模型 · {p1} 个厂商 · {p2} 个启用', {
      p0: models.value.length,
      p1: providers,
      p2: enabled,
    });
  });

  const filteredModels = computed(() => {
    const query = keyword.value.trim().toLowerCase();
    return models.value.filter((model) => {
      if (statusFilter.value === 'enabled' && !model.enabled) return false;
      if (statusFilter.value === 'disabled' && model.enabled) return false;
      if (statusFilter.value === 'custom' && model.builtIn) return false;
      if (statusFilter.value === 'builtin' && !model.builtIn) return false;
      if (vendorFilter.value !== 'all' && model.provider !== vendorFilter.value) return false;
      if (!query) return true;
      return [model.id, model.name, model.provider, model.family, model.targetModelId].some(
        (value) => value.toLowerCase().includes(query),
      );
    });
  });

  const providerGroups = computed(() => {
    const groups = new Map<string, ManagedModel[]>();
    models.value.forEach((model) => {
      const provider = canonicalCatalogProvider(model.provider) || 'Other';
      groups.set(provider, [...(groups.get(provider) || []), model]);
    });
    const grouped = Array.from(groups, ([provider, providerModels]) => ({
      provider,
      models: providerModels,
      enabled: providerModels.filter((model) => model.enabled).length,
    }));
    if (catalogOrder.value.length) return grouped;
    const providerOrder = getProviderOrder();
    return grouped.sort((left, right) => {
      const difference =
        orderOf(providerOrder, left.provider) - orderOf(providerOrder, right.provider);
      return difference || left.provider.localeCompare(right.provider);
    });
  });

  const orderModels = computed(
    () =>
      providerGroups.value.find((group) => group.provider === orderProvider.value)?.models || [],
  );

  const selectedVendorDeletable = computed(() => canDeleteVendor(vendorFilter.value));

  function canDeleteVendor(provider: string) {
    if (!provider || provider === 'all' || isFirstClassAccountProvider(provider)) return false;
    const group = providerGroups.value.find((item) => item.provider === provider);
    return Boolean(group?.models.length && group.models.every((model) => !model.builtIn));
  }

  async function load() {
    loading.value = true;
    try {
      const [catalog, view] = await Promise.all([
        modelsApi.list(),
        systemApi.modelCatalogDisplay().catch(() => null),
      ]);
      models.value = catalog;
      if (view) {
        display.value = normalizeModelCatalogDisplay(view.display);
        catalogOrder.value = view.modelIds ?? [];
      }
      if (
        !orderProvider.value ||
        !providerGroups.value.some((group) => group.provider === orderProvider.value)
      ) {
        orderProvider.value = providerGroups.value[0]?.provider || '';
      }
    } catch {
      ElMessage.error(t('模型目录加载失败'));
    } finally {
      loading.value = false;
    }
  }

  function openCreate() {
    void router.push({ name: 'settings-model-create' });
  }

  function openEdit(row: ManagedModel) {
    Object.assign(form, {
      id: row.id,
      name: row.name,
      icon: row.icon || '',
      provider: row.provider,
      family: row.family,
      targetModelId: row.targetModelId,
      requestFormat: row.requestFormat,
      endpoint: row.endpoint,
      contextWindow: row.contextWindow,
      description: row.description || '',
      enabled: row.enabled,
      badgeColor: row.badgeColor || '',
    });
    formRef.value?.clearValidate();
    dialogAdvancedOpen.value = false;
    dialogVisible.value = true;
  }

  function payloadFrom(row: ManagedModel | UpsertManagedModelRequest, enabled = row.enabled) {
    const id = row.id.trim();
    const customVendor = Boolean(
      row.provider?.trim() && !isFirstClassAccountProvider(row.provider),
    );
    return {
      id,
      name: row.name.trim(),
      icon: row.icon?.trim() || null,
      provider: row.provider?.trim() || 'Custom',
      family: row.family?.trim() || 'Custom',
      targetModelId: row.targetModelId?.trim() || id,
      requestFormat: row.requestFormat,
      endpoint: row.endpoint.trim(),
      contextWindow: row.contextWindow,
      description: row.description?.trim() || null,
      enabled,
      badgeColor: customVendor ? row.badgeColor || undefined : undefined,
    };
  }

  async function submitForm() {
    if (!formRef.value) return;
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return;
    submitting.value = true;
    try {
      await modelsApi.upsert(payloadFrom(form));
      ElMessage.success(t('模型已更新'));
      dialogVisible.value = false;
      await load();
    } finally {
      submitting.value = false;
    }
  }

  async function setEnabled(row: ManagedModel, enabled: boolean) {
    try {
      if (row.builtIn && !enabled) {
        await modelsApi.remove(row.id);
      } else {
        await modelsApi.upsert(payloadFrom(row, enabled));
      }
      ElMessage.success(enabled ? t('模型已启用') : t('模型已停用'));
      await load();
    } catch {
      ElMessage.error(t('更新状态失败'));
    }
  }

  async function removeModel(row: ManagedModel) {
    if (row.builtIn) {
      await setEnabled(row, false);
      return;
    }
    try {
      await ElMessageBox.confirm(
        t('确认删除自定义模型 {p0}？此操作不可恢复。', { p0: row.id }),
        t('删除模型'),
        {
          get confirmButtonText() {
            return t('删除');
          },
          get cancelButtonText() {
            return t('取消');
          },
          type: 'warning',
        },
      );
    } catch {
      return;
    }
    await modelsApi.remove(row.id);
    ElMessage.success(t('自定义模型已删除'));
    await load();
  }

  async function removeVendor(provider: string) {
    const group = providerGroups.value.find((item) => item.provider === provider);
    if (!group || !canDeleteVendor(provider)) return;
    try {
      await ElMessageBox.confirm(
        t('确认删除厂商 {p0}？将同时删除其下 {p1} 个自定义模型，此操作不可恢复。', {
          p0: provider,
          p1: group.models.length,
        }),
        t('删除厂商'),
        {
          get confirmButtonText() {
            return t('删除');
          },
          get cancelButtonText() {
            return t('取消');
          },
          type: 'warning',
        },
      );
    } catch {
      return;
    }
    try {
      await modelsApi.removeVendor(provider);
      if (vendorFilter.value === provider) vendorFilter.value = 'all';
      ElMessage.success(t('厂商已删除'));
      await load();
    } catch {
      ElMessage.error(t('删除厂商失败'));
    }
  }

  async function copyText(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      ElMessage.success(t('已复制模型 ID'));
    } catch {
      ElMessage.warning(t('复制失败，请手动复制'));
    }
  }

  function bindDrag(event: DragEvent, value: string) {
    event.dataTransfer?.setData('text/plain', value);
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
  }

  function applyViewOrder(nextView: ManagedModel[]) {
    const viewIds = new Set(nextView.map((model) => model.id));
    let index = 0;
    models.value = models.value.map((model) => (viewIds.has(model.id) ? nextView[index++] : model));
    catalogOrder.value = models.value.map((model) => model.id);
  }

  function moveModel(fromId: string, toId: string) {
    if (!fromId || fromId === toId) return;
    const view = [...orderModels.value];
    const from = view.findIndex((model) => model.id === fromId);
    const to = view.findIndex((model) => model.id === toId);
    if (from < 0 || to < 0) return;
    const [item] = view.splice(from, 1);
    view.splice(to, 0, item);
    applyViewOrder(view);
    schedulePersistOrder();
  }

  function moveProvider(fromProvider: string, toProvider: string) {
    if (!fromProvider || fromProvider === toProvider) return;
    const groups = new Map<string, ManagedModel[]>();
    models.value.forEach((model) => {
      const provider = canonicalCatalogProvider(model.provider) || 'Other';
      groups.set(provider, [...(groups.get(provider) || []), model]);
    });
    const keys = [...groups.keys()];
    const from = keys.indexOf(fromProvider);
    const to = keys.indexOf(toProvider);
    if (from < 0 || to < 0) return;
    const [item] = keys.splice(from, 1);
    keys.splice(to, 0, item);
    models.value = keys.flatMap((key) => groups.get(key) || []);
    catalogOrder.value = models.value.map((model) => model.id);
    schedulePersistOrder();
  }

  function schedulePersistOrder() {
    if (orderTimer) clearTimeout(orderTimer);
    orderTimer = setTimeout(() => {
      void persistOrder();
    }, 280);
  }

  async function persistOrder() {
    orderSaving.value = true;
    try {
      await modelsApi.reorder({ modelIds: models.value.map((model) => model.id) });
    } catch {
      ElMessage.error(t('显示顺序保存失败'));
      await load();
    } finally {
      orderSaving.value = false;
    }
  }

  async function restoreDefaultOrder() {
    try {
      await ElMessageBox.confirm(
        t('将恢复为系统默认的厂商与模型顺序，对所有用户的「模型 ID」页生效。'),
        t('恢复默认顺序'),
        {
          get confirmButtonText() {
            return t('恢复默认');
          },
          get cancelButtonText() {
            return t('取消');
          },
          type: 'warning',
        },
      );
    } catch {
      return;
    }
    orderSaving.value = true;
    try {
      await modelsApi.reorder({ restoreDefault: true });
      catalogOrder.value = [];
      ElMessage.success(t('已恢复默认顺序'));
      await load();
    } finally {
      orderSaving.value = false;
    }
  }

  function onDisplayChange(next: ModelCatalogDisplaySettings) {
    display.value = normalizeModelCatalogDisplay(next);
    if (displayTimer) clearTimeout(displayTimer);
    displayTimer = setTimeout(() => {
      void persistDisplay(display.value);
    }, 320);
  }

  async function persistDisplay(next: ModelCatalogDisplaySettings) {
    displaySaving.value = true;
    try {
      display.value = normalizeModelCatalogDisplay(await modelsApi.updateDisplay(next));
    } catch {
      ElMessage.error(t('界面样式保存失败'));
    } finally {
      displaySaving.value = false;
    }
  }

  async function resetDisplay() {
    try {
      await ElMessageBox.confirm(
        t('将恢复「模型 ID」页的默认布局和显示字段，对所有用户生效。'),
        t('恢复默认样式'),
        {
          get confirmButtonText() {
            return t('恢复默认');
          },
          get cancelButtonText() {
            return t('取消');
          },
          type: 'warning',
        },
      );
    } catch {
      return;
    }
    displaySaving.value = true;
    try {
      display.value = normalizeModelCatalogDisplay(
        await modelsApi.updateDisplay({ restoreDefault: true }),
      );
      ElMessage.success(t('已恢复默认样式'));
    } finally {
      displaySaving.value = false;
    }
  }

  onMounted(load);
  onBeforeUnmount(() => {
    if (orderTimer) clearTimeout(orderTimer);
    if (displayTimer) clearTimeout(displayTimer);
  });
</script>

<template>
  <PageSection :title="t('模型管理')" :subtitle="t('业务配置')" :icon="Cpu">
    <template #summary>
      <span>{{ summary }}</span>
    </template>
    <template #actions>
      <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('刷新') }}</el-button>
      <el-button type="primary" :icon="Plus" @click="openCreate">{{ t('添加模型') }}</el-button>
    </template>

    <p class="page-lead">{{ t('这里管理对外模型目录、计费倍率和「模型 ID」页的展示方式。') }}</p>

    <el-segmented v-model="workspace" :options="workspaceOptions" block class="workspace-switch" />

    <div v-show="workspace === 'catalog'" v-loading="loading" class="workspace">
      <div class="catalog-toolbar">
        <el-input v-model="keyword" clearable :placeholder="t('搜索名称、ID、厂商或系列')" />
        <el-select v-model="vendorFilter" :aria-label="t('厂商筛选')">
          <el-option :label="t('全部厂商')" value="all" />
          <el-option
            v-for="group in providerGroups"
            :key="group.provider"
            :label="group.provider"
            :value="group.provider"
          />
        </el-select>
        <el-select v-model="statusFilter" :aria-label="t('状态筛选')">
          <el-option :label="t('全部状态')" value="all" />
          <el-option :label="t('已启用')" value="enabled" />
          <el-option :label="t('已停用')" value="disabled" />
          <el-option :label="t('内置')" value="builtin" />
          <el-option :label="t('自定义')" value="custom" />
        </el-select>
        <el-button
          v-if="selectedVendorDeletable"
          type="danger"
          plain
          :icon="Delete"
          @click="removeVendor(vendorFilter)"
        >
          {{ t('删除厂商') }}
        </el-button>
      </div>

      <el-table
        :data="filteredModels"
        stripe
        class="catalog-table"
        :empty-text="t('没有符合条件的模型')"
      >
        <el-table-column :label="t('模型')" min-width="260">
          <template #default="{ row }">
            <div class="model-identity">
              <el-avatar v-if="row.icon" :size="32" :src="row.icon" shape="square" />
              <ProviderLogo v-else :provider="row.provider" :size="32" />
              <div>
                <strong>{{ row.name }}</strong>
                <button type="button" class="model-id" @click="copyText(row.id)">
                  <code>{{ row.id }}</code>
                  <el-icon><CopyDocument /></el-icon>
                </button>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('厂商')" prop="provider" min-width="120" />
        <el-table-column :label="t('系列')" prop="family" min-width="110" />
        <el-table-column :label="t('上下文')" width="92">
          <template #default="{ row }">{{ formatContextWindow(row.contextWindow) }}</template>
        </el-table-column>
        <el-table-column :label="t('价格 / MTok')" min-width="150">
          <template #default="{ row }">
            <span :title="modelPriceChip(row.pricing)?.title">
              {{ modelPriceChip(row.pricing)?.text || '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('来源')" width="88">
          <template #default="{ row }">
            <el-tag size="small" :type="row.builtIn ? 'info' : 'success'" effect="plain">
              {{ row.builtIn ? t('内置') : t('自定义') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('状态')" width="88">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="setEnabled(row, Boolean($event))" />
          </template>
        </el-table-column>
        <el-table-column label="" width="108" align="right">
          <template #default="{ row }">
            <el-tooltip :content="t('编辑')" placement="top">
              <el-button link :icon="Edit" :aria-label="t('编辑')" @click="openEdit(row)" />
            </el-tooltip>
            <el-tooltip v-if="!row.builtIn" :content="t('删除')" placement="top">
              <el-button
                link
                type="danger"
                :icon="Delete"
                :aria-label="t('删除')"
                @click="removeModel(row)"
              />
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-show="workspace === 'order'" v-loading="loading" class="workspace">
      <div class="workspace-head">
        <p>
          {{
            t('拖动左侧厂商或右侧模型，即可调整所有用户在「模型 ID」页看到的顺序。松手后自动保存。')
          }}
        </p>
        <el-button :icon="RefreshLeft" :loading="orderSaving" @click="restoreDefaultOrder">
          {{ t('恢复默认顺序') }}
        </el-button>
      </div>
      <div class="order-board">
        <section class="order-pane">
          <header>
            <strong>{{ t('厂商') }}</strong>
            <span>{{ providerGroups.length }}</span>
          </header>
          <div class="order-list">
            <div
              v-for="group in providerGroups"
              :key="group.provider"
              class="order-item"
              :class="{
                'is-active': group.provider === orderProvider,
                'is-drop-target': dropProvider === group.provider,
                'is-dragging': dragProvider === group.provider,
              }"
              draggable="true"
              role="button"
              tabindex="0"
              @click="orderProvider = group.provider"
              @keydown.enter="orderProvider = group.provider"
              @dragstart="
                dragProvider = group.provider;
                bindDrag($event, group.provider);
              "
              @dragover.prevent="dropProvider = group.provider"
              @drop.prevent="moveProvider(dragProvider, group.provider)"
              @dragend="
                dragProvider = '';
                dropProvider = '';
              "
            >
              <el-icon class="drag-handle"><Rank /></el-icon>
              <ProviderLogo :provider="group.provider" :size="22" />
              <span class="order-item__text">
                <strong>{{ group.provider }}</strong>
                <small>{{ group.enabled }}/{{ group.models.length }} {{ t('启用') }}</small>
              </span>
              <el-tooltip
                v-if="canDeleteVendor(group.provider)"
                :content="t('删除厂商')"
                placement="top"
              >
                <el-button
                  link
                  type="danger"
                  :icon="Delete"
                  :aria-label="t('删除厂商')"
                  @click.stop="removeVendor(group.provider)"
                  @mousedown.stop
                />
              </el-tooltip>
            </div>
          </div>
        </section>
        <section class="order-pane">
          <header>
            <strong>{{ orderProvider || t('模型') }}</strong>
            <span>{{ orderModels.length }}</span>
          </header>
          <div class="order-list">
            <div
              v-for="model in orderModels"
              :key="model.id"
              class="order-item"
              :class="{
                'is-disabled': !model.enabled,
                'is-drop-target': dropModelId === model.id,
                'is-dragging': dragModelId === model.id,
              }"
              draggable="true"
              @dragstart="
                dragModelId = model.id;
                bindDrag($event, model.id);
              "
              @dragover.prevent="dropModelId = model.id"
              @drop.prevent="moveModel(dragModelId, model.id)"
              @dragend="
                dragModelId = '';
                dropModelId = '';
              "
            >
              <el-icon class="drag-handle"><Rank /></el-icon>
              <el-avatar v-if="model.icon" :size="22" :src="model.icon" shape="square" />
              <ProviderLogo v-else :provider="model.provider" :size="22" />
              <span class="order-item__text">
                <strong>{{ model.name }}</strong>
                <small>{{ model.id }}</small>
              </span>
            </div>
            <el-empty
              v-if="!orderModels.length"
              :description="t('选择左侧厂商查看模型')"
              :image-size="64"
            />
          </div>
        </section>
      </div>
    </div>

    <div v-if="workspace === 'pricing'" class="workspace">
      <ModelCatalogPricingPanel @saved="load" />
    </div>

    <div v-show="workspace === 'style'" class="workspace">
      <div class="workspace-head">
        <p>
          {{ t('样式只影响「模型 ID」页的展示，不改变模型能力和计费。保存后对所有用户立即生效。') }}
        </p>
        <el-button :icon="RefreshLeft" :loading="displaySaving" @click="resetDisplay">
          {{ t('恢复默认样式') }}
        </el-button>
      </div>
      <ModelCatalogStylePanel :model-value="display" @update:model-value="onDisplayChange" />
    </div>
  </PageSection>

  <el-dialog
    v-model="dialogVisible"
    :title="t('编辑模型')"
    width="640px"
    class="model-dialog"
    destroy-on-close
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <div class="form-grid">
        <el-form-item :label="t('模型 ID')" prop="id">
          <el-input v-model="form.id" disabled placeholder="gpt-4.1" />
        </el-form-item>
        <el-form-item :label="t('模型名称')" prop="name">
          <el-input v-model="form.name" placeholder="GPT-4.1" />
        </el-form-item>
        <el-form-item :label="t('厂商')">
          <el-input v-model="form.provider" placeholder="OpenAI" />
        </el-form-item>
        <el-form-item v-if="isCustomCatalogVendor" :label="t('厂商配色')" prop="badgeColor">
          <div class="vendor-color-row">
            <el-color-picker
              :model-value="form.badgeColor || undefined"
              color-format="hex"
              :predefine="[
                '#4D6BFE',
                '#10A37F',
                '#7C3AED',
                '#D97757',
                '#0EA5E9',
                '#E11D48',
                '#CA8A04',
                '#0F766E',
              ]"
              @update:model-value="form.badgeColor = $event || ''"
            />
            <span class="vendor-color-row__hint">{{ t('账号类型会使用这个颜色') }}</span>
          </div>
        </el-form-item>
        <el-form-item :label="t('模型系列')">
          <el-input v-model="form.family" placeholder="GPT" />
        </el-form-item>
        <el-form-item :label="t('上下文窗口')" prop="contextWindow" class="form-grid__wide">
          <ContextWindowField v-model="form.contextWindow" />
        </el-form-item>
        <el-form-item :label="t('状态')">
          <el-switch v-model="form.enabled" inline-prompt />
        </el-form-item>
      </div>

      <div class="advanced-section" :class="{ 'is-open': dialogAdvancedOpen }">
        <button
          type="button"
          class="advanced-toggle"
          :aria-expanded="dialogAdvancedOpen"
          @click="dialogAdvancedOpen = !dialogAdvancedOpen"
        >
          <span class="advanced-toggle__text">
            <strong>{{ t('高级设置') }}</strong>
            <small>{{ t('上游映射、图标、Endpoint 与描述') }}</small>
          </span>
          <el-icon class="toggle-arrow"><ArrowDown /></el-icon>
        </button>
        <el-collapse-transition>
          <div v-show="dialogAdvancedOpen" class="form-grid advanced-body">
            <el-form-item :label="t('目标模型 ID')" class="form-grid__wide">
              <el-input v-model="form.targetModelId" :placeholder="form.id || t('上游模型 ID')" />
              <p class="field-hint">
                {{ t('留空则与模型 ID 相同；仅当对外 ID 需要映射到上游真实模型时填写') }}
              </p>
            </el-form-item>
            <el-form-item :label="t('模型图标')" class="form-grid__wide">
              <ModelIconField v-model:icon="form.icon" :provider="form.provider" />
            </el-form-item>
            <el-form-item label="Endpoint" prop="endpoint" class="form-grid__wide">
              <el-input v-model="form.endpoint" placeholder="/v1/chat/completions" />
            </el-form-item>
            <el-form-item :label="t('描述')" class="form-grid__wide">
              <el-input
                v-model="form.description"
                type="textarea"
                :rows="3"
                maxlength="1024"
                show-word-limit
              />
            </el-form-item>
          </div>
        </el-collapse-transition>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">{{ t('取消') }}</el-button>
      <el-button type="primary" :loading="submitting" @click="submitForm">
        {{
          t('保存')
        }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
  .page-lead {
    margin: 0 0 16px;
    color: var(--kp-text-muted);
    font-size: 13px;
    line-height: 1.6;
  }

  .workspace-switch {
    margin-bottom: 18px;
  }

  .workspace {
    min-height: 280px;
  }

  .workspace-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 16px;

    p {
      margin: 0;
      color: var(--kp-text-muted);
      font-size: 13px;
      line-height: 1.6;
    }
  }

  .catalog-toolbar {
    display: flex;
    gap: 10px;
    margin-bottom: 14px;

    .el-input {
      width: min(420px, 100%);
    }

    .el-select {
      width: 140px;
    }

    .el-button {
      margin-left: auto;
    }
  }

  .model-identity {
    display: flex;
    align-items: center;
    min-width: 0;
    gap: 10px;

    strong {
      display: block;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .model-id {
    display: inline-flex;
    align-items: center;
    min-width: 0;
    gap: 4px;
    padding: 0;
    border: 0;
    background: transparent;
    color: var(--kp-text-muted);
    cursor: copy;
    font-size: 12px;

    code {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &:hover {
      color: var(--el-color-primary);
    }
  }

  .order-board {
    display: grid;
    grid-template-columns: minmax(220px, 280px) minmax(0, 1fr);
    gap: 14px;
    min-height: 420px;
  }

  .order-pane {
    display: flex;
    min-width: 0;
    flex-direction: column;
    border: 1px solid var(--kp-divider);
    border-radius: 12px;
    background: rgba(255, 255, 255, 0.42);
    overflow: hidden;

    header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 12px 14px;
      border-bottom: 1px solid var(--kp-divider);

      strong {
        font-size: 13px;
      }

      span {
        color: var(--kp-text-muted);
        font-size: 12px;
        font-variant-numeric: tabular-nums;
      }
    }
  }

  .order-list {
    display: flex;
    min-height: 0;
    flex: 1;
    flex-direction: column;
    gap: 6px;
    padding: 10px;
    overflow: auto;
  }

  .order-item {
    display: flex;
    align-items: center;
    width: 100%;
    min-height: 48px;
    gap: 10px;
    padding: 8px 10px;
    border: 1px solid transparent;
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.7);
    color: inherit;
    cursor: grab;
    text-align: left;

    &:hover,
    &.is-active {
      border-color: rgba(91, 141, 239, 0.35);
      background: #fff;
    }

    &.is-active {
      box-shadow: 0 2px 8px rgba(41, 89, 177, 0.08);
    }

    &.is-drop-target {
      border-color: var(--el-color-primary);
    }

    &.is-dragging,
    &.is-disabled {
      opacity: 0.5;
    }

    &__text {
      display: flex;
      min-width: 0;
      flex: 1;
      flex-direction: column;
      gap: 1px;

      strong,
      small {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      strong {
        font-size: 13px;
      }

      small {
        color: var(--kp-text-muted);
        font-size: 11.5px;
      }
    }

    :deep(.el-button) {
      flex: 0 0 auto;
      width: 26px;
      height: 26px;
      margin: 0;
      padding: 0 !important;
    }
  }

  .drag-handle {
    color: var(--kp-text-muted);
  }

  .vendor-color-row {
    display: flex;
    align-items: center;
    gap: 12px;
    min-height: 32px;

    &__hint {
      color: var(--kp-text-muted);
      font-size: 12px;
    }
  }

  .form-grid {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 0 16px;

    &__wide {
      grid-column: 1 / -1;
    }
  }

  .field-hint {
    width: 100%;
    margin: 4px 0 0;
    color: var(--kp-text-muted);
    font-size: 11.5px;
    line-height: 1.5;
  }

  .advanced-section {
    margin-top: 6px;
    padding-top: 16px;
    border-top: 1px solid var(--kp-divider);
  }

  .advanced-toggle {
    display: flex;
    align-items: center;
    width: 100%;
    gap: 12px;
    padding: 12px 14px;
    margin-inline: -14px;
    border: 0;
    border-radius: 10px;
    background: transparent;
    color: var(--kp-text);
    cursor: pointer;
    text-align: left;

    &:hover {
      background: rgba(255, 255, 255, 0.65);
    }

    &__text {
      display: flex;
      min-width: 0;
      flex: 1;
      align-items: baseline;
      gap: 10px;

      small {
        overflow: hidden;
        color: var(--kp-text-muted);
        font-size: 11.5px;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }

    .toggle-arrow {
      color: var(--kp-text-muted);
      transition: transform 0.2s ease;
    }
  }

  .advanced-section.is-open .toggle-arrow {
    transform: rotate(180deg);
  }

  .advanced-body {
    padding-top: 14px;
  }

  :global(.model-dialog) {
    display: flex;
    flex-direction: column;
    max-height: calc(100vh - 32px);
    margin-top: 16px;
  }

  :global(.model-dialog .el-dialog__body) {
    min-height: 0;
    overflow-y: auto;
  }

  @media (max-width: 860px) {
    .workspace-head,
    .catalog-toolbar,
    .order-board {
      display: flex;
      flex-direction: column;
    }

    .order-board {
      min-height: 0;
    }

    .form-grid {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
