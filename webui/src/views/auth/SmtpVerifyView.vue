<script setup lang="ts">
  import { t } from '@/i18n';
  import { computed, onMounted, ref } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { authApi } from '@/api';
  import { useAppearanceStore } from '@/stores/appearance';
  import SiteBrandLogo from '@/components/SiteBrandLogo.vue';
  import SourceNotice from '@/components/SourceNotice.vue';
  import { ApiError } from '@/utils/http';

  const route = useRoute();
  const router = useRouter();
  const appearance = useAppearanceStore();

  const loading = ref(true);
  const verified = ref(false);
  const message = ref(t('正在验证 SMTP 发信…'));

  const token = computed(() => {
    const value = route.query.token;
    return typeof value === 'string' ? value.trim() : '';
  });

  onMounted(async () => {
    if (!token.value) {
      loading.value = false;
      verified.value = false;
      message.value = t('验证链接无效，缺少 token。');
      return;
    }
    try {
      const result = await authApi.verifySmtp(token.value);
      verified.value = Boolean(result.verified);
      message.value = result.message || (verified.value ? t('SMTP 发信已验证。') : t('验证失败。'));
    } catch (error) {
      verified.value = false;
      message.value = error instanceof ApiError ? error.message : t('验证链接无效或已过期。');
    } finally {
      loading.value = false;
    }
  });
</script>

<template>
  <div class="smtp-verify">
    <div class="smtp-verify__panel glass-panel glass-panel--strong">
      <SiteBrandLogo class="smtp-verify__logo" />
      <h1>{{ appearance.siteName }}</h1>
      <p class="smtp-verify__kicker">{{ t('SMTP 发信验证') }}</p>
      <p class="smtp-verify__message">{{ loading ? t('正在验证 SMTP 发信…') : message }}</p>
      <el-tag v-if="!loading" :type="verified ? 'success' : 'danger'" effect="light">
        {{ verified ? t('已验证') : t('未完成') }}
      </el-tag>
      <el-button
        class="smtp-verify__action"
        type="primary"
        @click="router.replace({ name: 'login' })"
      >
        {{ t('前往登录') }}
      </el-button>
    </div>
    <SourceNotice class="smtp-verify__source text-muted" />
  </div>
</template>

<style scoped lang="scss">
  .smtp-verify {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 100vh;
    padding: 24px 24px 56px;
    position: relative;
  }

  .smtp-verify__source {
    position: absolute;
    left: 16px;
    right: 16px;
    bottom: 12px;
    font-size: 12px;
  }

  .smtp-verify__panel {
    display: grid;
    justify-items: center;
    gap: 12px;
    width: min(420px, 100%);
    padding: 36px 28px;
    text-align: center;
  }

  .smtp-verify__logo {
    margin-bottom: 4px;
  }

  h1 {
    margin: 0;
    font-size: 22px;
    font-weight: 650;
  }

  .smtp-verify__kicker,
  .smtp-verify__message {
    margin: 0;
    color: var(--kp-text-secondary);
    font-size: 14px;
    line-height: 1.6;
  }

  .smtp-verify__action {
    width: 100%;
    margin-top: 8px;
  }
</style>
