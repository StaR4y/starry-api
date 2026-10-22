/**
 * 路由配置。
 *
 * 模块化原则：
 * - 业务页面统一惰性加载（动态 import），首屏只装登录页与布局壳。
 * - 通过 meta.requiresAuth / meta.title 由全局守卫处理鉴权与文档标题。
 * - 路由名沿用 kebab 风格，便于在权限或菜单系统里二次扩展。
 */

import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router';
import { systemApi } from '@/api';
import { useAuthStore } from '@/stores/auth';
import { useRankingStore } from '@/stores/ranking';
import { ApiError } from '@/utils/http';

const routes: RouteRecordRaw[] = [
  {
    path: '/maintenance',
    name: 'maintenance',
    component: () => import('@/views/system/MaintenanceView.vue'),
    meta: { title: '系统维护', maintenancePage: true },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { title: '登录', anonymous: true },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { title: '注册', anonymous: true },
  },
  {
    path: '/change-password',
    name: 'change-password',
    component: () => import('@/views/auth/ChangePasswordView.vue'),
    meta: { title: '修改密码', requiresAuth: true, allowFirstLogin: true },
  },
  {
    path: '/smtp-verify',
    name: 'smtp-verify',
    component: () => import('@/views/auth/SmtpVerifyView.vue'),
    meta: { title: 'SMTP 验证', anonymous: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/DefaultLayout.vue'),
    meta: { requiresAuth: true },
    redirect: { name: 'dashboard' },
    children: [
      {
        path: 'documentation/:id?',
        name: 'documentation',
        component: () => import('@/views/documentation/DocumentationView.vue'),
        meta: { title: '使用文档', menuName: 'documentation' },
      },
      {
        path: 'tickets/new',
        name: 'ticket-create',
        component: () => import('@/views/tickets/TicketCreateView.vue'),
        meta: { title: '新建工单', menuName: 'tickets' },
      },
      {
        path: 'tickets',
        name: 'tickets',
        component: () => import('@/views/tickets/TicketView.vue'),
        meta: { title: '工单', menuName: 'tickets' },
      },
      {
        path: 'tickets/:id',
        name: 'ticket-detail',
        component: () => import('@/views/tickets/TicketView.vue'),
        meta: { title: '工单详情', menuName: 'tickets' },
      },
      {
        path: 'welcome',
        name: 'welcome',
        component: () => import('@/views/welcome/UserWelcomeView.vue'),
        meta: { title: '仪表盘' },
      },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/dashboard/DashboardView.vue'),
        meta: { title: '仪表盘', adminOnly: true },
      },
      {
        path: 'accounts',
        name: 'accounts',
        component: () => import('@/views/accounts/AccountListView.vue'),
        meta: { title: '账号管理', adminOnly: true },
      },
      {
        path: 'api-keys',
        name: 'api-keys',
        component: () => import('@/views/api-keys/ApiKeyListView.vue'),
        meta: { title: 'API Key' },
      },
      {
        path: 'payment',
        name: 'payment',
        component: () => import('@/views/payment/PaymentView.vue'),
        meta: { title: '余额充值' },
      },
      {
        path: 'coding-plans',
        name: 'coding-plans',
        component: () => import('@/views/payment/CodingPlanView.vue'),
        meta: { title: 'Coding 订阅' },
      },
      {
        path: 'model-mappings',
        name: 'model-mappings',
        component: () => import('@/views/model-mappings/ModelMappingListView.vue'),
        meta: { title: '模型映射', adminOnly: true },
      },
      {
        path: 'model-pricing',
        name: 'model-pricing',
        redirect: { name: 'models' },
        meta: { title: '模型 ID' },
      },
      {
        path: 'model-ranking',
        name: 'model-ranking',
        redirect: { name: 'model-ranking-frontierbench' },
        meta: { title: '模型天梯', menuName: 'model-ranking' },
      },
      {
        path: 'model-ranking/programbench',
        name: 'model-ranking-programbench',
        component: () => import('@/views/model-ranking/ModelRankingView.vue'),
        props: { benchmark: 'programbench' },
        meta: { title: 'ProgramBench 天梯', menuName: 'model-ranking' },
      },
      {
        path: 'model-ranking/frontierbench',
        name: 'model-ranking-frontierbench',
        component: () => import('@/views/model-ranking/ModelRankingView.vue'),
        props: { benchmark: 'frontierbench' },
        meta: { title: 'FrontierBench 天梯', menuName: 'model-ranking' },
      },
      {
        path: 'models/new',
        name: 'model-create',
        redirect: (to) => ({ name: 'settings-model-create', query: to.query }),
        meta: { title: '添加模型', adminOnly: true, menuName: 'settings' },
      },
      {
        path: 'models',
        name: 'models',
        component: () => import('@/views/models/ModelListView.vue'),
        meta: { title: '模型 ID' },
      },
      {
        path: 'news',
        name: 'news',
        component: () => import('@/views/news/NewsView.vue'),
        meta: { title: '新闻' },
      },
      {
        path: 'logs',
        name: 'logs',
        component: () => import('@/views/logs/LogListView.vue'),
        meta: { title: '请求日志', adminOnly: true },
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/views/users/UserListView.vue'),
        meta: { title: '账号', adminOnly: true },
      },
      {
        path: 'payment-settings',
        name: 'payment-settings',
        redirect: { name: 'settings-payment' },
        meta: { title: '支付设置', adminOnly: true, menuName: 'settings' },
      },
      {
        path: 'redemption-codes',
        name: 'redemption-codes',
        component: () => import('@/views/payment/AdminRedemptionCodesView.vue'),
        meta: { title: '兑换码', adminOnly: true },
      },
      {
        path: 'data-transfer',
        name: 'data-transfer',
        component: () => import('@/views/data-transfer/DataTransferView.vue'),
        meta: { title: '数据管理', adminOnly: true, rootOnly: true },
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/views/settings/SystemSettingsLayout.vue'),
        redirect: { name: 'settings-payment' },
        meta: { title: '系统设置', adminOnly: true, menuName: 'settings' },
        children: [
          {
            path: 'proxy-channels',
            name: 'settings-proxy-channels',
            component: () => import('@/views/settings/ProxyChannelsView.vue'),
            meta: { title: '出站代理' },
          },
          {
            path: 'documentation',
            name: 'settings-documentation',
            component: () => import('@/views/settings/DocumentationSettingsView.vue'),
            meta: { title: '文档管理' },
          },
          {
            path: 'tickets',
            name: 'settings-tickets',
            component: () => import('@/views/settings/TicketSettingsView.vue'),
            meta: { title: '工单通知' },
          },
          {
            path: 'payment',
            name: 'settings-payment',
            component: () => import('@/views/payment/AdminPaymentSettingsView.vue'),
            meta: { title: '支付设置' },
          },
          {
            path: 'coding-plans',
            name: 'settings-coding-plans',
            component: () => import('@/views/settings/CodingPlanSettingsView.vue'),
            meta: { title: 'Coding 套餐' },
          },
          {
            path: 'registration',
            name: 'settings-registration',
            component: () => import('@/views/settings/RegistrationSettingsView.vue'),
            meta: { title: '注册设置' },
          },
          {
            path: 'smtp',
            name: 'settings-smtp',
            component: () => import('@/views/settings/SmtpSettingsView.vue'),
            meta: { title: 'SMTP 设置' },
          },
          {
            path: 'models',
            name: 'settings-models',
            component: () => import('@/views/settings/ModelCatalogSettingsView.vue'),
            meta: { title: '模型管理' },
          },
          {
            path: 'models/new',
            name: 'settings-model-create',
            component: () => import('@/views/models/ModelCreateView.vue'),
            meta: { title: '添加模型' },
          },
          {
            path: 'account-pool',
            name: 'settings-account-pool',
            component: () => import('@/views/settings/SettingsView.vue'),
            meta: { title: '账号池设置' },
          },
          {
            path: 'appearance',
            name: 'settings-appearance',
            component: () => import('@/views/settings/AppearanceSettingsView.vue'),
            meta: { title: 'UI 自定义' },
          },
          {
            path: 'model-ranking',
            name: 'settings-model-ranking',
            component: () => import('@/views/settings/ModelRankingSettingsView.vue'),
            meta: { title: '模型天梯' },
          },
          {
            path: 'plugin/:pluginId',
            name: 'settings-plugin',
            component: () => import('@/views/settings/PluginContributedSettingsView.vue'),
            meta: { title: '插件设置' },
          },
          {
            path: 'plugins',
            name: 'settings-plugins',
            component: () => import('@/views/settings/PluginSettingsView.vue'),
            meta: { title: '插件开发', rootOnly: true },
          },
          {
            path: 'news',
            redirect: { name: 'settings-payment' },
          },
          {
            path: 'security',
            redirect: { name: 'settings-payment' },
          },
        ],
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: { name: 'dashboard' } },
];

const router = createRouter({
  history: createWebHashHistory(),
  routes,
});

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  const requiresAuth = to.matched.some((r) => r.meta.requiresAuth);
  const isAnonymous = to.meta.anonymous === true;

  if (to.name !== 'maintenance') {
    try {
      const systemStatus = await systemApi.status();
      if (systemStatus.maintenance || systemStatus.code === 'SYSTEM_MAINTENANCE') {
        return { name: 'maintenance', query: { redirect: to.fullPath } };
      }
    } catch (error) {
      if (error instanceof ApiError && error.code === 'SYSTEM_MAINTENANCE') {
        return { name: 'maintenance', query: { redirect: to.fullPath } };
      }
      // 状态探测不可用时保留原导航，由目标接口给出真实服务状态。
    }
  }

  if (requiresAuth && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }

  // 已登录用户访问登录页直接送回首页
  if (
    isAnonymous &&
    auth.isAuthenticated &&
    !auth.requirePasswordChange &&
    to.name !== 'smtp-verify'
  ) {
    return { name: auth.defaultRouteName };
  }

  // 首次登录强制改密
  if (
    auth.isAuthenticated &&
    auth.requirePasswordChange &&
    !to.meta.allowFirstLogin &&
    !isAnonymous
  ) {
    return { name: 'change-password' };
  }

  if (auth.isAuthenticated && to.meta.adminOnly === true && !auth.isAdmin) {
    return { name: auth.defaultRouteName };
  }

  if (auth.isAuthenticated && auth.isAdmin && !auth.requirePasswordChange) {
    try {
      await auth.loadPermissions(to.meta.rootOnly === true);
    } catch {
      // Missing or unavailable permission data must not expose Root-only pages.
    }
  }
  if (to.meta.rootOnly === true && !auth.isRoot) {
    return auth.isAuthenticated
      ? { name: auth.defaultRouteName }
      : { name: 'login', query: { redirect: to.fullPath } };
  }
  if (requiresAuth && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }

  if (auth.isAuthenticated && to.meta.menuName === 'model-ranking') {
    const ranking = useRankingStore();
    if (!ranking.loaded) await ranking.load();
    if (!ranking.enabled) return { name: auth.defaultRouteName };
  }

  return true;
});

export default router;
