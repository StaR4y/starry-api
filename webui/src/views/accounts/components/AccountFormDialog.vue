<script setup lang="ts">
  import { t } from '@/i18n';
  /**
   * 新建 / 编辑账号对话框。
   *
   * 单页滚动表单，按步骤分区：账号类型 → 基本信息 → 凭证 → 出站代理 → 路由与限制 → 更多选项。
   * 新建时先选账号类型，之后只显示该类型用到的字段；编辑时类型只读。
   * 各分区位于 ./account-form/，通过 provide / inject 共享表单状态；
   * 提交成功后通知父组件刷新列表。
   */
  import { computed, nextTick, provide, reactive, ref, watch } from 'vue';
  import { useRouter } from 'vue-router';
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
  import { accountsApi, modelsApi } from '@/api';
  import type { Account } from '@/types/api';
  import {
    customOpenAiCompatibleVendors,
    isLegacyOpenAiCompatibleProvider,
    normalizedPoolTier,
    type CompatibleVendorOption,
  } from '../utils';
  import AccountBasicSection from './account-form/AccountBasicSection.vue';
  import AccountCredentialSection from './account-form/AccountCredentialSection.vue';
  import AccountMoreSection from './account-form/AccountMoreSection.vue';
  import AccountProxySection from './account-form/AccountProxySection.vue';
  import AccountRoutingSection from './account-form/AccountRoutingSection.vue';
  import AccountTypeSection from './account-form/AccountTypeSection.vue';
  import {
    accountTypeOf,
    credentialSpec,
    isAnthropicProvider,
    type AccountTypeKey,
  } from './account-form/accountTypes';
  import { accountFormKey } from './account-form/context';
  import {
    applyTypeToForm,
    buildCreatePayload,
    buildUpdatePayload,
    createFormModel,
  } from './account-form/formModel';
  import { useAccountRouting } from './account-form/useAccountRouting';
  import { useProxyCatalog } from './account-form/useProxyCatalog';

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

  const router = useRouter();

  const formRef = ref<FormInstance>();
  const form = reactive(createFormModel());
  const accountType = ref<AccountTypeKey>('kiro');
  const createMethod = ref<'credentials' | 'chatgpt'>('credentials');
  const formSubmitting = ref(false);
  const routingOpen = ref(false);
  const moreOpen = ref(false);

  const compatibleVendors = ref<CompatibleVendorOption[]>([]);
  const vendorsLoading = ref(false);
  const proxyCatalog = useProxyCatalog();

  const mode = computed(() => props.mode);
  const account = computed(() => props.account);
  const spec = computed(() => credentialSpec(accountType.value, form.authMethod));
  const isCompatible = computed(() => accountType.value === 'compatible');
  const isChatGptLogin = computed(
    () =>
      props.mode === 'create' && accountType.value === 'codex' && createMethod.value === 'chatgpt',
  );
  const selectedVendor = computed(
    () => compatibleVendors.value.find((vendor) => vendor.name === form.catalogVendor) ?? null,
  );

  const routing = useAccountRouting({
    mode,
    identity: () => ({
      provider: form.provider,
      authMethod: form.authMethod,
      baseUrl: form.baseUrl,
    }),
    isAnthropic: () => isAnthropicProvider(form.provider),
  });

  provide(accountFormKey, {
    mode,
    account,
    form,
    accountType,
    createMethod,
    spec,
    catalog: proxyCatalog.catalog,
    catalogLoading: proxyCatalog.loading,
    catalogFailed: proxyCatalog.failed,
    reloadCatalog: () => void proxyCatalog.load(),
    routing,
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
          if (isCompatible.value && !text) {
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
          if (isCompatible.value && !form.catalogVendor.trim()) {
            callback(new Error(t('请选择支持 OpenAI 兼容协议的厂商分类')));
            return;
          }
          callback();
        },
        trigger: 'change',
      },
    ],
  };

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

  /** 新建时选择账号类型：写入该类型的身份字段，Codex 默认走浏览器授权 */
  function selectType(type: AccountTypeKey) {
    if (props.mode !== 'create' || type === accountType.value) return;
    accountType.value = type;
    applyTypeToForm(form, type);
    createMethod.value = type === 'codex' ? 'chatgpt' : 'credentials';
    void nextTick(() => formRef.value?.clearValidate());
  }

  function fillFromAccount(row: Account) {
    Object.assign(form, {
      accountId: row.accountId,
      email: row.email ?? '',
      region: row.region ?? '',
      authMethod: row.authMethod ?? '',
      provider: row.provider ?? '',
      baseUrl: row.baseUrl ?? '',
      upstreamAccountId: row.upstreamAccountId ?? '',
      profileArn: row.profileArn ?? '',
      machineId: row.machineId ?? '',
      proxyUrl: row.proxyUrl ?? '',
      proxyChannelId: row.proxyChannelId ?? '',
      poolTier: normalizedPoolTier(row.poolTier),
      poolWeight: row.poolWeight ?? 100,
      quotaLimit: row.quotaLimit ?? undefined,
      enabled: row.enabled,
    });
    accountType.value = accountTypeOf(row.provider, row.authMethod);
    if (accountType.value === 'compatible') {
      form.catalogVendor = isLegacyOpenAiCompatibleProvider(row.provider)
        ? ''
        : (row.provider ?? '');
    }
  }

  /** 打开对话框期间，表单字段的初始化赋值不触发防抖的默认值查询 */
  let initializing = false;

  // 对话框每次打开时按 mode / account 初始化表单
  watch(
    () => props.visible,
    (visible) => {
      if (!visible) return;
      initializing = true;
      routing.reset();
      Object.assign(form, createFormModel());
      accountType.value = 'kiro';
      createMethod.value = 'credentials';
      routingOpen.value = false;
      moreOpen.value = false;
      void loadCompatibleVendors();
      void proxyCatalog.load();
      if (props.mode === 'edit' && props.account) {
        fillFromAccount(props.account);
        void routing.loadForEdit(props.account.accountId);
      }
      void routing.resolveNow();
      void nextTick(() => {
        initializing = false;
      });
    },
  );

  // 账号身份（类型、认证方式、Base URL）变化后，重新查询它会落入的类别与默认值
  watch(
    () => [form.provider, form.authMethod, form.baseUrl.trim()].join('\u0000'),
    () => {
      if (props.visible && !initializing) routing.scheduleResolve();
    },
  );

  async function submitForm() {
    if (!formRef.value) return;
    const valid = await formRef.value
      .validate()
      .then(() => true)
      .catch((fields: Record<string, unknown>) => {
        const first = Object.keys(fields ?? {})[0];
        if (first) formRef.value?.scrollToField(first);
        return false;
      });
    if (!valid) return;

    const problem = routing.validate();
    if (problem) {
      routingOpen.value = true;
      ElMessage.warning(problem);
      return;
    }

    formSubmitting.value = true;
    try {
      if (isCompatible.value) {
        form.provider = form.catalogVendor.trim();
        form.authMethod = 'api-key';
      }
      const allocationPolicy = routing.buildPayload();
      if (props.mode === 'create') {
        await accountsApi.create(buildCreatePayload(form, allocationPolicy));
        ElMessage.success(t('账号已创建'));
      } else if (props.account) {
        await accountsApi.update(
          props.account.accountId,
          buildUpdatePayload(form, allocationPolicy),
        );
        ElMessage.success(t('账号已更新'));
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
    class="account-form-dialog"
    :title="mode === 'create' ? t('新建账号') : t('编辑账号')"
    width="min(860px, calc(100vw - 32px))"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <el-form
      ref="formRef"
      class="account-form"
      :model="form"
      :rules="rules"
      label-position="top"
      @submit.prevent
    >
      <AccountTypeSection :vendor="selectedVendor" @select="selectType" />
      <AccountBasicSection
        v-if="!isChatGptLogin"
        :vendors="compatibleVendors"
        :vendors-loading="vendorsLoading"
        @add-vendor="goAddVendorModel"
      />
      <AccountCredentialSection :active="visible" @oauth-saved="emit('saved')" />
      <template v-if="!isChatGptLogin">
        <AccountProxySection />
        <AccountRoutingSection v-model:open="routingOpen" />
        <AccountMoreSection v-model:open="moreOpen" />
      </template>
    </el-form>

    <template #footer>
      <el-button @click="visibleValue = false">
        {{ isChatGptLogin ? t('关闭') : t('取消') }}
      </el-button>
      <el-button
        v-if="!isChatGptLogin"
        type="primary"
        :loading="formSubmitting"
        @click="submitForm"
      >
        {{ t('提交') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style lang="scss">
  .account-form-dialog.el-dialog {
    display: flex;
    flex-direction: column;
    max-height: calc(100vh - 32px);
    margin-top: 16px;
    margin-bottom: 16px;

    .el-dialog__body {
      flex: 1;
      min-height: 0;
      overflow-y: auto;
    }

    /* 分区共用的布局与提示样式 */
    .af-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      column-gap: 16px;
    }

    .af-span-2 {
      grid-column: 1 / -1;
    }

    .af-full {
      width: 100%;
    }

    .af-hint {
      width: 100%;
      margin: 6px 0 0;
      color: var(--kp-text-muted);
      font-size: 12px;
      line-height: 1.45;

      &--warn {
        color: var(--kp-warning);
      }
    }
  }

  @media (max-width: 640px) {
    .account-form-dialog.el-dialog .af-grid {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
