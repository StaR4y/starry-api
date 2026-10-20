<script setup lang="ts">
  import { t } from '@/i18n';
  /**
   * 账号池设置。
   *
   * 集中管理账号级运行策略：
   * - 账号路由策略：账号选择、会话亲和与失败换号上限
   * - 账号池分级熔断策略（试验性）：开关 + 模型路由健康快照监控
   * - 停用账号定期复检：调度、并发与恢复策略
   *
   * 面板组件仍位于 views/accounts/components/ 下，此处只做编排。
   */
  import { computed, onMounted, ref } from 'vue';
  import { ElMessage, ElMessageBox, ElCollapseTransition } from 'element-plus';
  import { Monitor } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import AppIcon from '../accounts/components/AppIcon.vue';
  import DisabledSchedulePanel from '../accounts/components/DisabledSchedulePanel.vue';
  import PoolPolicyPanel from '../accounts/components/PoolPolicyPanel.vue';
  import RoutingPolicyPanel from '../accounts/components/RoutingPolicyPanel.vue';
  import BatchTestResultDialog from '../accounts/components/BatchTestResultDialog.vue';
  import TestDetailDialog from '../accounts/components/TestDetailDialog.vue';
  import { accountsApi } from '@/api';
  import { ApiError } from '@/utils/http';
  import type { AccountSettings, TestAccountItem, TestAccountsResult } from '@/types/api';

  const loadState = ref<'loading' | 'ready' | 'error'>('loading');
  const loadError = ref('');
  const poolPolicySaving = ref(false);
  const circuitExpanded = ref(true);

  const accountSettings = ref<AccountSettings>({
    gptFastModeEnabled: false,
    disabledAccountScheduledTest: {
      enabled: false,
      scheduleMode: 'interval',
      intervalMinutes: 60,
      cronExpression: '0 0 * * * *',
      timezone: 'Asia/Shanghai',
      initialDelayMinutes: 5,
      maxConcurrency: 2,
      maxAccountsPerRun: 20,
      model: 'auto',
      prompt: '',
      updateStatus: true,
      autoRunWhenNoAvailableAccounts: false,
      includeDisabled: true,
      includeSuspended: true,
      includeQuotaExhausted: false,
      minAgeMinutes: 10,
      providers: [],
    },
    poolPolicy: {
      enabled: false,
      windowMinutes: 5,
      minSampleSize: 10,
      retestThresholdPercent: 50,
      failoverThresholdPercent: 70,
      recoveryThresholdPercent: 70,
      retestIntervalMinutes: 5,
      retestMaxConcurrency: 4,
      autoWeightEnabled: true,
      circuitEpoch: null,
      activationStartedAt: null,
    },
    routingPolicy: {
      isolateCodexPool: true,
      gptUsesCodexAccounts: false,
      bindSessionOnAdmission: false,
      bindSessionByApiKey: false,
      codexMaxRetryAccounts: 1,
      openAiMaxRetryAccounts: 3,
      deepSeekMaxRetryAccounts: 3,
      anthropicMaxRetryAccounts: 2,
      kiroMaxRetryAccounts: 2,
    },
  });

  const poolPolicyEnabled = computed(() => accountSettings.value.poolPolicy.enabled);

  async function loadAccountSettings() {
    loadState.value = 'loading';
    loadError.value = '';
    try {
      accountSettings.value = await accountsApi.settings();
      loadState.value = 'ready';
    } catch (err) {
      loadError.value = err instanceof ApiError ? err.message : t('服务暂时不可用');
      loadState.value = 'error';
    }
  }

  async function togglePoolPolicy(value: string | number | boolean) {
    const enabled = Boolean(value);
    if (enabled) {
      const confirmed = await ElMessageBox.confirm(
        t(
          '该试验性策略可能出现误熔断、路由抖动或监控口径变化。启用后此处将展示模型路由健康快照，确认继续？',
        ),
        t('启用账号池分级熔断策略'),
        {
          get confirmButtonText() {
            return t('确认启用');
          },
          get cancelButtonText() {
            return t('取消');
          },
          type: 'warning',
        },
      )
        .then(() => true)
        .catch(() => false);
      if (!confirmed) return;
    }

    poolPolicySaving.value = true;
    try {
      accountSettings.value = await accountsApi.updateSettings({
        poolPolicy: { enabled },
      });
      loadState.value = 'ready';
      ElMessage.success(
        accountSettings.value.poolPolicy.enabled
          ? t('已启用分级熔断策略')
          : t('已关闭分级熔断策略'),
      );
    } catch (err) {
      if (err instanceof ApiError) ElMessage.error(err.message);
    } finally {
      poolPolicySaving.value = false;
    }
  }

  function handlePoolSettingsUpdated(settings: AccountSettings) {
    accountSettings.value = settings;
  }

  /* ============= 停用账号复检结果 ============= */

  const batchTestVisible = ref(false);
  const batchTestResult = ref<TestAccountsResult | null>(null);
  const testDetailVisible = ref(false);
  const testDetail = ref<TestAccountItem | null>(null);

  function handleTestCompleted(result: TestAccountsResult) {
    batchTestResult.value = result;
    batchTestVisible.value = true;
    const message = t('复检完成：总计 {p0}，成功 {p1}，失败 {p2}，跳过 {p3}', {
      p0: result.total,
      p1: result.success,
      p2: result.failed,
      p3: result.skipped,
    });
    if (result.total === 0) {
      ElMessage.warning(t('没有找到可测试账号，请检查平台过滤或单批额度配置'));
    } else {
      ElMessage.success(message);
    }
  }

  function openBatchTestDetail(item: TestAccountItem) {
    testDetail.value = item;
    testDetailVisible.value = true;
  }

  onMounted(loadAccountSettings);
</script>

<template>
  <PageSection :title="t('账号池设置')" :subtitle="t('运行与安全')" :icon="Monitor">
    <el-skeleton v-if="loadState === 'loading'" class="settings-state" :rows="6" animated />

    <el-result
      v-else-if="loadState === 'error'"
      class="settings-state"
      icon="warning"
      :title="t('设置加载失败')"
      :sub-title="loadError"
    >
      <template #extra>
        <el-button type="primary" @click="loadAccountSettings">{{ t('重新加载') }}</el-button>
      </template>
    </el-result>

    <div v-else class="settings-stack">
      <RoutingPolicyPanel />

      <!-- 账号池分级熔断策略 -->
      <div
        class="settings-card surface-card"
        :class="{ 'is-active': poolPolicyEnabled, 'is-expanded': circuitExpanded }"
      >
        <div class="card-header" @click="circuitExpanded = !circuitExpanded">
          <div class="header-left">
            <div class="header-icon-container">
              <AppIcon name="monitor" class="header-icon" />
            </div>
            <div class="title-section">
              <div class="title-row">
                <span class="title-text">{{ t('账号池分级熔断策略') }}</span>
                <el-tag type="warning" size="small" effect="plain">{{ t('试验性') }}</el-tag>
                <span class="status-badge" :class="{ 'is-enabled': poolPolicyEnabled }">
                  <span class="status-dot" />
                  {{ poolPolicyEnabled ? t('已启用') : t('已关闭') }}
                </span>
              </div>
              <div class="desc-text">
                {{ t('按模型供应商与模型族监控账号池健康，窗口错误率超过阈值时自动切换账号池') }}
              </div>
            </div>
          </div>

          <div class="header-right" @click.stop>
            <el-switch
              :model-value="poolPolicyEnabled"
              :loading="poolPolicySaving"
              :disabled="poolPolicySaving"
              inline-prompt
              @change="togglePoolPolicy"
            />
            <div class="expand-toggle-btn" @click="circuitExpanded = !circuitExpanded">
              <AppIcon
                name="chevron-down"
                class="chevron-icon"
                :class="{ rotated: circuitExpanded }"
              />
            </div>
          </div>
        </div>

        <el-collapse-transition>
          <div v-show="circuitExpanded" class="card-body">
            <div class="divider" />
            <el-alert
              class="policy-alert"
              type="warning"
              :closable="false"
              show-icon
              :title="
                t(
                  '该功能仍处于试验阶段，可能发生误熔断、路由抖动或监控数据口径调整，请勿在关键业务中无保护启用。',
                )
              "
            />
            <PoolPolicyPanel
              v-if="poolPolicyEnabled"
              :settings="accountSettings"
              @settings-updated="handlePoolSettingsUpdated"
            />
            <el-empty
              v-else
              :image-size="72"
              :description="t('策略未启用，打开右上角开关后可查看模型路由健康快照')"
            />
          </div>
        </el-collapse-transition>
      </div>

      <!-- 停用账号定期复检 -->
      <DisabledSchedulePanel @test-completed="handleTestCompleted" />
    </div>

    <!-- 批量复检结果对话框 -->
    <BatchTestResultDialog
      v-model:visible="batchTestVisible"
      :result="batchTestResult"
      @show-detail="openBatchTestDetail"
    />

    <!-- 单账号测试结果对话框 -->
    <TestDetailDialog v-model:visible="testDetailVisible" :detail="testDetail" />
  </PageSection>
</template>

<style scoped lang="scss">
  .settings-state {
    min-height: 360px;
  }

  .settings-stack {
    display: grid;
    gap: 24px;

    :deep(.disabled-schedule-card),
    :deep(.routing-policy-card) {
      margin-bottom: 0;
    }
  }

  /* 卡片风格与 DisabledSchedulePanel 保持一致 */
  .settings-card {
    transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
    overflow: hidden;
    position: relative;
    border: 1px solid rgba(255, 255, 255, 0.5);

    &.is-active {
      border-color: rgba(91, 141, 239, 0.25);
      box-shadow:
        0 8px 30px rgba(91, 141, 239, 0.06),
        var(--kp-shadow);

      .header-icon-container {
        background: rgba(91, 141, 239, 0.12);
        color: var(--kp-primary);
      }
    }

    &:hover {
      border-color: rgba(91, 141, 239, 0.4);
      box-shadow:
        0 10px 35px rgba(91, 141, 239, 0.1),
        var(--kp-shadow);
    }
  }

  .card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 16px 20px;
    cursor: pointer;
    user-select: none;
    transition: background-color 0.2s ease;

    &:hover {
      background-color: rgba(255, 255, 255, 0.25);
    }
  }

  .header-left {
    display: flex;
    align-items: center;
    gap: 16px;
    flex: 1;
    min-width: 0;
  }

  .header-icon-container {
    width: 42px;
    height: 42px;
    border-radius: var(--kp-radius-sm);
    background: rgba(15, 23, 42, 0.05);
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--kp-text-secondary);
    transition: all 0.3s ease;
    flex-shrink: 0;
  }

  .header-icon {
    width: 22px;
    height: 22px;
  }

  .title-section {
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
  }

  .title-row {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }

  .title-text {
    font-size: 16px;
    font-weight: 800;
    color: var(--kp-text);
  }

  .status-badge {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: 11px;
    font-weight: 700;
    padding: 2px 8px;
    border-radius: 100px;
    background: rgba(15, 23, 42, 0.06);
    color: var(--kp-text-secondary);
    transition: all 0.3s ease;

    .status-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background-color: var(--kp-text-muted);
      transition: all 0.3s ease;
    }

    &.is-enabled {
      background: rgba(54, 192, 138, 0.12);
      color: #278c65;

      .status-dot {
        background-color: var(--kp-success);
        box-shadow: 0 0 6px var(--kp-success);
        animation: pulse 2s infinite;
      }
    }
  }

  .desc-text {
    font-size: 12.5px;
    color: var(--kp-text-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .header-right {
    display: flex;
    align-items: center;
    gap: 16px;
  }

  .expand-toggle-btn {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--kp-text-muted);
    transition:
      background-color 0.2s ease,
      color 0.2s ease;

    &:hover {
      background-color: rgba(15, 23, 42, 0.05);
      color: var(--kp-text);
    }
  }

  .chevron-icon {
    width: 18px;
    height: 18px;
    transition: transform 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);

    &.rotated {
      transform: rotate(180deg);
    }
  }

  .card-body {
    padding: 0 20px 20px;
  }

  .divider {
    height: 1px;
    background: var(--kp-divider);
    margin-bottom: 20px;
  }

  .policy-alert {
    margin-bottom: 18px;
  }

  @keyframes pulse {
    0% {
      box-shadow: 0 0 0 0 rgba(54, 192, 138, 0.4);
    }
    70% {
      box-shadow: 0 0 0 6px rgba(54, 192, 138, 0);
    }
    100% {
      box-shadow: 0 0 0 0 rgba(54, 192, 138, 0);
    }
  }
</style>
