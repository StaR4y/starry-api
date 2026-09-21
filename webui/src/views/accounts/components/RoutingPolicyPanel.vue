<script setup lang="ts">
  /**
   * 账号路由策略：账号选择、会话亲和与失败换号上限。
   */
  import { computed, onMounted, reactive, ref } from 'vue';
  import { ElMessage, ElCollapseTransition } from 'element-plus';
  import AppIcon from './AppIcon.vue';
  import { accountsApi } from '@/api';
  import { ApiError } from '@/utils/http';
  import type { AccountRoutingPolicySettings } from '@/types/api';

  const loading = ref(false);
  const saving = ref(false);
  const isExpanded = ref(true);

  const defaultSettings = (): AccountRoutingPolicySettings => ({
    isolateCodexPool: true,
    gptUsesCodexAccounts: false,
    bindSessionOnAdmission: false,
    bindSessionByApiKey: false,
    codexMaxRetryAccounts: 1,
    openAiMaxRetryAccounts: 3,
    deepSeekMaxRetryAccounts: 3,
    anthropicMaxRetryAccounts: 2,
    kiroMaxRetryAccounts: 2,
  });

  const form = reactive<AccountRoutingPolicySettings>(defaultSettings());

  const retryFields = [
    { key: 'codexMaxRetryAccounts', label: 'Codex' },
    { key: 'openAiMaxRetryAccounts', label: 'OpenAI' },
    { key: 'deepSeekMaxRetryAccounts', label: 'DeepSeek' },
    { key: 'anthropicMaxRetryAccounts', label: 'Anthropic' },
    { key: 'kiroMaxRetryAccounts', label: 'Kiro' },
  ] as const;

  const summary = computed(() => {
    const pool = form.isolateCodexPool
      ? 'Codex 与 OpenAI API Key 分开调度'
      : 'Codex 可回退到 OpenAI API Key';
    const gpt = form.gptUsesCodexAccounts ? 'GPT 请求使用 Codex 账号' : 'GPT 请求使用 OpenAI API Key';
    return `${pool} • ${gpt} • Codex 失败后最多尝试 ${form.codexMaxRetryAccounts} 个账号`;
  });

  async function loadSettings() {
    loading.value = true;
    try {
      const settings = await accountsApi.settings();
      Object.assign(form, defaultSettings(), settings.routingPolicy ?? {});
    } catch (err) {
      if (err instanceof ApiError) {
        ElMessage.warning(`路由策略加载失败：${err.message}`);
      }
    } finally {
      loading.value = false;
    }
  }

  async function saveSettings() {
    saving.value = true;
    try {
      const settings = await accountsApi.updateSettings({
        routingPolicy: { ...form },
      });
      Object.assign(form, defaultSettings(), settings.routingPolicy ?? {});
      ElMessage.success('路由策略已保存');
    } catch (err) {
      if (err instanceof ApiError) ElMessage.error(err.message);
    } finally {
      saving.value = false;
    }
  }

  function toggleExpand() {
    isExpanded.value = !isExpanded.value;
  }

  onMounted(loadSettings);
</script>

<template>
  <div
    v-loading="loading"
    class="routing-policy-card surface-card"
    :class="{ 'is-expanded': isExpanded }"
  >
    <div class="card-header" @click="toggleExpand">
      <div class="header-left">
        <div class="header-icon-container">
          <AppIcon name="split" class="header-icon" />
        </div>
        <div class="title-section">
          <div class="title-row">
            <span class="title-text">账号路由策略</span>
          </div>
          <div class="desc-text">{{ summary }}</div>
        </div>
      </div>
      <div class="header-right" @click.stop>
        <div class="expand-toggle-btn" @click="toggleExpand">
          <AppIcon name="chevron-down" class="chevron-icon" :class="{ rotated: isExpanded }" />
        </div>
      </div>
    </div>

    <el-collapse-transition>
      <div v-show="isExpanded" class="card-body">
        <div class="divider" />
        <el-form class="panel-form" label-position="top" size="default">
          <div class="form-grid">
            <div class="form-group-section">
              <div class="group-header">
                <AppIcon name="split" class="group-icon" />
                <span>账号选择</span>
              </div>
              <div class="group-content checks-container">
                <div class="check-card highlight" :class="{ 'is-checked': form.isolateCodexPool }">
                  <el-checkbox v-model="form.isolateCodexPool">
                    <div class="check-info">
                      <span class="check-title text-primary">分开调度 Codex 与 OpenAI API Key</span>
                      <span class="check-desc">Codex 请求只使用 Codex 账号；OpenAI API Key 请求不会选中 Codex 账号</span>
                    </div>
                  </el-checkbox>
                </div>
                <div class="check-card highlight" :class="{ 'is-checked': form.gptUsesCodexAccounts }">
                  <el-checkbox v-model="form.gptUsesCodexAccounts">
                    <div class="check-info">
                      <span class="check-title text-primary">GPT 请求使用 Codex 账号</span>
                      <span class="check-desc">不含 openai-compatible 与 DeepSeek。仅配置 Codex 账号时请保持开启</span>
                    </div>
                  </el-checkbox>
                </div>
              </div>
            </div>

            <div class="form-group-section">
              <div class="group-header">
                <AppIcon name="lock" class="group-icon" />
                <span>会话亲和</span>
              </div>
              <div class="group-content checks-container">
                <div class="check-card" :class="{ 'is-checked': form.bindSessionOnAdmission }">
                  <el-checkbox v-model="form.bindSessionOnAdmission">
                    <div class="check-info">
                      <span class="check-title">选中后保持会话亲和</span>
                      <span class="check-desc">账号准入成功后立即绑定，后续同一会话优先使用该账号</span>
                    </div>
                  </el-checkbox>
                </div>
                <div class="check-card" :class="{ 'is-checked': form.bindSessionByApiKey }">
                  <el-checkbox v-model="form.bindSessionByApiKey">
                    <div class="check-info">
                      <span class="check-title">无会话时按 API Key 保持亲和</span>
                      <span class="check-desc">没有会话标识时，同一 API Key 优先使用同一上游账号</span>
                    </div>
                  </el-checkbox>
                </div>
              </div>
            </div>

            <div class="form-group-section">
              <div class="group-header">
                <AppIcon name="refresh" class="group-icon" />
                <span>失败重试</span>
              </div>
              <div class="group-content">
                <div class="retry-grid">
                  <el-form-item v-for="field in retryFields" :key="field.key" :label="field.label">
                    <div class="input-with-unit">
                      <el-input-number
                        v-model="form[field.key]"
                        :min="1"
                        :max="5"
                        controls-position="right"
                        class="w-full"
                      />
                      <span class="unit-label">个</span>
                    </div>
                  </el-form-item>
                </div>
                <p class="field-hint">
                  表示本次请求失败后最多尝试几个账号。1 表示不再更换；账号不可用后的下一次请求仍可选择其他账号。
                </p>
              </div>
            </div>
          </div>

          <div class="form-actions">
            <el-button
              type="primary"
              :loading="saving"
              class="action-btn save-btn"
              @click="saveSettings"
            >
              <AppIcon v-if="!saving" name="save" class="btn-icon" />
              保存策略配置
            </el-button>
          </div>
        </el-form>
      </div>
    </el-collapse-transition>
  </div>
</template>

<style scoped lang="scss">
  .w-full {
    width: 100%;
  }

  .routing-policy-card {
    transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
    overflow: hidden;
    position: relative;
    border: 1px solid rgba(255, 255, 255, 0.5);

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
    background: rgba(91, 141, 239, 0.12);
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--kp-primary);
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

  .form-grid {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 24px;

    @media (max-width: 1024px) {
      grid-template-columns: 1fr;
      gap: 16px;
    }
  }

  .form-group-section {
    display: flex;
    flex-direction: column;
    gap: 14px;
    background: rgba(255, 255, 255, 0.25);
    border-radius: var(--el-border-radius-base);
    padding: 16px;
    border: 1px solid rgba(255, 255, 255, 0.4);
  }

  .group-header {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13.5px;
    font-weight: 700;
    color: var(--kp-text-secondary);
    border-bottom: 1px solid var(--kp-divider);
    padding-bottom: 8px;
    margin-bottom: 6px;
  }

  .group-icon {
    width: 16px;
    height: 16px;
    color: var(--kp-primary);
  }

  .group-content {
    display: flex;
    flex-direction: column;
    gap: 0;

    :deep(.el-form-item) {
      margin-bottom: 12px;

      &:last-child {
        margin-bottom: 0;
      }

      .el-form-item__label {
        font-weight: 600;
        color: var(--kp-text-secondary);
        font-size: 12.5px;
        padding-bottom: 4px;
      }
    }
  }

  .retry-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px 12px;
  }

  .input-with-unit {
    display: flex;
    align-items: center;
    width: 100%;
    gap: 8px;

    .unit-label {
      font-size: 12px;
      color: var(--kp-text-muted);
      font-weight: 600;
      white-space: nowrap;
    }
  }

  .checks-container {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding-top: 4px;
  }

  .check-card {
    border: 1px solid var(--kp-divider);
    border-radius: var(--kp-radius-sm);
    padding: 8px 12px;
    background: rgba(255, 255, 255, 0.3);
    transition: all 0.25s ease;
    display: flex;
    align-items: flex-start;

    :deep(.el-checkbox) {
      height: auto;
      align-items: flex-start;
      margin-right: 0;
      white-space: normal;
      width: 100%;

      .el-checkbox__input {
        margin-top: 3px;
      }

      .el-checkbox__label {
        padding-left: 8px;
        display: block;
        width: 100%;
      }
    }

    &:hover {
      background: rgba(255, 255, 255, 0.6);
      border-color: rgba(91, 141, 239, 0.2);
    }

    &.is-checked {
      background: rgba(91, 141, 239, 0.03);
      border-color: rgba(91, 141, 239, 0.25);
    }

    &.highlight.is-checked {
      background: rgba(54, 192, 138, 0.03);
      border-color: rgba(54, 192, 138, 0.25);
    }
  }

  .check-info {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .check-title {
    font-size: 13px;
    font-weight: 700;
    color: var(--kp-text);
    line-height: 1.3;

    &.text-primary {
      color: #278c65;
    }
  }

  .check-desc,
  .field-hint {
    font-size: 11px;
    color: var(--kp-text-muted);
    line-height: 1.35;
  }

  .field-hint {
    margin: 4px 0 0;
  }

  .form-actions {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    margin-top: 20px;
  }

  .action-btn {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    font-weight: 700;
    padding: 10px 20px;
    height: 40px;

    .btn-icon {
      width: 14px;
      height: 14px;
    }
  }
</style>
