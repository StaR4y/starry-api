<script setup lang="ts">
  /**
   * 上游账号管理。
   *
   * 覆盖 /admin/accounts 全部能力：
   * - 列表 / 新增 / 编辑 / 删除
   * - 启用/停用、挂起/恢复（reset）
   * - 批量 JSON 导入（支持 upsert）
   *
   * 各对话框已抽离至 ./components/（AccountFormDialog / ImportDialog /
   * BatchTestResultDialog / TestDetailDialog），本组件只保留列表与调度逻辑。
   */
  import { computed, onMounted, reactive, ref } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import PageSection from '@/components/PageSection.vue';
  import DisabledSchedulePanel from './components/DisabledSchedulePanel.vue';
  import PoolPolicyPanel from './components/PoolPolicyPanel.vue';
  import AppIcon from './components/AppIcon.vue';
  import ProviderBadge from './components/ProviderBadge.vue';
  import QuotaCell from './components/QuotaCell.vue';
  import AccountTestPopover from './components/AccountTestPopover.vue';
  import AccountFormDialog from './components/AccountFormDialog.vue';
  import ImportDialog from './components/ImportDialog.vue';
  import BatchTestResultDialog from './components/BatchTestResultDialog.vue';
  import TestDetailDialog from './components/TestDetailDialog.vue';
  import { accountsApi } from '@/api';
  import { ApiError } from '@/utils/http';
  import type {
    Account,
    AccountSettings,
    TestAccountItem,
    TestModelOption,
    TestModelOptionsResult,
    TestAccountsResult,
    OpenAiQuotaResponse,
  } from '@/types/api';
  import { formatDateTime, formatNumber, shorten } from '@/utils/format';
  import {
    isCodexAccount,
    normalized,
    normalizedPoolTier,
    poolTierLabel,
    poolTierTagType,
    statusOf,
  } from './utils';

  /* ============= 列表 ============= */

  const loading = ref(false);
  const accounts = ref<Account[]>([]);
  const keyword = ref('');
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
      enabled: true,
      windowMinutes: 5,
      minSampleSize: 10,
      retestThresholdPercent: 50,
      failoverThresholdPercent: 70,
      recoveryThresholdPercent: 70,
      retestIntervalMinutes: 5,
      retestMaxConcurrency: 4,
      autoWeightEnabled: true,
    },
  });
  // 熔断策略面板相关状态已迁移至 components/PoolPolicyPanel.vue
  const settingsSaving = ref(false);
  const clearingAccounts = ref(false);

  const visibleAccounts = computed(() => {
    const q = keyword.value.trim().toLowerCase();
    if (!q) return accounts.value;
    return accounts.value.filter(
      (a) =>
        a.accountId.toLowerCase().includes(q) ||
        (a.email ?? '').toLowerCase().includes(q) ||
        (a.region ?? '').toLowerCase().includes(q) ||
        (a.provider ?? '').toLowerCase().includes(q) ||
        (a.baseUrl ?? '').toLowerCase().includes(q),
    );
  });

  const summary = computed(() => {
    const total = accounts.value.length;
    const active = accounts.value.filter(
      (a) => a.enabled && !a.suspendedAt && !a.quotaExhaustedAt,
    ).length;
    const abnormal = accounts.value.filter(
      (a) => a.suspendedAt || a.quotaExhaustedAt || !a.enabled,
    ).length;

    const totalRequests = accounts.value.reduce((sum, a) => sum + (a.requestCount || 0), 0);
    const totalErrors = accounts.value.reduce((sum, a) => sum + (a.errorCount || 0), 0);
    const primary = accounts.value.filter(
      (a) => normalizedPoolTier(a.poolTier) === 'primary',
    ).length;
    const secondary = accounts.value.filter(
      (a) => normalizedPoolTier(a.poolTier) === 'secondary',
    ).length;

    return {
      total,
      active,
      abnormal,
      totalRequests,
      totalErrors,
      primary,
      secondary,
    };
  });

  // primaryPoolAccounts / secondaryPoolAccounts 已迁移至 components/PoolPolicyPanel.vue

  // statusOf 已收敛至 ./utils

  const openAiQuotas = ref<
    Record<string, { loading: boolean; error?: string; data?: OpenAiQuotaResponse }>
  >({});

  // isCodexAccount 已收敛至 ./utils

  async function fetchOpenAiQuota(accountId: string, force = false) {
    if (!force && openAiQuotas.value[accountId]?.data) return;

    openAiQuotas.value[accountId] = { loading: true };
    try {
      const res = await accountsApi.openAiQuota(accountId);
      openAiQuotas.value[accountId] = { loading: false, data: res };
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : String(err);
      openAiQuotas.value[accountId] = { loading: false, error: msg };
    }
  }

  async function loadAccounts() {
    loading.value = true;
    try {
      accounts.value = await accountsApi.list();
      accounts.value.forEach((account) => {
        if (isCodexAccount(account)) {
          fetchOpenAiQuota(account.accountId);
        }
      });
    } finally {
      loading.value = false;
    }
  }

  async function loadAccountSettings() {
    try {
      accountSettings.value = await accountsApi.settings();
    } catch (err) {
      if (err instanceof ApiError) ElMessage.warning(`账号配置加载失败：${err.message}`);
    }
  }

  // loadPoolStatus / savePoolPolicy 已迁移至 components/PoolPolicyPanel.vue

  async function toggleGptFastMode(value: string | number | boolean) {
    settingsSaving.value = true;
    try {
      accountSettings.value = await accountsApi.updateSettings({
        gptFastModeEnabled: Boolean(value),
      });
      ElMessage.success(
        accountSettings.value.gptFastModeEnabled ? '已允许 GPT Fast 模式' : '已关闭 GPT Fast 模式',
      );
    } catch (err) {
      accountSettings.value.gptFastModeEnabled = !value;
      if (err instanceof ApiError) ElMessage.error(err.message);
    } finally {
      settingsSaving.value = false;
    }
  }

  function handleTestCompleted(result: TestAccountsResult) {
    const nextResults = { ...latestTestResults.value };
    result.items.forEach((item) => {
      nextResults[item.accountId] = item;
    });
    latestTestResults.value = nextResults;
    batchTestResult.value = result;
    batchTestVisible.value = true;
    testDetail.value =
      result.items.find((item) => item.status === 'FAILED') ?? result.items[0] ?? null;
    testDetailVisible.value = false;
    const message = `复检完成：总计 ${result.total}，成功 ${result.success}，失败 ${result.failed}，跳过 ${result.skipped}`;
    if (result.total === 0) {
      ElMessage.warning('没有找到可测试账号，请检查平台过滤或单批额度配置');
    } else {
      ElMessage.success(message);
    }
    loadAccounts();
  }

  /* ============= 新建 / 编辑 ============= */
  // 表单、校验与提交逻辑已迁移至 components/AccountFormDialog.vue

  const formVisible = ref(false);
  const formMode = ref<'create' | 'edit'>('create');
  const editingAccount = ref<Account | null>(null);

  function openCreate() {
    formMode.value = 'create';
    editingAccount.value = null;
    formVisible.value = true;
  }

  function openEdit(row: Account) {
    formMode.value = 'edit';
    editingAccount.value = row;
    formVisible.value = true;
  }

  /* ============= 启停 / 挂起 / 重置 / 删除 ============= */

  async function toggleEnabled(row: Account) {
    await accountsApi.update(row.accountId, { enabled: !row.enabled });
    ElMessage.success(row.enabled ? '已停用' : '已启用');
    await loadAccounts();
  }

  async function suspend(row: Account) {
    const result = await ElMessageBox.prompt('请输入挂起原因（如 RATE_LIMITED）', '挂起账号', {
      confirmButtonText: '挂起',
      cancelButtonText: '取消',
      inputPlaceholder: 'TEMPORARILY_SUSPENDED',
    }).catch(() => null);
    if (!result) return;
    await accountsApi.suspend(row.accountId, {
      reason: result.value || 'TEMPORARILY_SUSPENDED',
      message: '由控制台手动挂起',
    });
    ElMessage.success('已挂起');
    await loadAccounts();
  }

  async function resetAccount(row: Account) {
    await ElMessageBox.confirm(`确认重置账号 ${row.accountId} 状态？`, '确认操作', {
      confirmButtonText: '重置',
      cancelButtonText: '取消',
      type: 'warning',
    }).catch(() => Promise.reject(new Error('cancel')));
    await accountsApi.reset(row.accountId);
    ElMessage.success('已重置');
    await loadAccounts();
  }

  async function removeAccount(row: Account) {
    await ElMessageBox.confirm(`确认删除账号 ${row.accountId}？操作不可恢复。`, '危险操作', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'error',
    }).catch(() => Promise.reject(new Error('cancel')));
    await accountsApi.remove(row.accountId);
    ElMessage.success('已删除');
    await loadAccounts();
  }

  async function clearAllAccounts() {
    if (accounts.value.length === 0) {
      ElMessage.info('当前没有账号可清空');
      return;
    }
    const confirmed = await ElMessageBox.confirm(
      `确认清空全部 ${accounts.value.length} 个账号？该操作不可恢复。`,
      '清空全部账号',
      { confirmButtonText: '清空全部', cancelButtonText: '取消', type: 'error' },
    )
      .then(() => true)
      .catch(() => false);
    if (!confirmed) return;

    clearingAccounts.value = true;
    try {
      const result = await accountsApi.clearAll();
      latestTestResults.value = {};
      testDetail.value = null;
      ElMessage.success(`已清空 ${result.deleted} 个账号`);
      await loadAccounts();
    } catch (err) {
      if (err instanceof ApiError) ElMessage.error(err.message);
    } finally {
      clearingAccounts.value = false;
    }
  }

  /* ============= 账号连通测试 ============= */

  const testingAccountId = ref<string | null>(null);
  const testDetailVisible = ref(false);
  const testDetail = ref<TestAccountItem | null>(null);
  const latestTestResults = ref<Record<string, TestAccountItem>>({});
  const batchTestVisible = ref(false);
  const batchTestResult = ref<TestAccountsResult | null>(null);
  const testModelByAccount = reactive<Record<string, string>>({});
  const fallbackOpenAiStyleTestModels: TestModelOption[] = [
    { label: 'auto (gpt-5.4-mini)', value: 'auto' },
    { label: 'gpt-5.5', value: 'gpt-5.5' },
    { label: 'gpt-5.4', value: 'gpt-5.4' },
    { label: 'gpt-5.4-mini', value: 'gpt-5.4-mini' },
    { label: 'gpt-5.4-nano', value: 'gpt-5.4-nano' },
    { label: 'gpt-5.4-pro', value: 'gpt-5.4-pro' },
    { label: 'gpt-5.3-chat', value: 'gpt-5.3-chat' },
    { label: 'gpt-5.2', value: 'gpt-5.2' },
    { label: 'gpt-5.2-chat', value: 'gpt-5.2-chat' },
    { label: 'gpt-5.2-pro', value: 'gpt-5.2-pro' },
    { label: 'gpt-5.1', value: 'gpt-5.1' },
    { label: 'gpt-5.1-chat', value: 'gpt-5.1-chat' },
    { label: 'gpt-5', value: 'gpt-5' },
    { label: 'gpt-5-mini', value: 'gpt-5-mini' },
    { label: 'gpt-5-nano', value: 'gpt-5-nano' },
    { label: 'gpt-5-pro', value: 'gpt-5-pro' },
    { label: 'gpt-4.1', value: 'gpt-4.1' },
    { label: 'gpt-4.1-mini', value: 'gpt-4.1-mini' },
    { label: 'gpt-4o', value: 'gpt-4o' },
    { label: 'gpt-4o-mini', value: 'gpt-4o-mini' },
  ];
  const fallbackTestModelOptions: TestModelOptionsResult = {
    kiro: [
      { label: 'auto', value: 'auto' },
      { label: 'claude-sonnet-4.5', value: 'claude-sonnet-4.5' },
      { label: 'simple-task', value: 'simple-task' },
      { label: 'claude-haiku-4.5', value: 'claude-haiku-4.5' },
    ],
    openAi: fallbackOpenAiStyleTestModels,
    google: [
      { label: 'auto (google/gemini-2.5-flash)', value: 'auto' },
      { label: 'google/gemini-2.5-pro', value: 'google/gemini-2.5-pro' },
      { label: 'google/gemini-2.5-flash', value: 'google/gemini-2.5-flash' },
    ],
    anthropic: [
      { label: 'auto (claude-sonnet-4.5)', value: 'auto' },
      { label: 'claude-sonnet-4.5', value: 'claude-sonnet-4.5' },
      { label: 'claude-opus-4.5', value: 'claude-opus-4.5' },
    ],
    codex: fallbackOpenAiStyleTestModels,
  };
  const testModelOptions = ref<TestModelOptionsResult>(fallbackTestModelOptions);
  const testOptions = reactive({
    prompt: 'Reply OK in one short sentence.',
    onlyEnabled: true,
    updateStatus: true,
  });

  // testDetailJson / batchTestJson / testStatusType / copy 已随对话框迁移至 components/

  function latestTest(accountId: string) {
    return latestTestResults.value[accountId];
  }

  function openBatchTestDetail(item: TestAccountItem) {
    testDetail.value = item;
    testDetailVisible.value = true;
  }

  type TestModelGroup = 'kiro' | 'openAi' | 'google' | 'anthropic' | 'codex';

  function accountTestModelGroup(account: Account): TestModelGroup {
    const provider = normalized(account.provider);
    const authMethod = normalized(account.authMethod);
    if (provider === 'codex' || authMethod === 'codex') return 'codex';
    if (
      provider === 'google' ||
      provider === 'gemini' ||
      authMethod === 'google' ||
      authMethod === 'gemini'
    ) {
      return 'google';
    }
    if (provider === 'anthropic' || provider === 'claude' || authMethod === 'anthropic') {
      return 'anthropic';
    }
    if (
      provider === 'openai' ||
      provider === 'openai-compatible' ||
      provider === 'openaicompatibility' ||
      authMethod === 'api-key' ||
      authMethod === 'apikey' ||
      authMethod === 'openai-api-key'
    ) {
      return 'openAi';
    }
    return 'kiro';
  }

  function testModelsFor(account: Account): TestModelOption[] {
    const group = accountTestModelGroup(account);
    return testModelOptions.value[group]?.length
      ? testModelOptions.value[group]
      : fallbackTestModelOptions[group];
  }

  function ensureTestModel(account: Account) {
    const options = testModelsFor(account);
    const current = testModelByAccount[account.accountId];
    if (!current || !options.some((option) => option.value === current)) {
      testModelByAccount[account.accountId] = options[0]?.value ?? 'auto';
    }
  }

  function selectedTestModel(account: Account) {
    ensureTestModel(account);
    return testModelByAccount[account.accountId] || 'auto';
  }

  // normalized / normalizedPoolTier / poolTierLabel / poolTierTagType 已收敛至 ./utils

  // 号池拖拽与移动逻辑已迁移至 components/PoolPolicyPanel.vue

  function openTestDetail(row: Account) {
    const item = latestTest(row.accountId);
    if (!item) {
      ElMessage.warning('该账号还没有测试结果');
      return;
    }
    testDetail.value = item;
    testDetailVisible.value = true;
  }

  async function testAccount(row: Account) {
    testingAccountId.value = row.accountId;
    try {
      const result: TestAccountsResult = await accountsApi.test({
        accountIds: [row.accountId],
        onlyEnabled: testOptions.onlyEnabled,
        model: selectedTestModel(row),
        prompt: testOptions.prompt,
        maxConcurrency: 1,
        updateStatus: testOptions.updateStatus,
      });
      const item = result.items[0];
      if (item) {
        latestTestResults.value = { ...latestTestResults.value, [row.accountId]: item };
        testDetail.value = item;
        testDetailVisible.value = true;
      }
      ElMessage.success(`测试完成：${item?.status ?? 'UNKNOWN'}`);
      await loadAccounts();
    } catch (err) {
      if (err instanceof ApiError) ElMessage.error(err.message);
    } finally {
      testingAccountId.value = null;
    }
  }

  async function loadTestModelOptions() {
    try {
      testModelOptions.value = await accountsApi.testModels();
    } catch (err) {
      if (err instanceof ApiError) ElMessage.warning(`测试模型列表加载失败：${err.message}`);
    }
  }

  /* ============= 批量导入 ============= */
  // 导入解析、提交与结果展示已迁移至 components/ImportDialog.vue

  const importVisible = ref(false);

  function openImport() {
    importVisible.value = true;
  }

  // getProviderDisplayName 已迁移至 components/PoolPolicyPanel.vue

  function handleCommand(command: string, row: Account) {
    if (command === 'toggle') {
      toggleEnabled(row);
    } else if (command === 'suspend') {
      suspend(row);
    } else if (command === 'reset') {
      resetAccount(row);
    } else if (command === 'remove') {
      removeAccount(row);
    }
  }

  onMounted(() => {
    loadAccountSettings();
    loadTestModelOptions();
    loadAccounts();
  });
</script>

<template>
  <PageSection title="账号管理" subtitle="维护上游账号池、配额与状态">
    <template #actions>
      <div class="fast-mode-toggle">
        <span>GPT Fast</span>
        <el-switch
          v-model="accountSettings.gptFastModeEnabled"
          :disabled="settingsSaving"
          inline-prompt
          @change="toggleGptFastMode"
        />
      </div>
      <el-input
        v-model="keyword"
        placeholder="搜索账号 ID / 邮箱 / 平台 / Base URL"
        clearable
        class="search-input"
      >
        <template #prefix>
          <AppIcon name="search" class="search-input__icon" />
        </template>
      </el-input>
      <el-button type="primary" @click="openCreate">新建账号</el-button>
      <el-button @click="openImport">批量导入</el-button>
      <el-button
        type="danger"
        plain
        :loading="clearingAccounts"
        :disabled="loading || accounts.length === 0"
        @click="clearAllAccounts"
      >
        清空全部
      </el-button>
    </template>

    <!-- 1. Stats Summary Panel -->
    <div class="metrics-panel glass-panel">
      <div class="metric-item">
        <div class="metric-item__header">
          <span class="metric-dot dot-total" />
          <span class="metric-label">总账号数</span>
        </div>
        <div class="metric-value mono">{{ summary.total }}</div>
      </div>

      <div class="metric-item">
        <div class="metric-item__header">
          <span class="metric-dot dot-active" />
          <span class="metric-label">正常运行</span>
        </div>
        <div class="metric-value mono text-success">{{ summary.active }}</div>
      </div>

      <div class="metric-item">
        <div class="metric-item__header">
          <span class="metric-dot dot-suspended" />
          <span class="metric-label">异常 / 停用</span>
        </div>
        <div class="metric-value mono" :class="{ 'text-danger': summary.abnormal > 0 }">
          {{ summary.abnormal }}
        </div>
      </div>

      <div class="metric-item">
        <div class="metric-item__header">
          <span class="metric-dot dot-requests" />
          <span class="metric-label">累计请求 / 错误</span>
        </div>
        <div class="metric-value mono">
          {{ formatNumber(summary.totalRequests) }}
          <span class="metric-value__sub">/ {{ formatNumber(summary.totalErrors) }}</span>
        </div>
      </div>

      <div class="metric-item">
        <div class="metric-item__header">
          <span class="metric-dot dot-requests" />
          <span class="metric-label">一级 / 二级</span>
        </div>
        <div class="metric-value mono">
          {{ summary.primary }}
          <span class="metric-value__sub">/ {{ summary.secondary }}</span>
        </div>
      </div>
    </div>

    <!-- 账号池熔断策略面板已抽离至 ./components/PoolPolicyPanel.vue -->
    <PoolPolicyPanel
      :accounts="accounts"
      :visible-accounts="visibleAccounts"
      :settings="accountSettings"
      @settings-updated="accountSettings = $event"
      @accounts-updated="accounts = $event"
    />

    <!-- 2. Suspended Accounts Periodic Review Collapsible Module -->
    <DisabledSchedulePanel @test-completed="handleTestCompleted" />

    <el-table v-loading="loading" :data="visibleAccounts" stripe>
      <el-table-column label="账号类型" width="160">
        <template #default="{ row }">
          <ProviderBadge :provider="row.provider" />
        </template>
      </el-table-column>
      <el-table-column label="邮箱 / 账号 ID" min-width="160">
        <template #default="{ row }">
          <div class="email-id-cell">
            <span class="email-text" :title="row.email">{{ row.email || '—' }}</span>
            <span class="id-subtext mono">{{ shorten(row.accountId, 10, 6) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="Base URL" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="mono">{{ row.baseUrl ? shorten(row.baseUrl, 24, 10) : '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="region" label="区域" width="80" />
      <el-table-column label="号池 / 权重" width="110">
        <template #default="{ row }">
          <div class="pool-cell">
            <el-tag :type="poolTierTagType(row.poolTier)" effect="light" round>
              {{ poolTierLabel(row.poolTier) }}
            </el-tag>
            <span class="text-secondary">权重 {{ row.poolWeight ?? 100 }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusOf(row).tone" effect="light" round>{{ statusOf(row).label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="请求 / 错误" width="100">
        <template #default="{ row }">
          {{ formatNumber(row.requestCount) }} / {{ formatNumber(row.errorCount) }}
        </template>
      </el-table-column>
      <el-table-column label="可用配额" width="180">
        <template #default="{ row }">
          <QuotaCell
            :account="row"
            :quota-state="openAiQuotas[row.accountId]"
            @refresh="(accountId) => fetchOpenAiQuota(accountId, true)"
          />
        </template>
      </el-table-column>
      <el-table-column label="最近使用" width="150">
        <template #default="{ row }">{{ formatDateTime(row.lastUsedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <div class="actions-wrapper">
            <el-button link type="primary" @click="openEdit(row)">
              <template #icon>
                <AppIcon name="edit" width="12" height="12" class="action-icon" />
              </template>
              编辑
            </el-button>
            <AccountTestPopover
              v-model:model="testModelByAccount[row.accountId]"
              v-model:prompt="testOptions.prompt"
              v-model:only-enabled="testOptions.onlyEnabled"
              v-model:update-status="testOptions.updateStatus"
              :latest-test="latestTest(row.accountId)"
              :testing="testingAccountId === row.accountId"
              :model-options="testModelsFor(row)"
              @ensure-model="ensureTestModel(row)"
              @test="testAccount(row)"
              @show-detail="openTestDetail(row)"
            />
            <el-dropdown trigger="click" @command="(cmd: string) => handleCommand(cmd, row)">
              <el-button link type="primary" class="more-btn">
                更多<span class="caret">▼</span>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="toggle">
                    {{ row.enabled ? '停用' : '启用' }}
                  </el-dropdown-item>
                  <el-dropdown-item command="suspend">挂起</el-dropdown-item>
                  <el-dropdown-item command="reset">重置</el-dropdown-item>
                  <el-dropdown-item command="remove" divided>
                    <span class="dropdown-danger-text">删除</span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </template>
      </el-table-column>
    </el-table>
  </PageSection>

  <!-- 新建 / 编辑对话框已抽离至 ./components/AccountFormDialog.vue -->
  <AccountFormDialog
    v-model:visible="formVisible"
    :mode="formMode"
    :account="editingAccount"
    @saved="loadAccounts"
  />

  <!-- 批量导入对话框已抽离至 ./components/ImportDialog.vue -->
  <ImportDialog v-model:visible="importVisible" @imported="loadAccounts" />

  <!-- 批量复检结果对话框已抽离至 ./components/BatchTestResultDialog.vue -->
  <BatchTestResultDialog
    v-model:visible="batchTestVisible"
    :result="batchTestResult"
    @show-detail="openBatchTestDetail"
  />

  <!-- 单账号测试结果对话框已抽离至 ./components/TestDetailDialog.vue -->
  <TestDetailDialog v-model:visible="testDetailVisible" :detail="testDetail" />
</template>

<style scoped lang="scss">
  // Scheduled test panel styling moved to DisabledSchedulePanel.vue
  // Row test popover styling moved to components/AccountTestPopover.vue

  // 批量复检结果样式已迁移至 components/BatchTestResultDialog.vue
  // 测试结果详情样式已迁移至 components/TestDetailDialog.vue
  // 批量导入样式（.import-summary / .import-drop-zone）已迁移至 components/ImportDialog.vue
  // 表单样式（.account-form-alert / .form-hint）已迁移至 components/AccountFormDialog.vue

  .caret {
    font-size: 10px;
    margin-left: 3px;
    vertical-align: middle;
    display: inline-block;
  }

  // Provider badge styling moved to components/ProviderBadge.vue

  .email-id-cell {
    display: flex;
    flex-direction: column;
    gap: 2px;
    line-height: 1.3;
    min-width: 0;

    .email-text {
      font-size: 13px;
      font-weight: 500;
      color: var(--el-text-color-primary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .id-subtext {
      font-size: 11px;
      color: var(--kp-text-muted);
    }
  }

  /* Unified Metrics Panel：grid 5 列，分隔线用 border 实现，换行断点处隐藏 */
  .metrics-panel {
    display: grid;
    grid-template-columns: repeat(5, 1fr);
    column-gap: 28px;
    align-items: center;
    padding: 20px 28px;
    margin-bottom: 24px;
    width: 100%;

    .metric-item {
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 0;

      & + .metric-item {
        border-left: 1px solid var(--kp-divider);
        padding-left: 28px;
      }

      &__header {
        display: flex;
        align-items: center;
        gap: 6px;
      }

      .metric-dot {
        width: 7px;
        height: 7px;
        border-radius: 50%;
        display: inline-block;

        &.dot-total {
          background-color: var(--kp-primary);
          box-shadow: 0 0 6px rgba(91, 141, 239, 0.6);
        }
        &.dot-active {
          background-color: var(--kp-success);
          box-shadow: 0 0 6px rgba(54, 192, 138, 0.6);
        }
        &.dot-suspended {
          background-color: var(--kp-danger);
          box-shadow: 0 0 6px rgba(239, 91, 107, 0.6);
        }
        &.dot-requests {
          background-color: var(--kp-accent);
          box-shadow: 0 0 6px rgba(179, 135, 255, 0.6);
        }
      }

      .metric-label {
        font-size: 12.5px;
        font-weight: 600;
        color: var(--kp-text-secondary);
      }

      .metric-value {
        font-size: 26px;
        font-weight: 800;
        color: var(--kp-text);
        line-height: 1.15;

        &.text-success {
          color: var(--kp-success);
        }
        &.text-danger {
          color: var(--kp-danger);
        }

        &__sub {
          font-size: 13px;
          font-weight: 500;
          color: var(--kp-text-muted);
        }
      }
    }
  }

  .pool-cell {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 5px;
  }

  .actions-wrapper {
    display: flex;
    align-items: center;
    gap: 4px;

    .el-button {
      margin: 0;
    }

    .action-icon {
      margin-right: 2px;
    }

    .more-btn {
      padding: 0 2px;
    }
  }

  // 删除项渲染在 teleport 到 body 的下拉菜单里，选择器需置于顶层
  .dropdown-danger-text {
    color: var(--el-color-danger);
  }

  .search-input {
    width: 280px;

    &__icon {
      width: 14px;
      height: 14px;
      color: var(--kp-text-muted);
      margin-right: 4px;
    }
  }

  .fast-mode-toggle {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    min-height: 32px;
    padding: 0 10px;
    border: 1px solid var(--kp-divider);
    border-radius: 6px;
    background: rgba(255, 255, 255, 0.55);
    color: var(--kp-text-secondary);
    font-size: 12px;
    font-weight: 700;
    white-space: nowrap;
  }

  // Quota cell styling moved to components/QuotaCell.vue

  // 表单页签样式（.account-form-tabs）已迁移至 components/AccountFormDialog.vue

  @media (max-width: 1024px) {
    .metrics-panel {
      grid-template-columns: repeat(3, 1fr);
      row-gap: 16px;
      padding: 16px 20px;

      // 换行后隐藏分隔线，避免出现孤立分隔线
      .metric-item + .metric-item {
        border-left: none;
        padding-left: 0;
      }
    }
  }

  @media (max-width: 640px) {
    .metrics-panel {
      grid-template-columns: repeat(2, 1fr);
      padding: 20px;
    }
  }
</style>
