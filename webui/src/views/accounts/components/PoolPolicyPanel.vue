<script setup lang="ts">
  /**
   * 账号池熔断策略面板（从 AccountListView.vue 抽离）。
   *
   * 覆盖：路由状态、熔断策略表单、一级/二级号池拖拽看板、错误率排行。
   * 账号列表与全局配置由父组件持有，本组件通过 props 接收、emit 回传变更。
   */
  import { computed, onMounted, reactive, ref, watch } from 'vue';
  import { ElMessage } from 'element-plus';
  import AppIcon from './AppIcon.vue';
  import { accountsApi } from '@/api';
  import { ApiError } from '@/utils/http';
  import type {
    Account,
    AccountPoolPolicySettings,
    AccountPoolStatus,
    AccountSettings,
  } from '@/types/api';
  import { formatNumber, shorten } from '@/utils/format';
  import {
    getProviderDisplayName,
    normalizedPoolTier,
    poolTierLabel,
    poolTierTagType,
    statusOf,
  } from '../utils';

  const props = defineProps<{
    accounts: Account[];
    visibleAccounts: Account[];
    settings: AccountSettings;
  }>();

  const emit = defineEmits<{
    (e: 'settings-updated', settings: AccountSettings): void;
    (e: 'accounts-updated', accounts: Account[]): void;
  }>();

  const poolStatus = ref<AccountPoolStatus | null>(null);
  const poolPolicySaving = ref(false);
  const poolDragAccountId = ref<string | null>(null);
  const poolDropTarget = ref<'primary' | 'secondary' | null>(null);
  const poolUpdatingAccountIds = ref<Record<string, boolean>>({});

  // 策略表单的本地副本，与父组件 settings.poolPolicy 保持同步
  const poolPolicy = reactive<AccountPoolPolicySettings>({ ...props.settings.poolPolicy });
  watch(
    () => props.settings.poolPolicy,
    (value) => {
      Object.assign(poolPolicy, value);
    },
  );

  const primaryPoolAccounts = computed(() =>
    props.visibleAccounts.filter((account) => normalizedPoolTier(account.poolTier) === 'primary'),
  );

  const secondaryPoolAccounts = computed(() =>
    props.visibleAccounts.filter(
      (account) => normalizedPoolTier(account.poolTier) === 'secondary',
    ),
  );

  // statusOf / normalizedPoolTier / poolTierLabel / poolTierTagType 已收敛至 ../utils

  async function loadPoolStatus() {
    try {
      poolStatus.value = await accountsApi.poolStatus();
      if (poolStatus.value?.settings) {
        emit('settings-updated', {
          ...props.settings,
          poolPolicy: poolStatus.value.settings,
        });
      }
    } catch (err) {
      if (err instanceof ApiError) ElMessage.warning(`账号池状态加载失败：${err.message}`);
    }
  }

  async function savePoolPolicy() {
    poolPolicySaving.value = true;
    try {
      const updated = await accountsApi.updateSettings({
        poolPolicy: { ...poolPolicy },
      });
      emit('settings-updated', updated);
      await loadPoolStatus();
      ElMessage.success('账号池策略已保存');
    } catch (err) {
      if (err instanceof ApiError) ElMessage.error(err.message);
    } finally {
      poolPolicySaving.value = false;
    }
  }

  function poolColumnDescription(tier: 'primary' | 'secondary') {
    return tier === 'primary'
      ? '主力账号池，正常情况下优先承接请求'
      : '备用账号池，一级池熔断时自动接管请求';
  }

  function isPoolUpdating(accountId: string) {
    return Boolean(poolUpdatingAccountIds.value[accountId]);
  }

  function setPoolUpdating(accountId: string, value: boolean) {
    poolUpdatingAccountIds.value = {
      ...poolUpdatingAccountIds.value,
      [accountId]: value,
    };
  }

  function onPoolDragStart(event: DragEvent, account: Account) {
    poolDragAccountId.value = account.accountId;
    event.dataTransfer?.setData('text/plain', account.accountId);
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  function onPoolDragEnd() {
    poolDragAccountId.value = null;
    poolDropTarget.value = null;
  }

  function onPoolDragOver(event: DragEvent, tier: 'primary' | 'secondary') {
    event.preventDefault();
    const account = draggedPoolAccount(event);
    if (account && normalizedPoolTier(account.poolTier) !== tier) {
      poolDropTarget.value = tier;
      if (event.dataTransfer) {
        event.dataTransfer.dropEffect = 'move';
      }
    }
  }

  function onPoolDragLeave(tier: 'primary' | 'secondary') {
    if (poolDropTarget.value === tier) {
      poolDropTarget.value = null;
    }
  }

  async function onPoolDrop(event: DragEvent, tier: 'primary' | 'secondary') {
    event.preventDefault();
    const account = draggedPoolAccount(event);
    poolDropTarget.value = null;
    poolDragAccountId.value = null;
    if (
      !account ||
      normalizedPoolTier(account.poolTier) === tier ||
      isPoolUpdating(account.accountId)
    ) {
      return;
    }
    await moveAccountToPool(account, tier);
  }

  function draggedPoolAccount(event: DragEvent) {
    const accountId = event.dataTransfer?.getData('text/plain') || poolDragAccountId.value;
    if (!accountId) return null;
    return props.accounts.find((account) => account.accountId === accountId) ?? null;
  }

  async function moveAccountToPool(account: Account, tier: 'primary' | 'secondary') {
    const previousTier = normalizedPoolTier(account.poolTier);
    setPoolUpdating(account.accountId, true);
    emit(
      'accounts-updated',
      props.accounts.map((item) =>
        item.accountId === account.accountId ? { ...item, poolTier: tier } : item,
      ),
    );
    try {
      await accountsApi.update(account.accountId, { poolTier: tier });
      ElMessage.success(
        `${account.email || shorten(account.accountId, 8, 4)} 已移动到${poolTierLabel(tier)}`,
      );
      await loadPoolStatus();
    } catch (err) {
      emit(
        'accounts-updated',
        props.accounts.map((item) =>
          item.accountId === account.accountId ? { ...item, poolTier: previousTier } : item,
        ),
      );
      if (err instanceof ApiError) ElMessage.error(err.message);
    } finally {
      setPoolUpdating(account.accountId, false);
    }
  }

  // getProviderDisplayName 已收敛至 ../utils

  onMounted(() => {
    loadPoolStatus();
  });
</script>

<template>
  <div class="pool-policy-panel glass-panel">
    <div class="pool-policy-panel__status">
      <div class="panel-header-left">
        <span class="brand-indicator" />
        <div class="panel-title">账号池熔断策略</div>
        <div class="panel-subtitle">
          当前路由：
          <el-tag
            :type="poolStatus?.activeTier === 'secondary' ? 'warning' : 'success'"
            effect="light"
            round
          >
            {{
              poolStatus?.activeTier === 'secondary'
                ? '二级号池'
                : poolStatus?.activeTier === 'all'
                  ? '全部账号'
                  : '一级号池'
            }}
          </el-tag>
          <span class="pool-status-text">
            一级池错误率 {{ poolStatus?.primaryFailureRatePercent ?? 0 }}% （{{
              poolStatus?.primaryFailures ?? 0
            }}/{{ poolStatus?.primaryRequests ?? 0 }}）
          </span>
          <span v-if="poolStatus?.lastRetestAt" class="pool-status-text">
            最近复测成功率 {{ poolStatus?.lastRetestRecoveryRatePercent ?? 0 }}%
          </span>
          <span v-if="poolStatus?.retestRunning" class="pool-status-text is-running"> 正在复测 </span>
        </div>
      </div>
      <el-button size="small" @click="loadPoolStatus">
        <template #icon>
          <AppIcon name="refresh" width="12" height="12" class="btn-icon" />
        </template>
        刷新状态
      </el-button>
    </div>

    <el-form class="pool-policy-form" label-position="top" size="small">
      <el-row :gutter="16">
        <el-col :xs="24" :sm="12" :md="6" :lg="3">
          <el-form-item label="启用熔断">
            <el-switch v-model="poolPolicy.enabled" />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6" :lg="3">
          <el-form-item label="自动权重">
            <el-switch v-model="poolPolicy.autoWeightEnabled" />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6" :lg="3">
          <el-form-item label="窗口(分钟)">
            <el-input-number
              v-model="poolPolicy.windowMinutes"
              :min="1"
              :max="1440"
              controls-position="right"
            />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6" :lg="3">
          <el-form-item label="最小样本">
            <el-input-number
              v-model="poolPolicy.minSampleSize"
              :min="1"
              :max="100000"
              controls-position="right"
            />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6" :lg="3">
          <el-form-item label="复测阈值%">
            <el-input-number
              v-model="poolPolicy.retestThresholdPercent"
              :min="0"
              :max="100"
              controls-position="right"
            />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6" :lg="3">
          <el-form-item label="切二级%">
            <el-input-number
              v-model="poolPolicy.failoverThresholdPercent"
              :min="0"
              :max="100"
              controls-position="right"
            />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6" :lg="3">
          <el-form-item label="恢复成功率%">
            <el-input-number
              v-model="poolPolicy.recoveryThresholdPercent"
              :min="0"
              :max="100"
              controls-position="right"
            />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6" :lg="3">
          <el-form-item label="复测间隔">
            <el-input-number
              v-model="poolPolicy.retestIntervalMinutes"
              :min="1"
              :max="1440"
              controls-position="right"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <div class="pool-policy-footer">
        <div class="info-alert">
          <AppIcon name="info" width="14" height="14" class="info-alert-icon" />
          <span>
            当一级池错误率达到复测阈值时会全量测试账号；达到切二级阈值时临时将请求转到二级号池，并持续复测所有账号，直到全账号复测成功率达到恢复成功率阈值。
          </span>
        </div>
        <el-button type="primary" :loading="poolPolicySaving" @click="savePoolPolicy">
          保存账号池策略
        </el-button>
      </div>
    </el-form>

    <div class="pool-drag-board">
      <div
        class="pool-drag-column"
        :class="{
          'is-drop-target': poolDropTarget === 'primary',
          'is-active-route': poolStatus?.activeTier === 'primary',
        }"
        @dragover="(event) => onPoolDragOver(event, 'primary')"
        @dragleave="onPoolDragLeave('primary')"
        @drop="(event) => onPoolDrop(event, 'primary')"
      >
        <div class="pool-drag-column__head">
          <div>
            <div class="pool-drag-column__title">
              <AppIcon name="star" width="16" height="16" class="pool-tier-icon is-primary" />
              一级号池
              <el-tag
                v-if="poolStatus?.activeTier === 'primary'"
                size="small"
                type="success"
                effect="light"
                round
              >
                当前路由
              </el-tag>
            </div>
            <div class="pool-drag-column__desc">{{ poolColumnDescription('primary') }}</div>
          </div>
          <strong class="mono">{{ primaryPoolAccounts.length }}</strong>
        </div>

        <div v-if="primaryPoolAccounts.length === 0" class="pool-drag-empty">
          拖入账号作为一级号池
        </div>
        <div
          v-for="account in primaryPoolAccounts"
          :key="account.accountId"
          class="pool-account-card"
          :class="{
            'is-dragging': poolDragAccountId === account.accountId,
            'is-updating': isPoolUpdating(account.accountId),
          }"
          draggable="true"
          @dragstart="(event) => onPoolDragStart(event, account)"
          @dragend="onPoolDragEnd"
        >
          <div class="pool-account-card__drag-handle">
            <AppIcon name="grip" width="12" height="12" />
          </div>
          <div class="pool-account-card__info-wrapper">
            <div class="pool-account-card__main">
              <span class="pool-account-card__name" :title="account.email || account.accountId">
                {{ account.email || shorten(account.accountId, 10, 6) }}
              </span>
              <span class="pool-account-card__id mono">{{
                shorten(account.accountId, 10, 4)
              }}</span>
            </div>
            <div class="pool-account-card__meta">
              <el-tag size="small" effect="plain">
                {{ getProviderDisplayName(account.provider) }}
              </el-tag>
              <el-tag size="small" :type="statusOf(account).tone" effect="light">
                {{ statusOf(account).label }}
              </el-tag>
              <span class="meta-item">权重: <strong>{{ account.poolWeight ?? 100 }}</strong></span>
              <span class="meta-item">错误:
                <strong class="text-danger">{{ formatNumber(account.errorCount) }}</strong></span>
            </div>
          </div>
        </div>
      </div>

      <div
        class="pool-drag-column is-secondary"
        :class="{
          'is-drop-target': poolDropTarget === 'secondary',
          'is-active-route': poolStatus?.activeTier === 'secondary',
        }"
        @dragover="(event) => onPoolDragOver(event, 'secondary')"
        @dragleave="onPoolDragLeave('secondary')"
        @drop="(event) => onPoolDrop(event, 'secondary')"
      >
        <div class="pool-drag-column__head">
          <div>
            <div class="pool-drag-column__title">
              <AppIcon name="lock" width="16" height="16" class="pool-tier-icon is-warning" />
              二级号池
              <el-tag
                v-if="poolStatus?.activeTier === 'secondary'"
                size="small"
                type="warning"
                effect="light"
                round
              >
                当前接管
              </el-tag>
            </div>
            <div class="pool-drag-column__desc">{{ poolColumnDescription('secondary') }}</div>
          </div>
          <strong class="mono">{{ secondaryPoolAccounts.length }}</strong>
        </div>

        <div v-if="secondaryPoolAccounts.length === 0" class="pool-drag-empty">
          拖入账号作为熔断备用池
        </div>
        <div
          v-for="account in secondaryPoolAccounts"
          :key="account.accountId"
          class="pool-account-card"
          :class="{
            'is-dragging': poolDragAccountId === account.accountId,
            'is-updating': isPoolUpdating(account.accountId),
          }"
          draggable="true"
          @dragstart="(event) => onPoolDragStart(event, account)"
          @dragend="onPoolDragEnd"
        >
          <div class="pool-account-card__drag-handle">
            <AppIcon name="grip" width="12" height="12" />
          </div>
          <div class="pool-account-card__info-wrapper">
            <div class="pool-account-card__main">
              <span class="pool-account-card__name" :title="account.email || account.accountId">
                {{ account.email || shorten(account.accountId, 10, 6) }}
              </span>
              <span class="pool-account-card__id mono">{{
                shorten(account.accountId, 10, 4)
              }}</span>
            </div>
            <div class="pool-account-card__meta">
              <el-tag size="small" effect="plain">
                {{ getProviderDisplayName(account.provider) }}
              </el-tag>
              <el-tag size="small" :type="statusOf(account).tone" effect="light">
                {{ statusOf(account).label }}
              </el-tag>
              <span class="meta-item">权重: <strong>{{ account.poolWeight ?? 100 }}</strong></span>
              <span class="meta-item">错误:
                <strong class="text-danger">{{ formatNumber(account.errorCount) }}</strong></span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="poolStatus?.failureRanking?.length" class="failure-ranking">
      <div class="failure-ranking__title">账号错误率排行</div>
      <div class="failure-ranking__list">
        <div
          v-for="item in poolStatus.failureRanking.slice(0, 6)"
          :key="item.accountId"
          class="failure-ranking__item"
        >
          <div class="failure-ranking__id">
            <span class="item-id mono">{{ shorten(item.accountId, 8, 4) }}</span>
            <el-tag :type="poolTierTagType(item.poolTier)" size="small" effect="light" round>
              {{ poolTierLabel(item.poolTier) }}
            </el-tag>
          </div>
          <div class="item-stats">
            <span class="failure-pct" :class="{ 'is-zero': item.failureRatePercent === 0 }">
              {{ item.failureRatePercent }}%
            </span>
            <span class="text-secondary item-stat">
              ({{ item.failures }}/{{ item.requests }})
            </span>
            <span class="text-secondary item-stat item-stat--weight">
              W:{{ item.effectiveWeight }}
            </span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
  .pool-policy-panel {
    display: grid;
    gap: 16px;
    padding: 20px 24px;
    margin-bottom: 24px;
  }

  .pool-policy-panel__status {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
    padding-bottom: 12px;
    border-bottom: 1px solid var(--kp-divider);
  }

  .panel-header-left {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;

    .brand-indicator {
      width: 4px;
      height: 16px;
      border-radius: 99px;
      background: var(--kp-primary);
    }

    .panel-title {
      font-size: 15px;
      font-weight: 800;
      color: var(--kp-text);
      line-height: 1;
    }

    .panel-subtitle {
      margin-top: 0;
      display: flex;
      align-items: center;
      gap: 10px;
      color: var(--kp-text-secondary);
      font-size: 12.5px;

      .pool-status-text strong {
        color: var(--kp-danger);
      }

      .is-running {
        color: var(--el-color-warning);
        font-weight: 700;
        animation: pulse-warn 1.5s infinite;
      }
    }
  }

  @keyframes pulse-warn {
    0% {
      opacity: 0.6;
    }
    50% {
      opacity: 1;
    }
    100% {
      opacity: 0.6;
    }
  }

  .pool-policy-form {
    :deep(.el-form-item) {
      margin-bottom: 8px;
    }

    :deep(.el-input-number) {
      width: 100%;
    }
  }

  .pool-policy-footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-top: 12px;
    padding-top: 12px;
    border-top: 1px dashed var(--kp-divider);

    .info-alert {
      display: flex;
      align-items: center;
      gap: 8px;
      flex: 1;

      &-icon {
        color: var(--kp-primary);
        flex-shrink: 0;
      }

      span {
        font-size: 12px;
        color: var(--kp-text-secondary);
        line-height: 1.4;
      }
    }
  }

  .pool-drag-board {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
  }

  @media (max-width: 768px) {
    .pool-drag-board {
      grid-template-columns: 1fr;
    }
  }

  .btn-icon {
    margin-right: 4px;
  }

  .pool-tier-icon {
    margin-right: 4px;

    &.is-primary {
      color: var(--kp-primary);
    }

    &.is-warning {
      color: var(--kp-warning);
    }
  }

  .pool-drag-column {
    min-height: 240px;
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 16px;
    border: 1px solid rgba(54, 192, 138, 0.18);
    border-radius: var(--kp-radius-sm);
    background: linear-gradient(180deg, rgba(54, 192, 138, 0.04), rgba(255, 255, 255, 0.6));
    transition: all 0.25s ease;

    &.is-secondary {
      border-color: rgba(245, 158, 11, 0.2);
      background: linear-gradient(180deg, rgba(245, 158, 11, 0.04), rgba(255, 255, 255, 0.6));
    }

    &.is-active-route {
      border-color: rgba(91, 141, 239, 0.4);
      box-shadow: 0 8px 24px rgba(91, 141, 239, 0.08);
    }

    &.is-drop-target {
      border-color: var(--kp-primary);
      box-shadow: 0 0 0 3px rgba(91, 141, 239, 0.12);
      transform: translateY(-2px);
    }
  }

  .pool-drag-column__head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 10px;
    padding-bottom: 10px;
    border-bottom: 1px solid var(--kp-divider);

    strong {
      min-width: 34px;
      text-align: right;
      color: var(--kp-text);
      font-size: 24px;
      line-height: 1;
    }
  }

  .pool-drag-column__title {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--kp-text);
    font-size: 14.5px;
    font-weight: 800;
  }

  .pool-drag-column__desc {
    margin-top: 4px;
    color: var(--kp-text-secondary);
    font-size: 12px;
  }

  .pool-drag-empty {
    display: grid;
    place-items: center;
    min-height: 120px;
    border: 2px dashed rgba(15, 23, 42, 0.12);
    border-radius: var(--kp-radius-sm);
    color: var(--kp-text-muted);
    font-size: 12px;
    background: rgba(255, 255, 255, 0.35);
  }

  .pool-account-card {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    padding: 12px;
    border: 1px solid rgba(15, 23, 42, 0.08);
    border-radius: 8px;
    background: rgba(255, 255, 255, 0.75);
    cursor: grab;
    user-select: none;
    transition: all 0.2s cubic-bezier(0.25, 0.8, 0.25, 1);

    &__drag-handle {
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--kp-text-muted);
      cursor: grab;
      margin-top: 4px;
      flex-shrink: 0;
      opacity: 0.4;
      transition: opacity 0.2s ease;
    }

    &:hover {
      border-color: rgba(91, 141, 239, 0.36);
      box-shadow: 0 6px 14px rgba(15, 23, 42, 0.06);
      transform: translateY(-1.5px);
      background: #ffffff;

      .pool-account-card__drag-handle {
        opacity: 0.8;
        color: var(--kp-primary);
      }
    }

    &:active {
      cursor: grabbing;
      .pool-account-card__drag-handle {
        cursor: grabbing;
      }
    }

    &.is-dragging {
      opacity: 0.5;
      border-style: dashed;
    }

    &.is-updating {
      pointer-events: none;
      opacity: 0.6;
    }

    &__info-wrapper {
      flex: 1;
      min-width: 0;
      display: grid;
      gap: 6px;
    }

    &__main {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      min-width: 0;
    }

    &__name {
      min-width: 0;
      overflow: hidden;
      color: var(--kp-text);
      font-size: 13px;
      font-weight: 700;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &__id {
      flex: 0 0 auto;
      color: var(--kp-text-muted);
      font-size: 11px;
    }

    &__meta {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 6px;
      color: var(--kp-text-secondary);
      font-size: 11px;

      .meta-item {
        color: var(--kp-text-secondary);

        strong {
          color: var(--kp-text);
        }
        .text-danger {
          color: var(--kp-danger) !important;
        }
      }
    }
  }

  .failure-ranking {
    display: grid;
    gap: 10px;
    padding-top: 16px;
    border-top: 1px solid var(--kp-divider);

    &__title {
      color: var(--kp-text-secondary);
      font-size: 12.5px;
      font-weight: 700;
      display: flex;
      align-items: center;
      gap: 6px;

      &::before {
        content: '';
        width: 3px;
        height: 12px;
        background-color: var(--kp-danger);
        border-radius: 4px;
      }
    }

    &__list {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
      gap: 10px;
    }

    &__item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 12px;
      border: 1px solid var(--kp-divider);
      border-radius: 8px;
      background: rgba(255, 255, 255, 0.45);
      font-size: 12px;
      transition: all 0.2s ease;

      &:hover {
        background: #ffffff;
        border-color: rgba(239, 91, 107, 0.24);
        box-shadow: 0 4px 10px rgba(15, 23, 42, 0.04);
        transform: translateY(-1px);
      }

      .failure-ranking__id {
        display: flex;
        align-items: center;
        gap: 8px;
      }

      .item-id {
        font-weight: 600;
        color: var(--kp-text);
      }

      .item-stats {
        display: flex;
        align-items: center;
        gap: 6px;
        color: var(--kp-text-secondary);
      }

      .item-stat {
        font-size: 11px;

        &--weight {
          margin-left: 2px;
        }
      }

      .failure-pct {
        font-weight: 700;
        color: var(--kp-danger);

        &.is-zero {
          color: var(--kp-success);
        }
      }
    }
  }
</style>
