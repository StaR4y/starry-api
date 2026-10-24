<script setup lang="ts">
  /** 订阅来源：状态、节点数、上次同步与错误，以及同步 / 重命名 / 删除。 */
  import { Delete, Edit, Refresh } from '@element-plus/icons-vue';
  import { t } from '@/i18n';
  import StatusTag from '@/components/StatusTag.vue';
  import type { ProxySubscription } from '@/api/proxyChannels';
  import { formatDateTime } from '@/utils/format';
  import { formatRelative } from './helpers';

  defineProps<{ subscriptions: ProxySubscription[]; busy: Set<string> }>();
  const emit = defineEmits<{
    sync: [id: string];
    rename: [subscription: ProxySubscription];
    remove: [subscription: ProxySubscription];
    show: [id: string];
  }>();

  function statusOf(subscription: ProxySubscription) {
    if (subscription.status === 'ACTIVE') return { label: t('正常'), tone: 'success' as const };
    if (subscription.status === 'ERROR') return { label: t('同步失败'), tone: 'danger' as const };
    return { label: t('待同步'), tone: 'info' as const };
  }
</script>

<template>
  <section class="subscription-section">
    <h3 class="section-title">
      {{ t('订阅来源') }}<span class="section-count">{{ subscriptions.length }}</span>
    </h3>
    <div class="subscription-list surface-card">
      <div v-for="sub in subscriptions" :key="sub.subscriptionId" class="source-row">
        <div class="subscription-main">
          <div class="subscription-title">
            <strong>{{ sub.name }}</strong>
            <StatusTag :label="statusOf(sub).label" :tone="statusOf(sub).tone" />
            <el-tag v-if="!sub.refreshable" size="small" effect="plain" type="info">
              {{ t('粘贴导入') }}
            </el-tag>
          </div>
          <div class="subscription-meta">
            <el-tooltip :content="t('查看节点')">
              <el-button
                link
                type="primary"
                class="subscription-nodes"
                @click="emit('show', sub.subscriptionId)"
              >
                {{ t('{p0} 个节点', { p0: sub.nodeCount }) }}
              </el-button>
            </el-tooltip>
            <span :title="formatDateTime(sub.lastSyncedAt)">
              {{
                sub.lastSyncedAt
                  ? t('上次同步 {p0}', { p0: formatRelative(sub.lastSyncedAt) })
                  : t('尚未同步')
              }}
            </span>
          </div>
          <el-tooltip v-if="sub.lastError" :content="sub.lastError" placement="top-start">
            <p class="subscription-error">{{ sub.lastError }}</p>
          </el-tooltip>
        </div>
        <div class="subscription-actions">
          <el-button
            v-if="sub.refreshable"
            link
            type="primary"
            :icon="Refresh"
            :loading="busy.has(sub.subscriptionId)"
            @click="emit('sync', sub.subscriptionId)"
          >
            {{ t('同步') }}
          </el-button>
          <el-tooltip :content="t('重命名')">
            <el-button
              link
              :icon="Edit"
              :disabled="busy.has(sub.subscriptionId)"
              :aria-label="t('重命名')"
              @click="emit('rename', sub)"
            />
          </el-tooltip>
          <el-tooltip :content="t('删除订阅')">
            <el-button
              link
              type="danger"
              :icon="Delete"
              :disabled="busy.has(sub.subscriptionId)"
              :aria-label="t('删除订阅')"
              @click="emit('remove', sub)"
            />
          </el-tooltip>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped lang="scss">
  .subscription-section {
    display: flex;
    flex-direction: column;
    gap: 10px;
    min-width: 0;
  }

  .section-title {
    display: flex;
    align-items: baseline;
    gap: 8px;
    margin: 0;
    font-size: 15px;
    color: var(--kp-text);
  }

  .section-count {
    font-size: 12px;
    font-weight: 400;
    color: var(--kp-text-muted);
  }

  .subscription-list {
    display: flex;
    flex-direction: column;
    padding: 2px 16px;
  }

  .source-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 12px 0;

    & + & {
      border-top: 1px solid var(--kp-divider);
    }
  }

  .subscription-main {
    display: flex;
    flex-direction: column;
    gap: 4px;
    min-width: 0;
  }

  .subscription-title {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;

    strong {
      font-size: 14px;
      color: var(--kp-text);
      overflow-wrap: anywhere;
    }
  }

  .subscription-meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 4px 14px;
    font-size: 12px;
    color: var(--kp-text-muted);
  }

  .subscription-nodes {
    height: auto;
    padding: 0;
    font-size: 12px;
  }

  .subscription-error {
    display: -webkit-box;
    margin: 0;
    overflow: hidden;
    font-size: 12px;
    color: var(--el-color-danger);
    overflow-wrap: anywhere;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  }

  .subscription-actions {
    display: flex;
    flex: 0 0 auto;
    flex-wrap: wrap;
    align-items: center;
    justify-content: flex-end;
    gap: 2px 6px;

    .el-button {
      margin: 0;
    }
  }

  @media (max-width: 640px) {
    .source-row {
      flex-direction: column;
      align-items: stretch;
      gap: 8px;
    }

    .subscription-actions {
      justify-content: flex-start;
    }
  }
</style>
