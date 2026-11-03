<script setup lang="ts">
  /**
   * 部署后 Root 第一次进入控制台时，确认许可证、源代码地址、第三方许可和免责声明。每个部署只问一次：同意记录在服务器上
   * （POST /auth/legal-consent），之后谁都不会再看到；其他用户从来看不到。没同意之前对话框关不掉，只能同意或退出登录。
   * 源代码地址来自运营者设置的 STARRY_SOURCE_URL（随公开外观一起下发）。
   */
  import { ref } from 'vue';
  import { ElMessage } from 'element-plus';
  import { useRouter } from 'vue-router';
  import { t } from '@/i18n';
  import { useAppearanceStore } from '@/stores/appearance';
  import { useAuthStore } from '@/stores/auth';
  import {
    LICENSE_NAME,
    LICENSE_URL,
    THIRD_PARTY_NOTICES_HREF,
    disclaimerText,
  } from '@/utils/sourceCode';

  const auth = useAuthStore();
  const appearance = useAppearanceStore();
  const router = useRouter();
  const accepting = ref(false);

  async function accept() {
    accepting.value = true;
    try {
      await auth.acceptLegalConsent();
    } catch (error) {
      ElMessage.error(error instanceof Error && error.message ? error.message : t('确认失败，请重试'));
    } finally {
      accepting.value = false;
    }
  }

  function signOut() {
    auth.logout();
    void router.push({ name: 'login' });
  }
</script>

<template>
  <el-dialog
    :model-value="auth.legalConsentRequired"
    :title="t('使用前请确认')"
    width="min(560px, 92vw)"
    :show-close="false"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    append-to-body
  >
    <p class="legal-consent__intro" data-test="consent-intro">
      {{ t('这是部署后的一次性确认，只有 Root 会看到。同意后不再出现。') }}
    </p>
    <dl class="legal-consent__terms">
      <dt>{{ t('许可证') }}</dt>
      <dd>
        <a data-test="license-link" :href="LICENSE_URL" target="_blank" rel="noopener noreferrer">{{ LICENSE_NAME }}</a>
      </dd>
      <dt>{{ t('源代码') }}</dt>
      <dd>
        <a data-test="source-link" :href="appearance.sourceUrl" target="_blank" rel="noopener noreferrer">{{
          appearance.sourceUrl
        }}</a>
      </dd>
      <dt>{{ t('第三方许可') }}</dt>
      <dd>
        <a data-test="notices-link" :href="THIRD_PARTY_NOTICES_HREF" target="_blank" rel="noopener noreferrer">{{
          THIRD_PARTY_NOTICES_HREF.replace('./', '')
        }}</a>
      </dd>
    </dl>
    <h3 class="legal-consent__heading">{{ t('免责声明') }}</h3>
    <p class="legal-consent__disclaimer" data-test="disclaimer-text">{{ disclaimerText() }}</p>
    <template #footer>
      <el-button data-test="consent-sign-out" @click="signOut">{{ t('退出登录') }}</el-button>
      <el-button type="primary" data-test="consent-accept" :loading="accepting" @click="accept">
        {{ t('我已阅读并同意') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
  .legal-consent__intro {
    margin: 0 0 12px;
  }
  .legal-consent__terms {
    display: grid;
    grid-template-columns: max-content 1fr;
    gap: 6px 16px;
    margin: 0 0 16px;
    dt {
      color: var(--kp-text-muted);
    }
    dd {
      margin: 0;
      overflow-wrap: anywhere;
    }
    a {
      color: var(--kp-primary);
    }
  }
  .legal-consent__heading {
    margin: 0 0 6px;
    font-size: 14px;
  }
  .legal-consent__disclaimer {
    margin: 0;
    line-height: 1.7;
    white-space: normal;
    overflow-wrap: anywhere;
  }
</style>
