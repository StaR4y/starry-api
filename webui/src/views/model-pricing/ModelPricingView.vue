<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { Refresh, Setting } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { modelPricingApi, systemApi } from '@/api';
  import type {
    ModelInfo,
    ModelPricingRule,
    UpdateModelPricingRuleRequest,
  } from '@/types/api';
  import { formatDateTime, formatNumber } from '@/utils/format';

  interface EditableRule {
    tempId: string;
    ruleId?: string;
    enabled: boolean;
    modelPattern: string;
    inputMultiplier: number | null;
    outputMultiplier: number | null;
    totalMultiplier: number | null;
    priority: number;
    createdAt?: string | null;
    updatedAt?: string | null;
  }

  const DEFAULT_TOTAL_MULTIPLIER = 0.0000166666666667;
  const loading = ref(false);
  const submitting = ref(false);
  const rules = ref<EditableRule[]>([]);
  const models = ref<ModelInfo[]>([]);

  const modelOptions = computed(() => {
    const presets = ['*', 'anthropic/*', 'openai/*', 'google/*', 'gpt-5*', 'claude-*', 'gemini-*'];
    return Array.from(new Set([...presets, ...models.value.map((model) => model.id)])).sort((a, b) =>
      a.localeCompare(b),
    );
  });

  const stats = computed(() => {
    const active = rules.value.filter((rule) => rule.enabled);
    return {
      total: rules.value.length,
      active: active.length,
      totalPrice: active.filter((rule) => isPositive(rule.totalMultiplier)).length,
      splitPrice: active.filter((rule) => !isPositive(rule.totalMultiplier)).length,
    };
  });

  function toEditable(rule: ModelPricingRule): EditableRule {
    return {
      tempId: crypto.randomUUID(),
      ruleId: rule.ruleId,
      enabled: rule.enabled !== false,
      modelPattern: rule.modelPattern,
      inputMultiplier: numberOrNull(rule.inputMultiplier),
      outputMultiplier: numberOrNull(rule.outputMultiplier),
      totalMultiplier: numberOrNull(rule.totalMultiplier),
      priority: rule.priority ?? 100,
      createdAt: rule.createdAt,
      updatedAt: rule.updatedAt,
    };
  }

  function addRule(pattern = '') {
    rules.value.push({
      tempId: crypto.randomUUID(),
      enabled: true,
      modelPattern: pattern,
      inputMultiplier: null,
      outputMultiplier: null,
      totalMultiplier: DEFAULT_TOTAL_MULTIPLIER,
      priority: 100,
    });
  }

  function removeRule(index: number) {
    rules.value.splice(index, 1);
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

      const totalMultiplier = positiveOrNull(rule.totalMultiplier);
      const inputMultiplier = positiveOrNull(rule.inputMultiplier);
      const outputMultiplier = positiveOrNull(rule.outputMultiplier);
      if (!totalMultiplier && (!inputMultiplier || !outputMultiplier)) {
        ElMessage.warning(`第 ${index + 1} 条规则需要填写总 token 单价，或同时填写输入/输出单价`);
        return null;
      }

      payload.push({
        ruleId: rule.ruleId,
        enabled: rule.enabled,
        modelPattern: pattern,
        totalMultiplier,
        inputMultiplier: totalMultiplier ? null : inputMultiplier,
        outputMultiplier: totalMultiplier ? null : outputMultiplier,
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

  function positiveOrNull(value: number | null | undefined) {
    const normalized = numberOrNull(value);
    return normalized !== null && Number.isFinite(normalized) && normalized > 0 ? normalized : null;
  }

  function isPositive(value: number | null | undefined) {
    return positiveOrNull(value) !== null;
  }

  function priceMode(rule: EditableRule) {
    return isPositive(rule.totalMultiplier) ? '总 token' : '输入/输出';
  }

  onMounted(load);
</script>

<template>
  <PageSection title="模型价格" subtitle="按模型 ID 或通配符调整 API 余额扣费倍率，只有管理员可修改">
    <template #actions>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button @click="addRule()">新增规则</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存价格</el-button>
    </template>

    <div v-loading="loading" class="model-pricing">
      <div class="metrics">
        <div class="metric">
          <span class="metric__label">规则总数</span>
          <strong>{{ formatNumber(stats.total) }}</strong>
        </div>
        <div class="metric">
          <span class="metric__label">启用规则</span>
          <strong>{{ formatNumber(stats.active) }}</strong>
        </div>
        <div class="metric">
          <span class="metric__label">总价模式</span>
          <strong>{{ formatNumber(stats.totalPrice) }}</strong>
        </div>
        <div class="metric">
          <span class="metric__label">输入/输出模式</span>
          <strong>{{ formatNumber(stats.splitPrice) }}</strong>
        </div>
      </div>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="未命中任何规则时，仍使用支付设置里的 API计费倍率。总 token 单价优先；留空总价时，必须同时配置输入和输出单价。"
      />

      <div class="table-actions">
        <div class="table-actions__hint">
          <el-icon><Setting /></el-icon>
          <span>优先级数字越小越先匹配，模型匹配支持 * 通配符。</span>
        </div>
        <el-button link type="danger" :disabled="rules.length === 0" @click="clearRules">清空规则</el-button>
      </div>

      <el-table
        :data="rules"
        :row-key="rowKey"
        stripe
        class="pricing-table"
        empty-text="暂无模型价格规则，未配置时使用全局 API 计费倍率"
      >
        <el-table-column label="启用" width="82" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" />
          </template>
        </el-table-column>

        <el-table-column label="模型匹配" min-width="250">
          <template #default="{ row }">
            <el-select
              v-model="row.modelPattern"
              filterable
              allow-create
              default-first-option
              placeholder="如 gpt-5* / anthropic/*"
              style="width: 100%"
            >
              <el-option
                v-for="option in modelOptions"
                :key="option"
                :label="option"
                :value="option"
              />
            </el-select>
          </template>
        </el-table-column>

        <el-table-column label="总 token 单价" width="190" align="right">
          <template #default="{ row }">
            <el-input-number
              v-model="row.totalMultiplier"
              :min="0"
              :precision="16"
              :step="0.000000000001"
              controls-position="right"
              placeholder="留空使用拆分价"
              style="width: 100%"
            />
          </template>
        </el-table-column>

        <el-table-column label="输入 token 单价" width="190" align="right">
          <template #default="{ row }">
            <el-input-number
              v-model="row.inputMultiplier"
              :min="0"
              :precision="16"
              :step="0.000000000001"
              controls-position="right"
              :disabled="isPositive(row.totalMultiplier)"
              style="width: 100%"
            />
          </template>
        </el-table-column>

        <el-table-column label="输出 token 单价" width="190" align="right">
          <template #default="{ row }">
            <el-input-number
              v-model="row.outputMultiplier"
              :min="0"
              :precision="16"
              :step="0.000000000001"
              controls-position="right"
              :disabled="isPositive(row.totalMultiplier)"
              style="width: 100%"
            />
          </template>
        </el-table-column>

        <el-table-column label="计价模式" width="110">
          <template #default="{ row }">
            <el-tag effect="light">{{ priceMode(row) }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="优先级" width="120" align="right">
          <template #default="{ row }">
            <el-input-number
              v-model="row.priority"
              :min="0"
              :max="10000"
              controls-position="right"
              style="width: 100%"
            />
          </template>
        </el-table-column>

        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">
            <span class="text-muted">{{ formatDateTime(row.updatedAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ $index }">
            <el-button link type="danger" @click="removeRule($index)">删除</el-button>
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

  .metrics {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 12px;
  }

  .metric {
    border: 1px solid var(--kp-divider);
    border-radius: var(--kp-radius-sm);
    padding: 14px 16px;
    background: rgba(255, 255, 255, 0.46);

    &__label {
      display: block;
      color: var(--kp-text-muted);
      font-size: 12px;
      margin-bottom: 4px;
    }

    strong {
      font-size: 22px;
      line-height: 1.2;
    }
  }

  .table-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;

    &__hint {
      display: flex;
      align-items: center;
      gap: 8px;
      color: var(--kp-text-secondary);
      font-size: 13px;
    }
  }

  .pricing-table {
    width: 100%;
  }

  @media (max-width: 900px) {
    .metrics {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media (max-width: 640px) {
    .metrics {
      grid-template-columns: 1fr;
    }
  }
</style>
