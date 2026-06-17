<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { Refresh } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { adminPaymentApi } from '@/api';
  import type { RedemptionCode } from '@/types/api';
  import { formatDateTime, formatNumber } from '@/utils/format';

  const loading = ref(false);
  const creating = ref(false);
  const codes = ref<RedemptionCode[]>([]);

  const form = reactive<{
    balance: number;
    count: number;
    expiresAt: Date | undefined;
  }>({
    balance: 100,
    count: 1,
    expiresAt: undefined,
  });

  const stats = computed(() => {
    const available = codes.value.filter((code) => code.enabled && !code.used && !isExpired(code));
    const used = codes.value.filter((code) => code.used);
    return {
      total: codes.value.length,
      available: available.length,
      used: used.length,
      availableBalance: available.reduce((sum, code) => sum + Number(code.balance || 0), 0),
    };
  });

  async function load() {
    loading.value = true;
    try {
      codes.value = await adminPaymentApi.redemptionCodes();
    } finally {
      loading.value = false;
    }
  }

  async function createCodes() {
    if (!Number.isFinite(form.balance) || form.balance <= 0) {
      ElMessage.warning('请填写大于 0 的兑换余额');
      return;
    }
    if (!Number.isInteger(form.count) || form.count < 1 || form.count > 100) {
      ElMessage.warning('生成数量需在 1 到 100 之间');
      return;
    }
    creating.value = true;
    try {
      const result = await adminPaymentApi.createRedemptionCodes({
        balance: form.balance,
        count: form.count,
        expiresAt: form.expiresAt ? form.expiresAt.toISOString() : null,
      });
      codes.value = [...result.codes, ...codes.value];
      if (result.codes.length === 1) {
        await copy(result.codes[0].code, false);
      } else {
        await copy(result.codes.map((code) => code.code).join('\n'), false);
      }
      ElMessage.success(`已生成 ${formatNumber(result.total)} 个兑换码，并复制到剪贴板`);
    } finally {
      creating.value = false;
    }
  }

  async function revoke(row: RedemptionCode) {
    await ElMessageBox.confirm(`确认停用兑换码 ${row.code}？`, '停用兑换码', {
      confirmButtonText: '停用',
      cancelButtonText: '取消',
      type: 'warning',
    }).catch(() => Promise.reject(new Error('cancel')));
    const updated = await adminPaymentApi.revokeRedemptionCode(row.codeId);
    codes.value = codes.value.map((item) => (item.codeId === updated.codeId ? updated : item));
    ElMessage.success('兑换码已停用');
  }

  async function copy(value: string, toast = true) {
    try {
      await navigator.clipboard.writeText(value);
      if (toast) ElMessage.success('已复制');
    } catch {
      if (toast) ElMessage.warning('请手动复制');
    }
  }

  function isExpired(row: RedemptionCode) {
    return Boolean(row.expiresAt && new Date(row.expiresAt).getTime() <= Date.now());
  }

  function statusLabel(row: RedemptionCode) {
    if (row.used) return '已使用';
    if (!row.enabled) return '已停用';
    if (isExpired(row)) return '已过期';
    return '可用';
  }

  function statusType(row: RedemptionCode) {
    if (row.used || !row.enabled) return 'info';
    if (isExpired(row)) return 'warning';
    return 'success';
  }

  onMounted(load);
</script>

<template>
  <PageSection
    title="兑换码"
    subtitle="生成可兑换指定余额的一次性兑换码，用户在余额充值页兑换后自动入账"
  >
    <template #actions>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </template>

    <div v-loading="loading" class="redemption">
      <section class="redemption__metrics">
        <div class="metric">
          <span>兑换码总数</span>
          <strong>{{ formatNumber(stats.total) }}</strong>
        </div>
        <div class="metric">
          <span>可用数量</span>
          <strong>{{ formatNumber(stats.available) }}</strong>
        </div>
        <div class="metric">
          <span>已使用</span>
          <strong>{{ formatNumber(stats.used) }}</strong>
        </div>
        <div class="metric">
          <span>未兑换余额</span>
          <strong>{{ formatNumber(stats.availableBalance) }}</strong>
        </div>
      </section>

      <section class="redemption__panel">
        <div class="redemption__panel-title">生成兑换码</div>
        <el-form label-position="top" class="redemption__form">
          <el-form-item label="每个兑换码到账余额">
            <el-input-number
              v-model="form.balance"
              :min="0.000001"
              :precision="6"
              :step="10"
              controls-position="right"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="生成数量">
            <el-input-number
              v-model="form.count"
              :min="1"
              :max="100"
              :step="1"
              :precision="0"
              controls-position="right"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="过期时间">
            <el-date-picker
              v-model="form.expiresAt"
              type="datetime"
              placeholder="不设置则长期有效"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label=" ">
            <el-button type="primary" :loading="creating" style="width: 100%" @click="createCodes">
              生成并复制
            </el-button>
          </el-form-item>
        </el-form>
      </section>

      <section class="redemption__panel">
        <div class="redemption__panel-title">兑换码列表</div>
        <el-table :data="codes" stripe>
          <el-table-column label="兑换码" min-width="220">
            <template #default="{ row }">
              <code class="mono">{{ row.code }}</code>
              <el-button link type="primary" @click="copy(row.code)">复制</el-button>
            </template>
          </el-table-column>
          <el-table-column label="余额" width="130" align="right">
            <template #default="{ row }">{{ formatNumber(row.balance) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="statusType(row)" effect="light" round>{{ statusLabel(row) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="使用用户" min-width="160">
            <template #default="{ row }">
              {{
                row.usedByUsername || row.usedByUserId || '—'
              }}
            </template>
          </el-table-column>
          <el-table-column label="过期时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.expiresAt) }}</template>
          </el-table-column>
          <el-table-column label="使用时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.usedAt) }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right" align="center">
            <template #default="{ row }">
              <el-button
                link
                type="warning"
                :disabled="row.used || !row.enabled"
                @click="revoke(row)"
              >
                停用
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>
  </PageSection>
</template>

<style scoped lang="scss">
  .redemption {
    display: flex;
    flex-direction: column;
    gap: 18px;

    &__metrics {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 14px;
    }

    &__panel {
      border: 1px solid var(--kp-divider);
      border-radius: var(--kp-radius);
      background: rgba(255, 255, 255, 0.55);
      box-shadow: var(--kp-shadow);
      padding: 18px;
    }

    &__panel-title {
      font-weight: 600;
      margin-bottom: 14px;
      color: var(--kp-text);
    }

    &__form {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 14px;
      align-items: end;
    }
  }

  .metric {
    border: 1px solid var(--kp-divider);
    border-radius: var(--kp-radius);
    background: rgba(255, 255, 255, 0.58);
    padding: 16px;
    display: flex;
    flex-direction: column;
    gap: 8px;

    span {
      color: var(--kp-text-secondary);
      font-size: 12px;
    }

    strong {
      font-size: 22px;
      color: var(--kp-text);
    }
  }

  .mono {
    font-family: ui-monospace, 'JetBrains Mono', 'SF Mono', Consolas, monospace;
  }

  @media (max-width: 900px) {
    .redemption {
      &__metrics,
      &__form {
        grid-template-columns: 1fr;
      }
    }
  }
</style>
