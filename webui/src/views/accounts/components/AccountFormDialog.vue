<script setup lang="ts">
  /**
   * 新建 / 编辑账号对话框。
   *
   * 从 AccountListView.vue 原样抽出：三页签表单（基本配置 / 凭证配置 /
   * 高级与路由）、表单校验与提交逻辑；提交成功后通知父组件刷新列表。
   */
  import { computed, reactive, ref, watch } from 'vue';
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
  import { accountsApi } from '@/api';
  import type { Account, CreateAccountRequest } from '@/types/api';
  import { normalized, normalizedPoolTier } from '../utils';

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

  const formRef = ref<FormInstance>();
  const formSubmitting = ref(false);
  const activeFormTab = ref('basic');

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
    poolTier: 'primary',
    poolWeight: 100,
    quotaLimit: undefined as number | undefined,
    enabled: true,
  });

  const isOpenAiCompatibleForm = computed(() => isOpenAiCompatibleProvider(form.provider));
  const isOpenAiApiKeyForm = computed(
    () => isOpenAiCompatibleForm.value || normalized(form.provider) === 'openai',
  );
  const isAnthropicApiKeyForm = computed(() => isAnthropicProvider(form.provider));
  const isApiKeyForm = computed(() => isOpenAiApiKeyForm.value || isAnthropicApiKeyForm.value);
  const accessTokenLabel = computed(() => {
    const suffix = props.mode === 'edit' ? '（留空则不修改）' : '';
    return isApiKeyForm.value ? `API Key${suffix}` : `Access Token${suffix}`;
  });
  const accessTokenPlaceholder = computed(() => {
    if (isAnthropicApiKeyForm.value) return '输入 Anthropic / Claude API Key，如 sk-ant-...';
    return isOpenAiApiKeyForm.value ? '输入上游网站 API Key，如 sk-...' : '输入 Access Token';
  });
  const baseUrlLabel = computed(() => {
    if (isOpenAiCompatibleForm.value) return '第三方 Base URL';
    if (isAnthropicApiKeyForm.value) return 'Anthropic Base URL';
    return 'Base URL';
  });
  const baseUrlPlaceholder = computed(() => {
    if (isOpenAiCompatibleForm.value) {
      return '例如 https://joystar.cc、https://api.deepseek.com 或 https://openrouter.ai/api/v1';
    }
    if (isAnthropicApiKeyForm.value) {
      return '默认 https://api.anthropic.com/v1，也可填第三方 Anthropic 兼容地址';
    }
    return '默认 https://api.openai.com/v1';
  });

  const rules: FormRules = {
    accessToken: [{ required: true, message: 'API Key / accessToken 必填', trigger: 'blur' }],
    baseUrl: [
      {
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
      },
    ],
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
      poolTier: 'primary',
      poolWeight: 100,
      quotaLimit: undefined,
      enabled: true,
    });
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
    { label: 'Anthropic / Claude API Key', value: ANTHROPIC_PROVIDER },
  ];

  const authMethodOptions = [
    { label: 'idc', value: 'idc' },
    { label: 'social', value: 'social' },
    { label: 'api-key', value: 'api-key' },
    { label: 'codex', value: 'codex' },
  ];

  // normalized / normalizedPoolTier 已收敛至 ../utils

  function isOpenAiCompatibleProvider(provider: string | null | undefined) {
    const value = normalized(provider);
    return value === 'openai-compatible' || value === 'openaicompatibility';
  }

  function isAnthropicProvider(provider: string | null | undefined) {
    const value = normalized(provider);
    return value === 'anthropic' || value === 'claude';
  }

  function applyProviderDefaults(provider: string | null | undefined) {
    const value = normalized(provider);
    const auth = normalized(form.authMethod);
    if (isOpenAiCompatibleProvider(provider)) {
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

  watch(() => form.provider, applyProviderDefaults);

  // 对话框每次打开时按 mode / account 初始化表单，与原 openCreate / openEdit 行为一致
  watch(
    () => props.visible,
    (visible) => {
      if (!visible) return;
      resetForm();
      activeFormTab.value = 'basic';
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
          poolTier: normalizedPoolTier(row.poolTier),
          poolWeight: row.poolWeight ?? 100,
          quotaLimit: row.quotaLimit ?? undefined,
          enabled: row.enabled,
        });
      }
    },
  );

  async function submitForm() {
    if (!formRef.value) return;
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return;

    formSubmitting.value = true;
    try {
      if (props.mode === 'create') {
        const payload = compact({ ...form }) as CreateAccountRequest;
        await accountsApi.create(payload);
        ElMessage.success('账号已创建');
      } else if (props.account) {
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
        await accountsApi.update(props.account.accountId, payload);
        ElMessage.success('账号已更新');
      }
      visibleValue.value = false;
      emit('saved');
    } finally {
      formSubmitting.value = false;
    }
  }
</script>

<template>
  <el-dialog
    v-model="visibleValue"
    :title="mode === 'create' ? '新建账号' : '编辑账号'"
    width="720px"
    destroy-on-close
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
      <el-alert
        v-else-if="isAnthropicApiKeyForm"
        class="account-form-alert"
        type="info"
        show-icon
        :closable="false"
        title="Anthropic API Key 账号会按原生 Messages API 调用 Base URL；模型映射目标建议写成 anthropic/真实模型ID，例如 anthropic/claude-sonnet-4.5。"
      />

      <el-tabs v-model="activeFormTab" class="account-form-tabs">
        <!-- Tab 1: Basic Info -->
        <el-tab-pane label="基本配置" name="basic">
          <div class="tab-pane-content">
            <el-row :gutter="20">
              <el-col v-if="mode === 'create'" :span="12">
                <el-form-item label="账号 ID（可选）">
                  <el-input v-model="form.accountId" placeholder="留空时自动生成" />
                </el-form-item>
              </el-col>
              <el-col :span="mode === 'create' ? 12 : 24">
                <el-form-item label="邮箱">
                  <el-input v-model="form.email" placeholder="如 email@example.com" />
                </el-form-item>
              </el-col>

              <el-col :span="12">
                <el-form-item label="Provider">
                  <el-select v-model="form.provider" allow-create filterable class="w-full">
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
                  <el-select v-model="form.authMethod" allow-create filterable class="w-full">
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
                <el-form-item label="账号池级别">
                  <el-radio-group v-model="form.poolTier">
                    <el-radio-button label="primary">一级号池</el-radio-button>
                    <el-radio-button label="secondary">二级号池</el-radio-button>
                  </el-radio-group>
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="基础权重">
                  <el-input-number
                    v-model="form.poolWeight"
                    :min="1"
                    :max="10000"
                    :step="10"
                    class="w-full"
                  />
                  <p class="form-hint">自动权重开启时，连续错误越多，实际调度权重越低。</p>
                </el-form-item>
              </el-col>

              <el-col :span="24">
                <el-form-item label="出站代理 URL">
                  <el-input v-model="form.proxyUrl" placeholder="http://user:pass@host:port" />
                </el-form-item>
              </el-col>
            </el-row>
          </div>
        </el-tab-pane>

        <!-- Tab 2: Credentials -->
        <el-tab-pane label="凭证配置" name="credentials">
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
                  <el-input v-model="form.refreshToken" placeholder="输入 Refresh Token，可选" />
                </el-form-item>
              </el-col>

              <el-col :span="24">
                <el-form-item label="ID Token">
                  <el-input
                    v-model="form.idToken"
                    type="textarea"
                    :rows="2"
                    placeholder="Codex id_token，可选"
                  />
                </el-form-item>
              </el-col>

              <el-col :span="24">
                <el-form-item label="Profile ARN">
                  <el-input v-model="form.profileArn" placeholder="AWS Profile ARN，可选" />
                </el-form-item>
              </el-col>
            </el-row>
          </div>
        </el-tab-pane>

        <!-- Tab 3: Advanced & Routing -->
        <el-tab-pane label="高级与路由" name="advanced">
          <div class="tab-pane-content">
            <el-row :gutter="20">
              <el-col :span="24">
                <el-form-item :label="baseUrlLabel" prop="baseUrl">
                  <el-input v-model="form.baseUrl" :placeholder="baseUrlPlaceholder" />
                  <p v-if="isOpenAiCompatibleForm" class="form-hint">
                    填到兼容 OpenAI 的 API 根路径，通常以 /v1 结尾；服务会自动追加 /chat/completions
                    或 /responses。
                  </p>
                  <p v-else-if="isAnthropicApiKeyForm" class="form-hint">
                    填到 Anthropic Messages API 根路径，通常以 /v1 结尾；服务会自动追加 /messages。
                  </p>
                </el-form-item>
              </el-col>

              <el-col :span="12">
                <el-form-item label="上游账号 ID">
                  <el-input
                    v-model="form.upstreamAccountId"
                    placeholder="Codex ChatGPT Account ID，可选"
                  />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="Machine ID">
                  <el-input v-model="form.machineId" placeholder="机器 ID，可选" />
                </el-form-item>
              </el-col>

              <el-col :span="12">
                <el-form-item label="区域">
                  <el-input v-model="form.region" placeholder="如 us-east-1" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-row :gutter="12">
                  <el-col :span="mode === 'edit' ? 16 : 24">
                    <el-form-item label="配额上限">
                      <el-input-number
                        v-model="form.quotaLimit"
                        :min="0"
                        :step="1000"
                        class="w-full"
                      />
                    </el-form-item>
                  </el-col>
                  <el-col v-if="mode === 'edit'" :span="8">
                    <el-form-item label="启用">
                      <el-switch v-model="form.enabled" class="switch-offset" />
                    </el-form-item>
                  </el-col>
                </el-row>
              </el-col>
            </el-row>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-form>

    <template #footer>
      <el-button @click="visibleValue = false">取消</el-button>
      <el-button type="primary" :loading="formSubmitting" @click="submitForm">提交</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
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
      max-height: 480px;
      overflow-y: auto;
    }
  }
</style>
