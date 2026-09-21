<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import { ElMessage } from 'element-plus';
  import {
    Monitor,
    Key,
    Wallet,
    ArrowRight,
    Refresh,
    Clock,
    Document,
    Check,
  } from '@element-plus/icons-vue';
  import { codingPlanApi } from '@/api/codingPlans';
  import { useAuthStore } from '@/stores/auth';
  import type { CodingPlan, CodingPlanSubscription } from '@/types/api';
  import { credits, stateLabel, stateType, subscriptionStates } from './codingPlanDisplay';
  import { formatDateTime } from '@/utils/format';
  import PageSection from '@/components/PageSection.vue';
  import ProviderLogo from '@/components/ProviderLogo.vue';
  import SubscriptionLedgerDrawer from './SubscriptionLedgerDrawer.vue';
  import { useAppearanceStore } from '@/stores/appearance';

  const router = useRouter();
  const auth = useAuthStore();
  const appearance = useAppearanceStore();
  const plans = ref<CodingPlan[]>([]);
  const subscriptions = ref<CodingPlanSubscription[]>([]);
  const loading = ref(false);
  const loadError = ref(false);
  const page = ref(1);
  const total = ref(0);
  const filter = ref('');
  const selected = ref<CodingPlan | null>(null);
  const checkout = ref(false);
  const purchasing = ref(false);
  const ledgerVisible = ref(false);
  const ledgerSubscription = ref<CodingPlanSubscription | null>(null);
  const canPurchase = computed(
    () => selected.value && Number(auth.user?.balance ?? 0) >= Number(selected.value.priceCredits),
  );
  const usagePercent = (s: CodingPlanSubscription) =>
    Math.min(100, (Number(s.usedCredits) / Number(s.quotaCredits)) * 100);

  async function loadSubscriptions() {
    const result = await codingPlanApi.page({
      page: page.value,
      pageSize: 10,
      status: filter.value,
    });
    subscriptions.value = result.items;
    total.value = result.total;
  }
  async function load() {
    loading.value = true;
    loadError.value = false;
    try {
      const [products] = await Promise.all([
        codingPlanApi.plans(),
        loadSubscriptions(),
      ]);
      plans.value = products;
    } catch {
      loadError.value = true;
    } finally {
      loading.value = false;
    }
  }
  function selectPlan(plan: CodingPlan) {
    selected.value = plan;
    checkout.value = true;
  }
  async function purchase() {
    const plan = selected.value;
    if (!plan || purchasing.value) return;
    purchasing.value = true;
    const slot = `coding-purchase:${auth.user?.userId}:${plan.planId}:${plan.version}`;
    const key = sessionStorage.getItem(slot) ?? crypto.randomUUID();
    sessionStorage.setItem(slot, key);
    try {
      await codingPlanApi.purchase(plan.planId, plan.version, key);
      sessionStorage.removeItem(slot);
      checkout.value = false;
      ElMessage.success('套餐已开通');
      await Promise.all([load(), auth.refreshCurrentUser()]);
    } finally {
      purchasing.value = false;
    }
  }
  function openLedger(s: CodingPlanSubscription) {
    ledgerSubscription.value = s;
    ledgerVisible.value = true;
  }
  async function changeFilter() {
    page.value = 1;
    await loadSubscriptions();
  }
  onMounted(load);
</script>

<template>
  <main v-loading="loading" class="plan-workspace" :class="`is-${appearance.chrome}`">
    <PageSection title="Coding Plan" subtitle="开发者订阅" :icon="Monitor">
      <template #actions>
        <el-tooltip content="更新额度">
          <el-button :icon="Refresh" aria-label="更新额度" :loading="loading" @click="load" />
        </el-tooltip>
        <el-button :icon="Key" @click="router.push({ name: 'api-keys' })">
          API Key<el-icon class="trailing-icon"><ArrowRight /></el-icon>
        </el-button>
      </template>
      <el-alert v-if="loadError" title="套餐加载失败，请重试" type="error" :closable="false" />

      <section class="plan-section">
        <div class="plan-section-heading">
          <h2>
            我的订阅 <span>{{ total }}</span>
          </h2>
          <el-select
            v-model="filter"
            aria-label="订阅状态"
            placeholder="全部状态"
            class="plan-status-filter"
            @change="changeFilter"
          >
            <el-option label="全部状态" value="" /><el-option
              v-for="state in subscriptionStates"
              :key="state.value"
              :label="state.label"
              :value="state.value"
            />
          </el-select>
        </div>
        <el-empty
          v-if="!subscriptions.length && !loading"
          :description="filter ? '暂无符合条件的订阅' : '还没有 Coding Plan'"
          :image-size="76"
        />
        <div v-else class="subscription-list">
          <article
            v-for="item in subscriptions"
            :key="item.subscriptionId"
            class="subscription-row"
          >
            <div class="subscription-title">
              <el-icon><Monitor /></el-icon>
              <div>
                <strong>{{ item.planName }}</strong><small>{{
                  item.allowedProviders.length
                    ? item.allowedProviders.join(' / ')
                    : '全部模型分类'
                }}
                  · v{{ item.planVersion }}</small>
              </div>
            </div>
            <div class="subscription-quota">
              <div>
                <b>{{ credits(item.availableCredits) }}</b><span>/ {{ credits(item.quotaCredits) }}</span>
              </div>
              <el-progress
                :percentage="usagePercent(item)"
                :show-text="false"
                :stroke-width="4"
                color="#268879"
              /><small v-if="item.reservedCredits">预占 {{ credits(item.reservedCredits) }}</small>
            </div>
            <div class="subscription-period">
              <el-tag :type="stateType(item.status)" effect="plain">
                {{
                  stateLabel(item.status)
                }}
              </el-tag><small>{{ formatDateTime(item.startsAt).slice(0, 10) }} 至
                {{ formatDateTime(item.endsAt).slice(0, 10) }}</small>
            </div>
            <el-tooltip content="额度明细">
              <el-button :icon="Document" text aria-label="额度明细" @click="openLedger(item)" />
            </el-tooltip>
          </article>
        </div>
        <el-pagination
          v-if="total > 10"
          v-model:current-page="page"
          :page-size="10"
          :total="total"
          layout="prev, pager, next"
          @current-change="loadSubscriptions"
        />
      </section>

      <section class="plan-section">
        <div class="plan-section-heading">
          <h2>选择套餐</h2>
          <span class="plan-caption">{{ plans.length }} 款可用</span>
        </div>
        <el-empty v-if="!plans.length && !loading" description="暂无在售套餐" :image-size="76" />
        <div class="product-grid">
          <article
            v-for="(plan, index) in plans"
            :key="plan.planId"
            class="product-item"
            :style="{ '--product-accent': ['#258573', '#426fa6', '#9b6c35'][index % 3] }"
          >
            <div class="product-top">
              <span class="product-icon"><el-icon><Monitor /></el-icon></span><span class="product-term">{{ plan.durationDays }} 天</span>
            </div>
            <h3>{{ plan.name }}</h3>
            <p class="product-description">{{ plan.description || 'Coding Plan' }}</p>
            <div class="product-price">
              {{ credits(plan.priceCredits) }}<span>credits / 期</span>
            </div>
            <div class="product-entitlement">
              <el-icon><Check /></el-icon><strong>{{ credits(plan.quotaCredits) }}</strong><span>调用额度</span>
            </div>
            <div class="product-providers">
              <template v-if="plan.allowedProviders.length">
                <span v-for="provider in plan.allowedProviders" :key="provider"><ProviderLogo :provider="provider" :size="18" />{{ provider }}</span>
              </template><span v-else>全部模型分类</span>
            </div>
            <el-button class="product-purchase" @click="selectPlan(plan)">
              开通套餐<el-icon><ArrowRight /></el-icon>
            </el-button>
          </article>
        </div>
      </section>

      <el-dialog
        v-model="checkout"
        title="开通 Coding Plan"
        width="min(460px, calc(100vw - 32px))"
        :close-on-click-modal="!purchasing"
        :close-on-press-escape="!purchasing"
        :show-close="!purchasing"
      >
        <div v-if="selected" class="checkout-summary">
          <div class="checkout-plan">
            <el-icon><Monitor /></el-icon>
            <h3>{{ selected.name }}</h3>
          </div>
          <dl>
            <div>
              <dt>有效期</dt>
              <dd>{{ selected.durationDays }} 天</dd>
            </div>
            <div>
              <dt>调用额度</dt>
              <dd>{{ credits(selected.quotaCredits) }} credits</dd>
            </div>
            <div>
              <dt>适用分类</dt>
              <dd>{{ selected.allowedProviders.join(' / ') || '全部模型分类' }}</dd>
            </div>
            <div>
              <dt>版本</dt>
              <dd>v{{ selected.version }}</dd>
            </div>
            <div class="checkout-total">
              <dt>应付余额</dt>
              <dd>{{ credits(selected.priceCredits) }} credits</dd>
            </div>
          </dl>
          <p class="checkout-terms">
            <el-icon><Clock /></el-icon>同套餐续购将在当前期到期或额度用尽后生效。
          </p>
          <el-alert v-if="!canPurchase" title="钱包余额不足" type="warning" :closable="false" />
        </div>
        <template #footer>
          <el-button :disabled="purchasing" @click="checkout = false">取消</el-button><el-button
            v-if="!canPurchase"
            :icon="Wallet"
            type="primary"
            @click="router.push({ name: 'payment' })"
          >
            充值余额
          </el-button><el-button v-else type="primary" :loading="purchasing" @click="purchase">
            确认开通
          </el-button>
        </template>
      </el-dialog>
      <SubscriptionLedgerDrawer
        v-if="ledgerSubscription"
        v-model="ledgerVisible"
        :subscription-id="ledgerSubscription.subscriptionId"
        :name="ledgerSubscription.planName"
      />
    </PageSection>
  </main>
</template>

<style scoped lang="scss">
  .plan-workspace {
    color: var(--kp-text);
    max-width: 1280px;
    margin: 0 auto;
    padding: 0 0 32px;
  }
  .plan-section-heading {
    display: flex;
    align-items: center;
    gap: 16px;
  }
  .plan-section-heading {
    justify-content: space-between;
  }
  .trailing-icon {
    margin-left: 10px;
  }
  .plan-section {
    margin-top: 34px;

    &:first-of-type {
      margin-top: 0;
    }
  }
  .plan-workspace.is-editorial .plan-section {
    margin-top: 16px;

    &:first-of-type {
      margin-top: 0;
    }
  }
  .plan-workspace.is-editorial .plan-section-heading {
    margin-bottom: 10px;
  }
  .plan-workspace.is-editorial .product-grid {
    gap: 10px;
  }
  .plan-workspace.is-editorial .product-item {
    padding: 14px;
  }
  .plan-workspace.is-editorial .subscription-row {
    padding: 12px 0;
    gap: 16px;
  }
  .plan-section-heading {
    margin-bottom: 18px;
    gap: 12px;
  }
  .plan-section h2 {
    font-size: 16px;
    margin: 0;
    font-weight: 600;
  }
  .plan-section h2 span {
    margin-left: 8px;
    font-size: 12px;
    color: var(--kp-text-muted);
  }
  .plan-status-filter {
    width: 130px;
  }
  .plan-caption {
    color: var(--kp-text-muted);
    font-size: 12px;
  }
  .subscription-row {
    display: grid;
    grid-template-columns: minmax(170px, 1.3fr) minmax(130px, 1fr) minmax(180px, 1fr) 32px;
    gap: 24px;
    align-items: center;
    border-bottom: 1px solid var(--el-border-color-lighter);
    padding: 20px 0;
  }
  .subscription-title {
    display: flex;
    gap: 12px;
    align-items: center;
    min-width: 0;
  }
  .subscription-title > .el-icon {
    font-size: 22px;
    color: #268879;
    flex-shrink: 0;
  }
  .subscription-title strong {
    font-size: 14px;
    overflow-wrap: anywhere;
  }
  .subscription-row small {
    display: block;
    margin-top: 7px;
    font-size: 11px;
    color: var(--kp-text-muted);
  }
  .subscription-quota > div {
    display: flex;
    align-items: baseline;
    gap: 6px;
    margin-bottom: 8px;
    flex-wrap: wrap;
  }
  .subscription-quota b {
    font-size: 17px;
    font-variant-numeric: tabular-nums;
  }
  .subscription-quota span {
    font-size: 11px;
    color: var(--kp-text-muted);
  }
  .el-pagination {
    justify-content: flex-end;
    margin-top: 20px;
  }
  .product-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(min(100%, 260px), 1fr));
    gap: 18px;
  }
  .product-item {
    min-width: 0;
    display: flex;
    flex-direction: column;
    padding: 24px;
    border: 1px solid var(--el-border-color);
    border-top: 3px solid var(--product-accent);
    border-radius: 8px;
    background: transparent;
    transition:
      box-shadow 0.2s,
      border-color 0.2s;
    &:hover {
      box-shadow: 0 8px 22px rgba(22, 35, 52, 0.06);
    }
  }
  .plan-workspace.is-classic {
    .subscription-list {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .subscription-row {
      padding: 16px 20px;
    }
    .product-item:hover {
      box-shadow: var(--kp-shadow);
    }
  }
  .product-top {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .product-icon {
    display: grid;
    place-items: center;
    width: 34px;
    height: 34px;
    color: var(--product-accent);
    font-size: 24px;
  }
  .product-term {
    font-size: 12px;
    color: var(--kp-text-secondary);
  }
  .product-item h3 {
    font-size: 18px;
    margin: 18px 0 8px;
    overflow-wrap: anywhere;
  }
  .product-description {
    font-size: 12px;
    line-height: 1.7;
    color: var(--kp-text-muted);
    margin: 0;
    min-height: 40px;
    overflow-wrap: anywhere;
  }
  .product-price {
    font-size: 30px;
    font-weight: 600;
    margin: 24px 0;
    overflow-wrap: anywhere;
    font-variant-numeric: tabular-nums;
  }
  .product-price span {
    display: block;
    font-size: 11px;
    font-weight: 400;
    color: var(--kp-text-muted);
    margin-top: 5px;
  }
  .product-entitlement {
    display: flex;
    gap: 8px;
    align-items: center;
    flex-wrap: wrap;
    font-size: 13px;
    margin-bottom: 18px;
  }
  .product-entitlement .el-icon {
    color: var(--product-accent);
  }
  .product-entitlement span {
    color: var(--kp-text-secondary);
  }
  .product-providers {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    margin-bottom: 24px;
    font-size: 12px;
    color: var(--kp-text-secondary);
  }
  .product-providers span {
    display: flex;
    gap: 6px;
    align-items: center;
  }
  .product-purchase {
    margin-top: auto;
    width: 100%;
    height: 40px;
  }
  .product-purchase :deep(span) {
    display: flex;
    justify-content: space-between;
    width: 100%;
  }
  .checkout-plan {
    display: flex;
    align-items: center;
    gap: 12px;
    padding-bottom: 18px;
    font-size: 24px;
    color: #268879;
  }
  .checkout-plan h3 {
    font-size: 18px;
    color: var(--kp-text);
    margin: 0;
    overflow-wrap: anywhere;
  }
  .checkout-summary dl > div {
    display: flex;
    justify-content: space-between;
    gap: 20px;
    padding: 10px 0;
    font-size: 13px;
  }
  .checkout-summary dt {
    color: var(--kp-text-muted);
    flex-shrink: 0;
  }
  .checkout-summary dd {
    margin: 0;
    text-align: right;
    overflow-wrap: anywhere;
  }
  .checkout-summary .checkout-total {
    border-top: 1px solid var(--el-border-color);
    margin-top: 12px;
    padding-top: 20px;
    font-weight: 600;
  }
  .checkout-terms {
    display: flex;
    gap: 8px;
    font-size: 12px;
    line-height: 1.7;
    color: var(--kp-text-secondary);
  }
  @media (max-width: 760px) {
    .subscription-row {
      grid-template-columns: minmax(0, 1fr) 32px;
      gap: 14px;
    }
    .subscription-title {
      grid-column: 1;
    }
    .subscription-row > .el-tooltip__trigger {
      grid-column: 2;
      grid-row: 1;
    }
    .subscription-quota {
      grid-column: 1/-1;
    }
    .subscription-period {
      grid-column: 1/-1;
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .subscription-period small {
      margin: 0;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .product-item {
      transition: none;
    }
  }
</style>
