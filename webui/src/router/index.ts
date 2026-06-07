/**
 * 路由配置。
 *
 * 模块化原则：
 * - 业务页面统一惰性加载（动态 import），首屏只装登录页与布局壳。
 * - 通过 meta.requiresAuth / meta.title 由全局守卫处理鉴权与文档标题。
 * - 路由名沿用 kebab 风格，便于在权限或菜单系统里二次扩展。
 */

import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { title: '登录', anonymous: true },
  },
  {
    path: '/change-password',
    name: 'change-password',
    component: () => import('@/views/auth/ChangePasswordView.vue'),
    meta: { title: '修改密码', requiresAuth: true, allowFirstLogin: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/DefaultLayout.vue'),
    meta: { requiresAuth: true },
    redirect: { name: 'dashboard' },
    children: [
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
        path: 'model-mappings',
        name: 'model-mappings',
        component: () => import('@/views/model-mappings/ModelMappingListView.vue'),
        meta: { title: '模型映射' },
      },
      {
        path: 'models',
        name: 'models',
        component: () => import('@/views/models/ModelListView.vue'),
        meta: { title: '模型 ID' },
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
        component: () => import('@/views/payment/AdminPaymentSettingsView.vue'),
        meta: { title: '支付设置', adminOnly: true },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: { name: 'dashboard' } },
];

const router = createRouter({
  history: createWebHashHistory(),
  routes,
});

router.beforeEach((to) => {
  const auth = useAuthStore();
  const requiresAuth = to.matched.some((r) => r.meta.requiresAuth);
  const isAnonymous = to.meta.anonymous === true;

  if (requiresAuth && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }

  // 已登录用户访问登录页直接送回首页
  if (isAnonymous && auth.isAuthenticated && !auth.requirePasswordChange) {
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

  return true;
});

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? '';
  document.title = title ? `${title} · Starry API` : 'Starry API 控制台';
});

export default router;
