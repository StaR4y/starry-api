<script setup lang="ts">
  import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
  import { ElMessage } from 'element-plus';
  import { CopyDocument, Cpu, Refresh, Search } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import ProviderLogo from '@/components/ProviderLogo.vue';
  import {
    DEFAULT_MODEL_CATALOG_DISPLAY,
    formatContextWindow,
    modelPriceChip,
    normalizeModelCatalogDisplay,
    pricingFromPublicModel,
  } from './catalogDisplay';
  import { systemApi } from '@/api';
  import { canonicalCatalogProvider, getFamilyOrder, getProviderOrder, orderOf } from '@/platforms/models';
  import type { ManagedModel, ModelCatalogDisplaySettings, ModelInfo } from '@/types/api';

  interface ProviderGroup {
    provider: string;
    models: ManagedModel[];
  }

  interface FamilyOption {
    name: string;
    count: number;
  }

  const loading = ref(false);
  const models = ref<ManagedModel[]>([]);
  const brokenIconIds = ref(new Set<string>());
  const keyword = ref('');
  const activeProvider = ref('');
  const activeFamily = ref('all');
  const page = ref(1);
  const pageSize = ref(24);
  const display = ref<ModelCatalogDisplaySettings>({ ...DEFAULT_MODEL_CATALOG_DISPLAY });
  const catalogOrder = ref<string[]>([]);
  const contentSwitching = ref(false);
  let switchTimer: ReturnType<typeof setTimeout> | undefined;

  function fromPublicModel(model: ModelInfo): ManagedModel {
    return {
      id: model.id,
      name: model.name || model.modelName || model.id,
      icon: model.icon,
      provider: canonicalCatalogProvider(model.provider || model.owned_by) || 'Custom',
      family: model.family || 'Custom',
      targetModelId: model.targetModelId || model.id,
      requestFormat: model.requestFormat || 'OPENAI_CHAT_COMPLETIONS',
      endpoint: model.endpoint || '/v1/chat/completions',
      contextWindow: model.maxInputTokens || model.context_length || 200000,
      description: model.description,
      enabled: true,
      builtIn: (model.owned_by || '').toLowerCase() !== 'admin',
      pricing: pricingFromPublicModel(model),
    };
  }

  async function loadModels() {
    loading.value = true;
    try {
      const [catalog, view] = await Promise.all([
        systemApi.models().then((items) => items.map(fromPublicModel)),
        systemApi.modelCatalogDisplay().catch(() => null),
      ]);
      models.value = catalog;
      if (view) {
        display.value = normalizeModelCatalogDisplay(view.display);
        catalogOrder.value = view.modelIds ?? [];
      }
    } catch {
      ElMessage.error('模型列表加载失败');
    } finally {
      loading.value = false;
    }
  }

  const summary = computed(() => {
    const providers = new Set(models.value.map((model) => model.provider)).size;
    return `${models.value.length} 个模型 · ${providers} 个厂商`;
  });

  const filteredModels = computed(() => {
    const query = keyword.value.trim().toLowerCase();
    if (!query) return models.value;
    return models.value.filter((model) =>
      [model.id, model.name, model.provider, model.family, model.targetModelId, model.endpoint].some(
        (value) => value.toLowerCase().includes(query),
      ),
    );
  });

  const providerGroups = computed<ProviderGroup[]>(() => {
    const groups = new Map<string, ManagedModel[]>();
    filteredModels.value.forEach((model) => {
      const provider = canonicalCatalogProvider(model.provider) || 'Other';
      groups.set(provider, [...(groups.get(provider) || []), model]);
    });
    const grouped = Array.from(groups, ([provider, providerModels]) => ({
      provider,
      models: providerModels,
    }));
    if (catalogOrder.value.length) return grouped;
    const providerOrder = getProviderOrder();
    return grouped.sort((left, right) => {
      const orderDifference =
        orderOf(providerOrder, left.provider) - orderOf(providerOrder, right.provider);
      return orderDifference || left.provider.localeCompare(right.provider);
    });
  });

  const showingAllProviders = computed(
    () => display.value.groupBy === 'none' && !activeProvider.value,
  );

  const activeGroup = computed(() =>
    providerGroups.value.find((group) => group.provider === activeProvider.value),
  );

  const familyOptions = computed<FamilyOption[]>(() => {
    const counts = new Map<string, number>();
    activeGroup.value?.models.forEach((model) => {
      counts.set(model.family, (counts.get(model.family) || 0) + 1);
    });
    const familyOrder = getFamilyOrder();
    return Array.from(counts, ([name, count]) => ({ name, count })).sort((left, right) => {
      const orderDifference = orderOf(familyOrder, left.name) - orderOf(familyOrder, right.name);
      return orderDifference || left.name.localeCompare(right.name);
    });
  });

  const selectedModels = computed(() => {
    if (showingAllProviders.value) return filteredModels.value;
    const providerModels = activeGroup.value?.models || [];
    return activeFamily.value === 'all'
      ? providerModels
      : providerModels.filter((model) => model.family === activeFamily.value);
  });

  const pagedModels = computed(() => {
    const start = (page.value - 1) * pageSize.value;
    return selectedModels.value.slice(start, start + pageSize.value);
  });

  const galleryClass = computed(() => [
    `is-layout-${display.value.layout}`,
    `is-density-${display.value.density}`,
    `is-style-${display.value.cardStyle}`,
    display.value.columns ? `is-cols-${display.value.columns}` : 'is-cols-auto',
  ]);

  watch(
    [providerGroups, () => display.value.groupBy],
    ([groups, groupBy]) => {
      if (groupBy === 'none') {
        if (activeProvider.value && !groups.some((group) => group.provider === activeProvider.value)) {
          activeProvider.value = '';
        }
        return;
      }
      if (!groups.some((group) => group.provider === activeProvider.value)) {
        activeProvider.value = groups[0]?.provider || '';
      }
    },
    { immediate: true },
  );

  watch(activeProvider, () => {
    activeFamily.value = 'all';
    page.value = 1;
  });

  watch([keyword, pageSize], () => {
    page.value = 1;
  });

  watch(familyOptions, (families) => {
    if (
      activeFamily.value !== 'all' &&
      !families.some((family) => family.name === activeFamily.value)
    ) {
      activeFamily.value = 'all';
    }
  });

  watch(
    () => selectedModels.value.length,
    (total) => {
      const lastPage = Math.max(1, Math.ceil(total / pageSize.value));
      if (page.value > lastPage) page.value = lastPage;
    },
  );

  function transitionContent(mutate: () => void) {
    if (switchTimer) clearTimeout(switchTimer);
    contentSwitching.value = true;
    switchTimer = setTimeout(() => {
      mutate();
      void nextTick(() => {
        contentSwitching.value = false;
      });
    }, 130);
  }

  function selectProvider(provider: string) {
    if (provider === activeProvider.value) return;
    transitionContent(() => {
      activeProvider.value = provider;
    });
  }

  function selectFamily(family: string) {
    if (family === activeFamily.value) return;
    transitionContent(() => {
      activeFamily.value = family;
      page.value = 1;
    });
  }

  async function copyText(text: string, message: string) {
    try {
      await navigator.clipboard.writeText(text);
      ElMessage.success(message);
    } catch {
      ElMessage.warning('复制失败，请手动复制');
    }
  }

  function copyAllIds() {
    return copyText(
      selectedModels.value.map((model) => model.id).join('\n'),
      '已复制当前分组中的模型 ID',
    );
  }

  function markIconAsBroken(modelId: string) {
    brokenIconIds.value = new Set([...brokenIconIds.value, modelId]);
  }

  function priceChip(model: ManagedModel) {
    return modelPriceChip(model.pricing);
  }

  onMounted(loadModels);
  onBeforeUnmount(() => {
    if (switchTimer) clearTimeout(switchTimer);
  });
</script>

<template>
  <div class="model-page-root">
    <PageSection title="模型 ID" subtitle="模型目录" :icon="Cpu">
      <template #summary>
        <span>{{ summary }}</span>
      </template>
      <template #actions>
        <el-tooltip content="复制当前分组模型 ID" placement="bottom">
          <el-button :icon="CopyDocument" aria-label="复制模型 ID" @click="copyAllIds" />
        </el-tooltip>
        <el-tooltip content="刷新" placement="bottom">
          <el-button :icon="Refresh" :loading="loading" aria-label="刷新" @click="loadModels" />
        </el-tooltip>
      </template>
      <template #toolbar>
        <el-input
          v-model="keyword"
          class="catalog-search"
          :prefix-icon="Search"
          clearable
          placeholder="搜索名称、ID、系列或 endpoint"
        />
      </template>

      <nav v-if="providerGroups.length" class="provider-chips" aria-label="模型厂商">
        <button
          v-if="display.groupBy === 'none'"
          type="button"
          class="provider-chip"
          :class="{ 'is-active': !activeProvider }"
          :aria-current="!activeProvider ? 'true' : undefined"
          @click="selectProvider('')"
        >
          <strong>全部</strong>
          <span>{{ filteredModels.length }}</span>
        </button>
        <button
          v-for="group in providerGroups"
          :key="group.provider"
          type="button"
          class="provider-chip"
          :class="{ 'is-active': group.provider === activeProvider }"
          :aria-current="group.provider === activeProvider ? 'true' : undefined"
          @click="selectProvider(group.provider)"
        >
          <ProviderLogo :provider="group.provider" :size="24" />
          <strong>{{ group.provider }}</strong>
          <span>{{ group.models.length }}</span>
        </button>
      </nav>

      <div class="catalog-content" :class="{ 'is-switching': contentSwitching }">
        <div
          v-if="activeGroup && !showingAllProviders"
          class="family-chips"
          role="tablist"
          aria-label="模型系列"
        >
          <button
            type="button"
            role="tab"
            :aria-selected="activeFamily === 'all'"
            :class="{ 'is-active': activeFamily === 'all' }"
            @click="selectFamily('all')"
          >
            全部 <span>{{ activeGroup.models.length }}</span>
          </button>
          <button
            v-for="family in familyOptions"
            :key="family.name"
            type="button"
            role="tab"
            :aria-selected="activeFamily === family.name"
            :class="{ 'is-active': activeFamily === family.name }"
            @click="selectFamily(family.name)"
          >
            {{ family.name }} <span>{{ family.count }}</span>
          </button>
        </div>

        <div
          v-if="activeGroup || showingAllProviders"
          v-loading="loading"
          class="model-gallery"
          :class="galleryClass"
        >
          <article v-for="model in pagedModels" :key="model.id" class="model-card">
            <header class="model-card__head">
              <el-avatar
                v-if="display.showIcon && model.icon && !brokenIconIds.has(model.id)"
                :size="display.layout === 'compact' ? 28 : 38"
                :src="model.icon"
                shape="square"
                class="model-card__icon"
                @error="markIconAsBroken(model.id)"
              />
              <ProviderLogo
                v-else-if="display.showIcon"
                :provider="model.provider"
                :size="display.layout === 'compact' ? 28 : 38"
              />
              <div class="model-card__title">
                <strong :title="model.name">{{ model.name }}</strong>
                <button
                  type="button"
                  class="model-card__id"
                  :title="`复制 ${model.id}`"
                  @click="copyText(model.id, '已复制模型 ID')"
                >
                  <code>{{ model.id }}</code>
                  <el-icon><CopyDocument /></el-icon>
                </button>
                <p v-if="display.showDescription && model.description" class="model-card__desc">
                  {{ model.description }}
                </p>
              </div>
            </header>
            <footer class="model-card__foot">
              <span v-if="display.showFamily" class="meta-chip" :title="`系列：${model.family}`">{{
                model.family
              }}</span>
              <span v-if="display.showContext" class="meta-chip" title="上下文窗口">
                {{ formatContextWindow(model.contextWindow) }}
              </span>
              <span
                v-if="priceChip(model)"
                class="meta-chip meta-chip--price"
                :title="priceChip(model)?.title"
              >
                {{ priceChip(model)?.text }}
              </span>
              <span v-if="display.showOrigin" class="meta-chip meta-chip--muted">{{
                model.builtIn ? '内置' : '自定义'
              }}</span>
            </footer>
          </article>
          <el-empty
            v-if="!loading && !pagedModels.length"
            class="gallery-empty"
            description="没有符合条件的模型"
            :image-size="72"
          />
        </div>

        <el-empty v-else-if="!loading" description="没有符合条件的模型" :image-size="72" />

        <div v-if="activeGroup || showingAllProviders" class="catalog-pagination">
          <span>当前分组共 {{ selectedModels.length }} 项</span>
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="pageSize"
            :page-sizes="[24, 48, 96]"
            :total="selectedModels.length"
            layout="sizes, prev, pager, next"
            background
          />
        </div>
      </div>
    </PageSection>
  </div>
</template>

<style scoped lang="scss">
  .catalog-search {
    width: min(460px, 100%);
  }

  .provider-chips {
    display: flex;
    gap: 10px;
    padding-bottom: 14px;
    overflow-x: auto;
  }

  .provider-chip {
    display: inline-flex;
    align-items: center;
    flex: 0 0 auto;
    min-height: 44px;
    gap: 9px;
    padding: 8px 14px;
    border: 1px solid var(--kp-divider);
    border-radius: 9px;
    background: rgba(255, 255, 255, 0.48);
    color: var(--kp-text);
    cursor: pointer;
    font-size: 13px;
    transition:
      border-color 0.16s ease,
      background 0.16s ease,
      box-shadow 0.16s ease;

    &:hover {
      background: rgba(255, 255, 255, 0.82);
      border-color: rgba(91, 141, 239, 0.35);
    }

    &.is-active {
      border-color: var(--el-color-primary);
      background: rgba(235, 243, 255, 0.72);
      box-shadow: 0 2px 8px rgba(41, 89, 177, 0.08);
    }

    strong {
      font-size: 13px;
      font-weight: 600;
      white-space: nowrap;
    }

    > span {
      color: var(--kp-text-muted);
      font-size: 11.5px;
      font-variant-numeric: tabular-nums;
    }

    &.is-active > span {
      color: var(--el-color-primary);
    }
  }

  .family-chips {
    display: flex;
    gap: 8px;
    margin-bottom: 18px;
    padding-bottom: 2px;
    overflow-x: auto;

    button {
      display: inline-flex;
      align-items: center;
      flex: 0 0 auto;
      gap: 6px;
      min-height: 34px;
      padding: 5px 12px;
      border: 1px solid transparent;
      border-radius: 7px;
      background: transparent;
      color: var(--kp-text-secondary);
      cursor: pointer;
      font-size: 12.5px;
      white-space: nowrap;
      transition:
        background 0.18s ease,
        border-color 0.18s ease,
        color 0.18s ease,
        box-shadow 0.18s ease;

      &:hover,
      &.is-active {
        background: #fff;
        border-color: var(--kp-divider);
        color: var(--kp-text);
      }

      &.is-active {
        box-shadow: 0 2px 8px rgba(31, 45, 80, 0.07);
        font-weight: 600;
      }

      span {
        color: var(--kp-text-muted);
        font-size: 10px;
        font-variant-numeric: tabular-nums;
      }
    }
  }

  .model-gallery {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
    gap: 14px;
    min-height: 200px;

    &.is-cols-2 {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    &.is-cols-3 {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
    &.is-cols-4 {
      grid-template-columns: repeat(4, minmax(0, 1fr));
    }
    &.is-layout-list,
    &.is-cols-1 {
      grid-template-columns: minmax(0, 1fr);
    }
    &.is-layout-compact:not(.is-cols-2):not(.is-cols-3):not(.is-cols-4):not(.is-cols-1) {
      grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
      gap: 10px;
    }
    &.is-density-compact {
      gap: 8px;
    }
    &.is-layout-list {
      gap: 8px;
    }
  }

  .catalog-content {
    transition:
      opacity 0.16s ease,
      transform 0.16s ease;
  }

  .catalog-content.is-switching {
    opacity: 0;
    transform: translateY(4px);
    pointer-events: none;
  }

  .model-card {
    display: flex;
    min-width: 0;
    flex-direction: column;
    justify-content: space-between;
    gap: 14px;
    padding: 16px 16px 12px;
    border: 1px solid var(--kp-divider);
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.6);
    transition:
      border-color 0.16s ease,
      box-shadow 0.16s ease,
      transform 0.16s ease;

    &:hover {
      border-color: rgba(91, 141, 239, 0.4);
      box-shadow: 0 6px 18px rgba(31, 45, 80, 0.09);
      transform: translateY(-2px);
    }

    &__head {
      display: flex;
      align-items: flex-start;
      min-width: 0;
      gap: 10px;
    }

    &__icon {
      flex: 0 0 38px;
      border: 1px solid var(--kp-divider);
      border-radius: 8px;
      background: #f2f4f8;
    }

    &__title {
      display: flex;
      min-width: 0;
      flex: 1;
      flex-direction: column;
      gap: 2px;
      line-height: 1.35;

      > strong {
        overflow: hidden;
        font-size: 13.5px;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }

    &__desc {
      display: -webkit-box;
      margin: 4px 0 0;
      overflow: hidden;
      color: var(--kp-text-muted);
      font-size: 12px;
      line-height: 1.45;
      line-clamp: 2;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
    }

    &__id {
      display: inline-flex;
      align-items: center;
      min-width: 0;
      gap: 4px;
      padding: 0;
      border: 0;
      background: transparent;
      color: var(--kp-text-muted);
      cursor: copy;
      font-size: 11.5px;
      text-align: left;

      code {
        overflow: hidden;
        font-size: 11.5px;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .el-icon {
        flex: 0 0 auto;
        font-size: 12px;
        opacity: 0;
        transition: opacity 0.16s ease;
      }

      &:hover {
        color: var(--el-color-primary);

        .el-icon {
          opacity: 1;
        }
      }
    }

    &__foot {
      display: flex;
      align-items: center;
      min-width: 0;
      gap: 7px;
      padding-top: 12px;
      border-top: 1px dashed var(--kp-divider);
    }
  }

  .model-gallery.is-style-outline .model-card {
    background: transparent;
  }

  .model-gallery.is-style-flat .model-card {
    border-color: transparent;
    background: rgba(246, 248, 251, 0.72);
    box-shadow: none;
  }

  .model-gallery.is-density-compact .model-card,
  .model-gallery.is-layout-compact .model-card {
    gap: 8px;
    padding: 12px;
  }

  .model-gallery.is-layout-list .model-card {
    flex-direction: row;
    align-items: center;
    gap: 16px;
    padding: 12px 16px;

    .model-card__foot {
      flex: 1;
      padding-top: 0;
      border-top: 0;
    }
  }

  .gallery-empty {
    grid-column: 1 / -1;
  }

  .meta-chip {
    overflow: hidden;
    max-width: 45%;
    padding: 3px 9px;
    border: 1px solid var(--kp-divider);
    border-radius: 6px;
    background: rgba(246, 248, 251, 0.8);
    color: var(--kp-text-secondary);
    font-size: 11px;
    line-height: 19px;
    text-overflow: ellipsis;
    white-space: nowrap;

    &--muted {
      border-style: dashed;
      color: var(--kp-text-muted);
    }

    &--price {
      max-width: none;
      color: var(--kp-text);
      font-variant-numeric: tabular-nums;
    }
  }

  .catalog-pagination {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    min-height: 56px;
    padding-top: 14px;

    > span {
      color: var(--kp-text-muted);
      font-size: 12px;
      white-space: nowrap;
    }
  }

  @media (max-width: 760px) {
    .catalog-search {
      width: 100%;
    }

    .model-gallery,
    .model-gallery.is-cols-2,
    .model-gallery.is-cols-3,
    .model-gallery.is-cols-4,
    .model-gallery.is-layout-compact {
      grid-template-columns: minmax(0, 1fr);
    }

    .catalog-pagination {
      align-items: flex-start;
      flex-direction: column;
      overflow-x: auto;
    }
  }
</style>
