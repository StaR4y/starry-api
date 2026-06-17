<script setup lang="ts">
  import { ref, reactive, onMounted, computed } from 'vue';
  import { ElMessage, ElCollapseTransition } from 'element-plus';
  import { accountsApi } from '@/api';
  import { ApiError } from '@/utils/http';
  import type { DisabledAccountScheduledTestSettings, TestAccountsResult } from '@/types/api';

  const emit = defineEmits<{
    (e: 'test-completed', result: TestAccountsResult): void;
  }>();

  // Loading States
  const loading = ref(false);
  const saving = ref(false);
  const running = ref(false);

  // Expandable State
  const isExpanded = ref(false);

  // Default values generator
  const defaultSettings = (): DisabledAccountScheduledTestSettings => ({
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
  });

  const form = reactive<DisabledAccountScheduledTestSettings>(defaultSettings());

  const providerOptions = [
    { label: 'Kiro / BuilderID', value: 'BuilderID' },
    { label: 'OpenAI', value: 'OpenAI' },
    { label: 'OpenAI-compatible', value: 'OpenAI-compatible' },
    { label: 'Anthropic', value: 'Anthropic' },
    { label: 'Google / Gemini', value: 'Google' },
    { label: 'Codex', value: 'codex' },
  ];

  // Load Settings
  async function loadSettings() {
    loading.value = true;
    try {
      const settings = await accountsApi.settings();
      const rawSettings = settings.disabledAccountScheduledTest;
      const normalized = {
        ...defaultSettings(),
        ...(rawSettings ?? {}),
        prompt: rawSettings?.prompt ?? '',
        providers: Array.isArray(rawSettings?.providers) ? [...rawSettings.providers] : [],
      };
      Object.assign(form, normalized);
    } catch (err) {
      if (err instanceof ApiError) {
        ElMessage.warning(`复检策略加载失败：${err.message}`);
      }
    } finally {
      loading.value = false;
    }
  }

  // Save Settings
  async function saveSettings(showMessage = true) {
    saving.value = true;
    try {
      const payload = {
        ...form,
        prompt: form.prompt?.trim() || null,
        providers: [...form.providers],
      };
      const settings = await accountsApi.updateSettings({
        disabledAccountScheduledTest: payload,
      });
      const rawSettings = settings.disabledAccountScheduledTest;
      const normalized = {
        ...defaultSettings(),
        ...(rawSettings ?? {}),
        prompt: rawSettings?.prompt ?? '',
        providers: Array.isArray(rawSettings?.providers) ? [...rawSettings.providers] : [],
      };
      Object.assign(form, normalized);
      if (showMessage) {
        ElMessage.success('复检策略已成功保存');
      }
      return true;
    } catch (err) {
      if (err instanceof ApiError) {
        ElMessage.error(err.message);
      }
      return false;
    } finally {
      saving.value = false;
    }
  }

  // Handle Switch Toggle from Header (Auto-save)
  async function handleEnabledChange(value: boolean | number | string) {
    const success = await saveSettings(false);
    if (success) {
      ElMessage.success(value ? '已开启定时复检' : '已关闭定时复检');
    } else {
      // Revert if failed
      form.enabled = !value;
    }
  }

  // Immediate Execution
  async function runNow() {
    running.value = true;
    try {
      const saved = await saveSettings(false);
      if (!saved) return;
      const result = await accountsApi.runDisabledScheduledTest();
      emit('test-completed', result);
    } catch (err) {
      if (err instanceof ApiError) {
        ElMessage.error(err.message);
      }
    } finally {
      running.value = false;
    }
  }

  // Auto-expand on enter/mount after 300ms transition delay for rich visual effect
  onMounted(async () => {
    await loadSettings();
    setTimeout(() => {
      isExpanded.value = true;
    }, 300);
  });

  // Summary helper text
  const scheduleSummary = computed(() => {
    if (!form.enabled) return '已关闭定时复检策略';
    const modeText =
      form.scheduleMode === 'interval'
        ? `每 ${form.intervalMinutes} 分钟`
        : `Cron: "${form.cronExpression}"`;
    const filterText =
      form.providers.length > 0 ? `过滤: ${form.providers.join(', ')}` : '全部平台';
    return `运行中 (${modeText} • ${filterText})`;
  });

  function toggleExpand() {
    isExpanded.value = !isExpanded.value;
  }
</script>

<template>
  <div
    v-loading="loading"
    class="disabled-schedule-card glass-panel"
    :class="{ 'is-active': form.enabled, 'is-expanded': isExpanded }"
  >
    <!-- Collapsible Header -->
    <div class="card-header" @click="toggleExpand">
      <div class="header-left">
        <div class="header-icon-container">
          <svg
            class="header-icon"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4"
            />
          </svg>
        </div>
        <div class="title-section">
          <div class="title-row">
            <span class="title-text">停用账号定期复检</span>
            <span class="status-badge" :class="{ 'is-enabled': form.enabled }">
              <span class="status-dot" />
              {{ form.enabled ? '已开启' : '已关闭' }}
            </span>
          </div>
          <div class="desc-text">
            {{ scheduleSummary }}
          </div>
        </div>
      </div>

      <div class="header-right" @click.stop>
        <div class="switch-container">
          <el-switch v-model="form.enabled" inline-prompt @change="handleEnabledChange" />
        </div>
        <div class="expand-toggle-btn" @click="toggleExpand">
          <svg
            class="chevron-icon"
            :class="{ rotated: isExpanded }"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2.5"
          >
            <path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" />
          </svg>
        </div>
      </div>
    </div>

    <!-- Collapsible Body -->
    <el-collapse-transition>
      <div v-show="isExpanded" class="card-body">
        <div class="divider" />
        <el-form class="panel-form" label-position="top" size="default">
          <div class="form-grid">
            <!-- Group 1: Time & Schedule -->
            <div class="form-group-section">
              <div class="group-header">
                <svg
                  class="group-icon"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                >
                  <circle cx="12" cy="12" r="10" />
                  <polyline points="12 6 12 12 16 14" />
                </svg>
                <span>调度触发</span>
              </div>
              <div class="group-content">
                <el-form-item label="调度方式">
                  <el-segmented
                    v-model="form.scheduleMode"
                    :options="[
                      { label: '固定间隔', value: 'interval' },
                      { label: 'Cron', value: 'cron' },
                    ]"
                    style="width: 100%"
                  />
                </el-form-item>

                <el-form-item v-if="form.scheduleMode !== 'cron'" label="间隔时间">
                  <div class="input-with-unit">
                    <el-input-number
                      v-model="form.intervalMinutes"
                      :min="1"
                      :max="10080"
                      controls-position="right"
                      style="width: 100%"
                    />
                    <span class="unit-label">分钟</span>
                  </div>
                </el-form-item>

                <el-form-item v-else label="Cron 表达式">
                  <el-input v-model="form.cronExpression" placeholder="0 */30 * * * *" />
                </el-form-item>

                <el-form-item label="时区">
                  <el-input v-model="form.timezone" placeholder="Asia/Shanghai" />
                </el-form-item>
              </div>
            </div>

            <!-- Group 2: Concurrency & Restrictions -->
            <div class="form-group-section">
              <div class="group-header">
                <svg
                  class="group-icon"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                >
                  <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
                  <line x1="8" y1="21" x2="16" y2="21" />
                  <line x1="12" y1="17" x2="12" y2="21" />
                </svg>
                <span>运行限制</span>
              </div>
              <div class="group-content">
                <el-form-item label="测试模型">
                  <el-input v-model="form.model" placeholder="auto" />
                </el-form-item>

                <div class="grid-2-cols">
                  <el-form-item label="最大并发">
                    <el-input-number
                      v-model="form.maxConcurrency"
                      :min="1"
                      :max="16"
                      controls-position="right"
                      style="width: 100%"
                    />
                  </el-form-item>
                  <el-form-item label="定时单批额度">
                    <el-input-number
                      v-model="form.maxAccountsPerRun"
                      :min="1"
                      :max="1000"
                      controls-position="right"
                      style="width: 100%"
                    />
                  </el-form-item>
                </div>

                <el-form-item label="启动延迟">
                  <div class="input-with-unit">
                    <el-input-number
                      v-model="form.initialDelayMinutes"
                      :min="0"
                      :max="1440"
                      controls-position="right"
                      style="width: 100%"
                    />
                    <span class="unit-label">分钟</span>
                  </div>
                </el-form-item>

                <el-form-item label="停用过滤阀值">
                  <template #label>
                    <span class="custom-label">
                      停用多久后测试
                      <el-tooltip
                        content="账号变更状态后需要等待多少分钟才进行复检，避免频繁测试"
                        placement="top"
                      >
                        <span class="info-icon">?</span>
                      </el-tooltip>
                    </span>
                  </template>
                  <div class="input-with-unit">
                    <el-input-number
                      v-model="form.minAgeMinutes"
                      :min="0"
                      :max="43200"
                      controls-position="right"
                      style="width: 100%"
                    />
                    <span class="unit-label">分钟</span>
                  </div>
                </el-form-item>
              </div>
            </div>

            <!-- Group 3: Scope & Recovery -->
            <div class="form-group-section">
              <div class="group-header">
                <svg
                  class="group-icon"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                >
                  <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
                  <polyline points="22 4 12 14.01 9 11.01" />
                </svg>
                <span>范围与策略</span>
              </div>
              <div class="group-content checks-container">
                <div class="check-card" :class="{ 'is-checked': form.includeDisabled }">
                  <el-checkbox v-model="form.includeDisabled">
                    <div class="check-info">
                      <span class="check-title">包含手动停用</span>
                      <span class="check-desc">检测因管理员手动设置停用的账号</span>
                    </div>
                  </el-checkbox>
                </div>

                <div class="check-card" :class="{ 'is-checked': form.includeSuspended }">
                  <el-checkbox v-model="form.includeSuspended">
                    <div class="check-info">
                      <span class="check-title">包含挂起账号</span>
                      <span class="check-desc">检测被系统自动挂起（Suspended）的账号</span>
                    </div>
                  </el-checkbox>
                </div>

                <div class="check-card" :class="{ 'is-checked': form.includeQuotaExhausted }">
                  <el-checkbox v-model="form.includeQuotaExhausted">
                    <div class="check-info">
                      <span class="check-title">包含配额耗尽</span>
                      <span class="check-desc">包含检测原配额耗尽异常状态的账号</span>
                    </div>
                  </el-checkbox>
                </div>

                <div class="check-card highlight" :class="{ 'is-checked': form.updateStatus }">
                  <el-checkbox v-model="form.updateStatus">
                    <div class="check-info">
                      <span class="check-title text-primary">成功后恢复账号</span>
                      <span class="check-desc">测试成功后，自动将账号状态变更为正常</span>
                    </div>
                  </el-checkbox>
                </div>

                <div
                  class="check-card highlight"
                  :class="{ 'is-checked': form.autoRunWhenNoAvailableAccounts }"
                >
                  <el-checkbox v-model="form.autoRunWhenNoAvailableAccounts">
                    <div class="check-info">
                      <span class="check-title text-primary">账号不可用时自动复查</span>
                      <span class="check-desc">当前请求没有可用账号时，自动复查一次对应平台账号并重新选择</span>
                    </div>
                  </el-checkbox>
                </div>
              </div>
            </div>
          </div>

          <!-- Filters & Advanced Inputs (Full Width) -->
          <div class="form-fullwidth-section">
            <el-form-item label="平台过滤">
              <el-select
                v-model="form.providers"
                multiple
                filterable
                allow-create
                default-first-option
                placeholder="留空表示全部平台"
                style="width: 100%"
              >
                <el-option
                  v-for="item in providerOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>

            <el-form-item label="自定义测试提示词">
              <el-input
                v-model="form.prompt"
                type="textarea"
                :rows="2"
                placeholder="留空使用系统默认测试提示词 (e.g. Say hello to verify connection)"
              />
            </el-form-item>
          </div>

          <!-- Action Buttons -->
          <div class="form-actions">
            <el-button :loading="running" class="action-btn run-btn" @click="runNow">
              <svg
                v-if="!running"
                class="btn-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2.5"
              >
                <polygon points="5 3 19 12 5 21 5 3" />
              </svg>
              立即测试全部账号
            </el-button>

            <el-button
              type="primary"
              :loading="saving"
              class="action-btn save-btn"
              @click="saveSettings(true)"
            >
              <svg
                v-if="!saving"
                class="btn-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2.5"
              >
                <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z" />
                <polyline points="17 21 17 13 7 13 7 21" />
                <polyline points="7 3 7 8 15 8" />
              </svg>
              保存策略配置
            </el-button>
          </div>
        </el-form>
      </div>
    </el-collapse-transition>
  </div>
</template>

<style scoped lang="scss">
  .disabled-schedule-card {
    transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
    margin-bottom: 20px;
    overflow: hidden;
    position: relative;
    border: 1px solid rgba(255, 255, 255, 0.5);

    &.is-active {
      border-color: rgba(91, 141, 239, 0.25);
      box-shadow:
        0 8px 30px rgba(91, 141, 239, 0.06),
        var(--kp-shadow);

      .header-icon-container {
        background: rgba(91, 141, 239, 0.12);
        color: var(--kp-primary);
      }
    }

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
    background: rgba(15, 23, 42, 0.05);
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--kp-text-secondary);
    transition: all 0.3s ease;
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

  .status-badge {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: 11px;
    font-weight: 700;
    padding: 2px 8px;
    border-radius: 100px;
    background: rgba(15, 23, 42, 0.06);
    color: var(--kp-text-secondary);
    transition: all 0.3s ease;

    .status-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background-color: var(--kp-text-muted);
      transition: all 0.3s ease;
    }

    &.is-enabled {
      background: rgba(54, 192, 138, 0.12);
      color: #278c65;

      .status-dot {
        background-color: var(--kp-success);
        box-shadow: 0 0 6px var(--kp-success);
        animation: pulse 2s infinite;
      }
    }
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

  .switch-container {
    display: flex;
    align-items: center;
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
      grid-template-columns: repeat(2, 1fr);
    }

    @media (max-width: 768px) {
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
    gap: 0px; // Handled by el-form-item margin

    :deep(.el-form-item) {
      margin-bottom: 12px;

      .el-form-item__label {
        font-weight: 600;
        color: var(--kp-text-secondary);
        font-size: 12.5px;
        padding-bottom: 4px;
      }
    }
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

  .grid-2-cols {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px;
  }

  .custom-label {
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }

  .info-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 14px;
    height: 14px;
    font-size: 10px;
    border-radius: 50%;
    background: rgba(15, 23, 42, 0.08);
    color: var(--kp-text-secondary);
    cursor: help;
    font-weight: 700;
    transition: all 0.2s ease;

    &:hover {
      background: var(--kp-primary);
      color: white;
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

  .check-desc {
    font-size: 11px;
    color: var(--kp-text-muted);
    line-height: 1.3;
  }

  .form-fullwidth-section {
    margin-top: 16px;
    background: rgba(255, 255, 255, 0.25);
    border-radius: var(--el-border-radius-base);
    padding: 16px;
    border: 1px solid rgba(255, 255, 255, 0.4);
    display: flex;
    flex-direction: column;
    gap: 4px;

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

  .form-actions {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    margin-top: 20px;
    flex-wrap: wrap;
  }

  .action-btn {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    font-weight: 700;
    padding: 10px 20px;
    border-radius: var(--el-border-radius-base);
    transition: all 0.2s ease;
    height: 40px;

    .btn-icon {
      width: 14px;
      height: 14px;
    }
  }

  @keyframes pulse {
    0% {
      box-shadow: 0 0 0 0 rgba(54, 192, 138, 0.4);
    }
    70% {
      box-shadow: 0 0 0 6px rgba(54, 192, 138, 0);
    }
    100% {
      box-shadow: 0 0 0 0 rgba(54, 192, 138, 0);
    }
  }
</style>
