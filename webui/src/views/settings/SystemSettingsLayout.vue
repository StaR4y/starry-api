<script setup lang="ts">
  /** 系统设置二级导航。业务设置保留各自页面组件，此处只负责分类与路由。 */
  import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
  import type { Component as VueComponent, CSSProperties } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { useAppearanceStore } from '@/stores/appearance';
  import { useAuthStore } from '@/stores/auth';
  import {
    Brush,
    Cpu,
    CreditCard,
    Connection,
    Message,
    Monitor,
    Postcard,
    SetUp,
    Trophy,
    Service,
    Operation,
  } from '@element-plus/icons-vue';
  import { pluginsApi, type PluginSettingPage } from '@/api/plugins';

  interface SettingsItem {
    name: string;
    label: string;
    icon: VueComponent;
    pluginId?: string;
    rootOnly?: boolean;
  }

  interface SettingsGroup {
    label: string;
    items: SettingsItem[];
  }

  const route = useRoute();
  const router = useRouter();
  const appearance = useAppearanceStore();
  const auth = useAuthStore();
  const navRef = ref<HTMLElement | null>(null);
  const indicatorReady = ref(false);
  const indicatorStyle = ref<CSSProperties>({});

  const builtinGroups: SettingsGroup[] = [
    {
      label: '业务配置',
      items: [
        { name: 'settings-payment', label: '支付设置', icon: CreditCard },
        { name: 'settings-coding-plans', label: 'Coding Plan', icon: SetUp },
        { name: 'settings-models', label: '模型管理', icon: Cpu },
      ],
    },
    {
      label: '访问与通知',
      items: [
        { name: 'settings-registration', label: '注册设置', icon: Postcard },
        { name: 'settings-smtp', label: 'SMTP 设置', icon: Message },
        { name: 'settings-tickets', label: '工单通知', icon: Service },
      ],
    },
    {
      label: '运行与安全',
      items: [
        { name: 'settings-account-pool', label: '账号池设置', icon: Monitor },
        { name: 'settings-plugins', label: '插件开发', icon: Connection, rootOnly: true },
      ],
    },
    {
      label: '内容与界面',
      items: [
        { name: 'settings-appearance', label: 'UI 自定义', icon: Brush },
        { name: 'settings-model-ranking', label: '模型天梯', icon: Trophy },
      ],
    },
  ];

  const pluginPages = ref<PluginSettingPage[]>([]);
  const groups = computed(() => {
    const next = builtinGroups.map((group) => ({
      label: group.label,
      items: group.items.filter((item) => !item.rootOnly || auth.isRoot),
    }));
    pluginPages.value.forEach((page) => {
      const item: SettingsItem = {
        name: `settings-plugin:${page.pluginId}`,
        label: page.title,
        icon: Operation,
        pluginId: page.pluginId,
      };
      const group = next.find((entry) => entry.label === page.group) ?? next[next.length - 1];
      if (!group.items.some((existing) => existing.pluginId === page.pluginId)) {
        group.items.push(item);
      }
    });
    return next;
  });

  const activeName = computed(() => String(route.name ?? 'settings-payment'));
  const orderedNames = computed(() =>
    groups.value.flatMap((group) =>
      group.items.flatMap((item) =>
        item.name === 'settings-models' ? ['settings-models', 'settings-model-create'] : [item.name],
      ),
    ),
  );

  function isItemActive(item: SettingsItem) {
    if (item.pluginId) {
      return activeName.value === 'settings-plugin' && String(route.params.pluginId ?? '') === item.pluginId;
    }
    if (item.name === 'settings-models') {
      return activeName.value === 'settings-models' || activeName.value === 'settings-model-create';
    }
    return activeName.value === item.name;
  }
  const previousIndex = ref(orderedNames.value.indexOf(activeName.value));
  const transitionDirection = ref<'forward' | 'backward'>('forward');
  const transitionName = computed(() => `settings-page-${transitionDirection.value}`);

  function navigate(item: SettingsItem) {
    if (item.pluginId) {
      if (route.name !== 'settings-plugin' || String(route.params.pluginId ?? '') !== item.pluginId) {
        void router.push({ name: 'settings-plugin', params: { pluginId: item.pluginId } });
      }
      return;
    }
    if (item.name !== route.name) void router.push({ name: item.name });
  }

  function syncActiveIndicator() {
    void nextTick(() => {
      const nav = navRef.value;
      const activeItem = nav?.querySelector<HTMLElement>('.settings-nav__item.is-active');
      if (!nav || !activeItem) return;

      const navRect = nav.getBoundingClientRect();
      const itemRect = activeItem.getBoundingClientRect();
      indicatorStyle.value = {
        width: `${itemRect.width}px`,
        height: `${itemRect.height}px`,
        transform: `translate3d(${itemRect.left - navRect.left + nav.scrollLeft}px, ${itemRect.top - navRect.top + nav.scrollTop}px, 0)`,
      };

      if (!indicatorReady.value) {
        window.requestAnimationFrame(() => {
          indicatorReady.value = true;
        });
      }
    });
  }

  watch(
    activeName,
    (name) => {
      const currentIndex = orderedNames.value.indexOf(name);
      if (currentIndex >= 0 && previousIndex.value >= 0 && currentIndex !== previousIndex.value) {
        transitionDirection.value = currentIndex > previousIndex.value ? 'forward' : 'backward';
      }
      if (currentIndex >= 0) previousIndex.value = currentIndex;

      void nextTick(() => {
        const nav = navRef.value;
        const activeItem = nav?.querySelector<HTMLElement>('.settings-nav__item.is-active');
        if (!nav || !activeItem) return;

        syncActiveIndicator();
        if (!window.matchMedia('(max-width: 980px)').matches) return;

        const navRect = nav.getBoundingClientRect();
        const itemRect = activeItem.getBoundingClientRect();
        const targetLeft = nav.scrollLeft + itemRect.left - navRect.left - 10;
        const behavior = window.matchMedia('(prefers-reduced-motion: reduce)').matches
          ? 'auto'
          : 'smooth';
        nav.scrollTo({ left: Math.max(0, targetLeft), behavior });
      });
    },
    { immediate: true },
  );

  onMounted(() => {
    window.addEventListener('resize', syncActiveIndicator);
    void pluginsApi
      .settingPages()
      .then((pages) => {
        pluginPages.value = pages;
        void nextTick(() => syncActiveIndicator());
      })
      .catch(() => {
        pluginPages.value = [];
      });
  });
  onBeforeUnmount(() => window.removeEventListener('resize', syncActiveIndicator));
</script>

<template>
  <div class="settings-layout" :class="`is-${appearance.chrome}`">
    <aside class="settings-nav" aria-label="系统设置分类">
      <header class="settings-nav__header">
        <span class="settings-nav__symbol">
          <el-icon><SetUp /></el-icon>
        </span>
        <div>
          <p>配置中心</p>
          <h1>系统设置</h1>
        </div>
      </header>

      <div ref="navRef" class="settings-nav__groups">
        <span
          class="settings-nav__indicator"
          :class="{ 'is-ready': indicatorReady }"
          :style="indicatorStyle"
          aria-hidden="true"
        />
        <div v-for="group in groups" :key="group.label" class="settings-nav__group">
          <div class="settings-nav__group-label">{{ group.label }}</div>
          <button
            v-for="item in group.items"
            :key="item.pluginId || item.name"
            type="button"
            class="settings-nav__item"
            :class="{ 'is-active': isItemActive(item) }"
            :aria-current="isItemActive(item) ? 'page' : undefined"
            @click="navigate(item)"
          >
            <el-icon :size="17"><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </button>
        </div>
      </div>
    </aside>

    <section class="settings-layout__content">
      <router-view v-slot="{ Component, route: settingsRoute }">
        <transition :name="transitionName">
          <div :key="`${String(settingsRoute.name)}:${String(settingsRoute.params.pluginId || '')}`" class="settings-page-shell">
            <component :is="Component" />
          </div>
        </transition>
      </router-view>
    </section>
  </div>
</template>

<style scoped lang="scss">
  .settings-layout {
    display: grid;
    grid-template-columns: 224px minmax(0, 1fr);
    align-items: start;
    gap: 24px;

    &.is-editorial {
      gap: 12px;
    }

    &__content {
      position: relative;
      min-width: 0;
      max-width: 100%;
      overflow: visible;
    }

    &.is-editorial .settings-nav__header {
      flex-direction: row;
      align-items: center;
      gap: 8px;
      padding: 0 8px 6px;

      h1 {
        order: 1;
        margin: 0;
        font-size: 16px;
        font-weight: 600;
      }

      p {
        order: 0;
        margin: 0;
        font-size: 10px;
      }
    }

    &.is-editorial .settings-nav__symbol {
      display: grid;
      place-items: center;
      width: 30px;
      height: 30px;
      flex: 0 0 auto;
      border: 1px solid var(--el-border-color);
      border-radius: 6px;
      background: transparent;
      color: var(--kp-primary);
      font-size: 15px;
    }

    &.is-editorial .settings-nav {
      top: 62px;
      gap: 10px;
      padding: 0 10px 0 0;
    }

    &.is-editorial .settings-nav__groups {
      gap: 10px;
    }

    &.is-editorial .settings-nav__item {
      min-height: 34px;
      padding: 6px 8px;
    }
  }

  .settings-nav {
    position: sticky;
    top: 94px;
    min-width: 0;
    width: 100%;
    display: flex;
    flex-direction: column;
    gap: 20px;
    padding: 4px 16px 12px 0;
    border-right: 1px solid var(--kp-divider);

    &__header {
      display: flex;
      flex-direction: column;
      padding: 2px 10px 4px;

      > div {
        display: flex;
        flex-direction: column;
      }

      h1 {
        order: 0;
        margin: 0;
        font-size: 20px;
        font-weight: 650;
        letter-spacing: 0;
      }

      p {
        order: 1;
        display: block;
        margin: 2px 0 0;
        color: var(--kp-text-muted);
        font-size: 12px;
      }
    }

    &__symbol {
      display: none;
    }

    &__group {
      position: relative;
      z-index: 1;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    &__groups {
      position: relative;
      min-width: 0;
      display: flex;
      flex-direction: column;
      gap: 20px;
    }

    &__group-label {
      padding: 0 10px 5px;
      color: var(--kp-text-muted);
      font-size: 11px;
      font-weight: 650;
    }

    &__item {
      width: 100%;
      min-height: 40px;
      display: grid;
      grid-template-columns: 22px minmax(0, 1fr);
      align-items: center;
      gap: 8px;
      padding: 8px 10px;
      border: 0;
      border-radius: 6px;
      background: transparent;
      color: var(--kp-text-secondary);
      cursor: pointer;
      font: inherit;
      text-align: left;
      transition:
        background-color 0.16s ease,
        color 0.2s ease,
        transform 0.16s ease;

      &:hover {
        background: var(--kp-primary-soft);
        color: var(--kp-text);
      }

      &:active {
        transform: scale(0.985);
      }

      &.is-active {
        background: transparent;
        color: var(--kp-primary);
        font-weight: 600;
      }

      :deep(.el-icon),
      > span {
        transition: transform 0.24s cubic-bezier(0.22, 1, 0.36, 1);
      }

      &.is-active {
        :deep(.el-icon) {
          transform: translateX(1px) scale(1.08);
        }

        > span {
          transform: translateX(1px);
        }
      }
    }

    &__indicator {
      position: absolute;
      top: 0;
      left: 0;
      z-index: 0;
      border-radius: 6px;
      background: var(--kp-primary-soft);
      box-shadow: inset 3px 0 0 var(--kp-primary);
      opacity: 0;
      pointer-events: none;
      will-change: width, height, transform;
      transition:
        width 0.24s cubic-bezier(0.22, 1, 0.36, 1),
        height 0.24s cubic-bezier(0.22, 1, 0.36, 1),
        transform 0.3s cubic-bezier(0.22, 1, 0.36, 1),
        opacity 0.14s ease;

      &.is-ready {
        opacity: 1;
      }
    }
  }

  .settings-page-shell {
    min-width: 0;
    transform-origin: top center;
  }

  .settings-page-forward-enter-active,
  .settings-page-backward-enter-active {
    z-index: 1;
    transition:
      opacity 0.22s ease 0.06s,
      transform 0.3s cubic-bezier(0.22, 1, 0.36, 1);
    will-change: opacity, transform;
  }

  .settings-page-forward-leave-active,
  .settings-page-backward-leave-active {
    position: absolute;
    inset: 0;
    z-index: 0;
    width: 100%;
    pointer-events: none;
    transition:
      opacity 0.14s ease,
      transform 0.22s ease;
    will-change: opacity, transform;
  }

  .settings-page-forward-enter-from,
  .settings-page-backward-enter-from,
  .settings-page-forward-leave-to,
  .settings-page-backward-leave-to {
    opacity: 0;
  }

  .settings-page-forward-enter-from {
    transform: translate3d(20px, 4px, 0) scale(0.996);
  }

  .settings-page-forward-leave-to {
    transform: translate3d(-10px, -2px, 0) scale(0.998);
  }

  .settings-page-backward-enter-from {
    transform: translate3d(-20px, 4px, 0) scale(0.996);
  }

  .settings-page-backward-leave-to {
    transform: translate3d(10px, -2px, 0) scale(0.998);
  }

  @media (max-width: 980px) {
    .settings-layout {
      grid-template-columns: 1fr;
      gap: 16px;
    }

    .settings-nav {
      position: static;
      display: block;
      padding: 0 0 14px;
      border-right: 0;
      border-bottom: 1px solid var(--kp-divider);

      &__header {
        margin-bottom: 12px;
      }

      &__groups {
        width: 100%;
        display: block;
        overflow-x: auto;
        white-space: nowrap;
        overscroll-behavior-x: contain;
        scrollbar-width: thin;
      }

      &__group {
        display: inline-flex;
        flex-direction: row;
        vertical-align: top;
        margin-right: 14px;
      }

      &__group-label {
        display: none;
      }

      &__item {
        width: auto;
        grid-template-columns: 20px auto;
      }
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .settings-page-forward-enter-active,
    .settings-page-forward-leave-active,
    .settings-page-backward-enter-active,
    .settings-page-backward-leave-active,
    .settings-nav__item,
    .settings-nav__item :deep(.el-icon),
    .settings-nav__item > span,
    .settings-nav__indicator {
      transition-duration: 0.01ms;
      transition-delay: 0ms;
    }
  }
</style>
