<script setup lang="ts">
  /**
   * 主布局：磨砂玻璃风格的侧边栏 + 顶栏 + 内容区。
   *
   * - 菜单数据由路由元信息派生，保持单一事实来源。
   * - 顶栏放置面包屑 / 用户操作。
   * - 内容区使用 router-view 承载子路由。
   */
  import { computed, onMounted } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { useAuthStore } from '@/stores/auth';
  import { ElMessageBox } from 'element-plus';
  import { formatNumber } from '@/utils/format';

  const route = useRoute();
  const router = useRouter();
  const auth = useAuthStore();

  /** 主菜单：分组在此集中维护，便于二次开发新增模块 */
  const rawMenus = [
    { name: 'welcome', label: '数据统计', icon: '', userOnly: true },
    { name: 'dashboard', label: '仪表盘', icon: '', adminOnly: true },
    { name: 'accounts', label: '账号管理', icon: '', adminOnly: true },
    { name: 'api-keys', label: 'API Key', icon: '' },
    { name: 'payment', label: '余额充值', icon: '', userOnly: true },
    { name: 'model-mappings', label: '模型映射', icon: '', adminOnly: true },
    { name: 'model-pricing', label: '模型价格', icon: '', adminOnly: true },
    { name: 'models', label: '模型 ID', icon: '' },
    { name: 'logs', label: '请求日志', icon: '', adminOnly: true },
    { name: 'users', label: '账号', icon: '', adminOnly: true },
    { name: 'payment-settings', label: '支付设置', icon: '', adminOnly: true },
  ];

  const menus = computed(() =>
    rawMenus.filter((item) => {
      if (item.adminOnly && !auth.isAdmin) return false;
      if (item.userOnly && auth.isAdmin) return false;
      return true;
    }),
  );
  const activeMenu = computed(() => (route.name as string) ?? 'dashboard');
  const currentTitle = computed(() => (route.meta.title as string) ?? '');
  const balanceText = computed(() => formatNumber(auth.user?.balance ?? 0));

  function handleSelect(name: string) {
    if (name !== route.name) router.push({ name });
  }

  async function handleChangePassword() {
    await router.push({ name: 'change-password' });
  }

  async function handleLogout() {
    await ElMessageBox.confirm('确认退出当前账号？', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    }).catch(() => null);
    auth.logout();
    router.push({ name: 'login' });
  }

  onMounted(() => {
    auth.refreshCurrentUser().catch(() => null);
  });
</script>

<template>
  <div class="layout">
    <aside class="layout__sider glass-panel">
      <div class="layout__brand">
        <span class="layout__brand-mark">⌁</span>
        <span class="layout__brand-text">Starry API</span>
      </div>

      <nav class="layout__menu">
        <button
          v-for="item in menus"
          :key="item.name"
          class="layout__menu-item"
          :class="{ 'is-active': activeMenu === item.name }"
          @click="handleSelect(item.name)"
        >
          <span class="layout__menu-icon">{{ item.icon }}</span>
          <span>{{ item.label }}</span>
        </button>
      </nav>

      <footer class="layout__footer text-muted">v0.1.0 · {{ new Date().getFullYear() }}</footer>
    </aside>

    <main class="layout__main">
      <header class="layout__topbar glass-panel">
        <div class="layout__crumbs">
          <span class="text-muted">控制台</span>
          <span class="layout__crumbs-sep">/</span>
          <span>{{ currentTitle }}</span>
        </div>
        <el-dropdown trigger="click" @command="(cmd: string) => cmd === 'logout' ? handleLogout() : handleChangePassword()">
          <span class="layout__user">
            <span v-if="!auth.isAdmin" class="layout__balance">余额 {{ balanceText }}</span>
            <el-avatar :size="32" class="layout__avatar">
              {{ auth.displayName?.charAt(0) }}
            </el-avatar>
            <span>{{ auth.displayName }}</span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="password">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>

      <section class="layout__content">
        <router-view />
      </section>

      <footer class="layout__main-footer text-muted">
        <span>© {{ new Date().getFullYear() }} Starry API. All rights reserved.</span>
      </footer>
    </main>
  </div>
</template>

<style scoped lang="scss">
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
    }

    &__brand {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 8px 12px 18px;
      font-weight: 700;
      font-size: 18px;
    }

    &__brand-mark {
      width: 32px;
      height: 32px;
      border-radius: 10px;
      background: linear-gradient(135deg, var(--kp-primary), var(--kp-accent));
      color: #fff;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      font-size: 18px;
    }

    &__menu {
      display: flex;
      flex-direction: column;
      gap: 4px;
      flex: 1;
    }

    &__menu-item {
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
      transition: background 0.15s ease, color 0.15s ease;

      &:hover {
        background: rgba(91, 141, 239, 0.08);
        color: var(--kp-text);
      }

      &.is-active {
        background: linear-gradient(135deg, rgba(91, 141, 239, 0.18), rgba(179, 135, 255, 0.18));
        color: var(--kp-primary);
        font-weight: 600;
      }
    }

    &__menu-icon {
      width: 22px;
      text-align: center;
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
      background: linear-gradient(135deg, var(--kp-primary), var(--kp-accent));
      color: #fff;
      font-weight: 600;
    }

    &__content {
      flex: 1;
    }

    &__main-footer {
      font-size: 12px;
      color: var(--kp-text-muted);
      text-align: center;
      padding: 10px 0 4px;
      border-top: 1px solid var(--kp-divider);
      opacity: 0.8;
      display: flex;
      justify-content: center;
      align-items: center;
    }
  }
</style>
