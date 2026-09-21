<script setup lang="ts">
  /**
   * API Key 管理。
   *
   * 关键点：API Key 按登录用户隔离，完整密钥由后端保存在所属用户记录中，便于直接复制。
   */
  import { computed, onMounted, reactive, ref, watch } from 'vue';
  import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
  import {
    MoreFilled,
    Plus,
    Key,
    Wallet,
    Monitor,
    Search,
    CopyDocument,
    EditPen,
    Setting,
    Refresh,
  } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import MetricsPanel, { type MetricPanelItem } from '@/components/MetricsPanel.vue';
  import ProviderLogo from '@/components/ProviderLogo.vue';
  import { apiKeysApi, systemApi, codingPlanApi } from '@/api';
  import { useAuthStore } from '@/stores/auth';
  import type { ApiKey, CreateApiKeyRequest, CreatedApiKey, ModelInfo } from '@/types/api';
  import { canonicalCatalogProvider, getProviderOrder, orderOf } from '@/platforms/models';
  import { formatDateTime, formatNumber, shorten } from '@/utils/format';
  import {
    createCcSwitchLinks,
    createConnectionText,
    createSetupScriptBlock,
    setupClients,
    setupPlatforms,
    type CcSwitchLink,
    type SetupClient,
    type SetupContext,
    type SetupFileBlock,
    type SetupPlatform,
    type SetupScriptBlock,
  } from '@/platforms/clientSetup';

  const loading = ref(false);
  const apiKeys = ref<ApiKey[]>([]);
  const models = ref<ModelInfo[]>([]);
  const auth = useAuthStore();
  const keySearch = ref('');
  const sourceFilter = ref('');
  const selectedPlanBalance = ref<string | null>(null);
  const filteredKeys = computed(() =>
    apiKeys.value.filter(
      (key) =>
        (!sourceFilter.value || key.billingSource === sourceFilter.value) &&
        (!keySearch.value ||
          `${key.name} ${key.modelProvider}`.toLowerCase().includes(keySearch.value.toLowerCase())),
    ),
  );
  const enabledCount = computed(() => apiKeys.value.filter((key) => key.enabled).length);
  const balanceCount = computed(
    () => apiKeys.value.filter((key) => key.billingSource !== 'CODING_PLAN').length,
  );
  const planCount = computed(
    () => apiKeys.value.filter((key) => key.billingSource === 'CODING_PLAN').length,
  );
  const keyMetrics = computed<MetricPanelItem[]>(() => [
    { label: '已启用', value: enabledCount.value, tone: 'success' },
    { label: '余额计费', value: balanceCount.value, tone: 'primary' },
    { label: 'Coding Plan', value: planCount.value, tone: 'accent' },
    { label: '全部密钥', value: apiKeys.value.length, tone: 'info' },
  ]);
  const hasFilter = computed(() => Boolean(keySearch.value.trim() || sourceFilter.value));
  const apiOrigin = computed(() => {
    const configured = import.meta.env.VITE_API_BASE;
    if (configured) {
      return String(configured).replace(/\/$/, '');
    }
    if (import.meta.env.DEV) {
      return 'http://127.0.0.1:8080';
    }
    return window.location.origin.replace(/\/$/, '');
  });
  const apiBaseUrl = computed(() => {
    return `${apiOrigin.value}/v1`;
  });
  const apiPort = computed(() => {
    try {
      const url = new URL(apiBaseUrl.value);
      return url.port || (url.protocol === 'https:' ? '443' : '80');
    } catch {
      return '';
    }
  });

  async function loadKeys() {
    loading.value = true;
    try {
      apiKeys.value = await apiKeysApi.list();
    } finally {
      loading.value = false;
    }
  }

  const modelProviders = computed(() => {
    const counts = new Map<string, number>();
    models.value.forEach((model) => {
      const provider = canonicalCatalogProvider(model.provider || model.owned_by);
      if (provider) counts.set(provider, (counts.get(provider) || 0) + 1);
    });
    const providerOrder = getProviderOrder();
    return Array.from(counts, ([name, count]) => ({ name, count })).sort((left, right) => {
      const difference = orderOf(providerOrder, left.name) - orderOf(providerOrder, right.name);
      return difference || left.name.localeCompare(right.name);
    });
  });

  async function loadModelProviders() {
    models.value = await systemApi.models();
  }

  /* ============= 新建 / 编辑 ============= */

  type FormMode = 'create' | 'edit';
  const formVisible = ref(false);
  const formMode = ref<FormMode>('create');
  const formRef = ref<FormInstance>();
  const formSubmitting = ref(false);
  const editingKeyId = ref<string | null>(null);

  const form = reactive({
    billingSource: 'BALANCE' as 'BALANCE' | 'CODING_PLAN',
    name: '',
    creditsLimit: undefined as number | undefined,
    modelProvider: '',
    enabled: true,
  });

  const rules: FormRules = {
    name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
    modelProvider: [{ required: true, message: '请选择模型分类', trigger: 'change' }],
  };

  watch(
    () => [formVisible.value, form.modelProvider],
    async (_, __, onCleanup) => {
      selectedPlanBalance.value = null;
      if (auth.isAdmin || !formVisible.value || !form.modelProvider) return;
      let current = true;
      onCleanup(() => {
        current = false;
      });
      try {
        const summary = await codingPlanApi.summary(form.modelProvider);
        if (current) selectedPlanBalance.value = summary.availableCredits;
      } catch {
        /* The HTTP interceptor reports availability errors. */
      }
    },
  );

  function resetForm() {
    Object.assign(form, {
      billingSource: 'BALANCE',
      name: '',
      creditsLimit: undefined,
      modelProvider: modelProviders.value[0]?.name || '',
      enabled: true,
    });
  }

  function openCreate() {
    formMode.value = 'create';
    editingKeyId.value = null;
    resetForm();
    formVisible.value = true;
  }

  function openEdit(row: ApiKey) {
    formMode.value = 'edit';
    editingKeyId.value = row.keyId;
    Object.assign(form, {
      billingSource: row.billingSource || 'BALANCE',
      name: row.name,
      creditsLimit: row.creditsLimit ?? undefined,
      modelProvider: row.modelProvider === '*' ? '' : row.modelProvider,
      enabled: row.enabled,
    });
    formVisible.value = true;
  }

  /* 创建后密钥展示 */
  const newKeyVisible = ref(false);
  const newKey = ref<CreatedApiKey | null>(null);
  const newSetupClient = ref<SetupClient>('codex');
  const newSetupPlatform = ref<SetupPlatform>('unix');
  const configSetupClient = ref<SetupClient>('codex');
  const configSetupPlatform = ref<SetupPlatform>('unix');

  function normalizedApiKey(apiKey?: string | null) {
    return apiKey?.trim() || 'sk-REPLACE_ME';
  }

  function setupContext(apiKey?: string | null): SetupContext {
    return {
      apiOrigin: apiOrigin.value,
      apiBaseUrl: apiBaseUrl.value,
      apiKey: normalizedApiKey(apiKey),
      apiPort: apiPort.value,
    };
  }

  const setupScriptBlock = computed(() =>
    createSetupScriptBlock(
      newSetupClient.value,
      newSetupPlatform.value,
      setupContext(newKey.value?.key),
    ),
  );
  const connectionText = computed(() => createConnectionText(setupContext(newKey.value?.key)));
  const ccSwitchLinks = computed(() => createCcSwitchLinks(setupContext(newKey.value?.key)));
  const connectionEndpoints = computed(() => [
    { label: 'Codex', value: apiBaseUrl.value },
    { label: 'Claude', value: apiOrigin.value },
    { label: 'OpenCode', value: apiBaseUrl.value },
    { label: '端口', value: apiPort.value },
  ]);

  async function copy(text: string, successMessage = '已复制') {
    try {
      await navigator.clipboard.writeText(text);
      ElMessage.success(successMessage);
    } catch {
      ElMessage.warning('请手动复制');
    }
  }

  function scriptCopyMessage(block: SetupScriptBlock) {
    return `已复制 ${block.clientLabel} / ${block.platformLabel} 配置`;
  }

  function setupFileCopyMessage(file: SetupFileBlock) {
    return `已复制 ${file.path}`;
  }

  function fullApiKeyOf(row?: ApiKey | null) {
    const value = row?.key?.trim() ?? '';
    return value.startsWith('sk-') ? value : '';
  }

  function isUsableApiKey(value?: string | null) {
    const key = value?.trim() ?? '';
    return key.length > 8 && !key.includes('REPLACE');
  }

  function rowApiKeyPlaceholder(row?: ApiKey | null) {
    if (fullApiKeyOf(row)) {
      return fullApiKeyOf(row);
    }
    return row?.keyPrefix ? `${row.keyPrefix}REPLACE_REST_OF_KEY` : 'sk-REPLACE_ME';
  }

  /* 新版密钥会保留完整值；历史密钥只剩哈希时仍允许手动粘贴。 */
  const configVisible = ref(false);
  const configKey = ref<ApiKey | null>(null);
  const configFullApiKey = ref('');
  const configDialogTitle = computed(() =>
    configKey.value ? `${configKey.value.name} 客户端配置` : '客户端配置',
  );
  const pastedConfigApiKey = computed(() => configFullApiKey.value.trim());
  const storedConfigApiKey = computed(() => fullApiKeyOf(configKey.value));
  const effectiveConfigApiKey = computed(
    () => pastedConfigApiKey.value || storedConfigApiKey.value,
  );
  const hasConfigFullApiKey = computed(() => {
    const value = effectiveConfigApiKey.value;
    return isUsableApiKey(value);
  });
  const configApiKey = computed(() => {
    if (hasConfigFullApiKey.value) {
      return effectiveConfigApiKey.value;
    }
    return rowApiKeyPlaceholder(configKey.value);
  });
  const hasStoredConfigApiKey = computed(() => Boolean(storedConfigApiKey.value));
  const configKeyNotice = computed(() =>
    hasStoredConfigApiKey.value
      ? '当前 API Key 可直接通过CC Switch进行客户端配置。'
      : '此为旧版 API Key，数据库只保留哈希和前缀，无法反推完整密钥；请重新创建或手动粘贴完整密钥。',
  );
  const configKeyNoticeType = computed(() => (hasStoredConfigApiKey.value ? 'success' : 'warning'));
  const configKeyHint = computed(() =>
    hasStoredConfigApiKey.value
      ? '完整密钥已自动填入；复制配置会使用当前用户自己的 API Key。'
      : '粘贴创建时保存的完整密钥；只含前缀的占位密钥不能用于客户端鉴权。',
  );

  function fullConfigMissingMessage(action: string) {
    return hasStoredConfigApiKey.value
      ? `当前 API Key 异常，无法${action}`
      : `请先粘贴完整 API Key，否则无法${action}`;
  }

  async function copyRowApiKey(row: ApiKey) {
    const key = fullApiKeyOf(row);
    if (!key) {
      ElMessage.warning('旧版 API Key 只保存哈希，无法直接复制完整密钥，请重新创建');
      return;
    }
    await copy(key, '已复制 API Key');
  }

  const configConnectionText = computed(() =>
    createConnectionText(setupContext(configApiKey.value)),
  );
  const configScriptBlock = computed(() =>
    createSetupScriptBlock(
      configSetupClient.value,
      configSetupPlatform.value,
      setupContext(configApiKey.value),
    ),
  );
  const configCcSwitchLinks = computed(() => createCcSwitchLinks(setupContext(configApiKey.value)));

  function openShellConfig(row: ApiKey) {
    configKey.value = row;
    configFullApiKey.value = fullApiKeyOf(row);
    configVisible.value = true;
  }

  async function copyConfigConnectionText() {
    if (!hasConfigFullApiKey.value) {
      ElMessage.warning(fullConfigMissingMessage('复制连接信息'));
      return;
    }
    await copy(configConnectionText.value, '已复制连接信息');
  }

  async function copyConfigScript(block: SetupScriptBlock) {
    if (!hasConfigFullApiKey.value) {
      ElMessage.warning(fullConfigMissingMessage('复制客户端配置'));
      return;
    }
    await copy(block.script, scriptCopyMessage(block));
  }

  async function copyConfigSetupFile(file: SetupFileBlock) {
    if (!hasConfigFullApiKey.value) {
      ElMessage.warning(fullConfigMissingMessage('复制客户端配置文件'));
      return;
    }
    await copy(file.content, setupFileCopyMessage(file));
  }

  async function copyConfigCcSwitch(link: CcSwitchLink) {
    if (!hasConfigFullApiKey.value) {
      ElMessage.warning(fullConfigMissingMessage('复制 CC Switch 链接'));
      return;
    }
    await copy(link.url, `已复制 ${link.label} CC Switch 链接`);
  }

  function openConfigCcSwitch(link: CcSwitchLink) {
    if (!hasConfigFullApiKey.value) {
      ElMessage.warning(fullConfigMissingMessage('打开 CC Switch 链接'));
      return;
    }
    window.location.href = link.url;
  }

  function openCcSwitch(link: CcSwitchLink) {
    window.location.href = link.url;
  }

  async function submitForm() {
    if (!formRef.value) return;
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return;

    formSubmitting.value = true;
    try {
      if (formMode.value === 'create') {
        const payload: CreateApiKeyRequest = {
          billingSource: form.billingSource,
          name: form.name,
          creditsLimit: form.creditsLimit,
          modelProvider: form.modelProvider,
        };
        const created = await apiKeysApi.create(payload);
        formVisible.value = false;
        newKey.value = created;
        newKeyVisible.value = true;
      } else if (editingKeyId.value) {
        await apiKeysApi.update(editingKeyId.value, {
          billingSource: form.billingSource,
          clearCreditsLimit: form.creditsLimit == null,
          name: form.name,
          creditsLimit: form.creditsLimit,
          modelProvider: form.modelProvider,
          enabled: form.enabled,
        });
        ElMessage.success('已更新');
        formVisible.value = false;
      }
      await loadKeys();
    } finally {
      formSubmitting.value = false;
    }
  }

  async function toggleEnabled(row: ApiKey) {
    await apiKeysApi.update(row.keyId, { enabled: !row.enabled });
    ElMessage.success(row.enabled ? '已停用' : '已启用');
    await loadKeys();
  }

  async function removeKey(row: ApiKey) {
    await ElMessageBox.confirm(`确认删除 ${row.name}？`, '危险操作', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'error',
    }).catch(() => Promise.reject(new Error('cancel')));
    await apiKeysApi.remove(row.keyId);
    ElMessage.success('已删除');
    await loadKeys();
  }

  async function handleRowCommand(command: string, row: ApiKey) {
    if (command === 'toggle') {
      await toggleEnabled(row);
      return;
    }
    if (command === 'copy') {
      await copyRowApiKey(row);
      return;
    }
    if (command === 'remove') {
      await removeKey(row);
    }
  }

  function providerLabel(provider?: string | null) {
    return !provider || provider === '*' ? '全部厂商' : provider;
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

  function usagePercent(row: ApiKey) {
    if (row.creditsLimit == null || row.creditsLimit <= 0) return null;
    return Math.min(100, Math.max(0, ((row.totalCredits ?? 0) / row.creditsLimit) * 100));
  }

  onMounted(async () => {
    await Promise.all([loadKeys(), loadModelProviders()]);
  });
</script>

<template>
  <div class="api-keys-view">
    <PageSection title="API Key" subtitle="访问与计费" :icon="Key">
      <template #actions>
        <el-button :icon="CopyDocument" @click="copy(apiBaseUrl)">复制 API 地址</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建 API Key</el-button>
      </template>
      <template #summary>
        <MetricsPanel :items="keyMetrics" />
      </template>
      <template #toolbar>
        <el-input
          v-model="keySearch"
          :prefix-icon="Search"
          placeholder="搜索名称或模型分类"
          clearable
        />
        <el-select v-model="sourceFilter" aria-label="筛选计费途径">
          <el-option label="全部计费途径" value="" />
          <el-option label="余额计费" value="BALANCE" />
          <el-option label="Coding Plan" value="CODING_PLAN" />
        </el-select>
        <span class="toolbar-count">
          {{ hasFilter ? `筛选 ${filteredKeys.length} / ${apiKeys.length}` : `${apiKeys.length} 个密钥` }}
        </span>
        <el-tooltip content="刷新密钥">
          <el-button :icon="Refresh" aria-label="刷新密钥" :loading="loading" @click="loadKeys" />
        </el-tooltip>
      </template>
      <el-table v-loading="loading" class="api-key-table" :data="filteredKeys">
        <template #empty>
          <el-empty
            :image-size="88"
            :description="hasFilter ? '没有匹配的密钥，试试换个筛选条件' : '还没有 API Key'"
          >
            <el-button v-if="!hasFilter" type="primary" :icon="Plus" @click="openCreate">
              新建 API Key
            </el-button>
          </el-empty>
        </template>
        <el-table-column label="密钥" min-width="220">
          <template #default="{ row }">
            <div class="key-identity">
              <strong class="key-row-name">{{ row.name }}</strong>
              <button
                type="button"
                class="key-prefix"
                :title="fullApiKeyOf(row) ? '复制完整密钥' : row.keyPrefix"
                @click="copyRowApiKey(row)"
              >
                {{ row.keyPrefix }}…
              </button>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="计费途径" min-width="132">
          <template #default="{ row }">
            <span
              class="billing-chip"
              :class="{ 'is-plan': row.billingSource === 'CODING_PLAN' }"
            >
              <el-icon>
                <Monitor v-if="row.billingSource === 'CODING_PLAN'" />
                <Wallet v-else />
              </el-icon>
              {{ row.billingSource === 'CODING_PLAN' ? 'Coding Plan' : '余额计费' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="模型分类" min-width="150">
          <template #default="{ row }">
            <div class="provider-cell">
              <ProviderLogo :provider="providerLabel(row.modelProvider)" :size="22" />
              <span>{{ providerLabel(row.modelProvider) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="auth.isAdmin" label="归属用户" min-width="140">
          <template #default="{ row }">
            <span class="mono-id" :title="row.userId || ''">{{ shorten(row.userId, 8, 6) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="key-state" :class="{ active: row.enabled }">
              <i />{{ row.enabled ? '已启用' : '已停用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="用量 / 上限" min-width="168">
          <template #default="{ row }">
            <div class="usage-cell">
              <div class="usage-cell__nums">
                <b>{{ formatNumber(row.totalCredits ?? 0) }}</b>
                <span>{{ row.creditsLimit == null ? '不限' : formatNumber(row.creditsLimit) }}</span>
              </div>
              <div v-if="usagePercent(row) != null" class="usage-bar" aria-hidden="true">
                <i :style="{ width: `${usagePercent(row)}%` }" />
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="最近使用" min-width="128">
          <template #default="{ row }">
            <span class="key-last-used" :title="row.lastUsedAt ? formatDateTime(row.lastUsedAt) : ''">
              {{ formatLastUsed(row.lastUsedAt) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-tooltip content="复制密钥">
                <el-button :icon="CopyDocument" text aria-label="复制密钥" @click="copyRowApiKey(row)" />
              </el-tooltip>
              <el-tooltip content="编辑">
                <el-button :icon="EditPen" text aria-label="编辑密钥" @click="openEdit(row)" />
              </el-tooltip>
              <el-tooltip content="客户端配置">
                <el-button :icon="Setting" text aria-label="客户端配置" @click="openShellConfig(row)" />
              </el-tooltip>
              <el-dropdown trigger="click" @command="(command: string) => handleRowCommand(command, row)">
                <button class="more-action" type="button" aria-label="更多操作">
                  <el-icon><MoreFilled /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="toggle">{{ row.enabled ? '停用' : '启用' }}</el-dropdown-item>
                    <el-dropdown-item command="remove" divided>删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </PageSection>

    <el-dialog
      v-model="formVisible"
      class="api-key-form-dialog"
      :title="formMode === 'create' ? '新建 API Key' : '编辑 API Key'"
      width="min(480px, calc(100vw - 24px))"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="api-key-form">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="64" placeholder="例如 Claude Code、Codex" />
        </el-form-item>
        <el-form-item label="计费途径" prop="billingSource">
          <el-radio-group v-model="form.billingSource" class="billing-choice" aria-label="计费途径">
            <el-radio-button label="BALANCE" value="BALANCE">
              <el-icon><Wallet /></el-icon>余额计费
            </el-radio-button>
            <el-radio-button label="CODING_PLAN" value="CODING_PLAN">
              <el-icon><Monitor /></el-icon>Coding Plan
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="模型分类" prop="modelProvider">
          <el-select
            v-model="form.modelProvider"
            placeholder="选择可访问的厂商"
            no-data-text="暂无可用厂商"
            class="provider-select"
            aria-label="选择 API Key 模型分类"
          >
            <template v-if="form.modelProvider" #prefix>
              <ProviderLogo :provider="form.modelProvider" :size="18" />
            </template>
            <el-option
              v-for="provider in modelProviders"
              :key="provider.name"
              :label="provider.name"
              :value="provider.name"
            >
              <div class="provider-option">
                <ProviderLogo :provider="provider.name" :size="24" />
                <strong>{{ provider.name }}</strong>
                <span>{{ provider.count }} 个可用模型</span>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
        <div
          v-if="!auth.isAdmin"
          class="billing-availability"
          :class="{ 'is-plan': form.billingSource === 'CODING_PLAN' }"
        >
          <el-icon>
            <Monitor v-if="form.billingSource === 'CODING_PLAN'" />
            <Wallet v-else />
          </el-icon>
          <div>
            <span>{{
              form.billingSource === 'CODING_PLAN' ? '当前分类套餐可用额度' : '可用余额'
            }}</span>
            <strong>
              {{
                form.billingSource === 'CODING_PLAN' && selectedPlanBalance === null
                  ? '暂不可用'
                  : formatNumber(
                      form.billingSource === 'CODING_PLAN'
                        ? Number(selectedPlanBalance)
                        : (auth.user?.balance ?? 0),
                    )
              }}
              <small>credits</small>
            </strong>
          </div>
        </div>
        <p class="billing-contract">
          {{
            form.billingSource === 'CODING_PLAN'
              ? '仅扣 Coding Plan 额度，套餐额度不足时停止调用。'
              : '仅扣钱包余额，余额不足时停止调用。'
          }}
        </p>
        <el-form-item label="累计额度上限（可选）">
          <el-input-number
            v-model="form.creditsLimit"
            :min="0"
            :step="100"
            controls-position="right"
            placeholder="不限制"
            style="width: 100%"
          />
          <p class="field-hint">留空表示不限制累计调用额度</p>
        </el-form-item>
        <el-form-item v-if="formMode === 'edit'" label="状态" class="status-field">
          <div class="status-switch">
            <el-switch v-model="form.enabled" />
            <span>{{ form.enabled ? '可用' : '已停用' }}</span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="formSubmitting" @click="submitForm">
          {{ formMode === 'create' ? '创建密钥' : '保存修改' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="configVisible"
      :title="configDialogTitle"
      width="min(900px, calc(100vw - 32px))"
    >
      <div class="config-dialog-body">
        <el-alert
          class="secret-alert"
          :title="configKeyNotice"
          :type="configKeyNoticeType"
          :closable="false"
          show-icon
        />

        <section v-if="configKey" class="secret-panel">
          <div class="secret-panel__head">
            <div>
              <h3>连接信息</h3>
              <p>{{ configKeyHint }}</p>
            </div>
            <el-button
              size="small"
              :disabled="!hasConfigFullApiKey"
              @click="copyConfigConnectionText"
            >
              复制连接信息
            </el-button>
          </div>

          <div class="full-key-input">
            <span class="full-key-input__label">完整 API Key</span>
            <el-input
              v-model="configFullApiKey"
              clearable
              show-password
              placeholder="完整 sk-... 密钥"
            />
          </div>

          <div class="secret-fields">
            <div class="secret-field secret-field--key">
              <span class="secret-field__label">API Key</span>
              <code class="secret-field__value mono" :title="configApiKey">{{ configApiKey }}</code>
              <el-button size="small" type="primary" :disabled="!hasConfigFullApiKey" @click="copy(configApiKey)">
                复制
              </el-button>
            </div>
            <div class="secret-field">
              <span class="secret-field__label">名称</span>
              <code class="secret-field__value" :title="configKey.name">{{ configKey.name }}</code>
            </div>
            <div class="secret-field">
              <span class="secret-field__label">模型分类</span>
              <code class="secret-field__value">{{ providerLabel(configKey.modelProvider) }}</code>
            </div>
            <div
              v-for="field in connectionEndpoints"
              :key="field.label"
              class="secret-field"
            >
              <span class="secret-field__label">{{ field.label }}</span>
              <code class="secret-field__value mono" :title="field.value">{{ field.value }}</code>
              <el-button size="small" @click="copy(field.value)">复制</el-button>
            </div>
          </div>
        </section>

        <section class="secret-panel setup-scripts">
          <div class="secret-panel__head">
            <div>
              <h3>客户端配置</h3>
              <p>将以下配置添加到对应客户端；Claude Code 可直接在终端运行。</p>
            </div>
            <el-button
              size="small"
              type="primary"
              :disabled="!hasConfigFullApiKey"
              @click="copyConfigScript(configScriptBlock)"
            >
              复制当前配置
            </el-button>
          </div>

          <div class="setup-controls">
            <div class="setup-control">
              <span class="setup-control__label">客户端</span>
              <el-radio-group v-model="configSetupClient" size="small">
                <el-radio-button v-for="item in setupClients" :key="item.value" :label="item.value">
                  {{ item.label }}
                </el-radio-button>
              </el-radio-group>
            </div>
            <div class="setup-control">
              <span class="setup-control__label">平台</span>
              <el-radio-group v-model="configSetupPlatform" size="small">
                <el-radio-button
                  v-for="item in setupPlatforms"
                  :key="item.value"
                  :label="item.value"
                >
                  {{ item.label }}
                </el-radio-button>
              </el-radio-group>
            </div>
          </div>

          <div class="script-preview">
            <div>
              <h4>{{ configScriptBlock.clientLabel }} / {{ configScriptBlock.platformLabel }}</h4>
              <p>
                {{ configScriptBlock.clientDescription }}；{{
                  configScriptBlock.platformDescription
                }}。
              </p>
            </div>
            <div v-for="file in configScriptBlock.files" :key="file.path" class="setup-file">
              <div class="setup-file__head">
                <span class="mono">{{ file.path }}</span>
                <el-button
                  size="small"
                  :disabled="!hasConfigFullApiKey"
                  @click="copyConfigSetupFile(file)"
                >
                  复制
                </el-button>
              </div>
              <p v-if="file.hint" class="setup-file__hint">{{ file.hint }}</p>
              <pre class="setup-scripts__code"><code>{{ file.content }}</code></pre>
            </div>
          </div>
        </section>

        <section class="secret-panel cc-switch-panel">
          <div class="secret-panel__head">
            <div>
              <h3>CC Switch 一键连接</h3>
              <p>打开 CC Switch 后会导入 Starry API 供应商配置。</p>
            </div>
          </div>

          <div class="cc-switch-list">
            <div v-for="link in configCcSwitchLinks" :key="link.client" class="cc-switch-item">
              <div class="cc-switch-item__meta">
                <strong>{{ link.label }}</strong>
                <span>{{ link.description }}</span>
              </div>
              <div class="cc-switch-item__actions">
                <el-button
                  size="small"
                  type="primary"
                  :disabled="!hasConfigFullApiKey"
                  @click="openConfigCcSwitch(link)"
                >
                  打开
                </el-button>
                <el-button
                  size="small"
                  :disabled="!hasConfigFullApiKey"
                  @click="copyConfigCcSwitch(link)"
                >
                  复制链接
                </el-button>
              </div>
            </div>
          </div>
        </section>
      </div>

      <template #footer>
        <el-button type="primary" @click="configVisible = false">完成</el-button>
      </template>
    </el-dialog>

    <!-- 创建后密钥展示 -->
    <el-dialog
      v-model="newKeyVisible"
      title="保存你的新密钥"
      width="760px"
      :close-on-click-modal="false"
    >
      <div class="secret-dialog-body">
        <el-alert
          class="secret-alert"
          title="此密钥已按当前用户独立保存，后续可在列表中直接复制。"
          type="success"
          :closable="false"
          show-icon
        />

        <section v-if="newKey" class="secret-panel">
          <div class="secret-panel__head">
            <div>
              <h3>连接信息</h3>
              <p>客户端使用下面的 Base URL 和 API Key 调用代理服务。</p>
            </div>
            <el-button size="small" @click="copy(connectionText)">复制全部</el-button>
          </div>

          <div class="secret-fields">
            <div class="secret-field secret-field--key">
              <span class="secret-field__label">API Key</span>
              <code class="secret-field__value mono" :title="newKey.key">{{ newKey.key }}</code>
              <el-button size="small" type="primary" @click="copy(newKey.key)">复制</el-button>
            </div>
            <div class="secret-field">
              <span class="secret-field__label">模型分类</span>
              <code class="secret-field__value">{{ providerLabel(newKey.modelProvider) }}</code>
            </div>
            <div
              v-for="field in connectionEndpoints"
              :key="field.label"
              class="secret-field"
            >
              <span class="secret-field__label">{{ field.label }}</span>
              <code class="secret-field__value mono" :title="field.value">{{ field.value }}</code>
              <el-button size="small" @click="copy(field.value)">复制</el-button>
            </div>
          </div>
        </section>

        <section v-if="newKey" class="secret-panel setup-scripts">
          <div class="secret-panel__head">
            <div>
              <h3>客户端配置</h3>
              <p>将以下配置添加到对应客户端；新密钥会直接写入配置内容。</p>
            </div>
            <el-button
              size="small"
              type="primary"
              @click="copy(setupScriptBlock.script, scriptCopyMessage(setupScriptBlock))"
            >
              复制当前配置
            </el-button>
          </div>

          <div class="setup-controls">
            <div class="setup-control">
              <span class="setup-control__label">客户端</span>
              <el-radio-group v-model="newSetupClient" size="small">
                <el-radio-button v-for="item in setupClients" :key="item.value" :label="item.value">
                  {{ item.label }}
                </el-radio-button>
              </el-radio-group>
            </div>
            <div class="setup-control">
              <span class="setup-control__label">平台</span>
              <el-radio-group v-model="newSetupPlatform" size="small">
                <el-radio-button
                  v-for="item in setupPlatforms"
                  :key="item.value"
                  :label="item.value"
                >
                  {{ item.label }}
                </el-radio-button>
              </el-radio-group>
            </div>
          </div>

          <div class="script-preview">
            <div>
              <h4>{{ setupScriptBlock.clientLabel }} / {{ setupScriptBlock.platformLabel }}</h4>
              <p>
                {{ setupScriptBlock.clientDescription }}；{{
                  setupScriptBlock.platformDescription
                }}。
              </p>
            </div>
            <div v-for="file in setupScriptBlock.files" :key="file.path" class="setup-file">
              <div class="setup-file__head">
                <span class="mono">{{ file.path }}</span>
                <el-button size="small" @click="copy(file.content, setupFileCopyMessage(file))">
                  复制
                </el-button>
              </div>
              <p v-if="file.hint" class="setup-file__hint">{{ file.hint }}</p>
              <pre class="setup-scripts__code"><code>{{ file.content }}</code></pre>
            </div>
          </div>
        </section>

        <section v-if="newKey" class="secret-panel cc-switch-panel">
          <div class="secret-panel__head">
            <div>
              <h3>CC Switch 一键连接</h3>
              <p>打开 CC Switch 后会导入 Starry API 供应商配置。</p>
            </div>
          </div>

          <div class="cc-switch-list">
            <div v-for="link in ccSwitchLinks" :key="link.client" class="cc-switch-item">
              <div class="cc-switch-item__meta">
                <strong>{{ link.label }}</strong>
                <span>{{ link.description }}</span>
              </div>
              <div class="cc-switch-item__actions">
                <el-button size="small" type="primary" @click="openCcSwitch(link)">打开</el-button>
                <el-button
                  size="small"
                  @click="copy(link.url, `已复制 ${link.label} CC Switch 链接`)"
                >
                  复制链接
                </el-button>
              </div>
            </div>
          </div>
        </section>
      </div>
      <template #footer>
        <el-button type="primary" @click="newKeyVisible = false">已妥善保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
  .page-section__toolbar :deep(.el-input) {
    max-width: 280px;
  }
  .page-section__toolbar :deep(.el-select) {
    width: 156px;
  }
  .toolbar-count {
    margin-left: auto;
    color: var(--kp-text-secondary);
    font-size: 12px;
  }

  .key-identity {
    display: flex;
    flex-direction: column;
    gap: 5px;
    min-width: 0;
  }
  .key-row-name {
    font-size: 13px;
    font-weight: 600;
    overflow-wrap: anywhere;
  }
  .key-prefix {
    width: fit-content;
    max-width: 100%;
    margin: 0;
    padding: 0;
    overflow: hidden;
    border: 0;
    background: transparent;
    color: var(--kp-text-muted);
    cursor: pointer;
    font: 11px/1.4 ui-monospace, SFMono-Regular, Menlo, monospace;
    text-align: left;
    text-overflow: ellipsis;
    white-space: nowrap;

    &:hover {
      color: var(--kp-primary);
    }
  }
  .billing-chip {
    display: inline-flex;
    gap: 6px;
    align-items: center;
    padding: 3px 8px 3px 6px;
    border: 1px solid color-mix(in srgb, var(--kp-primary) 22%, transparent);
    border-radius: 999px;
    background: color-mix(in srgb, var(--kp-primary) 8%, transparent);
    color: var(--kp-text);
    font-size: 12px;

    > .el-icon {
      color: var(--kp-primary);
      font-size: 14px;
    }

    &.is-plan {
      border-color: color-mix(in srgb, #268879 28%, transparent);
      background: color-mix(in srgb, #268879 10%, transparent);

      > .el-icon {
        color: #268879;
      }
    }
  }
  .key-state {
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

    &.active {
      color: #268879;
    }
  }
  .usage-cell {
    display: grid;
    gap: 6px;
    min-width: 0;

    &__nums {
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
      }
    }
  }
  .usage-bar {
    height: 4px;
    overflow: hidden;
    border-radius: 99px;
    background: color-mix(in srgb, var(--kp-text-muted) 18%, transparent);

    i {
      display: block;
      height: 100%;
      border-radius: inherit;
      background: var(--kp-primary);
    }
  }
  .key-last-used {
    color: var(--kp-text-secondary);
    font-size: 12px;
  }
  .mono-id {
    color: var(--kp-text-secondary);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
  }
  .billing-choice {
    display: grid;
    width: 100%;
    grid-template-columns: 1fr 1fr;
  }
  .billing-choice :deep(.el-radio-button__inner) {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 9px;
    width: 100%;
    height: 43px;
  }
  .billing-availability {
    display: flex;
    align-items: center;
    gap: 12px;
    margin: 4px 0 2px;
    padding: 12px 14px;
    border: 1px solid var(--el-border-color);
    border-radius: 10px;
    background: var(--kp-surface-strong);
  }
  .billing-availability > .el-icon {
    font-size: 22px;
    color: var(--kp-primary);
  }
  .billing-availability.is-plan > .el-icon {
    color: #268879;
  }
  .billing-availability span {
    display: block;
    font-size: 11px;
    color: var(--kp-text-muted);
  }
  .billing-availability strong {
    display: block;
    margin-top: 4px;
    font-size: 18px;
    font-weight: 600;
  }
  .billing-availability small {
    font-size: 11px;
    font-weight: 400;
    color: var(--kp-text-muted);
  }
  .billing-contract {
    font-size: 12px;
    color: var(--kp-text-secondary);
    line-height: 1.6;
    margin: 10px 0 18px;
  }
  @media (max-width: 640px) {
    .page-section__toolbar :deep(.el-input) {
      max-width: none;
    }
    .toolbar-count {
      margin-left: 0;
      width: 100%;
    }
  }
  .provider-cell {
    display: flex;
    align-items: center;
    min-width: 0;
    gap: 8px;

    span {
      overflow: hidden;
      font-size: 12px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .api-key-table {
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

  .api-key-form {
    .el-form-item {
      margin-bottom: 20px;
    }
  }

  .field-hint {
    width: 100%;
    margin: 5px 0 0;
    color: var(--kp-text-muted);
    font-size: 11.5px;
    line-height: 1.5;
  }

  .status-field {
    :deep(.el-form-item__content) {
      min-height: 32px;
      align-items: center;
    }
  }

  .status-switch {
    display: flex;
    align-items: center;
    gap: 10px;
    color: var(--kp-text-secondary);
    font-size: 13px;
  }

  .provider-select {
    width: 100%;

    :deep(.el-select__prefix) {
      align-items: center;
    }
  }

  .provider-option {
    display: flex;
    align-items: center;
    min-width: 0;
    gap: 10px;

    strong {
      overflow: hidden;
      font-size: 13px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    > span {
      margin-left: auto;
      color: var(--kp-text-muted);
      font-size: 11.5px;
      white-space: nowrap;
    }
  }

  .secret-dialog-body,
  .config-dialog-body {
    display: grid;
    gap: 14px;
  }

  @media (max-width: 520px) {
    :global(.api-key-form-dialog .el-dialog__body),
    :global(.api-key-form-dialog .el-dialog__footer) {
      width: auto;
      min-width: 0;
    }

    :global(.api-key-form-dialog .el-dialog__body) {
      padding-right: 16px;
      padding-left: 16px;
    }
  }

  .secret-alert {
    --el-alert-padding: 10px 12px;
  }

  .secret-panel {
    padding: 14px;
    border: 1px solid rgba(91, 141, 239, 0.16);
    border-radius: 12px;
    background: rgba(255, 255, 255, 0.52);

    &__head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 12px;
      margin-bottom: 12px;

      h3 {
        margin: 0;
        font-size: 15px;
        font-weight: 700;
      }

      p {
        margin: 2px 0 0;
        color: var(--kp-text-secondary);
        font-size: 12px;
      }
    }
  }

  .secret-fields {
    display: grid;
    gap: 8px;
  }

  .full-key-input {
    display: grid;
    grid-template-columns: 96px minmax(0, 1fr);
    align-items: center;
    gap: 12px;
    margin-bottom: 10px;
    padding: 8px 10px;
    border: 1px solid rgba(239, 68, 68, 0.18);
    border-radius: 8px;
    background: rgba(254, 242, 242, 0.58);

    &__label {
      color: var(--kp-text-secondary);
      font-size: 12px;
      font-weight: 700;
    }
  }

  .secret-field {
    min-height: 46px;
    display: grid;
    grid-template-columns: 82px minmax(0, 1fr) auto;
    align-items: center;
    gap: 12px;
    padding: 8px 10px;
    border: 1px solid rgba(15, 23, 42, 0.08);
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.7);

    &__label {
      color: var(--kp-text-secondary);
      font-size: 12px;
      font-weight: 700;
      text-transform: uppercase;
    }

    &__value {
      min-width: 0;
      overflow: hidden;
      color: var(--kp-text);
      font-size: 13px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &--compact {
      grid-template-columns: 82px 120px auto;
      justify-content: start;
    }

    &--key {
      border-color: color-mix(in srgb, var(--kp-primary) 28%, transparent);
      background: color-mix(in srgb, var(--kp-primary) 8%, white);

      .secret-field__label {
        color: var(--kp-primary);
      }
    }
  }

  .setup-scripts {
    &__code {
      max-height: 280px;
      overflow: auto;
      margin: 0;
      padding: 12px 14px;
      border: 1px solid rgba(148, 163, 184, 0.2);
      border-radius: 8px;
      background: rgba(15, 23, 42, 0.86);
      color: #e5edff;
      font-size: 12px;
      line-height: 1.6;
      white-space: pre;
    }
  }

  .setup-controls {
    display: grid;
    grid-template-columns: 1fr;
    gap: 10px;
    margin-bottom: 12px;
  }

  .setup-control {
    display: grid;
    grid-template-columns: 68px minmax(0, 1fr);
    align-items: center;
    gap: 10px;

    &__label {
      color: var(--kp-text-secondary);
      font-size: 12px;
      font-weight: 700;
    }
  }

  .script-preview {
    display: grid;
    gap: 10px;

    h4 {
      margin: 0;
      color: var(--kp-text);
      font-size: 14px;
      font-weight: 700;
    }

    p {
      margin: 2px 0 0;
      color: var(--kp-text-secondary);
      font-size: 12px;
    }
  }

  .setup-file {
    display: grid;
    gap: 6px;

    &__head {
      min-height: 36px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 10px;
      padding: 8px 10px;
      border: 1px solid rgba(148, 163, 184, 0.2);
      border-radius: 8px;
      background: rgba(248, 250, 252, 0.78);

      span {
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }

    &__hint {
      margin: 0;
      color: var(--kp-text-secondary);
      font-size: 12px;
    }
  }

  .cc-switch-list {
    display: grid;
    gap: 8px;
  }

  .cc-switch-item {
    min-height: 56px;
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
    gap: 12px;
    padding: 10px;
    border: 1px solid rgba(15, 23, 42, 0.08);
    border-radius: 8px;
    background: rgba(255, 255, 255, 0.7);

    &__meta {
      min-width: 0;
      display: grid;
      gap: 2px;

      strong {
        color: var(--kp-text);
        font-size: 13px;
      }

      span {
        color: var(--kp-text-secondary);
        font-size: 12px;
      }
    }

    &__actions {
      display: flex;
      justify-content: flex-end;
      gap: 8px;
    }
  }

  @media (max-width: 720px) {
    .secret-panel__head {
      flex-direction: column;
    }

    .secret-field,
    .secret-field--compact,
    .full-key-input,
    .setup-control {
      grid-template-columns: 1fr;
      gap: 6px;
      align-items: start;
    }

    .secret-field .el-button {
      justify-self: start;
    }

    .setup-file__head,
    .cc-switch-item {
      grid-template-columns: 1fr;
    }

    .setup-file__head {
      display: grid;
    }

    .cc-switch-item__actions {
      justify-content: flex-start;
      flex-wrap: wrap;
    }
  }
</style>
