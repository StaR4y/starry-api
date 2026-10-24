/**
 * 代理节点选择器的展示规则：节点能否被选中、状态、最近一次出口探测。
 * 账号绑定节点后不会回退直连，所以不可用的节点只显示、不允许新选。
 */
import { t } from '@/i18n';
import type { ProxyCatalog, ProxyChannel, ProxyRoute } from '@/api/proxyChannels';
import { probeFailureMessage } from '@/utils/egressDiagnostics';

export type Tone = 'success' | 'info' | 'warning' | 'danger';

/** 节点当前不能用来承载请求的原因；可用时返回 null */
export function nodeBlocker(node: ProxyChannel, mihomoEnabled: boolean): string | null {
  if (!node.enabled) return t('节点已停用');
  if (!node.sourcePresent) return t('订阅中已不存在该节点');
  if (node.configIssue) return t('节点配置不受支持');
  if (node.route === 'MIHOMO' && !mihomoEnabled)
    return t('需要在「系统设置 → 出站代理」启用 Mihomo');
  if (node.runtimeStatus === 'ERROR') return t('节点运行异常');
  return null;
}

export function routeLabel(route: ProxyRoute): string {
  return route === 'MIHOMO' ? t('需要 Mihomo') : t('直连代理');
}

export function nodeStatus(
  node: ProxyChannel,
  mihomoEnabled: boolean,
): { tone: Tone; label: string } {
  if (!node.enabled) return { tone: 'info', label: t('已停用') };
  if (!node.sourcePresent || node.configIssue) return { tone: 'warning', label: t('不可用') };
  if (node.route === 'MIHOMO' && !mihomoEnabled) return { tone: 'warning', label: t('未启用') };
  if (node.runtimeStatus === 'ERROR') return { tone: 'danger', label: t('不可用') };
  if (node.runtimeStatus === 'ACTIVE') return { tone: 'success', label: t('已加载') };
  return { tone: 'info', label: t('待加载') };
}

export interface ProbeSummary {
  tone: Tone;
  text: string;
  /** 完整说明，用作悬停提示 */
  title: string;
}

/** 最近一次出口探测：成功显示出口 IP 与耗时，失败给出简短结论，从未探测返回 null */
export function probeSummary(node: ProxyChannel): ProbeSummary | null {
  if (!node.lastTestResult) return null;
  const when = node.lastTestedAt ? new Date(node.lastTestedAt).toLocaleString() : '';
  if (node.lastTestResult === 'SUCCESS') {
    const latency = node.lastTestLatencyMs == null ? '' : ` · ${node.lastTestLatencyMs} ms`;
    const ip = node.lastTestExitIp || t('出口探测成功');
    return { tone: 'success', text: `${ip}${latency}`, title: when };
  }
  const reason = probeFailureMessage({
    success: false,
    latencyMs: node.lastTestLatencyMs ?? 0,
    errorCode: node.lastTestErrorCode ?? undefined,
    error: node.lastTestError ?? undefined,
  });
  return { tone: 'danger', text: t('探测失败'), title: when ? `${reason} (${when})` : reason };
}

export interface ChannelGroup {
  key: string;
  label: string;
  nodes: ProxyChannel[];
}

/** 先手动节点，再按订阅分组；没有节点的分组不显示 */
export function groupChannels(catalog: ProxyCatalog | null): ChannelGroup[] {
  if (!catalog) return [];
  const manual: ProxyChannel[] = [];
  const bySubscription = new Map<string, ChannelGroup>();
  for (const subscription of catalog.subscriptions) {
    bySubscription.set(subscription.subscriptionId, {
      key: subscription.subscriptionId,
      label: subscription.name,
      nodes: [],
    });
  }
  for (const node of catalog.channels) {
    if (!node.subscriptionId) {
      manual.push(node);
      continue;
    }
    let group = bySubscription.get(node.subscriptionId);
    if (!group) {
      group = {
        key: node.subscriptionId,
        label: node.subscriptionName || t('订阅'),
        nodes: [],
      };
      bySubscription.set(node.subscriptionId, group);
    }
    group.nodes.push(node);
  }
  const groups: ChannelGroup[] = [];
  if (manual.length) groups.push({ key: 'manual', label: t('手动节点'), nodes: manual });
  for (const group of bySubscription.values()) {
    if (group.nodes.length) groups.push(group);
  }
  return groups;
}
