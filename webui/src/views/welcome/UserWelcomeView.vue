<script setup lang="ts">
  /**
   * 普通用户欢迎面板。
   *
   * 展示当前账号余额与用户可访问的 API Key 使用概览，作为普通用户登录后的默认首页。
   */
  import { computed, onMounted, ref } from 'vue';
  import { Refresh } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { apiKeysApi, logsApi } from '@/api';
  import { useAuthStore } from '@/stores/auth';
  import type { ApiKey, ModelUsageSummary, UsageSummary } from '@/types/api';
  import { formatDateTime, formatNumber } from '@/utils/format';

  const auth = useAuthStore();
  const loading = ref(false);
  const apiKeys = ref<ApiKey[]>([]);
  const usageSummary = ref<UsageSummary | null>(null);
  const chartColors = ['#2563eb', '#10b981', '#f59e0b', '#ef5b6b', '#06b6d4'];

  const displayName = computed(() => auth.user?.displayName || auth.user?.username || '用户');
  const balance = computed(() => auth.user?.balance ?? 0);
  const activeKeys = computed(() => apiKeys.value.filter((key) => key.enabled).length);
  const totalRequests = computed(() =>
    apiKeys.value.reduce((sum, key) => sum + (key.totalRequests ?? 0), 0),
  );
  const totalCredits = computed(() =>
    apiKeys.value.reduce((sum, key) => sum + (key.totalCredits ?? 0), 0),
  );
  const apiKeyTotalTokens = computed(() =>
    apiKeys.value.reduce(
      (sum, key) => sum + (key.totalInputTokens ?? 0) + (key.totalOutputTokens ?? 0),
      0,
    ),
  );
  const totalTokens = computed(() => usageSummary.value?.totalTokens ?? apiKeyTotalTokens.value);
  const modelUsage = computed(() => usageSummary.value?.models?.slice(0, 8) ?? []);
  const maxModelTokens = computed(() =>
    Math.max(0, ...modelUsage.value.map((model) => model.totalTokens)),
  );
  const latestUsedAt = computed(() => {
    const latest = apiKeys.value
      .map((key) => key.lastUsedAt)
      .filter((value): value is string => Boolean(value))
      .sort((a, b) => new Date(b).getTime() - new Date(a).getTime())[0];
    return latest ?? null;
  });

  function colorFor(index: number) {
    return chartColors[index % chartColors.length];
  }

  function modelName(model: ModelUsageSummary) {
    return model.model?.trim() || '未指定模型';
  }

  function barWidth(value: number, maxValue: number) {
    if (maxValue <= 0 || value <= 0) return '0%';
    return `${Math.max((value / maxValue) * 100, 4)}%`;
  }

  async function load() {
    loading.value = true;
    try {
      const [keys, summary] = await Promise.allSettled([
        apiKeysApi.list(),
        logsApi.myUsageSummary(),
      ]);
      await auth.refreshCurrentUser();
      if (keys.status === 'fulfilled') apiKeys.value = keys.value;
      if (summary.status === 'fulfilled') usageSummary.value = summary.value;
    } finally {
      loading.value = false;
    }
  }

  onMounted(load);
</script>

<template>
  <div class="welcome">
    <PageSection :title="`欢迎，${displayName}`" subtitle="这里汇总你的账号余额和调用使用情况">
      <template #actions>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </template>

      <div v-loading="loading" class="welcome__content">
        <!-- 1. Stats Summary Panel (Unified metrics style with Admin Account List) -->
        <div class="metrics-panel glass-panel">
          <div class="metric-item">
            <div class="metric-item__header">
              <span class="metric-dot dot-balance" />
              <span class="metric-label">当前余额</span>
            </div>
            <div class="metric-value">{{ formatNumber(balance) }}</div>
            <div class="metric-subtext">
              最近使用：{{ formatDateTime(latestUsedAt) || '无使用记录' }}
            </div>
          </div>

          <div class="metric-divider" />

          <div class="metric-item">
            <div class="metric-item__header">
              <span class="metric-dot dot-keys" />
              <span class="metric-label">API Key 密钥</span>
            </div>
            <div class="metric-value text-success">
              {{ formatNumber(activeKeys) }}
              <span class="metric-value__sub">/ {{ formatNumber(apiKeys.length) }}</span>
            </div>
            <div class="metric-subtext">
              {{ activeKeys > 0 ? '服务正常运行中' : '暂无启用密钥' }}
            </div>
          </div>

          <div class="metric-divider" />

          <div class="metric-item">
            <div class="metric-item__header">
              <span class="metric-dot dot-requests" />
              <span class="metric-label">累计调用</span>
            </div>
            <div class="metric-value">{{ formatNumber(totalRequests) }}</div>
            <div class="metric-subtext">次 API 请求</div>
          </div>

          <div class="metric-divider" />

          <div class="metric-item">
            <div class="metric-item__header">
              <span class="metric-dot dot-tokens" />
              <span class="metric-label">已用 Token / 累计消耗</span>
            </div>
            <div class="metric-value">
              {{ formatNumber(totalTokens) }}
              <span class="metric-value__sub">/ {{ formatNumber(totalCredits) }}</span>
            </div>
            <div class="metric-subtext">Tokens / 累计消耗额度</div>
          </div>
        </div>

        <!-- 2. Model Usage Card -->
        <section class="welcome__model-card glass-panel">
          <div class="welcome__model-header">
            <div>
              <h3 class="welcome__model-title">
                <span class="model-title-dot" />
                模型使用情况
              </h3>
              <p class="text-muted">按你的 API Key 请求日志聚合模型调用和 token 消耗</p>
            </div>
            <div class="welcome__model-header-total text-secondary">
              总 Token：<strong class="mono">{{ formatNumber(totalTokens) }}</strong>
            </div>
          </div>

          <div v-if="modelUsage.length" class="welcome__model-list">
            <div
              v-for="(model, index) in modelUsage"
              :key="model.model || index"
              class="welcome__model-row"
            >
              <div class="welcome__model-row-head">
                <div class="welcome__model-name">
                  <i :style="{ backgroundColor: colorFor(index) }" />
                  <strong :title="modelName(model)" class="mono">{{ modelName(model) }}</strong>
                </div>
                <span class="mono">{{ formatNumber(model.totalTokens) }} token</span>
              </div>
              <div class="welcome__model-track">
                <span
                  :style="{
                    width: barWidth(model.totalTokens, maxModelTokens),
                    backgroundColor: colorFor(index),
                  }"
                />
              </div>
              <div class="welcome__model-meta text-muted">
                <span>
                  <strong>{{ formatNumber(model.requestCount) }}</strong> 次调用
                </span>
                <span>最近 {{ formatDateTime(model.lastUsedAt) }}</span>
              </div>
            </div>
          </div>
          <div v-else class="welcome__empty text-muted">
            <svg
              xmlns="http://www.w3.org/2000/svg"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
              style="width: 36px; height: 36px; opacity: 0.4; margin-bottom: 8px"
            >
              <path
                d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"
              />
              <polyline points="3.27 6.96 12 12.01 20.73 6.96" />
              <line x1="12" y1="22.08" x2="12" y2="12" />
            </svg>
            <div>暂无模型使用数据</div>
          </div>
        </section>
      </div>
    </PageSection>
  </div>
</template>

<style scoped lang="scss">
  .welcome {
    &__content {
      display: flex;
      flex-direction: column;
      gap: 18px;
      min-height: 260px;
    }

    /* Unified Metrics Panel with Admin Account List */
    .metrics-panel {
      display: flex;
      align-items: center;
      padding: 20px 28px;
      margin-bottom: 6px;
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

          &.dot-balance {
            background-color: var(--kp-primary);
            box-shadow: 0 0 6px rgba(91, 141, 239, 0.6);
          }
          &.dot-keys {
            background-color: #36c08a;
            box-shadow: 0 0 6px rgba(54, 192, 138, 0.6);
          }
          &.dot-requests {
            background-color: #b387ff;
            box-shadow: 0 0 6px rgba(179, 135, 255, 0.6);
          }
          &.dot-tokens {
            background-color: #f0a84d;
            box-shadow: 0 0 6px rgba(240, 168, 77, 0.6);
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

    &__model-card {
      padding: 22px 28px;
      // Border & background are handled by .glass-panel class
    }

    &__model-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 16px;
      margin-bottom: 20px;

      .welcome__model-title {
        display: flex;
        align-items: center;
        margin: 0;
        font-size: 16px;
        font-weight: 700;
        letter-spacing: 0;
        color: var(--kp-text);

        .model-title-dot {
          display: inline-block;
          width: 4px;
          height: 15px;
          border-radius: 99px;
          background: linear-gradient(to bottom, var(--kp-primary), var(--kp-accent));
          margin-right: 8px;
        }
      }

      p {
        margin: 4px 0 0;
        font-size: 12px;
      }

      .welcome__model-header-total {
        font-size: 13px;
        white-space: nowrap;

        strong {
          font-size: 16px;
          font-weight: 800;
          color: var(--kp-primary);
        }
      }
    }

    &__model-list {
      display: flex;
      flex-direction: column;
    }

    &__model-row {
      min-width: 0;
      padding: 14px 16px;
      border-radius: 10px;
      background: rgba(255, 255, 255, 0.35);
      border: 1px solid rgba(15, 23, 42, 0.03);
      transition: all 0.25s cubic-bezier(0.25, 0.8, 0.25, 1);
      margin-bottom: 10px;

      &:hover {
        background: rgba(255, 255, 255, 0.65);
        border-color: rgba(91, 141, 239, 0.15);
        transform: translateY(-1.5px);
        box-shadow: 0 4px 12px rgba(31, 45, 80, 0.05);
      }

      &:last-child {
        margin-bottom: 0;
      }
    }

    &__model-row-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 6px;

      > span {
        color: var(--kp-text);
        font-size: 13px;
        font-weight: 700;
        white-space: nowrap;
      }
    }

    &__model-name {
      min-width: 0;
      display: inline-flex;
      align-items: center;
      gap: 8px;

      i {
        width: 8px;
        height: 8px;
        border-radius: 50%;
        flex: 0 0 auto;
        box-shadow: 0 0 4px rgba(15, 23, 42, 0.15);
      }

      strong {
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        font-size: 13.5px;
        font-weight: 600;
        letter-spacing: 0;
        color: var(--kp-text);
      }
    }

    &__model-track {
      height: 6px;
      border-radius: 999px;
      background: rgba(31, 42, 68, 0.05);
      overflow: hidden;
      margin: 8px 0;

      span {
        display: block;
        height: 100%;
        border-radius: inherit;
        transition: width 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
      }
    }

    &__model-meta {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      font-size: 12px;
      color: var(--kp-text-muted);

      span {
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;

        strong {
          color: var(--kp-text-secondary);
        }
      }
    }

    &__empty {
      min-height: 140px;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      text-align: center;
      font-size: 13.5px;
      color: var(--kp-text-muted);
      gap: 6px;
    }
  }

  @media (max-width: 1024px) {
    .welcome {
      .metrics-panel {
        padding: 16px 20px;

        .metric-divider {
          margin: 0 20px;
        }
      }
    }
  }

  @media (max-width: 768px) {
    .welcome {
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
  }

  @media (max-width: 900px) {
    .welcome {
      &__model-header {
        flex-direction: column;
        align-items: flex-start;
        gap: 12px;
      }

      &__model-meta {
        flex-direction: column;
        align-items: flex-start;
        gap: 4px;
      }
    }
  }
</style>
