<script setup lang="ts">
  /**
   * 普通用户余额充值。
   *
   * 金额、支付方式和订单列表都从后端支付模块读取，前端只负责展示与跳转收银台。
   */
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Wallet, Timer, Check, Calendar, Warning } from '@element-plus/icons-vue';
import PageSection from '@/components/PageSection.vue';
import { paymentApi } from '@/api';
import { useAuthStore } from '@/stores/auth';
import type { PaymentOrder, PaymentSettings, PaymentType } from '@/types/api';
import { formatDateTime, formatNumber } from '@/utils/format';

  const auth = useAuthStore();
  const loading = ref(false);
  const creating = ref(false);
  const redeeming = ref(false);
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

  const redeemForm = reactive({
    code: '',
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
  const maxAmountLimit = computed(() => {
    const value = settings.value?.maxAmount ?? 0;
    return value > 0 ? value : undefined;
  });
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



  function paymentTypeLabel(type: PaymentType | string) {
    return type === 'wxpay' ? '微信支付' : '支付宝';
  }

  function amountRangeText(value: PaymentSettings) {
    return value.maxAmount > 0
      ? `${formatNumber(value.minAmount)} - ${formatNumber(value.maxAmount)}`
      : `${formatNumber(value.minAmount)} 起`;
  }

  function limitText(value: number) {
    return value > 0 ? formatNumber(value) : '不限';
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
    if (
      form.amount < settings.value.minAmount ||
      (settings.value.maxAmount > 0 && form.amount > settings.value.maxAmount)
    ) {
      ElMessage.warning(`充值金额需在 ${amountRangeText(settings.value)} 范围内`);
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

  async function redeemCode() {
    const code = redeemForm.code.trim();
    if (!code) {
      ElMessage.warning('请输入兑换码');
      return;
    }
    redeeming.value = true;
    try {
      const result = await paymentApi.redeemCode({ code });
      redeemForm.code = '';
      await auth.refreshCurrentUser();
      ElMessage.success(`兑换成功，已入账 ${formatNumber(result.redeemedBalance)} 余额`);
    } finally {
      redeeming.value = false;
    }
  }

  onMounted(load);
</script>

<template>
  <PageSection title="余额充值" subtitle="为当前登录账号充值余额，支付完成后由回调自动入账">
    <template #actions>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </template>

    <div v-loading="loading" class="payment">
      <!-- 统计指标 -->
      <section class="payment__summary">
        <article class="stat-card stat-card--balance">
          <div class="stat-card__icon-wrapper">
            <el-icon><Wallet /></el-icon>
          </div>
          <div class="stat-card__content">
            <span class="stat-card__label">当前账户余额</span>
            <strong class="stat-card__value mono">{{ formatNumber(currentBalance) }}<span class="stat-card__unit">元</span></strong>
          </div>
        </article>
        <article class="stat-card stat-card--pending">
          <div class="stat-card__icon-wrapper">
            <el-icon><Timer /></el-icon>
          </div>
          <div class="stat-card__content">
            <span class="stat-card__label">待支付订单数</span>
            <strong class="stat-card__value mono">{{ formatNumber(pendingOrders.length) }}<span class="stat-card__unit">笔</span></strong>
          </div>
        </article>
        <article class="stat-card stat-card--completed">
          <div class="stat-card__icon-wrapper">
            <el-icon><Calendar /></el-icon>
          </div>
          <div class="stat-card__content">
            <span class="stat-card__label">最近一次充值入账</span>
            <strong class="stat-card__value" style="font-size: 14px; font-weight: 600; padding: 4px 0;">
              {{ latestCompleted ? formatDateTime(latestCompleted.completedAt) : '无充值记录' }}
            </strong>
          </div>
        </article>
      </section>

      <!-- 主要内容 -->
      <section class="payment__grid">
        <!-- 创建订单面板 -->
        <div class="payment__panel">
          <div class="payment__panel-header">
            <h3 class="panel-header-title">创建充值订单</h3>
            <span class="custom-badge" :class="isPaymentEnabled ? 'completed' : 'cancelled'">
              {{ isPaymentEnabled ? '在线支付已开通' : '未开放' }}
            </span>
          </div>

          <el-alert
            v-if="!isPaymentEnabled"
            type="info"
            :closable="false"
            show-icon
            title="管理员尚未启用在线支付，目前无法进行余额充值。"
            style="margin-bottom: 16px;"
          />

          <el-form v-if="settings" label-position="top" class="payment__form">
            <el-form-item label="充值金额 (元)">
              <el-input-number
                v-model="form.amount"
                :min="settings.minAmount"
                :max="maxAmountLimit"
                :precision="2"
                :step="10"
                controls-position="right"
                style="width: 100%"
              />
              
              <!-- 快捷选额按钮组 -->
              <div class="quick-amounts">
                <button
                  v-for="amt in [10, 30, 50, 100, 300, 500]"
                  :key="amt"
                  type="button"
                  class="quick-amount-btn"
                  :class="{ active: form.amount === amt }"
                  :disabled="!isPaymentEnabled || amt < settings.minAmount || (settings.maxAmount > 0 && amt > settings.maxAmount)"
                  @click="form.amount = amt"
                >
                  ¥{{ amt }}
                </button>
              </div>
            </el-form-item>

            <el-form-item label="支付方式">
              <div class="payment-method-selector">
                <div 
                  v-for="type in availableTypes"
                  :key="type"
                  class="payment-method-card"
                  :class="[type, { active: form.paymentType === type }]"
                  @click="isPaymentEnabled && (form.paymentType = type)"
                >
                  <div class="payment-method-card__icon-brand">
                    <svg v-if="type === 'alipay'" viewBox="0 0 1024 1024" class="alipay-svg" fill="currentColor" style="width: 22px; height: 22px;">
                      <path d="M787 555c-7-59-40-118-98-174h148v-68H647c30-56 59-119 83-186l-68-21c-25 70-55 131-87 187-48-70-92-146-121-222l-65 20c31 78 78 158 129 230H302c-40 56-92 112-153 163l43 54c60-47 109-98 149-150h264c-36 58-80 116-130 168-51-38-96-86-133-140l-50 47c43 60 96 112 156 153-97 77-225 136-391 172l20 68c191-43 334-112 438-202 54 45 116 83 185 111l31-62c-64-26-121-62-172-103 46-47 84-98 116-150h141c5 7 10 15 14 24 15 30 22 62 22 95-1 102-54 162-158 179l20 67c139-24 206-104 205-244 0-38-8-75-22-110z"/>
                    </svg>
                    <svg v-else-if="type === 'wxpay'" viewBox="0 0 1024 1024" class="wxpay-svg" fill="currentColor" style="width: 22px; height: 22px;">
                      <path d="M647.4 340.6c11.9 0 23.7.9 35.3 2.6-32.9-77-109-131-198.8-131-131.7 0-238.4 92.5-238.4 206.7 0 63 64.9 123.6 161.7 165.7l-41.2 81.3 92.8-48.4c20.3 5.4 41.5 8.2 63.3 8.2 11.9 0 23.7-.9 35.3-2.6-9.6-34.9-14.8-71.8-14.8-110 0-95.2 62.9-172.5 140.2-172.5zm-149 71.9c-14.2 0-25.7-10.4-25.7-23.2s11.5-23.2 25.7-23.2c14.2 0 25.7 10.4 25.7 23.2s-11.5 23.2-25.7 23.2zm-128.4 0c-14.2 0-25.7-10.4-25.7-23.2s11.5-23.2 25.7-23.2c14.2 0 25.7 10.4 25.7 23.2s-11.5 23.2-25.7 23.2z"/>
                      <path d="M856.2 590.2c0-95.2-90.3-172.3-201.7-172.3-111.4 0-201.7 77.2-201.7 172.3s90.3 172.3 201.7 172.3c18.5 0 36.4-2.1 53.7-6.2l78.8 41.2-34.9-69.3c63.7-35.3 104.1-87 104.1-138zM600.8 567c-11.9 0-21.6-8.7-21.6-19.4s9.7-19.4 21.6-19.4 21.6 8.7 21.6 19.4-9.7 19.4-21.6 19.4zm108 0c-11.9 0-21.6-8.7-21.6-19.4s9.7-19.4 21.6-19.4c11.9 0 21.6 8.7 21.6 19.4s-9.7 19.4-21.6 19.4z"/>
                    </svg>
                  </div>
                  <div class="payment-method-card__info">
                    <span class="payment-method-card__title">{{ paymentTypeLabels[type] }}</span>
                    <span class="payment-method-card__desc">{{ type === 'wxpay' ? '微信扫码完成支付' : '跳转支付宝网页支付' }}</span>
                  </div>
                  <div class="payment-method-card__badge" v-if="form.paymentType === type">
                    <el-icon><Check /></el-icon>
                  </div>
                </div>
              </div>
            </el-form-item>

            <div class="payment__quote">
              <div class="quote-item quote-item--credited">
                <span class="quote-label">预计到账余额</span>
                <strong class="quote-val mono">+{{ formatNumber(creditedAmount) }}</strong>
              </div>
              <div class="quote-item quote-item--pay">
                <span class="quote-label">实际应付金额</span>
                <strong class="quote-val mono">¥{{ formatNumber(payAmount) }}</strong>
              </div>
              <div class="quote-item">
                <span class="quote-label">手续费率</span>
                <strong class="quote-val mono">{{ feeRateText }}</strong>
              </div>
            </div>

            <div class="payment__limits text-muted">
              <el-icon><Warning /></el-icon>
              <span>
                交易规则：单笔金额 {{ amountRangeText(settings) }}，每日限额 {{ limitText(settings.dailyLimit) }}。每个账号最多允许保留 {{ formatNumber(settings.maxPendingOrders) }} 个未支付订单。
              </span>
            </div>

            <el-button
              type="primary"
              :icon="Wallet"
              :loading="creating"
              :disabled="!isPaymentEnabled"
              class="submit-btn"
              @click="createOrder"
            >
              创建订单并支付
            </el-button>
          </el-form>
        </div>

        <!-- 兑换码充值面板 -->
        <div class="payment__panel">
          <div class="payment__panel-header">
            <h3 class="panel-header-title">兑换码充值</h3>
            <span class="custom-badge completed">即时入账</span>
          </div>

          <el-form label-position="top" class="payment__form">
            <el-form-item label="兑换码">
              <el-input
                v-model="redeemForm.code"
                placeholder="输入管理员发放的余额兑换码"
                clearable
                @keyup.enter="redeemCode"
              />
            </el-form-item>

            <div class="payment__limits text-muted">
              <el-icon><Warning /></el-icon>
              <span>兑换码为一次性使用，兑换成功后余额会直接加入当前账号。</span>
            </div>

            <el-button
              type="primary"
              :loading="redeeming"
              class="submit-btn"
              @click="redeemCode"
            >
              兑换余额
            </el-button>
          </el-form>
        </div>

        <!-- 当前未完成订单 -->
        <div class="payment__panel">
          <div class="payment__panel-header">
            <h3 class="panel-header-title">当前待支付订单</h3>
            <span v-if="activeOrder" class="custom-badge" :class="activeOrder.status.toLowerCase()">
              {{ statusLabel(activeOrder.status) }}
            </span>
          </div>

          <div v-if="activeOrder" class="payment__active">
            <div class="active-info-list">
              <div class="payment__active-row">
                <span class="text-muted">订单号</span>
                <code class="mono-code">{{ activeOrder.outTradeNo }}</code>
              </div>
              <div class="payment__active-row">
                <span class="text-muted">应付金额</span>
                <strong class="active-amount">¥{{ formatNumber(activeOrder.payAmount) }}</strong>
              </div>
              <div class="payment__active-row">
                <span class="text-muted">过期自动取消</span>
                <span class="time-text">{{ formatDateTime(activeOrder.expiresAt) }}</span>
              </div>
            </div>

            <!-- 二维码显示 -->
            <div class="qr-container" v-if="isQrCodeImage(activeOrder.qrCode)">
              <img
                class="payment__qr"
                :src="activeOrder.qrCode || ''"
                alt="支付二维码"
              />
              <span class="qr-tip">请使用微信或支付宝扫码支付</span>
            </div>
            <div class="qr-container" v-else-if="activeOrder.qrCode">
              <code class="payment__qr-text">{{ activeOrder.qrCode }}</code>
              <span class="qr-tip">商户支付请求链接，请点击下方按钮支付</span>
            </div>

            <div class="payment__actions">
              <el-button type="primary" :disabled="!activeOrder.payUrl" class="action-pay-btn" @click="openPay(activeOrder)">
                立即打开支付
              </el-button>
              <el-button
                v-if="activeOrder.status === 'PENDING'"
                type="info"
                plain
                class="action-cancel-btn"
                @click="cancelOrder(activeOrder)"
              >
                取消充值订单
              </el-button>
            </div>
          </div>
          <el-empty v-else description="暂无待支付的充值订单" :image-size="120" />
        </div>
      </section>

      <!-- 充值记录 -->
      <section class="payment__orders">
        <h3 class="panel-header-title">充值历史记录</h3>
        <el-table :data="orders" stripe style="width: 100%">
          <el-table-column label="订单号" min-width="190">
            <template #default="{ row }">
              <span class="mono-code">{{ row.outTradeNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="支付方式" width="120">
            <template #default="{ row }">
              <div class="payment-type-badge" :class="row.paymentType">
                <span class="dot"></span>
                {{ paymentTypeLabel(row.paymentType) }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="充值余额" width="110" align="right">
            <template #default="{ row }">
              <span class="amount-val amount-val--credited">+{{ formatNumber(row.amount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="实付金额" width="110" align="right">
            <template #default="{ row }">
              <span class="amount-val amount-val--pay">¥{{ formatNumber(row.payAmount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="订单状态" width="110" align="center">
            <template #default="{ row }">
              <span class="custom-badge" :class="row.status.toLowerCase()">
                {{ statusLabel(row.status) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">
              <span class="time-text">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="入账时间" width="170">
            <template #default="{ row }">
              <span v-if="row.completedAt" class="time-text">{{ formatDateTime(row.completedAt) }}</span>
              <span v-else class="text-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right" align="center">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'PENDING'"
                link
                type="primary"
                :disabled="!row.payUrl"
                @click="openPay(row)"
              >
                支付
              </el-button>
              <el-button
                v-if="row.status === 'PENDING'"
                link
                type="warning"
                @click="cancelOrder(row)"
              >
                取消
              </el-button>
              <span v-else class="text-muted">—</span>
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
    gap: 24px;

    &__summary {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 16px;
    }

    &__grid {
      display: grid;
      grid-template-columns: minmax(320px, 0.95fr) minmax(320px, 1.05fr);
      gap: 20px;
      align-items: start;
    }

    &__panel,
    &__orders {
      border: 1px solid var(--kp-divider);
      background: rgba(255, 255, 255, 0.45);
      backdrop-filter: blur(var(--kp-blur)) saturate(160%);
      -webkit-backdrop-filter: blur(var(--kp-blur)) saturate(160%);
      border-radius: var(--kp-radius);
      box-shadow: var(--kp-shadow);
      padding: 24px;
      transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);

      &:hover {
        box-shadow: var(--kp-shadow-lg);
        border-color: rgba(91, 141, 239, 0.25);
      }
    }

    &__panel-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 20px;
      border-bottom: 1px solid var(--kp-divider);
      padding-bottom: 12px;

      .panel-header-title {
        font-size: 16px;
        font-weight: 600;
        color: var(--kp-text);
        margin: 0;
      }
    }

    &__form {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    &__quote {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 12px;
      margin: 12px 0;

      .quote-item {
        border: 1px solid var(--kp-divider);
        border-radius: 12px;
        padding: 14px 10px;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 6px;
        background: rgba(255, 255, 255, 0.3);
        transition: all 0.2s ease;

        &:hover {
          background: rgba(255, 255, 255, 0.75);
          border-color: var(--kp-primary-soft);
        }

        &--credited {
          border-color: rgba(54, 192, 138, 0.25);
          background: rgba(54, 192, 138, 0.02);
          .quote-val { color: var(--kp-success); }
        }

        &--pay {
          border-color: rgba(91, 141, 239, 0.25);
          background: rgba(91, 141, 239, 0.02);
          .quote-val { color: var(--kp-primary); }
        }
      }

      .quote-label {
        color: var(--kp-text-secondary);
        font-size: 11px;
        font-weight: 500;
        text-align: center;
      }

      .quote-val {
        font-size: 16px;
        font-weight: 700;
      }
    }

    &__limits {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      font-size: 12px;
      line-height: 1.5;
      padding: 12px 14px;
      background: rgba(138, 147, 168, 0.06);
      border-radius: 10px;
      border: 1px solid var(--kp-divider);
      margin-bottom: 8px;

      .el-icon {
        font-size: 16px;
        color: var(--kp-text-secondary);
        margin-top: 1px;
        flex-shrink: 0;
      }
    }

    .submit-btn {
      width: 100%;
      height: 40px;
      font-size: 14px;
      font-weight: 600;
      border-radius: 10px;
      margin-top: 8px;
    }

    &__active {
      display: flex;
      flex-direction: column;
      gap: 20px;
    }

    .active-info-list {
      display: flex;
      flex-direction: column;
      gap: 12px;
      border: 1px solid var(--kp-divider);
      border-radius: 12px;
      padding: 16px;
      background: rgba(255, 255, 255, 0.4);
    }

    &__active-row {
      display: flex;
      justify-content: space-between;
      gap: 16px;
      align-items: center;

      .active-amount {
        font-size: 18px;
        color: var(--kp-primary);
        font-weight: 700;
        font-family: ui-monospace, 'JetBrains Mono', 'SF Mono', Consolas, monospace;
      }
    }

    .qr-container {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 10px;
      padding: 20px;
      border-radius: 12px;
      border: 1px dashed var(--kp-divider);
      background: rgba(255, 255, 255, 0.65);
      align-self: center;
      width: 100%;
      max-width: 280px;
      transition: all 0.3s ease;

      &:hover {
        background: #fff;
        box-shadow: var(--kp-shadow);
      }
    }

    &__qr {
      width: 160px;
      height: 160px;
      object-fit: contain;
      border: 4px solid #fff;
      border-radius: 8px;
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
    }

    .qr-tip {
      font-size: 11px;
      color: var(--kp-text-secondary);
      font-weight: 500;
      text-align: center;
    }

    &__qr-text {
      display: block;
      word-break: break-all;
      border: 1px solid var(--kp-divider);
      border-radius: 8px;
      padding: 10px;
      background: rgba(255, 255, 255, 0.9);
      font-family: ui-monospace, 'JetBrains Mono', 'SF Mono', Consolas, monospace;
      font-size: 11px;
      width: 100%;
      text-align: center;
    }

    &__actions {
      display: flex;
      gap: 12px;
      width: 100%;

      .action-pay-btn {
        flex: 1;
        height: 38px;
        font-weight: 600;
      }

      .action-cancel-btn {
        height: 38px;
      }
    }

    &__orders {
      h3 {
        font-size: 16px;
        font-weight: 600;
        margin: 0 0 16px;
        color: var(--kp-text);
      }
    }
  }

  /* 统计卡片设计 */
  .stat-card {
    display: flex;
    align-items: center;
    gap: 16px;
    border: 1px solid var(--kp-divider);
    background: rgba(255, 255, 255, 0.5);
    backdrop-filter: blur(var(--kp-blur)) saturate(160%);
    -webkit-backdrop-filter: blur(var(--kp-blur)) saturate(160%);
    border-radius: 16px;
    padding: 20px;
    box-shadow: var(--kp-shadow);
    transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
    position: relative;
    overflow: hidden;

    &::before {
      content: '';
      position: absolute;
      top: 0;
      left: 0;
      width: 4px;
      height: 100%;
    }

    &:hover {
      transform: translateY(-4px);
      box-shadow: var(--kp-shadow-lg);
    }

    &__icon-wrapper {
      width: 48px;
      height: 48px;
      border-radius: 12px;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 24px;
    }

    &__content {
      display: flex;
      flex-direction: column;
      gap: 4px;
      flex: 1;
    }

    &__label {
      font-size: 12px;
      color: var(--kp-text-secondary);
      font-weight: 500;
    }

    &__value {
      font-size: 24px;
      font-weight: 700;
      color: var(--kp-text);
      line-height: 1.1;
      display: flex;
      align-items: baseline;
      gap: 2px;
    }

    &__unit {
      font-size: 12px;
      font-weight: 400;
      color: var(--kp-text-muted);
      margin-left: 2px;
    }

    /* 各卡片特有颜色方案 */
    &--completed {
      &::before { background: var(--kp-success); }
      &:hover { border-color: rgba(54, 192, 138, 0.3); }
      .stat-card__icon-wrapper {
        background: rgba(54, 192, 138, 0.12);
        color: var(--kp-success);
      }
    }

    &--pending {
      &::before { background: var(--kp-warning); }
      &:hover { border-color: rgba(240, 168, 77, 0.3); }
      .stat-card__icon-wrapper {
        background: rgba(240, 168, 77, 0.12);
        color: var(--kp-warning);
      }
    }

    &--balance {
      &::before { background: var(--kp-primary); }
      &:hover { border-color: rgba(91, 141, 239, 0.3); }
      .stat-card__icon-wrapper {
        background: rgba(91, 141, 239, 0.12);
        color: var(--kp-primary);
      }
    }
  }

  /* 快捷选额按钮组 */
  .quick-amounts {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 10px;
    width: 100%;

    .quick-amount-btn {
      flex: 1;
      min-width: 65px;
      border: 1px solid var(--kp-divider);
      background: rgba(255, 255, 255, 0.6);
      border-radius: 8px;
      padding: 8px 4px;
      font-size: 13px;
      font-weight: 600;
      color: var(--kp-text-secondary);
      cursor: pointer;
      transition: all 0.2s ease;
      outline: none;

      &:hover:not(:disabled) {
        background: rgba(255, 255, 255, 0.9);
        border-color: var(--kp-primary);
        color: var(--kp-primary);
      }

      &.active {
        border-color: var(--kp-primary);
        background: var(--kp-primary-soft);
        color: var(--kp-primary);
        box-shadow: 0 0 0 1px var(--kp-primary);
      }

      &:disabled {
        opacity: 0.45;
        cursor: not-allowed;
      }
    }
  }

  /* 支付方式定制卡片选择器 */
  .payment-method-selector {
    display: flex;
    flex-direction: column;
    gap: 12px;
    width: 100%;
    margin-top: 4px;
  }

  .payment-method-card {
    display: flex;
    align-items: center;
    gap: 14px;
    border: 1px solid var(--kp-divider);
    border-radius: 12px;
    padding: 14px 16px;
    background: rgba(255, 255, 255, 0.4);
    cursor: pointer;
    position: relative;
    transition: all 0.25s ease;
    user-select: none;

    &:hover {
      background: rgba(255, 255, 255, 0.8);
      border-color: var(--kp-text-muted);
      transform: translateY(-1px);
    }

    &__icon-brand {
      width: 40px;
      height: 40px;
      border-radius: 10px;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 20px;
      transition: all 0.25s ease;
    }

    &__info {
      display: flex;
      flex-direction: column;
      gap: 2px;
      flex: 1;
      min-width: 0;
    }

    &__title {
      font-size: 14px;
      font-weight: 600;
      color: var(--kp-text);
    }

    &__desc {
      font-size: 11px;
      color: var(--kp-text-secondary);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    &__badge {
      position: absolute;
      top: -6px;
      right: -6px;
      width: 18px;
      height: 18px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      color: #fff;
      font-size: 10px;
      box-shadow: 0 2px 4px rgba(0, 0, 0, 0.15);
      animation: scaleIn 0.2s cubic-bezier(0.34, 1.56, 0.64, 1);
    }

    /* 支付宝选中状态 */
    &.alipay {
      .payment-method-card__icon-brand {
        background: rgba(22, 119, 255, 0.08);
        color: #1677ff;
      }
      &.active {
        border-color: #1677ff;
        background: rgba(22, 119, 255, 0.05);
        box-shadow: 0 0 0 1px #1677ff, var(--kp-shadow);
        .payment-method-card__icon-brand {
          background: #1677ff;
          color: #fff;
        }
        .payment-method-card__badge {
          background: #1677ff;
        }
      }
    }

    /* 微信支付选中状态 */
    &.wxpay {
      .payment-method-card__icon-brand {
        background: rgba(7, 193, 96, 0.08);
        color: #07c160;
      }
      &.active {
        border-color: #07c160;
        background: rgba(7, 193, 96, 0.05);
        box-shadow: 0 0 0 1px #07c160, var(--kp-shadow);
        .payment-method-card__icon-brand {
          background: #07c160;
          color: #fff;
        }
        .payment-method-card__badge {
          background: #07c160;
        }
      }
    }
  }

  /* 支付方式徽章 */
  .payment-type-badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    font-weight: 600;
    padding: 2px 10px;
    border-radius: 99px;
    background: rgba(0, 0, 0, 0.04);
    color: var(--kp-text-secondary);

    .dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: currentColor;
    }

    &.alipay {
      background: rgba(22, 119, 255, 0.08);
      color: #1677ff;
    }

    &.wxpay {
      background: rgba(7, 193, 96, 0.08);
      color: #07c160;
    }
  }

  /* 额度/数值样式 */
  .amount-val {
    font-family: ui-monospace, 'JetBrains Mono', 'SF Mono', Consolas, monospace;
    font-weight: 600;
    font-size: 14px;

    &--credited {
      color: var(--kp-success);
    }

    &--pay {
      color: var(--kp-text);
    }
  }

  /* 状态徽章设计 */
  .custom-badge {
    display: inline-block;
    padding: 3px 10px;
    border-radius: 99px;
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 0.3px;

    &.completed {
      background: rgba(54, 192, 138, 0.12);
      color: var(--kp-success);
    }

    &.pending, &.paid {
      background: rgba(240, 168, 77, 0.12);
      color: var(--kp-warning);
    }

    &.failed, &.expired {
      background: rgba(239, 91, 107, 0.12);
      color: var(--kp-danger);
    }

    &.cancelled {
      background: rgba(138, 147, 168, 0.12);
      color: var(--kp-text-muted);
    }
  }

  .mono-code {
    font-family: ui-monospace, 'JetBrains Mono', 'SF Mono', Consolas, monospace;
    font-size: 12px;
    color: var(--kp-text-secondary);
    background: rgba(15, 23, 42, 0.04);
    padding: 2px 6px;
    border-radius: 4px;
  }

  .time-text {
    font-size: 12px;
    color: var(--kp-text-secondary);
  }

  @keyframes scaleIn {
    from {
      transform: scale(0);
      opacity: 0;
    }
    to {
      transform: scale(1);
      opacity: 1;
    }
  }

  @media (max-width: 960px) {
    .payment {
      &__summary {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
      &__grid {
        grid-template-columns: 1fr;
      }
      &__quote {
        grid-template-columns: 1fr;
      }
    }
  }

  @media (max-width: 550px) {
    .payment {
      &__summary {
        grid-template-columns: 1fr;
      }
      .quick-amounts {
        grid-template-columns: repeat(3, minmax(0, 1fr));
        display: grid;
      }
    }
  }
</style>
