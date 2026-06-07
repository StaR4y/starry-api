<script setup lang="ts">
  /**
   * 仪表盘视图。
   *
   * 内容：
   * - 服务运行状态（/health）
   * - 模型调用趋势（基于 /admin/logs 最新 100 条聚合）
   * - 账号、API Key、最近请求总览
   *
   * 全部数据为只读概览，刷新按钮触发并行拉取。
   */
  import { computed, onMounted, ref } from 'vue';
  import { ElMessage } from 'element-plus';
  import PageSection from '@/components/PageSection.vue';
  import { accountsApi, apiKeysApi, logsApi, systemApi } from '@/api';
  import type { Account, ApiKey, HealthInfo, ModelInfo, PerformanceMetric, RequestLog } from '@/types/api';
  import { formatDateTime, formatNumber } from '@/utils/format';

  const loading = ref(false);
  const health = ref<HealthInfo | null>(null);
  const models = ref<ModelInfo[]>([]);
  const accounts = ref<Account[]>([]);
  const apiKeys = ref<ApiKey[]>([]);
  const logs = ref<RequestLog[]>([]);
  const performanceMetrics = ref<PerformanceMetric[]>([]);

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

  interface ChartBucket {
    label: string;
    timestamp: number;
  }

  interface ChartSeries {
    name: string;
    color: string;
    values: number[];
    total: number;
    successRate: number;
    averageLatency: number | null;
    share: number;
    lastValue: number;
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

  function rateText(value: number | null | undefined) {
    const text = bytesText(value);
    return text === '—' ? text : `${text}/s`;
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
    return values
      .map(
        (value, index) =>
          `${xFor(index, values.length).toFixed(2)},${yFor(value, maxValue).toFixed(2)}`,
      )
      .join(' ');
  }

  function buildAreaPoints(values: number[], maxValue: number) {
    const baseline = yFor(0, maxValue).toFixed(2);
    return `${xFor(0, values.length).toFixed(2)},${baseline} ${buildPoints(values, maxValue)} ${xFor(
      values.length - 1,
      values.length,
    ).toFixed(2)},${baseline}`;
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
        total: values.reduce((sum, value) => sum + value, 0),
        successRate: summary.successRate,
        averageLatency: summary.averageLatency,
        share: summary.share,
        lastValue: values.at(-1) ?? 0,
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

  function buildPerformanceChart(defs: PerformanceSeriesDef[], fixedMax?: number): PerformanceChartData {
    const samples = performanceSamples.value;
    const buckets = samples.map((metric) => ({
      timestamp: validTime(metric.sampledAt) ?? Date.now(),
      label: formatBucketLabel(validTime(metric.sampledAt) ?? Date.now(), 60 * 60 * 1000),
    }));
    const seriesValues = defs.map((def) =>
      samples.map((metric) => {
        const value = def.value(metric);
        return value === null || value === undefined || Number.isNaN(value) ? 0 : Math.max(0, value);
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
  const enabledApiKeys = computed(() => apiKeys.value.filter((key) => key.enabled).length);
  const parsedLogs = computed(() => parseLogs(logs.value));
  const modelSummaries = computed(() => summarizeModels(parsedLogs.value));
  const chartData = computed(() => buildChart(logs.value));
  const performanceSamples = computed(() =>
    performanceMetrics.value
      .filter((metric) => validTime(metric.sampledAt) !== null)
      .sort((a, b) => (validTime(a.sampledAt) ?? 0) - (validTime(b.sampledAt) ?? 0)),
  );
  const latestPerformance = computed(() => performanceSamples.value.at(-1) ?? null);
  const heapUsedPercent = computed(() => {
    const latest = latestPerformance.value;
    if (!latest?.heapUsedBytes) return null;
    const max = latest.heapMaxBytes && latest.heapMaxBytes > 0 ? latest.heapMaxBytes : latest.heapCommittedBytes;
    if (!max || max <= 0) return null;
    return (latest.heapUsedBytes / max) * 100;
  });
  const resourceChart = computed(() =>
    buildPerformanceChart(
      [
        { name: '系统 CPU', color: '#2563eb', value: (metric) => metric.systemCpuLoadPercent },
        { name: '进程 CPU', color: '#10b981', value: (metric) => metric.processCpuLoadPercent },
        { name: '系统内存', color: '#f59e0b', value: (metric) => metric.systemMemoryUsedPercent },
      ],
      100,
    ),
  );
  const networkChart = computed(() =>
    buildPerformanceChart([
      { name: '下行', color: '#06b6d4', value: (metric) => metric.networkRxBytesPerSecond },
      { name: '上行', color: '#ef5b6b', value: (metric) => metric.networkTxBytesPerSecond },
    ]),
  );
  const totalRequests = computed(() => logs.value.length);
  const totalTokens = computed(() => logs.value.reduce((sum, log) => sum + tokensOf(log), 0));
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
  const activeModelCount = computed(() => new Set(parsedLogs.value.map((log) => log.model)).size);

  function shouldShowBucketLabel(index: number) {
    return index === 0 || index === chartData.value.buckets.length - 1 || index % 2 === 0;
  }

  async function loadAll() {
    loading.value = true;
    try {
      const [h, m, a, k, l, p] = await Promise.allSettled([
        systemApi.health(),
        systemApi.models(),
        accountsApi.list(),
        apiKeysApi.list(),
        logsApi.list(),
        logsApi.performance(),
      ]);
      if (h.status === 'fulfilled') health.value = h.value;
      if (m.status === 'fulfilled') models.value = m.value;
      if (a.status === 'fulfilled') accounts.value = a.value;
      if (k.status === 'fulfilled') apiKeys.value = k.value;
      if (l.status === 'fulfilled') logs.value = l.value;
      if (p.status === 'fulfilled') performanceMetrics.value = p.value;
    } catch (e) {
      ElMessage.error((e as Error).message);
    } finally {
      loading.value = false;
    }
  }

  function shouldShowPerformanceBucketLabel(chart: PerformanceChartData, index: number) {
    return index === 0 || index === chart.buckets.length - 1 || index % 20 === 0;
  }

  onMounted(loadAll);
</script>

<template>
  <PageSection title="仪表盘" subtitle="服务运行状态与资源概览">
    <template #actions>
      <el-button :loading="loading" @click="loadAll">刷新</el-button>
    </template>

    <div class="dash-grid">
      <div class="metric glass-panel">
        <div class="metric__label">服务状态</div>
        <div class="metric__value">
          <el-tag v-if="health?.status === 'ok'" type="success" effect="light" round>运行中</el-tag>
          <el-tag v-else type="danger" effect="light" round>不可达</el-tag>
        </div>
        <div class="metric__sub text-muted">
          <span v-if="health">运行 {{ uptimeText(health.uptime_ms) }}</span>
          <span v-else>—</span>
        </div>
      </div>

      <div class="metric glass-panel">
        <div class="metric__label">可用模型</div>
        <div class="metric__value">{{ formatNumber(models.length) }}</div>
        <div class="metric__sub text-muted">/v1/models 暴露</div>
      </div>

      <div class="metric glass-panel">
        <div class="metric__label">上游账号</div>
        <div class="metric__value">{{ formatNumber(accounts.length) }}</div>
        <div class="metric__sub text-muted">启用 {{ enabledAccounts }} 个</div>
      </div>

      <div class="metric glass-panel">
        <div class="metric__label">API Key</div>
        <div class="metric__value">{{ formatNumber(apiKeys.length) }}</div>
        <div class="metric__sub text-muted">启用 {{ enabledApiKeys }} 个</div>
      </div>
    </div>
  </PageSection>

  <PageSection title="性能日志" subtitle="每 30 秒记录一次 CPU、内存与网络吞吐，展示最近 120 个采样点">
    <div class="performance-dashboard">
      <div class="performance-stats">
        <div class="performance-stat">
          <span>系统 CPU</span>
          <strong>{{ percentText(latestPerformance?.systemCpuLoadPercent) }}</strong>
          <small class="text-muted">进程 {{ percentText(latestPerformance?.processCpuLoadPercent) }}</small>
        </div>
        <div class="performance-stat">
          <span>系统内存</span>
          <strong>{{ percentText(latestPerformance?.systemMemoryUsedPercent) }}</strong>
          <small class="text-muted">
            可用 {{ bytesText(latestPerformance?.systemMemoryFreeBytes) }}
          </small>
        </div>
        <div class="performance-stat">
          <span>JVM 堆内存</span>
          <strong>{{ bytesText(latestPerformance?.heapUsedBytes) }}</strong>
          <small class="text-muted">占用 {{ percentText(heapUsedPercent) }}</small>
        </div>
        <div class="performance-stat">
          <span>网络吞吐</span>
          <strong>{{ rateText(latestPerformance?.networkRxBytesPerSecond) }}</strong>
          <small class="text-muted">上行 {{ rateText(latestPerformance?.networkTxBytesPerSecond) }}</small>
        </div>
      </div>

      <div class="performance-charts">
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
                  <polygon :points="series.areaPoints" :fill="`url(#resource-fill-${index})`" />
                  <polyline :points="series.points" :stroke="series.color" />
                  <circle
                    v-for="(value, valueIndex) in series.values"
                    :key="`${series.name}-${valueIndex}`"
                    :cx="xFor(valueIndex, series.values.length)"
                    :cy="yFor(value, resourceChart.maxValue)"
                    :fill="series.color"
                  />
                </g>
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
          </div>
        </div>

        <div class="chart-shell">
          <div class="chart-shell__header">
            <div>
              <h3>网络带宽趋势</h3>
              <p class="text-muted">网卡上下行速率</p>
            </div>
            <div v-if="networkChart.series.length" class="chart-legend">
              <span v-for="series in networkChart.series" :key="series.name">
                <i :style="{ backgroundColor: series.color }" />
                {{ series.name }}
              </span>
            </div>
          </div>

          <div class="chart-stage" :class="{ 'is-empty': networkChart.empty }">
            <svg
              class="usage-chart"
              :viewBox="`0 0 ${chartWidth} ${chartHeight}`"
              role="img"
              aria-label="网络带宽趋势折线图"
            >
              <defs>
                <linearGradient
                  v-for="(series, index) in networkChart.series"
                  :id="`network-fill-${index}`"
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
                <g v-for="grid in networkChart.yLabels" :key="grid.value">
                  <line
                    :x1="chartPadding.left"
                    :x2="chartWidth - chartPadding.right"
                    :y1="grid.y"
                    :y2="grid.y"
                  />
                  <text :x="chartPadding.left - 12" :y="grid.y + 4" text-anchor="end">
                    {{ rateText(grid.value) }}
                  </text>
                </g>
              </g>
              <g class="chart-series">
                <g v-for="(series, index) in networkChart.series" :key="series.name">
                  <polygon :points="series.areaPoints" :fill="`url(#network-fill-${index})`" />
                  <polyline :points="series.points" :stroke="series.color" />
                  <circle
                    v-for="(value, valueIndex) in series.values"
                    :key="`${series.name}-${valueIndex}`"
                    :cx="xFor(valueIndex, series.values.length)"
                    :cy="yFor(value, networkChart.maxValue)"
                    :fill="series.color"
                  />
                </g>
              </g>
              <g class="chart-x-axis">
                <template v-for="(bucket, index) in networkChart.buckets" :key="bucket.timestamp">
                  <text
                    v-if="shouldShowPerformanceBucketLabel(networkChart, index)"
                    :x="xFor(index, networkChart.buckets.length)"
                    :y="chartHeight - 14"
                    text-anchor="middle"
                  >
                    {{ bucket.label }}
                  </text>
                </template>
              </g>
            </svg>
            <div v-if="networkChart.empty" class="chart-empty">
              <strong>暂无网络采样</strong>
            </div>
          </div>
        </div>
      </div>
    </div>
  </PageSection>

  <PageSection
    title="模型调用仪表盘"
    subtitle="基于最新请求日志聚合，展示活跃模型的调用趋势与健康度"
  >
    <div class="usage-dashboard">
      <div class="usage-dashboard__main">
        <div class="usage-stats">
          <div class="usage-stat">
            <span class="usage-stat__label">总调用</span>
            <strong>{{ formatNumber(totalRequests) }}</strong>
          </div>
          <div class="usage-stat">
            <span class="usage-stat__label">成功率</span>
            <strong>{{ percentText(successRate) }}</strong>
          </div>
          <div class="usage-stat">
            <span class="usage-stat__label">消耗 Tokens</span>
            <strong>{{ formatNumber(totalTokens) }}</strong>
          </div>
          <div class="usage-stat">
            <span class="usage-stat__label">平均耗时</span>
            <strong>{{ latencyText(averageLatencyMs) }}</strong>
          </div>
          <div class="usage-stat">
            <span class="usage-stat__label">活跃模型</span>
            <strong>{{ formatNumber(activeModelCount) }}</strong>
          </div>
        </div>

        <div class="chart-shell">
          <div class="chart-shell__header">
            <div>
              <h3>模型调用趋势</h3>
              <p class="text-muted">Top 4 模型按时间聚合</p>
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
                  <polygon :points="series.areaPoints" :fill="`url(#series-fill-${index})`" />
                  <polyline :points="series.points" :stroke="series.color" />
                  <circle
                    v-for="(value, valueIndex) in series.values"
                    :key="`${series.name}-${valueIndex}`"
                    :cx="xFor(valueIndex, series.values.length)"
                    :cy="yFor(value, chartData.maxValue)"
                    :fill="series.color"
                  />
                </g>
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

  <PageSection title="最近请求" subtitle="仅展示最新 6 条，完整列表请前往请求日志">
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
</template>

<style scoped lang="scss">
  .dash-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
    gap: 16px;
  }

  .metric {
    padding: 18px 20px;

    &__label {
      font-size: 13px;
      color: var(--kp-text-secondary);
      margin-bottom: 8px;
    }

    &__value {
      font-size: 28px;
      font-weight: 700;
      letter-spacing: 0;
    }

    &__sub {
      font-size: 12px;
      margin-top: 6px;
    }
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

  .performance-dashboard {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .performance-stats {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 12px;
  }

  .performance-stat {
    min-height: 86px;
    padding: 14px 16px;
    border: 1px solid rgba(15, 23, 42, 0.07);
    border-radius: 14px;
    background: rgba(255, 255, 255, 0.56);
    display: flex;
    flex-direction: column;
    justify-content: center;
    gap: 5px;

    span {
      color: var(--kp-text-secondary);
      font-size: 12px;
    }

    strong {
      font-size: 22px;
      line-height: 1.2;
      letter-spacing: 0;
      white-space: nowrap;
    }

    small {
      font-size: 12px;
    }
  }

  .performance-charts {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
  }

  .usage-stats {
    display: grid;
    grid-template-columns: repeat(5, minmax(120px, 1fr));
    gap: 12px;
  }

  .usage-stat {
    min-height: 74px;
    padding: 14px 16px;
    border: 1px solid rgba(15, 23, 42, 0.07);
    border-radius: 14px;
    background: rgba(255, 255, 255, 0.52);
    display: flex;
    flex-direction: column;
    justify-content: center;

    &__label {
      color: var(--kp-text-secondary);
      font-size: 12px;
      margin-bottom: 6px;
    }

    strong {
      font-size: 22px;
      line-height: 1.2;
      letter-spacing: 0;
      white-space: nowrap;
    }
  }

  .chart-shell,
  .model-rank {
    border: 1px solid rgba(15, 23, 42, 0.07);
    border-radius: 16px;
    background: linear-gradient(180deg, rgba(255, 255, 255, 0.74), rgba(255, 255, 255, 0.44)),
      rgba(255, 255, 255, 0.58);
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
      stroke: rgba(31, 42, 68, 0.1);
      stroke-width: 1;
    }
  }

  .chart-series {
    polyline {
      fill: none;
      stroke-width: 3;
      stroke-linecap: round;
      stroke-linejoin: round;
      filter: drop-shadow(0 6px 12px rgba(31, 42, 68, 0.12));
    }

    circle {
      r: 4;
      stroke: rgba(255, 255, 255, 0.9);
      stroke-width: 2;
    }
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

    .model-rank {
      align-self: auto;
    }
  }

  @media (max-width: 820px) {
    .usage-stats,
    .performance-stats {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .chart-shell__header {
      flex-direction: column;
    }

    .chart-legend {
      max-width: none;
      justify-content: flex-start;
    }

    .usage-chart {
      min-width: 620px;
    }

    .chart-stage {
      overflow-x: auto;
    }
  }

  @media (max-width: 560px) {
    .usage-stats,
    .performance-stats {
      grid-template-columns: 1fr;
    }

    .usage-stat,
    .performance-stat {
      min-height: 64px;
    }
  }
</style>
