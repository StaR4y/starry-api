<script setup lang="ts">
  /** Floating whale-maid pet from a started usage plugin; opens the tracker panel. */
  import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
  import UsageMaidPanel from '@/components/usage-maid/UsageMaidPanel.vue';
  import { usePluginWidgetStore, type PluginWidget } from '@/stores/pluginWidgets';

  const widgets = usePluginWidgetStore();
  const imageWidgets = computed(() =>
    widgets.visible.filter((widget) => !widget.scriptUrl),
  );
  const openId = ref<string | null>(null);
  const hoveredId = ref<string | null>(null);

  function assetUrl(imageUrl: string, file: string) {
    return imageUrl.replace(/[^/]+(?:\?.*)?$/, file);
  }

  function poseUrl(widget: PluginWidget, open: boolean) {
    if (!open) return widget.imageUrl;
    return assetUrl(widget.imageUrl, 'maid-side.png');
  }

  function onPoseError(event: Event, fallback: string) {
    const image = event.target as HTMLImageElement;
    if (image.src.includes(fallback) || image.dataset.fallback === '1') return;
    image.dataset.fallback = '1';
    image.src = fallback;
  }

  function toggle(pluginId: string) {
    openId.value = openId.value === pluginId ? null : pluginId;
  }

  function onDocumentClick(event: MouseEvent) {
    const target = event.target as HTMLElement | null;
    if (target?.closest('.usage-maid')) return;
    openId.value = null;
  }

  function onKeydown(event: KeyboardEvent) {
    if (event.key === 'Escape') openId.value = null;
  }

  onMounted(() => {
    void widgets.refresh();
    document.addEventListener('click', onDocumentClick);
    document.addEventListener('keydown', onKeydown);
  });

  onBeforeUnmount(() => {
    document.removeEventListener('click', onDocumentClick);
    document.removeEventListener('keydown', onKeydown);
  });
</script>

<template>
  <div
    v-for="widget in imageWidgets"
    :key="widget.pluginId"
    class="usage-maid"
    :class="{ 'is-open': openId === widget.pluginId }"
  >
    <UsageMaidPanel
      v-if="openId === widget.pluginId"
      :title="widget.title"
      @close="openId = null"
    />
    <button
      type="button"
      class="usage-maid__trigger"
      :aria-expanded="openId === widget.pluginId"
      :aria-label="widget.title"
      @mouseenter="hoveredId = widget.pluginId"
      @mouseleave="hoveredId = null"
      @click.stop="toggle(widget.pluginId)"
    >
      <img
        :src="poseUrl(widget, openId === widget.pluginId)"
        :alt="widget.title"
        @error="onPoseError($event, widget.imageUrl)"
      >
    </button>
    <span v-if="hoveredId === widget.pluginId && openId !== widget.pluginId" class="usage-maid__hint">
      {{ widget.title }}
    </span>
  </div>
</template>

<style scoped lang="scss">
  .usage-maid {
    position: fixed;
    right: 18px;
    bottom: 18px;
    z-index: 40;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 8px;
    pointer-events: none;
  }

  .usage-maid__trigger,
  .usage-maid :deep(.maid-panel) {
    pointer-events: auto;
  }

  .usage-maid__trigger {
    width: 168px;
    height: 224px;
    padding: 0;
    border: 0;
    background: transparent;
    cursor: pointer;
    filter: drop-shadow(0 10px 18px rgba(26, 48, 92, 0.22));

    img {
      width: 100%;
      height: 100%;
      object-fit: contain;
      object-position: bottom center;
      animation: maid-idle 2.8s ease-in-out infinite;
    }

    &:focus-visible {
      outline: 2px solid var(--kp-primary);
      outline-offset: 4px;
      border-radius: 18px;
    }
  }

  .usage-maid__hint {
    pointer-events: none;
    position: absolute;
    right: 170px;
    bottom: 36px;
    padding: 4px 10px;
    border: 1px solid var(--el-border-color);
    border-radius: 999px;
    background: color-mix(in srgb, white 82%, transparent);
    color: var(--kp-text);
    font-size: 12px;
    white-space: nowrap;
    box-shadow: 0 8px 18px rgba(26, 48, 92, 0.12);
  }

  .usage-maid.is-open .usage-maid__trigger img {
    animation: none;
  }

  @keyframes maid-idle {
    0%,
    100% {
      transform: translateY(0);
    }
    50% {
      transform: translateY(-6px);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .usage-maid__trigger img {
      animation: none;
    }
  }

  @media (max-width: 900px) {
    .usage-maid {
      right: 8px;
      left: 8px;
      bottom: 8px;
      align-items: stretch;
    }

    .usage-maid__trigger {
      align-self: flex-end;
      width: 126px;
      height: 168px;
    }

    .usage-maid__hint {
      right: 12px;
      bottom: 188px;
    }
  }
</style>
