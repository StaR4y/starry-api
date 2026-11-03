<script setup lang="ts">
  /**
   * 许可条款与免责声明。只在新部署上出现：首次启动在空数据库里创建 Root 时，服务端记下需要确认，Root 进入控制台后看到这个
   * 对话框，同意后记录在服务器上（POST /auth/legal-consent），之后谁都不会再看到；在这之前已经初始化过的部署不会出现。
   * 没同意之前对话框关不掉，只能同意或退出登录。源代码地址来自运营者设置的 STARRY_SOURCE_URL（随公开外观一起下发）。
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
  const noticesFile = THIRD_PARTY_NOTICES_HREF.replace('./', '');

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

  function decline() {
    auth.logout();
    void router.push({ name: 'login' });
  }
</script>

<template>
  <el-dialog
    :model-value="auth.legalConsentRequired"
    :title="t('许可条款与免责声明')"
    width="min(600px, 92vw)"
    :show-close="false"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    append-to-body
  >
    <div class="legal-consent">
      <p class="legal-consent__intro" data-test="consent-intro">
        {{ t('使用本软件前，请仔细阅读以下许可条款与免责声明。点击「同意并继续」，即表示已阅读、理解并同意以下全部内容。') }}
      </p>

      <section class="legal-consent__clause">
        <h3>{{ t('一、软件许可') }}</h3>
        <p>
          {{ t('本软件依据 GNU Affero 通用公共许可证第 3 版发布。许可证全文：') }}
          <a data-test="license-link" :href="LICENSE_URL" target="_blank" rel="noopener noreferrer">{{ LICENSE_NAME }}</a>
        </p>
      </section>

      <section class="legal-consent__clause">
        <h3>{{ t('二、源代码') }}</h3>
        <p>
          {{ t('本软件的源代码可从以下地址获取：') }}
          <a data-test="source-link" :href="appearance.sourceUrl" target="_blank" rel="noopener noreferrer">{{
            appearance.sourceUrl
          }}</a>
        </p>
      </section>

      <section class="legal-consent__clause">
        <h3>{{ t('三、第三方组件') }}</h3>
        <p>
          {{ t('本软件包含的第三方组件按各自的许可证提供，许可证与版权声明见：') }}
          <a data-test="notices-link" :href="THIRD_PARTY_NOTICES_HREF" target="_blank" rel="noopener noreferrer">{{
            noticesFile
          }}</a>
        </p>
      </section>

      <section class="legal-consent__clause">
        <h3>{{ t('四、免责声明') }}</h3>
        <p data-test="disclaimer-text">{{ disclaimerText() }}</p>
      </section>
    </div>
    <template #footer>
      <el-button data-test="consent-decline" @click="decline">{{ t('不同意并退出') }}</el-button>
      <el-button type="primary" data-test="consent-accept" :loading="accepting" @click="accept">
        {{ t('同意并继续') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
  .legal-consent {
    max-height: min(60vh, 520px);
    overflow-y: auto;
    line-height: 1.7;
  }
  .legal-consent__intro {
    margin: 0 0 16px;
  }
  .legal-consent__clause {
    margin: 0 0 14px;
    h3 {
      margin: 0 0 4px;
      font-size: 14px;
      font-weight: 600;
    }
    p {
      margin: 0;
      white-space: normal;
      overflow-wrap: anywhere;
    }
    a {
      color: var(--kp-primary);
    }
  }
</style>
