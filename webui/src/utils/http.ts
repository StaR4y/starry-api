import { t } from '@/i18n';
/**
 * 全局 HTTP 客户端。
 *
 * 职责：
 * 1. 统一注入 Authorization 头（adm-... 会话令牌）。
 * 2. 解包 `ApiResponse` 并把业务错误转换成 Error，避免 view 层重复判断 success。
 * 3. 401 时自动登出并跳转登录页。
 * 4. 403 + FIRST_LOGIN_PASSWORD_CHANGE_REQUIRED 时引导跳转改密页。
 */

import axios, { type AxiosInstance, type InternalAxiosRequestConfig, AxiosError } from 'axios';
import { ElMessage } from 'element-plus';
import type { ApiResponse } from '@/types/api';

const TOKEN_STORAGE_KEY = 'kp.adminToken';

export function getStoredToken(): string | null {
  return localStorage.getItem(TOKEN_STORAGE_KEY) || sessionStorage.getItem(TOKEN_STORAGE_KEY);
}

export function setStoredToken(token: string | null, remember = true) {
  if (token) {
    if (remember) {
      localStorage.setItem(TOKEN_STORAGE_KEY, token);
      sessionStorage.removeItem(TOKEN_STORAGE_KEY);
    } else {
      sessionStorage.setItem(TOKEN_STORAGE_KEY, token);
      localStorage.removeItem(TOKEN_STORAGE_KEY);
    }
  } else {
    localStorage.removeItem(TOKEN_STORAGE_KEY);
    sessionStorage.removeItem(TOKEN_STORAGE_KEY);
  }
}

const http: AxiosInstance = axios.create({
  baseURL: '/',
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
});

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getStoredToken();
  if (token) {
    config.headers = config.headers ?? {};
    config.headers.Authorization = `Bearer ${token}`;
  }
  if (config.data instanceof FormData) {
    delete config.headers['Content-Type'];
  }
  return config;
});

/** 业务错误：携带后端返回的 error.code，便于上层做差异化处理；data 是错误响应附带的详情（例如阻止删除的记录） */
export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public status?: number,
    public data?: unknown,
  ) {
    super(message);
  }
}

const MAINTENANCE_ERROR_CODE = 'SYSTEM_MAINTENANCE';

function redirectToMaintenance() {
  if (location.hash.startsWith('#/maintenance')) return;
  const currentPath = location.hash.replace(/^#/, '') || '/';
  location.hash = `#/maintenance?redirect=${encodeURIComponent(currentPath)}`;
}

async function errorPayload(
  data: ApiResponse<unknown> | Blob | undefined,
): Promise<ApiResponse<unknown>['error'] | undefined> {
  if (data instanceof Blob) {
    if (!data.type.toLowerCase().includes('json')) return undefined;
    try {
      const parsed = JSON.parse(await data.text()) as ApiResponse<unknown>;
      return parsed.error;
    } catch {
      return undefined;
    }
  }
  return data?.error;
}

/** 不需要走全局错误提示的接口，由调用方自行处理 */
const SILENT_PATHS = [
  '/auth/login',
  '/auth/me',
  '/auth/permissions',
  '/auth/register',
  '/auth/send-verify-code',
  '/auth/password',
  '/auth/registration-settings',
  '/auth/smtp-verify',
  '/admin/data-transfer/security/unlock',
  '/system/status',
  '/system/appearance',
  '/system/plugin-widgets',
  '/system/model-catalog-display',
  '/system/model-ranking',
  '/assistant/settings',
  '/assistant/chat',
  '/system/plugin-settings',
];

http.interceptors.response.use(
  (response) => {
    const responseError = (response.data as ApiResponse<unknown> | undefined)?.error;
    if (responseError?.code === MAINTENANCE_ERROR_CODE) {
      redirectToMaintenance();
    }
    return response;
  },
  async (err: AxiosError<ApiResponse<unknown> | Blob>) => {
    const status = err.response?.status ?? 0;
    const apiError = await errorPayload(err.response?.data);
    const code = apiError?.code ?? 'NETWORK_ERROR';
    const url = err.config?.url ?? '';
    const message = t(
      payloadTooLargeMessage(status, code, url, apiError?.message) ??
        apiError?.message ??
        err.message ??
        t('请求失败'),
    );

    if (code === MAINTENANCE_ERROR_CODE) {
      redirectToMaintenance();
    } else if (status === 401) {
      setStoredToken(null);
      // 同步清掉内存里的登录态，否则路由守卫仍认为已登录，会把 #/login 又弹回首页，形成 401 死循环。
      // 动态引入以避开 store → api → http 的循环依赖。
      try {
        const { useAuthStore } = await import('@/stores/auth');
        useAuthStore().logout({ remote: false });
      } catch {
        /* 即使 store 不可用，下面的跳转仍然生效 */
      }
      // 用 hash 跳转，避免与 router 强耦合
      if (!location.hash.startsWith('#/login')) {
        location.hash = '#/login';
      }
    }

    const silent =
      SILENT_PATHS.some((p) => url.endsWith(p)) ||
      url.includes('/system/model-ranking/') ||
      url.includes('/admin/accounts/chatgpt-oauth') ||
      url.includes('/admin/usage-tracker/me') ||
      url.includes('/admin/logs/me') ||
      url.includes('/admin/source-tiers/') ||
      url.includes('/plugin-assets/');
    // 这些错误由页面自行展示：409 列出仍绑定节点的账号，默认限制的校验错误显示在编辑框内
    const shownByCaller =
      code === 'PROXY_NODE_IN_USE' ||
      (url.includes('/admin/accounts/allocation-defaults') &&
        (code === 'VALIDATION_ERROR' || code === 'ALLOCATION_DEFAULTS_INVALID'));
    if (!silent && !shownByCaller && code !== MAINTENANCE_ERROR_CODE) {
      ElMessage.error(message);
    }
    const data = err.response?.data instanceof Blob ? undefined : err.response?.data?.data;
    return Promise.reject(new ApiError(code, message, status, data ?? undefined));
  },
);

/**
 * 解包响应：返回 `data` 字段，失败时抛出 ApiError。
 * 业务代码应使用此函数而非直接访问 axios response。
 */
export async function unwrap<T>(promise: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  const { data } = await promise;
  if (!data.success) {
    if (data.error?.code === MAINTENANCE_ERROR_CODE) {
      redirectToMaintenance();
    }
    throw new ApiError(data.error?.code ?? 'UNKNOWN', t(data.error?.message ?? '请求失败'));
  }
  return data.data;
}

function payloadTooLargeMessage(
  status: number,
  code: string,
  url: string,
  apiMessage: string | undefined,
): string | undefined {
  if (code === 'MODEL_RANKING_REFRESH_FAILED' || code === 'MODEL_PRICE_REFRESH_FAILED') {
    return t('同步失败，请检查数据源地址或服务器网络');
  }
  if (code === 'MODEL_RANKING_REFRESH_IN_PROGRESS' || code === 'MODEL_PRICE_REFRESH_IN_PROGRESS') {
    return t('正在同步，请稍候');
  }
  if (code === 'MODEL_RANKING_URL_INVALID') return t('数据源地址必须是 http 或 https');
  if (code === 'MODEL_RANKING_MAX_ENTRIES_INVALID') return t('展示条数必须在 1 到 30 之间');
  const brandingUpload = url.includes('/admin/appearance/');
  if (
    code === 'BRANDING_FILE_TOO_LARGE' ||
    (brandingUpload && (status === 413 || code === 'PAYLOAD_TOO_LARGE'))
  ) {
    return t('图片过大，请压缩后再上传');
  }
  if (status === 413 && (code === 'PAYLOAD_TOO_LARGE' || code === 'NETWORK_ERROR' || !apiMessage)) {
    return t('上传内容过大');
  }
  return undefined;
}

export default http;
