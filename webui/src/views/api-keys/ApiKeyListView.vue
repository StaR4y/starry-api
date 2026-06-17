<script setup lang="ts">
  /**
   * API Key 管理。
   *
   * 关键点：API Key 按登录用户隔离，完整密钥由后端保存在所属用户记录中，便于直接复制。
   */
  import { computed, onMounted, reactive, ref } from 'vue';
  import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
  import PageSection from '@/components/PageSection.vue';
  import { apiKeysApi } from '@/api';
  import { useAuthStore } from '@/stores/auth';
  import type { ApiKey, CreateApiKeyRequest, CreatedApiKey } from '@/types/api';
  import { formatDateTime, formatNumber } from '@/utils/format';
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
  const auth = useAuthStore();
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

  /* ============= 新建 / 编辑 ============= */

  type FormMode = 'create' | 'edit';
  const formVisible = ref(false);
  const formMode = ref<FormMode>('create');
  const formRef = ref<FormInstance>();
  const formSubmitting = ref(false);
  const editingKeyId = ref<string | null>(null);

  const form = reactive({
    name: '',
    creditsLimit: undefined as number | undefined,
    enabled: true,
  });

  const rules: FormRules = {
    name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  };

  function resetForm() {
    Object.assign(form, { name: '', creditsLimit: undefined, enabled: true });
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
      name: row.name,
      creditsLimit: row.creditsLimit ?? undefined,
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
      ? '当前 API Key 已按用户隔离保存，可直接复制密钥或客户端配置。'
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
          name: form.name,
          creditsLimit: form.creditsLimit,
        };
        const created = await apiKeysApi.create(payload);
        formVisible.value = false;
        newKey.value = created;
        newKeyVisible.value = true;
      } else if (editingKeyId.value) {
        await apiKeysApi.update(editingKeyId.value, {
          name: form.name,
          creditsLimit: form.creditsLimit,
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

  onMounted(loadKeys);
</script>

<template>
  <PageSection title="API Key" subtitle="向客户端下发的代理密钥（sk-...）">
    <template #actions>
      <el-button @click="copy(apiBaseUrl)">复制 API 地址</el-button>
      <el-button type="primary" @click="openCreate">新建 API Key</el-button>
    </template>

    <el-table v-loading="loading" :data="apiKeys" stripe>
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="前缀" width="200">
        <template #default="{ row }">
          <span class="mono">{{ row.keyPrefix }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="auth.isAdmin" label="归属用户" min-width="150">
        <template #default="{ row }">
          <span class="mono">{{ row.userId || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" effect="light" round>
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="积分上限" width="120">
        <template #default="{ row }">{{ formatNumber(row.creditsLimit) }}</template>
      </el-table-column>
      <el-table-column label="累计请求" width="120">
        <template #default="{ row }">{{ formatNumber(row.totalRequests) }}</template>
      </el-table-column>
      <el-table-column label="累计积分" width="120">
        <template #default="{ row }">{{ formatNumber(row.totalCredits) }}</template>
      </el-table-column>
      <el-table-column label="最近使用" width="180">
        <template #default="{ row }">{{ formatDateTime(row.lastUsedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="340" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="toggleEnabled(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
          <el-button link type="primary" @click="copyRowApiKey(row)">复制 Key</el-button>
          <el-button link type="primary" @click="openShellConfig(row)">客户端配置</el-button>
          <el-button link type="danger" @click="removeKey(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </PageSection>

  <el-dialog
    v-model="formVisible"
    :title="formMode === 'create' ? '新建 API Key' : '编辑 API Key'"
    width="480px"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="积分上限（可选）">
        <el-input-number v-model="form.creditsLimit" :min="0" :step="100" style="width: 100%" />
      </el-form-item>
      <el-form-item v-if="formMode === 'edit'" label="启用">
        <el-switch v-model="form.enabled" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="formVisible = false">取消</el-button>
      <el-button type="primary" :loading="formSubmitting" @click="submitForm">提交</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="configVisible" :title="configDialogTitle" width="900px">
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
          <div class="secret-field">
            <span class="secret-field__label">名称</span>
            <code class="secret-field__value mono" :title="configKey.name">{{
              configKey.name
            }}</code>
          </div>
          <div class="secret-field">
            <span class="secret-field__label">Codex</span>
            <code class="secret-field__value mono" :title="apiBaseUrl">{{ apiBaseUrl }}</code>
            <el-button size="small" @click="copy(apiBaseUrl)">复制</el-button>
          </div>
          <div class="secret-field">
            <span class="secret-field__label">Claude</span>
            <code class="secret-field__value mono" :title="apiOrigin">{{ apiOrigin }}</code>
            <el-button size="small" @click="copy(apiOrigin)">复制</el-button>
          </div>
          <div class="secret-field">
            <span class="secret-field__label">OpenCode</span>
            <code class="secret-field__value mono" :title="apiBaseUrl">
              {{ apiBaseUrl }}
            </code>
            <el-button size="small" @click="copy(apiBaseUrl)">复制</el-button>
          </div>
          <div class="secret-field">
            <span class="secret-field__label">API Key</span>
            <code class="secret-field__value mono" :title="configApiKey">{{ configApiKey }}</code>
            <el-button size="small" :disabled="!hasConfigFullApiKey" @click="copy(configApiKey)">
              复制
            </el-button>
          </div>
          <div class="secret-field secret-field--compact">
            <span class="secret-field__label">端口</span>
            <code class="secret-field__value mono">{{ apiPort }}</code>
            <el-button size="small" @click="copy(apiPort)">复制</el-button>
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
              <el-radio-button v-for="item in setupPlatforms" :key="item.value" :label="item.value">
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
          <div class="secret-field">
            <span class="secret-field__label">Codex</span>
            <code class="secret-field__value mono" :title="apiBaseUrl">{{ apiBaseUrl }}</code>
            <el-button size="small" @click="copy(apiBaseUrl)">复制</el-button>
          </div>
          <div class="secret-field">
            <span class="secret-field__label">Claude</span>
            <code class="secret-field__value mono" :title="apiOrigin">{{ apiOrigin }}</code>
            <el-button size="small" @click="copy(apiOrigin)">复制</el-button>
          </div>
          <div class="secret-field">
            <span class="secret-field__label">OpenCode</span>
            <code class="secret-field__value mono" :title="apiBaseUrl">
              {{ apiBaseUrl }}
            </code>
            <el-button size="small" @click="copy(apiBaseUrl)">复制</el-button>
          </div>
          <div class="secret-field">
            <span class="secret-field__label">API Key</span>
            <code class="secret-field__value mono" :title="newKey.key">{{ newKey.key }}</code>
            <el-button size="small" type="primary" @click="copy(newKey.key)">复制</el-button>
          </div>
          <div class="secret-field secret-field--compact">
            <span class="secret-field__label">端口</span>
            <code class="secret-field__value mono">{{ apiPort }}</code>
            <el-button size="small" @click="copy(apiPort)">复制</el-button>
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
              <el-radio-button v-for="item in setupPlatforms" :key="item.value" :label="item.value">
                {{ item.label }}
              </el-radio-button>
            </el-radio-group>
          </div>
        </div>

        <div class="script-preview">
          <div>
            <h4>{{ setupScriptBlock.clientLabel }} / {{ setupScriptBlock.platformLabel }}</h4>
            <p>
              {{ setupScriptBlock.clientDescription }}；{{ setupScriptBlock.platformDescription }}。
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
</template>

<style scoped lang="scss">
  .secret-dialog-body,
  .config-dialog-body {
    display: grid;
    gap: 14px;
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
