<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { Refresh } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { adminPaymentApi, modelPricingApi, systemApi } from '@/api';
  import type { ModelInfo, ModelPricingRule, UpdateModelPricingRuleRequest } from '@/types/api';
  import { formatDateTime } from '@/utils/format';

  interface EditableRule {
    tempId: string;
    ruleId?: string;
    enabled: boolean;
    modelPattern: string;
    inputPrice: number | null;
    outputPrice: number | null;
    cacheWritePrice: number | null;
    cacheReadPrice: number | null;
    totalRatio: number | null;
    priority: number;
    createdAt?: string | null;
    updatedAt?: string | null;
  }

  interface Sub2ApiVendorPreset {
    pattern: string;
    description: string;
    inputPrice: number;
    outputPrice: number;
    cacheWritePrice: number | null;
    cacheReadPrice: number | null;
    priority: number;
  }

  const TOKENS_PER_MILLION = 1_000_000;
  const DEFAULT_BASE_MULTIPLIER = 0.0000166666666667;

  const SUB2API_VENDOR_PRESETS: Sub2ApiVendorPreset[] = [
    {
      pattern: '*gpt-4o-mini*',
      description: 'OpenAI cheap small models',
      inputPrice: 0.15,
      outputPrice: 0.6,
      cacheWritePrice: 0.15,
      cacheReadPrice: 0.015,
      priority: 20,
    },
    {
      pattern: '*gpt-5*nano*',
      description: 'OpenAI nano models',
      inputPrice: 0.2,
      outputPrice: 1.25,
      cacheWritePrice: 0.2,
      cacheReadPrice: 0.02,
      priority: 20,
    },
    {
      pattern: '*gpt-5*mini*',
      description: 'OpenAI mini models',
      inputPrice: 0.75,
      outputPrice: 4.5,
      cacheWritePrice: 0.75,
      cacheReadPrice: 0.075,
      priority: 25,
    },
    {
      pattern: '*gpt-5.2*',
      description: 'OpenAI GPT-5.2 class',
      inputPrice: 1.75,
      outputPrice: 14,
      cacheWritePrice: 1.75,
      cacheReadPrice: 0.175,
      priority: 30,
    },
    {
      pattern: '*gpt-5.4*',
      description: 'OpenAI GPT-5.4 class',
      inputPrice: 2.5,
      outputPrice: 15,
      cacheWritePrice: 2.5,
      cacheReadPrice: 0.25,
      priority: 30,
    },
    {
      pattern: '*gpt-5.5*',
      description: 'OpenAI GPT-5.5 class',
      inputPrice: 2.5,
      outputPrice: 15,
      cacheWritePrice: 2.5,
      cacheReadPrice: 0.25,
      priority: 30,
    },
    {
      pattern: '*codex*',
      description: 'OpenAI Codex class',
      inputPrice: 1.5,
      outputPrice: 12,
      cacheWritePrice: 1.5,
      cacheReadPrice: 0.15,
      priority: 35,
    },
    {
      pattern: '*o1*',
      description: 'OpenAI reasoning o1 class',
      inputPrice: 1.1,
      outputPrice: 4.4,
      cacheWritePrice: 1.1,
      cacheReadPrice: 0.11,
      priority: 35,
    },
    {
      pattern: '*o3*',
      description: 'OpenAI reasoning o3 class',
      inputPrice: 1.1,
      outputPrice: 4.4,
      cacheWritePrice: 1.1,
      cacheReadPrice: 0.11,
      priority: 35,
    },
    {
      pattern: '*openai*',
      description: 'OpenAI fallback',
      inputPrice: 1,
      outputPrice: 4,
      cacheWritePrice: 1,
      cacheReadPrice: 0.1,
      priority: 80,
    },
    {
      pattern: 'gpt-*',
      description: 'OpenAI model id fallback',
      inputPrice: 1,
      outputPrice: 4,
      cacheWritePrice: 1,
      cacheReadPrice: 0.1,
      priority: 85,
    },
    {
      pattern: '*claude*haiku*',
      description: 'Anthropic Haiku class',
      inputPrice: 1,
      outputPrice: 5,
      cacheWritePrice: 1.25,
      cacheReadPrice: 0.1,
      priority: 20,
    },
    {
      pattern: '*claude*sonnet*',
      description: 'Anthropic Sonnet class',
      inputPrice: 3,
      outputPrice: 15,
      cacheWritePrice: 3.75,
      cacheReadPrice: 0.3,
      priority: 25,
    },
    {
      pattern: '*claude*opus*',
      description: 'Anthropic Opus class',
      inputPrice: 5,
      outputPrice: 25,
      cacheWritePrice: 6.25,
      cacheReadPrice: 0.5,
      priority: 25,
    },
    {
      pattern: '*anthropic*',
      description: 'Anthropic fallback',
      inputPrice: 3,
      outputPrice: 15,
      cacheWritePrice: 3.75,
      cacheReadPrice: 0.3,
      priority: 80,
    },
    {
      pattern: '*claude*',
      description: 'Claude model id fallback',
      inputPrice: 3,
      outputPrice: 15,
      cacheWritePrice: 3.75,
      cacheReadPrice: 0.3,
      priority: 85,
    },
    {
      pattern: '*gemma*',
      description: 'Google Gemma/free class',
      inputPrice: 0.1,
      outputPrice: 0.4,
      cacheWritePrice: 0.1,
      cacheReadPrice: 0.01,
      priority: 20,
    },
    {
      pattern: '*gemini*flash*',
      description: 'Google Gemini Flash class',
      inputPrice: 0.3,
      outputPrice: 2.5,
      cacheWritePrice: 0.3,
      cacheReadPrice: 0.03,
      priority: 25,
    },
    {
      pattern: '*gemini*pro*',
      description: 'Google Gemini Pro class',
      inputPrice: 2,
      outputPrice: 12,
      cacheWritePrice: 2,
      cacheReadPrice: 0.2,
      priority: 30,
    },
    {
      pattern: '*gemini*',
      description: 'Google Gemini fallback',
      inputPrice: 0.5,
      outputPrice: 2,
      cacheWritePrice: 0.5,
      cacheReadPrice: 0.05,
      priority: 80,
    },
    {
      pattern: '*google*',
      description: 'Google provider fallback',
      inputPrice: 0.5,
      outputPrice: 2,
      cacheWritePrice: 0.5,
      cacheReadPrice: 0.05,
      priority: 85,
    },
    {
      pattern: 'deepseek-v4-flash',
      description: 'DeepSeek V4 Flash official pricing',
      inputPrice: 0.14,
      outputPrice: 0.28,
      cacheWritePrice: 0.14,
      cacheReadPrice: 0.0028,
      priority: 20,
    },
    {
      pattern: 'deepseek-v4-pro',
      description: 'DeepSeek V4 Pro official pricing',
      inputPrice: 0.435,
      outputPrice: 0.87,
      cacheWritePrice: 0.435,
      cacheReadPrice: 0.003625,
      priority: 20,
    },
    {
      pattern: '*',
      description: 'Global fallback',
      inputPrice: 16.6666666667,
      outputPrice: 16.6666666667,
      cacheWritePrice: 16.6666666667,
      cacheReadPrice: 1.6666666667,
      priority: 1000,
    },
  ];

  const loading = ref(false);
  const submitting = ref(false);
  const rules = ref<EditableRule[]>([]);
  const models = ref<ModelInfo[]>([]);
  const baseMultiplier = ref(DEFAULT_BASE_MULTIPLIER);

  const selectedVendor = ref('all');
  const searchQuery = ref('');

  interface VendorInfo {
    id: string;
    name: string;
    color: string;
    bg: string;
    border: string;
  }

  function getVendorInfo(pattern: string): VendorInfo {
    const p = (pattern || '').toLowerCase();
    if (p === '*') {
      return {
        id: 'global',
        name: '全局 / 默认',
        color: '#64748b',
        bg: 'rgba(100, 116, 139, 0.08)',
        border: '1px solid rgba(100, 116, 139, 0.15)',
      };
    }
    if (
      p.includes('gpt') ||
      p.includes('o1') ||
      p.includes('o3') ||
      p.includes('openai') ||
      p.includes('codex')
    ) {
      return {
        id: 'openai',
        name: 'OpenAI',
        color: '#10a37f',
        bg: 'rgba(16, 163, 127, 0.08)',
        border: '1px solid rgba(16, 163, 127, 0.15)',
      };
    }
    if (p.includes('claude') || p.includes('anthropic')) {
      return {
        id: 'anthropic',
        name: 'Anthropic',
        color: '#d97757',
        bg: 'rgba(217, 119, 87, 0.08)',
        border: '1px solid rgba(217, 119, 87, 0.15)',
      };
    }
    if (p.includes('gemini') || p.includes('gemma') || p.includes('google')) {
      return {
        id: 'google',
        name: 'Google',
        color: '#4285f4',
        bg: 'rgba(66, 133, 244, 0.08)',
        border: '1px solid rgba(66, 133, 244, 0.15)',
      };
    }
    if (p.includes('deepseek')) {
      return {
        id: 'deepseek',
        name: 'DeepSeek',
        color: '#0d6efd',
        bg: 'rgba(13, 110, 253, 0.08)',
        border: '1px solid rgba(13, 110, 253, 0.15)',
      };
    }
    if (p.includes('llama') || p.includes('meta')) {
      return {
        id: 'meta',
        name: 'Meta',
        color: '#044eab',
        bg: 'rgba(4, 78, 171, 0.08)',
        border: '1px solid rgba(4, 78, 171, 0.15)',
      };
    }
    if (p.includes('mj') || p.includes('midjourney')) {
      return {
        id: 'midjourney',
        name: 'Midjourney',
        color: '#b387ff',
        bg: 'rgba(179, 135, 255, 0.08)',
        border: '1px solid rgba(179, 135, 255, 0.15)',
      };
    }
    return {
      id: 'other',
      name: '其他厂商',
      color: '#0f172a',
      bg: 'rgba(15, 23, 66, 0.05)',
      border: '1px solid rgba(15, 23, 66, 0.1)',
    };
  }

  const vendorCounts = computed(() => {
    const counts = {
      all: rules.value.length,
      openai: 0,
      anthropic: 0,
      google: 0,
      deepseek: 0,
      meta: 0,
      midjourney: 0,
      other: 0,
    };
    rules.value.forEach((rule) => {
      const vendor = getVendorInfo(rule.modelPattern).id;
      if (vendor === 'global' || vendor === 'other') {
        counts.other++;
      } else if (vendor in counts) {
        counts[vendor as keyof typeof counts]++;
      }
    });
    return counts;
  });

  const vendorFilterOptions = computed(() => [
    { label: `全部 (${vendorCounts.value.all})`, value: 'all' },
    { label: `OpenAI (${vendorCounts.value.openai})`, value: 'openai' },
    { label: `Anthropic (${vendorCounts.value.anthropic})`, value: 'anthropic' },
    { label: `Google (${vendorCounts.value.google})`, value: 'google' },
    { label: `DeepSeek (${vendorCounts.value.deepseek})`, value: 'deepseek' },
    { label: `Meta (${vendorCounts.value.meta})`, value: 'meta' },
    { label: `Midjourney (${vendorCounts.value.midjourney})`, value: 'midjourney' },
    { label: `其他 / 全局 (${vendorCounts.value.other})`, value: 'other' },
  ]);

  const filteredRules = computed(() => {
    let list = rules.value;
    if (selectedVendor.value !== 'all') {
      list = list.filter((rule) => {
        const vendor = getVendorInfo(rule.modelPattern).id;
        if (selectedVendor.value === 'other') {
          return vendor === 'other' || vendor === 'global';
        }
        return vendor === selectedVendor.value;
      });
    }
    const q = searchQuery.value.trim().toLowerCase();
    if (q) {
      list = list.filter((rule) => rule.modelPattern.toLowerCase().includes(q));
    }
    return list;
  });

  const modelOptions = computed(() => {
    const presets = SUB2API_VENDOR_PRESETS.map((preset) => preset.pattern);
    return Array.from(new Set([...presets, ...models.value.map((model) => model.id)])).sort(
      (a, b) => a.localeCompare(b),
    );
  });

  const stats = computed(() => {
    const active = rules.value.filter((rule) => rule.enabled);
    return {
      total: rules.value.length,
      active: active.length,
      totalMultiplier: active.filter((rule) => isPositive(rule.totalRatio)).length,
      splitPrice: active.filter((rule) => !isPositive(rule.totalRatio)).length,
    };
  });

  const basePricePerMillion = computed(() => {
    return baseMultiplier.value > 0 ? baseMultiplier.value * TOKENS_PER_MILLION : 0;
  });

  const pricingHint = computed(() => {
    return `总倍率模式直接填写等效倍率 x；分项模式按 sub2api 风格填写每 1M token 消耗的余额。当前 1x 基准价为 ${formatPrice(basePricePerMillion.value)} 余额/MTok，对应每 token ${formatBaseMultiplier(baseMultiplier.value)} 余额。`;
  });

  function toEditable(rule: ModelPricingRule): EditableRule {
    return {
      tempId: crypto.randomUUID(),
      ruleId: rule.ruleId,
      enabled: rule.enabled !== false,
      modelPattern: rule.modelPattern,
      inputPrice: pricePerMillionFromStored(
        multiplierFromStored(rule.inputMultiplier, rule.inputPricePerMillionTokens),
      ),
      outputPrice: pricePerMillionFromStored(
        multiplierFromStored(rule.outputMultiplier, rule.outputPricePerMillionTokens),
      ),
      cacheWritePrice: pricePerMillionFromStored(
        multiplierFromStored(rule.cacheWriteMultiplier, rule.cacheWritePricePerMillionTokens),
      ),
      cacheReadPrice: pricePerMillionFromStored(
        multiplierFromStored(rule.cacheReadMultiplier, rule.cacheReadPricePerMillionTokens),
      ),
      totalRatio: ratioFromStoredMultiplier(
        multiplierFromStored(rule.totalMultiplier, rule.totalPricePerMillionTokens),
      ),
      priority: rule.priority ?? 100,
      createdAt: rule.createdAt,
      updatedAt: rule.updatedAt,
    };
  }

  function addRule(pattern = '') {
    let prefillPattern = pattern;
    if (!prefillPattern) {
      if (selectedVendor.value === 'openai') prefillPattern = 'gpt-*';
      else if (selectedVendor.value === 'anthropic') prefillPattern = '*claude*';
      else if (selectedVendor.value === 'google') prefillPattern = '*gemini*';
      else if (selectedVendor.value === 'deepseek') prefillPattern = '*deepseek*';
      else if (selectedVendor.value === 'meta') prefillPattern = '*llama*';
      else if (selectedVendor.value === 'midjourney') prefillPattern = '*mj*';
    }
    rules.value.push({
      tempId: crypto.randomUUID(),
      enabled: true,
      modelPattern: prefillPattern,
      inputPrice: null,
      outputPrice: null,
      cacheWritePrice: null,
      cacheReadPrice: null,
      totalRatio: 1,
      priority: 100,
    });
  }

  function removeRule(row: EditableRule) {
    const idx = rules.value.findIndex((r) => r.tempId === row.tempId);
    if (idx !== -1) {
      rules.value.splice(idx, 1);
    }
  }

  async function clearRules() {
    await ElMessageBox.confirm('确认清空所有模型价格规则？未保存的改动会一起清空。', '提示', {
      confirmButtonText: '清空',
      cancelButtonText: '取消',
      type: 'warning',
    }).catch(() => Promise.reject(new Error('cancel')));
    rules.value = [];
  }

  async function load() {
    loading.value = true;
    try {
      const [settings, nextModels] = await Promise.all([
        modelPricingApi.settings(),
        systemApi.models().catch(() => []),
        adminPaymentApi
          .settings()
          .then((settings) => {
            const value = Number(settings.apiBillingMultiplier);
            if (Number.isFinite(value) && value > 0) {
              baseMultiplier.value = value;
            }
          })
          .catch(() => undefined),
      ]);
      rules.value = settings.rules.map(toEditable);
      models.value = nextModels;
    } finally {
      loading.value = false;
    }
  }

  async function submit() {
    const payload = validateAndBuildPayload();
    if (!payload) return;
    submitting.value = true;
    try {
      const saved = await modelPricingApi.update({ rules: payload });
      rules.value = saved.rules.map(toEditable);
      ElMessage.success('模型价格已保存');
    } finally {
      submitting.value = false;
    }
  }

  function validateAndBuildPayload(): UpdateModelPricingRuleRequest[] | null {
    const seen = new Set<string>();
    const payload: UpdateModelPricingRuleRequest[] = [];
    for (const [index, rule] of rules.value.entries()) {
      const pattern = rule.modelPattern.trim();
      if (!pattern) {
        ElMessage.warning(`第 ${index + 1} 条规则缺少模型匹配`);
        return null;
      }
      const lowerPattern = pattern.toLowerCase();
      if (seen.has(lowerPattern)) {
        ElMessage.warning(`模型匹配重复：${pattern}`);
        return null;
      }
      seen.add(lowerPattern);

      const totalRatio = positiveOrNull(rule.totalRatio);
      const inputPrice = positiveOrNull(rule.inputPrice);
      const outputPrice = positiveOrNull(rule.outputPrice);
      const cacheWritePrice = positiveOrNull(rule.cacheWritePrice);
      const cacheReadPrice = positiveOrNull(rule.cacheReadPrice);
      if (!totalRatio && (!inputPrice || !outputPrice)) {
        ElMessage.warning(`第 ${index + 1} 条规则需要填写总倍率，或同时填写输入/输出价格`);
        return null;
      }

      payload.push({
        ruleId: rule.ruleId,
        enabled: rule.enabled,
        modelPattern: pattern,
        totalMultiplier: totalRatio ? multiplierFromRatio(totalRatio) : null,
        inputPricePerMillionTokens: totalRatio ? null : inputPrice,
        outputPricePerMillionTokens: totalRatio ? null : outputPrice,
        cacheWritePricePerMillionTokens: totalRatio ? null : cacheWritePrice,
        cacheReadPricePerMillionTokens: totalRatio ? null : cacheReadPrice,
        priority: rule.priority ?? 100,
      });
    }
    return payload;
  }

  function rowKey(row: EditableRule) {
    return row.ruleId || row.tempId;
  }

  function numberOrNull(value: number | null | undefined) {
    return value === null || value === undefined ? null : Number(value);
  }

  function multiplierFromStored(
    multiplier: number | null | undefined,
    pricePerMillion: number | null | undefined,
  ) {
    const normalizedMultiplier = numberOrNull(multiplier);
    if (normalizedMultiplier !== null) return normalizedMultiplier;
    const normalizedPrice = numberOrNull(pricePerMillion);
    return normalizedPrice === null ? null : normalizedPrice / TOKENS_PER_MILLION;
  }

  function pricePerMillionFromStored(multiplier: number | null) {
    if (multiplier === null || !Number.isFinite(multiplier) || multiplier <= 0) {
      return null;
    }
    return Number((multiplier * TOKENS_PER_MILLION).toPrecision(12));
  }

  function ratioFromStoredMultiplier(multiplier: number | null) {
    if (
      multiplier === null ||
      !Number.isFinite(multiplier) ||
      multiplier <= 0 ||
      baseMultiplier.value <= 0
    ) {
      return null;
    }
    return Number((multiplier / baseMultiplier.value).toFixed(6));
  }

  function multiplierFromRatio(ratio: number | null) {
    if (ratio === null || !Number.isFinite(ratio) || ratio <= 0) {
      return null;
    }
    return Number((ratio * baseMultiplier.value).toFixed(16));
  }

  function positiveOrNull(value: number | null | undefined) {
    const normalized = numberOrNull(value);
    return normalized !== null && Number.isFinite(normalized) && normalized > 0 ? normalized : null;
  }

  function isPositive(value: number | null | undefined) {
    return positiveOrNull(value) !== null;
  }

  function priceMode(rule: EditableRule) {
    return isPositive(rule.totalRatio) ? '总倍率' : '分项';
  }

  function priceFromPreset(
    preset: Sub2ApiVendorPreset,
    field: 'inputPrice' | 'outputPrice' | 'cacheWritePrice' | 'cacheReadPrice',
  ) {
    const price = preset[field];
    if (preset.pattern !== '*') return price;
    return field === 'cacheReadPrice' ? basePricePerMillion.value * 0.1 : basePricePerMillion.value;
  }

  function presetToEditableRule(preset: Sub2ApiVendorPreset): EditableRule {
    return {
      tempId: crypto.randomUUID(),
      enabled: true,
      modelPattern: preset.pattern,
      inputPrice: priceFromPreset(preset, 'inputPrice'),
      outputPrice: priceFromPreset(preset, 'outputPrice'),
      cacheWritePrice: priceFromPreset(preset, 'cacheWritePrice'),
      cacheReadPrice: priceFromPreset(preset, 'cacheReadPrice'),
      totalRatio: null,
      priority: preset.priority,
    };
  }

  async function applySub2ApiVendorPresets() {
    try {
      await ElMessageBox.confirm(
        `将替换当前全部模型价格规则，并立即保存 ${SUB2API_VENDOR_PRESETS.length} 条通配符规则。继续？`,
        '应用 sub2api 厂商价格表',
        {
          confirmButtonText: '应用并保存',
          cancelButtonText: '取消',
          type: 'warning',
        },
      );
    } catch {
      return;
    }

    rules.value = SUB2API_VENDOR_PRESETS.map(presetToEditableRule).sort(
      (a, b) =>
        (a.priority ?? 100) - (b.priority ?? 100) || a.modelPattern.localeCompare(b.modelPattern),
    );
    await submit();
  }

  function formatBaseMultiplier(value: number) {
    return value.toFixed(16).replace(/0+$/, '').replace(/\.$/, '');
  }

  function multiplierFromPrice(price: number | null | undefined) {
    const normalized = positiveOrNull(price);
    if (normalized === null || basePricePerMillion.value <= 0) return null;
    return Number((normalized / basePricePerMillion.value).toFixed(6));
  }

  function formatPrice(value: number | null | undefined) {
    const normalized = positiveOrNull(value);
    if (normalized === null) return '-';
    return normalized.toLocaleString(undefined, { maximumFractionDigits: 10 });
  }

  function priceFromRatio(value: number | null | undefined) {
    const normalized = positiveOrNull(value);
    if (normalized === null || basePricePerMillion.value <= 0) return null;
    return normalized * basePricePerMillion.value;
  }

  function formatRatio(value: number | null | undefined) {
    const normalized = positiveOrNull(value);
    return normalized === null
      ? '-'
      : `${normalized.toLocaleString(undefined, { maximumFractionDigits: 6 })}x`;
  }

  function formatPriceMultiplier(value: number | null | undefined) {
    const multiplier = multiplierFromPrice(value);
    return multiplier === null
      ? '-'
      : `${multiplier.toLocaleString(undefined, { maximumFractionDigits: 6 })}x`;
  }

  onMounted(load);
</script>

<template>
  <PageSection title="模型价格" subtitle="配置等效倍率或按 sub2api 风格配置每 1M token 分项价格">
    <template #actions>
      <el-input
        v-model="searchQuery"
        placeholder="搜索模型匹配模式..."
        clearable
        style="width: 240px; margin-right: 8px"
      >
        <template #prefix>
          <svg
            xmlns="http://www.w3.org/2000/svg"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2.5"
            stroke-linecap="round"
            stroke-linejoin="round"
            style="width: 14px; height: 14px; color: var(--kp-text-muted); margin-right: 4px"
          >
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
        </template>
      </el-input>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button @click="addRule()">新增规则</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存价格</el-button>
    </template>

    <div v-loading="loading" class="model-pricing">
      <!-- Stats Summary Panel -->
      <div class="metrics-panel glass-panel">
        <div class="metric-item">
          <div class="metric-item__header">
            <span class="metric-dot dot-total" />
            <span class="metric-label">规则总数</span>
          </div>
          <div class="metric-value">{{ stats.total }}</div>
        </div>

        <div class="metric-divider" />

        <div class="metric-item">
          <div class="metric-item__header">
            <span class="metric-dot dot-active" />
            <span class="metric-label">启用规则</span>
          </div>
          <div class="metric-value text-success">{{ stats.active }}</div>
        </div>

        <div class="metric-divider" />

        <div class="metric-item">
          <div class="metric-item__header">
            <span class="metric-dot dot-suspended" />
            <span class="metric-label">总倍率模式</span>
          </div>
          <div class="metric-value">{{ stats.totalMultiplier }}</div>
        </div>

        <div class="metric-divider" />

        <div class="metric-item">
          <div class="metric-item__header">
            <span class="metric-dot dot-requests" />
            <span class="metric-label">分项计价模式</span>
          </div>
          <div class="metric-value">{{ stats.splitPrice }}</div>
        </div>
      </div>

      <!-- Combined Pricing Toolbar -->
      <div class="pricing-toolbar">
        <el-segmented
          v-model="selectedVendor"
          :options="vendorFilterOptions"
          class="vendor-segmented-tabs"
        />
        <div class="toolbar-right">
          <el-button
            size="small"
            type="primary"
            plain
            :loading="submitting"
            @click="applySub2ApiVendorPresets"
          >
            恢复默认
          </el-button>
          <el-button link type="danger" :disabled="rules.length === 0" @click="clearRules">
            清空所有规则
          </el-button>
        </div>
      </div>

      <!-- Combined Pricing Alert -->
      <el-alert type="info" :closable="false" show-icon>
        <template #title>
          <div class="pricing-tips">
            <p>{{ pricingHint }}</p>
            <p>
              提示：优先级数字越小越先匹配，模型匹配支持 * 通配符。总倍率直接填
              x，分项价格单位为余额/MTok，等效倍率会自动计算。
            </p>
          </div>
        </template>
      </el-alert>

      <el-table
        :data="filteredRules"
        :row-key="rowKey"
        stripe
        class="pricing-table"
        empty-text="暂无模型价格规则，未配置时使用全局 API 计费倍率"
      >
        <el-table-column label="启用" width="65" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" size="small" />
          </template>
        </el-table-column>

        <el-table-column label="模型匹配" min-width="200">
          <template #default="{ row }">
            <div class="model-pattern-cell">
              <span
                class="vendor-badge-tag"
                :style="{
                  backgroundColor: getVendorInfo(row.modelPattern).bg,
                  color: getVendorInfo(row.modelPattern).color,
                  border: getVendorInfo(row.modelPattern).border,
                }"
              >
                <svg
                  v-if="getVendorInfo(row.modelPattern).id === 'openai'"
                  class="vendor-logo-mini"
                  viewBox="0 0 24 24"
                  fill="currentColor"
                >
                  <path
                    d="M22.2819 9.8211a5.9847 5.9847 0 0 0-.5157-4.9108 6.0462 6.0462 0 0 0-6.5098-2.9A6.0651 6.0651 0 0 0 4.9807 4.1818a5.9847 5.9847 0 0 0-3.9977 2.9 6.0462 6.0462 0 0 0 .7427 7.0966 5.98 5.98 0 0 0 .511 4.9107 6.051 6.051 0 0 0 6.5146 2.9001A5.9847 5.9847 0 0 0 13.2599 24a6.0557 6.0557 0 0 0 5.7718-4.2058 5.9894 5.9894 0 0 0 3.9977-2.9001 6.0557 6.0557 0 0 0-.7475-7.0729zm-9.022 12.6081a4.4755 4.4755 0 0 1-2.8764-1.0408l.1419-.0804 4.7783-2.7582a.7948.7948 0 0 0 .3927-.6813v-6.7369l2.02 1.1686a.071.071 0 0 1 .038.052v5.5826a4.504 4.504 0 0 1-4.4945 4.4944zm-9.6607-4.1254a4.4708 4.4708 0 0 1-.5346-3.0137l.142.0852 4.783 2.7582a.7712.7712 0 0 0 .7806 0l5.8428-3.3685v2.3324a.0804.0804 0 0 1-.0332.0615L9.74 19.9502a4.4992 4.4992 0 0 1-6.1408-1.6464zM2.3408 7.8956a4.485 4.485 0 0 1 2.3655-1.9728V11.6a.7664.7664 0 0 0 .3879.6765l5.8144 3.3543-2.0201 1.1685a.0757.0757 0 0 1-.071 0l-4.8303-2.7865A4.504 4.504 0 0 1 2.3408 7.872zm16.5963 3.8558L13.1038 8.364 15.1192 7.2a.0757.0757 0 0 1 .071 0l4.8303 2.7913a4.4944 4.4944 0 0 1-.6765 8.1042v-5.6772a.79.79 0 0 0-.407-.667zm2.0107-3.0231l-.142-.0852-4.7735-2.7818a.7759.7759 0 0 0-.7854 0L9.409 9.2297V6.8974a.0662.0662 0 0 1 .0284-.0615l4.8303-2.7866a4.4992 4.4992 0 0 1 6.6802 4.66zM8.3065 12.863l-2.02-1.1638a.0804.0804 0 0 1-.038-.0567V6.0742a4.4992 4.4992 0 0 1 7.3757-3.4537l-.142.0805L8.704 5.459a.7948.7948 0 0 0-.3927.6813zm1.0976-2.3654l2.602-1.4998 2.6069 1.4998v2.9994l-2.5974 1.4997-2.6067-1.4997Z"
                  />
                </svg>
                <svg
                  v-else-if="getVendorInfo(row.modelPattern).id === 'anthropic'"
                  class="vendor-logo-mini"
                  viewBox="0 0 24 24"
                  fill="currentColor"
                >
                  <path d="M12 2L3 22h4l2.5-6h5l2.5 6h4L12 2zm-1.3 10.5L12 7.7l1.3 4.8h-2.6z" />
                </svg>
                <svg
                  v-else-if="['google', 'global'].includes(getVendorInfo(row.modelPattern).id)"
                  class="vendor-logo-mini"
                  viewBox="0 0 24 24"
                  fill="currentColor"
                >
                  <path
                    d="M12 2c0 5.5-4.5 10-10 10 5.5 0 10 4.5 10 10 0-5.5 4.5-10 10-10-5.5 0-10-4.5-10-10z"
                  />
                </svg>
                <svg
                  v-else-if="getVendorInfo(row.modelPattern).id === 'deepseek'"
                  class="vendor-logo-mini"
                  viewBox="0 0 24 24"
                  fill="currentColor"
                >
                  <path
                    d="M12 2C6.48 2 2 6.48 2 12c0 3.08 1.4 5.84 3.61 7.7L12 14.83l6.39 4.87C20.6 17.84 22 15.08 22 12c0-5.52-4.48-10-10-10zm0 10.5L6.5 17.5h11L12 12.5z"
                  />
                </svg>
                <svg
                  v-else-if="getVendorInfo(row.modelPattern).id === 'meta'"
                  class="vendor-logo-mini"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2.5"
                >
                  <path
                    d="M7 16.5c-2.5 0-4.5-2-4.5-4.5s2-4.5 4.5-4.5c2.5 0 4.5 2 6 4.5 1.5 2.5 3.5 4.5 6 4.5 2.5 0 4.5-2 4.5-4.5s-2-4.5-4.5-4.5c-2.5 0-4.5 2-6 4.5-1.5-2.5-3.5-4.5-6-4.5z"
                  />
                </svg>
                <svg
                  v-else-if="getVendorInfo(row.modelPattern).id === 'midjourney'"
                  class="vendor-logo-mini"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                >
                  <path d="M12 2L2 22h20L12 2zM12 18V2" />
                </svg>
                <svg
                  v-else
                  class="vendor-logo-mini"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                >
                  <circle cx="12" cy="12" r="10" />
                  <line x1="2" y1="12" x2="22" y2="12" />
                  <path
                    d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"
                  />
                </svg>
                {{ getVendorInfo(row.modelPattern).name }}
              </span>
              <el-select
                v-model="row.modelPattern"
                filterable
                allow-create
                default-first-option
                placeholder="如 gpt-5* / anthropic/*"
                size="small"
                style="flex: 1"
              >
                <el-option
                  v-for="option in modelOptions"
                  :key="option"
                  :label="option"
                  :value="option"
                />
              </el-select>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="总倍率 (x)" width="115" align="right">
          <template #default="{ row }">
            <el-input-number
              v-model="row.totalRatio"
              :min="0"
              :precision="6"
              :step="0.1"
              controls-position="right"
              size="small"
              placeholder="留空"
              style="width: 100%"
            />
            <div v-if="isPositive(row.totalRatio)" class="multiplier-preview">
              约 {{ formatPrice(priceFromRatio(row.totalRatio)) }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="分项价格 (余额/MTok)" min-width="360">
          <template #default="{ row }">
            <div class="split-editor">
              <label>
                <span>输入</span>
                <el-input-number
                  v-model="row.inputPrice"
                  :min="0"
                  :precision="10"
                  :step="0.01"
                  controls-position="right"
                  size="small"
                  :disabled="isPositive(row.totalRatio)"
                />
              </label>
              <label>
                <span>输出</span>
                <el-input-number
                  v-model="row.outputPrice"
                  :min="0"
                  :precision="10"
                  :step="0.01"
                  controls-position="right"
                  size="small"
                  :disabled="isPositive(row.totalRatio)"
                />
              </label>
              <label>
                <span>缓存写</span>
                <el-input-number
                  v-model="row.cacheWritePrice"
                  :min="0"
                  :precision="10"
                  :step="0.01"
                  controls-position="right"
                  size="small"
                  :disabled="isPositive(row.totalRatio)"
                  placeholder="同输入"
                />
              </label>
              <label>
                <span>缓存读</span>
                <el-input-number
                  v-model="row.cacheReadPrice"
                  :min="0"
                  :precision="10"
                  :step="0.01"
                  controls-position="right"
                  size="small"
                  :disabled="isPositive(row.totalRatio)"
                  placeholder="输入 0.1"
                />
              </label>
            </div>
            <div v-if="!isPositive(row.totalRatio)" class="multiplier-preview">
              等效倍率：入 {{ formatPriceMultiplier(row.inputPrice) }} / 出
              {{ formatPriceMultiplier(row.outputPrice) }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="计价模式" width="90">
          <template #default="{ row }">
            <el-tag effect="light" size="small">{{ priceMode(row) }}</el-tag>
            <div class="ratio-summary">
              {{
                isPositive(row.totalRatio)
                  ? formatRatio(row.totalRatio)
                  : `入 ${formatPrice(row.inputPrice)} / 出 ${formatPrice(row.outputPrice)}`
              }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="优先级" width="80" align="right">
          <template #default="{ row }">
            <el-input-number
              v-model="row.priority"
              :min="0"
              :max="10000"
              controls-position="right"
              size="small"
              style="width: 100%"
            />
          </template>
        </el-table-column>

        <el-table-column label="更新时间" width="135">
          <template #default="{ row }">
            <span class="text-muted">{{ formatDateTime(row.updatedAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="65" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="removeRule(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </PageSection>
</template>

<style scoped lang="scss">
  .model-pricing {
    display: flex;
    flex-direction: column;
    gap: 18px;
  }

  .pricing-toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 16px;
    flex-wrap: wrap;
    background: rgba(255, 255, 255, 0.45);
    border: 1px solid var(--kp-border);
    border-radius: var(--kp-radius);
    padding: 8px 12px;
    box-shadow: 0 4px 16px rgba(31, 45, 80, 0.05);
    backdrop-filter: blur(10px);

    .vendor-segmented-tabs {
      flex: 1;
      min-width: 300px;
      --el-segmented-bg-color: transparent;
      --el-segmented-item-selected-bg-color: rgba(91, 141, 239, 0.15);
      --el-segmented-item-selected-color: var(--kp-primary);
      --el-segmented-item-hover-bg-color: rgba(91, 141, 239, 0.05);

      background-color: transparent !important;
      border: none !important;

      :deep(.el-segmented__wrapper) {
        background-color: transparent !important;
        padding: 2px;
      }

      :deep(.el-segmented__item) {
        font-weight: 700;
        font-size: 13px;
        border-radius: 8px;
        color: var(--kp-text-secondary);
        transition: all 0.2s ease;

        &.is-selected {
          color: var(--kp-primary) !important;
          font-weight: 800;
          background-color: rgba(91, 141, 239, 0.12) !important;
          box-shadow: inset 0 0 0 1px rgba(91, 141, 239, 0.2) !important;
        }

        &:hover:not(.is-selected) {
          color: var(--kp-text) !important;
          background-color: rgba(15, 23, 42, 0.04) !important;
        }
      }
    }

    .toolbar-right {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-shrink: 0;
    }
  }

  :deep(.el-alert) {
    background: rgba(255, 255, 255, 0.45) !important;
    border: 1px solid var(--kp-border) !important;
    border-radius: var(--kp-radius-sm) !important;
    color: var(--kp-text-secondary) !important;
    backdrop-filter: blur(10px);

    .el-alert__title {
      font-size: 12.5px;
      line-height: 1.5;
      font-weight: 500;
      color: var(--kp-text-secondary);
    }

    .el-alert__icon {
      color: var(--kp-primary);
    }
  }

  .model-pattern-cell {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
  }

  .vendor-badge-tag {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    padding: 0 10px;
    border-radius: 6px;
    font-size: 11px;
    font-weight: 700;
    white-space: nowrap;
    height: 32px;
    box-sizing: border-box;

    .vendor-logo-mini {
      width: 12px;
      height: 12px;
      flex-shrink: 0;
    }
  }

  /* Unified Metrics Panel */
  .metrics-panel {
    display: flex;
    align-items: center;
    padding: 20px 28px;
    width: 100%;

    .metric-item {
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 0;

      &__header {
        display: flex;
        align-items: center;
        gap: 6px;
      }

      .metric-dot {
        width: 7px;
        height: 7px;
        border-radius: 50%;
        display: inline-block;

        &.dot-total {
          background-color: var(--kp-primary);
          box-shadow: 0 0 6px rgba(91, 141, 239, 0.6);
        }
        &.dot-active {
          background-color: #36c08a;
          box-shadow: 0 0 6px rgba(54, 192, 138, 0.6);
        }
        &.dot-suspended {
          background-color: #ef5b6b;
          box-shadow: 0 0 6px rgba(239, 91, 107, 0.6);
        }
        &.dot-requests {
          background-color: #b387ff;
          box-shadow: 0 0 6px rgba(179, 135, 255, 0.6);
        }
      }

      .metric-label {
        font-size: 12.5px;
        font-weight: 600;
        color: var(--kp-text-secondary);
      }

      .metric-value {
        font-size: 26px;
        font-weight: 800;
        color: var(--kp-text);
        line-height: 1.15;
        font-family: ui-monospace, 'JetBrains Mono', 'SF Mono', Consolas, monospace;

        &.text-success {
          color: #36c08a;
        }
        &.text-danger {
          color: #ef5b6b;
        }

        &__sub {
          font-size: 13px;
          font-weight: 500;
          color: var(--kp-text-muted);
        }
      }
    }

    .metric-divider {
      width: 1px;
      height: 40px;
      background-color: var(--kp-divider);
      margin: 0 28px;
      align-self: center;
      flex-shrink: 0;
    }
  }

  .pricing-table {
    width: 100%;
  }

  .split-editor {
    display: flex;
    gap: 6px;
    width: 100%;

    label {
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 3px;
      color: var(--kp-text-muted);
      font-size: 11px;
      line-height: 1.1;
      min-width: 76px;
    }

    :deep(.el-input-number) {
      width: 100%;
    }
  }

  .multiplier-preview,
  .ratio-summary {
    margin-top: 4px;
    color: var(--kp-text-muted);
    font-size: 11px;
    line-height: 1.2;
  }

  @media (max-width: 1024px) {
    .metrics-panel {
      padding: 16px 20px;

      .metric-divider {
        margin: 0 20px;
      }
    }
  }

  @media (max-width: 768px) {
    .metrics-panel {
      flex-direction: column;
      align-items: stretch;
      gap: 16px;
      padding: 20px;

      .metric-divider {
        width: 100%;
        height: 1px;
        margin: 4px 0;
      }
    }
  }

  @media (max-width: 640px) {
    .split-editor {
      grid-template-columns: repeat(2, minmax(104px, 1fr));
    }
  }
</style>
