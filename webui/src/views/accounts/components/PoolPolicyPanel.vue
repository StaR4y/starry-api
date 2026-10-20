<script setup lang="ts">
  import { t } from '@/i18n';
  import { computed, onMounted, ref } from 'vue';
  import { Monitor, Refresh } from '@element-plus/icons-vue';
  import { ElMessage } from 'element-plus';
  import { accountsApi } from '@/api';
  import { ApiError } from '@/utils/http';
  import type {
    AccountPoolStatus,
    AccountSettings,
    ModelPoolCircuitState,
    ModelPoolCircuitStatus,
  } from '@/types/api';
  import { formatDateTime, formatNumber } from '@/utils/format';

  const props = defineProps<{
    settings: AccountSettings;
  }>();

  const emit = defineEmits<{
    (e: 'settings-updated', settings: AccountSettings): void;
  }>();

  const poolStatus = ref<AccountPoolStatus | null>(null);
  const refreshing = ref(false);
  const snapshotError = ref('');
  const generatedAt = ref(new Date().toISOString());

  interface TierSnapshot {
    accountCount: number;
    availableAccountCount: number | null;
    requests: number;
    failures: number;
    failureRatePercent: number;
  }

  interface MonitorGroup {
    key: string;
    provider: string;
    modelFamily: string;
    representativeModel: string | null;
    state: ModelPoolCircuitState;
    activeTier: 'primary' | 'secondary' | string;
    primary: TierSnapshot;
    secondary: TierSnapshot;
    windowStartedAt: string | null;
    lastTransitionAt: string | null;
    openedAt: string | null;
    nextProbeAt: string | null;
    lastOutcomeAt: string | null;
    halfOpenInFlight: number;
    halfOpenCompleted: number;
    halfOpenSucceeded: number;
  }

  function count(value: number | null | undefined) {
    return Math.max(0, Number.isFinite(value) ? Number(value) : 0);
  }

  function nativeTier(
    accountCount: number,
    requests: number,
    failures: number,
    failureRatePercent: number,
  ): TierSnapshot {
    return {
      accountCount: count(accountCount),
      availableAccountCount: null,
      requests: count(requests),
      failures: count(failures),
      failureRatePercent: Math.min(100, count(failureRatePercent)),
    };
  }

  function nativeGroup(item: ModelPoolCircuitStatus): MonitorGroup {
    return {
      key: item.key,
      provider: item.provider,
      modelFamily: item.modelFamily,
      representativeModel: item.representativeModel,
      state: item.state,
      activeTier: item.activeTier,
      primary: nativeTier(
        item.primaryAccountCount,
        item.primaryRequests,
        item.primaryFailures,
        item.primaryFailureRatePercent,
      ),
      secondary: nativeTier(
        item.secondaryAccountCount,
        item.secondaryRequests,
        item.secondaryFailures,
        item.secondaryFailureRatePercent,
      ),
      windowStartedAt: item.windowStartedAt,
      lastTransitionAt: item.lastTransitionAt,
      openedAt: item.openedAt,
      nextProbeAt: item.nextProbeAt,
      lastOutcomeAt: item.lastOutcomeAt,
      halfOpenInFlight: count(item.halfOpenInFlight),
      halfOpenCompleted: count(item.halfOpenCompleted),
      halfOpenSucceeded: count(item.halfOpenSucceeded),
    };
  }

  const groups = computed<MonitorGroup[]>(() => {
    if (!poolStatus.value) return [];
    const values = (poolStatus.value.modelPools ?? []).map(nativeGroup);
    const stateOrder: Record<ModelPoolCircuitState, number> = {
      OPEN: 0,
      HALF_OPEN: 1,
      CLOSED: 2,
    };
    return [...values].sort(
      (left, right) =>
        stateOrder[left.state] - stateOrder[right.state] ||
        left.provider.localeCompare(right.provider),
    );
  });

  const snapshot = computed(() => ({
    generatedAt: generatedAt.value,
    activeTier: poolStatus.value?.activeTier ?? 'all',
    lastEvaluatedAt: poolStatus.value?.lastEvaluatedAt ?? null,
    groups: groups.value,
  }));

  const summary = computed(() => ({
    groupCount: snapshot.value.groups.length,
    accountCount: count(poolStatus.value?.accountCount),
    availableCount: count(poolStatus.value?.availableAccountCount),
    abnormalGroupCount: snapshot.value.groups.filter((group) => group.state !== 'CLOSED').length,
  }));

  const failoverThreshold = computed(
    () =>
      poolStatus.value?.settings.failoverThresholdPercent ??
      props.settings.poolPolicy.failoverThresholdPercent ??
      70,
  );

  function activeTierLabel(value: string) {
    if (value === 'secondary') return t('二级池接管');
    if (value === 'primary') return t('一级池');
    if (value === 'mixed') return t('模型池混合路由');
    return t('全部账号');
  }

  function activeTierType(value: string) {
    if (value === 'secondary') return 'warning';
    if (value === 'primary') return 'success';
    if (value === 'mixed') return 'primary';
    return 'info';
  }

  function stateMeta(state: ModelPoolCircuitState) {
    if (state === 'OPEN')
      return {
        get label() {
          return t('已熔断');
        },
        type: 'danger' as const,
      };
    if (state === 'HALF_OPEN')
      return {
        get label() {
          return t('半开探测');
        },
        type: 'warning' as const,
      };
    return {
      get label() {
        return t('闭合正常');
      },
      type: 'success' as const,
    };
  }

  function tierAccountLabel(tier: TierSnapshot) {
    if (tier.availableAccountCount === null) return `${tier.accountCount}`;
    return `${tier.availableAccountCount}/${tier.accountCount}`;
  }

  function activeTierOf(group: MonitorGroup) {
    return group.activeTier === 'secondary' ? group.secondary : group.primary;
  }

  function healthProgressStatus(group: MonitorGroup) {
    return activeTierOf(group).failureRatePercent >= failoverThreshold.value
      ? ('exception' as const)
      : undefined;
  }

  async function loadPoolStatus() {
    if (refreshing.value) return;
    refreshing.value = true;
    try {
      poolStatus.value = await accountsApi.poolStatus();
      snapshotError.value = '';
      generatedAt.value = new Date().toISOString();
      if (poolStatus.value.settings) {
        emit('settings-updated', {
          ...props.settings,
          poolPolicy: poolStatus.value.settings,
        });
      }
    } catch (err) {
      snapshotError.value = err instanceof ApiError ? err.message : t('服务暂时不可用');
      if (poolStatus.value) {
        ElMessage.warning(t('分级熔断快照刷新失败：{p0}', { p0: snapshotError.value }));
      }
    } finally {
      refreshing.value = false;
    }
  }

  onMounted(loadPoolStatus);
</script>

<template>
  <section v-loading="refreshing" class="pool-monitor" :aria-label="t('账号池分级熔断监控')">
    <header class="pool-monitor__header">
      <div class="monitor-heading">
        <el-icon class="monitor-heading__icon"><Monitor /></el-icon>
        <div>
          <div class="monitor-heading__title">{{ t('模型路由健康快照') }}</div>
          <div v-if="poolStatus" class="monitor-heading__meta">
            <span>{{ t('数据口径：模型池原生快照') }}</span>
            <span>{{ t('更新于') }} {{ formatDateTime(snapshot.generatedAt) }}</span>
            <span v-if="snapshot.lastEvaluatedAt">
              {{ t('评估于') }} {{ formatDateTime(snapshot.lastEvaluatedAt) }}
            </span>
          </div>
        </div>
      </div>
      <el-button :icon="Refresh" :loading="refreshing" @click="loadPoolStatus">
        {{ t('刷新快照') }}
      </el-button>
    </header>

    <el-skeleton v-if="refreshing && !poolStatus" class="snapshot-state" :rows="6" animated />

    <el-result
      v-else-if="snapshotError && !poolStatus"
      class="snapshot-state"
      icon="warning"
      :title="t('熔断快照不可用')"
      :sub-title="snapshotError"
    >
      <template #extra>
        <el-button type="primary" :loading="refreshing" @click="loadPoolStatus">
          {{
            t('重新加载')
          }}
        </el-button>
      </template>
    </el-result>

    <template v-else-if="poolStatus">
      <el-alert
        v-if="snapshotError"
        type="warning"
        :title="t('快照刷新失败，当前显示上次成功结果：{p0}', { p0: snapshotError })"
        :closable="false"
        show-icon
      />

      <div class="monitor-summary">
        <div class="summary-item">
          <span class="summary-item__label">{{ t('当前路由') }}</span>
          <el-tag :type="activeTierType(snapshot.activeTier)" effect="light" round>
            {{ activeTierLabel(snapshot.activeTier) }}
          </el-tag>
        </div>
        <div class="summary-item">
          <span class="summary-item__label">{{ t('供应商 / 模型族') }}</span>
          <strong class="mono">{{ summary.groupCount }}</strong>
        </div>
        <div class="summary-item">
          <span class="summary-item__label">{{ t('可用账号') }}</span>
          <strong class="mono">{{ summary.availableCount }} / {{ summary.accountCount }}</strong>
        </div>
        <div class="summary-item">
          <span class="summary-item__label">{{ t('异常分组') }}</span>
          <strong class="mono" :class="{ 'is-danger': summary.abnormalGroupCount > 0 }">
            {{ summary.abnormalGroupCount }}
          </strong>
        </div>
      </div>

      <div class="group-list__head" aria-hidden="true">
        <span>{{ t('模型供应商 / 模型族') }}</span>
        <span>{{ t('分级账号池') }}</span>
        <span>{{ t('窗口健康指标') }}</span>
      </div>

      <div v-if="snapshot.groups.length" class="group-list">
        <article v-for="group in snapshot.groups" :key="group.key" class="group-row">
          <div class="group-identity">
            <div class="group-identity__vendor">{{ group.provider }}</div>
            <div class="group-identity__family">
              <span>{{ group.modelFamily }}</span>
              <el-tag :type="stateMeta(group.state).type" size="small" effect="light" round>
                {{ stateMeta(group.state).label }}
              </el-tag>
            </div>
            <div v-if="group.representativeModel" class="group-identity__models mono">
              {{ group.representativeModel }}
            </div>
          </div>

          <div class="tier-snapshots">
            <div class="tier-snapshot">
              <div class="tier-snapshot__head">
                <span class="tier-dot is-primary" />
                <span>{{ t('一级池') }}</span>
                <el-tag
                  v-if="group.activeTier === 'primary'"
                  size="small"
                  type="success"
                  effect="plain"
                >
                  {{ t('当前') }}
                </el-tag>
                <strong class="mono">{{ tierAccountLabel(group.primary) }}</strong>
              </div>
              <div class="tier-snapshot__meta">
                {{ formatNumber(group.primary.requests) }} {{ t('请求 ·') }}
                {{ formatNumber(group.primary.failures) }} {{ t('失败 ·') }}
                {{ group.primary.failureRatePercent }}%
              </div>
            </div>
            <div class="tier-snapshot">
              <div class="tier-snapshot__head">
                <span class="tier-dot is-secondary" />
                <span>{{ t('二级池') }}</span>
                <el-tag
                  v-if="group.activeTier === 'secondary'"
                  size="small"
                  type="warning"
                  effect="plain"
                >
                  {{ t('当前') }}
                </el-tag>
                <strong class="mono">{{ tierAccountLabel(group.secondary) }}</strong>
              </div>
              <div class="tier-snapshot__meta">
                {{ formatNumber(group.secondary.requests) }} {{ t('请求 ·') }}
                {{ formatNumber(group.secondary.failures) }} {{ t('失败 ·') }}
                {{ group.secondary.failureRatePercent }}%
              </div>
            </div>
          </div>

          <div class="health-metrics">
            <div class="health-metrics__rate">
              <strong class="mono">{{ activeTierOf(group).failureRatePercent }}%</strong>
              <span> {{ activeTierLabel(group.activeTier) }}{{ t('窗口错误率') }} </span>
            </div>
            <el-progress
              :percentage="activeTierOf(group).failureRatePercent"
              :show-text="false"
              :stroke-width="6"
              :status="healthProgressStatus(group)"
            />
            <div class="health-metrics__detail">
              {{ formatNumber(activeTierOf(group).failures) }} {{ t('失败 /') }}
              {{ formatNumber(activeTierOf(group).requests) }} {{ t('请求 · 熔断阈值') }}
              {{ failoverThreshold }}%
            </div>
            <div v-if="group.state === 'HALF_OPEN'" class="health-metrics__detail">
              {{ t('探测') }} {{ group.halfOpenSucceeded }}/{{ group.halfOpenCompleted }}
              {{ t('成功 ·') }} {{ group.halfOpenInFlight }} {{ t('进行中') }}
            </div>
            <div v-else-if="group.lastTransitionAt" class="health-metrics__detail">
              {{ t('状态变更') }} {{ formatDateTime(group.lastTransitionAt) }}
            </div>
            <div v-if="group.nextProbeAt" class="health-metrics__detail">
              {{ t('下次探测') }} {{ formatDateTime(group.nextProbeAt) }}
            </div>
          </div>
        </article>
      </div>

      <el-empty v-else :image-size="84" :description="t('暂无可监控的模型路由分组')" />
    </template>
  </section>
</template>

<style scoped lang="scss">
  .pool-monitor {
    display: grid;
    gap: 18px;
    min-width: 0;
  }

  .pool-monitor__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    min-width: 0;
  }

  .snapshot-state {
    min-height: 320px;
  }

  .monitor-heading {
    display: flex;
    align-items: center;
    gap: 12px;
    min-width: 0;

    &__icon {
      flex: 0 0 auto;
      width: 34px;
      height: 34px;
      border-radius: 6px;
      background: rgba(91, 141, 239, 0.1);
      color: var(--kp-primary);
      font-size: 18px;
    }

    &__title {
      color: var(--kp-text);
      font-size: 15px;
      font-weight: 800;
    }

    &__meta {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 14px;
      margin-top: 4px;
      color: var(--kp-text-muted);
      font-size: 11.5px;
    }
  }

  .monitor-summary {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    border-block: 1px solid var(--kp-divider);
    background: rgba(15, 23, 42, 0.018);
  }

  .summary-item {
    display: flex;
    min-height: 76px;
    flex-direction: column;
    align-items: flex-start;
    justify-content: center;
    gap: 7px;
    min-width: 0;
    padding: 14px 18px;

    & + & {
      border-left: 1px solid var(--kp-divider);
    }

    &__label {
      color: var(--kp-text-secondary);
      font-size: 12px;
    }

    strong {
      color: var(--kp-text);
      font-size: 20px;

      &.is-danger {
        color: var(--kp-danger);
      }
    }
  }

  .group-list__head,
  .group-row {
    display: grid;
    grid-template-columns: minmax(170px, 1.15fr) minmax(300px, 2fr) minmax(180px, 1fr);
    gap: 22px;
    align-items: center;
  }

  .group-list__head {
    padding: 0 14px;
    color: var(--kp-text-muted);
    font-size: 11.5px;
    font-weight: 700;
  }

  .group-list {
    border-top: 1px solid var(--kp-divider);
  }

  .group-row {
    min-height: 116px;
    padding: 18px 14px;
    border-bottom: 1px solid var(--kp-divider);

    &:hover {
      background: rgba(91, 141, 239, 0.025);
    }
  }

  .group-identity {
    min-width: 0;

    &__vendor {
      overflow: hidden;
      color: var(--kp-text);
      font-size: 15px;
      font-weight: 800;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &__family {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 8px;
      margin-top: 6px;
      color: var(--kp-text-secondary);
      font-size: 12px;
    }

    &__models {
      overflow: hidden;
      margin-top: 6px;
      color: var(--kp-text-muted);
      font-size: 11px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .tier-snapshots {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 18px;
    min-width: 0;
  }

  .tier-snapshot {
    min-width: 0;

    &__head {
      display: flex;
      align-items: center;
      gap: 7px;
      color: var(--kp-text-secondary);
      font-size: 12px;

      strong {
        margin-left: auto;
        color: var(--kp-text);
        font-size: 15px;
      }
    }

    &__meta {
      overflow: hidden;
      margin-top: 7px;
      color: var(--kp-text-muted);
      font-size: 11px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .tier-dot {
    width: 7px;
    height: 7px;
    flex: 0 0 auto;
    border-radius: 50%;

    &.is-primary {
      background: var(--kp-success);
    }

    &.is-secondary {
      background: var(--kp-warning);
    }
  }

  .health-metrics {
    min-width: 0;

    &__rate {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 8px;
      margin-bottom: 7px;

      strong {
        color: var(--kp-text);
        font-size: 16px;
      }

      span {
        color: var(--kp-text-secondary);
        font-size: 11px;
      }
    }

    &__detail {
      overflow: hidden;
      margin-top: 6px;
      color: var(--kp-text-muted);
      font-size: 11px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  @media (max-width: 960px) {
    .monitor-summary {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .summary-item:nth-child(3) {
      border-left: none;
      border-top: 1px solid var(--kp-divider);
    }

    .summary-item:nth-child(4) {
      border-top: 1px solid var(--kp-divider);
    }

    .group-list__head {
      display: none;
    }

    .group-row {
      grid-template-columns: 1fr;
      gap: 16px;
    }

    .health-metrics {
      grid-column: auto;
    }
  }

  @media (max-width: 640px) {
    .pool-monitor__header {
      align-items: flex-start;
      flex-direction: column;
    }

    .pool-monitor__header .el-button {
      width: 100%;
    }

    .monitor-summary {
      grid-template-columns: 1fr;
    }

    .summary-item + .summary-item {
      border-top: 1px solid var(--kp-divider);
      border-left: none;
    }

    .group-row {
      grid-template-columns: 1fr;
      gap: 16px;
      padding-inline: 4px;
    }

    .health-metrics {
      grid-column: auto;
    }

    .tier-snapshots {
      gap: 12px;
    }
  }

  @media (max-width: 520px) {
    .tier-snapshots {
      grid-template-columns: 1fr;
    }
  }
</style>
