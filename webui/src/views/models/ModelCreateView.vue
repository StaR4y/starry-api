<script setup lang="ts">
  import { computed, onMounted, reactive, ref, watch } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { ElMessage, ElCollapseTransition, type FormInstance, type FormRules } from 'element-plus';
  import { ArrowDown, ArrowLeft, ArrowRight, Check, Cpu, Plus } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import ProviderLogo from '@/components/ProviderLogo.vue';
  import ContextWindowField from './components/ContextWindowField.vue';
  import ModelIconField from './components/ModelIconField.vue';
  import { modelsApi } from '@/api';
  import { canonicalCatalogProvider, getProviderOrder, inferModelFamily, orderOf } from '@/platforms/models';
  import type { ManagedModel, UpsertManagedModelRequest } from '@/types/api';
  import { badgeColorFromName, isFirstClassAccountProvider } from '@/views/accounts/utils';

  interface ProviderOption {
    name: string;
    modelCount: number;
    familyCount: number;
  }

  type SectionKey = 'provider' | 'info' | 'advanced';

  const CUSTOM_VALUE = '__custom__';
  const route = useRoute();
  const router = useRouter();
  const formRef = ref<FormInstance>();
  const catalogLoading = ref(false);
  const saving = ref(false);
  const models = ref<ManagedModel[]>([]);
  const openSection = ref<SectionKey>('provider');
  const selectedProvider = ref('');
  const selectedFamily = ref('');
  const customProvider = ref('');
  const customFamily = ref('');
  const nameDirty = ref(false);
  const familyDirty = ref(false);

  const form = reactive<UpsertManagedModelRequest>({
    id: '',
    name: '',
    icon: null,
    provider: '',
    family: '',
    targetModelId: '',
    requestFormat: 'OPENAI_CHAT_COMPLETIONS',
    endpoint: '/v1/chat/completions',
    contextWindow: 200000,
    description: '',
    enabled: true,
    badgeColor: '',
  });

  const rules: FormRules = {
    provider: [{ required: true, message: '请输入厂商名称', trigger: 'blur' }],
    family: [{ required: true, message: '请输入模型分类', trigger: 'blur' }],
    id: [
      { required: true, message: '请输入模型 ID', trigger: 'blur' },
      {
        pattern: /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}$/,
        message: '仅支持字母、数字及 . _ : / -',
        trigger: 'blur',
      },
    ],
    name: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
    endpoint: [{ required: true, message: '请输入 endpoint', trigger: 'blur' }],
    contextWindow: [{ required: true, message: '请输入上下文窗口', trigger: 'change' }],
    badgeColor: [
      {
        validator: (_rule, value, callback) => {
          if (!isCustomCatalogVendor.value) {
            callback();
            return;
          }
          if (!/^#[0-9A-Fa-f]{6}$/.test(typeof value === 'string' ? value : '')) {
            callback(new Error('请为该厂商选择配色'));
            return;
          }
          callback();
        },
        trigger: 'change',
      },
    ],
  };

  const providerOptions = computed<ProviderOption[]>(() => {
    const grouped = new Map<string, ManagedModel[]>();
    models.value.forEach((model) => {
      const provider = canonicalCatalogProvider(model.provider) || 'Custom';
      grouped.set(provider, [...(grouped.get(provider) || []), model]);
    });
    const providerOrder = getProviderOrder();
    return Array.from(grouped, ([name, providerModels]) => ({
      name,
      modelCount: providerModels.length,
      familyCount: new Set(providerModels.map((model) => model.family)).size,
    })).sort((left, right) => {
      const difference = orderOf(providerOrder, left.name) - orderOf(providerOrder, right.name);
      return difference || left.name.localeCompare(right.name);
    });
  });

  const familyOptions = computed(() =>
    Array.from(
      new Set(
        models.value
          .filter((model) => model.provider === form.provider)
          .map((model) => model.family)
          .filter(Boolean),
      ),
    ).sort((left, right) => left.localeCompare(right)),
  );

  const existingModel = computed(() => models.value.find((model) => model.id === form.id.trim()));

  const isCustomCatalogVendor = computed(
    () =>
      selectedProvider.value === CUSTOM_VALUE ||
      Boolean(form.provider?.trim() && !isFirstClassAccountProvider(form.provider)),
  );

  const providerDone = computed(
    () =>
      Boolean(form.provider?.trim() && form.family?.trim()) &&
      (!isCustomCatalogVendor.value || /^#[0-9A-Fa-f]{6}$/.test(form.badgeColor || '')),
  );

  const infoDone = computed(
    () =>
      /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}$/.test(form.id.trim()) &&
      Boolean(form.name.trim()) &&
      form.contextWindow > 0,
  );

  /** 常见 ID 片段的展示名映射，其余片段按首字母大写处理。 */
  const KNOWN_SEGMENTS: Record<string, string> = {
    gpt: 'GPT',
    chatgpt: 'ChatGPT',
    deepseek: 'DeepSeek',
    claude: 'Claude',
    gemini: 'Gemini',
    llama: 'LLaMA',
    qwen: 'Qwen',
    glm: 'GLM',
    mistral: 'Mistral',
    grok: 'Grok',
  };

  function deriveName(id: string) {
    const local = id.split('/').pop() ?? id;
    return local
      .split(/([-.:_])/)
      .map((segment) => {
        if (!segment || /^[-.:_]$/.test(segment)) return segment;
        const known = KNOWN_SEGMENTS[segment.toLowerCase()];
        if (known) return known;
        if (/^\d/.test(segment)) return segment;
        return segment.charAt(0).toUpperCase() + segment.slice(1);
      })
      .join('');
  }

  watch(customProvider, (value) => {
    if (selectedProvider.value !== CUSTOM_VALUE) return;
    form.provider = value;
    if (value.trim() && !form.badgeColor) form.badgeColor = badgeColorFromName(value);
  });

  watch(customFamily, (value) => {
    if (selectedFamily.value === CUSTOM_VALUE) form.family = value;
  });

  watch(
    () => form.id,
    (value) => {
      const id = value.trim();
      if (!nameDirty.value) form.name = deriveName(id);
      if (!id || familyDirty.value) return;
      const inferred = inferModelFamily({ id, object: 'model', owned_by: form.provider || '' });
      if (inferred && familyOptions.value.includes(inferred)) {
        selectedFamily.value = inferred;
        form.family = inferred;
      }
    },
  );

  function queryValue(name: string) {
    const value = route.query[name];
    return typeof value === 'string' ? value : '';
  }

  function setSection(section: SectionKey) {
    openSection.value = section;
  }

  function chooseProvider(provider: string, preferredFamily = '') {
    selectedProvider.value = provider;
    familyDirty.value = false;
    if (provider === CUSTOM_VALUE) {
      form.provider = customProvider.value;
      form.badgeColor = customProvider.value.trim()
        ? badgeColorFromName(customProvider.value)
        : '';
      chooseFamily(CUSTOM_VALUE);
      return;
    }
    form.provider = provider;
    form.badgeColor = vendorColor(provider);
    const providerFamilies = Array.from(
      new Set(
        models.value.filter((model) => model.provider === provider).map((model) => model.family),
      ),
    );
    chooseFamily(
      preferredFamily && providerFamilies.includes(preferredFamily)
        ? preferredFamily
        : providerFamilies[0] || CUSTOM_VALUE,
    );
    familyDirty.value = false;
  }

  function vendorColor(provider: string) {
    return (
      models.value.find((model) => model.provider === provider && model.enabled && model.badgeColor)
        ?.badgeColor ||
      models.value.find((model) => model.provider === provider && model.badgeColor)?.badgeColor ||
      ''
    );
  }

  function chooseFamily(family: string, manual = false) {
    if (manual) familyDirty.value = true;
    selectedFamily.value = family;
    form.family = family === CUSTOM_VALUE ? customFamily.value : family;
  }

  async function loadCatalog() {
    catalogLoading.value = true;
    try {
      models.value = await modelsApi.list();
      const preferredProvider = canonicalCatalogProvider(queryValue('provider')) || queryValue('provider');
      const preferredFamily = queryValue('family');
      const initialProvider = providerOptions.value.some(
        (provider) => provider.name === preferredProvider,
      )
        ? preferredProvider
        : providerOptions.value[0]?.name;
      if (initialProvider) {
        chooseProvider(initialProvider, preferredFamily);
      } else {
        chooseProvider(CUSTOM_VALUE);
      }
      if (providerDone.value) openSection.value = 'info';
    } catch {
      chooseProvider(CUSTOM_VALUE);
    } finally {
      catalogLoading.value = false;
    }
  }

  async function submitForm() {
    if (!formRef.value) return;
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) {
      openSection.value = providerDone.value ? 'info' : 'provider';
      return;
    }
    saving.value = true;
    try {
      const id = form.id.trim();
      await modelsApi.upsert({
        ...form,
        id,
        name: form.name.trim(),
        icon: form.icon?.trim() || null,
        provider: form.provider?.trim() || 'Custom',
        family: form.family?.trim() || 'Custom',
        targetModelId: form.targetModelId?.trim() || id,
        endpoint: form.endpoint.trim(),
        description: form.description?.trim() || null,
        badgeColor: isCustomCatalogVendor.value ? form.badgeColor : undefined,
      });
      ElMessage.success('模型已添加');
      await router.push({ name: 'settings-models' });
    } finally {
      saving.value = false;
    }
  }

  onMounted(loadCatalog);
</script>

<template>
  <PageSection title="添加模型" subtitle="业务配置" :icon="Cpu">
    <template #actions>
      <el-button :icon="ArrowLeft" @click="router.push({ name: 'settings-models' })">返回</el-button>
      <el-button type="primary" :icon="Check" :loading="saving" @click="submitForm">
        保存模型
      </el-button>
    </template>

    <el-form
      ref="formRef"
      v-loading="catalogLoading"
      :model="form"
      :rules="rules"
      label-position="top"
      class="model-create-form"
    >
      <div class="accordion-stack">
        <section
          class="accordion-card"
          :class="{ 'is-open': openSection === 'provider', 'is-done': providerDone }"
        >
          <header
            class="accordion-card__head"
            role="button"
            tabindex="0"
            :aria-expanded="openSection === 'provider'"
            @click="setSection('provider')"
            @keydown.enter.space.prevent="setSection('provider')"
          >
            <span class="step-badge">
              <el-icon v-if="providerDone && openSection !== 'provider'"><Check /></el-icon>
              <template v-else>1</template>
            </span>
            <div class="accordion-card__title">
              <h3>厂商与分类</h3>
              <p v-if="openSection === 'provider'">选择模型所属厂商，以及要归入的系列分类</p>
              <p v-else>{{ form.provider || '未设置厂商' }} / {{ form.family || '未设置分类' }}</p>
            </div>
            <el-icon class="toggle-arrow"><ArrowDown /></el-icon>
          </header>

          <el-collapse-transition>
            <div v-show="openSection === 'provider'" class="accordion-card__body">
              <div class="provider-grid" role="listbox" aria-label="选择模型厂商">
                <button
                  v-for="provider in providerOptions"
                  :key="provider.name"
                  type="button"
                  class="provider-choice"
                  :class="{ 'is-active': selectedProvider === provider.name }"
                  :aria-selected="selectedProvider === provider.name"
                  @click="chooseProvider(provider.name)"
                >
                  <ProviderLogo :provider="provider.name" :size="36" />
                  <span>
                    <strong>{{ provider.name }}</strong>
                    <small>{{ provider.modelCount }} 模型 · {{ provider.familyCount }} 分类</small>
                  </span>
                  <el-icon v-if="selectedProvider === provider.name"><Check /></el-icon>
                </button>
                <button
                  type="button"
                  class="provider-choice provider-choice--custom"
                  :class="{ 'is-active': selectedProvider === CUSTOM_VALUE }"
                  :aria-selected="selectedProvider === CUSTOM_VALUE"
                  @click="chooseProvider(CUSTOM_VALUE)"
                >
                  <span class="add-provider-icon">
                    <el-icon>
                      <Plus />
                    </el-icon>
                  </span>
                  <span>
                    <strong>自定义厂商</strong>
                    <small>创建新的厂商分组</small>
                  </span>
                  <el-icon v-if="selectedProvider === CUSTOM_VALUE"><Check /></el-icon>
                </button>
              </div>

              <el-form-item
                v-if="selectedProvider === CUSTOM_VALUE"
                label="厂商名称"
                prop="provider"
                class="inline-field"
              >
                <el-input v-model="customProvider" maxlength="64" placeholder="例如 Mistral AI" />
              </el-form-item>

              <el-form-item
                v-if="isCustomCatalogVendor"
                label="厂商配色"
                prop="badgeColor"
                class="inline-field"
              >
                <div class="vendor-color-row">
                  <el-color-picker
                    :model-value="form.badgeColor || undefined"
                    @update:model-value="form.badgeColor = $event || ''"
                    color-format="hex"
                    :predefine="[
                      '#4D6BFE',
                      '#10A37F',
                      '#7C3AED',
                      '#D97757',
                      '#0EA5E9',
                      '#E11D48',
                      '#CA8A04',
                      '#0F766E',
                    ]"
                  />
                  <span class="vendor-color-row__hint">账号类型会使用这个颜色</span>
                </div>
              </el-form-item>

              <div class="family-area">
                <label>模型分类</label>
                <div class="family-options" role="listbox" aria-label="选择模型分类">
                  <button
                    v-for="family in familyOptions"
                    :key="family"
                    type="button"
                    :class="{ 'is-active': selectedFamily === family }"
                    :aria-selected="selectedFamily === family"
                    @click="chooseFamily(family, true)"
                  >
                    {{ family }}
                  </button>
                  <button
                    type="button"
                    :class="{ 'is-active': selectedFamily === CUSTOM_VALUE }"
                    :aria-selected="selectedFamily === CUSTOM_VALUE"
                    @click="chooseFamily(CUSTOM_VALUE, true)"
                  >
                    <el-icon><Plus /></el-icon>
                    新建分类
                  </button>
                </div>
                <el-form-item
                  v-if="selectedFamily === CUSTOM_VALUE"
                  prop="family"
                  class="inline-field family-input"
                >
                  <el-input v-model="customFamily" maxlength="128" placeholder="例如 Large" />
                </el-form-item>
              </div>

              <div class="accordion-card__actions">
                <el-button type="primary" plain :icon="ArrowRight" @click="setSection('info')">
                  下一步：模型信息
                </el-button>
              </div>
            </div>
          </el-collapse-transition>
        </section>

        <section
          class="accordion-card"
          :class="{ 'is-open': openSection === 'info', 'is-done': infoDone }"
        >
          <header
            class="accordion-card__head"
            role="button"
            tabindex="0"
            :aria-expanded="openSection === 'info'"
            @click="setSection('info')"
            @keydown.enter.space.prevent="setSection('info')"
          >
            <span class="step-badge">
              <el-icon v-if="infoDone && openSection !== 'info'"><Check /></el-icon>
              <template v-else>2</template>
            </span>
            <div class="accordion-card__title">
              <h3>模型信息</h3>
              <p v-if="openSection === 'info'">模型 ID 用于客户端调用，名称与分类会自动填充</p>
              <p v-else>{{ form.name || form.id || '未命名模型' }}</p>
            </div>
            <el-icon class="toggle-arrow"><ArrowDown /></el-icon>
          </header>

          <el-collapse-transition>
            <div v-show="openSection === 'info'" class="accordion-card__body">
              <div class="form-grid">
                <el-form-item label="模型 ID" prop="id" class="form-grid__wide">
                  <el-input v-model="form.id" placeholder="例如 gpt-4.1" />
                  <p v-if="existingModel" class="field-hint field-hint--warning">
                    该 ID 已存在（{{ existingModel.name }}），保存后将更新原模型
                  </p>
                  <p v-else class="field-hint">客户端调用时使用的唯一标识，名称与分类会自动填充</p>
                </el-form-item>
                <el-form-item label="模型名称" prop="name">
                  <el-input v-model="form.name" placeholder="GPT-4.1" @input="nameDirty = true" />
                </el-form-item>
                <el-form-item label="状态" class="status-field">
                  <el-switch
                    v-model="form.enabled"
                    inline-prompt
                  />
                </el-form-item>
                <el-form-item label="上下文窗口" prop="contextWindow" class="form-grid__wide">
                  <ContextWindowField v-model="form.contextWindow" />
                </el-form-item>
              </div>

              <div class="accordion-card__actions">
                <el-button text type="primary" :icon="ArrowRight" @click="setSection('advanced')">
                  高级设置（可选）
                </el-button>
              </div>
            </div>
          </el-collapse-transition>
        </section>

        <section class="accordion-card" :class="{ 'is-open': openSection === 'advanced' }">
          <header
            class="accordion-card__head"
            role="button"
            tabindex="0"
            :aria-expanded="openSection === 'advanced'"
            @click="setSection('advanced')"
            @keydown.enter.space.prevent="setSection('advanced')"
          >
            <span class="step-badge">3</span>
            <div class="accordion-card__title">
              <h3>高级设置</h3>
              <p>上游映射、图标、Endpoint 与描述（可选）</p>
            </div>
            <el-icon class="toggle-arrow"><ArrowDown /></el-icon>
          </header>

          <el-collapse-transition>
            <div v-show="openSection === 'advanced'" class="accordion-card__body">
              <div class="form-grid">
                <el-form-item label="目标模型 ID" class="form-grid__wide">
                  <el-input v-model="form.targetModelId" :placeholder="form.id || '上游模型 ID'" />
                  <p class="field-hint">
                    留空则与模型 ID 相同；仅当对外 ID 需要映射到上游真实模型时填写
                  </p>
                </el-form-item>
                <el-form-item label="模型图标" class="form-grid__wide">
                  <ModelIconField v-model:icon="form.icon" :provider="form.provider" />
                </el-form-item>
                <el-form-item label="Endpoint" prop="endpoint" class="form-grid__wide">
                  <el-input v-model="form.endpoint" placeholder="/v1/chat/completions" />
                  <p class="field-hint">
                    OpenAI Chat Completions 兼容端点，默认为 /v1/chat/completions
                  </p>
                </el-form-item>
                <el-form-item label="描述" class="form-grid__wide">
                  <el-input
                    v-model="form.description"
                    type="textarea"
                    :rows="3"
                    maxlength="1024"
                    show-word-limit
                    placeholder="可选，向调用方展示在模型列表中"
                  />
                </el-form-item>
              </div>

              <div class="accordion-card__actions">
                <el-button type="primary" :icon="Check" :loading="saving" @click="submitForm">
                  保存模型
                </el-button>
              </div>
            </div>
          </el-collapse-transition>
        </section>
      </div>
    </el-form>
  </PageSection>
</template>

<style scoped lang="scss">
  .model-create-form {
    border-top: 1px solid var(--kp-divider);
  }

  .accordion-stack {
    display: flex;
    flex-direction: column;
    max-width: 840px;
    margin: 0 auto;
    padding: 20px 0 12px;
    gap: 14px;
  }

  .accordion-card {
    border: 1px solid var(--kp-divider);
    border-radius: 12px;
    background: rgba(255, 255, 255, 0.55);
    transition:
      border-color 0.18s ease,
      box-shadow 0.18s ease,
      background 0.18s ease;

    &.is-open {
      border-color: rgba(91, 141, 239, 0.45);
      background: rgba(255, 255, 255, 0.88);
      box-shadow: 0 8px 22px rgba(31, 45, 80, 0.09);

      .toggle-arrow {
        transform: rotate(180deg);
      }
    }

    &__head {
      display: flex;
      align-items: center;
      min-width: 0;
      gap: 14px;
      padding: 18px 22px;
      border-radius: 12px;
      cursor: pointer;
      user-select: none;
      transition: background 0.16s ease;

      &:hover {
        background: rgba(255, 255, 255, 0.6);
      }

      &:focus-visible {
        outline: 2px solid var(--el-color-primary);
        outline-offset: 2px;
      }
    }

    &__title {
      min-width: 0;
      flex: 1;

      h3 {
        margin: 0;
        font-size: 15.5px;
        font-weight: 700;
        letter-spacing: 0;
      }

      p {
        overflow: hidden;
        margin: 3px 0 0;
        color: var(--kp-text-muted);
        font-size: 12px;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }

    &__body {
      margin: 0 22px;
      padding: 20px 0 24px;
      border-top: 1px solid var(--kp-divider);
    }

    &__actions {
      display: flex;
      justify-content: flex-end;
      margin-top: 6px;
    }
  }

  .step-badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    flex: 0 0 30px;
    border-radius: 50%;
    background: #eef1f7;
    color: var(--kp-text-muted);
    font-size: 12.5px;
    font-weight: 700;
    transition:
      background 0.18s ease,
      color 0.18s ease;

    .el-icon {
      font-size: 14px;
    }

    .is-open & {
      background: var(--el-color-primary);
      color: #fff;
    }

    .is-done:not(.is-open) & {
      background: rgba(53, 164, 111, 0.15);
      color: #35a46f;
    }
  }

  .toggle-arrow {
    flex: 0 0 auto;
    color: var(--kp-text-muted);
    font-size: 13px;
    transition: transform 0.2s ease;
  }

  .provider-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
    margin-bottom: 18px;
  }

  .provider-choice {
    display: flex;
    align-items: center;
    min-width: 0;
    min-height: 64px;
    gap: 11px;
    padding: 10px 12px;
    border: 1px solid var(--kp-divider);
    border-radius: 8px;
    background: rgba(255, 255, 255, 0.52);
    color: var(--kp-text);
    cursor: pointer;
    text-align: left;
    transition:
      border-color 0.16s ease,
      background 0.16s ease,
      box-shadow 0.16s ease;

    &:hover {
      border-color: rgba(64, 119, 229, 0.42);
      background: rgba(255, 255, 255, 0.82);
    }

    &.is-active {
      border-color: var(--el-color-primary);
      background: rgba(235, 243, 255, 0.72);
      box-shadow: 0 3px 12px rgba(41, 89, 177, 0.09);
    }

    > span:nth-child(2) {
      display: flex;
      min-width: 0;
      flex: 1;
      flex-direction: column;
    }

    strong,
    small {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    strong {
      font-size: 13px;
    }

    small {
      margin-top: 2px;
      color: var(--kp-text-muted);
      font-size: 11px;
    }

    > .el-icon:last-child {
      flex: 0 0 auto;
      color: var(--el-color-primary);
    }
  }

  .add-provider-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    flex: 0 0 34px;
    border: 1px dashed #aeb8ca;
    border-radius: 7px;
    background: #f6f8fb;
    color: #52617a;
  }

  .inline-field {
    width: min(420px, 100%);
  }

  .vendor-color-row {
    display: flex;
    align-items: center;
    gap: 12px;
    min-height: 32px;

    &__hint {
      color: var(--kp-text-muted);
      font-size: 12px;
    }
  }

  .family-area {
    padding-top: 2px;

    > label {
      display: block;
      margin-bottom: 8px;
      color: var(--el-text-color-regular);
      font-size: 14px;
      line-height: 22px;
    }
  }

  .family-options {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;

    button {
      display: inline-flex;
      align-items: center;
      min-height: 34px;
      gap: 5px;
      padding: 6px 13px;
      border: 1px solid var(--kp-divider);
      border-radius: 6px;
      background: #fff;
      color: var(--kp-text-secondary);
      cursor: pointer;
      font-size: 12.5px;

      &:hover,
      &.is-active {
        border-color: var(--el-color-primary);
        color: var(--el-color-primary);
      }

      &.is-active {
        background: rgba(235, 243, 255, 0.72);
        font-weight: 600;
      }
    }
  }

  .family-input {
    margin-top: 12px;
    margin-bottom: 0;
  }

  .form-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    column-gap: 20px;

    &__wide {
      grid-column: 1 / -1;
    }
  }

  .status-field {
    :deep(.el-form-item__content) {
      min-height: 32px;
      align-items: center;
    }
  }

  .field-hint {
    width: 100%;
    margin: 4px 0 0;
    color: var(--kp-text-muted);
    font-size: 11.5px;
    line-height: 1.5;

    &--warning {
      color: var(--el-color-warning);
    }
  }

  @media (max-width: 560px) {
    .provider-grid,
    .form-grid {
      grid-template-columns: minmax(0, 1fr);
    }

    .form-grid__wide {
      grid-column: auto;
    }

    .provider-choice {
      min-height: 58px;
    }

    .accordion-card__head {
      padding: 16px 18px;
    }

    .accordion-card__body {
      margin: 0 18px;
    }
  }
</style>
