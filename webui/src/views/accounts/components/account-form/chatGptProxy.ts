import { t } from '@/i18n';
import { ApiError } from '@/utils/http';

/** 后端在发起 ChatGPT 授权时校验所选代理节点，下列错误码都与所选节点有关 */
const PROXY_ERROR_MESSAGES: Record<string, () => string> = {
  PROXY_RESOURCE_NOT_FOUND: () => t('所选代理节点不存在，可能已被删除。请刷新节点列表后重新选择。'),
  PROXY_CHANNEL_UNAVAILABLE: () =>
    t('所选代理节点当前不可用（已停用、已从订阅移除或配置不受支持），请换一个节点。'),
  PROXY_RUNTIME_DISABLED: () =>
    t('所选节点需要 Mihomo 组件，但 Mihomo 未启用。请启用组件，或改选直连代理节点。'),
};

/** 与所选代理节点有关的授权失败说明；其他错误返回 null */
export function proxyErrorMessage(error: unknown): string | null {
  const message = error instanceof ApiError ? PROXY_ERROR_MESSAGES[error.code] : undefined;
  return message ? message() : null;
}

/** 「不使用代理」选项的文字：重新授权时不选节点表示沿用账号自己的绑定，新账号则是直连 */
export function emptyProxyLabel(accountId: string | null | undefined): string | undefined {
  return accountId ? t('沿用账号当前设置') : undefined;
}
