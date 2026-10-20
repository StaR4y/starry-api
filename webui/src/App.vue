<script setup lang="ts">
  import { computed, watchEffect } from 'vue';
  import { useRoute } from 'vue-router';
  import en from 'element-plus/es/locale/lang/en';
  import zhCn from 'element-plus/es/locale/lang/zh-cn';
  import { locale, t } from '@/i18n';
  import { useAppearanceStore } from '@/stores/appearance';
  const elementLocale = computed(() => (locale.value === 'en-US' ? en : zhCn));
  const route = useRoute();
  const appearance = useAppearanceStore();
  watchEffect(() => {
    const title = t(String(route.meta.title ?? '控制台'));
    document.title = `${title} · ${appearance.siteName}`;
  });
  /**
   * 应用根组件。
   * 仅承载 router-view，并提供全局过渡。
   */
</script>

<template>
  <el-config-provider :locale="elementLocale">
    <router-view v-slot="{ Component, route: activeRoute }">
      <transition name="kp-fade" mode="out-in">
        <component :is="Component" :key="activeRoute.matched[0]?.path ?? activeRoute.path" />
      </transition>
    </router-view>
  </el-config-provider>
</template>

<style>
  .kp-fade-enter-active,
  .kp-fade-leave-active {
    transition: opacity 0.18s ease;
  }
  .kp-fade-enter-from,
  .kp-fade-leave-to {
    opacity: 0;
  }
</style>
