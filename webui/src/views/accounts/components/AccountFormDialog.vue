<script setup lang="ts">
  import { locale, t } from '@/i18n';
  /**
   * 新建 / 编辑账号对话框。
   *
   * 从 AccountListView.vue 原样抽出：三页签表单（基本配置 / 凭证配置 /
   * 高级与路由）、表单校验与提交逻辑；提交成功后通知父组件刷新列表。
   */
  import { computed, reactive, ref, watch } from 'vue';
  import { useRouter } from 'vue-router';
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
  import { accountsApi, modelsApi } from '@/api';
  import { proxyChannelsApi, type ProxyCatalog } from '@/api/proxyChannels';
  import type { Account, AccountAllocationPolicy, CreateAccountRequest } from '@/types/api';
  import ChatGptOAuthFlow from './ChatGptOAuthFlow.vue';
  import ProviderBadge from './ProviderBadge.vue';
  import {
    customOpenAiCompatibleVendors,
    isCustomOpenAiCompatibleProvider,
    isLegacyOpenAiCompatibleProvider,
    normalized,
    normalizedPoolTier,
    type CompatibleVendorOption,
  } from '../utils';

  const props = defineProps<{
    visible: boolean;
    mode: 'create' | 'edit';
    account: Account | null;
  }>();

  const emit = defineEmits<{
    (e: 'update:visible', value: boolean): void;
    (e: 'saved'): void;
  }>();

  const visibleValue = computed({
    get: () => props.visible,
    set: (value: boolean) => emit('update:visible', value),
  });

  const OPENAI_COMPATIBLE_PROVIDER = 'OpenAI-compatible';
  const ANTHROPIC_PROVIDER = 'Anthropic';
  const DEEPSEEK_PROVIDER = 'DeepSeek';
  const DEEPSEEK_DEFAULT_BASE_URL = 'https://api.deepseek.com/v1';
  const router = useRouter();

  const formRef = ref<FormInstance>();
  const protocol = ref('BuilderID');
  const compatibleVendors = ref<CompatibleVendorOption[]>([]);
  const vendorsLoading = ref(false);
  const formSubmitting = ref(false);
  const allocationPolicyLoading = ref(false);
  const allocationPolicy = ref<AccountAllocationPolicy | null>(null);
  const proxyCatalog = ref<ProxyCatalog | null>(null);
  const activeFormTab = ref('basic');
  const createMethod = ref<'credentials' | 'chatgpt'>('credentials');
  let allocationPolicyRequest = 0;

  const createMethodOptions = [
    {
      get label() {
        return t('手动凭证');
      },
      value: 'credentials',
    },
    {
      get label() {
        return t('ChatGPT 浏览器授权');
      },
      value: 'chatgpt',
    },
  ];

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
    proxyChannelId: '',
    poolTier: 'primary',
    poolWeight: 100,
    quotaLimit: undefined as number | undefined,
    enabled: true,
    catalogVendor: '',
  });

  const policyForm = reactive({
    allocationPriority: 100,
    safeConcurrency: 4,
    safeRpm: 60,
    burstCapacity: 4,
    maxSessions: 0,
    sessionIdleSeconds: 600,
    maxInflightTokens: 1_000_000,
    cooldownSeconds: 0,
    tlsProfile: '',
  });

  const isOpenAiCompatibleForm = computed(
    () =>
      protocol.value === OPENAI_COMPATIBLE_PROVIDER ||
      isLegacyOpenAiCompatibleProvider(form.provider) ||
      isCustomOpenAiCompatibleProvider(form.provider, form.authMethod),
  );
  const selectedVendor = computed(
    () => compatibleVendors.value.find((vendor) => vendor.name === form.catalogVendor) ?? null,
  );
  const vendorSelectOptions = computed(() => {
    if (
      form.catalogVendor &&
      !compatibleVendors.value.some((vendor) => vendor.name === form.catalogVendor)
    ) {
      return [
        { name: form.catalogVendor, icon: null, color: null, modelCount: 0 },
        ...compatibleVendors.value,
      ];
    }
    return compatibleVendors.value;
  });
  const isOpenAiApiKeyForm = computed(
    () => isOpenAiCompatibleForm.value || normalized(form.provider) === 'openai',
  );
  const isAnthropicApiKeyForm = computed(() => isAnthropicProvider(form.provider));
  const isDeepSeekApiKeyForm = computed(() => isDeepSeekProvider(form.provider));
  const isApiKeyForm = computed(
    () => isOpenAiApiKeyForm.value || isAnthropicApiKeyForm.value || isDeepSeekApiKeyForm.value,
  );
  const isChatGptCreate = computed(
    () => props.mode === 'create' && createMethod.value === 'chatgpt',
  );
  const usesSessionLimit = computed(() => isAnthropicApiKeyForm.value);
  const accessTokenLabel = computed(() => {
    const suffix = props.mode === 'edit' ? t('（留空则不修改）') : '';
    return isApiKeyForm.value ? `API Key${suffix}` : `Access Token${suffix}`;
  });
  const accessTokenPlaceholder = computed(() => {
    if (isDeepSeekApiKeyForm.value) return t('输入 DeepSeek API Key，如 sk-...');
    if (isAnthropicApiKeyForm.value) return t('输入 Anthropic / Claude API Key，如 sk-ant-...');
    return isOpenAiApiKeyForm.value ? t('输入上游网站 API Key，如 sk-...') : t('输入 Access Token');
  });
  const baseUrlLabel = computed(() => {
    if (isDeepSeekApiKeyForm.value) return 'DeepSeek API Base URL';
    if (isOpenAiCompatibleForm.value) return t('第三方 Base URL');
    if (isAnthropicApiKeyForm.value) return 'Anthropic Base URL';
    return 'Base URL';
  });
  const baseUrlPlaceholder = computed(() => {
    if (isDeepSeekApiKeyForm.value) return t('默认 {p0}', { p0: DEEPSEEK_DEFAULT_BASE_URL });
    if (isOpenAiCompatibleForm.value) {
      return t('例如 https://joystar.cc、https://api.deepseek.com 或 https://openrouter.ai/api/v1');
    }
    if (isAnthropicApiKeyForm.value) {
      return t('默认 https://api.anthropic.com/v1，也可填第三方 Anthropic 兼容地址');
    }
    return t('默认 https://api.openai.com/v1');
  });

  const rules: FormRules = {
    accessToken: [
      {
        validator: (_rule, value, callback) => {
          const text = typeof value === 'string' ? value.trim() : '';
          if (props.mode === 'create' && !text) {
            callback(new Error(t('API Key / accessToken 必填')));
            return;
          }
          callback();
        },
        trigger: 'blur',
      },
    ],
    baseUrl: [
      {
        validator: (_rule, value, callback) => {
          const text = typeof value === 'string' ? value.trim() : '';
          if (isOpenAiCompatibleForm.value && !text) {
            callback(new Error(t('第三方 API Key 账号必须填写 Base URL')));
            return;
          }
          if (text && !/^https?:\/\//i.test(text)) {
            callback(new Error(t('Base URL 必须以 http:// 或 https:// 开头')));
            return;
          }
          callback();
        },
        trigger: 'blur',
      },
    ],
    catalogVendor: [
      {
        validator: (_rule, _value, callback) => {
          if (isOpenAiCompatibleForm.value && !form.catalogVendor.trim()) {
            callback(new Error(t('请选择支持 OpenAI 兼容协议的厂商分类')));
            return;
          }
          callback();
        },
        trigger: 'change',
      },
    ],
  };

  function resetForm() {
    allocationPolicyRequest += 1;
    allocationPolicy.value = null;
    allocationPolicyLoading.value = false;
    createMethod.value = 'credentials';
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
      proxyChannelId: '',
      poolTier: 'primary',
      poolWeight: 100,
      quotaLimit: undefined,
      enabled: true,
      catalogVendor: '',
    });
    protocol.value = 'BuilderID';
    Object.assign(policyForm, {
      allocationPriority: 100,
      safeConcurrency: 4,
      safeRpm: 60,
      burstCapacity: 4,
      maxSessions: 0,
      sessionIdleSeconds: 600,
      maxInflightTokens: 1_000_000,
      cooldownSeconds: 0,
      tlsProfile: '',
    });
    applyRoutingDefaults();
  }

  async function loadAllocationPolicy(accountId: string) {
    const request = ++allocationPolicyRequest;
    allocationPolicyLoading.value = true;
    try {
      const policy = await accountsApi.allocationPolicy(accountId);
      if (request !== allocationPolicyRequest) return;
      allocationPolicy.value = policy;
      Object.assign(policyForm, {
        allocationPriority: policy.allocationPriority,
        safeConcurrency: policy.safeConcurrency,
        safeRpm: policy.safeRpm,
        burstCapacity: policy.burstCapacity,
        maxSessions: policy.maxSessions,
        sessionIdleSeconds: policy.sessionIdleSeconds,
        maxInflightTokens: policy.maxInflightTokens,
        cooldownSeconds: policy.cooldownSeconds ?? 0,
        tlsProfile: policy.tlsProfile ?? '',
      });
    } catch {
      if (request === allocationPolicyRequest) {
        ElMessage.error(t('账号分配策略加载失败'));
      }
    } finally {
      if (request === allocationPolicyRequest) {
        allocationPolicyLoading.value = false;
      }
    }
  }

  /** 把空串字段从 payload 中剔除，避免覆盖后端为空 */
  function compact<T extends Record<string, unknown>>(obj: T): Partial<T> {
    return Object.fromEntries(
      Object.entries(obj).filter(([, v]) => v !== '' && v !== undefined && v !== null),
    ) as Partial<T>;
  }

  const providerOptions = [
    { label: 'BuilderID / Kiro', value: 'BuilderID' },
    {
      get label() {
        return t('OpenAI 官方 API Key');
      },
      value: 'OpenAI',
    },
    {
      get label() {
        return t('第三方 OpenAI 兼容 API Key');
      },
      value: OPENAI_COMPATIBLE_PROVIDER,
    },
    { label: 'Anthropic / Claude API Key', value: ANTHROPIC_PROVIDER },
    { label: 'DeepSeek API Key', value: DEEPSEEK_PROVIDER },
  ];

  const authMethodOptions = [
    { label: 'idc', value: 'idc' },
    { label: 'social', value: 'social' },
    { label: 'api-key', value: 'api-key' },
    { label: 'codex', value: 'codex' },
  ];

  // normalized / normalizedPoolTier 已收敛至 ../utils

  function isAnthropicProvider(provider: string | null | undefined) {
    const value = normalized(provider);
    return value === 'anthropic' || value === 'claude';
  }

  function isDeepSeekProvider(provider: string | null | undefined) {
    return normalized(provider) === 'deepseek';
  }

  function applyProviderDefaults(provider: string | null | undefined) {
    const value = normalized(provider);
    const auth = normalized(form.authMethod);
    if (
      protocol.value === OPENAI_COMPATIBLE_PROVIDER ||
      isLegacyOpenAiCompatibleProvider(provider) ||
      isCustomOpenAiCompatibleProvider(provider, form.authMethod)
    ) {
      form.authMethod = 'api-key';
      form.region = '';
      return;
    }
    if (isAnthropicProvider(provider)) {
      form.authMethod = 'api-key';
      form.region = '';
      if (!form.baseUrl) form.baseUrl = 'https://api.anthropic.com/v1';
      return;
    }
    if (isDeepSeekProvider(provider)) {
      form.authMethod = 'api-key';
      form.region = '';
      if (!form.baseUrl) form.baseUrl = DEEPSEEK_DEFAULT_BASE_URL;
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
      if (
        !auth ||
        auth === 'api-key' ||
        auth === 'apikey' ||
        auth === 'openai-api-key' ||
        auth === 'codex'
      ) {
        form.authMethod = 'idc';
      }
      if (!form.region) form.region = 'us-east-1';
    }
  }

  function handleProviderChange(provider: string) {
    if (isDeepSeekProvider(provider)) {
      form.baseUrl = DEEPSEEK_DEFAULT_BASE_URL;
    } else if (form.baseUrl.trim() === DEEPSEEK_DEFAULT_BASE_URL) {
      form.baseUrl = '';
    }
    applyProviderDefaults(provider);
  }

  function handleProtocolChange(value: string) {
    protocol.value = value;
    if (value === OPENAI_COMPATIBLE_PROVIDER) {
      form.authMethod = 'api-key';
      form.region = '';
      form.provider = form.catalogVendor || OPENAI_COMPATIBLE_PROVIDER;
      if (form.baseUrl.trim() === DEEPSEEK_DEFAULT_BASE_URL) form.baseUrl = '';
      return;
    }
    form.catalogVendor = '';
    form.provider = value;
    handleProviderChange(value);
  }

  function handleVendorChange(vendor: string) {
    form.catalogVendor = vendor;
    form.provider = vendor;
    form.authMethod = 'api-key';
  }

  async function loadCompatibleVendors() {
    vendorsLoading.value = true;
    try {
      compatibleVendors.value = customOpenAiCompatibleVendors(await modelsApi.list());
    } catch {
      compatibleVendors.value = [];
    } finally {
      vendorsLoading.value = false;
    }
  }

  function goAddVendorModel() {
    visibleValue.value = false;
    void router.push({ name: 'model-create' });
  }

  function protocolFromAccount(row: Account) {
    if (
      isCustomOpenAiCompatibleProvider(row.provider, row.authMethod) ||
      isLegacyOpenAiCompatibleProvider(row.provider)
    ) {
      return OPENAI_COMPATIBLE_PROVIDER;
    }
    const provider = normalized(row.provider);
    const authMethod = normalized(row.authMethod);
    if (provider === 'openai' || provider === 'codex' || authMethod === 'codex') return 'OpenAI';
    if (provider === 'anthropic' || provider === 'claude') return ANTHROPIC_PROVIDER;
    if (provider === 'deepseek') return DEEPSEEK_PROVIDER;
    return 'BuilderID';
  }

  watch(() => form.provider, applyProviderDefaults);
  const isOfficialApi = computed(() => {
    if (!isApiKeyForm.value || normalized(form.authMethod) === 'codex') return false;
    const host =
      normalized(form.provider) === 'openai'
        ? 'api.openai.com'
        : isAnthropicApiKeyForm.value
          ? 'api.anthropic.com'
          : '';
    if (!host) return false;
    try {
      const url = new URL(form.baseUrl.trim() || `https://${host}/v1`);
      return (
        url.protocol === 'https:' &&
        url.hostname === host &&
        !url.port &&
        !url.username &&
        !url.password &&
        !url.search &&
        !url.hash
      );
    } catch {
      return false;
    }
  });
  function applyRoutingDefaults() {
    const api = isApiKeyForm.value && normalized(form.authMethod) !== 'codex';
    const codex = normalized(form.authMethod) === 'codex' || normalized(form.provider) === 'codex';
    Object.assign(policyForm, {
      safeConcurrency: api ? (isOfficialApi.value ? 14 : 0) : codex ? 4 : 2,
      safeRpm: api ? 0 : codex ? 60 : 30,
      burstCapacity: api ? 0 : codex ? 4 : 2,
      maxSessions: 0,
      sessionIdleSeconds: 600,
      cooldownSeconds: 0,
      tlsProfile: '',
    });
  }
  watch([() => form.provider, () => form.authMethod, isOfficialApi], () => {
    if (props.mode === 'create') applyRoutingDefaults();
  });
  watch(
    () => form.catalogVendor,
    (vendor) => {
      if (!isOpenAiCompatibleForm.value) return;
      form.provider = vendor || OPENAI_COMPATIBLE_PROVIDER;
    },
  );

  // 对话框每次打开时按 mode / account 初始化表单，与原 openCreate / openEdit 行为一致
  watch(
    () => props.visible,
    (visible) => {
      if (!visible) return;
      resetForm();
      activeFormTab.value = 'basic';
      void loadCompatibleVendors();
      void proxyChannelsApi
        .catalog()
        .then((value) => {
          proxyCatalog.value = value;
        })
        .catch(() => {
          ElMessage.error(t('代理节点加载失败'));
        });
      if (props.mode === 'edit' && props.account) {
        const row = props.account;
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
          proxyChannelId: row.proxyChannelId ?? '',
          poolTier: normalizedPoolTier(row.poolTier),
          poolWeight: row.poolWeight ?? 100,
          quotaLimit: row.quotaLimit ?? undefined,
          enabled: row.enabled,
          catalogVendor: '',
        });
        protocol.value = protocolFromAccount(row);
        if (protocol.value === OPENAI_COMPATIBLE_PROVIDER) {
          form.catalogVendor = isLegacyOpenAiCompatibleProvider(row.provider)
            ? ''
            : (row.provider ?? '');
        }
        void loadAllocationPolicy(row.accountId);
      } else {
        protocol.value = 'BuilderID';
      }
    },
  );

  async function submitForm() {
    if (!formRef.value) return;
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return;

    formSubmitting.value = true;
    try {
      if (isOpenAiCompatibleForm.value) {
        form.provider = form.catalogVendor.trim();
        form.authMethod = 'api-key';
      }
      if (props.mode === 'create') {
        const { catalogVendor: _catalogVendor, ...createFields } = form;
        const payload = compact({
          ...createFields,
          allocationPolicy: {
            ...policyForm,
            tlsProfile: isAnthropicApiKeyForm.value ? policyForm.tlsProfile : '',
          },
        }) as CreateAccountRequest;
        await accountsApi.create(payload);
        ElMessage.success(t('账号已创建'));
      } else if (props.account) {
        const {
          accessToken,
          refreshToken,
          clientId,
          clientSecret,
          idToken,
          catalogVendor: _catalogVendor,
          ...rest
        } = form;
        // 编辑模式下 access/refresh token 仅在有值时提交
        const payload = compact({
          ...rest,
          clearProxyChannel: !form.proxyChannelId,
          allocationPolicy: allocationPolicy.value
            ? {
                ...policyForm,
                tlsProfile: isAnthropicApiKeyForm.value ? policyForm.tlsProfile : '',
              }
            : undefined,
          accessToken: accessToken || undefined,
          refreshToken: refreshToken || undefined,
          clientId: clientId || undefined,
          clientSecret: clientSecret || undefined,
          idToken: idToken || undefined,
        });
        payload.proxyUrl = form.proxyUrl;
        await accountsApi.update(props.account.accountId, payload);
        ElMessage.success(t('账号已更新'));
      }
      visibleValue.value = false;
      emit('saved');
    } finally {
      formSubmitting.value = false;
    }
  }

  function handleOAuthSaved() {
    emit('saved');
  }

  function lifecycleLabel(state: string | null | undefined) {
    const labels: Record<string, string> = {
      get WARMING() {
        return t('预热中');
      },
      get HEALTHY() {
        return t('健康');
      },
      get DEGRADED() {
        return t('性能下降');
      },
      get COOLDOWN() {
        return t('冷却中');
      },
      get QUARANTINED() {
        return t('已隔离');
      },
      get DISABLED() {
        return t('已停用');
      },
    };
    return labels[state ?? ''] ?? state ?? '-';
  }

  function lifecycleTagType(state: string | null | undefined) {
    if (state === 'HEALTHY') return 'success';
    if (state === 'WARMING') return 'primary';
    if (state === 'DEGRADED' || state === 'COOLDOWN') return 'warning';
    return 'danger';
  }

  function compactNumber(value: number | null | undefined) {
    return new Intl.NumberFormat(locale.value, {
      notation: 'compact',
      maximumFractionDigits: 1,
    }).format(value ?? 0);
  }
</script>

<template>
  <el-dialog
    v-model="visibleValue"
    class="account-form-dialog"
    :title="mode === 'create' ? t('新建账号') : t('编辑账号')"
    width="min(780px, calc(100vw - 32px))"
    destroy-on-close
  >
    <div v-if="mode === 'create'" class="create-method-switch">
      <span class="create-method-switch__label">{{ t('添加方式') }}</span>
      <el-segmented v-model="createMethod" :options="createMethodOptions" />
    </div>

    <ChatGptOAuthFlow
      v-if="isChatGptCreate"
      :active="visible && isChatGptCreate"
      @saved="handleOAuthSaved"
    />

    <el-form v-else ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-alert
        v-if="isOpenAiCompatibleForm"
        class="account-form-alert"
        type="info"
        show-icon
        :closable="false"
        :title="
          t(
            '请先选择你在模型管理中自行添加、且使用 OpenAI 兼容协议的厂商。账号类型会显示该厂商当时配置的图标、名称和配色，测试时只能选择该厂商已配置的模型 ID。填写站点根地址时会自动使用 /v1。',
          )
        "
      />
      <el-alert
        v-else-if="isDeepSeekApiKeyForm"
        class="account-form-alert"
        type="info"
        show-icon
        :closable="false"
        :title="
          t(
            'DeepSeek API Key 账号使用官方 OpenAI 兼容接口；系统已配置 https://api.deepseek.com/v1，无需手动填写 Base URL。可直接使用 deepseek-v4-flash 或 deepseek-v4-pro 模型 ID。',
          )
        "
      />
      <el-alert
        v-else-if="isAnthropicApiKeyForm"
        class="account-form-alert"
        type="info"
        show-icon
        :closable="false"
        :title="
          t(
            'Anthropic API Key 账号会按原生 Messages API 调用 Base URL；模型映射目标建议写成 anthropic/真实模型ID，例如 anthropic/claude-sonnet-4.5。',
          )
        "
      />

      <el-tabs v-model="activeFormTab" class="account-form-tabs">
        <!-- Tab 1: Basic Info -->
        <el-tab-pane :label="t('基本配置')" name="basic">
          <div class="tab-pane-content">
            <el-row :gutter="20">
              <el-col v-if="mode === 'create'" :span="12" :xs="24">
                <el-form-item :label="t('账号 ID（可选）')">
                  <el-input v-model="form.accountId" :placeholder="t('留空时自动生成')" />
                </el-form-item>
              </el-col>
              <el-col :span="mode === 'create' ? 12 : 24" :xs="24">
                <el-form-item :label="t('邮箱')">
                  <el-input v-model="form.email" :placeholder="t('如 email@example.com')" />
                </el-form-item>
              </el-col>

              <el-col :span="12" :xs="24">
                <el-form-item :label="t('账号协议')">
                  <el-select
                    v-model="protocol"
                    filterable
                    class="w-full"
                    @change="handleProtocolChange"
                  >
                    <el-option
                      v-for="item in providerOptions"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="12" :xs="24">
                <el-form-item :label="t('认证方式')">
                  <el-select
                    v-model="form.authMethod"
                    allow-create
                    filterable
                    class="w-full"
                    :disabled="isOpenAiCompatibleForm"
                  >
                    <el-option
                      v-for="item in authMethodOptions"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </el-select>
                </el-form-item>
              </el-col>

              <template v-if="isOpenAiCompatibleForm">
                <el-col :span="12" :xs="24">
                  <el-form-item :label="t('厂商分类')" prop="catalogVendor">
                    <el-select
                      v-model="form.catalogVendor"
                      filterable
                      class="w-full"
                      :loading="vendorsLoading"
                      :placeholder="t('选择已添加的 OpenAI 兼容厂商')"
                      @change="handleVendorChange"
                    >
                      <el-option
                        v-for="vendor in vendorSelectOptions"
                        :key="vendor.name"
                        :label="vendor.name"
                        :value="vendor.name"
                      >
                        <span class="vendor-option">
                          <img
                            v-if="vendor.icon"
                            class="vendor-option__icon"
                            :src="vendor.icon"
                            alt=""
                          />
                          <span v-else class="vendor-option__icon vendor-option__icon--fallback">
                            {{ vendor.name.slice(0, 1).toUpperCase() }}
                          </span>
                          <span class="vendor-option__name">{{ vendor.name }}</span>
                          <span class="vendor-option__count"
                            >{{ vendor.modelCount }} {{ t('个模型') }}</span
                          >
                        </span>
                      </el-option>
                    </el-select>
                    <p v-if="!vendorsLoading && compatibleVendors.length === 0" class="form-hint">
                      {{ t('还没有第三方 OpenAI 兼容厂商。') }}
                      <el-button link type="primary" @click="goAddVendorModel">
                        {{ t('去添加模型') }}
                      </el-button>
                    </p>
                  </el-form-item>
                </el-col>
                <el-col v-if="form.catalogVendor" :span="12" :xs="24">
                  <el-form-item :label="t('账号类型预览')">
                    <ProviderBadge
                      :provider="form.catalogVendor"
                      auth-method="api-key"
                      :color="selectedVendor?.color"
                      :icon="selectedVendor?.icon"
                    />
                    <p class="form-hint">{{ t('图标和配色来自该厂商在模型管理中的设置。') }}</p>
                  </el-form-item>
                </el-col>
              </template>

              <el-col :span="24">
                <el-form-item :label="t('代理出口节点')">
                  <el-select
                    v-model="form.proxyChannelId"
                    clearable
                    filterable
                    class="w-full"
                    :placeholder="t('直连')"
                    @change="form.proxyUrl = ''"
                  >
                    <el-option
                      v-for="node in proxyCatalog?.channels ?? []"
                      :key="node.channelId"
                      :label="`${node.name} · ${node.protocol}`"
                      :value="node.channelId"
                      :disabled="!node.enabled || node.runtimeStatus === 'ERROR'"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="form.proxyUrl" :label="t('出站代理 URL')">
                  <el-input
                    v-model="form.proxyUrl"
                    type="password"
                    show-password
                    clearable
                    autocomplete="off"
                  />
                </el-form-item>
              </el-col>
            </el-row>
          </div>
        </el-tab-pane>

        <!-- Tab 2: Credentials -->
        <el-tab-pane :label="t('凭证配置')" name="credentials">
          <div class="tab-pane-content">
            <el-row :gutter="20">
              <el-col :span="24">
                <el-form-item :label="accessTokenLabel" prop="accessToken">
                  <el-input
                    v-model="form.accessToken"
                    type="textarea"
                    :rows="3"
                    :placeholder="accessTokenPlaceholder"
                  />
                </el-form-item>
              </el-col>

              <el-col :span="24">
                <el-form-item label="Refresh Token">
                  <el-input
                    v-model="form.refreshToken"
                    :placeholder="t('输入 Refresh Token，可选')"
                  />
                </el-form-item>
              </el-col>

              <el-col :span="24">
                <el-form-item label="ID Token">
                  <el-input
                    v-model="form.idToken"
                    type="textarea"
                    :rows="2"
                    :placeholder="t('Codex id_token，可选')"
                  />
                </el-form-item>
              </el-col>

              <el-col :span="24">
                <el-form-item label="Profile ARN">
                  <el-input v-model="form.profileArn" :placeholder="t('AWS Profile ARN，可选')" />
                </el-form-item>
              </el-col>
            </el-row>
          </div>
        </el-tab-pane>

        <!-- Tab 3: Advanced & Routing -->
        <el-tab-pane :label="t('高级与路由')" name="advanced">
          <div class="tab-pane-content">
            <el-row :gutter="20">
              <el-col :span="24">
                <el-form-item :label="baseUrlLabel" prop="baseUrl">
                  <el-input v-model="form.baseUrl" :placeholder="baseUrlPlaceholder" />
                  <p v-if="isOpenAiCompatibleForm" class="form-hint">
                    {{
                      t(
                        '填到兼容 OpenAI 的 API 根路径，通常以 /v1 结尾；服务会自动追加 /chat/completions 或 /responses。',
                      )
                    }}
                  </p>
                  <p v-else-if="isDeepSeekApiKeyForm" class="form-hint">
                    {{ t('已自动使用 DeepSeek 官方 API 地址；仅在接入兼容网关时需要修改。') }}
                  </p>
                  <p v-else-if="isAnthropicApiKeyForm" class="form-hint">
                    {{
                      t(
                        '填到 Anthropic Messages API 根路径，通常以 /v1 结尾；服务会自动追加 /messages。',
                      )
                    }}
                  </p>
                </el-form-item>
              </el-col>

              <el-col :span="12" :xs="24">
                <el-form-item :label="t('上游账号 ID')">
                  <el-input
                    v-model="form.upstreamAccountId"
                    :placeholder="t('Codex ChatGPT Account ID，可选')"
                  />
                </el-form-item>
              </el-col>
              <el-col :span="12" :xs="24">
                <el-form-item label="Machine ID">
                  <el-input v-model="form.machineId" :placeholder="t('机器 ID，可选')" />
                </el-form-item>
              </el-col>

              <el-col :span="12" :xs="24">
                <el-form-item :label="t('区域')">
                  <el-input v-model="form.region" :placeholder="t('如 us-east-1')" />
                </el-form-item>
              </el-col>
              <el-col :span="12" :xs="24">
                <el-row :gutter="12">
                  <el-col :span="mode === 'edit' ? 16 : 24" :xs="24">
                    <el-form-item :label="t('配额上限')">
                      <el-input-number
                        v-model="form.quotaLimit"
                        :min="0"
                        :step="1000"
                        class="w-full"
                      />
                    </el-form-item>
                  </el-col>
                  <el-col v-if="mode === 'edit'" :span="8" :xs="24">
                    <el-form-item :label="t('启用')">
                      <el-switch v-model="form.enabled" class="switch-offset" />
                    </el-form-item>
                  </el-col>
                </el-row>
              </el-col>
            </el-row>
          </div>
        </el-tab-pane>

        <el-tab-pane :label="t('路由与限制')" name="allocation">
          <div v-loading="allocationPolicyLoading" class="tab-pane-content allocation-pane">
            <template v-if="mode === 'create' || allocationPolicy">
              <div v-if="allocationPolicy" class="allocation-runtime">
                <div class="runtime-state">
                  <span class="runtime-label">{{ t('运行状态') }}</span>
                  <el-tag :type="lifecycleTagType(allocationPolicy.lifecycleState)" effect="light">
                    {{ lifecycleLabel(allocationPolicy.lifecycleState) }}
                  </el-tag>
                </div>
                <div class="runtime-metric">
                  <span>{{ t('预热阶段') }}</span>
                  <strong>{{ allocationPolicy.warmupStage }}/4</strong>
                </div>
                <div class="runtime-metric">
                  <span>{{ t('当前并发') }}</span>
                  <strong
                    >{{ allocationPolicy.activeConcurrency }}/{{
                      policyForm.safeConcurrency === 0 ? t('不限') : policyForm.safeConcurrency
                    }}</strong
                  >
                </div>
                <div class="runtime-metric">
                  <span>{{ t('预留令牌') }}</span>
                  <strong>
                    {{
                      compactNumber(
                        allocationPolicy.reservedInputTokens +
                          allocationPolicy.reservedOutputTokens,
                      )
                    }}
                  </strong>
                </div>
                <div class="runtime-metric">
                  <span>{{ t('近期错误率') }}</span>
                  <strong>{{ (allocationPolicy.recentErrorRate * 100).toFixed(1) }}%</strong>
                </div>
              </div>

              <el-row :gutter="20">
                <el-col :span="24"
                  ><el-button link type="primary" @click="applyRoutingDefaults">{{
                    t('应用此类型的默认策略')
                  }}</el-button></el-col
                >
                <el-col :span="12" :xs="24">
                  <el-form-item :label="t('分配优先级')">
                    <el-input-number
                      v-model="policyForm.allocationPriority"
                      :min="1"
                      :max="10000"
                      controls-position="right"
                      class="w-full"
                    />
                    <p class="form-hint">{{ t('数值越小，账号越优先。') }}</p>
                  </el-form-item>
                </el-col>
                <el-col :span="12" :xs="24">
                  <el-form-item :label="t('并发上限（0 为不限）')">
                    <el-input-number
                      v-model="policyForm.safeConcurrency"
                      :min="0"
                      :max="10000"
                      controls-position="right"
                      class="w-full"
                    />
                  </el-form-item>
                </el-col>
                <el-col :span="12" :xs="24">
                  <el-form-item :label="t('平均每分钟请求数（0 为不限）')">
                    <el-input-number
                      v-model="policyForm.safeRpm"
                      :min="0"
                      :max="1000000"
                      controls-position="right"
                      class="w-full"
                    />
                  </el-form-item>
                </el-col>
                <el-col :span="12" :xs="24">
                  <el-form-item :label="t('突发容量')">
                    <el-input-number
                      v-model="policyForm.burstCapacity"
                      :min="0"
                      :disabled="policyForm.safeRpm === 0"
                      :max="100000"
                      controls-position="right"
                      class="w-full"
                    />
                  </el-form-item>
                </el-col>
                <el-col :span="12" :xs="24">
                  <el-form-item :label="t('窗口内不同会话上限（0 为不限）')">
                    <el-input-number
                      v-model="policyForm.maxSessions"
                      :min="0"
                      :max="100000"
                      :disabled="!usesSessionLimit"
                      controls-position="right"
                      class="w-full"
                    />
                  </el-form-item>
                </el-col>
                <el-col :span="12" :xs="24">
                  <el-form-item :label="t('会话窗口（秒）')">
                    <el-input-number
                      v-model="policyForm.sessionIdleSeconds"
                      :min="60"
                      :max="86400"
                      :disabled="!usesSessionLimit"
                      controls-position="right"
                      class="w-full"
                    />
                  </el-form-item>
                </el-col>
                <el-col v-if="isAnthropicApiKeyForm" :span="12" :xs="24">
                  <el-form-item :label="t('TLS 指纹')">
                    <el-select
                      v-model="policyForm.tlsProfile"
                      clearable
                      class="w-full"
                      :placeholder="t('关闭')"
                      :disabled="!proxyCatalog?.tlsEnabled && !policyForm.tlsProfile"
                    >
                      <el-option
                        v-for="profile in proxyCatalog?.tlsProfiles ?? []"
                        :key="profile"
                        :label="profile"
                        :value="profile"
                      />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="12" :xs="24">
                  <el-form-item :label="t('冷却时间（秒，0 为自动）')">
                    <el-input-number
                      v-model="policyForm.cooldownSeconds"
                      :min="0"
                      :max="86400"
                      controls-position="right"
                      class="w-full"
                    />
                  </el-form-item>
                </el-col>
                <el-col :span="24">
                  <el-form-item :label="t('最大在途令牌数')">
                    <el-input-number
                      v-model="policyForm.maxInflightTokens"
                      :min="1"
                      :max="1000000000"
                      :step="10000"
                      controls-position="right"
                      class="w-full"
                    />
                  </el-form-item>
                </el-col>
              </el-row>
            </template>
            <el-empty v-else :image-size="72" :description="t('暂无分配策略')" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-form>

    <template #footer>
      <el-button @click="visibleValue = false">
        {{ isChatGptCreate ? t('关闭') : t('取消') }}
      </el-button>
      <el-button
        v-if="!isChatGptCreate"
        type="primary"
        :loading="formSubmitting"
        @click="submitForm"
      >
        {{ t('提交') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
  .create-method-switch {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 20px;
    padding-bottom: 16px;
    border-bottom: 1px solid var(--kp-border);

    &__label {
      color: var(--kp-text-secondary);
      font-size: 13px;
      font-weight: 600;
      white-space: nowrap;
    }

    :deep(.el-segmented) {
      width: min(360px, 100%);
    }

    :deep(.el-segmented__item) {
      min-width: 0;
    }
  }

  .account-form-alert {
    margin-bottom: 16px;
  }

  .w-full {
    width: 100%;
  }

  .switch-offset {
    margin-top: 6px;
  }

  .form-hint {
    width: 100%;
    margin: 6px 0 0;
    color: var(--kp-text-muted);
    font-size: 12px;
    line-height: 1.45;
  }

  .vendor-option {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;

    &__icon {
      width: 18px;
      height: 18px;
      border-radius: 4px;
      object-fit: cover;
      flex: 0 0 auto;

      &--fallback {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        background: var(--kp-surface-soft);
        color: var(--kp-text-secondary);
        font-size: 11px;
        font-weight: 800;
      }
    }

    &__name {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &__count {
      margin-left: auto;
      color: var(--kp-text-muted);
      font-size: 12px;
    }
  }

  .badge-color-row {
    display: flex;
    align-items: center;
    gap: 12px;
    min-height: 32px;
  }

  .account-form-tabs {
    margin-top: 10px;

    :deep(.el-tabs__header) {
      margin-bottom: 20px;
    }

    :deep(.el-tabs__item) {
      font-weight: 600;
      font-size: 14px;
      padding: 0 20px;
      color: var(--kp-text-secondary);

      &:hover {
        color: var(--kp-primary);
      }
      &.is-active {
        color: var(--kp-primary);
        font-weight: 700;
      }
    }

    .tab-pane-content {
      padding: 4px 2px;
    }
  }

  :global(.account-form-dialog.el-dialog) {
    display: flex;
    flex-direction: column;
    max-height: calc(100vh - 32px);
    margin-top: 16px;
    margin-bottom: 16px;
  }

  :global(.account-form-dialog .el-dialog__body) {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
  }

  @media (max-width: 560px) {
    .create-method-switch {
      align-items: stretch;
      flex-direction: column;
      gap: 10px;

      :deep(.el-segmented) {
        width: 100%;
      }
    }
  }

  .allocation-pane {
    min-height: 330px;
  }

  .allocation-runtime {
    display: grid;
    grid-template-columns: 1.25fr repeat(4, 1fr);
    gap: 0;
    margin-bottom: 20px;
    padding: 12px 0;
    border-top: 1px solid var(--kp-border);
    border-bottom: 1px solid var(--kp-border);
    background: var(--kp-surface-soft);
  }

  .runtime-state,
  .runtime-metric {
    min-width: 0;
    padding: 0 14px;
    border-right: 1px solid var(--kp-border);
  }

  .runtime-metric:last-child {
    border-right: 0;
  }

  .runtime-state {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    justify-content: center;
    gap: 5px;
  }

  .runtime-label,
  .runtime-metric span {
    color: var(--kp-text-muted);
    font-size: 12px;
    line-height: 1.3;
  }

  .runtime-metric {
    display: flex;
    flex-direction: column;
    justify-content: center;
    gap: 3px;

    strong {
      overflow: hidden;
      color: var(--kp-text);
      font-size: 14px;
      line-height: 1.4;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  @media (max-width: 680px) {
    .allocation-runtime {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .runtime-state,
    .runtime-metric {
      min-height: 56px;
      border-bottom: 1px solid var(--kp-border);
    }

    .runtime-metric:nth-child(even) {
      border-right: 0;
    }

    .runtime-metric:last-child {
      grid-column: span 2;
      border-bottom: 0;
    }
  }
</style>
