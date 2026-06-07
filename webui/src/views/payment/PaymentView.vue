<script setup lang="ts">
  /**
   * 普通用户余额充值。
   *
   * 金额、支付方式和订单列表都从后端支付模块读取，前端只负责展示与跳转收银台。
   */
  import { computed, onMounted, reactive, ref } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { Refresh, Wallet } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { paymentApi } from '@/api';
  import { useAuthStore } from '@/stores/auth';
  import type { PaymentOrder, PaymentSettings, PaymentType } from '@/types/api';
  import { formatDateTime, formatNumber } from '@/utils/format';

  const auth = useAuthStore();
  const loading = ref(false);
  const creating = ref(false);
  const orders = ref<PaymentOrder[]>([]);
  const activeOrder = ref<PaymentOrder | null>(null);
  const settings = ref<PaymentSettings | null>(null);

  const form = reactive<{
    amount: number;
    paymentType: PaymentType;
  }>({
    amount: 10,
    paymentType: 'alipay',
  });

  const paymentTypeLabels: Record<PaymentType, string> = {
    alipay: '支付宝',
    wxpay: '微信支付',
  };

  const availableTypes = computed(() => settings.value?.enabledTypes ?? []);
  const isPaymentEnabled = computed(
    () => Boolean(settings.value?.enabled) && availableTypes.value.length > 0,
  );
  const currentBalance = computed(() => auth.user?.balance ?? 0);
  const feeRateText = computed(() => `${formatNumber((settings.value?.rechargeFeeRate ?? 0) * 100)}%`);
  const creditedAmount = computed(
    () => Number((form.amount * (settings.value?.balanceRechargeMultiplier ?? 1)).toFixed(6)),
  );
  const payAmount = computed(
    () => Number((form.amount * (1 + (settings.value?.rechargeFeeRate ?? 0))).toFixed(6)),
  );
  const pendingOrders = computed(() =>
    orders.value.filter((order) => order.status === 'PENDING' || order.status === 'PAID'),
  );
  const latestCompleted = computed(
    () => orders.value.find((order) => order.status === 'COMPLETED') ?? null,
  );

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

  function paymentTypeLabel(type: PaymentType | string) {
    return type === 'wxpay' ? '微信支付' : '支付宝';
  }

  function isQrCodeImage(value: string | null | undefined) {
    return Boolean(value && (value.startsWith('http') || value.startsWith('data:image/')));
  }

  function defaultReturnUrl() {
    return `${location.origin}${location.pathname}#/payment`;
  }

  async function load() {
    loading.value = true;
    try {
      const [nextSettings, nextOrders] = await Promise.all([
        paymentApi.settings(),
        paymentApi.orders(),
        auth.refreshCurrentUser(),
      ]);

      settings.value = nextSettings;
      orders.value = nextOrders;
      if (!availableTypes.value.includes(form.paymentType) && availableTypes.value.length > 0) {
        form.paymentType = availableTypes.value[0];
      }
      activeOrder.value =
        nextOrders.find((order) => order.status === 'PENDING' || order.status === 'PAID') ?? null;
    } finally {
      loading.value = false;
    }
  }

  async function createOrder() {
    if (!settings.value) return;
    if (!isPaymentEnabled.value) {
      ElMessage.warning('当前未开放在线充值');
      return;
    }
    if (form.amount < settings.value.minAmount || form.amount > settings.value.maxAmount) {
      ElMessage.warning(`充值金额需在 ${settings.value.minAmount} - ${settings.value.maxAmount} 之间`);
      return;
    }

    creating.value = true;
    try {
      const result = await paymentApi.createOrder({
        amount: form.amount,
        paymentType: form.paymentType,
        returnUrl: defaultReturnUrl(),
        mobile: /Android|iPhone|iPad|Mobile/i.test(navigator.userAgent),
      });
      activeOrder.value = result.order;
      orders.value = [result.order, ...orders.value.filter((order) => order.orderId !== result.order.orderId)];
      ElMessage.success('充值订单已创建');
      const target = result.payUrl || result.order.payUrl;
      if (target) window.open(target, '_blank', 'noopener,noreferrer');
    } finally {
      creating.value = false;
    }
  }

  async function openPay(order: PaymentOrder) {
    if (!order.payUrl) {
      ElMessage.warning('该订单没有可用支付链接');
      return;
    }
    window.open(order.payUrl, '_blank', 'noopener,noreferrer');
  }

  async function cancelOrder(order: PaymentOrder) {
    await ElMessageBox.confirm(`确认取消订单 ${order.outTradeNo}？`, '取消订单', {
      confirmButtonText: '取消订单',
      cancelButtonText: '关闭',
      type: 'warning',
    }).catch(() => Promise.reject(new Error('cancel')));
    const updated = await paymentApi.cancelOrder(order.orderId);
    orders.value = orders.value.map((item) => (item.orderId === updated.orderId ? updated : item));
    if (activeOrder.value?.orderId === updated.orderId) activeOrder.value = null;
    ElMessage.success('订单已取消');
  }

  onMounted(load);
</script>

<template>
  <PageSection title="余额充值" subtitle="为当前登录账号充值余额，支付完成后由回调自动入账">
    <template #actions>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </template>

    <div v-loading="loading" class="payment">
      <section class="payment__summary">
        <article class="payment__metric payment__metric--balance">
          <span>当前余额</span>
          <strong>{{ formatNumber(currentBalance) }}</strong>
        </article>
        <article class="payment__metric">
          <span>待处理订单</span>
          <strong>{{ formatNumber(pendingOrders.length) }}</strong>
        </article>
        <article class="payment__metric">
          <span>最近入账</span>
          <strong>{{ latestCompleted ? formatDateTime(latestCompleted.completedAt) : '—' }}</strong>
        </article>
      </section>

      <section class="payment__grid">
        <div class="payment__panel">
          <div class="payment__panel-header">
            <h3>创建充值订单</h3>
            <el-tag :type="isPaymentEnabled ? 'success' : 'info'" effect="light" round>
              {{ isPaymentEnabled ? '已开放' : '未开放' }}
            </el-tag>
          </div>

          <el-alert
            v-if="!isPaymentEnabled"
            type="info"
            :closable="false"
            show-icon
            title="管理员尚未启用在线支付"
          />

          <el-form v-if="settings" label-position="top" class="payment__form">
            <el-form-item label="充值金额">
              <el-input-number
                v-model="form.amount"
                :min="settings.minAmount"
                :max="settings.maxAmount"
                :precision="2"
                :step="10"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>

            <el-form-item label="支付方式">
              <el-radio-group v-model="form.paymentType" :disabled="!isPaymentEnabled">
                <el-radio-button
                  v-for="type in availableTypes"
                  :key="type"
                  :label="type"
                  :value="type"
                >
                  {{ paymentTypeLabels[type] }}
                </el-radio-button>
              </el-radio-group>
            </el-form-item>

            <div class="payment__quote">
              <div>
                <span>到账余额</span>
                <strong>{{ formatNumber(creditedAmount) }}</strong>
              </div>
              <div>
                <span>支付金额</span>
                <strong>{{ formatNumber(payAmount) }}</strong>
              </div>
              <div>
                <span>手续费率</span>
                <strong>{{ feeRateText }}</strong>
              </div>
            </div>

            <div class="payment__limits text-muted">
              单笔 {{ formatNumber(settings.minAmount) }} - {{ formatNumber(settings.maxAmount) }}，
              每日最多 {{ formatNumber(settings.dailyLimit) }}，
              最多保留 {{ formatNumber(settings.maxPendingOrders) }} 个待支付订单。
            </div>

            <el-button
              type="primary"
              :icon="Wallet"
              :loading="creating"
              :disabled="!isPaymentEnabled"
              @click="createOrder"
            >
              创建订单并支付
            </el-button>
          </el-form>
        </div>

        <div class="payment__panel">
          <div class="payment__panel-header">
            <h3>当前订单</h3>
            <el-tag
              v-if="activeOrder"
              :type="statusType(activeOrder.status)"
              effect="light"
              round
            >
              {{ statusLabel(activeOrder.status) }}
            </el-tag>
          </div>

          <div v-if="activeOrder" class="payment__active">
            <div class="payment__active-row">
              <span class="text-muted">订单号</span>
              <code>{{ activeOrder.outTradeNo }}</code>
            </div>
            <div class="payment__active-row">
              <span class="text-muted">支付金额</span>
              <strong>{{ formatNumber(activeOrder.payAmount) }}</strong>
            </div>
            <div class="payment__active-row">
              <span class="text-muted">过期时间</span>
              <strong>{{ formatDateTime(activeOrder.expiresAt) }}</strong>
            </div>

            <img
              v-if="isQrCodeImage(activeOrder.qrCode)"
              class="payment__qr"
              :src="activeOrder.qrCode || ''"
              alt="支付二维码"
            />
            <code v-else-if="activeOrder.qrCode" class="payment__qr-text">
              {{ activeOrder.qrCode }}
            </code>

            <div class="payment__actions">
              <el-button type="primary" :disabled="!activeOrder.payUrl" @click="openPay(activeOrder)">
                打开支付
              </el-button>
              <el-button
                v-if="activeOrder.status === 'PENDING'"
                @click="cancelOrder(activeOrder)"
              >
                取消订单
              </el-button>
            </div>
          </div>
          <el-empty v-else description="暂无待处理充值订单" />
        </div>
      </section>

      <section class="payment__orders">
        <h3>充值记录</h3>
        <el-table :data="orders" stripe>
          <el-table-column label="订单号" min-width="180">
            <template #default="{ row }">
              <code>{{ row.outTradeNo }}</code>
            </template>
          </el-table-column>
          <el-table-column label="方式" width="110">
            <template #default="{ row }">{{ paymentTypeLabel(row.paymentType) }}</template>
          </el-table-column>
          <el-table-column label="充值余额" width="120" align="right">
            <template #default="{ row }">{{ formatNumber(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="支付金额" width="120" align="right">
            <template #default="{ row }">{{ formatNumber(row.payAmount) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" effect="light" round>
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="入账时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.completedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :disabled="!row.payUrl || row.status !== 'PENDING'"
                @click="openPay(row)"
              >
                支付
              </el-button>
              <el-button
                link
                type="warning"
                :disabled="row.status !== 'PENDING'"
                @click="cancelOrder(row)"
              >
                取消
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>
  </PageSection>
</template>

<style scoped lang="scss">
  .payment {
    display: flex;
    flex-direction: column;
    gap: 18px;

    &__summary,
    &__grid {
      display: grid;
      gap: 14px;
    }

    &__summary {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }

    &__grid {
      grid-template-columns: minmax(320px, 0.9fr) minmax(320px, 1.1fr);
      align-items: start;
    }

    &__metric,
    &__panel,
    &__orders {
      border: 1px solid var(--kp-divider);
      background: rgba(255, 255, 255, 0.62);
      border-radius: 10px;
    }

    &__metric {
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 8px;

      span {
        color: var(--kp-text-muted);
        font-size: 12px;
      }

      strong {
        font-size: 20px;
        letter-spacing: 0;
      }

      &--balance strong {
        color: #059669;
      }
    }

    &__panel,
    &__orders {
      padding: 18px;
    }

    &__panel-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 16px;

      h3 {
        margin: 0;
        font-size: 16px;
        font-weight: 600;
      }
    }

    &__form {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    &__quote {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 10px;
      margin: 2px 0 8px;

      div {
        border: 1px solid var(--kp-divider);
        border-radius: 8px;
        padding: 10px;
        display: flex;
        flex-direction: column;
        gap: 6px;
      }

      span {
        color: var(--kp-text-muted);
        font-size: 12px;
      }

      strong {
        font-size: 16px;
      }
    }

    &__limits {
      font-size: 12px;
      line-height: 1.6;
      margin-bottom: 8px;
    }

    &__active {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    &__active-row {
      display: flex;
      justify-content: space-between;
      gap: 16px;
      align-items: center;

      code {
        word-break: break-all;
        text-align: right;
      }
    }

    &__qr {
      width: 180px;
      height: 180px;
      object-fit: contain;
      border: 1px solid var(--kp-divider);
      border-radius: 8px;
      align-self: center;
      background: #fff;
      padding: 8px;
    }

    &__qr-text {
      display: block;
      word-break: break-all;
      border: 1px solid var(--kp-divider);
      border-radius: 8px;
      padding: 10px;
      background: rgba(255, 255, 255, 0.72);
    }

    &__actions {
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
    }

    &__orders h3 {
      margin: 0 0 14px;
      font-size: 16px;
      font-weight: 600;
    }
  }

  @media (max-width: 960px) {
    .payment {
      &__summary,
      &__grid,
      &__quote {
        grid-template-columns: 1fr;
      }
    }
  }
</style>
