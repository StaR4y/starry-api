<script setup lang="ts">
  /**
   * 许可证、源代码与免责声明。AGPL-3.0 第 13 条要求向通过网络使用本服务的人提供其所运行版本的源代码，
   * 地址来自运营者设置的 STARRY_SOURCE_URL（随公开外观一起下发），所以修改过代码的运营者能把链接指向自己的源码。
   */
  import { ref } from 'vue';
  import { t } from '@/i18n';
  import { useAppearanceStore } from '@/stores/appearance';
  import {
    LICENSE_NAME,
    LICENSE_URL,
    THIRD_PARTY_NOTICES_HREF,
    disclaimerText,
  } from '@/utils/sourceCode';

  const appearance = useAppearanceStore();
  const disclaimerVisible = ref(false);
</script>

<template>
  <nav class="source-notice" :aria-label="t('许可证与源代码')">
    <a
      class="source-notice__link"
      data-test="license-link"
      :href="LICENSE_URL"
      target="_blank"
      rel="noopener noreferrer"
    >{{ LICENSE_NAME }}</a>
    <a
      class="source-notice__link"
      data-test="source-link"
      :href="appearance.sourceUrl"
      target="_blank"
      rel="noopener noreferrer"
    >{{ t('源代码') }}</a>
    <a
      class="source-notice__link"
      data-test="notices-link"
      :href="THIRD_PARTY_NOTICES_HREF"
      target="_blank"
      rel="noopener noreferrer"
    >{{ t('第三方许可') }}</a>
    <button
      type="button"
      class="source-notice__link source-notice__button"
      data-test="disclaimer-button"
      @click="disclaimerVisible = true"
    >
      {{ t('免责声明') }}
    </button>
    <el-dialog
      v-model="disclaimerVisible"
      :title="t('免责声明')"
      width="min(520px, 92vw)"
      append-to-body
    >
      <p class="source-notice__disclaimer" data-test="disclaimer-text">{{ disclaimerText() }}</p>
    </el-dialog>
  </nav>
</template>

<style scoped lang="scss">
  .source-notice {
    display: inline-flex;
    flex-wrap: wrap;
    justify-content: center;
    align-items: center;
    gap: 2px 14px;
  }
  .source-notice__link {
    color: inherit;
    text-decoration: none;
    opacity: 0.9;
    &:hover,
    &:focus-visible {
      color: var(--kp-primary);
      text-decoration: underline;
    }
  }
  .source-notice__button {
    appearance: none;
    background: none;
    border: 0;
    padding: 0;
    font: inherit;
    cursor: pointer;
  }
  .source-notice__disclaimer {
    margin: 0;
    line-height: 1.7;
    white-space: normal;
    overflow-wrap: anywhere;
  }
</style>
