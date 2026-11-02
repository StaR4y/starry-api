<script setup lang="ts">
  import { t } from '@/i18n';
  import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
  import { Refresh, Tools } from '@element-plus/icons-vue';
  import { useRoute, useRouter } from 'vue-router';
  import { systemApi } from '@/api';
  import { useAuthStore } from '@/stores/auth';
  import SiteBrandLogo from '@/components/SiteBrandLogo.vue';
  import SourceNotice from '@/components/SourceNotice.vue';
  import type { SystemStatus } from '@/types/api';

  const route = useRoute();
  const router = useRouter();
  const auth = useAuthStore();
  const checking = ref(false);
  const status = ref<SystemStatus | null>(null);
  const checkFailed = ref(false);
  let pollTimer: number | undefined;

  const message = computed(() => {
    if (status.value?.maintenance || status.value?.code === 'SYSTEM_MAINTENANCE') {
      return t('安全保护已触发，站内服务暂时停止');
    }
    return checkFailed.value ? t('暂时无法连接服务') : t('系统正在维护中');
  });

  function recoveryTarget() {
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '';
    if (
      redirect.startsWith('/') &&
      !redirect.startsWith('//') &&
      !redirect.startsWith('/maintenance')
    ) {
      return redirect;
    }
    return { name: auth.isAuthenticated ? auth.defaultRouteName : 'login' };
  }

  async function checkStatus() {
    if (checking.value) return;
    checking.value = true;
    try {
      const nextStatus = await systemApi.status();
      status.value = nextStatus;
      checkFailed.value = false;
      if (!nextStatus.maintenance && nextStatus.code !== 'SYSTEM_MAINTENANCE') {
        await router.replace(recoveryTarget());
      }
    } catch {
      checkFailed.value = true;
    } finally {
      checking.value = false;
    }
  }

  onMounted(() => {
    checkStatus();
    pollTimer = window.setInterval(checkStatus, 15_000);
  });

  onBeforeUnmount(() => {
    if (pollTimer !== undefined) window.clearInterval(pollTimer);
  });
</script>

<template>
  <main class="maintenance">
    <section class="maintenance__panel glass-panel glass-panel--strong" aria-live="polite">
      <SiteBrandLogo class="maintenance__logo" />
      <el-icon class="maintenance__icon" :size="30"><Tools /></el-icon>
      <h1>{{ t('系统维护中') }}</h1>
      <p>{{ message }}</p>
      <el-button type="primary" :icon="Refresh" :loading="checking" @click="checkStatus">
        {{ t('检查服务状态') }}
      </el-button>
    </section>
    <SourceNotice class="maintenance__source text-muted" />
  </main>
</template>

<style scoped lang="scss">
  .maintenance {
    min-height: 100vh;
    display: grid;
    place-items: center;
    padding: 24px 24px 56px;
    position: relative;

    &__source {
      position: absolute;
      left: 16px;
      right: 16px;
      bottom: 12px;
      font-size: 12px;
    }

    &__panel {
      width: min(440px, 100%);
      padding: 36px 32px;
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;
    }

    &__logo {
      width: 52px;
      height: 52px;
      margin-bottom: 22px;
    }

    &__icon {
      color: var(--kp-warning);
    }

    h1 {
      margin: 12px 0 8px;
      font-size: 24px;
      letter-spacing: 0;
    }

    p {
      min-height: 44px;
      margin: 0 0 24px;
      color: var(--kp-text-secondary);
      line-height: 1.7;
    }
  }

  @media (max-width: 520px) {
    .maintenance {
      padding: 16px 16px 56px;

      &__panel {
        padding: 30px 22px;
      }
    }
  }
</style>
