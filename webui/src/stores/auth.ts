/**
 * 鉴权状态。
 *
 * 存储管理员会话令牌、当前用户信息，并提供登录/登出/初始化方法。
 * 令牌持久化由 utils/http 负责（localStorage），store 只在内存中保留快照。
 */
import { defineStore } from 'pinia';
import { authApi } from '@/api';
import { getStoredToken, setStoredToken } from '@/utils/http';
import type { AdminUser, LoginRequest, RegisterRequest } from '@/types/api';

interface AuthState {
  token: string | null;
  user: AdminUser | null;
}

const USER_STORAGE_KEY = 'kp.adminUser';

function readStoredUser(): AdminUser | null {
  try {
    const raw = localStorage.getItem(USER_STORAGE_KEY) || sessionStorage.getItem(USER_STORAGE_KEY);
    return raw ? (JSON.parse(raw) as AdminUser) : null;
  } catch {
    return null;
  }
}

function writeStoredUser(user: AdminUser | null, remember = true) {
  if (user) {
    if (remember) {
      localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(user));
      sessionStorage.removeItem(USER_STORAGE_KEY);
    } else {
      sessionStorage.setItem(USER_STORAGE_KEY, JSON.stringify(user));
      localStorage.removeItem(USER_STORAGE_KEY);
    }
  } else {
    localStorage.removeItem(USER_STORAGE_KEY);
    sessionStorage.removeItem(USER_STORAGE_KEY);
  }
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: getStoredToken(),
    user: readStoredUser(),
  }),

  getters: {
    isAuthenticated: (state) => Boolean(state.token),
    requirePasswordChange: (state) => Boolean(state.user?.firstLogin),
    isAdmin: (state) => state.user?.role === 'ADMIN',
    defaultRouteName: (state) => (state.user?.role === 'ADMIN' ? 'dashboard' : 'welcome'),
    displayName: (state) => state.user?.displayName || state.user?.username || '账号',
  },

  actions: {
    async login(payload: LoginRequest & { remember?: boolean }) {
      const { remember = true, ...loginPayload } = payload;
      const result = await authApi.login(loginPayload);
      this.token = result.accessToken;
      this.user = result.user;
      setStoredToken(result.accessToken, remember);
      writeStoredUser(result.user, remember);
      return result;
    },

    async register(payload: RegisterRequest & { remember?: boolean }) {
      const { remember = true, ...registerPayload } = payload;
      const result = await authApi.register(registerPayload);
      this.token = result.accessToken;
      this.user = result.user;
      setStoredToken(result.accessToken, remember);
      writeStoredUser(result.user, remember);
      return result;
    },

    async refreshCurrentUser() {
      if (!this.token) return null;
      const user = await authApi.me();
      this.user = user;
      const remember = localStorage.getItem('kp.adminToken') !== null;
      writeStoredUser(user, remember);
      return user;
    },

    /** 清空内存与持久化态。不主动跳转，由调用方决定路由行为。 */
    logout() {
      this.token = null;
      this.user = null;
      setStoredToken(null);
      writeStoredUser(null);
    },

    /** 用户改密成功后，标记 firstLogin = false，避免重复跳转改密页。 */
    markPasswordChanged() {
      if (this.user) {
        this.user = { ...this.user, firstLogin: false };
        const remember = localStorage.getItem('kp.adminToken') !== null;
        writeStoredUser(this.user, remember);
      }
    },
  },
});
