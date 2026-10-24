import { locale, t } from '@/i18n';
import type { ProxyChannel, ProxyRoute } from '@/api/proxyChannels';
import { configIssueMessage, probeFailureMessage } from '@/utils/egressDiagnostics';

export type StatusTone = 'success' | 'info' | 'warning' | 'danger';
/** 状态筛选使用的分组 */
export type StatusGroup = 'active' | 'pending' | 'abnormal' | 'disabled';

export interface NodeStatus {
  key: string;
  label: string;
  tone: StatusTone;
  group: StatusGroup;
  hint: string;
}

/**
 * 把后端的 runtimeStatus、configIssue、sourcePresent 和 Mihomo 开关合并成一个对管理员有意义的状态。
 * 越靠前的原因越根本：节点已不存在或配置有误时，启用与否不再重要。
 */
export function nodeStatus(node: ProxyChannel, mihomoEnabled: boolean): NodeStatus {
  if (!node.sourcePresent) {
    return {
      key: 'removed',
      label: t('已从订阅移除'),
      tone: 'warning',
      group: 'abnormal',
      hint: t('订阅已不再包含该节点，无法使用。解绑账号后可删除。'),
    };
  }
  if (node.configIssue) {
    return {
      key: 'issue',
      label: t('配置有误'),
      tone: 'danger',
      group: 'abnormal',
      hint: configIssueMessage(node.configIssue),
    };
  }
  if (!node.enabled) {
    return { key: 'disabled', label: t('已停用'), tone: 'info', group: 'disabled', hint: '' };
  }
  if (node.route === 'MIHOMO' && !mihomoEnabled) {
    return {
      key: 'needs-mihomo',
      label: t('Mihomo 未启用'),
      tone: 'warning',
      group: 'abnormal',
      hint: t('该节点需要 Mihomo，启用组件后才能使用。'),
    };
  }
  if (node.runtimeStatus === 'ERROR') {
    return {
      key: 'error',
      label: t('不可用'),
      tone: 'danger',
      group: 'abnormal',
      hint: t('Mihomo 未能加载该节点，请查看页面顶部的组件状态及后端日志。'),
    };
  }
  if (node.runtimeStatus === 'ACTIVE') {
    return {
      key: 'active',
      label: t('可用'),
      tone: 'success',
      group: 'active',
      hint:
        node.route === 'DIRECT_PROXY'
          ? t('后端直接连接该代理，无需 Mihomo。')
          : t('Mihomo 已加载该节点。'),
    };
  }
  return {
    key: 'pending',
    label: t('待加载'),
    tone: 'info',
    group: 'pending',
    hint: t('等待 Mihomo 加载该节点。'),
  };
}

export function routeLabel(route: ProxyRoute | string): string {
  return route === 'MIHOMO' ? t('需要 Mihomo') : t('直连代理');
}

/** 节点此刻能否发起出口测试 */
export function canTest(node: ProxyChannel, mihomoEnabled: boolean): boolean {
  return (
    node.enabled &&
    node.sourcePresent &&
    !node.configIssue &&
    (node.route === 'DIRECT_PROXY' || mihomoEnabled)
  );
}

export type ProbeView =
  | { state: 'never' }
  | { state: 'success'; exitIp: string; latencyMs: number | null; at: string }
  | { state: 'failed'; message: string; at: string };

/** 节点最近一次出口探测（来自后端持久化的 lastTest*）。 */
export function probeView(node: ProxyChannel): ProbeView {
  if (!node.lastTestedAt || !node.lastTestResult) return { state: 'never' };
  if (node.lastTestResult === 'SUCCESS') {
    return {
      state: 'success',
      exitIp: node.lastTestExitIp || '—',
      latencyMs: node.lastTestLatencyMs ?? null,
      at: node.lastTestedAt,
    };
  }
  return {
    state: 'failed',
    message: probeFailureMessage({
      success: false,
      latencyMs: 0,
      errorCode: node.lastTestErrorCode ?? undefined,
      error: node.lastTestError ?? undefined,
    }),
    at: node.lastTestedAt,
  };
}

/** 相对时间；超过一周显示具体时间。 */
export function formatRelative(value: string | null | undefined): string {
  if (!value) return '—';
  const at = new Date(value);
  if (Number.isNaN(at.getTime())) return '—';
  const elapsed = Date.now() - at.getTime();
  if (elapsed < 60_000) return t('刚刚');
  if (elapsed < 3_600_000) return t('{p0} 分钟前', { p0: Math.floor(elapsed / 60_000) });
  if (elapsed < 86_400_000) return t('{p0} 小时前', { p0: Math.floor(elapsed / 3_600_000) });
  if (elapsed < 7 * 86_400_000) return t('{p0} 天前', { p0: Math.floor(elapsed / 86_400_000) });
  return at.toLocaleDateString(locale.value, { year: 'numeric', month: 'short', day: 'numeric' });
}
