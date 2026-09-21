<script setup lang="ts">
  /**
   * 管理员支付设置。
   *
   * 配置项参考 sub2api 的支付设置，实际落库由后端 payment settings 服务负责。
   */
  import { computed, onMounted, reactive, ref } from 'vue';
  import { ElMessage } from 'element-plus';
  import { Refresh, Check, Setting, Connection, CreditCard } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import MetricsPanel, { type MetricPanelItem } from '@/components/MetricsPanel.vue';
  import { adminPaymentApi } from '@/api';
  import type {
    InviteRebateRecord,
    PaymentOrder,
    PaymentSettings,
    PaymentType,
    UpdatePaymentSettingsRequest,
  } from '@/types/api';
  import { formatDateTime, formatNumber } from '@/utils/format';

  const loading = ref(false);
  const submitting = ref(false);
  const orders = ref<PaymentOrder[]>([]);
  const rebates = ref<InviteRebateRecord[]>([]);
  const pkeyConfigured = ref(false);

  type PaymentSettingsForm = Required<
    Omit<UpdatePaymentSettingsRequest, 'inviteRebateStartAt' | 'inviteRebateEndAt'>
  > & {
    inviteRebateStartAt: Date | null;
    inviteRebateEndAt: Date | null;
  };

  const form = reactive<PaymentSettingsForm>({
    enabled: false,
    minAmount: 1,
    maxAmount: 1000,
    dailyLimit: 1000,
    orderTimeoutMinutes: 30,
    maxPendingOrders: 3,
    enabledTypes: ['alipay', 'wxpay'],
    balanceRechargeMultiplier: 1,
    apiBillingMultiplier: 1,
    rechargeFeeRate: 0,
    productNamePrefix: 'Starry API',
    productNameSuffix: '余额充值',
    helpImageUrl: '',
    helpText: '',
    easypayApiBase: '',
    easypayPid: '',
    easypayPkey: '',
    easypayPaymentMode: 'popup',
    inviteRebateEnabled: false,
    inviteRebateRate: 0.1,
    inviteRebateMaxOrders: 10,
    inviteRebateStartAt: null,
    inviteRebateEndAt: null,
  });

  function togglePaymentType(type: PaymentType) {
    const index = form.enabledTypes.indexOf(type);
    if (index > -1) {
      if (form.enabledTypes.length === 1) {
        ElMessage.warning('至少启用一种支付方式');
        return;
      }
      form.enabledTypes.splice(index, 1);
    } else {
      form.enabledTypes.push(type);
    }
  }

  const orderStats = computed(() => {
    const completed = orders.value.filter((order) => order.status === 'COMPLETED');
    const pending = orders.value.filter(
      (order) => order.status === 'PENDING' || order.status === 'PAID',
    );
    return {
      completed: completed.length,
      pending: pending.length,
      creditedAmount: completed.reduce((sum, order) => sum + order.amount, 0),
      actualIncome: completed.reduce((sum, order) => sum + order.payAmount, 0),
    };
  });

  const inviteRebateRatePercent = computed({
    get: () => Number(((form.inviteRebateRate || 0) * 100).toFixed(2)),
    set: (value: number) => {
      form.inviteRebateRate = Number((value / 100).toFixed(6));
    },
  });

  const inviteRebateStartDate = computed<Date | undefined>({
    get: () => form.inviteRebateStartAt ?? undefined,
    set: (value) => {
      form.inviteRebateStartAt = value ?? null;
    },
  });

  const inviteRebateEndDate = computed<Date | undefined>({
    get: () => form.inviteRebateEndAt ?? undefined,
    set: (value) => {
      form.inviteRebateEndAt = value ?? null;
    },
  });

  const rebateStats = computed(() => ({
    count: rebates.value.length,
    total: rebates.value.reduce((sum, record) => sum + record.rebateAmount, 0),
  }));

  const orderMetrics = computed<MetricPanelItem[]>(() => [
    {
      label: '已入账订单',
      value: orderStats.value.completed,
      unit: '笔',
      tone: 'success',
      valueTone: 'success',
    },
    {
      label: '待处理订单',
      value: orderStats.value.pending,
      unit: '笔',
      tone: 'warning',
      valueTone: 'warning',
    },
    { label: '实际收入', value: orderStats.value.actualIncome, unit: '元', tone: 'primary' },
    { label: '发放余额', value: orderStats.value.creditedAmount, unit: '元', tone: 'accent' },
  ]);

  function applySettings(settings: PaymentSettings) {
    form.enabled = settings.enabled;
    form.minAmount = settings.minAmount;
    form.maxAmount = settings.maxAmount;
    form.dailyLimit = settings.dailyLimit;
    form.orderTimeoutMinutes = settings.orderTimeoutMinutes;
    form.maxPendingOrders = settings.maxPendingOrders;
    form.enabledTypes = settings.enabledTypes.length ? settings.enabledTypes : ['alipay', 'wxpay'];
    form.balanceRechargeMultiplier = settings.balanceRechargeMultiplier;
    form.apiBillingMultiplier = settings.apiBillingMultiplier ?? 1;
    form.rechargeFeeRate = settings.rechargeFeeRate;
    form.productNamePrefix = settings.productNamePrefix ?? '';
    form.productNameSuffix = settings.productNameSuffix ?? '';
    form.helpImageUrl = settings.helpImageUrl ?? '';
    form.helpText = settings.helpText ?? '';
    form.easypayApiBase = settings.easypayApiBase ?? '';
    form.easypayPid = settings.easypayPid ?? '';
    form.easypayPkey = '';
    form.easypayPaymentMode = settings.easypayPaymentMode === 'qrcode' ? 'qrcode' : 'popup';
    form.inviteRebateEnabled = Boolean(settings.inviteRebateEnabled);
    form.inviteRebateRate = settings.inviteRebateRate ?? 0.1;
    form.inviteRebateMaxOrders = settings.inviteRebateMaxOrders ?? 10;
    form.inviteRebateStartAt = parseDate(settings.inviteRebateStartAt);
    form.inviteRebateEndAt = parseDate(settings.inviteRebateEndAt);
    pkeyConfigured.value = Boolean(settings.easypayPkeyConfigured);
  }

  async function load() {
    loading.value = true;
    try {
      const [settings, nextOrders, nextRebates] = await Promise.all([
        adminPaymentApi.settings(),
        adminPaymentApi.orders(),
        adminPaymentApi.inviteRebates(),
      ]);
      applySettings(settings);
      orders.value = nextOrders;
      rebates.value = nextRebates;
    } finally {
      loading.value = false;
    }
  }

  async function submit() {
    if (form.minAmount <= 0 || (form.maxAmount > 0 && form.maxAmount < form.minAmount)) {
      ElMessage.warning('请检查单笔充值金额范围');
      return;
    }
    if (form.enabledTypes.length === 0) {
      ElMessage.warning('至少启用一种支付方式');
      return;
    }
    if (form.apiBillingMultiplier <= 0) {
      ElMessage.warning('模型计费倍率必须大于 0');
      return;
    }
    if (form.inviteRebateRate < 0.1 || form.inviteRebateRate > 0.2) {
      ElMessage.warning('邀请返利比例必须在 10% 到 20% 之间');
      return;
    }
    if (form.inviteRebateMaxOrders < 1) {
      ElMessage.warning('邀请返利订单数必须大于 0');
      return;
    }
    if (
      form.inviteRebateStartAt &&
      form.inviteRebateEndAt &&
      form.inviteRebateStartAt >= form.inviteRebateEndAt
    ) {
      ElMessage.warning('返利活动开始时间必须早于结束时间');
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
        apiBillingMultiplier: form.apiBillingMultiplier,
        rechargeFeeRate: form.rechargeFeeRate,
        productNamePrefix: form.productNamePrefix,
        productNameSuffix: form.productNameSuffix,
        helpImageUrl: form.helpImageUrl,
        helpText: form.helpText,
        easypayApiBase: form.easypayApiBase,
        easypayPid: form.easypayPid,
        easypayPaymentMode: form.easypayPaymentMode,
        inviteRebateEnabled: form.inviteRebateEnabled,
        inviteRebateRate: form.inviteRebateRate,
        inviteRebateMaxOrders: form.inviteRebateMaxOrders,
        inviteRebateStartAt: isoDate(form.inviteRebateStartAt),
        inviteRebateEndAt: isoDate(form.inviteRebateEndAt),
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

  function paymentTypeLabel(type: PaymentType) {
    return type === 'wxpay' ? '微信支付' : '支付宝';
  }

  function parseDate(value?: string | null) {
    return value ? new Date(value) : null;
  }

  function isoDate(value: Date | null) {
    return value ? value.toISOString() : null;
  }

  function getUserInitials(name?: string) {
    if (!name) return '?';
    return name.trim().charAt(0).toUpperCase();
  }

  function getUserColor(name?: string) {
    if (!name) return '#a8abb2';
    const colors = [
      '#5b8def',
      '#b387ff',
      '#36c08a',
      '#f0a84d',
      '#ef5b6b',
      '#2ea9df',
      '#f596aa',
      '#fc9d9a',
    ];
    let hash = 0;
    for (let i = 0; i < name.length; i++) {
      hash = name.charCodeAt(i) + ((hash << 5) - hash);
    }
    const index = Math.abs(hash) % colors.length;
    return colors[index];
  }

  onMounted(load);
</script>

<template>
  <PageSection title="支付设置" subtitle="业务配置" :icon="CreditCard">
    <template #actions>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存设置</el-button>
    </template>
    <template #summary>
      <MetricsPanel :items="orderMetrics" />
    </template>

    <div v-loading="loading" class="payment-admin">

      <!-- 配置区域 -->
      <section class="payment-admin__grid">
        <!-- 基础配置 -->
        <div class="payment-admin__panel">
          <div class="payment-admin__panel-header">
            <el-icon class="panel-header-icon"><Setting /></el-icon>
            <span class="panel-header-title">基础交易配置</span>
          </div>

          <el-form label-position="top">
            <div class="payment-admin__row payment-admin__row--switch">
              <div class="switch-info">
                <span class="switch-title">启用在线充值</span>
                <p class="switch-desc text-muted">
                  关闭后用户无法创建新的支付订单，历史订单仍可查看。
                </p>
              </div>
              <el-switch v-model="form.enabled" />
            </div>

            <el-form-item label="支付方式">
              <div class="payment-method-selector">
                <div
                  class="payment-method-card alipay"
                  :class="{ active: form.enabledTypes.includes('alipay') }"
                  @click="togglePaymentType('alipay')"
                >
                  <div class="payment-method-card__icon-brand">
                    <svg
                      viewBox="0 0 1024 1024"
                      class="alipay-svg"
                      fill="currentColor"
                      style="width: 22px; height: 22px"
                    >
                      <path
                        d="M787 555c-7-59-40-118-98-174h148v-68H647c30-56 59-119 83-186l-68-21c-25 70-55 131-87 187-48-70-92-146-121-222l-65 20c31 78 78 158 129 230H302c-40 56-92 112-153 163l43 54c60-47 109-98 149-150h264c-36 58-80 116-130 168-51-38-96-86-133-140l-50 47c43 60 96 112 156 153-97 77-225 136-391 172l20 68c191-43 334-112 438-202 54 45 116 83 185 111l31-62c-64-26-121-62-172-103 46-47 84-98 116-150h141c5 7 10 15 14 24 15 30 22 62 22 95-1 102-54 162-158 179l20 67c139-24 206-104 205-244 0-38-8-75-22-110z"
                      />
                    </svg>
                  </div>
                  <div class="payment-method-card__info">
                    <span class="payment-method-card__title">支付宝 (Alipay)</span>
                    <span class="payment-method-card__desc">使用支付宝快捷支付接口</span>
                  </div>
                  <div
                    v-if="form.enabledTypes.includes('alipay')"
                    class="payment-method-card__badge"
                  >
                    <el-icon><Check /></el-icon>
                  </div>
                </div>

                <div
                  class="payment-method-card wxpay"
                  :class="{ active: form.enabledTypes.includes('wxpay') }"
                  @click="togglePaymentType('wxpay')"
                >
                  <div class="payment-method-card__icon-brand">
                    <svg
                      viewBox="0 0 1024 1024"
                      class="wxpay-svg"
                      fill="currentColor"
                      style="width: 22px; height: 22px"
                    >
                      <path
                        d="M647.4 340.6c11.9 0 23.7.9 35.3 2.6-32.9-77-109-131-198.8-131-131.7 0-238.4 92.5-238.4 206.7 0 63 64.9 123.6 161.7 165.7l-41.2 81.3 92.8-48.4c20.3 5.4 41.5 8.2 63.3 8.2 11.9 0 23.7-.9 35.3-2.6-9.6-34.9-14.8-71.8-14.8-110 0-95.2 62.9-172.5 140.2-172.5zm-149 71.9c-14.2 0-25.7-10.4-25.7-23.2s11.5-23.2 25.7-23.2c14.2 0 25.7 10.4 25.7 23.2s-11.5 23.2-25.7 23.2zm-128.4 0c-14.2 0-25.7-10.4-25.7-23.2s11.5-23.2 25.7-23.2c14.2 0 25.7 10.4 25.7 23.2s-11.5 23.2-25.7 23.2z"
                      />
                      <path
                        d="M856.2 590.2c0-95.2-90.3-172.3-201.7-172.3-111.4 0-201.7 77.2-201.7 172.3s90.3 172.3 201.7 172.3c18.5 0 36.4-2.1 53.7-6.2l78.8 41.2-34.9-69.3c63.7-35.3 104.1-87 104.1-138zM600.8 567c-11.9 0-21.6-8.7-21.6-19.4s9.7-19.4 21.6-19.4 21.6 8.7 21.6 19.4-9.7 19.4-21.6 19.4zm108 0c-11.9 0-21.6-8.7-21.6-19.4s9.7-19.4 21.6-19.4c11.9 0 21.6 8.7 21.6 19.4s-9.7 19.4-21.6 19.4z"
                      />
                    </svg>
                  </div>
                  <div class="payment-method-card__info">
                    <span class="payment-method-card__title">微信支付 (WeChat Pay)</span>
                    <span class="payment-method-card__desc">使用微信扫码/内置公众号支付</span>
                  </div>
                  <div
                    v-if="form.enabledTypes.includes('wxpay')"
                    class="payment-method-card__badge"
                  >
                    <el-icon><Check /></el-icon>
                  </div>
                </div>
              </div>
            </el-form-item>

            <div class="payment-admin__form-grid">
              <el-form-item label="单笔最小金额 (元)">
                <el-input-number
                  v-model="form.minAmount"
                  :min="0.01"
                  :precision="2"
                  controls-position="right"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="单笔最大金额 (元, 0为不限)">
                <el-input-number
                  v-model="form.maxAmount"
                  :min="0"
                  :precision="2"
                  controls-position="right"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="每日最高交易总额 (元)">
                <el-input-number
                  v-model="form.dailyLimit"
                  :min="0"
                  :precision="2"
                  controls-position="right"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="最大待支付订单数 (个)">
                <el-input-number
                  v-model="form.maxPendingOrders"
                  :min="1"
                  :max="50"
                  controls-position="right"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="订单超时自动取消 (分钟)">
                <el-input-number
                  v-model="form.orderTimeoutMinutes"
                  :min="1"
                  :max="1440"
                  controls-position="right"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="余额到账倍率 (1元兑换多少余额)">
                <el-input-number
                  v-model="form.balanceRechargeMultiplier"
                  :min="0.000001"
                  :precision="6"
                  controls-position="right"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="模型官方价格计费倍率">
                <el-input-number
                  v-model="form.apiBillingMultiplier"
                  :min="0.0001"
                  :precision="4"
                  :step="0.1"
                  controls-position="right"
                  style="width: 100%"
                />
                <p class="field-hint text-muted">
                  1.0 按模型官方美元成本计费；2.0 为官方成本的 2 倍。
                </p>
              </el-form-item>
              <el-form-item label="充值手续费率 (例如 0.006)">
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

        <!-- EasyPay 配置 -->
        <div class="payment-admin__panel">
          <div class="payment-admin__panel-header">
            <el-icon class="panel-header-icon"><Connection /></el-icon>
            <span class="panel-header-title">EasyPay 接口参数</span>
          </div>

          <el-form label-position="top">
            <el-form-item label="接口网关地址 (ApiBase)">
              <el-input v-model="form.easypayApiBase" placeholder="https://pay.example.com/" />
            </el-form-item>
            <el-form-item label="商户 ID (PID)">
              <el-input v-model="form.easypayPid" placeholder="请输入商户 PID" />
            </el-form-item>
            <el-form-item label="商户通讯密钥 (Pkey)">
              <el-input
                v-model="form.easypayPkey"
                type="password"
                show-password
                :placeholder="pkeyConfigured ? '已配置，留空保持不变' : '请输入商户通讯密钥'"
                autocomplete="new-password"
              />
            </el-form-item>
            <el-form-item label="收银台支付模式">
              <el-radio-group v-model="form.easypayPaymentMode" class="payment-mode-radios">
                <el-radio-button label="popup" value="popup">跳转官方收银台</el-radio-button>
                <el-radio-button label="qrcode" value="qrcode">直接输出二维码</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <div class="payment-admin__form-grid">
              <el-form-item label="商品名称前缀">
                <el-input v-model="form.productNamePrefix" placeholder="例如: Starry API" />
              </el-form-item>
              <el-form-item label="商品名称后缀">
                <el-input v-model="form.productNameSuffix" placeholder="例如: 余额充值" />
              </el-form-item>
            </div>
            <el-form-item label="充值指南图片 (帮助图片 URL)">
              <el-input v-model="form.helpImageUrl" placeholder="https://example.com/help.png" />
            </el-form-item>
            <el-form-item label="充值帮助说明 (Markdown 或纯文本)">
              <el-input
                v-model="form.helpText"
                type="textarea"
                :rows="3"
                placeholder="请输入在充值界面展示的提示信息"
              />
            </el-form-item>
          </el-form>
        </div>

        <!-- 邀请返利配置 -->
        <div class="payment-admin__panel payment-admin__panel--wide">
          <div class="payment-admin__panel-header">
            <el-icon class="panel-header-icon"><Setting /></el-icon>
            <span class="panel-header-title">邀请返利活动</span>
          </div>

          <el-form label-position="top">
            <div class="payment-admin__row payment-admin__row--switch">
              <div class="switch-info">
                <span class="switch-title">启用充值返利</span>
                <p class="switch-desc text-muted">
                  用户通过 register?aff=推广码 注册后，前 N 笔成功充值按到账余额返还给邀请人。
                </p>
              </div>
              <el-switch v-model="form.inviteRebateEnabled" />
            </div>

            <div class="payment-admin__form-grid">
              <el-form-item label="返利比例 (%)">
                <el-input-number
                  v-model="inviteRebateRatePercent"
                  :min="10"
                  :max="20"
                  :precision="2"
                  :step="1"
                  controls-position="right"
                  style="width: 100%"
                />
                <p class="field-hint text-muted">默认 10%，可在 10% 到 20% 之间调整。</p>
              </el-form-item>
              <el-form-item label="每个被邀请用户返利订单数">
                <el-input-number
                  v-model="form.inviteRebateMaxOrders"
                  :min="1"
                  :max="100"
                  controls-position="right"
                  style="width: 100%"
                />
                <p class="field-hint text-muted">默认前 10 笔成功充值参与返利。</p>
              </el-form-item>
              <el-form-item label="活动开始时间">
                <el-date-picker
                  v-model="inviteRebateStartDate"
                  type="datetime"
                  placeholder="不限制开始时间"
                  clearable
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="活动结束时间">
                <el-date-picker
                  v-model="inviteRebateEndDate"
                  type="datetime"
                  placeholder="不限制结束时间"
                  clearable
                  style="width: 100%"
                />
              </el-form-item>
            </div>

            <p class="field-hint text-muted">
              当前已记录 {{ formatNumber(rebateStats.count) }} 笔返利，共发放
              {{ formatNumber(rebateStats.total) }} 余额。返利只会在订单首次真实入账时执行一次。
            </p>
          </el-form>
        </div>
      </section>

      <!-- 返利记录 -->
      <section class="payment-admin__orders">
        <div class="payment-admin__panel-header">
          <el-icon class="panel-header-icon"><Setting /></el-icon>
          <span class="panel-header-title">近期邀请返利</span>
        </div>
        <el-table :data="rebates" stripe style="width: 100%" empty-text="暂无返利记录">
          <el-table-column label="推广码" width="120">
            <template #default="{ row }">
              <span class="mono-code">{{ row.affiliateCode }}</span>
            </template>
          </el-table-column>
          <el-table-column label="邀请人" min-width="170">
            <template #default="{ row }">
              <span class="mono-code">{{ row.inviterUserId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="被邀请用户" min-width="170">
            <template #default="{ row }">
              <span class="mono-code">{{ row.invitedUserId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="充值余额" width="120" align="right">
            <template #default="{ row }">
              {{ formatNumber(row.baseAmount) }}
            </template>
          </el-table-column>
          <el-table-column label="比例" width="90" align="right">
            <template #default="{ row }"> {{ formatNumber(row.rebateRate * 100) }}% </template>
          </el-table-column>
          <el-table-column label="返利余额" width="120" align="right">
            <template #default="{ row }">
              <span class="amount-val amount-val--credited">
                +{{ formatNumber(row.rebateAmount) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="第几笔" width="90" align="center">
            <template #default="{ row }">第 {{ row.rebateOrderNo }} 笔</template>
          </el-table-column>
          <el-table-column label="返利时间" width="170">
            <template #default="{ row }">
              <span class="time-text">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <!-- 订单记录 -->
      <section class="payment-admin__orders">
        <div class="payment-admin__panel-header">
          <el-icon class="panel-header-icon"><Setting /></el-icon>
          <span class="panel-header-title">近期充值订单</span>
        </div>
        <el-table :data="orders" stripe style="width: 100%">
          <el-table-column label="订单号" min-width="190">
            <template #default="{ row }">
              <span class="mono-code">{{ row.outTradeNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="充值用户" min-width="180">
            <template #default="{ row }">
              <div class="user-cell">
                <div
                  class="user-cell__avatar"
                  :style="{
                    backgroundColor: getUserColor(row.username || row.email || row.userId),
                  }"
                >
                  {{ getUserInitials(row.username || row.email || row.userId) }}
                </div>
                <span class="user-cell__name">{{ row.username || row.email || row.userId }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="支付方式" width="120">
            <template #default="{ row }">
              <div class="payment-type-badge" :class="row.paymentType">
                <span class="dot" />
                {{ paymentTypeLabel(row.paymentType) }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="到账余额" width="110" align="right">
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
          <el-table-column label="三方支付流水号" min-width="160">
            <template #default="{ row }">
              <span v-if="row.paymentTradeNo" class="mono-code">{{ row.paymentTradeNo }}</span>
              <span v-else class="text-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">
              <span class="time-text">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="入账时间" width="170">
            <template #default="{ row }">
              <span v-if="row.completedAt" class="time-text">{{
                formatDateTime(row.completedAt)
              }}</span>
              <span v-else class="text-muted">—</span>
            </template>
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
    gap: 24px;

    &__grid {
      display: grid;
      grid-template-columns: minmax(360px, 1fr) minmax(360px, 1fr);
      gap: 20px;
      align-items: start;
    }

    &__panel,
    &__orders {
      border: 1px solid var(--el-border-color);
      background: var(--kp-surface-strong);
      border-radius: 8px;
      padding: 24px;
      transition: border-color 0.2s ease, box-shadow 0.2s ease;

      &:hover {
        box-shadow: 0 8px 22px rgba(22, 35, 52, 0.06);
        border-color: rgba(91, 141, 239, 0.25);
      }
    }

    &__panel--wide {
      grid-column: 1 / -1;
    }

    &__panel-header {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 20px;
      border-bottom: 1px solid var(--kp-divider);
      padding-bottom: 12px;

      .panel-header-icon {
        font-size: 20px;
        color: var(--kp-primary);
      }

      .panel-header-title {
        font-size: 16px;
        font-weight: 600;
        color: var(--kp-text);
      }
    }

    &__row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 18px;
      border: 1px solid var(--kp-divider);
      border-radius: 12px;
      padding: 16px 20px;
      margin-bottom: 20px;
      transition: all 0.3s ease;

      &--switch {
        background: rgba(255, 255, 255, 0.6);
        box-shadow: inset 0 2px 4px rgba(0, 0, 0, 0.02);

        &:hover {
          background: rgba(255, 255, 255, 0.85);
          border-color: var(--kp-primary-soft);
        }
      }

      .switch-info {
        display: flex;
        flex-direction: column;
        gap: 4px;
      }

      .switch-title {
        font-weight: 600;
        font-size: 14px;
        color: var(--kp-text);
      }

      .switch-desc {
        margin: 0;
        font-size: 12px;
        color: var(--kp-text-secondary);
        line-height: 1.4;
      }
    }

    &__form-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 16px;
    }
  }

  .field-hint {
    width: 100%;
    margin: 6px 0 0;
    font-size: 12px;
    line-height: 1.4;
  }

  /* 支付方式定制卡片选择器 */
  .payment-method-selector {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 14px;
    width: 100%;
    margin-top: 4px;
  }

  .payment-method-card {
    display: flex;
    align-items: center;
    gap: 14px;
    border: 1px solid var(--kp-divider);
    border-radius: 12px;
    padding: 16px;
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
        box-shadow:
          0 0 0 1px #1677ff,
          var(--kp-shadow);
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
        box-shadow:
          0 0 0 1px #07c160,
          var(--kp-shadow);
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

  /* 支付模式收音单选美化 */
  .payment-mode-radios {
    :deep(.el-radio-button__inner) {
      border-radius: 10px !important;
      margin-right: 8px;
      border: 1px solid var(--kp-divider) !important;
      background: rgba(255, 255, 255, 0.6) !important;
      box-shadow: none !important;
      color: var(--kp-text-secondary);
      transition: all 0.2s ease;
      font-weight: 500;
    }
    :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
      background: var(--kp-brand-gradient) !important;
      color: #fff !important;
      border-color: transparent !important;
      box-shadow: var(--kp-shadow) !important;
    }
  }

  /* 表格用户单元格设计 */
  .user-cell {
    display: flex;
    align-items: center;
    gap: 10px;

    &__avatar {
      width: 32px;
      height: 32px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      color: #fff;
      font-size: 14px;
      font-weight: 600;
      box-shadow: 0 2px 6px rgba(0, 0, 0, 0.08);
      border: 1.5px solid rgba(255, 255, 255, 0.8);
    }

    &__name {
      font-weight: 500;
      color: var(--kp-text);
      font-size: 13px;
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

    &.pending,
    &.paid {
      background: rgba(240, 168, 77, 0.12);
      color: var(--kp-warning);
    }

    &.failed,
    &.expired {
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

  @media (max-width: 1200px) {
    .payment-admin {
      &__grid {
        grid-template-columns: 1fr;
      }
    }
  }

  @media (max-width: 600px) {
    .payment-admin {
      &__form-grid,
      .payment-method-selector {
        grid-template-columns: 1fr;
      }
    }
  }
</style>
