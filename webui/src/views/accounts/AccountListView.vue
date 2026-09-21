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
   * TestDetailDialog），本组件只保留列表与调度逻辑。
   * 账号池分级熔断策略与停用账号定期复检已统一迁移至系统设置页（views/settings）。
  */
  import { computed, onMounted, reactive, ref } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { CreditCard, MoreFilled, Plus, Refresh, Search } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import MetricsPanel, { type MetricPanelItem } from '@/components/MetricsPanel.vue';
  import AppIcon from './components/AppIcon.vue';
  import ProviderBadge from './components/ProviderBadge.vue';
  import QuotaCell from './components/QuotaCell.vue';
  import AccountTestPopover from './components/AccountTestPopover.vue';
  import AccountFormDialog from './components/AccountFormDialog.vue';
  import ImportDialog from './components/ImportDialog.vue';
  import TestDetailDialog from './components/TestDetailDialog.vue';
  import ChatGptOAuthDialog from './components/ChatGptOAuthDialog.vue';
  import { accountsApi } from '@/api';
  import { ApiError } from '@/utils/http';
  import type {
    Account,
    TestAccountItem,
    TestModelOption,
    TestModelOptionsResult,
    TestAccountsResult,
    OpenAiQuotaResponse,
  } from '@/types/api';
  import { formatDateTime, formatNumber, shorten } from '@/utils/format';
  import {
    isCodexAccount,
    isCustomOpenAiCompatibleProvider,
    normalized,
    statusOf,
  } from './utils';

  /* ============= 列表 ============= */

  const loading = ref(false);
  const accounts = ref<Account[]>([]);
  const keyword = ref('');
  const statusFilter = ref<'all' | 'active' | 'abnormal'>('all');
  const clearingAccounts = ref(false);
  const hasFilter = computed(() => Boolean(keyword.value.trim() || statusFilter.value !== 'all'));

  const visibleAccounts = computed(() => {
    const q = keyword.value.trim().toLowerCase();
    return accounts.value.filter((account) => {
      const available = account.enabled && !account.suspendedAt && !account.quotaExhaustedAt;
      if (statusFilter.value === 'active' && !available) return false;
      if (statusFilter.value === 'abnormal' && available) return false;
      if (!q) return true;
      return (
        account.accountId.toLowerCase().includes(q) ||
        (account.email ?? '').toLowerCase().includes(q) ||
        (account.region ?? '').toLowerCase().includes(q) ||
        (account.provider ?? '').toLowerCase().includes(q) ||
        (account.baseUrl ?? '').toLowerCase().includes(q)
      );
    });
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
    return {
      total,
      active,
      abnormal,
      totalRequests,
      totalErrors,
    };
  });

  const accountMetrics = computed<MetricPanelItem[]>(() => [
    { label: '总账号数', value: summary.value.total, tone: 'primary' },
    { label: '正常运行', value: summary.value.active, tone: 'success', valueTone: 'success' },
    {
      label: '异常 / 停用',
      value: summary.value.abnormal,
      tone: 'danger',
      valueTone: summary.value.abnormal > 0 ? 'danger' : undefined,
    },
    {
      label: '累计请求 / 错误',
      value: formatNumber(summary.value.totalRequests),
      unit: `/ ${formatNumber(summary.value.totalErrors)}`,
      tone: 'accent',
    },
  ]);

  const openAiQuotas = ref<
    Record<string, { loading: boolean; error?: string; data?: OpenAiQuotaResponse }>
  >({});
  let accountDataGeneration = 0;

  // isCodexAccount 已收敛至 ./utils

  async function fetchOpenAiQuota(
    accountId: string,
    force = false,
    generation = accountDataGeneration,
  ) {
    if (!force && openAiQuotas.value[accountId]?.data) return;

    openAiQuotas.value[accountId] = { loading: true };
    try {
      const res = await accountsApi.openAiQuota(accountId);
      if (generation !== accountDataGeneration) return;
      openAiQuotas.value[accountId] = { loading: false, data: res };
    } catch (err) {
      if (generation !== accountDataGeneration) return;
      const msg = err instanceof ApiError ? err.message : String(err);
      openAiQuotas.value[accountId] = { loading: false, error: msg };
    }
  }

  function loadOpenAiQuotas(generation = accountDataGeneration) {
    accounts.value.forEach((account) => {
      if (isCodexAccount(account)) {
        fetchOpenAiQuota(account.accountId, false, generation);
      }
    });
  }

  async function loadAccounts(loadQuotas = true) {
    const generation = ++accountDataGeneration;
    loading.value = true;
    try {
      const nextAccounts = await accountsApi.list();
      if (generation !== accountDataGeneration) return;
      accounts.value = nextAccounts;
      if (loadQuotas) loadOpenAiQuotas(generation);
    } catch (err) {
      if (generation !== accountDataGeneration) return;
      const message = err instanceof ApiError ? err.message : '服务暂时不可用';
      ElMessage.error(`账号列表加载失败：${message}`);
    } finally {
      if (generation === accountDataGeneration) loading.value = false;
    }
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

  const chatGptOAuthVisible = ref(false);
  const chatGptOAuthAccountId = ref<string | null>(null);

  function openChatGptOAuth(accountId: string) {
    chatGptOAuthAccountId.value = accountId;
    chatGptOAuthVisible.value = true;
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
  const testModelByAccount = reactive<Record<string, string>>({});
  const fallbackOpenAiStyleTestModels: TestModelOption[] = [
    { label: 'auto (gpt-5.5)', value: 'auto' },
    { label: 'gpt-5.5', value: 'gpt-5.5' },
    { label: 'gpt-5.4', value: 'gpt-5.4' },
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
    deepSeek: [
      { label: 'auto (deepseek-v4-flash)', value: 'auto' },
      { label: 'deepseek-v4-flash', value: 'deepseek-v4-flash' },
      { label: 'deepseek-v4-pro', value: 'deepseek-v4-pro' },
    ],
    compatibleVendors: [],
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

  type TestModelGroup = 'kiro' | 'openAi' | 'google' | 'anthropic' | 'codex' | 'deepSeek';

  function isOfficialDeepSeekAccount(account: Account) {
    const provider = normalized(account.provider);
    if (provider === 'deepseek') return true;
    if (provider !== 'openai-compatible' && provider !== 'openaicompatibility') return false;
    try {
      return new URL(account.baseUrl ?? '').hostname.toLowerCase() === 'api.deepseek.com';
    } catch {
      return false;
    }
  }

  function accountTestModelGroup(account: Account): TestModelGroup {
    const provider = normalized(account.provider);
    const authMethod = normalized(account.authMethod);
    if (provider === 'codex' || authMethod === 'codex') return 'codex';
    if (isOfficialDeepSeekAccount(account)) return 'deepSeek';
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

  function compatibleVendors() {
    return testModelOptions.value.compatibleVendors ?? [];
  }

  function vendorFor(account: Account) {
    const name = normalized(account.provider);
    return compatibleVendors().find((vendor) => normalized(vendor.name) === name);
  }

  function testModelsFor(account: Account): TestModelOption[] {
    const vendor = vendorFor(account);
    if (vendor?.models?.length) return vendor.models;
    if (isCustomOpenAiCompatibleProvider(account.provider, account.authMethod)) {
      return [{ label: 'auto', value: 'auto' }];
    }
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

  function formatLastUsed(value: string | null | undefined) {
    if (!value) return '尚未使用';
    const at = new Date(value);
    if (Number.isNaN(at.getTime())) return '尚未使用';
    const elapsed = Date.now() - at.getTime();
    if (elapsed < 60_000) return '刚刚';
    if (elapsed < 3_600_000) return `${Math.floor(elapsed / 60_000)} 分钟前`;
    if (elapsed < 86_400_000) return `${Math.floor(elapsed / 3_600_000)} 小时前`;
    if (elapsed < 7 * 86_400_000) return `${Math.floor(elapsed / 86_400_000)} 天前`;
    return formatDateTime(value).slice(0, 16);
  }

  function handleCommand(command: string, row: Account) {
    if (command === 'toggle') {
      toggleEnabled(row);
    } else if (command === 'suspend') {
      suspend(row);
    } else if (command === 'reset') {
      resetAccount(row);
    } else if (command === 'reauthorize') {
      openChatGptOAuth(row.accountId);
    } else if (command === 'remove') {
      removeAccount(row);
    }
  }

  onMounted(async () => {
    await Promise.all([loadAccounts(), loadTestModelOptions()]);
  });
</script>

<template>
  <div class="account-list-view">
    <PageSection title="账号管理" subtitle="上游资源" :icon="CreditCard">
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建账号</el-button>
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
      <template #summary>
        <MetricsPanel :items="accountMetrics" />
      </template>
      <template #toolbar>
        <el-input
          v-model="keyword"
          :prefix-icon="Search"
          placeholder="搜索账号 ID / 邮箱 / 平台 / Base URL"
          clearable
          class="search-input"
        />
        <el-select v-model="statusFilter" aria-label="筛选状态">
          <el-option label="全部状态" value="all" />
          <el-option label="正常运行" value="active" />
          <el-option label="异常 / 停用" value="abnormal" />
        </el-select>
        <span class="toolbar-count">
          {{
            hasFilter
              ? `筛选 ${visibleAccounts.length} / ${accounts.length}`
              : `${accounts.length} 个账号`
          }}
        </span>
        <el-tooltip content="刷新账号">
          <el-button :icon="Refresh" aria-label="刷新账号" :loading="loading" @click="loadAccounts()" />
        </el-tooltip>
      </template>

      <el-table v-loading="loading" class="account-table" :data="visibleAccounts">
        <template #empty>
          <el-empty
            :image-size="88"
            :description="hasFilter ? '没有匹配的账号，试试换个筛选条件' : '还没有上游账号'"
          >
            <template v-if="!hasFilter">
              <el-button type="primary" :icon="Plus" @click="openCreate">新建账号</el-button>
              <el-button @click="openImport">批量导入</el-button>
            </template>
          </el-empty>
        </template>
        <el-table-column label="账号类型" min-width="168" width="180">
          <template #default="{ row }">
            <ProviderBadge
              :provider="row.provider"
              :auth-method="row.authMethod"
              :color="vendorFor(row)?.color"
              :icon="vendorFor(row)?.icon"
            />
          </template>
        </el-table-column>
        <el-table-column label="邮箱 / 账号 ID" min-width="180">
          <template #default="{ row }">
            <div class="email-id-cell">
              <strong class="email-text" :title="row.email">{{ row.email || '—' }}</strong>
              <span class="id-subtext" :title="row.accountId">{{ shorten(row.accountId, 10, 6) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="Base URL" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="url-text">{{ row.baseUrl ? shorten(row.baseUrl, 24, 10) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="区域" width="88">
          <template #default="{ row }">
            <span class="region-text">{{ row.region || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="108">
          <template #default="{ row }">
            <span class="account-state" :class="`is-${statusOf(row).tone}`">
              <i />{{ statusOf(row).label }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="请求 / 错误" width="120">
          <template #default="{ row }">
            <div class="req-cell">
              <b>{{ formatNumber(row.requestCount) }}</b>
              <span :class="{ 'is-error': (row.errorCount || 0) > 0 }">{{
                formatNumber(row.errorCount)
              }}</span>
            </div>
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
        <el-table-column label="最近使用" min-width="128">
          <template #default="{ row }">
            <span class="last-used" :title="row.lastUsedAt ? formatDateTime(row.lastUsedAt) : ''">
              {{ formatLastUsed(row.lastUsedAt) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="168" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-tooltip content="编辑">
                <el-button text aria-label="编辑账号" @click="openEdit(row)">
                  <template #icon>
                    <AppIcon name="edit" width="13" height="13" />
                  </template>
                </el-button>
              </el-tooltip>
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
                <button class="more-action" type="button" aria-label="更多操作">
                  <el-icon><MoreFilled /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="toggle">
                      {{ row.enabled ? '停用' : '启用' }}
                    </el-dropdown-item>
                    <el-dropdown-item command="suspend">挂起</el-dropdown-item>
                    <el-dropdown-item command="reset">重置</el-dropdown-item>
                    <el-dropdown-item v-if="isCodexAccount(row)" command="reauthorize">
                      重新授权
                    </el-dropdown-item>
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
      @saved="() => { void loadAccounts(); void loadTestModelOptions(); }"
    />

    <!-- 批量导入对话框已抽离至 ./components/ImportDialog.vue -->
    <ImportDialog v-model:visible="importVisible" @imported="loadAccounts" />

    <!-- 单账号测试结果对话框已抽离至 ./components/TestDetailDialog.vue -->
    <TestDetailDialog v-model:visible="testDetailVisible" :detail="testDetail" />

    <ChatGptOAuthDialog
      v-model:visible="chatGptOAuthVisible"
      :account-id="chatGptOAuthAccountId"
      @saved="() => loadAccounts()"
    />
  </div>
</template>

<style scoped lang="scss">
  // Scheduled test panel styling moved to DisabledSchedulePanel.vue
  // Row test popover styling moved to components/AccountTestPopover.vue

  // 批量复检结果样式已迁移至 components/BatchTestResultDialog.vue
  // 测试结果详情样式已迁移至 components/TestDetailDialog.vue
  // 批量导入样式（.import-summary / .import-drop-zone）已迁移至 components/ImportDialog.vue
  // 表单样式（.account-form-alert / .form-hint）已迁移至 components/AccountFormDialog.vue

  .account-list-view {
    min-width: 0;
  }

  .page-section__toolbar :deep(.el-select) {
    width: 148px;
  }

  .toolbar-count {
    margin-left: auto;
    color: var(--kp-text-secondary);
    font-size: 12px;
  }

  .email-id-cell {
    display: flex;
    flex-direction: column;
    gap: 5px;
    min-width: 0;

    .email-text {
      overflow: hidden;
      font-size: 13px;
      font-weight: 600;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .id-subtext {
      color: var(--kp-text-muted);
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 11px;
    }
  }

  .url-text,
  .region-text,
  .last-used {
    color: var(--kp-text-secondary);
    font-size: 12px;
  }

  .url-text {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  }

  .account-state {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: var(--kp-text-muted);
    font-size: 12px;

    i {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: currentColor;
    }

    &.is-success {
      color: var(--kp-success);
    }
    &.is-warning {
      color: var(--kp-warning);
    }
    &.is-danger {
      color: var(--kp-danger);
    }
  }

  .req-cell {
    display: flex;
    align-items: baseline;
    gap: 6px;
    font-variant-numeric: tabular-nums;

    b {
      font-size: 13px;
    }

    span {
      color: var(--kp-text-muted);
      font-size: 11px;

      &.is-error {
        color: var(--kp-danger);
      }
    }
  }

  .account-table {
    :deep(.el-table-fixed-column--right) {
      background-color: color-mix(in srgb, var(--kp-surface-strong) 92%, white) !important;
    }

    :deep(.row-actions .el-button) {
      margin: 0;
      padding: 7px;
    }
  }

  .row-actions {
    display: flex;
    align-items: center;
    gap: 2px;
    white-space: nowrap;
  }

  .more-action {
    width: 28px;
    height: 28px;
    display: inline-grid;
    place-items: center;
    padding: 0;
    border: 0;
    border-radius: 6px;
    background: transparent;
    color: var(--el-color-primary);
    cursor: pointer;

    &:hover,
    &:focus-visible {
      background: rgba(91, 141, 239, 0.1);
      outline: none;
    }
  }

  .dropdown-danger-text {
    color: var(--el-color-danger);
  }

  .search-input {
    width: 280px;
  }

  @media (max-width: 640px) {
    .search-input {
      width: 100%;
    }

    .toolbar-count {
      margin-left: 0;
      width: 100%;
    }
  }

  // Quota cell styling moved to components/QuotaCell.vue

  // 表单页签样式（.account-form-tabs）已迁移至 components/AccountFormDialog.vue
</style>
