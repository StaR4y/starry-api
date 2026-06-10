<script setup lang="ts">
  /**
   * 上游账号管理。
   *
   * 覆盖 /admin/accounts 全部能力：
   * - 列表 / 新增 / 编辑 / 删除
   * - 启用/停用、挂起/恢复（reset）
   * - 批量 JSON 导入（支持 upsert）
   *
   * 表单与对话框由本组件直接管理，避免拆分过细造成的目录爆炸。
   * 后续表单复杂化时可再抽 AccountForm.vue。
   */
  import { computed, onMounted, reactive, ref, watch } from 'vue';
  import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
  import PageSection from '@/components/PageSection.vue';
  import { accountsApi } from '@/api';
  import { ApiError } from '@/utils/http';
  import type {
    Account,
    AccountSettings,
    CreateAccountRequest,
    ImportAccountItem,
    ImportAccountsResult,
    TestAccountItem,
    TestModelOption,
    TestModelOptionsResult,
    TestAccountsResult,
  } from '@/types/api';
  import { formatDateTime, formatNumber, shorten } from '@/utils/format';

  /* ============= 列表 ============= */

  const loading = ref(false);
  const accounts = ref<Account[]>([]);
  const keyword = ref('');
  const accountSettings = ref<AccountSettings>({ gptFastModeEnabled: false });
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
    const active = accounts.value.filter((a) => a.enabled && !a.suspendedAt && !a.quotaExhaustedAt).length;
    const abnormal = accounts.value.filter((a) => a.suspendedAt || a.quotaExhaustedAt || !a.enabled).length;
    
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

  function statusOf(account: Account) {
    if (!account.enabled) return { label: '已停用', tone: 'info' as const };
    if (account.suspendedAt) return { label: '挂起', tone: 'danger' as const };
    if (account.quotaExhaustedAt) return { label: '配额用尽', tone: 'warning' as const };
    return { label: '可用', tone: 'success' as const };
  }

  async function loadAccounts() {
    loading.value = true;
    try {
      accounts.value = await accountsApi.list();
    } finally {
      loading.value = false;
    }
  }

  async function loadAccountSettings() {
    try {
      accountSettings.value = await accountsApi.settings();
    } catch (err) {
      if (err instanceof ApiError) ElMessage.warning(`Fast 模式配置加载失败：${err.message}`);
    }
  }

  async function toggleGptFastMode(value: string | number | boolean) {
    settingsSaving.value = true;
    try {
      accountSettings.value = await accountsApi.updateSettings({
        gptFastModeEnabled: Boolean(value),
      });
      ElMessage.success(accountSettings.value.gptFastModeEnabled ? '已允许 GPT Fast 模式' : '已关闭 GPT Fast 模式');
    } catch (err) {
      accountSettings.value.gptFastModeEnabled = !Boolean(value);
      if (err instanceof ApiError) ElMessage.error(err.message);
    } finally {
      settingsSaving.value = false;
    }
  }

  /* ============= 新建 / 编辑 ============= */

  type FormMode = 'create' | 'edit';
  const OPENAI_COMPATIBLE_PROVIDER = 'OpenAI-compatible';

  const formVisible = ref(false);
  const formMode = ref<FormMode>('create');
  const formRef = ref<FormInstance>();
  const formSubmitting = ref(false);
  const formAccountId = ref<string | null>(null);

  // 与后端 DTO 字段一致；可选字段使用空字符串占位，提交时再清洗
  const form = reactive({
    accountId: '',
    email: '',
    accessToken: '',
    refreshToken: '',
    clientId: '',
    clientSecret: '',
    region: 'us-east-1',
    authMethod: 'idc',
    provider: 'BuilderID',
    baseUrl: '',
    upstreamAccountId: '',
    idToken: '',
    profileArn: '',
    machineId: '',
    proxyUrl: '',
    quotaLimit: undefined as number | undefined,
    enabled: true,
  });

  const isOpenAiCompatibleForm = computed(() => isOpenAiCompatibleProvider(form.provider));
  const isOpenAiApiKeyForm = computed(() => isOpenAiCompatibleForm.value || normalized(form.provider) === 'openai');
  const accessTokenLabel = computed(() => {
    const suffix = formMode.value === 'edit' ? '（留空则不修改）' : '';
    return isOpenAiApiKeyForm.value ? `API Key${suffix}` : `Access Token${suffix}`;
  });
  const accessTokenPlaceholder = computed(() =>
    isOpenAiApiKeyForm.value ? '输入上游网站 API Key，如 sk-...' : '输入 Access Token',
  );
  const baseUrlLabel = computed(() => isOpenAiCompatibleForm.value ? '第三方 Base URL' : 'Base URL');
  const baseUrlPlaceholder = computed(() =>
    isOpenAiCompatibleForm.value
      ? '例如 https://joystar.cc、https://api.deepseek.com 或 https://openrouter.ai/api/v1'
      : '默认 https://api.openai.com/v1',
  );

  const rules: FormRules = {
    accessToken: [{ required: true, message: 'API Key / accessToken 必填', trigger: 'blur' }],
    baseUrl: [{
      validator: (_rule, value, callback) => {
        const text = typeof value === 'string' ? value.trim() : '';
        if (isOpenAiCompatibleForm.value && !text) {
          callback(new Error('第三方 API Key 账号必须填写 Base URL'));
          return;
        }
        if (text && !/^https?:\/\//i.test(text)) {
          callback(new Error('Base URL 必须以 http:// 或 https:// 开头'));
          return;
        }
        callback();
      },
      trigger: 'blur',
    }],
  };

  function resetForm() {
    Object.assign(form, {
      accountId: '',
      email: '',
      accessToken: '',
      refreshToken: '',
      clientId: '',
      clientSecret: '',
      region: 'us-east-1',
      authMethod: 'idc',
      provider: 'BuilderID',
      baseUrl: '',
      upstreamAccountId: '',
      idToken: '',
      profileArn: '',
      machineId: '',
      proxyUrl: '',
      quotaLimit: undefined,
      enabled: true,
    });
  }

  function openCreate() {
    formMode.value = 'create';
    formAccountId.value = null;
    resetForm();
    formVisible.value = true;
  }

  function openEdit(row: Account) {
    formMode.value = 'edit';
    formAccountId.value = row.accountId;
    resetForm();
    Object.assign(form, {
      accountId: row.accountId,
      email: row.email ?? '',
      region: row.region ?? '',
      authMethod: row.authMethod ?? '',
      provider: row.provider ?? '',
      baseUrl: row.baseUrl ?? '',
      upstreamAccountId: row.upstreamAccountId ?? '',
      idToken: '',
      profileArn: row.profileArn ?? '',
      machineId: row.machineId ?? '',
      proxyUrl: row.proxyUrl ?? '',
      quotaLimit: row.quotaLimit ?? undefined,
      enabled: row.enabled,
    });
    formVisible.value = true;
  }

  /** 把空串字段从 payload 中剔除，避免覆盖后端为空 */
  function compact<T extends Record<string, unknown>>(obj: T): Partial<T> {
    return Object.fromEntries(
      Object.entries(obj).filter(([, v]) => v !== '' && v !== undefined && v !== null),
    ) as Partial<T>;
  }

  const providerOptions = [
    { label: 'BuilderID / Kiro', value: 'BuilderID' },
    { label: 'OpenAI 官方 API Key', value: 'OpenAI' },
    { label: '第三方 OpenAI 兼容 API Key', value: OPENAI_COMPATIBLE_PROVIDER },
  ];

  const authMethodOptions = [
    { label: 'idc', value: 'idc' },
    { label: 'social', value: 'social' },
    { label: 'api-key', value: 'api-key' },
    { label: 'codex', value: 'codex' },
  ];

  function isOpenAiCompatibleProvider(provider: string | null | undefined) {
    const value = normalized(provider);
    return value === 'openai-compatible' || value === 'openaicompatibility';
  }

  function applyProviderDefaults(provider: string | null | undefined) {
    const value = normalized(provider);
    const auth = normalized(form.authMethod);
    if (isOpenAiCompatibleProvider(provider)) {
      form.authMethod = 'api-key';
      form.region = '';
      return;
    }
    if (value === 'openai') {
      if (!auth || auth === 'idc' || auth === 'social') {
        form.authMethod = 'api-key';
      }
      form.region = '';
      return;
    }
    if (value === 'codex') {
      form.authMethod = 'codex';
      form.region = '';
      if (!form.baseUrl) form.baseUrl = 'https://chatgpt.com/backend-api/codex';
      return;
    }
    if (value.includes('builder') || value.includes('kiro')) {
      if (!auth || auth === 'api-key' || auth === 'apikey' || auth === 'openai-api-key' || auth === 'codex') {
        form.authMethod = 'idc';
      }
      if (!form.region) form.region = 'us-east-1';
    }
  }

  watch(() => form.provider, applyProviderDefaults);

  async function submitForm() {
    if (!formRef.value) return;
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return;

    formSubmitting.value = true;
    try {
      if (formMode.value === 'create') {
        const payload = compact({ ...form }) as CreateAccountRequest;
        await accountsApi.create(payload);
        ElMessage.success('账号已创建');
      } else if (formAccountId.value) {
        const { accessToken, refreshToken, clientId, clientSecret, idToken, ...rest } = form;
        // 编辑模式下 access/refresh token 仅在有值时提交
        const payload = compact({
          ...rest,
          accessToken: accessToken || undefined,
          refreshToken: refreshToken || undefined,
          clientId: clientId || undefined,
          clientSecret: clientSecret || undefined,
          idToken: idToken || undefined,
        });
        await accountsApi.update(formAccountId.value, payload);
        ElMessage.success('账号已更新');
      }
      formVisible.value = false;
      await loadAccounts();
    } finally {
      formSubmitting.value = false;
    }
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
    await ElMessageBox.confirm(
      `确认删除账号 ${row.accountId}？操作不可恢复。`,
      '危险操作',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'error' },
    ).catch(() => Promise.reject(new Error('cancel')));
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
  const fallbackTestModelOptions: TestModelOptionsResult = {
    kiro: [
      { label: 'auto', value: 'auto' },
      { label: 'claude-sonnet-4.5', value: 'claude-sonnet-4.5' },
      { label: 'simple-task', value: 'simple-task' },
      { label: 'claude-haiku-4.5', value: 'claude-haiku-4.5' },
    ],
    openAi: [
      { label: 'auto (gpt-4o-mini)', value: 'auto' },
      { label: 'gpt-4o-mini', value: 'gpt-4o-mini' },
      { label: 'gpt-4.1-mini', value: 'gpt-4.1-mini' },
      { label: 'gpt-5.1', value: 'gpt-5.1' },
      { label: 'gpt-5-mini', value: 'gpt-5-mini' },
    ],
    google: [
      { label: 'auto (google/gemini-2.5-flash)', value: 'auto' },
      { label: 'google/gemini-2.5-pro', value: 'google/gemini-2.5-pro' },
      { label: 'google/gemini-2.5-flash', value: 'google/gemini-2.5-flash' },
    ],
    anthropic: [
      { label: 'auto (anthropic/claude-sonnet-4.5)', value: 'auto' },
      { label: 'anthropic/claude-sonnet-4.5', value: 'anthropic/claude-sonnet-4.5' },
      { label: 'anthropic/claude-opus-4.5', value: 'anthropic/claude-opus-4.5' },
    ],
    codex: [
      { label: 'auto (gpt-5.4-mini)', value: 'auto' },
      { label: 'gpt-5.5', value: 'gpt-5.5' },
      { label: 'gpt-5.4', value: 'gpt-5.4' },
      { label: 'gpt-5.4-mini', value: 'gpt-5.4-mini' },
      { label: 'gpt-5.4-nano', value: 'gpt-5.4-nano' },
      { label: 'gpt-5.2', value: 'gpt-5.2' },
      { label: 'gpt-5.1', value: 'gpt-5.1' },
      { label: 'gpt-5', value: 'gpt-5' },
    ],
  };
  const testModelOptions = ref<TestModelOptionsResult>(fallbackTestModelOptions);
  const testOptions = reactive({
    prompt: 'Reply OK in one short sentence.',
    onlyEnabled: true,
    updateStatus: true,
  });

  const testDetailJson = computed(() =>
    testDetail.value ? JSON.stringify(testDetail.value, null, 2) : '',
  );

  function testStatusType(status: TestAccountItem['status']) {
    if (status === 'SUCCESS') return 'success';
    if (status === 'SKIPPED') return 'info';
    return 'danger';
  }

  function latestTest(accountId: string) {
    return latestTestResults.value[accountId];
  }

  type TestModelGroup = 'kiro' | 'openAi' | 'google' | 'anthropic' | 'codex';

  function accountTestModelGroup(account: Account): TestModelGroup {
    const provider = normalized(account.provider);
    const authMethod = normalized(account.authMethod);
    if (provider === 'codex' || authMethod === 'codex') return 'codex';
    if (provider === 'google' || provider === 'gemini' || authMethod === 'google' || authMethod === 'gemini') {
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

  function normalized(value: string | null | undefined) {
    return (value ?? '').trim().toLowerCase();
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

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      ElMessage.success('已复制');
    } catch {
      ElMessage.warning('请手动复制');
    }
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

  const importVisible = ref(false);
  const importContent = ref('');
  const importUpsert = ref(false);
  const importResult = ref<ImportAccountsResult | null>(null);
  const importSubmitting = ref(false);
  const importFileInput = ref<HTMLInputElement | null>(null);
  const importFileNames = ref<string[]>([]);
  const importFilePayloads = ref<Array<{ name: string; payload: unknown }>>([]);
  const isDragover = ref(false);



  function openImport() {
    importContent.value = '';
    importResult.value = null;
    importUpsert.value = false;
    importFileNames.value = [];
    importFilePayloads.value = [];
    isDragover.value = false;
    importVisible.value = true;
  }

  function chooseImportFiles() {
    importFileInput.value?.click();
  }

  async function processFiles(files: File[]) {
    try {
      const parsed = await Promise.all(
        files.map(async (file) => ({
          name: file.name,
          payload: JSON.parse(await file.text()) as unknown,
        })),
      );
      const invalid = parsed.find((item) => !isImportPayload(item.payload));
      if (invalid) {
        throw new Error(`${invalid.name} 不是可识别的账号导入 JSON`);
      }
      const next = [...importFilePayloads.value, ...parsed];
      importFilePayloads.value = next;
      importFileNames.value = next.map((item) => item.name);
      importContent.value = next.length === 1 ? JSON.stringify(next[0].payload, null, 2) : '';
      importResult.value = null;
      ElMessage.success(`已加入 ${files.length} 个 JSON 文件，待导入 ${next.length} 个`);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      ElMessage.error(`JSON 文件解析失败：${message}`);
    }
  }

  async function handleImportFiles(event: Event) {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    if (files.length === 0) return;
    await processFiles(files);
  }

  function onDragOver() {
    isDragover.value = true;
  }
  function onDragEnter() {
    isDragover.value = true;
  }
  function onDragLeave() {
    isDragover.value = false;
  }
  async function onDrop(event: DragEvent) {
    isDragover.value = false;
    const files = Array.from(event.dataTransfer?.files ?? []).filter(
      (file) => file.type === 'application/json' || file.name.endsWith('.json')
    );
    if (files.length === 0) {
      ElMessage.warning('请拖拽有效的 JSON 配置文件');
      return;
    }
    await processFiles(files);
  }

  function clearImportFiles() {
    if (importFilePayloads.value.length === 0) return;
    importFilePayloads.value = [];
    importFileNames.value = [];
  }

  async function submitImport() {
    if (importFilePayloads.value.length > 0) {
      await submitImportFiles();
      return;
    }

    let parsed: unknown;
    try {
      parsed = JSON.parse(importContent.value);
    } catch {
      ElMessage.error('JSON 格式错误');
      return;
    }
    const payload =
      Array.isArray(parsed)
        ? { accounts: parsed, upsert: importUpsert.value }
        : parsed && typeof parsed === 'object'
          ? { ...(parsed as Record<string, unknown>), upsert: importUpsert.value }
          : null;
    if (!isImportPayload(payload)) {
      ElMessage.error('数据应为 Kiro / OpenAI Codex / OpenAI-compatible 导入 JSON');
      return;
    }

    const importPayload = payload as Record<string, unknown>;
    importSubmitting.value = true;
    try {
      importResult.value = await accountsApi.importBatch(importPayload);
      ElMessage.success(
        `导入完成：新建 ${importResult.value.created} / 更新 ${importResult.value.updated} / 跳过 ${importResult.value.skipped} / 失败 ${importResult.value.failed}`,
      );
      await loadAccounts();
    } catch (err) {
      if (err instanceof ApiError) ElMessage.error(err.message);
    } finally {
      importSubmitting.value = false;
    }
  }

  async function submitImportFiles() {
    const items: ImportAccountItem[] = [];
    let created = 0;
    let updated = 0;
    let skipped = 0;
    let failed = 0;

    importSubmitting.value = true;
    try {
      for (const file of importFilePayloads.value) {
        try {
          const result = await accountsApi.importBatch(importPayloadForFile(file.payload));
          created += result.created;
          updated += result.updated;
          skipped += result.skipped;
          failed += result.failed;
          for (const item of result.items) {
            items.push({
              ...item,
              index: items.length,
              message: `${file.name}: ${item.message ?? ''}`.trim(),
            });
          }
        } catch (err) {
          failed += 1;
          items.push({
            index: items.length,
            accountId: null,
            email: null,
            status: 'FAILED',
            message: `${file.name}: ${err instanceof ApiError ? err.message : String(err)}`,
            account: null,
          });
        }
      }

      importResult.value = {
        total: items.length,
        created,
        updated,
        skipped,
        failed,
        items,
      };
      const message = `导入完成：新建 ${created} / 更新 ${updated} / 跳过 ${skipped} / 失败 ${failed}`;
      if (failed > 0) {
        ElMessage.warning(message);
      } else {
        ElMessage.success(message);
      }
      await loadAccounts();
    } finally {
      importSubmitting.value = false;
    }
  }

  function importPayloadForFile(payload: unknown) {
    if (Array.isArray(payload)) {
      return { accounts: payload, upsert: importUpsert.value };
    }
    return {
      ...(payload as Record<string, unknown>),
      upsert: importUpsert.value,
    };
  }

  function isImportPayload(payload: unknown) {
    if (!payload || typeof payload !== 'object') return false;
    if (Array.isArray(payload)) return payload.length > 0;
    const record = payload as Record<string, unknown>;
    if (Array.isArray(record.documents) && record.documents.length > 0) return true;
    if (Array.isArray(record.imports) && record.imports.length > 0) return true;
    if (Array.isArray(record.files) && record.files.length > 0) return true;
    if (Array.isArray(record.accounts) && record.accounts.length > 0) return true;
    const openAiCompatibility =
      record['openai-compatibility'] ?? record.openaiCompatibility ?? record.openai_compatibility;
    if (Array.isArray(openAiCompatibility) && openAiCompatibility.length > 0) return true;
    if (openAiCompatibility && typeof openAiCompatibility === 'object') return true;
    if (looksLikeOpenAiCompatibilityGroup(record)) return true;
    return (
      'credentials' in record ||
      'accessToken' in record ||
      'access_token' in record ||
      'apiKey' in record ||
      'api_key' in record ||
      'api-key' in record ||
      'type' in record ||
      'provider' in record
    );
  }

  function looksLikeOpenAiCompatibilityGroup(record: Record<string, unknown>) {
    return (
      'api-key-entries' in record ||
      'apiKeyEntries' in record ||
      'api_key_entries' in record ||
      'keys' in record
    );
  }

  function getProviderTagStyle(provider: string) {
    const p = (provider || '').toUpperCase();
    if (p === 'OPENAI' || p === 'OPENAI-COMPATIBLE') {
      return {
        background: 'rgba(16, 163, 127, 0.08)',
        color: '#10a37f',
        border: '1px solid rgba(16, 163, 127, 0.15)',
      };
    }
    if (p === 'GEMINI' || p === 'GOOGLE') {
      return {
        background: 'rgba(66, 133, 244, 0.08)',
        color: '#4285f4',
        border: '1px solid rgba(66, 133, 244, 0.15)',
      };
    }
    if (p === 'ANTHROPIC') {
      return {
        background: 'rgba(217, 119, 87, 0.08)',
        color: '#d97757',
        border: '1px solid rgba(217, 119, 87, 0.15)',
      };
    }
    if (p.includes('BUILDERID') || p.includes('KIRO')) {
      return {
        background: 'rgba(124, 58, 237, 0.08)',
        color: '#7c3aed',
        border: '1px solid rgba(124, 58, 237, 0.15)',
      };
    }
    return {
      background: 'rgba(15, 23, 42, 0.05)',
      color: 'var(--kp-text-secondary)',
      border: '1px solid rgba(15, 23, 42, 0.1)',
    };
  }

  function handleCommand(command: string, row: Account) {
    if (command === 'suspend') {
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
        style="width: 280px"
      >
        <template #prefix>
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="width: 14px; height: 14px; color: var(--kp-text-muted); margin-right: 4px;">
            <circle cx="11" cy="11" r="8"></circle>
            <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
          </svg>
        </template>
      </el-input>
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
      <el-button type="primary" @click="openCreate">新建账号</el-button>
    </template>

    <!-- 1. Stats Summary Panel -->
    <div class="metrics-panel glass-panel">
      <div class="metric-item">
        <div class="metric-item__header">
          <span class="metric-dot dot-total"></span>
          <span class="metric-label">总账号数</span>
        </div>
        <div class="metric-value">{{ summary.total }}</div>
      </div>

      <div class="metric-divider"></div>

      <div class="metric-item">
        <div class="metric-item__header">
          <span class="metric-dot dot-active"></span>
          <span class="metric-label">正常运行</span>
        </div>
        <div class="metric-value text-success">{{ summary.active }}</div>
      </div>

      <div class="metric-divider"></div>

      <div class="metric-item">
        <div class="metric-item__header">
          <span class="metric-dot dot-suspended"></span>
          <span class="metric-label">异常 / 停用</span>
        </div>
        <div class="metric-value" :class="{ 'text-danger': summary.abnormal > 0 }">{{ summary.abnormal }}</div>
      </div>

      <div class="metric-divider"></div>

      <div class="metric-item">
        <div class="metric-item__header">
          <span class="metric-dot dot-requests"></span>
          <span class="metric-label">累计请求 / 错误</span>
        </div>
        <div class="metric-value">
          {{ formatNumber(summary.totalRequests) }}
          <span class="metric-value__sub">/ {{ formatNumber(summary.totalErrors) }}</span>
        </div>
      </div>
    </div>

    <el-table v-loading="loading" :data="visibleAccounts" stripe>
      <el-table-column label="账号类型" width="130">
        <template #default="{ row }">
          <span class="provider-badge" :style="getProviderTagStyle(row.provider)">
            <!-- OpenAI Logo -->
            <svg v-if="['OPENAI', 'OPENAI-COMPATIBLE'].includes((row.provider || '').toUpperCase())" class="provider-logo" viewBox="0 0 24 24" fill="currentColor">
              <path d="M22.2819 9.8211a5.9847 5.9847 0 0 0-.5157-4.9108 6.0462 6.0462 0 0 0-6.5098-2.9A6.0651 6.0651 0 0 0 4.9807 4.1818a5.9847 5.9847 0 0 0-3.9977 2.9 6.0462 6.0462 0 0 0 .7427 7.0966 5.98 5.98 0 0 0 .511 4.9107 6.051 6.051 0 0 0 6.5146 2.9001A5.9847 5.9847 0 0 0 13.2599 24a6.0557 6.0557 0 0 0 5.7718-4.2058 5.9894 5.9894 0 0 0 3.9977-2.9001 6.0557 6.0557 0 0 0-.7475-7.0729zm-9.022 12.6081a4.4755 4.4755 0 0 1-2.8764-1.0408l.1419-.0804 4.7783-2.7582a.7948.7948 0 0 0 .3927-.6813v-6.7369l2.02 1.1686a.071.071 0 0 1 .038.052v5.5826a4.504 4.504 0 0 1-4.4945 4.4944zm-9.6607-4.1254a4.4708 4.4708 0 0 1-.5346-3.0137l.142.0852 4.783 2.7582a.7712.7712 0 0 0 .7806 0l5.8428-3.3685v2.3324a.0804.0804 0 0 1-.0332.0615L9.74 19.9502a4.4992 4.4992 0 0 1-6.1408-1.6464zM2.3408 7.8956a4.485 4.485 0 0 1 2.3655-1.9728V11.6a.7664.7664 0 0 0 .3879.6765l5.8144 3.3543-2.0201 1.1685a.0757.0757 0 0 1-.071 0l-4.8303-2.7865A4.504 4.504 0 0 1 2.3408 7.872zm16.5963 3.8558L13.1038 8.364 15.1192 7.2a.0757.0757 0 0 1 .071 0l4.8303 2.7913a4.4944 4.4944 0 0 1-.6765 8.1042v-5.6772a.79.79 0 0 0-.407-.667zm2.0107-3.0231l-.142-.0852-4.7735-2.7818a.7759.7759 0 0 0-.7854 0L9.409 9.2297V6.8974a.0662.0662 0 0 1 .0284-.0615l4.8303-2.7866a4.4992 4.4992 0 0 1 6.6802 4.66zM8.3065 12.863l-2.02-1.1638a.0804.0804 0 0 1-.038-.0567V6.0742a4.4992 4.4992 0 0 1 7.3757-3.4537l-.142.0805L8.704 5.459a.7948.7948 0 0 0-.3927.6813zm1.0976-2.3654l2.602-1.4998 2.6069 1.4998v2.9994l-2.5974 1.4997-2.6067-1.4997Z"/>
            </svg>
            <!-- Gemini / Google Logo -->
            <svg v-else-if="['GEMINI', 'GOOGLE'].includes((row.provider || '').toUpperCase())" class="provider-logo" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 2c0 5.5-4.5 10-10 10 5.5 0 10 4.5 10 10 0-5.5 4.5-10 10-10-5.5 0-10-4.5-10-10z"/>
            </svg>
            <!-- Anthropic Logo -->
            <svg v-else-if="(row.provider || '').toUpperCase() === 'ANTHROPIC'" class="provider-logo" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12.5 3L4.2 19h4l2.1-4.2h5.4l2.1 4.2h4L13.5 3h-1zM11.3 12l1.7-3.4 1.7 3.4h-3.4z"/>
            </svg>
            <!-- Other Shield Logo -->
            <svg v-else class="provider-logo" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
            </svg>
            <span class="provider-name">{{ row.provider || 'Unknown' }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="邮箱 / 账号 ID" min-width="180">
        <template #default="{ row }">
          <div class="email-id-cell">
            <span class="email-text" :title="row.email">{{ row.email || '—' }}</span>
            <span class="id-subtext mono">{{ shorten(row.accountId, 10, 6) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="Base URL" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="mono">{{ row.baseUrl ? shorten(row.baseUrl, 24, 10) : '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="region" label="区域" width="100" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusOf(row).tone" effect="light" round>{{ statusOf(row).label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="请求 / 错误" width="120">
        <template #default="{ row }">
          {{ formatNumber(row.requestCount) }} / {{ formatNumber(row.errorCount) }}
        </template>
      </el-table-column>
      <el-table-column label="配额 (used/limit)" width="150">
        <template #default="{ row }">
          {{ formatNumber(row.quotaUsed) }} / {{ formatNumber(row.quotaLimit) }}
        </template>
      </el-table-column>
      <el-table-column label="最近使用" width="160">
        <template #default="{ row }">{{ formatDateTime(row.lastUsedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <div class="actions-wrapper">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-popover placement="bottom-end" width="320" trigger="click" @show="ensureTestModel(row)">
              <template #reference>
                <el-button link type="primary">测试</el-button>
              </template>
              <div class="row-test-panel">
                <div class="row-test-panel__title">
                  <span>账号测试</span>
                  <el-tag
                    v-if="latestTest(row.accountId)"
                    :type="testStatusType(latestTest(row.accountId).status)"
                    effect="light"
                    round
                  >
                    {{ latestTest(row.accountId).status }}
                  </el-tag>
                </div>
                <el-form label-position="top" size="small">
                  <el-form-item label="模型">
                    <el-select
                      v-model="testModelByAccount[row.accountId]"
                      filterable
                      allow-create
                      default-first-option
                      placeholder="选择或输入模型 ID"
                      style="width: 100%"
                      @focus="ensureTestModel(row)"
                    >
                      <el-option
                        v-for="item in testModelsFor(row)"
                        :key="item.value"
                        :label="item.label"
                        :value="item.value"
                      />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="提示词">
                    <el-input v-model="testOptions.prompt" />
                  </el-form-item>
                  <div class="row-test-panel__checks">
                    <el-checkbox v-model="testOptions.onlyEnabled">仅测试当前可用账号</el-checkbox>
                    <el-checkbox v-model="testOptions.updateStatus">测试后自动更新账号状态</el-checkbox>
                  </div>
                </el-form>
                <div class="row-test-panel__actions">
                  <el-button
                    size="small"
                    type="primary"
                    :loading="testingAccountId === row.accountId"
                    @click="testAccount(row)"
                  >
                    自动测试
                  </el-button>
                  <el-button
                    size="small"
                    :disabled="!latestTest(row.accountId)"
                    @click="openTestDetail(row)"
                  >
                    查看结果
                  </el-button>
                </div>
              </div>
            </el-popover>
            <el-button link :type="row.enabled ? 'danger' : 'success'" @click="toggleEnabled(row)">
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
            <span class="action-divider">|</span>
            <el-dropdown trigger="click" @command="(cmd: string) => handleCommand(cmd, row)">
              <el-button link type="primary" class="more-btn">
                更多<span class="caret">▼</span>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="suspend">挂起</el-dropdown-item>
                  <el-dropdown-item command="reset">重置</el-dropdown-item>
                  <el-dropdown-item command="remove" divided>
                    <span style="color: var(--el-color-danger)">删除</span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </template>
      </el-table-column>
    </el-table>
  </PageSection>

  <!-- 新建 / 编辑 -->
  <el-dialog
    v-model="formVisible"
    :title="formMode === 'create' ? '新建账号' : '编辑账号'"
    width="720px"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-alert
        v-if="isOpenAiCompatibleForm"
        class="account-form-alert"
        type="info"
        show-icon
        :closable="false"
        title="第三方 API Key 账号会按 OpenAI 兼容协议调用 Base URL；填写站点根地址时会自动使用 /v1。模型映射目标可写成 openai-compatible/真实模型ID，例如 openai-compatible/deepseek-chat。"
      />
      <el-row :gutter="20">
        <el-col v-if="formMode === 'create'" :span="12">
          <el-form-item label="账号 ID（可选）">
            <el-input v-model="form.accountId" placeholder="留空时自动生成" />
          </el-form-item>
        </el-col>
        <el-col :span="formMode === 'create' ? 12 : 24">
          <el-form-item label="邮箱">
            <el-input v-model="form.email" placeholder="如 email@example.com" />
          </el-form-item>
        </el-col>

        <el-col :span="12">
          <el-form-item label="Provider">
            <el-select v-model="form.provider" allow-create filterable style="width: 100%">
              <el-option
                v-for="item in providerOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="认证方式">
            <el-select v-model="form.authMethod" allow-create filterable style="width: 100%">
              <el-option
                v-for="item in authMethodOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </el-col>

        <el-col :span="12">
          <el-form-item label="区域">
            <el-input v-model="form.region" placeholder="如 us-east-1" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="Machine ID">
            <el-input v-model="form.machineId" placeholder="机器 ID，可选" />
          </el-form-item>
        </el-col>

        <el-col :span="24">
          <el-form-item :label="accessTokenLabel" prop="accessToken">
            <el-input v-model="form.accessToken" type="textarea" :rows="2" :placeholder="accessTokenPlaceholder" />
          </el-form-item>
        </el-col>

        <el-col :span="12">
          <el-form-item label="Refresh Token">
            <el-input v-model="form.refreshToken" placeholder="输入 Refresh Token，可选" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="上游账号 ID">
            <el-input v-model="form.upstreamAccountId" placeholder="Codex ChatGPT Account ID，可选" />
          </el-form-item>
        </el-col>

        <el-col :span="24">
          <el-form-item label="ID Token">
            <el-input v-model="form.idToken" type="textarea" :rows="2" placeholder="Codex id_token，可选" />
          </el-form-item>
        </el-col>

        <el-col :span="12">
          <el-form-item :label="baseUrlLabel" prop="baseUrl">
            <el-input v-model="form.baseUrl" :placeholder="baseUrlPlaceholder" />
            <p v-if="isOpenAiCompatibleForm" class="form-hint">
              填到兼容 OpenAI 的 API 根路径，通常以 /v1 结尾；服务会自动追加 /chat/completions 或 /responses。
            </p>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="Profile ARN">
            <el-input v-model="form.profileArn" placeholder="AWS Profile ARN，可选" />
          </el-form-item>
        </el-col>

        <el-col :span="12">
          <el-form-item label="出站代理 URL">
            <el-input v-model="form.proxyUrl" placeholder="http://user:pass@host:port" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-row :gutter="12">
            <el-col :span="formMode === 'edit' ? 16 : 24">
              <el-form-item label="配额上限">
                <el-input-number v-model="form.quotaLimit" :min="0" :step="1000" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col v-if="formMode === 'edit'" :span="8">
              <el-form-item label="启用">
                <el-switch v-model="form.enabled" style="margin-top: 6px" />
              </el-form-item>
            </el-col>
          </el-row>
        </el-col>
      </el-row>
    </el-form>

    <template #footer>
      <el-button @click="formVisible = false">取消</el-button>
      <el-button type="primary" :loading="formSubmitting" @click="submitForm">提交</el-button>
    </template>
  </el-dialog>

  <!-- 批量导入 -->
  <el-dialog v-model="importVisible" title="批量导入账号" width="720px">
    <div class="text-secondary mt-8" style="margin-bottom: 8px">
      上传或粘贴 Kiro Account Manager、OpenAI Codex token、OpenAI-compatible 配置 JSON，或账号数组 /
      <span class="mono">{ "accounts": [...] }</span>
    </div>

    <div
      class="import-drop-zone"
      :class="{ 'is-dragover': isDragover, 'has-files': importFileNames.length > 0 }"
      @dragover.prevent="onDragOver"
      @dragenter.prevent="onDragEnter"
      @dragleave.prevent="onDragLeave"
      @drop.prevent="onDrop"
      @click="chooseImportFiles"
    >
      <input
        ref="importFileInput"
        class="import-file-row__input"
        type="file"
        accept=".json,application/json"
        multiple
        style="display: none;"
        @change="handleImportFiles"
      />

      <div v-if="importFileNames.length === 0" class="drop-zone-prompt">
        <svg xmlns="http://www.w3.org/2000/svg" width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" class="drop-icon">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
          <polyline points="17 8 12 3 7 8"></polyline>
          <line x1="12" y1="3" x2="12" y2="15"></line>
        </svg>
        <div class="prompt-text">将 JSON 配置文件拖拽到此处，或<span>点击上传</span></div>
        <div class="prompt-hint">支持单个或多个 .json 配置文件</div>
      </div>
      <div v-else class="drop-zone-files" @click.stop>
        <div class="files-header">
          <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="success-icon">
            <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path>
            <polyline points="22 4 12 14.01 9 11.01"></polyline>
          </svg>
          <span class="files-title">已选择 {{ importFileNames.length }} 个 JSON 文件</span>
          <el-button size="small" type="danger" link @click="clearImportFiles">清除文件</el-button>
        </div>
        <div class="files-list">
          <div v-for="name in importFileNames" :key="name" class="file-item">
            <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="file-icon">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
              <polyline points="14 2 14 8 20 8"></polyline>
              <line x1="16" y1="13" x2="8" y2="13"></line>
              <line x1="16" y1="17" x2="8" y2="17"></line>
              <polyline points="10 9 9 9 8 9"></polyline>
            </svg>
            <span class="file-name" :title="name">{{ name }}</span>
          </div>
        </div>
      </div>
    </div>

    <div class="text-secondary mt-16" style="margin-bottom: 8px">或者，在此直接粘贴 JSON 文本：</div>
    <el-input
      v-model="importContent"
      type="textarea"
      :rows="8"
      placeholder='{"type":"codex","access_token":"...","refresh_token":"...","id_token":"..."}'
      @input="clearImportFiles"
    />
    <el-checkbox v-model="importUpsert" class="mt-16">
      已存在邮箱时覆盖更新旧账号（谨慎开启）
    </el-checkbox>

    <div v-if="importResult" class="import-summary glass-panel mt-16">
      <div class="flex gap-16">
        <div>总计 {{ importResult.total }}</div>
        <div>新建 {{ importResult.created }}</div>
        <div>更新 {{ importResult.updated }}</div>
        <div>跳过 {{ importResult.skipped }}</div>
        <div>失败 {{ importResult.failed }}</div>
      </div>
      <el-table :data="importResult.items" size="small" class="mt-8" max-height="240">
        <el-table-column prop="index" label="#" width="60" />
        <el-table-column prop="accountId" label="账号 ID" min-width="180" />
        <el-table-column prop="status" label="结果" width="100" />
        <el-table-column prop="message" label="信息" />
      </el-table>
    </div>

    <template #footer>
      <el-button @click="importVisible = false">关闭</el-button>
      <el-button type="primary" :loading="importSubmitting" @click="submitImport">开始导入</el-button>
    </template>
  </el-dialog>

  <!-- 单账号测试结果 -->
  <el-dialog v-model="testDetailVisible" title="账号测试结果" width="760px">
    <div v-if="testDetail" class="test-detail">
      <div class="test-detail__head">
        <div class="test-detail__title">
          <el-tag :type="testStatusType(testDetail.status)" effect="light" round>
            {{ testDetail.status }}
          </el-tag>
          <span class="mono">{{ testDetail.accountId }}</span>
        </div>
        <div class="test-detail__actions">
          <el-button size="small" @click="copy(testDetailJson)">复制完整结果</el-button>
          <el-button
            size="small"
            type="primary"
            @click="copy(testDetail.detail || testDetail.message || '')"
          >
            {{ testDetail.status === 'FAILED' ? '复制报错信息' : '复制返回信息' }}
          </el-button>
        </div>
      </div>

      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="邮箱">{{ testDetail.email || '—' }}</el-descriptions-item>
        <el-descriptions-item label="模型">{{ testDetail.model || '—' }}</el-descriptions-item>
        <el-descriptions-item label="HTTP">{{ testDetail.statusCode ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="耗时">
          {{ testDetail.latencyMs == null ? '—' : `${testDetail.latencyMs}ms` }}
        </el-descriptions-item>
        <el-descriptions-item label="测试时间" :span="2">
          {{ formatDateTime(testDetail.testedAt) }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="test-detail__section">
        <div class="test-detail__section-head">
          <span>{{ testDetail.status === 'FAILED' ? '完整报错信息' : '完整返回信息' }}</span>
          <el-button
            link
            type="primary"
            @click="copy(testDetail.detail || testDetail.message || '')"
          >
            复制
          </el-button>
        </div>
        <pre class="test-detail__pre">{{ testDetail.detail || testDetail.message || '—' }}</pre>
      </div>

      <div class="test-detail__section">
        <div class="test-detail__section-head">
          <span>完整 JSON</span>
          <el-button link type="primary" @click="copy(testDetailJson)">复制</el-button>
        </div>
        <pre class="test-detail__pre">{{ testDetailJson }}</pre>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped lang="scss">
  .row-test-panel {
    display: grid;
    gap: 10px;

    &__title,
    &__actions {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
    }

    &__title {
      font-weight: 700;
    }

    &__checks {
      display: grid;
      gap: 4px;
    }
  }

  .test-detail {
    display: grid;
    gap: 14px;

    &__head,
    &__actions {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }

    &__title {
      display: flex;
      align-items: center;
      gap: 10px;
      min-width: 0;
    }

    &__section {
      display: grid;
      gap: 6px;
    }

    &__section-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      color: var(--kp-text-secondary);
      font-weight: 700;
    }

    &__pre {
      max-height: 220px;
      overflow: auto;
      margin: 0;
      padding: 12px;
      border: 1px solid rgba(15, 23, 42, 0.08);
      border-radius: 10px;
      background: rgba(15, 23, 42, 0.86);
      color: #e5edff;
      font-family: ui-monospace, 'JetBrains Mono', 'SF Mono', Consolas, monospace;
      font-size: 12px;
      line-height: 1.55;
      white-space: pre-wrap;
      word-break: break-word;
    }
  }

  .import-summary {
    padding: 12px 16px;
  }

  .account-form-alert {
    margin-bottom: 16px;
  }

  .form-hint {
    width: 100%;
    margin: 6px 0 0;
    color: var(--kp-text-muted);
    font-size: 12px;
    line-height: 1.45;
  }

  /* Import Drop Zone */
  .import-drop-zone {
    border: 2px dashed rgba(15, 23, 42, 0.12);
    border-radius: var(--kp-radius-sm);
    padding: 24px 20px;
    text-align: center;
    background: rgba(255, 255, 255, 0.45);
    cursor: pointer;
    transition: all 0.25s ease;
    margin-top: 12px;
    margin-bottom: 12px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    min-height: 120px;
    user-select: none;

    &:hover, &.is-dragover {
      border-color: var(--kp-primary);
      background: rgba(91, 141, 239, 0.04);
      
      .drop-icon {
        color: var(--kp-primary);
        transform: translateY(-2px);
      }
    }

    &.has-files {
      cursor: default;
      border-style: solid;
      background: rgba(255, 255, 255, 0.55);
      align-items: stretch;
      padding: 16px 20px;
    }

    .drop-icon {
      color: var(--kp-text-muted);
      margin-bottom: 8px;
      transition: all 0.25s ease;
    }

    .prompt-text {
      font-size: 13.5px;
      font-weight: 600;
      color: var(--kp-text);
      margin-bottom: 4px;

      span {
        color: var(--kp-primary);
        font-weight: 700;
        text-decoration: underline;
        margin-left: 4px;
      }
    }

    .prompt-hint {
      font-size: 11px;
      color: var(--kp-text-muted);
    }

    .drop-zone-files {
      display: flex;
      flex-direction: column;
      gap: 10px;
      text-align: left;

      .files-header {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 13px;
        font-weight: 700;
        color: var(--kp-text);
        border-bottom: 1px solid var(--kp-divider);
        padding-bottom: 6px;

        .success-icon {
          color: #36c08a;
        }

        .files-title {
          flex: 1;
        }
      }

      .files-list {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        max-height: 100px;
        overflow-y: auto;
      }

      .file-item {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        padding: 3px 8px;
        border-radius: 5px;
        background: rgba(15, 23, 42, 0.03);
        border: 1px solid var(--kp-divider);
        font-size: 11.5px;
        color: var(--kp-text-secondary);
        max-width: 200px;

        .file-icon {
          flex-shrink: 0;
          color: var(--kp-text-muted);
        }

        .file-name {
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
      }
    }
  }

  .caret {
    font-size: 10px;
    margin-left: 3px;
    vertical-align: middle;
    display: inline-block;
  }

  .provider-badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 3px 10px;
    border-radius: 6px;
    font-size: 11px;
    font-weight: 600;
    white-space: nowrap;
    vertical-align: middle;

    .provider-logo {
      width: 13px;
      height: 13px;
      flex-shrink: 0;
    }

    .provider-name {
      line-height: 1;
    }
  }

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

  /* Unified Metrics Panel */
  .metrics-panel {
    display: flex;
    align-items: center;
    padding: 20px 28px;
    margin-bottom: 24px;
    width: 100%;

    .metric-item {
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 0;

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
          background-color: #36c08a;
          box-shadow: 0 0 6px rgba(54, 192, 138, 0.6);
        }
        &.dot-suspended {
          background-color: #ef5b6b;
          box-shadow: 0 0 6px rgba(239, 91, 107, 0.6);
        }
        &.dot-requests {
          background-color: #b387ff;
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
        font-family: ui-monospace, 'JetBrains Mono', 'SF Mono', Consolas, monospace;

        &.text-success {
          color: #36c08a;
        }
        &.text-danger {
          color: #ef5b6b;
        }

        &__sub {
          font-size: 13px;
          font-weight: 500;
          color: var(--kp-text-muted);
        }
      }

      .metric-subtext {
        font-size: 11px;
        color: var(--kp-text-muted);
        font-weight: 500;
      }
    }

    .metric-divider {
      width: 1px;
      height: 40px;
      background-color: var(--kp-divider);
      margin: 0 28px;
      align-self: center;
      flex-shrink: 0;
    }
  }

  .actions-wrapper {
    display: flex;
    align-items: center;
    gap: 8px;

    .el-button {
      margin: 0;
    }

    .action-divider {
      color: var(--el-border-color-lighter);
      font-size: 12px;
      user-select: none;
    }

    .more-btn {
      padding: 0 4px;
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

  @media (max-width: 1024px) {
    .metrics-panel {
      padding: 16px 20px;
      
      .metric-divider {
        margin: 0 20px;
      }
    }
  }

  @media (max-width: 768px) {
    .metrics-panel {
      flex-direction: column;
      align-items: stretch;
      gap: 16px;
      padding: 20px;

      .metric-divider {
        width: 100%;
        height: 1px;
        margin: 4px 0;
      }
    }
  }
</style>
