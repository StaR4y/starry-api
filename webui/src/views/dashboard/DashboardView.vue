<script setup lang="ts">
  /**
   * 仪表盘视图（精简版）。
   *
   * 内容：
   * - 概览指标行：服务状态 / 模型 / 账号 / API Key + 调用统计
   * - 性能指标行：CPU / 内存 / JVM 堆 / 网络（无趋势图）
   * - 模型调用趋势（基于 /admin/logs 最新 100 条聚合）+ 模型排行
   * - 用户分析：近五分钟活跃用户、用户排行（请求/Token/余额 Tab）、异常用户
   * - 最近请求 6 条
   *
   * 全部数据为只读概览，刷新按钮触发并行拉取。
   */
  import { computed, onMounted, ref } from 'vue';
  import { ElMessage } from 'element-plus';
  import { Cpu, Document, Odometer, Refresh, User } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import MetricsPanel, { type MetricPanelItem } from '@/components/MetricsPanel.vue';
  import { useAppearanceStore } from '@/stores/appearance';
  import { accountsApi, apiKeysApi, logsApi, systemApi, usersApi } from '@/api';
  import type {
    Account,
    AdminUser,
    ApiKey,
    HealthInfo,
    PerformanceMetric,
    RequestLog,
    ActiveUserUsage,
    UsageSummary,
  } from '@/types/api';
  import { formatDateTime, formatNumber } from '@/utils/format';

  const appearance = useAppearanceStore();
  const loading = ref(false);
  const health = ref<HealthInfo | null>(null);
  const accounts = ref<Account[]>([]);
  const apiKeys = ref<ApiKey[]>([]);
  const users = ref<AdminUser[]>([]);
  const logs = ref<RequestLog[]>([]);
  const performanceMetrics = ref<PerformanceMetric[]>([]);
  const usageSummary = ref<UsageSummary | null>(null);

  const chartWidth = 760;
  const chartHeight = 320;
  const chartPadding = { top: 28, right: 28, bottom: 46, left: 50 };
  const chartColors = ['#2563eb', '#10b981', '#f59e0b', '#ef5b6b', '#06b6d4'];

  interface ParsedLog {
    log: RequestLog;
    time: number;
    model: string;
  }

  interface ModelSummary {
    name: string;
    count: number;
    success: number;
    successRate: number;
    tokens: number;
    averageLatency: number | null;
    share: number;
    lastUsedAt: string | null;
    color: string;
  }

  interface UserUsageSummary {
    id: string;
    name: string;
    secondary: string;
    count: number;
    success: number;
    failed: number;
    successRate: number;
    tokens: number;
    credits: number;
    averageLatency: number | null;
    share: number;
    keyCount: number;
    lastUsedAt: string | null;
    color: string;
  }

  interface ChartBucket {
    label: string;
    timestamp: number;
  }

  interface ChartSeries {
    name: string;
    color: string;
    values: number[];
    points: string;
    areaPoints: string;
  }

  interface ChartData {
    buckets: ChartBucket[];
    series: ChartSeries[];
    maxValue: number;
    yLabels: Array<{ value: number; y: number }>;
    empty: boolean;
  }

  interface PerformanceSeriesDef {
    name: string;
    color: string;
    value: (metric: PerformanceMetric) => number | null | undefined;
  }

  interface PerformanceSeries {
    name: string;
    color: string;
    values: number[];
    points: string;
    areaPoints: string;
    latestValue: number | null;
  }

  interface PerformanceChartData {
    buckets: ChartBucket[];
    series: PerformanceSeries[];
    maxValue: number;
    yLabels: Array<{ value: number; y: number }>;
    empty: boolean;
  }

  function uptimeText(ms: number) {
    const s = Math.floor(ms / 1000);
    const days = Math.floor(s / 86400);
    const hours = Math.floor((s % 86400) / 3600);
    const minutes = Math.floor((s % 3600) / 60);
    if (days > 0) return `${days}天 ${hours}小时`;
    if (hours > 0) return `${hours}小时 ${minutes}分`;
    return `${minutes}分`;
  }

  function modelName(log: RequestLog) {
    return log.model?.trim() || '未指定模型';
  }

  function tokensOf(log: RequestLog) {
    return (log.inputTokens ?? 0) + (log.outputTokens ?? 0);
  }

  function creditsOf(log: RequestLog) {
    const value = Number(log.credits ?? 0);
    return Number.isFinite(value) ? Math.max(0, value) : 0;
  }

  function validTime(value: string) {
    const time = new Date(value).getTime();
    return Number.isNaN(time) ? null : time;
  }

  function compactNumber(value: number) {
    if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}m`;
    if (value >= 10_000) return `${Math.round(value / 1_000)}k`;
    if (value >= 1_000) return `${(value / 1_000).toFixed(1)}k`;
    return String(value);
  }

  function shortId(value: string | null | undefined) {
    if (!value) return '—';
    return value.length <= 10 ? value : `${value.slice(0, 8)}…`;
  }

  function userDisplayName(user: AdminUser) {
    return user.displayName?.trim() || user.username || shortId(user.userId);
  }

  function activeUserDisplayName(user: ActiveUserUsage) {
    return (
      user.displayName?.trim() ||
      user.username?.trim() ||
      user.email?.trim() ||
      shortId(user.userId) ||
      '匿名 / 未识别'
    );
  }

  function activeUserSecondary(user: ActiveUserUsage) {
    const parts = [
      user.email?.trim(),
      user.apiKeyCount > 0 ? `${formatNumber(user.apiKeyCount)} Key` : null,
      user.userId ? `ID ${shortId(user.userId)}` : null,
    ].filter(Boolean);
    return parts.join(' · ') || '无用户信息';
  }

  function balanceText(value: number | null | undefined) {
    if (value === null || value === undefined || Number.isNaN(value)) return '—';
    const fractionDigits = Math.abs(value) >= 100 ? 2 : 4;
    return value.toLocaleString(undefined, {
      maximumFractionDigits: fractionDigits,
    });
  }

  function percentText(value: number | null | undefined) {
    if (value === null || value === undefined || Number.isNaN(value)) return '—';
    return `${Math.round(value)}%`;
  }

  function latencyText(value: number | null | undefined) {
    if (value === null || value === undefined || Number.isNaN(value)) return '—';
    return `${Math.round(value)} ms`;
  }

  function bytesText(value: number | null | undefined) {
    if (value === null || value === undefined || Number.isNaN(value)) return '—';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let size = Math.max(0, value);
    let index = 0;
    while (size >= 1024 && index < units.length - 1) {
      size /= 1024;
      index += 1;
    }
    return `${index === 0 ? Math.round(size) : size.toFixed(size >= 10 ? 1 : 2)} ${units[index]}`;
  }

  function getChartColor(index: number) {
    return chartColors[index % chartColors.length];
  }

  function formatBucketLabel(timestamp: number, interval: number) {
    const d = new Date(timestamp);
    const pad = (n: number) => String(n).padStart(2, '0');
    if (interval >= 24 * 60 * 60 * 1000) {
      return `${d.getMonth() + 1}/${d.getDate()}`;
    }
    return `${pad(d.getHours())}:00`;
  }

  function xFor(index: number, count: number) {
    const plotWidth = chartWidth - chartPadding.left - chartPadding.right;
    return chartPadding.left + (count <= 1 ? 0 : (plotWidth * index) / (count - 1));
  }

  function yFor(value: number, maxValue: number) {
    const plotHeight = chartHeight - chartPadding.top - chartPadding.bottom;
    return chartPadding.top + (1 - value / maxValue) * plotHeight;
  }

  function buildPoints(values: number[], maxValue: number) {
    if (values.length === 0) return '';
    const coords = values.map((val, idx) => ({
      x: xFor(idx, values.length),
      y: yFor(val, maxValue),
    }));

    let d = `M ${coords[0].x.toFixed(2)} ${coords[0].y.toFixed(2)}`;
    for (let i = 0; i < coords.length - 1; i++) {
      const p0 = coords[i];
      const p1 = coords[i + 1];
      const cpX1 = p0.x + (p1.x - p0.x) / 3;
      const cpY1 = p0.y;
      const cpX2 = p0.x + (2 * (p1.x - p0.x)) / 3;
      const cpY2 = p1.y;
      d += ` C ${cpX1.toFixed(2)} ${cpY1.toFixed(2)}, ${cpX2.toFixed(2)} ${cpY2.toFixed(2)}, ${p1.x.toFixed(2)} ${p1.y.toFixed(2)}`;
    }
    return d;
  }

  function buildAreaPoints(values: number[], maxValue: number) {
    const pathD = buildPoints(values, maxValue);
    if (!pathD) return '';
    const startX = xFor(0, values.length).toFixed(2);
    const endX = xFor(values.length - 1, values.length).toFixed(2);
    const baseline = yFor(0, maxValue).toFixed(2);
    return `${pathD} L ${endX} ${baseline} L ${startX} ${baseline} Z`;
  }

  function average(values: number[]) {
    if (!values.length) return null;
    return values.reduce((sum, value) => sum + value, 0) / values.length;
  }

  function parseLogs(source: RequestLog[]) {
    return source
      .map((log): ParsedLog | null => {
        const time = validTime(log.createdAt);
        if (time === null) return null;
        return { log, time, model: modelName(log) };
      })
      .filter((item): item is ParsedLog => item !== null)
      .sort((a, b) => a.time - b.time);
  }

  function summarizeModels(source: ParsedLog[]): ModelSummary[] {
    const total = source.length;
    const map = new Map<
      string,
      {
        count: number;
        success: number;
        tokens: number;
        latencies: number[];
        lastUsedAt: string | null;
      }
    >();

    for (const item of source) {
      const current = map.get(item.model) ?? {
        count: 0,
        success: 0,
        tokens: 0,
        latencies: [],
        lastUsedAt: null,
      };
      current.count += 1;
      current.success += item.log.success ? 1 : 0;
      current.tokens += tokensOf(item.log);
      if (item.log.latencyMs !== null && item.log.latencyMs !== undefined) {
        current.latencies.push(item.log.latencyMs);
      }
      if (!current.lastUsedAt || item.time > (validTime(current.lastUsedAt) ?? 0)) {
        current.lastUsedAt = item.log.createdAt;
      }
      map.set(item.model, current);
    }

    return Array.from(map.entries())
      .map(([name, item], index) => ({
        name,
        count: item.count,
        success: item.success,
        successRate: item.count ? (item.success / item.count) * 100 : 0,
        tokens: item.tokens,
        averageLatency: average(item.latencies),
        share: total ? (item.count / total) * 100 : 0,
        lastUsedAt: item.lastUsedAt,
        color: getChartColor(index),
      }))
      .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name))
      .map((item, index) => ({ ...item, color: getChartColor(index) }));
  }

  function summarizeUsers(source: ParsedLog[]): UserUsageSummary[] {
    const total = source.length;
    const apiKeyById = new Map(apiKeys.value.map((key) => [key.keyId, key]));
    const userById = new Map(users.value.map((user) => [user.userId, user]));
    const map = new Map<
      string,
      {
        name: string;
        secondary: string;
        count: number;
        success: number;
        failed: number;
        tokens: number;
        credits: number;
        latencies: number[];
        keyIds: Set<string>;
        lastUsedAt: string | null;
      }
    >();

    for (const item of source) {
      const apiKey = item.log.apiKeyId ? apiKeyById.get(item.log.apiKeyId) : null;
      const user = apiKey?.userId ? userById.get(apiKey.userId) : null;
      const groupId = user?.userId || apiKey?.userId || item.log.apiKeyId || 'anonymous';
      const fallbackKeyName = apiKey?.name?.trim() || `API Key ${shortId(item.log.apiKeyId)}`;
      const name = user
        ? userDisplayName(user)
        : item.log.apiKeyId
          ? fallbackKeyName
          : '匿名 / 未识别';
      const secondary = user
        ? user.email || `用户 ${shortId(user.userId)}`
        : apiKey
          ? `${apiKey.keyPrefix || shortId(apiKey.keyId)} · ${apiKey.name}`
          : item.log.apiKeyId
            ? `未匹配 API Key ${shortId(item.log.apiKeyId)}`
            : '无 API Key 信息';
      const current = map.get(groupId) ?? {
        name,
        secondary,
        count: 0,
        success: 0,
        failed: 0,
        tokens: 0,
        credits: 0,
        latencies: [],
        keyIds: new Set<string>(),
        lastUsedAt: null,
      };

      current.count += 1;
      if (item.log.success) {
        current.success += 1;
      } else {
        current.failed += 1;
      }
      current.tokens += tokensOf(item.log);
      current.credits += creditsOf(item.log);
      if (item.log.apiKeyId) {
        current.keyIds.add(item.log.apiKeyId);
      }
      if (item.log.latencyMs !== null && item.log.latencyMs !== undefined) {
        current.latencies.push(item.log.latencyMs);
      }
      if (!current.lastUsedAt || item.time > (validTime(current.lastUsedAt) ?? 0)) {
        current.lastUsedAt = item.log.createdAt;
      }
      map.set(groupId, current);
    }

    return Array.from(map.entries())
      .map(([id, item], index) => ({
        id,
        name: item.name,
        secondary: item.secondary,
        count: item.count,
        success: item.success,
        failed: item.failed,
        successRate: item.count ? (item.success / item.count) * 100 : 0,
        tokens: item.tokens,
        credits: item.credits,
        averageLatency: average(item.latencies),
        share: total ? (item.count / total) * 100 : 0,
        keyCount: item.keyIds.size,
        lastUsedAt: item.lastUsedAt,
        color: getChartColor(index),
      }))
      .sort((a, b) => b.count - a.count || b.tokens - a.tokens || a.name.localeCompare(b.name))
      .map((item, index) => ({ ...item, color: getChartColor(index) }));
  }

  function barWidth(value: number, maxValue: number) {
    if (maxValue <= 0 || value <= 0) return '0%';
    return `${Math.max((value / maxValue) * 100, 4)}%`;
  }

  function buildChart(source: RequestLog[]): ChartData {
    const parsed = parseLogs(source);
    const bucketCount = 12;
    const minInterval = 60 * 60 * 1000;
    const end = parsed.at(-1)?.time ?? Date.now();
    const first = parsed[0]?.time ?? end - minInterval * (bucketCount - 1);
    const interval = Math.max(Math.ceil((end - first) / (bucketCount - 1)), minInterval);
    const start = end - interval * (bucketCount - 1);
    const buckets = Array.from({ length: bucketCount }, (_, index) => {
      const timestamp = start + interval * index;
      return { timestamp, label: formatBucketLabel(timestamp, interval) };
    });
    const summaries = summarizeModels(parsed).slice(0, 4);
    const modelSet = new Set(summaries.map((item) => item.name));
    const valuesByModel = new Map(
      summaries.map((item) => [item.name, Array(bucketCount).fill(0) as number[]]),
    );

    for (const item of parsed) {
      if (!modelSet.has(item.model) || item.time < start) continue;
      const bucketIndex = Math.min(
        bucketCount - 1,
        Math.max(0, Math.floor((item.time - start) / interval)),
      );
      valuesByModel.get(item.model)![bucketIndex] += 1;
    }

    const maxValue = Math.max(1, ...Array.from(valuesByModel.values()).flat());
    const yValues = Array.from(new Set([maxValue, Math.ceil(maxValue / 2), 0]));
    const series = summaries.map((summary) => {
      const values = valuesByModel.get(summary.name) ?? Array(bucketCount).fill(0);
      return {
        name: summary.name,
        color: summary.color,
        values,
        points: buildPoints(values, maxValue),
        areaPoints: buildAreaPoints(values, maxValue),
      };
    });

    return {
      buckets,
      series,
      maxValue,
      yLabels: yValues.map((value) => ({ value, y: yFor(value, maxValue) })),
      empty: parsed.length === 0,
    };
  }

  function buildPerformanceChart(
    defs: PerformanceSeriesDef[],
    fixedMax?: number,
  ): PerformanceChartData {
    const samples = performanceSamples.value;
    const buckets = samples.map((metric) => ({
      timestamp: validTime(metric.sampledAt) ?? Date.now(),
      label: formatBucketLabel(validTime(metric.sampledAt) ?? Date.now(), 60 * 60 * 1000),
    }));
    const seriesValues = defs.map((def) =>
      samples.map((metric) => {
        const value = def.value(metric);
        return value === null || value === undefined || Number.isNaN(value)
          ? 0
          : Math.max(0, value);
      }),
    );
    const maxValue = fixedMax ?? Math.max(1, ...seriesValues.flat());
    const yValues = fixedMax
      ? [fixedMax, fixedMax / 2, 0]
      : Array.from(new Set([maxValue, Math.ceil(maxValue / 2), 0]));
    return {
      buckets,
      series: defs.map((def, index) => {
        const values = seriesValues[index] ?? [];
        const latest = samples.length ? def.value(samples[samples.length - 1]) : null;
        return {
          name: def.name,
          color: def.color,
          values,
          points: values.length ? buildPoints(values, maxValue) : '',
          areaPoints: values.length ? buildAreaPoints(values, maxValue) : '',
          latestValue: latest === undefined || Number.isNaN(latest) ? null : latest,
        };
      }),
      maxValue,
      yLabels: yValues.map((value) => ({ value, y: yFor(value, maxValue) })),
      empty: samples.length === 0,
    };
  }

  const recentLogs = computed(() => logs.value.slice(0, 6));
  const enabledAccounts = computed(
    () => accounts.value.filter((account) => account.enabled).length,
  );
  const parsedLogs = computed(() => parseLogs(logs.value));
  const modelSummaries = computed(() => summarizeModels(parsedLogs.value));
  const userSummaries = computed(() => summarizeUsers(parsedLogs.value));
  const chartData = computed(() => buildChart(logs.value));

  const performanceSamples = computed(() =>
    performanceMetrics.value
      .filter((metric) => validTime(metric.sampledAt) !== null)
      .sort((a, b) => (validTime(a.sampledAt) ?? 0) - (validTime(b.sampledAt) ?? 0)),
  );

  /* 实例性能趋势图 */
  const hoveredResourceIndex = ref<number | null>(null);
  const hoveredMemoryIndex = ref<number | null>(null);

  const resourceChart = computed(() =>
    buildPerformanceChart(
      [
        { name: '系统 CPU', color: '#2563eb', value: (metric) => metric.systemCpuLoadPercent },
        { name: '进程 CPU', color: '#10b981', value: (metric) => metric.processCpuLoadPercent },
        { name: '物理内存', color: '#f59e0b', value: (metric) => metric.systemMemoryUsedPercent },
      ],
      100,
    ),
  );
  const memoryChart = computed(() =>
    buildPerformanceChart([
      { name: 'JVM 堆内存', color: '#06b6d4', value: (metric) => metric.heapUsedBytes },
      { name: 'JVM 非堆内存', color: '#b387ff', value: (metric) => metric.nonHeapUsedBytes },
      {
        name: '物理已用内存',
        color: '#ef5b6b',
        value: (metric) =>
          metric.systemMemoryTotalBytes && metric.systemMemoryFreeBytes
            ? metric.systemMemoryTotalBytes - metric.systemMemoryFreeBytes
            : 0,
      },
    ]),
  );

  function shouldShowPerformanceBucketLabel(chart: PerformanceChartData, index: number) {
    return index === 0 || index === chart.buckets.length - 1 || index % 20 === 0;
  }

  const hoveredUsageIndex = ref<number | null>(null);
  const totalRequests = computed(() => logs.value.length);
  const successRate = computed(() => {
    if (!logs.value.length) return null;
    return (logs.value.filter((log) => log.success).length / logs.value.length) * 100;
  });
  const averageLatencyMs = computed(() =>
    average(
      logs.value
        .map((log) => log.latencyMs)
        .filter((value): value is number => value !== null && value !== undefined),
    ),
  );

  const recentActiveUsers = computed(() => usageSummary.value?.activeUsers ?? []);
  const activeWindowStartedAt = computed(() => usageSummary.value?.activeWindowStartedAt ?? null);

  /* 顶部两条状态栏（统一 MetricsPanel 组件） */
  const overviewMetrics = computed<MetricPanelItem[]>(() => {
    const healthy = health.value?.status === 'ok';
    return [
      {
        label: '服务状态',
        value: healthy ? 'running' : 'failed',
        tone: healthy ? 'success' : 'danger',
        valueTone: healthy ? 'success' : 'danger',
        sub: health.value ? `已运行 ${uptimeText(health.value.uptime_ms)}` : '—',
      },
      {
        label: '上游账号',
        value: accounts.value.length,
        tone: 'primary',
        sub: `启用 ${enabledAccounts.value} 个`,
      },
      {
        label: '成功率',
        value: percentText(successRate.value),
        tone: 'accent',
        sub: `最近 ${formatNumber(totalRequests.value)} 次调用`,
      },
      {
        label: '平均耗时',
        value: latencyText(averageLatencyMs.value),
        tone: 'info',
        sub: '按当前日志窗口平均',
      },
    ];
  });



  const riskyUsers = computed(() =>
    [...userSummaries.value]
      .filter((user) => user.failed > 0)
      .sort((a, b) => a.successRate - b.successRate || b.failed - a.failed || b.count - a.count)
      .slice(0, 6),
  );

  /* 用户排行：同一批用户数据按 请求 / Token / 余额 三种维度切换 */
  type UserRankMode = 'requests' | 'tokens' | 'credits';
  const userRankMode = ref<UserRankMode>('requests');

  const rankedUsers = computed(() => {
    const source = [...userSummaries.value];
    if (userRankMode.value === 'tokens') {
      return source.sort((a, b) => b.tokens - a.tokens || b.count - a.count).slice(0, 8);
    }
    if (userRankMode.value === 'credits') {
      return source.sort((a, b) => b.credits - a.credits || b.count - a.count).slice(0, 8);
    }
    return source.sort((a, b) => b.count - a.count || b.tokens - a.tokens).slice(0, 8);
  });

  function rankValueOf(user: UserUsageSummary) {
    if (userRankMode.value === 'tokens') return user.tokens;
    if (userRankMode.value === 'credits') return user.credits;
    return user.count;
  }

  function rankValueText(user: UserUsageSummary) {
    if (userRankMode.value === 'tokens') return formatNumber(user.tokens);
    if (userRankMode.value === 'credits') return balanceText(user.credits);
    return `${formatNumber(user.count)} 次`;
  }

  function rankMetaText(user: UserUsageSummary) {
    if (userRankMode.value === 'tokens') return latencyText(user.averageLatency);
    if (userRankMode.value === 'credits') return `最近 ${formatDateTime(user.lastUsedAt)}`;
    return `${user.keyCount} Key · 成功率 ${percentText(user.successRate)}`;
  }

  const maxUserRankValue = computed(() =>
    Math.max(0, ...rankedUsers.value.map((user) => rankValueOf(user))),
  );

  function shouldShowBucketLabel(index: number) {
    return index === 0 || index === chartData.value.buckets.length - 1 || index % 2 === 0;
  }

  async function loadAll() {
    loading.value = true;
    try {
      const [h, a, k, u, l, p, s] = await Promise.allSettled([
        systemApi.health(),
        accountsApi.list(),
        apiKeysApi.list(),
        usersApi.list(),
        logsApi.list(),
        logsApi.performance(),
        logsApi.usageSummary(),
      ]);
      if (h.status === 'fulfilled') health.value = h.value;
      if (a.status === 'fulfilled') accounts.value = a.value;
      if (k.status === 'fulfilled') apiKeys.value = k.value;
      if (u.status === 'fulfilled') users.value = u.value;
      if (l.status === 'fulfilled') logs.value = l.value;
      if (p.status === 'fulfilled') performanceMetrics.value = p.value;
      if (s.status === 'fulfilled') usageSummary.value = s.value;
    } catch (e) {
      ElMessage.error((e as Error).message);
    } finally {
      loading.value = false;
    }
  }

  onMounted(loadAll);
</script>

<template>
  <div class="dashboard-view" :class="`is-${appearance.chrome}`">
  <PageSection title="仪表盘" subtitle="运行概览" :icon="Odometer">
    <template #actions>
      <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
    </template>
    <template #summary>
      <MetricsPanel :items="overviewMetrics" />
    </template>

    <div class="performance-charts">
      <!-- Resource Chart -->
      <div class="chart-shell">
        <div class="chart-shell__header">
          <div>
            <h3>资源占用趋势</h3>
            <p class="text-muted">CPU 与内存使用率</p>
          </div>
          <div v-if="resourceChart.series.length" class="chart-legend">
            <span v-for="series in resourceChart.series" :key="series.name">
              <i :style="{ backgroundColor: series.color }" />
              {{ series.name }}
            </span>
          </div>
        </div>

        <div class="chart-stage" :class="{ 'is-empty': resourceChart.empty }">
          <svg
            class="usage-chart"
            :viewBox="`0 0 ${chartWidth} ${chartHeight}`"
            role="img"
            aria-label="资源占用趋势折线图"
          >
            <defs>
              <linearGradient
                v-for="(series, index) in resourceChart.series"
                :id="`resource-fill-${index}`"
                :key="series.name"
                x1="0"
                x2="0"
                y1="0"
                y2="1"
              >
                <stop offset="0%" :stop-color="series.color" stop-opacity="0.22" />
                <stop offset="100%" :stop-color="series.color" stop-opacity="0.02" />
              </linearGradient>
            </defs>
            <g class="chart-grid">
              <g v-for="grid in resourceChart.yLabels" :key="grid.value">
                <line
                  :x1="chartPadding.left"
                  :x2="chartWidth - chartPadding.right"
                  :y1="grid.y"
                  :y2="grid.y"
                />
                <text :x="chartPadding.left - 12" :y="grid.y + 4" text-anchor="end">
                  {{ percentText(grid.value) }}
                </text>
              </g>
            </g>
            <g class="chart-series">
              <g v-for="(series, index) in resourceChart.series" :key="series.name">
                <path
                  class="chart-area"
                  :d="series.areaPoints"
                  :fill="`url(#resource-fill-${index})`"
                />
                <path class="chart-line" :d="series.points" :stroke="series.color" />
              </g>
            </g>

            <!-- Hover Guide Line -->
            <line
              v-if="hoveredResourceIndex !== null"
              :x1="xFor(hoveredResourceIndex, resourceChart.buckets.length)"
              :x2="xFor(hoveredResourceIndex, resourceChart.buckets.length)"
              :y1="chartPadding.top"
              :y2="chartHeight - chartPadding.bottom"
              stroke="rgba(31, 42, 68, 0.15)"
              stroke-width="1.5"
              stroke-dasharray="3 3"
            />

            <!-- Glowing Active Point Circles -->
            <g v-if="hoveredResourceIndex !== null" class="chart-active-points">
              <circle
                v-for="series in resourceChart.series"
                :key="`active-res-${series.name}`"
                :cx="xFor(hoveredResourceIndex, series.values.length)"
                :cy="yFor(series.values[hoveredResourceIndex], resourceChart.maxValue)"
                :fill="series.color"
                r="6"
                stroke="#fff"
                stroke-width="2.5"
                style="filter: drop-shadow(0 2px 4px rgba(0, 0, 0, 0.15))"
              />
            </g>

            <!-- Transparent Hotspot Columns -->
            <g class="chart-hotspots">
              <rect
                v-for="(bucket, idx) in resourceChart.buckets"
                :key="bucket.timestamp"
                :x="
                  xFor(idx, resourceChart.buckets.length) -
                    (xFor(1, resourceChart.buckets.length) - xFor(0, resourceChart.buckets.length)) /
                    2
                "
                y="0"
                :width="xFor(1, resourceChart.buckets.length) - xFor(0, resourceChart.buckets.length)"
                :height="chartHeight"
                fill="transparent"
                style="cursor: crosshair"
                @mouseenter="hoveredResourceIndex = idx"
                @mouseleave="hoveredResourceIndex = null"
              />
            </g>

            <g class="chart-x-axis">
              <template v-for="(bucket, index) in resourceChart.buckets" :key="bucket.timestamp">
                <text
                  v-if="shouldShowPerformanceBucketLabel(resourceChart, index)"
                  :x="xFor(index, resourceChart.buckets.length)"
                  :y="chartHeight - 14"
                  text-anchor="middle"
                >
                  {{ bucket.label }}
                </text>
              </template>
            </g>
          </svg>
          <div v-if="resourceChart.empty" class="chart-empty">
            <strong>暂无性能采样</strong>
          </div>

          <!-- Tooltip Panel -->
          <transition name="fade">
            <div
              v-if="hoveredResourceIndex !== null && resourceChart.buckets[hoveredResourceIndex]"
              class="chart-tooltip"
              :style="{
                left:
                  xFor(hoveredResourceIndex, resourceChart.buckets.length) > chartWidth / 2
                    ? '58px'
                    : 'auto',
                right:
                  xFor(hoveredResourceIndex, resourceChart.buckets.length) > chartWidth / 2
                    ? 'auto'
                    : '58px',
              }"
            >
              <div class="tooltip-time">
                {{ resourceChart.buckets[hoveredResourceIndex].label }}
              </div>
              <div class="tooltip-divider" />
              <div v-for="series in resourceChart.series" :key="series.name" class="tooltip-row">
                <i :style="{ backgroundColor: series.color }" class="tooltip-dot" />
                <span class="tooltip-name">{{ series.name }}</span>
                <strong class="tooltip-value">
                  {{ percentText(series.values[hoveredResourceIndex]) }}
                </strong>
              </div>
            </div>
          </transition>
        </div>
      </div>

      <!-- Memory Chart -->
      <div class="chart-shell">
        <div class="chart-shell__header">
          <div>
            <h3>内存使用趋势</h3>
            <p class="text-muted">JVM 堆、非堆与物理内存占用</p>
          </div>
          <div v-if="memoryChart.series.length" class="chart-legend">
            <span v-for="series in memoryChart.series" :key="series.name">
              <i :style="{ backgroundColor: series.color }" />
              {{ series.name }}
            </span>
          </div>
        </div>

        <div class="chart-stage" :class="{ 'is-empty': memoryChart.empty }">
          <svg
            class="usage-chart"
            :viewBox="`0 0 ${chartWidth} ${chartHeight}`"
            role="img"
            aria-label="内存使用趋势折线图"
          >
            <defs>
              <linearGradient
                v-for="(series, index) in memoryChart.series"
                :id="`memory-fill-${index}`"
                :key="series.name"
                x1="0"
                x2="0"
                y1="0"
                y2="1"
              >
                <stop offset="0%" :stop-color="series.color" stop-opacity="0.22" />
                <stop offset="100%" :stop-color="series.color" stop-opacity="0.02" />
              </linearGradient>
            </defs>
            <g class="chart-grid">
              <g v-for="grid in memoryChart.yLabels" :key="grid.value">
                <line
                  :x1="chartPadding.left"
                  :x2="chartWidth - chartPadding.right"
                  :y1="grid.y"
                  :y2="grid.y"
                />
                <text :x="chartPadding.left - 12" :y="grid.y + 4" text-anchor="end">
                  {{ bytesText(grid.value) }}
                </text>
              </g>
            </g>
            <g class="chart-series">
              <g v-for="(series, index) in memoryChart.series" :key="series.name">
                <path
                  class="chart-area"
                  :d="series.areaPoints"
                  :fill="`url(#memory-fill-${index})`"
                />
                <path class="chart-line" :d="series.points" :stroke="series.color" />
              </g>
            </g>

            <!-- Hover Guide Line -->
            <line
              v-if="hoveredMemoryIndex !== null"
              :x1="xFor(hoveredMemoryIndex, memoryChart.buckets.length)"
              :x2="xFor(hoveredMemoryIndex, memoryChart.buckets.length)"
              :y1="chartPadding.top"
              :y2="chartHeight - chartPadding.bottom"
              stroke="rgba(31, 42, 68, 0.15)"
              stroke-width="1.5"
              stroke-dasharray="3 3"
            />

            <!-- Glowing Active Point Circles -->
            <g v-if="hoveredMemoryIndex !== null" class="chart-active-points">
              <circle
                v-for="series in memoryChart.series"
                :key="`active-mem-${series.name}`"
                :cx="xFor(hoveredMemoryIndex, series.values.length)"
                :cy="yFor(series.values[hoveredMemoryIndex], memoryChart.maxValue)"
                :fill="series.color"
                r="6"
                stroke="#fff"
                stroke-width="2.5"
                style="filter: drop-shadow(0 2px 4px rgba(0, 0, 0, 0.15))"
              />
            </g>

            <!-- Transparent Hotspot Columns -->
            <g class="chart-hotspots">
              <rect
                v-for="(bucket, idx) in memoryChart.buckets"
                :key="bucket.timestamp"
                :x="
                  xFor(idx, memoryChart.buckets.length) -
                    (xFor(1, memoryChart.buckets.length) - xFor(0, memoryChart.buckets.length)) / 2
                "
                y="0"
                :width="xFor(1, memoryChart.buckets.length) - xFor(0, memoryChart.buckets.length)"
                :height="chartHeight"
                fill="transparent"
                style="cursor: crosshair"
                @mouseenter="hoveredMemoryIndex = idx"
                @mouseleave="hoveredMemoryIndex = null"
              />
            </g>

            <g class="chart-x-axis">
              <template v-for="(bucket, index) in memoryChart.buckets" :key="bucket.timestamp">
                <text
                  v-if="shouldShowPerformanceBucketLabel(memoryChart, index)"
                  :x="xFor(index, memoryChart.buckets.length)"
                  :y="chartHeight - 14"
                  text-anchor="middle"
                >
                  {{ bucket.label }}
                </text>
              </template>
            </g>
          </svg>
          <div v-if="memoryChart.empty" class="chart-empty">
            <strong>暂无内存采样</strong>
          </div>

          <!-- Tooltip Panel -->
          <transition name="fade">
            <div
              v-if="hoveredMemoryIndex !== null && memoryChart.buckets[hoveredMemoryIndex]"
              class="chart-tooltip"
              :style="{
                left:
                  xFor(hoveredMemoryIndex, memoryChart.buckets.length) > chartWidth / 2
                    ? '58px'
                    : 'auto',
                right:
                  xFor(hoveredMemoryIndex, memoryChart.buckets.length) > chartWidth / 2
                    ? 'auto'
                    : '58px',
              }"
            >
              <div class="tooltip-time">{{ memoryChart.buckets[hoveredMemoryIndex].label }}</div>
              <div class="tooltip-divider" />
              <div v-for="series in memoryChart.series" :key="series.name" class="tooltip-row">
                <i :style="{ backgroundColor: series.color }" class="tooltip-dot" />
                <span class="tooltip-name">{{ series.name }}</span>
                <strong class="tooltip-value">
                  {{ bytesText(series.values[hoveredMemoryIndex]) }}
                </strong>
              </div>
            </div>
          </transition>
        </div>
      </div>
    </div>
  </PageSection>

  <PageSection title="模型调用" subtitle="用量趋势" variant="section" :icon="Cpu">
    <div class="usage-dashboard">
      <div class="usage-dashboard__main">
        <div class="chart-shell">
          <div class="chart-shell__header">
            <div>
              <h3>模型调用趋势</h3>
              <p class="text-muted">按时间桶聚合的请求量</p>
            </div>
            <div v-if="chartData.series.length" class="chart-legend">
              <span v-for="series in chartData.series" :key="series.name">
                <i :style="{ backgroundColor: series.color }" />
                {{ series.name }}
              </span>
            </div>
          </div>

          <div class="chart-stage" :class="{ 'is-empty': chartData.empty }">
            <svg
              class="usage-chart"
              :viewBox="`0 0 ${chartWidth} ${chartHeight}`"
              role="img"
              aria-label="模型调用趋势折线图"
            >
              <defs>
                <linearGradient
                  v-for="(series, index) in chartData.series"
                  :id="`series-fill-${index}`"
                  :key="series.name"
                  x1="0"
                  x2="0"
                  y1="0"
                  y2="1"
                >
                  <stop offset="0%" :stop-color="series.color" stop-opacity="0.22" />
                  <stop offset="100%" :stop-color="series.color" stop-opacity="0.02" />
                </linearGradient>
              </defs>

              <g class="chart-grid">
                <g v-for="grid in chartData.yLabels" :key="grid.value">
                  <line
                    :x1="chartPadding.left"
                    :x2="chartWidth - chartPadding.right"
                    :y1="grid.y"
                    :y2="grid.y"
                  />
                  <text :x="chartPadding.left - 12" :y="grid.y + 4" text-anchor="end">
                    {{ compactNumber(grid.value) }}
                  </text>
                </g>
              </g>

              <g class="chart-series">
                <g v-for="(series, index) in chartData.series" :key="series.name">
                  <path
                    class="chart-area"
                    :d="series.areaPoints"
                    :fill="`url(#series-fill-${index})`"
                  />
                  <path class="chart-line" :d="series.points" :stroke="series.color" />
                </g>
              </g>

              <!-- Hover Guide Line -->
              <line
                v-if="hoveredUsageIndex !== null"
                :x1="xFor(hoveredUsageIndex, chartData.buckets.length)"
                :x2="xFor(hoveredUsageIndex, chartData.buckets.length)"
                :y1="chartPadding.top"
                :y2="chartHeight - chartPadding.bottom"
                stroke="rgba(31, 42, 68, 0.15)"
                stroke-width="1.5"
                stroke-dasharray="3 3"
              />

              <!-- Glowing Active Point Circles -->
              <g v-if="hoveredUsageIndex !== null" class="chart-active-points">
                <circle
                  v-for="series in chartData.series"
                  :key="`active-usage-${series.name}`"
                  :cx="xFor(hoveredUsageIndex, series.values.length)"
                  :cy="yFor(series.values[hoveredUsageIndex], chartData.maxValue)"
                  :fill="series.color"
                  r="6"
                  stroke="#fff"
                  stroke-width="2.5"
                  style="filter: drop-shadow(0 2px 4px rgba(0, 0, 0, 0.15))"
                />
              </g>

              <!-- Transparent Hotspot Columns -->
              <g class="chart-hotspots">
                <rect
                  v-for="(bucket, idx) in chartData.buckets"
                  :key="bucket.timestamp"
                  :x="
                    xFor(idx, chartData.buckets.length) -
                      (xFor(1, chartData.buckets.length) - xFor(0, chartData.buckets.length)) / 2
                  "
                  y="0"
                  :width="xFor(1, chartData.buckets.length) - xFor(0, chartData.buckets.length)"
                  :height="chartHeight"
                  fill="transparent"
                  style="cursor: crosshair"
                  @mouseenter="hoveredUsageIndex = idx"
                  @mouseleave="hoveredUsageIndex = null"
                />
              </g>

              <g class="chart-x-axis">
                <template v-for="(bucket, index) in chartData.buckets" :key="bucket.timestamp">
                  <text
                    v-if="shouldShowBucketLabel(index)"
                    :x="xFor(index, chartData.buckets.length)"
                    :y="chartHeight - 14"
                    text-anchor="middle"
                  >
                    {{ bucket.label }}
                  </text>
                </template>
              </g>
            </svg>

            <div v-if="chartData.empty" class="chart-empty">
              <strong>暂无调用数据</strong>
            </div>

            <!-- Tooltip Panel -->
            <transition name="fade">
              <div
                v-if="hoveredUsageIndex !== null && chartData.buckets[hoveredUsageIndex]"
                class="chart-tooltip"
                :style="{
                  left:
                    xFor(hoveredUsageIndex, chartData.buckets.length) > chartWidth / 2
                      ? '58px'
                      : 'auto',
                  right:
                    xFor(hoveredUsageIndex, chartData.buckets.length) > chartWidth / 2
                      ? 'auto'
                      : '58px',
                }"
              >
                <div class="tooltip-time">{{ chartData.buckets[hoveredUsageIndex].label }}</div>
                <div class="tooltip-divider" />
                <div v-for="series in chartData.series" :key="series.name" class="tooltip-row">
                  <i :style="{ backgroundColor: series.color }" class="tooltip-dot" />
                  <span class="tooltip-name">{{ series.name }}</span>
                  <strong class="tooltip-value">
                    {{ formatNumber(series.values[hoveredUsageIndex]) }} 次
                  </strong>
                </div>
              </div>
            </transition>
          </div>
        </div>
      </div>

      <aside class="model-rank">
        <div class="model-rank__header">
          <h3>模型排行</h3>
          <span class="text-muted">最新 {{ formatNumber(totalRequests) }} 次调用</span>
        </div>

        <div v-if="modelSummaries.length" class="model-rank__list">
          <div
            v-for="model in modelSummaries.slice(0, 5)"
            :key="model.name"
            class="model-rank__item"
          >
            <div class="model-rank__item-head">
              <span class="model-rank__dot" :style="{ backgroundColor: model.color }" />
              <strong>{{ model.name }}</strong>
              <span>{{ formatNumber(model.count) }}</span>
            </div>
            <div class="model-rank__bar">
              <span
                :style="{ width: `${Math.max(model.share, 4)}%`, backgroundColor: model.color }"
              />
            </div>
            <div class="model-rank__meta text-muted">
              <span>成功率 {{ percentText(model.successRate) }}</span>
              <span>{{ latencyText(model.averageLatency) }}</span>
            </div>
          </div>
        </div>

        <div v-else class="model-rank__empty text-muted">暂无模型调用记录</div>
      </aside>
    </div>
  </PageSection>

  <PageSection title="用户分析" subtitle="使用情况" variant="section" :icon="User">
    <div class="user-chart-grid">
      <div class="user-chart-card active-users-card">
        <div class="user-chart-card__header">
          <div>
            <h3>近五分钟活跃用户</h3>
            <p class="text-muted">
              <span v-if="activeWindowStartedAt">自 {{ formatDateTime(activeWindowStartedAt) }} 起</span>
              <span v-else>按最近 5 分钟请求统计</span>
            </p>
          </div>
          <span class="text-muted">{{ formatNumber(recentActiveUsers.length) }} 个</span>
        </div>
        <div v-if="recentActiveUsers.length" class="active-user-list">
          <div
            v-for="(user, index) in recentActiveUsers"
            :key="user.userId || user.email || `${user.lastActiveAt}-${index}`"
            class="active-user-item"
          >
            <div class="active-user-item__main">
              <strong :title="activeUserDisplayName(user)">{{ activeUserDisplayName(user) }}</strong>
              <span class="text-muted">{{ activeUserSecondary(user) }}</span>
            </div>
            <div class="active-user-item__stats">
              <span>{{ formatNumber(user.requestCount) }} 次</span>
              <span>{{ formatNumber(user.totalTokens) }} token</span>
              <small class="text-muted">最近 {{ formatDateTime(user.lastActiveAt) }}</small>
            </div>
          </div>
        </div>
        <div v-else class="user-chart-empty text-muted">最近五分钟暂无活跃用户</div>
      </div>

      <div class="user-chart-card">
        <div class="user-chart-card__header">
          <div>
            <h3>用户排行</h3>
            <p class="text-muted">当前日志窗口内按用户聚合</p>
          </div>
          <el-radio-group v-model="userRankMode" size="small">
            <el-radio-button label="requests">按请求</el-radio-button>
            <el-radio-button label="tokens">按 Token</el-radio-button>
            <el-radio-button label="credits">按余额</el-radio-button>
          </el-radio-group>
        </div>
        <div v-if="rankedUsers.length" class="user-bars">
          <div v-for="user in rankedUsers" :key="user.id" class="user-bar-row">
            <div class="user-bar-row__head">
              <div class="user-bar-row__identity">
                <span class="user-rank-dot" :style="{ backgroundColor: user.color }" />
                <strong :title="user.name">{{ user.name }}</strong>
              </div>
              <span>{{ rankValueText(user) }}</span>
            </div>
            <div class="user-bar-track">
              <span
                :style="{
                  width: barWidth(rankValueOf(user), maxUserRankValue),
                  backgroundColor: user.color,
                }"
              />
            </div>
            <div class="user-bar-row__meta text-muted">
              <span>{{ user.secondary }}</span>
              <span>{{ rankMetaText(user) }}</span>
            </div>
          </div>
        </div>
        <div v-else class="user-chart-empty text-muted">暂无用户调用数据</div>
      </div>

      <div class="user-chart-card user-risk-card">
        <div class="user-chart-card__header">
          <div>
            <h3>异常用户</h3>
            <p class="text-muted">按失败率优先排序</p>
          </div>
          <span class="text-muted">{{ formatNumber(riskyUsers.length) }} 个</span>
        </div>
        <div v-if="riskyUsers.length" class="risk-list">
          <div v-for="user in riskyUsers" :key="user.id" class="risk-item">
            <div>
              <strong :title="user.name">{{ user.name }}</strong>
              <span class="text-muted">{{ user.secondary }}</span>
            </div>
            <div class="risk-item__stats">
              <el-tag type="danger" effect="light" round>{{ user.failed }} 失败</el-tag>
              <span>成功率 {{ percentText(user.successRate) }}</span>
            </div>
          </div>
        </div>
        <div v-else class="user-chart-empty text-muted">当前日志窗口内没有失败用户</div>
      </div>
    </div>
  </PageSection>

  <PageSection title="最近请求" subtitle="最新 6 条" variant="section" :icon="Document">
    <el-table :data="recentLogs" stripe>
      <el-table-column label="时间" width="180">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column prop="path" label="路径" min-width="200" />
      <el-table-column prop="model" label="模型" min-width="180" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.success ? 'success' : 'danger'" effect="light" round>
            {{ row.statusCode || '—' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="100">
        <template #default="{ row }">{{ row.latencyMs ? `${row.latencyMs} ms` : '—' }}</template>
      </el-table-column>
    </el-table>
  </PageSection>
  </div>
</template>

<style scoped lang="scss">
  .dashboard-view {
    min-width: 0;
  }

  .performance-charts {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
  }

  .usage-dashboard {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 320px;
    gap: 18px;

    &__main {
      min-width: 0;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
  }

  .chart-shell,
  .model-rank,
  .user-chart-card {
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    background: transparent;
  }

  .chart-shell {
    min-width: 0;
    padding: 18px 18px 14px;

    &__header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 16px;
      margin-bottom: 10px;

      h3 {
        margin: 0;
        font-size: 16px;
        font-weight: 700;
        letter-spacing: 0;
      }

      p {
        margin: 2px 0 0;
        font-size: 12px;
      }
    }
  }

  .user-chart-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
  }

  .user-chart-card {
    min-width: 0;
    padding: 18px;

    &__header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 16px;

      h3 {
        margin: 0;
        font-size: 16px;
        font-weight: 700;
        letter-spacing: 0;
      }

      p {
        margin: 2px 0 0;
        font-size: 12px;
      }

      > span {
        font-size: 12px;
        white-space: nowrap;
      }
    }
  }

  .user-bars {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .user-bar-row {
    min-width: 0;

    &__head,
    &__meta {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
    }

    &__head {
      margin-bottom: 8px;

      > span {
        color: var(--kp-text);
        font-size: 13px;
        font-weight: 700;
        white-space: nowrap;
      }
    }

    &__identity {
      min-width: 0;
      display: inline-flex;
      align-items: center;
      gap: 8px;

      strong {
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        font-size: 13px;
        letter-spacing: 0;
      }
    }

    &__meta {
      margin-top: 6px;
      font-size: 12px;

      span {
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }
  }

  .user-rank-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    flex: 0 0 auto;
  }

  .user-bar-track {
    height: 9px;
    border-radius: 999px;
    background: rgba(31, 42, 68, 0.08);
    overflow: hidden;

    span {
      display: block;
      height: 100%;
      border-radius: inherit;
      transition: width 0.2s ease;
    }
  }

  .risk-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .active-users-card {
    grid-column: 1 / -1;
  }

  .active-user-list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }

  .active-user-item {
    min-width: 0;
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 12px;
    align-items: center;
    padding: 12px;
    border: 1px solid rgba(37, 99, 235, 0.1);
    border-radius: 12px;
    background: rgba(37, 99, 235, 0.045);

    &__main {
      min-width: 0;

      strong,
      span {
        display: block;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      strong {
        font-size: 13px;
        letter-spacing: 0;
      }

      span {
        margin-top: 3px;
        font-size: 12px;
      }
    }

    &__stats {
      display: flex;
      align-items: flex-end;
      flex-direction: column;
      gap: 4px;
      font-size: 12px;
      white-space: nowrap;

      span {
        color: var(--kp-text);
        font-weight: 700;
      }
    }
  }

  .risk-item {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
    gap: 12px;
    padding: 12px;
    border: 1px solid rgba(239, 91, 107, 0.11);
    border-radius: 12px;
    background: rgba(239, 91, 107, 0.045);

    strong,
    span {
      display: block;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    strong {
      font-size: 13px;
      letter-spacing: 0;
    }

    span {
      margin-top: 3px;
      font-size: 12px;
    }

    &__stats {
      display: flex;
      align-items: flex-end;
      flex-direction: column;
      gap: 6px;
      color: var(--kp-text-secondary);
      font-size: 12px;
      white-space: nowrap;

      span {
        margin-top: 0;
      }
    }
  }

  .user-chart-empty {
    min-height: 250px;
    display: flex;
    align-items: center;
    justify-content: center;
    text-align: center;
    font-size: 13px;
  }

  .chart-legend {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    flex-wrap: wrap;
    gap: 8px 14px;
    max-width: 58%;
    color: var(--kp-text-secondary);
    font-size: 12px;

    span {
      display: inline-flex;
      align-items: center;
      min-width: 0;
      gap: 6px;
      max-width: 180px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    i {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      flex: 0 0 auto;
    }
  }

  .chart-stage {
    position: relative;
    min-height: 300px;
    overflow: hidden;
  }

  .usage-chart {
    display: block;
    width: 100%;
    height: 320px;

    text {
      fill: var(--kp-text-muted);
      font-size: 12px;
      font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Helvetica Neue', Arial,
        sans-serif;
    }
  }

  .chart-grid {
    line {
      stroke: rgba(15, 23, 42, 0.06);
      stroke-width: 1;
      stroke-dasharray: 3 3;
    }
  }

  .chart-series {
    .chart-line {
      fill: none;
      stroke-width: 3;
      stroke-linecap: round;
      stroke-linejoin: round;
      filter: drop-shadow(0 4px 8px rgba(0, 0, 0, 0.05));
    }

    .chart-area {
      stroke: none;
      stroke-width: 0;
      filter: none;
    }

    circle {
      r: 3.5;
      stroke: rgba(255, 255, 255, 0.95);
      stroke-width: 1.5;
      transition:
        r 0.2s ease,
        stroke-width 0.2s ease;
    }
  }

  .chart-tooltip {
    position: absolute;
    top: 48px;
    padding: 10px 12px;
    border-radius: 8px;
    background: color-mix(in srgb, white 38%, transparent);
    border: 1px solid var(--el-border-color);
    box-shadow: none;
    pointer-events: none;
    z-index: 100;
    min-width: 150px;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .dashboard-view.is-classic .chart-tooltip {
    background: rgba(255, 255, 255, 0.92);
    backdrop-filter: blur(10px);
    -webkit-backdrop-filter: blur(10px);
    border: 1px solid var(--kp-border);
    box-shadow: 0 4px 16px rgba(31, 45, 80, 0.08);
  }

  .dashboard-view.is-editorial {
    .performance-charts,
    .user-chart-grid {
      gap: 8px;
    }

    .usage-dashboard {
      gap: 8px;

      &__main {
        gap: 8px;
      }
    }

    .chart-shell,
    .user-chart-card,
    .model-rank {
      padding: 10px 12px;
    }

    .chart-shell__header,
    .user-chart-card__header,
    .model-rank__header {
      margin-bottom: 8px;

      h3 {
        font-size: 14px;
        font-weight: 600;
      }
    }

    .chart-tooltip {
      background: color-mix(in srgb, white 38%, transparent);
      border: 1px solid var(--el-border-color);
      box-shadow: none;
    }
  }

  .tooltip-time {
    font-size: 11px;
    font-weight: 600;
    color: var(--kp-text-secondary);
  }

  .tooltip-divider {
    height: 1px;
    background: var(--kp-divider);
    margin: 2px 0;
  }

  .tooltip-row {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 11.5px;
  }

  .tooltip-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    flex-shrink: 0;
  }

  .tooltip-name {
    color: var(--kp-text-secondary);
    flex: 1;
  }

  .tooltip-value {
    color: var(--kp-text);
    font-weight: 700;
  }

  .chart-empty {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 6px;
    text-align: center;
    pointer-events: none;

    strong {
      font-size: 17px;
      letter-spacing: 0;
    }
  }

  .model-rank {
    min-width: 0;
    padding: 18px;
    align-self: stretch;

    &__header {
      display: flex;
      justify-content: space-between;
      gap: 12px;
      align-items: flex-start;
      margin-bottom: 16px;

      h3 {
        margin: 0;
        font-size: 16px;
        font-weight: 700;
        letter-spacing: 0;
      }

      span {
        font-size: 12px;
        white-space: nowrap;
      }
    }

    &__list {
      display: flex;
      flex-direction: column;
      gap: 14px;
    }

    &__item {
      min-width: 0;
    }

    &__item-head {
      display: grid;
      grid-template-columns: 10px minmax(0, 1fr) auto;
      align-items: center;
      gap: 8px;
      margin-bottom: 8px;

      strong {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        font-size: 13px;
        letter-spacing: 0;
      }

      span:last-child {
        color: var(--kp-text);
        font-weight: 700;
      }
    }

    &__dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
    }

    &__bar {
      height: 8px;
      border-radius: 999px;
      background: rgba(31, 42, 68, 0.08);
      overflow: hidden;

      span {
        display: block;
        height: 100%;
        border-radius: inherit;
      }
    }

    &__meta {
      display: flex;
      justify-content: space-between;
      gap: 10px;
      margin-top: 6px;
      font-size: 12px;
    }

    &__empty {
      min-height: 230px;
      display: flex;
      align-items: center;
      justify-content: center;
      text-align: center;
    }
  }

  @media (max-width: 1180px) {
    .usage-dashboard {
      grid-template-columns: 1fr;
    }

    .performance-charts {
      grid-template-columns: 1fr;
    }

    .user-chart-grid {
      grid-template-columns: 1fr;
    }

    .model-rank {
      align-self: auto;
    }
  }

  @media (max-width: 820px) {
    .chart-shell__header {
      flex-direction: column;
    }

    .chart-legend {
      max-width: none;
      justify-content: flex-start;
    }

    .risk-item {
      grid-template-columns: 1fr;

      &__stats {
        align-items: flex-start;
      }
    }

    .active-user-list {
      grid-template-columns: 1fr;
    }

    .active-user-item {
      grid-template-columns: 1fr;

      &__stats {
        align-items: flex-start;
      }
    }

    .usage-chart {
      min-width: 620px;
    }

    .chart-stage {
      overflow-x: auto;
    }
  }

</style>
