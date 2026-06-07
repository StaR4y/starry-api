<script setup lang="ts">
  /**
   * 应用根组件。
   * 仅承载 router-view，并提供全局过渡。
   */
</script>

<template>
  <router-view v-slot="{ Component, route }">
    <transition name="kp-fade" mode="out-in">
      <component :is="Component" :key="route.fullPath" />
    </transition>
  </router-view>
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
