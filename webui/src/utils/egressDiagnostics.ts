import { t } from '@/i18n';
import type { ProxyProbe } from '@/api/proxyChannels';

export function probeFailureMessage(probe: ProxyProbe): string {
  switch (probe.errorCode) {
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
      return t('代理连接失败，请检查节点连通性及 Mihomo 监听端口。');
    case 'PROBE_DNS_FAILED':
      return t('域名解析失败，请检查代理链路的 DNS 配置。');
    case 'PROBE_TLS_FAILED':
      return t('出口探测 TLS 握手或证书验证失败，请检查代理链路。');
    case 'PROBE_HTTP_FAILED':
      return t('探测服务返回异常状态，不代表节点一定不可用，请稍后重试。');
    case 'PROBE_INVALID_RESPONSE':
      return t('探测服务没有返回有效的出口 IP。');
    case 'PROBE_REQUEST_FAILED':
      return t('测试请求未完成，请检查后端连接后重试。');
    default:
      return probe.error || t('出口探测失败，请检查节点及代理组件状态。');
  }
}
