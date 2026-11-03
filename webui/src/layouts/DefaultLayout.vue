<script setup lang="ts">
  import { locale, t } from '@/i18n';
  /**
   * 主布局：侧边栏 + 顶栏 + 内容区。磨砂玻璃 / 线框由外观设置切换。
   *
   * - 菜单数据由路由元信息派生，保持单一事实来源。
   * - 顶栏放置面包屑 / 用户操作。
   * - 内容区使用 router-view 承载子路由。
   */
  import {
    computed,
    nextTick,
    onBeforeUnmount,
    onMounted,
    ref,
    watch,
    type Component,
    type CSSProperties,
  } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { useAuthStore } from '@/stores/auth';
  import { useAppearanceStore } from '@/stores/appearance';
  import { useRankingStore } from '@/stores/ranking';
  import { usePluginWidgetStore } from '@/stores/pluginWidgets';
  import SiteBrandLogo from '@/components/SiteBrandLogo.vue';
  import LanguageSelect from '@/components/LanguageSelect.vue';
  import UsageMaidAssistant from '@/components/UsageMaidAssistant.vue';
  import PluginScriptHost from '@/components/PluginScriptHost.vue';
  import LegalConsentDialog from '@/components/LegalConsentDialog.vue';
  import { ElMessageBox } from 'element-plus';
  import {
    DataAnalysis,
    Odometer,
    CreditCard,
    Key,
    Wallet,
    Monitor,
    Connection,
    Cpu,
    Document,
    Notebook,
    Reading,
    User,
    Present,
    FolderOpened,
    Operation,
    Trophy,
    Service,
  } from '@element-plus/icons-vue';
  import { formatNumber } from '@/utils/format';

// The release tag the image was built from (Dockerfile); a build from a checkout says dev.
const appVersion = import.meta.env.VITE_APP_VERSION || 'dev';

  const route = useRoute();
  const router = useRouter();
  const auth = useAuthStore();
  const appearance = useAppearanceStore();
  const ranking = useRankingStore();
  const pluginWidgets = usePluginWidgetStore();

  interface MenuItem {
    name: string;
    label: string;
    icon: Component;
    adminOnly?: boolean;
    rootOnly?: boolean;
    userOnly?: boolean;
  }

  /** 主菜单：分组在此集中维护，便于二次开发新增模块 */
  const rawMenus: MenuItem[] = [
    {
      name: 'welcome',
      get label() {
        return t('数据统计');
      },
      icon: DataAnalysis,
      userOnly: true,
    },
    {
      name: 'dashboard',
      get label() {
        return t('仪表盘');
      },
      icon: Odometer,
      adminOnly: true,
    },
    {
      name: 'accounts',
      get label() {
        return t('账号管理');
      },
      icon: CreditCard,
      adminOnly: true,
    },
    { name: 'api-keys', label: 'API Key', icon: Key },
    {
      name: 'payment',
      get label() {
        return t('余额充值');
      },
      icon: Wallet,
      userOnly: true,
    },
    { name: 'coding-plans', label: 'Coding Plan', icon: Monitor, userOnly: true },
    {
      name: 'model-mappings',
      get label() {
        return t('模型映射');
      },
      icon: Connection,
      adminOnly: true,
    },
    {
      name: 'model-ranking',
      get label() {
        return t('模型天梯');
      },
      icon: Trophy,
    },
    {
      name: 'models',
      get label() {
        return t('模型 ID');
      },
      icon: Cpu,
    },
    {
      name: 'news',
      get label() {
        return t('新闻');
      },
      icon: Notebook,
    },
    {
      name: 'documentation',
      get label() {
        return t('使用文档');
      },
      icon: Reading,
    },
    {
      name: 'tickets',
      get label() {
        return t('我的工单');
      },
      icon: Service,
      userOnly: true,
    },
    {
      name: 'tickets',
      get label() {
        return t('工单管理');
      },
      icon: Service,
      adminOnly: true,
    },
    {
      name: 'logs',
      get label() {
        return t('请求日志');
      },
      icon: Document,
      adminOnly: true,
    },
    {
      name: 'users',
      get label() {
        return t('用户管理');
      },
      icon: User,
      adminOnly: true,
    },
    {
      name: 'redemption-codes',
      get label() {
        return t('兑换码');
      },
      icon: Present,
      adminOnly: true,
    },
    {
      name: 'data-transfer',
      get label() {
        return t('数据管理');
      },
      icon: FolderOpened,
      adminOnly: true,
      rootOnly: true,
    },
    {
      name: 'settings',
      get label() {
        return t('系统设置');
      },
      icon: Operation,
      adminOnly: true,
    },
  ];

  const menus = computed(() =>
    rawMenus.filter((item) => {
      if (item.adminOnly && !auth.isAdmin) return false;
      if (item.rootOnly && !auth.isRoot) return false;
      if (item.userOnly && auth.isAdmin) return false;
      if (item.name === 'model-ranking' && !ranking.enabled) return false;
      return true;
    }),
  );
  const activeMenu = computed(
    () => (route.meta.menuName as string | undefined) ?? (route.name as string) ?? 'dashboard',
  );
  const currentTitle = computed(() => t((route.meta.title as string) ?? ''));
  const balanceText = computed(() => formatNumber(auth.user?.balance ?? 0));
  const menuRef = ref<HTMLElement | null>(null);
  const menuIndicatorReady = ref(false);
  const menuIndicatorStyle = ref<CSSProperties>({});
  const menuSignature = computed(() => menus.value.map((item) => item.name).join('|'));

  function handleSelect(name: string) {
    if (name !== activeMenu.value) void router.push({ name });
  }

  function syncMenuIndicator() {
    void nextTick(() => {
      const menu = menuRef.value;
      const activeItem = menu?.querySelector<HTMLElement>('.layout__menu-item.is-active');
      if (!menu || !activeItem) return;

      const menuRect = menu.getBoundingClientRect();
      const itemRect = activeItem.getBoundingClientRect();
      menuIndicatorStyle.value = {
        width: `${itemRect.width}px`,
        height: `${itemRect.height}px`,
        transform: `translate3d(${itemRect.left - menuRect.left + menu.scrollLeft}px, ${itemRect.top - menuRect.top + menu.scrollTop}px, 0)`,
      };

      if (!menuIndicatorReady.value) {
        window.requestAnimationFrame(() => {
          menuIndicatorReady.value = true;
        });
      }
    });
  }

  function revealActiveMenu() {
    void nextTick(() => {
      if (!window.matchMedia('(max-width: 720px)').matches) return;

      const menu = menuRef.value;
      const activeItem = menu?.querySelector<HTMLElement>('.layout__menu-item.is-active');
      if (!menu || !activeItem) return;

      const menuRect = menu.getBoundingClientRect();
      const itemRect = activeItem.getBoundingClientRect();
      const targetLeft =
        menu.scrollLeft +
        itemRect.left -
        menuRect.left -
        Math.max(0, (menu.clientWidth - itemRect.width) / 2);
      const maxScrollLeft = Math.max(0, menu.scrollWidth - menu.clientWidth);
      const behavior = window.matchMedia('(prefers-reduced-motion: reduce)').matches
        ? 'auto'
        : 'smooth';
      menu.scrollTo({
        left: Math.min(maxScrollLeft, Math.max(0, targetLeft)),
        behavior,
      });
    });
  }

  function handleViewportResize() {
    syncMenuIndicator();
    revealActiveMenu();
  }

  async function handleChangePassword() {
    await router.push({ name: 'change-password' });
  }

  async function handleLogout() {
    try {
      await ElMessageBox.confirm(t('确认退出当前账号？'), t('提示'), {
        get confirmButtonText() {
          return t('退出');
        },
        get cancelButtonText() {
          return t('取消');
        },
        type: 'warning',
      });
    } catch {
      // 点了“取消”或关闭弹窗：保持登录，不要退出
      return;
    }
    auth.logout();
    router.push({ name: 'login' });
  }

  function onWidgetPrefs() {
    pluginWidgets.reloadPrefs();
  }

  function handleUserCommand(cmd: string) {
    if (cmd === 'logout') {
      void handleLogout();
      return;
    }
    if (cmd === 'password') {
      void handleChangePassword();
    }
  }

  watch(
    [activeMenu, menuSignature, locale],
    () => {
      syncMenuIndicator();
      revealActiveMenu();
    },
    { immediate: true },
  );

  onMounted(() => {
    syncMenuIndicator();
    revealActiveMenu();
    auth.refreshCurrentUser().catch(() => null);
    void pluginWidgets.refresh();
    window.addEventListener('resize', handleViewportResize);
    window.addEventListener('starry-plugin-widget-prefs', onWidgetPrefs);
  });
  onBeforeUnmount(() => {
    window.removeEventListener('resize', handleViewportResize);
    window.removeEventListener('starry-plugin-widget-prefs', onWidgetPrefs);
  });
</script>

<template>
  <div class="layout" :class="`is-${appearance.chrome}`">
    <aside class="layout__sider" :class="{ 'glass-panel': !appearance.isEditorial }">
      <div class="layout__brand">
        <SiteBrandLogo class="layout__brand-logo" />
        <span class="layout__brand-text">{{ appearance.siteName }}</span>
      </div>

      <nav ref="menuRef" class="layout__menu">
        <span
          class="layout__menu-indicator"
          :class="{ 'is-ready': menuIndicatorReady }"
          :style="menuIndicatorStyle"
          aria-hidden="true"
        />
        <button
          v-for="item in menus"
          :key="item.name"
          type="button"
          class="layout__menu-item"
          :class="{ 'is-active': activeMenu === item.name }"
          :aria-current="activeMenu === item.name ? 'page' : undefined"
          @click="handleSelect(item.name)"
        >
          <el-icon class="layout__menu-icon" :size="16">
            <component :is="item.icon" />
          </el-icon>
          <span>{{ item.label }}</span>
        </button>
      </nav>

      <footer class="layout__footer text-muted">{{ appVersion }} · {{ new Date().getFullYear() }}</footer>
    </aside>

    <main class="layout__main">
      <header class="layout__topbar" :class="{ 'glass-panel': !appearance.isEditorial }">
        <div class="layout__crumbs">
          <span class="text-muted">{{ t('控制台') }}</span>
          <span class="layout__crumbs-sep">/</span>
          <span>{{ currentTitle }}</span>
        </div>
        <div class="layout__topbar-actions">
          <LanguageSelect />
          <el-dropdown
            trigger="click"
            popper-class="layout-user-dropdown"
            @command="handleUserCommand"
          >
            <span class="layout__user">
              <span v-if="!auth.isAdmin" class="layout__balance">{{ t('余额') }} {{ balanceText }}</span>
              <el-avatar :size="32" class="layout__avatar">
                {{ auth.displayName?.charAt(0) }}
              </el-avatar>
              <span>{{ auth.displayName }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <template v-if="pluginWidgets.available.length">
                  <el-dropdown-item disabled class="layout__widget-heading">
                    {{
                      t('桌面挂件')
                    }}
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-for="widget in pluginWidgets.available"
                    :key="widget.pluginId"
                    @click.stop
                  >
                    <div class="layout__widget-pref" @click.stop>
                      <span>{{ widget.title }}</span>
                      <el-switch
                        :model-value="pluginWidgets.isEnabled(widget.pluginId)"
                        @change="
                          (value: string | number | boolean) =>
                            pluginWidgets.setEnabled(widget.pluginId, Boolean(value))
                        "
                        @click.stop
                      />
                    </div>
                  </el-dropdown-item>
                </template>
                <el-dropdown-item command="password" :divided="pluginWidgets.available.length > 0">
                  {{ t('修改密码') }}
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>{{ t('退出登录') }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <section class="layout__content">
        <router-view v-slot="{ Component: View, route: childRoute }">
          <transition name="kp-page" mode="out-in">
            <component :is="View" :key="childRoute.matched[1]?.path ?? childRoute.path" />
          </transition>
        </router-view>
      </section>
    </main>
    <UsageMaidAssistant />
    <LegalConsentDialog />
    <PluginScriptHost />
  </div>
</template>

<style scoped lang="scss">
  .layout__topbar-actions {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-shrink: 0;
  }
  .layout__crumbs {
    min-width: 0;
    overflow-wrap: anywhere;
  }
  @media (max-width: 720px) {
    .layout__topbar-actions {
      gap: 4px;
    }
    .layout__balance {
      display: none;
    }
  }
  .layout {
    display: flex;
    min-height: 100vh;
    padding: 20px;
    gap: 20px;

    &__sider {
      width: 232px;
      flex-shrink: 0;
      padding: 24px 16px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      position: sticky;
      top: 20px;
      height: calc(100vh - 40px);
      background: rgba(255, 255, 255, var(--kp-sidebar-opacity, 0.62));
    }

    &__brand {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 8px 12px 18px;
      font-weight: 700;
      font-size: 18px;
    }

    &__brand-text {
      background: var(--kp-brand-gradient);
      -webkit-background-clip: text;
      background-clip: text;
      color: transparent;
    }

    &__brand-logo {
      width: 34px;
      height: 34px;
      flex: 0 0 auto;
      display: block;
    }

    &__menu {
      position: relative;
      display: flex;
      flex-direction: column;
      gap: 4px;
      flex: 1;
      min-width: 0;
    }

    &__menu-item {
      position: relative;
      z-index: 1;
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 10px 12px;
      border: none;
      background: transparent;
      border-radius: 12px;
      cursor: pointer;
      font-size: 14px;
      color: var(--kp-text-secondary);
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

      .layout__menu-icon,
      > span {
        transition: transform 0.24s cubic-bezier(0.22, 1, 0.36, 1);
      }

      &.is-active {
        .layout__menu-icon {
          transform: translateX(1px) scale(1.08);
        }

        > span {
          transform: translateX(1px);
        }
      }
    }

    &__menu-indicator {
      position: absolute;
      top: 0;
      left: 0;
      z-index: 0;
      border-radius: 12px;
      background: linear-gradient(
        135deg,
        var(--kp-primary-soft),
        color-mix(in srgb, var(--kp-accent) 18%, transparent)
      );
      box-shadow: 0 4px 12px var(--kp-primary-soft);
      opacity: 0;
      pointer-events: none;
      will-change: width, height, transform;
      transition:
        width 0.24s cubic-bezier(0.22, 1, 0.36, 1),
        height 0.24s cubic-bezier(0.22, 1, 0.36, 1),
        transform 0.3s cubic-bezier(0.22, 1, 0.36, 1),
        opacity 0.14s ease;

      &::before {
        content: '';
        position: absolute;
        left: -8px;
        top: 50%;
        width: 3px;
        height: 16px;
        border-radius: 2px;
        background: var(--kp-brand-gradient-y);
        transform: translateY(-50%);
      }

      &.is-ready {
        opacity: 1;
      }
    }

    &__menu-icon {
      width: 22px;
      justify-content: center;
    }

    &__footer {
      font-size: 12px;
      padding: 8px 12px;
    }

    &__main {
      flex: 1;
      min-width: 0;
      display: flex;
      flex-direction: column;
      gap: 20px;
    }

    &__topbar {
      padding: 14px 22px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      position: sticky;
      top: 20px;
      z-index: 10;
    }

    &__crumbs {
      display: flex;
      gap: 8px;
      font-size: 14px;
      align-items: center;
    }

    &__crumbs-sep {
      color: var(--kp-text-muted);
    }

    &__user {
      display: flex;
      align-items: center;
      gap: 8px;
      cursor: pointer;
    }

    &__balance {
      padding: 4px 10px;
      border-radius: 999px;
      background: rgba(16, 185, 129, 0.1);
      color: #047857;
      font-size: 12px;
      line-height: 1.4;
      white-space: nowrap;
      font-weight: 600;
    }

    &__avatar {
      background: var(--kp-brand-gradient);
      color: #fff;
      font-weight: 600;
    }

    &__content {
      flex: 1;
      width: 100%;
      max-width: 1600px;
      min-width: 0;
      margin: 0 auto;
    }


    &.is-editorial {
      padding: 10px;
      gap: 10px;

      .layout__sider,
      .layout__topbar {
        background: transparent;
        border: 1px solid var(--el-border-color);
        border-radius: 6px;
        box-shadow: none;
      }

      .layout__sider {
        padding: 12px 8px;
        top: 10px;
        height: calc(100vh - 20px);
      }

      .layout__brand {
        padding: 2px 8px 8px;
        font-size: 16px;
      }

      .layout__topbar {
        padding: 8px 12px;
        top: 10px;
      }

      .layout__main {
        gap: 10px;
      }

      .layout__menu-item {
        padding: 8px 10px;
        border-radius: 6px;
      }

      .layout__menu-indicator {
        border-radius: 6px;
        box-shadow: none;
        background: transparent;
        border: 1px solid var(--el-border-color);
      }
    }
  }

  // 内容区页面切换过渡：淡出下移 + 淡入上移
  .kp-page-enter-active,
  .kp-page-leave-active {
    transition:
      opacity 0.26s cubic-bezier(0.25, 0.8, 0.25, 1),
      transform 0.26s cubic-bezier(0.25, 0.8, 0.25, 1);
  }
  .kp-page-enter-from {
    opacity: 0;
    transform: translateY(14px);
  }
  .kp-page-leave-to {
    opacity: 0;
    transform: translateY(-8px);
  }

  // 窄屏基础降级：缩小留白、收窄侧边栏，菜单样式保持不变
  @media (max-width: 1024px) {
    .layout {
      padding: 12px;
      gap: 12px;

      &__sider {
        width: 200px;
        top: 12px;
        height: calc(100vh - 24px);
      }

      &__topbar {
        top: 12px;
      }
    }
  }

  @media (max-width: 720px) {
    .layout.is-editorial .layout__sider {
      height: auto;
    }

    .layout {
      flex-direction: column;
      min-width: 0;
      padding: 8px;
      gap: 8px;

      &__sider {
        position: static;
        width: 100%;
        height: auto;
        padding: 10px;
        overflow: hidden;
      }

      &__brand {
        padding: 2px 8px 8px;
      }

      &__brand-logo {
        width: 28px;
        height: 28px;
      }

      &__brand-text {
        white-space: nowrap;
      }

      &__menu {
        flex: 0 0 auto;
        flex-direction: row;
        padding-bottom: 2px;
        overflow-x: auto;
        overscroll-behavior-x: contain;
        scrollbar-width: thin;
      }

      &__menu-item {
        flex: 0 0 auto;
        padding: 8px 10px;
        border-radius: 6px;
        white-space: nowrap;
      }

      &__menu-indicator {
        border-radius: 6px;

        &::before {
          display: none;
        }
      }

      &__footer {
        display: none;
      }

      &__main {
        min-width: 0;
        gap: 8px;
      }

      &__topbar {
        position: static;
        min-width: 0;
        padding: 10px 12px;
        gap: 8px;
      }

      &__crumbs {
        flex: 1;
        min-width: 0;

        > span {
          white-space: nowrap;
        }

        > span:last-child {
          overflow: hidden;
          text-overflow: ellipsis;
        }
      }

      &__content {
        min-width: 0;
      }
    }
  }

  @media (max-width: 480px) {
    .layout__user > span:last-child {
      display: none;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .layout__menu-item,
    .layout__menu-item .layout__menu-icon,
    .layout__menu-item > span,
    .layout__menu-indicator {
      transition-duration: 0.01ms;
      transition-delay: 0ms;
    }
  }
</style>

<style lang="scss">
  .layout-user-dropdown {
    .layout__widget-heading {
      font-size: 11px;
      letter-spacing: 0.04em;
      color: var(--kp-text-muted);
    }

    .layout__widget-pref {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      min-width: 168px;
      width: 100%;
    }
  }
</style>
