<script setup lang="ts">
  import { t } from '@/i18n';
  /**
   * 账号列表「可用配额」列。
   *
   * 从 AccountListView.vue 原样抽出：
   * - Codex 账号：官方配额接口的 加载/错误/成功/未获取 4 态
   * - 非 Codex 账号：本地 quotaUsed / quotaLimit 进度条
   *
   * 刷新官方配额通过 refresh 事件回传父组件。
   */
  import { computed } from 'vue';
  import AppIcon from './AppIcon.vue';
  import { formatDateTime, formatNumber } from '@/utils/format';
  import type { Account, OpenAiQuotaResponse } from '@/types/api';
  import { isCodexAccount } from '../utils';

  interface OpenAiQuotaState {
    loading: boolean;
    error?: string;
    data?: OpenAiQuotaResponse;
  }

  const props = defineProps<{
    account: Account;
    quotaState?: OpenAiQuotaState;
  }>();

  const emit = defineEmits<{
    (e: 'refresh', accountId: string): void;
  }>();

  const isCodex = computed(() => isCodexAccount(props.account));

  function getQuotaInfo(row: Account) {
    const used = Math.max(0, row.quotaUsed || 0);
    const limit = row.quotaLimit;
    const hasLimit = limit !== null && limit !== undefined && limit > 0;

    if (!hasLimit) {
      return {
        hasLimit: false,
        used,
        percentage: 100,
        remaining: 0,
        status: 'success' as const,
      };
    }

    const remaining = Math.max(0, limit - used);
    const percentage = Math.round((remaining / limit) * 100);

    let status: 'success' | 'warning' | 'exception' = 'success';
    if (percentage <= 15) {
      status = 'exception';
    } else if (percentage <= 40) {
      status = 'warning';
    }

    return {
      hasLimit: true,
      used,
      limit,
      remaining,
      percentage,
      status,
    };
  }

  function getOpenAiQuotaInfo(data: OpenAiQuotaResponse | undefined) {
    if (!data) {
      return {
        hasData: false,
        percentage: 100,
        status: 'success' as const,
        get label() {
          return t('未知');
        },
        activeWindow: null,
      };
    }

    const fiveHour = data.fiveHour;
    const sevenDay = data.sevenDay;

    let activeWindow = fiveHour || sevenDay;
    if (fiveHour && sevenDay) {
      activeWindow =
        (fiveHour.remainingPercent ?? 100) < (sevenDay.remainingPercent ?? 100)
          ? fiveHour
          : sevenDay;
    }

    if (!activeWindow) {
      return {
        hasData: false,
        percentage: 100,
        status: 'success' as const,
        get label() {
          return t('无限制');
        },
        activeWindow: null,
      };
    }

    const percentage = Math.round(activeWindow.remainingPercent ?? 100);
    let status: 'success' | 'warning' | 'exception' = 'success';
    if (percentage <= 15) {
      status = 'exception';
    } else if (percentage <= 40) {
      status = 'warning';
    }

    return {
      hasData: true,
      percentage,
      status,
      label: activeWindow.label || (activeWindow.key === '5h' ? t('5小时') : t('7天')),
      activeWindow,
    };
  }
</script>

<template>
  <div v-if="isCodex" class="quota-cell is-codex">
    <template v-if="quotaState?.loading">
      <div class="quota-meta mono">
        <span class="quota-loading-text">
          <span class="quota-spinner" /> {{ t('正在获取官方配额...') }}
        </span>
      </div>
      <div class="quota-loading-bar" />
    </template>
    <template v-else-if="quotaState?.error">
      <div class="quota-meta mono">
        <el-tooltip :content="quotaState.error" placement="top" effect="dark">
          <span class="quota-error-text">{{ t('获取失败') }}</span>
        </el-tooltip>
        <el-button
          link
          type="primary"
          size="small"
          class="quota-refresh-btn"
          @click.stop="emit('refresh', account.accountId)"
        >
          <AppIcon name="refresh" class="icon-refresh icon-gap" width="10" height="10" />
          {{ t('重试') }}
        </el-button>
      </div>
      <el-progress
        v-if="getQuotaInfo(account).hasLimit"
        :percentage="getQuotaInfo(account).percentage"
        :status="getQuotaInfo(account).status"
        :stroke-width="6"
        :show-text="false"
        class="quota-progress"
      />
      <div v-else class="quota-unlimited-bar" />
    </template>
    <template v-else-if="quotaState?.data">
      <el-tooltip placement="top" effect="dark">
        <template #content>
          <div class="quota-tooltip-content mono">
            <div v-if="quotaState?.data?.fiveHour">
              <strong>{{ t('5小时窗口:') }}</strong> {{ t('已用') }}
              {{ quotaState?.data?.fiveHour?.usedPercent }}{{ t('%, 剩余') }}
              {{ quotaState?.data?.fiveHour?.remainingPercent }}%
              <span v-if="quotaState?.data?.fiveHour?.resetAt">
                {{ t('(重置:') }} {{ formatDateTime(quotaState?.data?.fiveHour?.resetAt) }})
              </span>
            </div>
            <div v-if="quotaState?.data?.sevenDay" class="quota-tooltip-window">
              <strong>{{ t('7天窗口:') }}</strong> {{ t('已用') }}
              {{ quotaState?.data?.sevenDay?.usedPercent }}{{ t('%, 剩余') }}
              {{ quotaState?.data?.sevenDay?.remainingPercent }}%
              <span v-if="quotaState?.data?.sevenDay?.resetAt">
                {{ t('(重置:') }} {{ formatDateTime(quotaState?.data?.sevenDay?.resetAt) }})
              </span>
            </div>
            <div class="quota-tooltip-source">
              {{ t('数据源:') }} {{ quotaState?.data?.source }}<br>
              {{ t('更新时间:') }} {{ formatDateTime(quotaState?.data?.checkedAt) }}
            </div>
          </div>
        </template>
        <div class="quota-wrapper-hoverable">
          <div class="quota-meta mono">
            <span
              class="quota-remaining"
              :class="'is-' + getOpenAiQuotaInfo(quotaState?.data).status"
            >
              {{ getOpenAiQuotaInfo(quotaState?.data).percentage }}{{ t('% 剩余 (')
              }}{{ getOpenAiQuotaInfo(quotaState?.data).label }})
            </span>
            <el-button
              link
              type="primary"
              size="small"
              class="quota-refresh-btn-mini"
              @click.stop="emit('refresh', account.accountId)"
            >
              <AppIcon name="refresh" class="icon-refresh" width="10" height="10" />
            </el-button>
          </div>
          <el-progress
            :percentage="getOpenAiQuotaInfo(quotaState?.data).percentage"
            :status="getOpenAiQuotaInfo(quotaState?.data).status"
            :stroke-width="6"
            :show-text="false"
            class="quota-progress"
          />
        </div>
      </el-tooltip>
    </template>
    <template v-else>
      <div class="quota-meta mono">
        <span class="quota-fallback-label">{{ t('未获取官方配额') }}</span>
        <el-button
          link
          type="primary"
          size="small"
          class="quota-refresh-btn"
          @click.stop="emit('refresh', account.accountId)"
        >
          <AppIcon name="refresh" class="icon-refresh icon-gap" width="10" height="10" />
          {{ t('获取') }}
        </el-button>
      </div>
      <el-progress
        v-if="getQuotaInfo(account).hasLimit"
        :percentage="getQuotaInfo(account).percentage"
        :status="getQuotaInfo(account).status"
        :stroke-width="6"
        :show-text="false"
        class="quota-progress"
      />
      <div v-else class="quota-unlimited-bar" />
    </template>
  </div>

  <div v-else class="quota-cell">
    <template v-if="getQuotaInfo(account).hasLimit">
      <div class="quota-meta mono">
        <span class="quota-remaining" :class="'is-' + getQuotaInfo(account).status">
          {{ formatNumber(getQuotaInfo(account).remaining) }} {{ t('剩余') }}
        </span>
        <span class="quota-divider">/</span>
        <span class="quota-limit">{{ formatNumber(getQuotaInfo(account).limit) }}</span>
      </div>
      <el-progress
        :percentage="getQuotaInfo(account).percentage"
        :status="getQuotaInfo(account).status"
        :stroke-width="6"
        :show-text="false"
        class="quota-progress"
      />
    </template>
    <template v-else>
      <div class="quota-meta mono">
        <span class="quota-used">{{ formatNumber(getQuotaInfo(account).used) }} {{ t('已用') }}</span>
        <span class="quota-divider">/</span>
        <span class="quota-limit-unlimited">{{ t('无限制') }}</span>
      </div>
      <div class="quota-unlimited-bar" />
    </template>
  </div>
</template>

<style scoped lang="scss">
  .quota-cell {
    display: flex;
    flex-direction: column;
    gap: 6px;
    width: 100%;
    padding: 2px 0;

    .quota-meta {
      display: flex;
      align-items: center;
      font-size: 11.5px;
      line-height: 1.2;
      color: var(--kp-text-secondary);

      .quota-remaining {
        font-weight: 600;

        &.is-success {
          color: var(--el-color-success);
        }
        &.is-warning {
          color: var(--el-color-warning);
        }
        &.is-exception {
          color: var(--el-color-danger);
        }
      }

      .quota-used {
        font-weight: 600;
        color: var(--kp-text-secondary);
      }

      .quota-divider {
        margin: 0 4px;
        color: var(--kp-text-muted);
      }

      .quota-limit {
        color: var(--kp-text-muted);
      }

      .quota-limit-unlimited {
        color: var(--kp-text-muted);
        font-style: italic;
      }
    }

    .quota-progress {
      margin: 0;
      width: 100%;
      :deep(.el-progress-bar__outer) {
        background-color: rgba(15, 23, 42, 0.06) !important;
      }
    }

    .quota-unlimited-bar {
      height: 6px;
      border-radius: 100px;
      background: linear-gradient(90deg, rgba(66, 133, 244, 0.2) 0%, rgba(66, 133, 244, 0.05) 100%);
      width: 100%;
    }

    &.is-codex {
      .quota-loading-text {
        display: inline-flex;
        align-items: center;
        font-size: 11px;
        color: var(--kp-text-muted);
      }

      .quota-spinner {
        display: inline-block;
        width: 10px;
        height: 10px;
        border: 1.5px solid rgba(66, 133, 244, 0.2);
        border-top-color: #4285f4;
        border-radius: 50%;
        animation: quota-spin 0.8s linear infinite;
        margin-right: 6px;
        flex-shrink: 0;
      }

      .quota-loading-bar {
        height: 6px;
        border-radius: 100px;
        background: linear-gradient(
          90deg,
          rgba(66, 133, 244, 0.1) 25%,
          rgba(66, 133, 244, 0.3) 50%,
          rgba(66, 133, 244, 0.1) 75%
        );
        background-size: 200% 100%;
        animation: quota-loading-sweep 1.5s infinite linear;
        width: 100%;
      }

      .quota-error-text {
        color: var(--el-color-danger);
        font-weight: 600;
        cursor: help;
        text-decoration: underline dotted;
      }

      .quota-refresh-btn {
        margin-left: auto;
        padding: 0;
        height: auto;
        font-size: 11px;
        display: inline-flex;
        align-items: center;
        gap: 2px;

        .icon-refresh {
          transition: transform 0.25s ease;
        }

        &:hover .icon-refresh {
          transform: rotate(180deg);
        }
      }

      .quota-refresh-btn-mini {
        margin-left: 6px;
        padding: 0;
        height: auto;
        font-size: 10px;
        opacity: 0;
        transition: opacity 0.2s ease;
        display: inline-flex;
        align-items: center;

        .icon-refresh {
          color: var(--kp-text-muted);
          transition:
            transform 0.25s ease,
            color 0.2s ease;
        }

        &:hover .icon-refresh {
          transform: rotate(180deg);
          color: var(--kp-primary);
        }
      }

      .quota-wrapper-hoverable {
        cursor: pointer;
        width: 100%;

        &:hover {
          .quota-refresh-btn-mini {
            opacity: 1;
          }
        }
      }

      .quota-fallback-label {
        font-size: 11px;
        color: var(--kp-text-muted);
      }
    }
  }

  .icon-gap {
    margin-right: 2px;
  }

  .quota-tooltip-window {
    margin-top: 4px;
  }

  .quota-tooltip-source {
    margin-top: 6px;
    font-size: 10px;
    opacity: 0.8;
    border-top: 1px solid rgba(255, 255, 255, 0.15);
    padding-top: 4px;
  }

  .quota-tooltip-content {
    font-size: 12px;
    line-height: 1.55;
    padding: 2px 4px;

    strong {
      color: #93c5fd;
    }
  }

  @keyframes quota-spin {
    to {
      transform: rotate(360deg);
    }
  }

  @keyframes quota-loading-sweep {
    0% {
      background-position: 200% 0;
    }
    100% {
      background-position: -200% 0;
    }
  }
</style>
