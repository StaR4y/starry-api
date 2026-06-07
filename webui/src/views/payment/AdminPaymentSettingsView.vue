<script setup lang="ts">
  /**
   * 管理员支付设置。
   *
   * 配置项参考 sub2api 的支付设置，实际落库由后端 payment settings 服务负责。
   */
  import { computed, onMounted, reactive, ref } from 'vue';
  import { ElMessage } from 'element-plus';
  import { Refresh } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { adminPaymentApi } from '@/api';
  import type {
    PaymentOrder,
    PaymentSettings,
    PaymentType,
    UpdatePaymentSettingsRequest,
  } from '@/types/api';
  import { formatDateTime, formatNumber } from '@/utils/format';

  const loading = ref(false);
  const submitting = ref(false);
  const orders = ref<PaymentOrder[]>([]);
  const pkeyConfigured = ref(false);

  const form = reactive<Required<UpdatePaymentSettingsRequest>>({
    enabled: false,
    minAmount: 1,
    maxAmount: 1000,
    dailyLimit: 1000,
    orderTimeoutMinutes: 30,
    maxPendingOrders: 3,
    enabledTypes: ['alipay', 'wxpay'],
    balanceRechargeMultiplier: 1,
    rechargeFeeRate: 0,
    productNamePrefix: 'Starry API',
    productNameSuffix: '余额充值',
    helpImageUrl: '',
    helpText: '',
    easypayApiBase: '',
    easypayPid: '',
    easypayPkey: '',
    easypayPaymentMode: 'popup',
  });

  const paymentTypeOptions: Array<{ label: string; value: PaymentType }> = [
    { label: '支付宝', value: 'alipay' },
    { label: '微信支付', value: 'wxpay' },
  ];

  const orderStats = computed(() => {
    const completed = orders.value.filter((order) => order.status === 'COMPLETED');
    const pending = orders.value.filter((order) => order.status === 'PENDING' || order.status === 'PAID');
    return {
      completed: completed.length,
      pending: pending.length,
      completedAmount: completed.reduce((sum, order) => sum + order.amount, 0),
      paidAmount: completed.reduce((sum, order) => sum + order.payAmount, 0),
    };
  });

  function applySettings(settings: PaymentSettings) {
    form.enabled = settings.enabled;
    form.minAmount = settings.minAmount;
    form.maxAmount = settings.maxAmount;
    form.dailyLimit = settings.dailyLimit;
    form.orderTimeoutMinutes = settings.orderTimeoutMinutes;
    form.maxPendingOrders = settings.maxPendingOrders;
    form.enabledTypes = settings.enabledTypes.length ? settings.enabledTypes : ['alipay', 'wxpay'];
    form.balanceRechargeMultiplier = settings.balanceRechargeMultiplier;
    form.rechargeFeeRate = settings.rechargeFeeRate;
    form.productNamePrefix = settings.productNamePrefix ?? '';
    form.productNameSuffix = settings.productNameSuffix ?? '';
    form.helpImageUrl = settings.helpImageUrl ?? '';
    form.helpText = settings.helpText ?? '';
    form.easypayApiBase = settings.easypayApiBase ?? '';
    form.easypayPid = settings.easypayPid ?? '';
    form.easypayPkey = '';
    form.easypayPaymentMode = settings.easypayPaymentMode === 'qrcode' ? 'qrcode' : 'popup';
    pkeyConfigured.value = Boolean(settings.easypayPkeyConfigured);
  }

  async function load() {
    loading.value = true;
    try {
      const [settings, nextOrders] = await Promise.all([
        adminPaymentApi.settings(),
        adminPaymentApi.orders(),
      ]);
      applySettings(settings);
      orders.value = nextOrders;
    } finally {
      loading.value = false;
    }
  }

  async function submit() {
    if (form.minAmount <= 0 || form.maxAmount < form.minAmount) {
      ElMessage.warning('请检查单笔充值金额范围');
      return;
    }
    if (form.enabledTypes.length === 0) {
      ElMessage.warning('至少启用一种支付方式');
      return;
    }

    submitting.value = true;
    try {
      const payload: UpdatePaymentSettingsRequest = {
        enabled: form.enabled,
        minAmount: form.minAmount,
        maxAmount: form.maxAmount,
        dailyLimit: form.dailyLimit,
        orderTimeoutMinutes: form.orderTimeoutMinutes,
        maxPendingOrders: form.maxPendingOrders,
        enabledTypes: form.enabledTypes,
        balanceRechargeMultiplier: form.balanceRechargeMultiplier,
        rechargeFeeRate: form.rechargeFeeRate,
        productNamePrefix: form.productNamePrefix,
        productNameSuffix: form.productNameSuffix,
        helpImageUrl: form.helpImageUrl,
        helpText: form.helpText,
        easypayApiBase: form.easypayApiBase,
        easypayPid: form.easypayPid,
        easypayPaymentMode: form.easypayPaymentMode,
      };
      if (form.easypayPkey) payload.easypayPkey = form.easypayPkey;
      const next = await adminPaymentApi.updateSettings(payload);
      applySettings(next);
      ElMessage.success('支付设置已保存');
    } finally {
      submitting.value = false;
    }
  }

  function statusLabel(status: string) {
    const map: Record<string, string> = {
      PENDING: '待支付',
      PAID: '已支付',
      COMPLETED: '已入账',
      EXPIRED: '已过期',
      CANCELLED: '已取消',
      FAILED: '失败',
    };
    return map[status] ?? status;
  }

  function statusType(status: string) {
    if (status === 'COMPLETED') return 'success';
    if (status === 'PENDING' || status === 'PAID') return 'warning';
    if (status === 'FAILED') return 'danger';
    return 'info';
  }

  function paymentTypeLabel(type: PaymentType) {
    return type === 'wxpay' ? '微信支付' : '支付宝';
  }

  onMounted(load);
</script>

<template>
  <PageSection title="支付设置" subtitle="配置 EasyPay 在线充值，并查看近期充值订单">
    <template #actions>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存设置</el-button>
    </template>

    <div v-loading="loading" class="payment-admin">
      <section class="payment-admin__stats">
        <article>
          <span>已入账订单</span>
          <strong>{{ formatNumber(orderStats.completed) }}</strong>
        </article>
        <article>
          <span>待处理订单</span>
          <strong>{{ formatNumber(orderStats.pending) }}</strong>
        </article>
        <article>
          <span>入账余额</span>
          <strong>{{ formatNumber(orderStats.completedAmount) }}</strong>
        </article>
        <article>
          <span>实付金额</span>
          <strong>{{ formatNumber(orderStats.paidAmount) }}</strong>
        </article>
      </section>

      <section class="payment-admin__grid">
        <div class="payment-admin__panel">
          <div class="payment-admin__panel-title">基础配置</div>
          <el-form label-position="top">
            <div class="payment-admin__row payment-admin__row--switch">
              <div>
                <strong>启用在线充值</strong>
                <p class="text-muted">关闭后用户无法创建新的支付订单，历史订单仍可查看。</p>
              </div>
              <el-switch v-model="form.enabled" />
            </div>

            <el-form-item label="支付方式">
              <el-checkbox-group v-model="form.enabledTypes">
                <el-checkbox
                  v-for="option in paymentTypeOptions"
                  :key="option.value"
                  :label="option.value"
                >
                  {{ option.label }}
                </el-checkbox>
              </el-checkbox-group>
            </el-form-item>

            <div class="payment-admin__form-grid">
              <el-form-item label="单笔最小金额">
                <el-input-number
                  v-model="form.minAmount"
                  :min="0.01"
                  :precision="2"
                  controls-position="right"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="单笔最大金额">
                <el-input-number
                  v-model="form.maxAmount"
                  :min="form.minAmount"
                  :precision="2"
                  controls-position="right"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="每日限额">
                <el-input-number
                  v-model="form.dailyLimit"
                  :min="0"
                  :precision="2"
                  controls-position="right"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="最大待支付订单">
                <el-input-number
                  v-model="form.maxPendingOrders"
                  :min="1"
                  :max="50"
                  controls-position="right"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="订单超时分钟">
                <el-input-number
                  v-model="form.orderTimeoutMinutes"
                  :min="1"
                  :max="1440"
                  controls-position="right"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="余额到账倍率">
                <el-input-number
                  v-model="form.balanceRechargeMultiplier"
                  :min="0.000001"
                  :precision="6"
                  controls-position="right"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="手续费率">
                <el-input-number
                  v-model="form.rechargeFeeRate"
                  :min="0"
                  :max="1"
                  :precision="6"
                  :step="0.01"
                  controls-position="right"
                  style="width: 100%"
                />
              </el-form-item>
            </div>
          </el-form>
        </div>

        <div class="payment-admin__panel">
          <div class="payment-admin__panel-title">EasyPay 配置</div>
          <el-form label-position="top">
            <el-form-item label="接口地址">
              <el-input v-model="form.easypayApiBase" placeholder="https://pay.example.com/" />
            </el-form-item>
            <el-form-item label="商户 PID">
              <el-input v-model="form.easypayPid" />
            </el-form-item>
            <el-form-item label="商户密钥">
              <el-input
                v-model="form.easypayPkey"
                type="password"
                show-password
                :placeholder="pkeyConfigured ? '已配置，留空保持不变' : '请输入商户密钥'"
                autocomplete="new-password"
              />
            </el-form-item>
            <el-form-item label="支付模式">
              <el-radio-group v-model="form.easypayPaymentMode">
                <el-radio-button label="popup" value="popup">跳转收银台</el-radio-button>
                <el-radio-button label="qrcode" value="qrcode">二维码接口</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <div class="payment-admin__form-grid">
              <el-form-item label="商品名前缀">
                <el-input v-model="form.productNamePrefix" />
              </el-form-item>
              <el-form-item label="商品名后缀">
                <el-input v-model="form.productNameSuffix" />
              </el-form-item>
            </div>
            <el-form-item label="帮助图片 URL">
              <el-input v-model="form.helpImageUrl" />
            </el-form-item>
            <el-form-item label="帮助说明">
              <el-input v-model="form.helpText" type="textarea" :rows="3" />
            </el-form-item>
          </el-form>
        </div>
      </section>

      <section class="payment-admin__orders">
        <div class="payment-admin__panel-title">近期订单</div>
        <el-table :data="orders" stripe>
          <el-table-column label="订单号" min-width="180">
            <template #default="{ row }">
              <code>{{ row.outTradeNo }}</code>
            </template>
          </el-table-column>
          <el-table-column label="用户" min-width="170">
            <template #default="{ row }">{{ row.username || row.email || row.userId }}</template>
          </el-table-column>
          <el-table-column label="方式" width="110">
            <template #default="{ row }">{{ paymentTypeLabel(row.paymentType) }}</template>
          </el-table-column>
          <el-table-column label="余额" width="110" align="right">
            <template #default="{ row }">{{ formatNumber(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="实付" width="110" align="right">
            <template #default="{ row }">{{ formatNumber(row.payAmount) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" effect="light" round>
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="三方单号" min-width="150">
            <template #default="{ row }">{{ row.paymentTradeNo || '—' }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="入账时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.completedAt) }}</template>
          </el-table-column>
        </el-table>
      </section>
    </div>
  </PageSection>
</template>

<style scoped lang="scss">
  .payment-admin {
    display: flex;
    flex-direction: column;
    gap: 18px;

    &__stats,
    &__grid,
    &__form-grid {
      display: grid;
      gap: 14px;
    }

    &__stats {
      grid-template-columns: repeat(4, minmax(0, 1fr));

      article {
        border: 1px solid var(--kp-divider);
        background: rgba(255, 255, 255, 0.62);
        border-radius: 10px;
        padding: 16px;
        display: flex;
        flex-direction: column;
        gap: 8px;
      }

      span {
        color: var(--kp-text-muted);
        font-size: 12px;
      }

      strong {
        font-size: 20px;
      }
    }

    &__grid {
      grid-template-columns: minmax(360px, 1fr) minmax(360px, 1fr);
      align-items: start;
    }

    &__panel,
    &__orders {
      border: 1px solid var(--kp-divider);
      background: rgba(255, 255, 255, 0.62);
      border-radius: 10px;
      padding: 18px;
    }

    &__panel-title {
      margin-bottom: 16px;
      font-size: 16px;
      font-weight: 600;
    }

    &__row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 18px;
      border: 1px solid var(--kp-divider);
      border-radius: 8px;
      padding: 12px 14px;
      margin-bottom: 16px;

      p {
        margin: 4px 0 0;
        font-size: 12px;
        line-height: 1.5;
      }

      &--switch {
        background: rgba(255, 255, 255, 0.52);
      }
    }

    &__form-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media (max-width: 1100px) {
    .payment-admin {
      &__stats,
      &__grid,
      &__form-grid {
        grid-template-columns: 1fr;
      }
    }
  }
</style>
