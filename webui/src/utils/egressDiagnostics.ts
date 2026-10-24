import { t } from '@/i18n';
import type { ProxyProbe, ProxyRuntimeHealth } from '@/api/proxyChannels';

/**
 * 后端 EgressFailure 的固定诊断码对应的中文说明。
 * 返回 undefined 表示这不是已知的诊断码，由调用方决定如何兜底。
 */
function knownFailureMessage(code: string | null | undefined): string | undefined {
  switch (code) {
    case 'RUNTIME_DISABLED':
      return t('Mihomo 未启用，请启动代理组件并启用后端代理配置。');
    case 'NODE_UNAVAILABLE':
      return t('节点已停用、已从订阅移除或协议不受支持。');
    case 'NODE_NOT_ACTIVE':
      return t('节点尚未加载，请在 Mihomo 就绪后重试。');
    case 'CONTROLLER_UNREACHABLE':
      return t('无法连接 Mihomo 控制端，请检查组件是否启动及后端连接地址。');
    case 'CONTROLLER_AUTH_FAILED':
      return t('Mihomo 控制端认证失败，请检查两端控制密钥是否一致。');
    case 'CONFIG_REJECTED':
      return t('Mihomo 拒绝了节点配置，请检查协议参数或监听端口是否冲突。');
    case 'RUNTIME_FAILED':
      return t('代理配置加载失败，请检查组件状态及后端日志。');
    case 'PROBE_TIMEOUT':
      return t('出口探测超时，请检查节点连接及 api.ipify.org 的可达性。');
    case 'PROXY_CONNECT_FAILED':
      return t('代理连接失败，请检查代理地址、端口和认证信息；Mihomo 节点还需检查节点本身能否连通。');
    case 'PROBE_DNS_FAILED':
      return t('域名解析失败，请检查代理链路的 DNS 配置。');
    case 'PROBE_TLS_FAILED':
      return t('出口探测 TLS 握手或证书验证失败，请检查代理链路。');
    case 'PROBE_HTTP_FAILED':
      return t('探测服务返回异常状态，不代表节点一定不可用，请稍后重试。');
    case 'PROBE_INVALID_RESPONSE':
      return t('探测服务没有返回有效的出口 IP。');
    case 'PROBE_FAILED':
      return t('出口探测失败，请检查节点及代理组件状态。');
    case 'PROBE_REQUEST_FAILED':
      return t('测试请求未完成，请检查后端连接后重试。');
    // 账号请求（而不是出口探测）失败时的诊断码
    case 'ACCOUNT_ROUTE_UNAVAILABLE':
      return t('无法加载账号的出站线路。');
    case 'LEGACY_PROXY_INVALID':
      return t('账号的旧版代理地址不是受支持的 HTTP CONNECT 或 SOCKS5 地址。');
    case 'TLS_PROFILE_UNAVAILABLE':
      return t('TLS 组件没有启用所需的 TLS 指纹。');
    case 'TLS_COMPONENT_UNAVAILABLE':
      return t('TLS 组件未启用、未通过认证或无法连接。');
    case 'TLS_COMPONENT_REJECTED':
      return t('TLS 组件在转发前拒绝了请求，请检查桥接密钥。');
    case 'TLS_COMPONENT_FAILED':
      return t('TLS 组件未能完成转发的请求。');
    case 'PROXY_TRANSPORT_FAILED':
      return t('请求可能已发出后，经所选代理的连接中断。');
    case 'PROXY_ROUTE_SUSPENDED':
      return t('所选代理连续连接失败，其请求会暂时改用其他账号，稍后再重试。');
    default:
      return undefined;
  }
}

/**
 * 诊断码对应的中文说明；未知诊断码依次回退到后端给出的固定文本、通用提示。
 * 后端文本只来自固定的诊断码表，不会包含凭据。
 */
export function egressFailureMessage(
  code: string | null | undefined,
  fallback?: string | null,
): string {
  return knownFailureMessage(code) ?? (fallback || t('出口探测失败，请检查节点及代理组件状态。'));
}

/** 出口探测失败的说明 */
export function probeFailureMessage(probe: ProxyProbe): string {
  return egressFailureMessage(probe.errorCode, probe.error);
}

/** Mihomo 组件最近一次故障的说明；没有故障时返回 null */
export function runtimeFailureMessage(
  runtime: Pick<ProxyRuntimeHealth, 'lastFailureCode' | 'lastFailureMessage'>,
): string | null {
  if (!runtime.lastFailureCode && !runtime.lastFailureMessage) return null;
  return egressFailureMessage(runtime.lastFailureCode, runtime.lastFailureMessage);
}

/** 节点配置问题（后端固定文本）的中文说明；无法识别时原样返回 */
export function configIssueMessage(issue: string): string {
  if (issue.startsWith('Unsupported node protocol')) return t('节点协议不受支持。');
  if (issue === 'Invalid node server or port') return t('节点的服务器或端口无效。');
  if (issue === 'Node contains unsupported chaining, file, or interface options') {
    return t('节点包含不受支持的链式代理、文件或网卡选项。');
  }
  return issue;
}

/** 订阅同步结果里的逐条问题，格式为「节点名: 原因」；原因是后端的固定文本时翻译，否则原样返回 */
export function syncIssueMessage(issue: string): string {
  const unsupportedFormat = ': unsupported node format';
  if (issue.endsWith(unsupportedFormat)) {
    return `${issue.slice(0, -unsupportedFormat.length)}: ${t('节点格式不受支持。')}`;
  }
  const known =
    /^(.*): (Unsupported node protocol: .*|Invalid node server or port|Node contains unsupported chaining, file, or interface options)$/s.exec(
      issue,
    );
  return known ? `${known[1]}: ${configIssueMessage(known[2])}` : issue;
}
