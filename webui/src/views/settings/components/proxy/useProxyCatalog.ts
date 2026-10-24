import { reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { t } from '@/i18n';
import { ApiError } from '@/utils/http';
import {
  proxyChannelsApi,
  type ProxyCatalog,
  type ProxyChannel,
  type ProxyProbe,
} from '@/api/proxyChannels';

/** 同时进行的出口探测数；每次探测最长约 35 秒，过高会占满后端连接 */
const TEST_CONCURRENCY = 4;
/** 连续多少次请求本身失败（而不是探测失败）后放弃剩余的批量测试 */
const MAX_CONSECUTIVE_REQUEST_ERRORS = 3;

function emptyCatalog(): ProxyCatalog {
  return {
    channels: [],
    subscriptions: [],
    mihomoEnabled: false,
    tlsEnabled: false,
    tlsProfiles: [],
    runtime: {
      controllerReachable: null,
      activeNodes: 0,
    },
  };
}

/**
 * 出站代理目录：加载、刷新，以及单个 / 批量出口测试。
 * 全局 axios 拦截器已经弹出错误提示，这里只处理状态，不再重复提示。
 */
export function useProxyCatalog() {
  const catalog = ref<ProxyCatalog>(emptyCatalog());
  const state = ref<'loading' | 'ready' | 'error'>('loading');
  const errorMessage = ref('');
  const refreshing = ref(false);

  /** 正在测试的节点 */
  const testing = reactive(new Set<string>());
  /** 请求本身失败的测试（后端没有记录结果）：节点 -> 失败时间 */
  const requestFailures = new Map<string, number>();
  const batch = reactive({
    running: false,
    total: 0,
    done: 0,
    succeeded: 0,
    failed: 0,
    cancelled: false,
    aborted: false,
  });

  async function load(options: { silent?: boolean } = {}) {
    if (!options.silent && state.value !== 'ready') state.value = 'loading';
    refreshing.value = true;
    try {
      catalog.value = await proxyChannelsApi.catalog();
      reapplyRequestFailures();
      state.value = 'ready';
    } catch (err) {
      // 静默刷新失败时保留已有数据，仅首次加载失败才进入错误态
      if (!options.silent || state.value !== 'ready') {
        errorMessage.value = err instanceof ApiError ? err.message : t('服务暂时不可用');
        state.value = 'error';
      }
    } finally {
      refreshing.value = false;
    }
  }

  function findChannel(id: string): ProxyChannel | undefined {
    return catalog.value.channels.find((channel) => channel.channelId === id);
  }

  /** 立刻在列表里体现刚完成的探测，之后再由 load() 用持久化的结果覆盖 */
  function applyProbe(id: string, probe: ProxyProbe | null, at = Date.now()) {
    const row = findChannel(id);
    if (!row) return;
    row.lastTestedAt = new Date(at).toISOString();
    row.lastTestResult = probe?.success ? 'SUCCESS' : 'FAILED';
    row.lastTestExitIp = probe?.success ? (probe.exitIp ?? null) : null;
    row.lastTestLatencyMs = probe?.success ? probe.latencyMs : null;
    row.lastTestErrorCode = probe?.success ? null : (probe?.errorCode ?? 'PROBE_REQUEST_FAILED');
    row.lastTestError = probe?.success ? null : (probe?.error ?? null);
  }

  function reapplyRequestFailures() {
    for (const [id, at] of requestFailures) {
      const row = findChannel(id);
      const persisted = row?.lastTestedAt ? Date.parse(row.lastTestedAt) : 0;
      if (!row || persisted >= at) requestFailures.delete(id);
      else applyProbe(id, null, at);
    }
  }

  /** success / failed：探测已完成；error：测试请求本身没有完成 */
  async function testOne(id: string): Promise<'success' | 'failed' | 'error'> {
    testing.add(id);
    try {
      const probe = await proxyChannelsApi.test(id);
      requestFailures.delete(id);
      applyProbe(id, probe);
      return probe.success ? 'success' : 'failed';
    } catch {
      requestFailures.set(id, Date.now());
      applyProbe(id, null);
      return 'error';
    } finally {
      testing.delete(id);
    }
  }

  /** 以有限并发测试一组节点，结束后刷新目录并汇总提示 */
  async function runTests(ids: string[]) {
    if (batch.running || ids.length === 0) return;
    Object.assign(batch, {
      running: true,
      total: ids.length,
      done: 0,
      succeeded: 0,
      failed: 0,
      cancelled: false,
      aborted: false,
    });
    const queue = [...ids];
    let consecutiveErrors = 0;
    const worker = async () => {
      while (!batch.cancelled && queue.length > 0) {
        const id = queue.shift() as string;
        const outcome = await testOne(id);
        batch.done += 1;
        if (outcome === 'success') batch.succeeded += 1;
        else batch.failed += 1;
        consecutiveErrors = outcome === 'error' ? consecutiveErrors + 1 : 0;
        if (consecutiveErrors >= MAX_CONSECUTIVE_REQUEST_ERRORS) {
          batch.aborted = true;
          batch.cancelled = true;
        }
      }
    };
    await Promise.all(Array.from({ length: Math.min(TEST_CONCURRENCY, ids.length) }, worker));
    batch.running = false;
    await load({ silent: true });
    if (ids.length === 1) return;
    if (batch.aborted) {
      ElMessage.warning(t('测试已中止：连续多次请求未能完成'));
    } else if (batch.cancelled) {
      ElMessage.info(t('测试已停止：已完成 {p0} / {p1}', { p0: batch.done, p1: batch.total }));
    } else {
      ElMessage.success(
        t('测试完成：{p0} 个可用，{p1} 个失败', { p0: batch.succeeded, p1: batch.failed }),
      );
    }
  }

  function cancelTests() {
    batch.cancelled = true;
  }

  return {
    catalog,
    state,
    errorMessage,
    refreshing,
    testing,
    batch,
    load,
    runTests,
    cancelTests,
  };
}
