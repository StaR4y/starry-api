<script setup lang="ts">
  import { locale, t } from '@/i18n';
  import { computed, onMounted, ref, watch } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { Check, Delete, Edit, Plus, Refresh, RefreshLeft, Search } from '@element-plus/icons-vue';
  import { adminPaymentApi, modelPricingApi, modelsApi } from '@/api';
  import { sourceTiersApi, type SourceTierOverview } from '@/api/sourceTiers';
  import {
    previewTiers,
    vendorOfProvider,
    type PreviewTier,
  } from '@/views/settings/components/source-tiers/tierStatusMeta';
  import { formatDateTime } from '@/utils/format';
  import ProviderLogo from '@/components/ProviderLogo.vue';
  import { canonicalCatalogProvider } from '@/platforms/models';
  import type {
    ManagedModel,
    ModelPricingPreview,
    ModelPricingRule,
    ModelPricingSourceStatus,
    ModelPublicPricing,
    UpdateModelPricingRuleRequest,
  } from '@/types/api';

  type EditableRule = UpdateModelPricingRuleRequest & {
    ruleId: string;
    enabled: boolean;
    priority: number;
  };
  const emit = defineEmits<{ saved: [] }>();
  const loading = ref(true);
  const ready = ref(false);
  const submitting = ref(false);
  const syncing = ref(false);
  const savingSource = ref(false);
  const rules = ref<EditableRule[]>([]);
  const savedRules = ref('[]');
  const modelIds = ref<string[]>([]);
  const catalog = ref<ManagedModel[]>([]);
  const selectedProvider = ref('');
  const billingMultiplier = ref(1);
  const keyword = ref('');
  const dialogVisible = ref(false);
  const editingId = ref<string | null>(null);
  const draft = ref<EditableRule>(newRule());
  const previewModel = ref('gpt-5.5');
  const preview = ref<ModelPricingPreview | null>(null);
  const previewing = ref(false);
  let previewSequence = 0;
  // 号源分级：预览的模型所在厂商如果启用了分级，预览一并算出每个号池的价格
  const tierOverview = ref<SourceTierOverview | null>(null);
  const previewedTiers = ref<PreviewTier[]>([]);
  const source = ref<ModelPricingSourceStatus>({
    remoteRefreshEnabled: true,
    syncCatalogIds: false,
    sourceUrl: '',
    checksumUrl: '',
    catalogEntries: 0,
    refreshedAt: null,
    lastError: null,
  });
  const busy = computed(() => !ready.value || submitting.value);
  const dirty = computed(() => JSON.stringify(rules.value) !== savedRules.value);
  const legacyCount = computed(() => rules.value.filter(isLegacy).length);
  const enabledCount = computed(() => rules.value.filter((rule) => rule.enabled).length);
  const filteredRules = computed(() =>
    rules.value.filter(
      (rule) =>
        (rule.scope || 'model') === 'model' &&
        rule.modelPattern.toLowerCase().includes(keyword.value.trim().toLowerCase()),
    ),
  );
  const providers = computed(() =>
    [
      ...new Set([
        ...catalog.value.map((model) => canonicalCatalogProvider(model.provider)),
        ...rules.value
          .filter((rule) => rule.scope === 'provider' || rule.scope === 'family')
          .map((rule) => rule.provider || ''),
      ]),
    ]
      .filter(Boolean)
      .sort(),
  );
  const providerModels = computed(() =>
    catalog.value.filter(
      (model) => canonicalCatalogProvider(model.provider) === selectedProvider.value,
    ),
  );
  const families = computed(() =>
    [
      ...new Set([
        ...providerModels.value.map((model) => model.family),
        ...rules.value
          .filter((rule) => rule.scope === 'family' && rule.provider === selectedProvider.value)
          .map((rule) => rule.family || ''),
      ]),
    ]
      .filter(Boolean)
      .sort(),
  );
  const providerRule = computed(() => scopedRule());
  const providerMultiplier = computed(() =>
    providerRule.value?.enabled
      ? Number(providerRule.value.priceMultiplier)
      : billingMultiplier.value,
  );
  const exactPatternMissing = computed(() => {
    const pattern = draft.value.modelPattern.trim();
    const normalize = (id: string) =>
      id
        .toLowerCase()
        .replace(
          /^(?:(?:starry|openai|anthropic|google|gemini|deepseek|openai-compatible)\/)+/,
          '',
        );
    return (
      (draft.value.scope || 'model') === 'model' &&
      pattern &&
      !pattern.includes('*') &&
      !modelIds.value.some((id) => normalize(id) === normalize(pattern))
    );
  });
  const modelOptions = computed(() =>
    [
      ...new Set([
        ...modelIds.value,
        ...rules.value
          .filter((rule) => (rule.scope || 'model') === 'model')
          .map((rule) => rule.modelPattern),
      ]),
    ].sort(),
  );
  const matchedRules = computed(() =>
    (preview.value?.matchedRuleIds || [])
      .map((id) => rules.value.find((rule) => rule.ruleId === id))
      .filter((rule): rule is EditableRule => Boolean(rule)),
  );
  const tierPrices = computed(() =>
    (preview.value?.factorPricing ?? []).flatMap((entry, index) => {
      const tier = previewedTiers.value[index];
      return tier ? [{ tier, pricing: entry.pricing }] : [];
    }),
  );
  const tierPriceColumns = computed(() => ({
    gridTemplateColumns: `minmax(90px, 1fr) repeat(${tierPrices.value.length}, minmax(80px, 1fr))`,
  }));
  const priceFields: { label: string; key: keyof ModelPublicPricing }[] = [
    {
      get label() {
        return t('总量参考价');
      },
      key: 'totalPricePerMillionTokens',
    },
    {
      get label() {
        return t('输入');
      },
      key: 'inputPricePerMillionTokens',
    },
    {
      get label() {
        return t('输出');
      },
      key: 'outputPricePerMillionTokens',
    },
    {
      get label() {
        return t('缓存读取');
      },
      key: 'cacheReadPricePerMillionTokens',
    },
    {
      get label() {
        return t('缓存写入');
      },
      key: 'cacheWritePricePerMillionTokens',
    },
  ];

  function newRule(): EditableRule {
    return {
      ruleId: crypto.randomUUID(),
      enabled: true,
      modelPattern: '',
      priceMultiplier: 1,
      priority: 100,
      scope: 'model',
    };
  }
  function ruleLabel(rule: EditableRule) {
    if (rule.scope === 'provider') return rule.provider + t(' · 全部系列');
    if (rule.scope === 'family') return rule.provider + ' · ' + rule.family;
    return rule.modelPattern;
  }
  function ruleIdentity(rule: EditableRule) {
    return JSON.stringify(
      (rule.scope || 'model') === 'model'
        ? ['model', rule.modelPattern.trim().toLowerCase()]
        : [rule.scope, rule.provider?.toLowerCase(), rule.family?.toLowerCase() || ''],
    );
  }
  function scopedRule(family?: string) {
    return rules.value.find(
      (rule) =>
        rule.scope === (family ? 'family' : 'provider') &&
        rule.provider?.toLowerCase() === selectedProvider.value.toLowerCase() &&
        (!family || rule.family?.toLowerCase() === family.toLowerCase()),
    );
  }
  function scopedMultiplier(family?: string) {
    const rule = scopedRule(family);
    if (rule) return Number(rule.priceMultiplier);
    return family ? providerMultiplier.value : billingMultiplier.value;
  }
  function setScopedMultiplier(value: number | undefined, family?: string) {
    if (value === undefined || !Number.isFinite(value) || value < 0.000001 || value > 1000) return;
    const existing = scopedRule(family);
    if (existing) {
      existing.priceMultiplier = value;
      return;
    }
    if (rules.value.length >= 200) {
      ElMessage.warning(t('最多支持 200 条规则'));
      return;
    }
    rules.value.push({
      ...newRule(),
      modelPattern: '*',
      scope: family ? 'family' : 'provider',
      provider: selectedProvider.value,
      family: family || null,
      priceMultiplier: value,
    });
  }
  function inheritScope(family?: string) {
    const rule = scopedRule(family);
    if (rule) removeRule(rule.ruleId);
  }
  function familyCount(family: string) {
    return providerModels.value.filter((model) => model.family === family).length;
  }
  function isLegacy(rule: UpdateModelPricingRuleRequest) {
    return (
      !(Number(rule.priceMultiplier) > 0) &&
      Boolean(rule.totalMultiplier || rule.inputMultiplier || rule.outputMultiplier)
    );
  }
  function number(value: unknown) {
    if (value === null || value === undefined || value === '') return '—';
    const amount = Number(value);
    if (!Number.isFinite(amount)) return '—';
    if (amount > 0 && amount < 0.000001) return amount.toExponential(2);
    return amount.toLocaleString(locale.value, { maximumFractionDigits: 6 });
  }
  function rulePrice(rule: EditableRule) {
    if (!isLegacy(rule)) return number(rule.priceMultiplier) + ' ×';
    if (rule.totalMultiplier) return '$' + number(rule.totalMultiplier * 1_000_000) + ' / MTok';
    return (
      '$' +
      number(Number(rule.inputMultiplier) * 1_000_000) +
      ' / $' +
      number(Number(rule.outputMultiplier) * 1_000_000)
    );
  }
  function toEditable(rule: ModelPricingRule): EditableRule {
    return {
      ruleId: rule.ruleId,
      enabled: rule.enabled !== false,
      modelPattern: rule.modelPattern,
      priceMultiplier: rule.priceMultiplier,
      priority: rule.priority ?? 100,
      inputMultiplier: rule.inputMultiplier,
      outputMultiplier: rule.outputMultiplier,
      cacheReadMultiplier: rule.cacheReadMultiplier,
      cacheWriteMultiplier: rule.cacheWriteMultiplier,
      totalMultiplier: rule.totalMultiplier,
      scope: rule.scope || 'model',
      provider: rule.provider,
      family: rule.family,
    };
  }
  function acceptRules(incoming: ModelPricingRule[]) {
    rules.value = incoming.map(toEditable);
    savedRules.value = JSON.stringify(rules.value);
  }
  function editRule(rule?: EditableRule) {
    editingId.value = rule?.ruleId ?? null;
    draft.value = rule ? { ...rule } : newRule();
    dialogVisible.value = true;
  }
  function commitDraft() {
    const next = { ...draft.value, modelPattern: draft.value.modelPattern.trim() };
    if (!next.modelPattern || next.modelPattern.length > 256) {
      ElMessage.warning(t('请输入 1–256 个字符的模型匹配'));
      return;
    }
    if (
      !isLegacy(next) &&
      (!Number.isFinite(Number(next.priceMultiplier)) ||
        Number(next.priceMultiplier) < 0.000001 ||
        Number(next.priceMultiplier) > 1000)
    ) {
      ElMessage.warning(t('倍率范围为 0.000001–1000'));
      return;
    }
    if (!Number.isInteger(next.priority) || next.priority < 0 || next.priority > 10000) {
      ElMessage.warning(t('优先级范围为 0–10000'));
      return;
    }
    if (
      !isLegacy(next) ||
      next.totalMultiplier ||
      (next.inputMultiplier && next.outputMultiplier)
    ) {
      if (
        rules.value.some(
          (rule) => rule.ruleId !== editingId.value && ruleIdentity(rule) === ruleIdentity(next),
        )
      ) {
        ElMessage.warning(t('该模型匹配已存在'));
        return;
      }
      const index = rules.value.findIndex((rule) => rule.ruleId === editingId.value);
      if (index >= 0) rules.value[index] = next;
      else rules.value.push(next);
      dialogVisible.value = false;
    } else ElMessage.warning(t('请填写有效的价格倍率'));
  }
  async function convertLegacy() {
    try {
      await ElMessageBox.confirm(
        t('固定单价将替换为官方价格 × 倍率，实际费用会改变。'),
        t('改为倍率计费'),
        {
          get confirmButtonText() {
            return t('改为倍率');
          },
          get cancelButtonText() {
            return t('保留固定价');
          },
          type: 'warning',
        },
      );
      draft.value = {
        ruleId: draft.value.ruleId,
        enabled: draft.value.enabled,
        modelPattern: draft.value.modelPattern,
        priority: draft.value.priority,
        priceMultiplier: 1,
      };
    } catch {
      /* Keep the legacy draft unchanged. */
    }
  }
  function removeRule(id: string) {
    rules.value = rules.value.filter((rule) => rule.ruleId !== id);
  }
  function discard() {
    rules.value = JSON.parse(savedRules.value);
  }
  function invalidatePreview() {
    previewSequence += 1;
    preview.value = null;
    previewing.value = false;
  }
  watch([rules, previewModel], invalidatePreview, { deep: true, flush: 'sync' });

  async function load() {
    loading.value = true;
    try {
      const [settings, models, payment] = await Promise.all([
        modelPricingApi.settings(),
        modelsApi.list(),
        adminPaymentApi.settings(),
      ]);
      acceptRules(settings.rules);
      if (settings.source) source.value = settings.source;
      // 号源分级只是附加信息：读不到就不显示号池价格
      tierOverview.value = await sourceTiersApi.overview().catch(() => null);
      catalog.value = models;
      modelIds.value = models.map((model) => model.id);
      if (!providers.value.includes(selectedProvider.value)) {
        selectedProvider.value = providers.value.includes('OpenAI')
          ? 'OpenAI'
          : providers.value[0] || '';
      }
      billingMultiplier.value = payment.apiBillingMultiplier;
      ready.value = true;
    } catch {
      ElMessage.error(t('价格设置加载失败'));
    } finally {
      loading.value = false;
    }
  }
  async function save(payload = rules.value) {
    submitting.value = true;
    try {
      const result = await modelPricingApi.update({ rules: payload });
      acceptRules(result.rules);
      emit('saved');
      ElMessage.success(t('价格规则已保存'));
    } catch {
      // The shared HTTP client reports the error; keep the draft intact.
    } finally {
      submitting.value = false;
    }
  }
  async function restoreDefault() {
    try {
      await ElMessageBox.confirm(
        t('全部覆盖规则（含旧固定价）将被删除，恢复默认计费倍率。'),
        t('恢复默认'),
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
    await save([]);
  }
  async function runPreview() {
    if (!previewModel.value.trim()) return;
    const sequence = ++previewSequence;
    preview.value = null;
    previewing.value = true;
    try {
      const id = previewModel.value.trim();
      const entry = catalog.value.find((model) => model.id.toLowerCase() === id.toLowerCase());
      const vendor = tierOverview.value?.vendors.find(
        (candidate) => candidate.vendor === vendorOfProvider(entry?.provider),
      );
      const tiers = previewTiers(vendor, !!tierOverview.value?.runtime.nativeTiers);
      const result = await modelPricingApi.preview(
        id,
        rules.value,
        tiers.map((tier) => tier.factor),
      );
      if (sequence === previewSequence) {
        previewedTiers.value = tiers;
        preview.value = result;
      }
    } catch {
      // Failed previews must not retain an older price.
    } finally {
      if (sequence === previewSequence) previewing.value = false;
    }
  }
  async function saveSource() {
    savingSource.value = true;
    try {
      source.value = await modelPricingApi.updateSource({
        remoteRefreshEnabled: source.value.remoteRefreshEnabled,
        syncCatalogIds: source.value.syncCatalogIds,
        sourceUrl: source.value.sourceUrl,
        checksumUrl: source.value.checksumUrl,
      });
      invalidatePreview();
      ElMessage.success(t('数据源已保存'));
    } catch {
      // Keep source edits available for correction or retry.
    } finally {
      savingSource.value = false;
    }
  }
  async function syncNow() {
    syncing.value = true;
    try {
      const status = await modelPricingApi.refresh();
      source.value = {
        ...source.value,
        catalogEntries: status.catalogEntries,
        refreshedAt: status.refreshedAt,
        lastError: status.lastError,
      };
      invalidatePreview();
      emit('saved');
      ElMessage.success(t('官方价格已同步'));
    } catch {
      ElMessage.error(t('同步失败，已保留当前规则草稿和上次成功价格'));
    } finally {
      syncing.value = false;
    }
  }
  onMounted(load);
</script>

<template>
  <div v-loading="loading" class="pricing-panel">
    <el-alert v-if="!loading && !ready" :title="t('价格设置未加载')" type="error" :closable="false">
      <el-button link :icon="Refresh" @click="load">{{ t('重新加载') }}</el-button>
    </el-alert>
    <div class="pricing-summary">
      <div>
        <span class="eyebrow">{{ t('默认计费') }}</span>
        <div class="default-price">
          {{ t('官方价格') }} <span>×</span> {{ number(billingMultiplier) }}
        </div>
      </div>
      <div class="summary-meta">
        <span>{{ enabledCount }} {{ t('条生效规则') }}</span>
        <el-tag v-if="legacyCount" type="warning" effect="plain" size="small">
          {{ legacyCount }} {{ t('条旧固定价') }}
        </el-tag>
        <el-tag v-if="dirty" effect="plain" size="small">{{ t('未保存') }}</el-tag>
        <el-button
          type="primary"
          :icon="Check"
          :disabled="busy || !dirty"
          :loading="submitting"
          @click="save()"
        >
          {{ t('保存规则') }}
        </el-button>
      </div>
    </div>

    <section class="scope-section" :aria-label="t('厂商与系列倍率')">
      <div class="section-head">
        <h3>{{ t('厂商与系列') }}</h3>
        <el-select
          v-model="selectedProvider"
          class="provider-select"
          filterable
          :aria-label="t('定价厂商')"
          :disabled="busy"
        >
          <el-option
            v-for="provider in providers"
            :key="provider"
            :label="provider"
            :value="provider"
          />
        </el-select>
      </div>
      <template v-if="selectedProvider">
        <div class="scope-row provider-rate">
          <div class="scope-identity">
            <ProviderLogo :provider="selectedProvider" :size="28" />
            <div>
              <strong>{{ selectedProvider }} {{ t('默认倍率') }}</strong>
              <span>{{ families.length }} {{ t('个系列 ·') }} {{ providerModels.length }}
                {{ t('个模型') }}</span>
            </div>
          </div>
          <div class="scope-controls">
            <el-tag v-if="!providerRule" size="small" type="info" effect="plain">
              {{
                t('继承全局')
              }}
            </el-tag>
            <el-tag v-else-if="!providerRule.enabled" size="small" type="warning" effect="plain">
              {{ t('已停用') }}
            </el-tag>
            <el-input-number
              :model-value="scopedMultiplier()"
              :min="0.000001"
              :max="1000"
              :precision="6"
              :step="0.1"
              controls-position="right"
              :disabled="busy"
              :aria-label="selectedProvider + t(' 厂商倍率')"
              @update:model-value="setScopedMultiplier($event)"
            />
            <span class="rate-unit">×</span>
            <el-tooltip :content="t('优先级与启用状态')">
              <el-button
                text
                :icon="Edit"
                :disabled="busy || !providerRule"
                :aria-label="selectedProvider + t(' 倍率高级设置')"
                @click="editRule(providerRule)"
              />
            </el-tooltip>
            <el-tooltip :content="t('恢复继承全局倍率')">
              <el-button
                text
                :icon="RefreshLeft"
                :disabled="busy || !providerRule"
                :aria-label="selectedProvider + t(' 恢复全局倍率')"
                @click="inheritScope()"
              />
            </el-tooltip>
          </div>
        </div>
        <div v-for="family in families" :key="family" class="scope-row family-rate">
          <div class="scope-identity">
            <div>
              <strong>{{ family }}</strong><span>{{ familyCount(family) }} {{ t('个模型') }}</span>
            </div>
          </div>
          <div class="scope-controls">
            <el-tag v-if="!scopedRule(family)" size="small" type="info" effect="plain">
              {{ t('继承厂商') }}
            </el-tag>
            <el-tag
              v-else-if="!scopedRule(family)?.enabled"
              size="small"
              type="warning"
              effect="plain"
            >
              {{ t('已停用') }}
            </el-tag>
            <el-input-number
              :model-value="scopedMultiplier(family)"
              :min="0.000001"
              :max="1000"
              :precision="6"
              :step="0.1"
              controls-position="right"
              :disabled="busy"
              :aria-label="selectedProvider + ' ' + family + t(' 系列倍率')"
              @update:model-value="setScopedMultiplier($event, family)"
            />
            <span class="rate-unit">×</span>
            <el-tooltip :content="t('优先级与启用状态')">
              <el-button
                text
                :icon="Edit"
                :disabled="busy || !scopedRule(family)"
                :aria-label="family + t(' 倍率高级设置')"
                @click="editRule(scopedRule(family))"
              />
            </el-tooltip>
            <el-tooltip :content="t('恢复继承厂商倍率')">
              <el-button
                text
                :icon="RefreshLeft"
                :disabled="busy || !scopedRule(family)"
                :aria-label="family + t(' 恢复厂商倍率')"
                @click="inheritScope(family)"
              />
            </el-tooltip>
          </div>
        </div>
      </template>
    </section>

    <section class="rules-section" :aria-label="t('价格覆盖规则')">
      <div class="section-head">
        <h3>{{ t('模型单独规则') }}</h3>
        <div class="actions">
          <el-button :icon="Plus" :disabled="busy || rules.length >= 200" @click="editRule()">
            {{ t('新增规则') }}
          </el-button>
        </div>
      </div>
      <div class="filter-row">
        <el-input
          v-model="keyword"
          :prefix-icon="Search"
          :placeholder="t('搜索模型匹配')"
          clearable
          :aria-label="t('搜索规则')"
        />
        <el-tooltip
          :content="
            t(
              '优先级数值越小越优先；同级按单个模型、系列、厂商、通配符排序。最终只使用一条规则的倍率，不会叠乘。',
            )
          "
          placement="top"
        >
          <span class="match-order" tabindex="0">{{ t('同级：模型 → 系列 → 厂商 → 通配符') }}</span>
        </el-tooltip>
      </div>
      <div class="rule-grid rule-header" aria-hidden="true">
        <span>{{ t('模型匹配') }}</span><span>{{ t('价格') }}</span><span>{{ t('优先级') }}</span><span>{{ t('启用') }}</span><span />
      </div>
      <div
        v-for="rule in filteredRules"
        :key="rule.ruleId"
        class="rule-grid rule-row"
        :class="{ 'is-disabled': !rule.enabled }"
      >
        <div class="rule-identity">
          <code>{{ rule.modelPattern }}</code>
          <span>{{
            isLegacy(rule)
              ? rule.totalMultiplier
                ? t('固定总量价')
                : t('固定输入 / 输出价')
              : rule.modelPattern.includes('*')
                ? t('通配符')
                : t('精确匹配')
          }}</span>
        </div>
        <strong class="rule-price">{{ rulePrice(rule) }}</strong>
        <span class="rule-priority"><span class="mobile-label">{{ t('优先级') }} </span>{{ rule.priority }}</span>
        <el-switch
          v-model="rule.enabled"
          :disabled="busy"
          :aria-label="t('启用 ') + rule.modelPattern"
        />
        <div class="row-actions">
          <el-tooltip :content="t('编辑规则')">
            <el-button
              :icon="Edit"
              text
              :disabled="busy"
              :aria-label="t('编辑 ') + rule.modelPattern"
              @click="editRule(rule)"
            />
          </el-tooltip>
          <el-tooltip :content="t('删除规则')">
            <el-button
              :icon="Delete"
              text
              type="danger"
              :disabled="busy"
              :aria-label="t('删除 ') + rule.modelPattern"
              @click="removeRule(rule.ruleId)"
            />
          </el-tooltip>
        </div>
      </div>
      <div v-if="!filteredRules.length" class="empty-state">
        {{ keyword ? t('没有匹配的规则') : t('暂无模型单独规则') }}
      </div>
      <div class="rule-footer">
        <span>{{ rules.length }} / 200</span>
        <div class="actions">
          <el-button v-if="dirty" :icon="RefreshLeft" text :disabled="busy" @click="discard">
            {{ t('撤销更改') }}
          </el-button>
          <el-button
            v-if="rules.length"
            text
            type="danger"
            :disabled="busy"
            @click="restoreDefault"
          >
            {{ t('恢复默认') }}
          </el-button>
        </div>
      </div>
    </section>

    <section class="preview-section" :aria-label="t('价格预览')">
      <div class="section-head">
        <h3>
          {{ t('价格预览') }} <span v-if="dirty">{{ t('当前草稿') }}</span>
        </h3>
        <span class="unit">USD / MTok</span>
      </div>
      <div class="preview-controls">
        <el-select
          v-model="previewModel"
          filterable
          allow-create
          default-first-option
          :aria-label="t('预览模型')"
          :placeholder="t('模型 ID')"
        >
          <el-option v-for="id in modelIds" :key="id" :label="id" :value="id" />
        </el-select>
        <el-button
          :icon="Search"
          :disabled="busy || !previewModel.trim()"
          :loading="previewing"
          @click="runPreview"
        >
          {{ t('预览价格') }}
        </el-button>
      </div>
      <template v-if="preview">
        <div class="preview-match">
          <span>{{ t('生效规则') }}</span><code>{{ matchedRules[0] ? ruleLabel(matchedRules[0]) : t('默认倍率') }}</code>
          <el-tag v-if="preview.multiplier != null" effect="plain" size="small">
            {{ number(preview.multiplier) }} ×
          </el-tag>
          <el-tag v-else type="warning" effect="plain" size="small">{{ t('固定价') }}</el-tag>
        </div>
        <p v-if="matchedRules.length > 1" class="conflict-note">
          {{ t('同时匹配但未生效：') }}{{ matchedRules.slice(1).map(ruleLabel).join('、') }}
        </p>
        <el-alert
          v-if="preview.pricing.billingMode === 'unavailable'"
          :title="t('未找到可核实的官方价格，暂不可计费')"
          type="warning"
          :closable="false"
        />
        <div class="price-table">
          <div class="price-line price-table-head">
            <span>{{ t('计费项') }}</span><span>{{ t('官方价格') }}</span><span>{{ t('当前价格') }}</span>
          </div>
          <div v-for="field in priceFields" :key="field.key" class="price-line">
            <el-tooltip
              v-if="field.key === 'totalPricePerMillionTokens'"
              :content="
                t(
                  '输入、输出各占 50% 的总量参考价；实际按各项用量计费。固定总量价则按总 Token 数计费。',
                )
              "
            >
              <span tabindex="0">{{ field.label }}</span>
            </el-tooltip>
            <span v-else>{{ field.label }}</span>
            <span>{{ number(preview.basePricing[field.key]) }}</span>
            <strong>{{ number(preview.pricing[field.key]) }}</strong>
          </div>
        </div>
        <template v-if="tierPrices.length">
          <div class="section-head tier-price-head">
            <h3>{{ t('号池价格') }}</h3>
            <span class="unit">USD / MTok</span>
          </div>
          <p class="tier-price-note">
            {{ t('规则只命中一条，号池系数在它之后再乘：号池价格 = 上面的当前价格 × 号池系数。') }}
          </p>
          <div class="price-table" role="table" :aria-label="t('号池价格')">
            <div class="price-line price-table-head" :style="tierPriceColumns">
              <span>{{ t('计费项') }}</span>
              <span v-for="entry in tierPrices" :key="entry.tier.tierId">
                L{{ entry.tier.level }} {{ entry.tier.name }} × {{ number(entry.tier.factor) }}
              </span>
            </div>
            <div
              v-for="field in priceFields"
              :key="field.key"
              class="price-line"
              :style="tierPriceColumns"
            >
              <span>{{ field.label }}</span>
              <strong v-for="entry in tierPrices" :key="entry.tier.tierId">
                {{ number(entry.pricing[field.key]) }}
              </strong>
            </div>
          </div>
        </template>
        <div v-if="preview.matchedPriceModel" class="price-source">
          {{ t('官方价格模型') }} <code>{{ preview.matchedPriceModel }}</code>
        </div>
      </template>
    </section>

    <el-collapse class="source-section">
      <el-collapse-item name="source">
        <template #title>
          <div class="source-title">
            <span>{{ t('官方价格数据源') }}</span><el-tag :type="source.lastError ? 'warning' : 'info'" size="small" effect="plain">
              {{ source.lastError ? t('同步异常') : source.catalogEntries + t(' 条价格') }}
            </el-tag>
          </div>
        </template>
        <div class="source-status">
          <span>{{ formatDateTime(source.refreshedAt, t('当前使用打包快照')) }}</span>
          <div class="actions">
            <el-button
              :icon="Refresh"
              :loading="syncing"
              :disabled="busy || savingSource"
              @click="syncNow"
            >
              {{ t('立即同步') }}
            </el-button>
            <el-button
              :icon="Check"
              :loading="savingSource"
              :disabled="busy || syncing"
              @click="saveSource"
            >
              {{ t('保存数据源') }}
            </el-button>
          </div>
        </div>
        <el-alert
          v-if="source.lastError"
          :title="source.lastError"
          type="warning"
          :closable="false"
        />
        <el-form
          label-position="top"
          :disabled="busy || savingSource || syncing"
          class="source-grid"
        >
          <label class="switch-field"><span>{{ t('远程自动同步') }}</span><el-switch v-model="source.remoteRefreshEnabled" /></label>
          <label class="switch-field"><span>{{ t('同步新模型 ID') }}</span><el-switch v-model="source.syncCatalogIds" /></label>
          <el-form-item :label="t('价格表地址')">
            <el-input v-model="source.sourceUrl" />
          </el-form-item>
          <el-form-item :label="t('校验和地址')">
            <el-input v-model="source.checksumUrl" />
          </el-form-item>
        </el-form>
      </el-collapse-item>
    </el-collapse>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? t('编辑价格规则') : t('新增价格规则')"
      width="min(520px, 94vw)"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form label-position="top" class="rule-form" @submit.prevent="commitDraft">
        <el-form-item
          v-if="draft.scope === 'provider' || draft.scope === 'family'"
          :label="t('计费范围')"
        >
          <strong>{{ ruleLabel(draft) }}</strong>
        </el-form-item>
        <el-form-item v-else :label="t('模型 ID / 通配符')">
          <el-select
            v-model="draft.modelPattern"
            filterable
            allow-create
            default-first-option
            :placeholder="t('模型 ID 或 gpt-*')"
          >
            <el-option v-for="id in modelOptions" :key="id" :label="id" :value="id" />
          </el-select>
        </el-form-item>
        <el-alert
          v-if="exactPatternMissing"
          type="warning"
          :closable="false"
          class="pattern-warning"
          :title="t('未找到精确模型 ID：') + draft.modelPattern"
          :description="
            t('系列倍率可在厂商与系列中设置；按 ID 前缀匹配需使用 gpt-*、gpt-5* 等通配符。')
          "
        />
        <div v-if="isLegacy(draft)" class="legacy-value">
          <div>
            <span>{{ t('旧固定价') }}</span><strong>{{ rulePrice(draft) }}</strong>
          </div>
          <el-button text type="primary" @click="convertLegacy">{{ t('改为倍率') }}</el-button>
        </div>
        <el-form-item v-else :label="t('官方价格倍率')">
          <el-input-number
            :model-value="draft.priceMultiplier ?? undefined"
            :min="0.000001"
            :max="1000"
            :step="0.1"
            :precision="6"
            controls-position="right"
            @update:model-value="draft.priceMultiplier = $event"
          />
        </el-form-item>
        <div class="form-options">
          <el-form-item :label="t('优先级（数值越小越优先）')">
            <el-input-number
              v-model="draft.priority"
              :min="0"
              :max="10000"
              :precision="0"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item :label="t('启用')"><el-switch v-model="draft.enabled" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('取消') }}</el-button><el-button type="primary" @click="commitDraft">{{ t('确定') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
  .scope-section {
    padding: 24px 0;
    border-bottom: 1px solid var(--kp-divider);
  }
  .provider-select {
    width: 200px;
    max-width: 100%;
  }
  .scope-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 12px 20px;
    padding: 14px 0;
    border-bottom: 1px solid var(--kp-divider);
  }
  .scope-row:last-child {
    border-bottom: 0;
    padding-bottom: 0;
  }
  .scope-identity {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
  }
  .scope-identity > div {
    display: grid;
    gap: 5px;
  }
  .scope-identity strong {
    font-size: 13px;
    font-weight: 500;
    overflow-wrap: anywhere;
  }
  .scope-identity span {
    color: var(--kp-text-muted);
    font-size: 12px;
  }
  .provider-rate strong {
    font-weight: 600;
  }
  .scope-controls {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: flex-end;
    gap: 8px;
  }
  .scope-controls :deep(.el-input-number) {
    width: 144px;
  }
  .scope-controls :deep(.el-button) {
    width: 28px;
    height: 28px;
    padding: 0;
    margin: 0;
  }
  .rate-unit {
    font-size: 13px;
    color: var(--kp-text-muted);
  }
  .pattern-warning {
    margin-bottom: 18px;
  }
  .pricing-panel {
    min-height: 280px;
    container: pricing / inline-size;
  }
  .pricing-summary,
  .section-head,
  .filter-row,
  .rule-footer,
  .source-status {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
  }
  .pricing-summary {
    padding: 4px 0 24px;
    border-bottom: 1px solid var(--kp-divider);
  }
  .eyebrow,
  .unit,
  .summary-meta,
  .rule-footer,
  .price-source {
    font-size: 12px;
    color: var(--kp-text-muted);
  }
  .default-price {
    margin-top: 6px;
    font-size: 22px;
    font-weight: 600;
    color: var(--kp-text);
    font-variant-numeric: tabular-nums;
  }
  .default-price > span {
    margin: 0 8px;
    color: var(--kp-text-muted);
    font-weight: 400;
  }
  .summary-meta,
  .actions,
  .source-title {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
  }
  .actions :deep(.el-button + .el-button),
  .row-actions :deep(.el-button + .el-button) {
    margin-left: 0;
  }
  .section-head {
    margin-bottom: 16px;
  }
  h3 {
    margin: 0;
    font-size: 15px;
    font-weight: 600;
    color: var(--kp-text);
  }
  h3 > span {
    margin-left: 8px;
    font-size: 12px;
    font-weight: 400;
    color: var(--kp-text-muted);
  }
  .rules-section,
  .preview-section {
    padding: 24px 0;
    border-bottom: 1px solid var(--kp-divider);
  }
  .filter-row {
    margin-bottom: 12px;
  }
  .filter-row > .el-input {
    max-width: 290px;
  }
  .match-order {
    font-size: 12px;
    color: var(--kp-text-muted);
    text-align: right;
  }
  .rule-grid {
    display: grid;
    grid-template-columns: minmax(160px, 1fr) minmax(150px, 0.65fr) 64px 44px 72px;
    gap: 16px;
    align-items: center;
  }
  .rule-header {
    padding: 10px 12px;
    background: var(--el-fill-color-light);
    font-size: 12px;
    color: var(--kp-text-muted);
  }
  .rule-row {
    padding: 14px 12px;
    border-bottom: 1px solid var(--kp-divider);
    transition: background 0.18s ease;
  }
  .rule-row:hover {
    background: var(--el-fill-color-lighter);
  }
  .rule-identity {
    display: grid;
    gap: 5px;
    min-width: 0;
  }
  code {
    font-family: var(--el-font-family-monospace, monospace);
    overflow-wrap: anywhere;
    font-size: 12px;
  }
  .rule-identity > span {
    font-size: 11px;
    color: var(--kp-text-muted);
  }
  .rule-price {
    font-size: 13px;
    font-weight: 600;
    overflow-wrap: anywhere;
    font-variant-numeric: tabular-nums;
  }
  .rule-priority {
    font-size: 13px;
    color: var(--kp-text-muted);
  }
  .is-disabled .rule-identity,
  .is-disabled .rule-price {
    opacity: 0.5;
  }
  .row-actions {
    display: flex;
    gap: 0;
  }
  .row-actions :deep(.el-button) {
    width: 36px;
    height: 32px;
    padding: 0;
  }
  .mobile-label {
    display: none;
  }
  .empty-state {
    padding: 36px 12px;
    text-align: center;
    color: var(--kp-text-muted);
    font-size: 13px;
  }
  .rule-footer {
    min-height: 40px;
    padding-top: 8px;
  }
  .preview-controls {
    display: flex;
    gap: 10px;
    max-width: 540px;
  }
  .preview-controls > .el-select {
    min-width: 0;
    flex: 1;
  }
  .preview-match {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    margin-top: 20px;
    font-size: 12px;
  }
  .preview-match > span:first-child,
  .price-table-head {
    color: var(--kp-text-muted);
  }
  .conflict-note {
    font-size: 12px;
    color: var(--el-color-warning-dark-2);
    overflow-wrap: anywhere;
  }
  .price-table {
    margin-top: 16px;
  }
  .price-line {
    display: grid;
    grid-template-columns: minmax(90px, 1fr) repeat(2, minmax(80px, 1fr));
    gap: 12px;
    padding: 9px 0;
    border-bottom: 1px solid var(--kp-divider);
    font-size: 13px;
    font-variant-numeric: tabular-nums;
  }
  .price-line > :not(:first-child) {
    text-align: right;
    overflow-wrap: anywhere;
  }
  .price-line > strong {
    font-weight: 500;
  }
  .price-table-head {
    font-size: 12px;
  }
  .tier-price-head {
    margin-top: 22px;
  }
  .tier-price-note {
    margin: 4px 0 0;
    font-size: 12px;
    line-height: 1.55;
    color: var(--kp-text-secondary);
  }
  .price-source {
    padding-top: 12px;
    overflow-wrap: anywhere;
  }
  .price-source > code {
    margin-left: 8px;
  }
  .source-section {
    border-top: 0;
  }
  .source-title {
    font-size: 13px;
  }
  .source-status {
    margin: 8px 0 20px;
    font-size: 12px;
    color: var(--kp-text-muted);
  }
  .source-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px 20px;
  }
  .switch-field {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    font-size: 13px;
  }
  .rule-form .el-select {
    width: 100%;
  }
  .form-options {
    display: flex;
    gap: 32px;
  }
  .legacy-value {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 20px;
    padding: 12px 0;
    border-block: 1px solid var(--kp-divider);
  }
  .legacy-value > div {
    display: grid;
    gap: 8px;
    font-size: 13px;
  }
  .legacy-value strong {
    overflow-wrap: anywhere;
  }
  @container pricing (max-width: 660px) {
    .scope-row {
      flex-direction: column;
      align-items: stretch;
    }
    .scope-controls {
      display: grid;
      grid-template-columns: minmax(100px, 144px) 12px 28px 28px;
      justify-content: start;
    }
    .scope-controls :deep(.el-tag) {
      grid-column: 1 / -1;
      justify-self: start;
    }
    .scope-controls :deep(.el-input-number) {
      grid-column: 1;
      width: 100%;
    }
    .pricing-summary,
    .section-head,
    .source-status {
      align-items: flex-start;
      flex-direction: column;
    }
    .summary-meta {
      gap: 8px;
    }
    .filter-row {
      flex-direction: column;
      align-items: stretch;
    }
    .filter-row > .el-input {
      max-width: none;
    }
    .match-order {
      text-align: left;
    }
    .rule-header {
      display: none;
    }
    .rule-grid {
      grid-template-columns: minmax(0, 1fr) 44px 72px;
      gap: 10px;
      padding-inline: 0;
    }
    .rule-identity {
      grid-column: 1 / -1;
    }
    .rule-price {
      grid-column: 1;
    }
    .rule-priority {
      grid-column: 1;
      grid-row: 3;
    }
    .rule-row > .el-switch {
      grid-column: 2;
      grid-row: 2;
    }
    .row-actions {
      grid-column: 3;
      grid-row: 2;
    }
    .mobile-label {
      display: inline;
    }
    .source-grid {
      grid-template-columns: minmax(0, 1fr);
    }
    .default-price {
      font-size: 20px;
    }
    .form-options {
      gap: 20px;
    }
  }
</style>
