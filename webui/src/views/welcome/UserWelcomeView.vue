<script setup lang="ts">
  /**
   * 普通用户欢迎面板。
   *
   * 展示当前账号余额与用户可访问的 API Key 使用概览，作为普通用户登录后的默认首页。
   */
  import { computed, onMounted, ref } from 'vue';
  import { Refresh } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { apiKeysApi } from '@/api';
  import { useAuthStore } from '@/stores/auth';
  import type { ApiKey } from '@/types/api';
  import { formatDateTime, formatNumber } from '@/utils/format';

  const auth = useAuthStore();
  const loading = ref(false);
  const apiKeys = ref<ApiKey[]>([]);

  const displayName = computed(() => auth.user?.displayName || auth.user?.username || '用户');
  const balance = computed(() => auth.user?.balance ?? 0);
  const activeKeys = computed(() => apiKeys.value.filter((key) => key.enabled).length);
  const totalRequests = computed(() =>
    apiKeys.value.reduce((sum, key) => sum + (key.totalRequests ?? 0), 0),
  );
  const totalCredits = computed(() =>
    apiKeys.value.reduce((sum, key) => sum + (key.totalCredits ?? 0), 0),
  );
  const totalTokens = computed(() =>
    apiKeys.value.reduce(
      (sum, key) => sum + (key.totalInputTokens ?? 0) + (key.totalOutputTokens ?? 0),
      0,
    ),
  );
  const latestUsedAt = computed(() => {
    const latest = apiKeys.value
      .map((key) => key.lastUsedAt)
      .filter((value): value is string => Boolean(value))
      .sort((a, b) => new Date(b).getTime() - new Date(a).getTime())[0];
    return latest ?? null;
  });

  const stats = computed(() => [
    { label: '当前余额', value: formatNumber(balance.value), tone: 'primary' },
    { label: 'API Key', value: `${formatNumber(activeKeys.value)} / ${formatNumber(apiKeys.value.length)}`, tone: 'success' },
    { label: '累计调用', value: formatNumber(totalRequests.value), tone: 'info' },
    { label: '累计消耗', value: formatNumber(totalCredits.value), tone: 'warning' },
  ]);

  async function load() {
    loading.value = true;
    try {
      await auth.refreshCurrentUser();
      apiKeys.value = await apiKeysApi.list();
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
        <section class="welcome__hero">
          <div>
            <p class="welcome__eyebrow">账户概览</p>
            <h3 class="welcome__headline">余额 {{ formatNumber(balance) }}</h3>
            <p class="welcome__copy">
              你可以在 API Key 页面创建和管理密钥。余额由管理员设置，刷新后会显示最新数值。
            </p>
          </div>
          <div class="welcome__badge">
            <span>最近使用</span>
            <strong>{{ formatDateTime(latestUsedAt) }}</strong>
          </div>
        </section>

        <section class="welcome__stats">
          <article
            v-for="item in stats"
            :key="item.label"
            class="welcome__stat"
            :class="`welcome__stat--${item.tone}`"
          >
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </article>
        </section>

        <section class="welcome__details">
          <div class="welcome__detail">
            <span class="text-muted">累计 Token</span>
            <strong>{{ formatNumber(totalTokens) }}</strong>
          </div>
          <div class="welcome__detail">
            <span class="text-muted">密钥状态</span>
            <strong>{{ activeKeys > 0 ? '可用' : '暂无启用密钥' }}</strong>
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

    &__hero {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 24px;
      align-items: center;
      padding: 22px;
      border-radius: 12px;
      background: linear-gradient(135deg, rgba(91, 141, 239, 0.12), rgba(16, 185, 129, 0.1));
      border: 1px solid rgba(91, 141, 239, 0.16);
    }

    &__eyebrow {
      margin: 0 0 8px;
      font-size: 12px;
      color: var(--kp-text-secondary);
      font-weight: 600;
    }

    &__headline {
      margin: 0;
      font-size: 28px;
      font-weight: 700;
      letter-spacing: 0;
    }

    &__copy {
      margin: 8px 0 0;
      color: var(--kp-text-secondary);
      font-size: 13px;
      line-height: 1.6;
      max-width: 560px;
    }

    &__badge {
      display: flex;
      flex-direction: column;
      gap: 6px;
      min-width: 170px;
      padding: 14px 16px;
      border-radius: 10px;
      background: rgba(255, 255, 255, 0.72);
      border: 1px solid var(--kp-divider);

      span {
        color: var(--kp-text-muted);
        font-size: 12px;
      }

      strong {
        font-size: 13px;
      }
    }

    &__stats {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 14px;
    }

    &__stat,
    &__detail {
      padding: 16px;
      border-radius: 10px;
      border: 1px solid var(--kp-divider);
      background: rgba(255, 255, 255, 0.62);
    }

    &__stat {
      display: flex;
      flex-direction: column;
      gap: 8px;

      span {
        color: var(--kp-text-muted);
        font-size: 12px;
      }

      strong {
        font-size: 20px;
        letter-spacing: 0;
      }

      &--primary strong { color: var(--kp-primary); }
      &--success strong { color: #059669; }
      &--info strong { color: #2563eb; }
      &--warning strong { color: #b45309; }
    }

    &__details {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 14px;
    }

    &__detail {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 16px;
    }
  }

  @media (max-width: 900px) {
    .welcome {
      &__hero,
      &__stats,
      &__details {
        grid-template-columns: 1fr;
      }

      &__badge {
        min-width: 0;
      }
    }
  }
</style>
