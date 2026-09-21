<script setup lang="ts">
  import { computed, ref, watch } from 'vue';
  import { useAppearanceStore } from '@/stores/appearance';

  const props = withDefaults(
    defineProps<{
      alt?: string;
    }>(),
    { alt: '' },
  );

  const appearance = useAppearanceStore();
  const failed = ref(false);
  const src = computed(() => (failed.value ? '/logo.svg' : appearance.logoSrc));
  const label = computed(() => props.alt || appearance.siteName);

  watch(
    () => appearance.logoSrc,
    () => {
      failed.value = false;
    },
  );

  function handleError() {
    failed.value = true;
  }
</script>

<template>
  <img :src="src" :alt="label" @error="handleError">
</template>
