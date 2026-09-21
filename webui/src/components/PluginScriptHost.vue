<script setup lang="ts">
  /** Loads started plugin web scripts the current user has chosen to show. */
  import { onBeforeUnmount, onMounted, watch } from 'vue';
  import { usePluginWidgetStore } from '@/stores/pluginWidgets';

  const widgets = usePluginWidgetStore();
  const loaded = new Set<string>();

  function scriptWidgets() {
    return widgets.visible.filter((widget) => Boolean(widget.scriptUrl));
  }

  function loadScript(url: string) {
    if (!url || loaded.has(url)) {
      return;
    }
    document.querySelectorAll(`script[data-plugin-script="${url}"]`).forEach((node) => node.remove());
    const el = document.createElement('script');
    el.src = `${url}${url.includes('?') ? '&' : '?'}t=${Date.now()}`;
    el.async = true;
    el.dataset.pluginScript = url;
    document.body.appendChild(el);
    loaded.add(url);
  }

  function unloadScripts() {
    const host = window as Window & {
      __starryWhaleWidgetDestroy?: () => void
      __starryDynamicIslandDestroy?: () => void
      __starryPluginDestroy?: Record<string, () => void>
    }
    try {
      host.__starryWhaleWidgetDestroy?.()
    } catch {
      /* ignore */
    }
    try {
      host.__starryDynamicIslandDestroy?.()
    } catch {
      /* ignore */
    }
    const registry = host.__starryPluginDestroy
    if (registry) {
      Object.values(registry).forEach((destroy) => {
        try {
          destroy?.()
        } catch {
          /* ignore */
        }
      })
    }
    document.querySelectorAll('script[data-plugin-script]').forEach((node) => node.remove());
    loaded.clear();
  }

  function syncScripts() {
    const wanted = new Set(scriptWidgets().map((widget) => widget.scriptUrl as string));
    if (wanted.size === 0) {
      unloadScripts();
      return;
    }
    for (const url of [...loaded]) {
      if (!wanted.has(url)) {
        unloadScripts();
        break;
      }
    }
    for (const url of wanted) loadScript(url);
  }

  function onPrefsChanged() {
    widgets.reloadPrefs();
  }

  onMounted(() => {
    window.addEventListener('starry-plugin-widget-prefs', onPrefsChanged);
    void widgets.refresh().then(syncScripts);
  });

  watch(
    () =>
      widgets.visible
        .map((widget) => widget.scriptUrl)
        .filter(Boolean)
        .join('|'),
    () => syncScripts(),
  );

  onBeforeUnmount(() => {
    window.removeEventListener('starry-plugin-widget-prefs', onPrefsChanged);
    unloadScripts();
  });
</script>

<template></template>
