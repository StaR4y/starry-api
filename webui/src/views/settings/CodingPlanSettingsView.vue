<script setup lang="ts">
  import { t } from '@/i18n';
  import { computed, onMounted, reactive, ref, watch } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import {
    Monitor,
    Plus,
    Refresh,
    EditPen,
    Search,
    Document,
    User,
    CircleCheck,
    Warning,
  } from '@element-plus/icons-vue';
  import { adminCodingPlanApi, type BillingReview } from '@/api/codingPlans';
  import { modelsApi, usersApi } from '@/api';
  import type { AdminUser, CodingPlan, CodingPlanSubscription } from '@/types/api';
  import { credits, stateLabel, stateType, subscriptionStates } from '../payment/codingPlanDisplay';
  import { formatDateTime } from '@/utils/format';
  import SubscriptionLedgerDrawer from '../payment/SubscriptionLedgerDrawer.vue';
  import ProviderLogo from '@/components/ProviderLogo.vue';
  import PageSection from '@/components/PageSection.vue';
  import MetricsPanel, { type MetricPanelItem } from '@/components/MetricsPanel.vue';

  const tab = ref('products');
  const loading = ref(false);
  const saving = ref(false);
  const plans = ref<CodingPlan[]>([]);
  const productSearch = ref('');
  const productState = ref('enabled');
  const productPage = ref(1);
  const filteredPlans = computed(() =>
    plans.value.filter(
      (plan) =>
        (productState.value === 'all' || plan.enabled === (productState.value === 'enabled')) &&
        plan.name.toLowerCase().includes(productSearch.value.trim().toLowerCase()),
    ),
  );
  const visiblePlans = computed(() =>
    filteredPlans.value.slice((productPage.value - 1) * 10, productPage.value * 10),
  );
  watch([productSearch, productState], () => {
    productPage.value = 1;
  });
  watch(
    () => filteredPlans.value.length,
    (count) => {
      productPage.value = Math.min(productPage.value, Math.max(1, Math.ceil(count / 10)));
    },
  );
  const subscriptions = ref<CodingPlanSubscription[]>([]);
  const reviews = ref<BillingReview[]>([]);
  const users = ref<AdminUser[]>([]);
  const providers = ref<string[]>([]);
  const search = ref('');
  const status = ref('');
  const page = ref(1);
  const total = ref(0);
  const editing = ref<CodingPlan | null>(null);
  const visible = ref(false);
  const grantVisible = ref(false);
  const grantUser = ref('');
  const grantPlan = ref('');
  const grantKey = ref('');
  const busyIds = ref(new Set<string>());
  const form = reactive({
    name: '',
    description: '',
    priceCredits: '10',
    quotaCredits: '100',
    durationDays: 30,
    allowedProviders: [] as string[],
    sortOrder: 0,
  });
  const ledgerVisible = ref(false);
  const selectedSubscription = ref<CodingPlanSubscription | null>(null);
  const selectedReview = ref<BillingReview | null>(null);
  const reviewVisible = ref(false);
  const resolution = reactive({ action: 'SETTLE', reason: '', credits: '0' });
  const published = computed(() => plans.value.filter((p) => p.enabled).length);
  const eligibleUsers = computed(() => users.value.filter((u) => u.role === 'USER' && u.enabled));
  const overviewMetrics = computed<MetricPanelItem[]>(() => [
    {
      get label() {
        return t('在售套餐');
      },
      value: published.value,
      tone: 'success',
    },
    {
      get label() {
        return t('用户订阅');
      },
      value: total.value,
      tone: 'primary',
    },
    {
      get label() {
        return t('待核对');
      },
      value: reviews.value.length,
      tone: reviews.value.length ? 'warning' : 'info',
    },
  ]);

  async function loadPage() {
    const result = await adminCodingPlanApi.page({
      page: page.value,
      pageSize: 15,
      search: search.value,
      status: status.value,
    });
    subscriptions.value = result.items;
    total.value = result.total;
  }
  async function load() {
    loading.value = true;
    try {
      const [products, models, members, pending] = await Promise.all([
        adminCodingPlanApi.plans(),
        modelsApi.list(),
        usersApi.list(),
        adminCodingPlanApi.reviews(),
        loadPage(),
      ]);
      plans.value = products;
      providers.value = [...new Set(models.map((m) => m.provider).filter(Boolean))].sort();
      users.value = members;
      reviews.value = pending;
    } finally {
      loading.value = false;
    }
  }
  async function filterSubscriptions() {
    page.value = 1;
    await loadPage();
  }
  function edit(plan: CodingPlan | null) {
    editing.value = plan;
    Object.assign(form, {
      name: plan?.name ?? '',
      description: plan?.description ?? '',
      priceCredits: String(plan?.priceCredits ?? 10),
      quotaCredits: String(plan?.quotaCredits ?? 100),
      durationDays: plan?.durationDays ?? 30,
      allowedProviders: [...(plan?.allowedProviders ?? [])],
      sortOrder: plan?.sortOrder ?? 0,
    });
    visible.value = true;
  }
  function validMoney(value: string, positive: boolean) {
    return /^\d{1,14}(\.\d{1,6})?$/.test(value) && (!positive || Number(value) > 0);
  }
  async function save() {
    if (
      !form.name.trim() ||
      !validMoney(form.priceCredits, false) ||
      !validMoney(form.quotaCredits, true)
    ) {
      ElMessage.warning(t('请检查名称、售价和额度，金额最多支持 6 位小数'));
      return;
    }
    saving.value = true;
    try {
      const payload = {
        ...form,
        name: form.name.trim(),
        allowedProviders: [...form.allowedProviders],
      };
      if (editing.value) await adminCodingPlanApi.updatePlan(editing.value.planId, payload);
      else await adminCodingPlanApi.createPlan(payload);
      visible.value = false;
      ElMessage.success(editing.value ? t('新版本已发布，已有订阅权益保持不变') : t('套餐已发布'));
      await load();
    } finally {
      saving.value = false;
    }
  }
  async function toggle(plan: CodingPlan) {
    if (busyIds.value.has(plan.planId)) return;
    busyIds.value.add(plan.planId);
    try {
      const result = await adminCodingPlanApi.updatePlan(plan.planId, { enabled: !plan.enabled });
      plans.value = plans.value.map((p) => (p.planId === result.planId ? result : p));
    } finally {
      busyIds.value.delete(plan.planId);
    }
  }
  function openGrant() {
    grantUser.value = '';
    grantPlan.value = '';
    grantKey.value = crypto.randomUUID();
    grantVisible.value = true;
  }
  async function grant() {
    if (!grantUser.value || !grantPlan.value) {
      ElMessage.warning(t('请选择用户与套餐'));
      return;
    }
    saving.value = true;
    try {
      await adminCodingPlanApi.grantSubscription(
        { userId: grantUser.value, planId: grantPlan.value },
        grantKey.value,
      );
      grantVisible.value = false;
      ElMessage.success(t('订阅已开通'));
      await loadPage();
    } finally {
      saving.value = false;
    }
  }
  async function changeStatus(item: CodingPlanSubscription, next: string) {
    const result = await ElMessageBox.prompt(
      t('操作原因'),
      next === 'ACTIVE' ? t('恢复订阅') : t('撤销订阅'),
      {
        inputValidator: (value) => Boolean(value?.trim()) || t('请填写原因'),
        get inputPlaceholder() {
          return t('填写本次操作的原因');
        },
        get confirmButtonText() {
          return t('确认');
        },
        get cancelButtonText() {
          return t('取消');
        },
      },
    ).catch(() => null);
    if (!result) return;
    await adminCodingPlanApi.updateSubscriptionStatus(item.subscriptionId, next, result.value);
    await loadPage();
  }
  function ledger(item: CodingPlanSubscription) {
    selectedSubscription.value = item;
    ledgerVisible.value = true;
  }
  function review(item: BillingReview) {
    selectedReview.value = item;
    Object.assign(resolution, {
      action: 'SETTLE',
      credits: String(item.actual_credits ?? item.reserved_credits),
      reason: '',
    });
    reviewVisible.value = true;
  }
  async function resolve() {
    if (
      !selectedReview.value ||
      !resolution.reason.trim() ||
      !validMoney(resolution.credits, false)
    ) {
      ElMessage.warning(t('请填写核对金额与原因'));
      return;
    }
    saving.value = true;
    try {
      await adminCodingPlanApi.resolve(selectedReview.value.request_hash, resolution);
      reviewVisible.value = false;
      ElMessage.success(t('费用核对已完成'));
      await load();
    } finally {
      saving.value = false;
    }
  }
  onMounted(load);
</script>

<template>
  <PageSection title="Coding Plan" :subtitle="t('订阅管理')" :icon="Monitor">
    <template #actions>
      <el-tooltip :content="t('刷新')">
        <el-button :icon="Refresh" :aria-label="t('刷新')" :loading="loading" @click="load" />
      </el-tooltip>
      <el-button v-if="tab === 'products'" type="primary" :icon="Plus" @click="edit(null)">
        {{ t('新建套餐') }}
      </el-button>
      <el-button v-if="tab === 'subscriptions'" type="primary" :icon="User" @click="openGrant">
        {{ t('开通订阅') }}
      </el-button>
    </template>
    <template #summary>
      <MetricsPanel :items="overviewMetrics" />
    </template>
    <div v-loading="loading" class="plan-admin">
      <el-tabs v-model="tab" class="admin-tabs">
        <el-tab-pane :label="t('套餐管理')" name="products">
          <div class="subscription-toolbar">
            <el-input
              v-model="productSearch"
              :prefix-icon="Search"
              :placeholder="t('搜索套餐')"
              clearable
            />
            <el-select v-model="productState" :aria-label="t('套餐在售状态')">
              <el-option :label="t('在售套餐')" value="enabled" /><el-option
                :label="t('已下架')"
                value="disabled"
              /><el-option :label="t('全部套餐')" value="all" />
            </el-select>
          </div>
          <el-empty v-if="!plans.length && !loading" :description="t('暂无套餐')" :image-size="76">
            <el-button :icon="Plus" @click="edit(null)">{{ t('新建套餐') }}</el-button>
          </el-empty>
          <el-table
            v-else
            :data="visiblePlans"
            class="plan-admin-table"
            :empty-text="t('暂无符合条件的套餐')"
          >
            <el-table-column :label="t('套餐')" min-width="200">
              <template #default="{ row }">
                <div class="table-product">
                  <el-icon><Monitor /></el-icon>
                  <div>
                    <strong>{{ row.name }}</strong><small>v{{ row.version }} · {{ row.durationDays }} {{ t('天') }}</small>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column :label="t('售价 / credits')" min-width="130" align="right">
              <template #default="{ row }">
                <b>{{ credits(row.priceCredits) }}</b>
              </template>
            </el-table-column>
            <el-table-column :label="t('调用额度')" min-width="130" align="right">
              <template #default="{ row }">
                {{ credits(row.quotaCredits) }}
              </template>
            </el-table-column>
            <el-table-column :label="t('模型分类')" min-width="160">
              <template #default="{ row }">
                <span v-if="!row.allowedProviders.length" class="muted">{{
                  t('全部模型分类')
                }}</span>
                <div v-else class="provider-stack">
                  <ProviderLogo
                    v-for="provider in row.allowedProviders"
                    :key="provider"
                    :provider="provider"
                    :size="22"
                    :title="provider"
                  /><span>{{ row.allowedProviders.join(' / ') }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column :label="t('在售')" width="82">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.enabled"
                  :loading="busyIds.has(row.planId)"
                  :aria-label="row.name + t('在售状态')"
                  @change="toggle(row)"
                />
              </template>
            </el-table-column>
            <el-table-column width="60" fixed="right">
              <template #default="{ row }">
                <el-tooltip :content="t('编辑并发布版本')">
                  <el-button
                    :icon="EditPen"
                    text
                    :aria-label="t('编辑并发布版本')"
                    @click="edit(row)"
                  />
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-if="filteredPlans.length > 10"
            v-model:current-page="productPage"
            :total="filteredPlans.length"
            :page-size="10"
            layout="total, prev, pager, next"
          />
        </el-tab-pane>
        <el-tab-pane :label="t('用户订阅')" name="subscriptions">
          <div class="subscription-toolbar">
            <el-input
              v-model="search"
              :prefix-icon="Search"
              :placeholder="t('搜索用户或套餐')"
              clearable
              @keyup.enter="filterSubscriptions"
              @clear="filterSubscriptions"
            /><el-select
              v-model="status"
              :aria-label="t('订阅状态')"
              :placeholder="t('全部状态')"
              @change="filterSubscriptions"
            >
              <el-option :label="t('全部状态')" value="" /><el-option
                v-for="s in subscriptionStates"
                :key="s.value"
                :label="s.label"
                :value="s.value"
              />
            </el-select><el-button :icon="Search" :aria-label="t('搜索订阅')" @click="filterSubscriptions" />
          </div>
          <el-table
            :data="subscriptions"
            class="plan-admin-table"
            :empty-text="t('暂无符合条件的订阅')"
          >
            <el-table-column :label="t('用户 / 套餐')" min-width="220">
              <template #default="{ row }">
                <strong>{{ row.username || row.userId }}</strong><small>{{ row.planName }} · v{{ row.planVersion }}</small>
              </template>
            </el-table-column>
            <el-table-column :label="t('状态')" width="95">
              <template #default="{ row }">
                <el-tag :type="stateType(row.status)" effect="plain">
                  {{ stateLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('可用 / 总额度')" min-width="160" align="right">
              <template #default="{ row }">
                <b>{{ credits(row.availableCredits) }}</b> / {{ credits(row.quotaCredits)
                }}<small v-if="row.reservedCredits">{{ t('预占') }} {{ credits(row.reservedCredits) }}</small>
              </template>
            </el-table-column>
            <el-table-column :label="t('有效期')" min-width="210">
              <template #default="{ row }">
                {{ formatDateTime(row.startsAt).slice(0, 10)
                }}<small>{{ t('至') }} {{ formatDateTime(row.endsAt).slice(0, 10) }}</small>
              </template>
            </el-table-column>
            <el-table-column :label="t('操作')" width="110" fixed="right">
              <template #default="{ row }">
                <el-tooltip :content="t('额度明细')">
                  <el-button
                    :icon="Document"
                    text
                    :aria-label="t('额度明细')"
                    @click="ledger(row)"
                  />
                </el-tooltip><el-tooltip
                  v-if="['ACTIVE', 'SCHEDULED'].includes(row.status)"
                  :content="t('撤销订阅')"
                >
                  <el-button
                    :icon="Warning"
                    text
                    :aria-label="t('撤销订阅')"
                    @click="changeStatus(row, 'REVOKED')"
                  />
                </el-tooltip><el-tooltip
                  v-else-if="['REVOKED', 'CANCELLED'].includes(row.status)"
                  :content="t('恢复订阅')"
                >
                  <el-button
                    :icon="CircleCheck"
                    text
                    :aria-label="t('恢复订阅')"
                    @click="changeStatus(row, 'ACTIVE')"
                  />
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-if="total > 15"
            v-model:current-page="page"
            :total="total"
            :page-size="15"
            layout="total, prev, pager, next"
            @current-change="loadPage"
          />
        </el-tab-pane>
        <el-tab-pane
          :label="reviews.length ? t('费用核对 (') + reviews.length + ')' : t('费用核对')"
          name="reviews"
        >
          <el-empty v-if="!reviews.length" :description="t('没有待核对的费用')" :image-size="76" />
          <el-table v-else :data="reviews" class="plan-admin-table">
            <el-table-column prop="user_id" :label="t('用户')" min-width="160" /><el-table-column
              :label="t('途径')"
              width="125"
            >
              <template #default="{ row }">
                {{ row.billing_source === 'BALANCE' ? t('余额计费') : 'Coding Plan' }}
              </template>
            </el-table-column><el-table-column :label="t('预占 / 实际')" min-width="140">
              <template #default="{ row }">
                {{ credits(row.reserved_credits) }} /
                {{ row.actual_credits == null ? t('待确认') : credits(row.actual_credits) }}
              </template>
            </el-table-column><el-table-column prop="reason" :label="t('原因')" min-width="200" /><el-table-column
              :label="t('操作')"
              width="90"
            >
              <template #default="{ row }">
                <el-button link type="primary" @click="review(row)">{{ t('核对') }}</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
      <el-drawer
        v-model="visible"
        :title="editing ? t('发布套餐新版本') : t('新建 Coding Plan')"
        size="min(520px, 100vw)"
        :close-on-click-modal="!saving"
      >
        <el-form label-position="top" class="product-form">
          <el-form-item :label="t('套餐名称')" required>
            <el-input v-model="form.name" maxlength="128" :placeholder="t('例如：Coding Pro')" />
          </el-form-item>
          <el-form-item :label="t('套餐描述')">
            <el-input v-model="form.description" type="textarea" :rows="2" maxlength="512" />
          </el-form-item>
          <div class="form-pair">
            <el-form-item :label="t('售价（credits）')" required>
              <el-input v-model="form.priceCredits" inputmode="decimal" />
            </el-form-item><el-form-item :label="t('调用额度（credits）')" required>
              <el-input v-model="form.quotaCredits" inputmode="decimal" />
            </el-form-item>
          </div>
          <div class="form-pair">
            <el-form-item :label="t('有效期（天）')" required>
              <el-input-number
                v-model="form.durationDays"
                :min="1"
                :max="3650"
                :precision="0"
                controls-position="right"
              />
            </el-form-item><el-form-item :label="t('显示顺序')">
              <el-input-number
                v-model="form.sortOrder"
                :min="0"
                :max="1000000"
                :precision="0"
                controls-position="right"
              />
            </el-form-item>
          </div>
          <el-form-item :label="t('适用模型分类')">
            <el-select
              v-model="form.allowedProviders"
              multiple
              clearable
              :placeholder="t('全部模型分类')"
            >
              <el-option
                v-for="provider in providers"
                :key="provider"
                :label="provider"
                :value="provider"
              />
            </el-select>
          </el-form-item>
          <el-alert
            v-if="editing"
            :title="t('已有订阅与兑换码继续使用原版本权益')"
            type="info"
            :closable="false"
          />
        </el-form>
        <template #footer>
          <el-button :disabled="saving" @click="visible = false">{{ t('取消') }}</el-button><el-button type="primary" :loading="saving" @click="save">
            {{ editing ? t('发布 v') + (editing.version + 1) : t('发布套餐') }}
          </el-button>
        </template>
      </el-drawer>
      <el-dialog
        v-model="grantVisible"
        :title="t('为用户开通订阅')"
        width="min(460px, calc(100vw - 32px))"
      >
        <el-form label-position="top">
          <el-form-item :label="t('用户')" required>
            <el-select v-model="grantUser" filterable :placeholder="t('搜索用户名或邮箱')">
              <el-option
                v-for="u in eligibleUsers"
                :key="u.userId"
                :label="u.username + (u.email ? ' · ' + u.email : '')"
                :value="u.userId"
              />
            </el-select>
          </el-form-item><el-form-item :label="t('套餐')" required>
            <el-select v-model="grantPlan" :placeholder="t('选择套餐')">
              <el-option
                v-for="p in plans.filter((p) => p.enabled)"
                :key="p.planId"
                :label="p.name + ' · v' + p.version"
                :value="p.planId"
              />
            </el-select>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="grantVisible = false">{{ t('取消') }}</el-button><el-button type="primary" :loading="saving" @click="grant">
            {{
              t('开通订阅')
            }}
          </el-button>
        </template>
      </el-dialog>
      <el-dialog
        v-model="reviewVisible"
        :title="t('费用核对')"
        width="min(460px, calc(100vw - 32px))"
      >
        <el-form label-position="top">
          <el-form-item :label="t('处理方式')">
            <el-radio-group v-model="resolution.action">
              <el-radio-button value="SETTLE" label="SETTLE">{{ t('按实际结算') }}</el-radio-button><el-radio-button value="WAIVE" label="WAIVE">{{ t('免除费用') }}</el-radio-button>
            </el-radio-group>
          </el-form-item><el-form-item v-if="resolution.action === 'SETTLE'" :label="t('实际费用（credits）')">
            <el-input v-model="resolution.credits" inputmode="decimal" />
          </el-form-item><el-form-item :label="t('核对依据与原因')" required>
            <el-input v-model="resolution.reason" type="textarea" :rows="3" maxlength="512" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="reviewVisible = false">{{ t('取消') }}</el-button><el-button type="primary" :loading="saving" @click="resolve">
            {{
              t('确认处理')
            }}
          </el-button>
        </template>
      </el-dialog>
      <SubscriptionLedgerDrawer
        v-if="selectedSubscription"
        v-model="ledgerVisible"
        :subscription-id="selectedSubscription.subscriptionId"
        :name="selectedSubscription.planName"
        admin
      />
    </div>
  </PageSection>
</template>

<style scoped lang="scss">
  .plan-admin {
    min-width: 0;
    color: var(--kp-text);
  }
  .admin-tabs :deep(.el-tabs__item) {
    font-size: 13px;
    height: 46px;
  }
  .admin-tabs :deep(.el-tabs__nav-wrap::after) {
    height: 1px;
  }
  .plan-admin-table {
    --el-table-bg-color: transparent;
    --el-table-tr-bg-color: transparent;
    margin-top: 8px;
  }
  .plan-admin-table small {
    display: block;
    font-size: 11px;
    margin-top: 5px;
    color: var(--kp-text-muted);
  }
  .plan-admin-table strong {
    font-weight: 600;
    overflow-wrap: anywhere;
  }
  .plan-admin-table b {
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }
  .table-product {
    display: flex;
    align-items: center;
    gap: 12px;
  }
  .table-product > .el-icon {
    font-size: 22px;
    color: #268879;
    flex-shrink: 0;
  }
  .provider-stack {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 6px;
    font-size: 11px;
  }
  .provider-stack span {
    width: 100%;
    color: var(--kp-text-secondary);
  }
  .muted {
    color: var(--kp-text-muted);
    font-size: 12px;
  }
  .subscription-toolbar {
    display: flex;
    gap: 10px;
    margin: 10px 0 20px;
  }
  .subscription-toolbar > .el-input {
    max-width: 300px;
  }
  .subscription-toolbar > .el-select {
    width: 130px;
  }
  .el-pagination {
    justify-content: flex-end;
    margin-top: 20px;
    flex-wrap: wrap;
  }
  .product-form .el-select,
  .product-form .el-input-number {
    width: 100%;
  }
  .form-pair {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 18px;
  }
  @media (max-width: 640px) {
    .subscription-toolbar {
      flex-wrap: wrap;
    }
    .subscription-toolbar > .el-input {
      max-width: none;
      width: 100%;
    }
    .admin-heading {
      gap: 14px;
    }
    .form-pair {
      gap: 12px;
    }
  }
</style>
