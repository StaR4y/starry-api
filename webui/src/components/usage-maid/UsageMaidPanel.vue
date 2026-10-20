<script setup lang="ts">
  import { locale, t } from '@/i18n';
  /** DSH-style usage tracker: overview, calendar, call list, prices, balance, export. */
  import { computed, onMounted, ref, watch } from 'vue';
  import { apiKeysApi, codingPlanApi, logsApi, systemApi } from '@/api';
  import { useAuthStore } from '@/stores/auth';
  import type {
    ApiKey,
    ModelInfo,
    ModelUsageSummary,
    RequestLog,
    UsageDay,
    UsageTracker,
  } from '@/types/api';
  import { formatNumber } from '@/utils/format';
  import {
    currentPeriodLabel,
    isPeakAt,
    monthLength,
    monthMondayOffset,
    pad2,
    rangeForPreset,
    shanghaiDateTime,
    shanghaiDay,
    type UsagePreset,
  } from '@/utils/usagePeriod';

  defineProps<{ title: string }>();
  const emit = defineEmits<{ close: [] }>();

  type MainTab = 'usage' | 'balance';
  type SubTab = 'overview' | 'calendar' | 'calls' | 'prices';
  type HeatDim = 'cost' | 'calls';

  const WEEKDAYS = computed(() => [t('一'), t('二'), t('三'), t('四'), t('五'), t('六'), t('日')]);

  const auth = useAuthStore();
  const mainTab = ref<MainTab>('usage');
  const subTab = ref<SubTab>('overview');
  const preset = ref<UsagePreset>('30d');
  const customFrom = ref('');
  const customTo = ref('');
  const loading = ref(false);
  const errorMessage = ref('');
  const exportMessage = ref('');
  const tracker = ref<UsageTracker | null>(null);
  const logItems = ref<RequestLog[]>([]);
  const logHasMore = ref(false);
  const logCursors = ref<(string | undefined)[]>([undefined]);
  const logPage = ref(0);
  const models = ref<ModelInfo[]>([]);
  const keys = ref<ApiKey[]>([]);
  const planAvailable = ref('—');
  const planReserved = ref('—');
  const planActive = ref('—');
  const nowParts = shanghaiDay(Date.now()).split('-').map(Number);
  const calendarYear = ref(nowParts[0]);
  const calendarMonth = ref(nowParts[1] - 1);
  const calendarTracker = ref<UsageTracker | null>(null);
  const calendarDim = ref<HeatDim>('cost');
  const selectedDay = ref<string | null>(null);
  const selectedLogs = ref<RequestLog[]>([]);
  const selectedLoading = ref(false);
  const periodNow = computed(() => currentPeriodLabel());
  const range = computed(() => rangeForPreset(preset.value, customFrom.value, customTo.value));

  function num(value: number | string | null | undefined) {
    const amount = Number(value ?? 0);
    return Number.isFinite(amount) ? amount : 0;
  }

  function fmtInt(value: number | string | null | undefined) {
    return formatNumber(Math.round(num(value)));
  }

  function fmtMoney(value: number | string | null | undefined) {
    const amount = num(value);
    if (amount === 0) return '0';
    if (Math.abs(amount) < 0.0001) return amount.toExponential(2);
    if (Math.abs(amount) < 1) return amount.toFixed(4);
    return amount.toLocaleString(locale.value, {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
  }

  function providerOf(modelId: string | null | undefined) {
    if (!modelId) return t('未指定服务商');
    const found = models.value.find((item) => item.id === modelId);
    return found?.provider || found?.owned_by || t('未指定服务商');
  }

  function modelLabel(modelId: string | null | undefined) {
    if (!modelId) return t('未指定模型');
    const found = models.value.find((item) => item.id === modelId);
    return found?.name || found?.modelName || modelId;
  }

  const overviewModels = computed(() => {
    const rows = [...(tracker.value?.models ?? [])];
    rows.sort((a, b) => num(b.credits) - num(a.credits) || b.requestCount - a.requestCount);
    return rows;
  });

  const providerGroups = computed(() => {
    const groups = new Map<
      string,
      { provider: string; calls: number; credits: number; models: ModelUsageSummary[] }
    >();
    for (const row of overviewModels.value) {
      const provider = providerOf(row.model);
      const current = groups.get(provider) ?? { provider, calls: 0, credits: 0, models: [] };
      current.calls += row.requestCount;
      current.credits += num(row.credits);
      current.models.push(row);
      groups.set(provider, current);
    }
    return [...groups.values()].sort(
      (a, b) => b.credits - a.credits || a.provider.localeCompare(b.provider),
    );
  });

  const calendarDays = computed(() => {
    const map = new Map<string, UsageDay>();
    for (const day of calendarTracker.value?.days ?? []) map.set(day.day, day);
    return map;
  });

  const calendarCells = computed(() => {
    const year = calendarYear.value;
    const month = calendarMonth.value;
    const length = monthLength(year, month);
    const offset = monthMondayOffset(year, month);
    const cells: Array<{ key: string; label: string; day?: UsageDay }> = [];
    for (let i = 0; i < offset; i += 1) cells.push({ key: `pad-${i}`, label: '' });
    for (let day = 1; day <= length; day += 1) {
      const key = `${year}-${pad2(month + 1)}-${pad2(day)}`;
      cells.push({ key, label: String(day), day: calendarDays.value.get(key) });
    }
    return cells;
  });

  const calendarMax = computed(() => {
    let max = 0;
    for (const cell of calendarCells.value) {
      if (!cell.day) continue;
      max = Math.max(
        max,
        calendarDim.value === 'cost' ? num(cell.day.credits) : cell.day.requestCount,
      );
    }
    return max;
  });

  const monthRows = computed(() =>
    [...calendarDays.value.values()].sort((a, b) => (a.day < b.day ? 1 : a.day > b.day ? -1 : 0)),
  );

  const keyUsed = computed(() => keys.value.reduce((sum, key) => sum + (key.totalCredits ?? 0), 0));
  const keyLimit = computed(() => {
    const limited = keys.value.filter((key) => key.creditsLimit != null && key.creditsLimit > 0);
    return limited.length ? limited.reduce((sum, key) => sum + (key.creditsLimit ?? 0), 0) : null;
  });

  function heatBackground(day?: UsageDay) {
    if (!day) return 'rgba(128,128,128,.06)';
    const value = calendarDim.value === 'cost' ? num(day.credits) : day.requestCount;
    if (value <= 0 || calendarMax.value <= 0) return 'rgba(128,128,128,.06)';
    const intensity = value / calendarMax.value;
    return `rgba(46,134,222,${(0.12 + 0.66 * intensity).toFixed(2)})`;
  }

  function setPreset(next: UsagePreset) {
    preset.value = next;
    if (next !== 'custom') {
      customFrom.value = '';
      customTo.value = '';
    }
  }

  function setCustom() {
    preset.value = 'custom';
  }

  async function loadTrackerAndLogs() {
    loading.value = true;
    errorMessage.value = '';
    try {
      const query = range.value;
      const [nextTracker, page] = await Promise.all([
        logsApi.myTracker(query),
        logsApi.myLogs({ ...query, limit: 100 }),
      ]);
      tracker.value = nextTracker;
      logItems.value = page.items;
      logHasMore.value = page.hasMore;
      logCursors.value = page.nextBefore ? [undefined, page.nextBefore] : [undefined];
      logPage.value = 0;
    } catch (error) {
      tracker.value = null;
      logItems.value = [];
      errorMessage.value = error instanceof Error ? error.message : t('暂时读不到用量');
    } finally {
      loading.value = false;
    }
  }

  async function loadLogPage(index: number) {
    loading.value = true;
    try {
      const before = logCursors.value[index];
      const page = await logsApi.myLogs({ ...range.value, before, limit: 100 });
      logItems.value = page.items;
      logHasMore.value = page.hasMore;
      logPage.value = index;
      if (page.nextBefore) {
        const cursors = [...logCursors.value];
        cursors[index + 1] = page.nextBefore;
        logCursors.value = cursors;
      }
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : t('调用明细加载失败');
    } finally {
      loading.value = false;
    }
  }

  async function loadCalendar() {
    const year = calendarYear.value;
    const month = calendarMonth.value;
    const from = `${year}-${pad2(month + 1)}-01`;
    const to = `${year}-${pad2(month + 1)}-${pad2(monthLength(year, month))}`;
    try {
      calendarTracker.value = await logsApi.myTracker({ from, to });
    } catch {
      calendarTracker.value = null;
    }
  }

  async function selectDay(day: string) {
    selectedDay.value = selectedDay.value === day ? null : day;
    selectedLogs.value = [];
    if (!selectedDay.value) return;
    selectedLoading.value = true;
    try {
      const page = await logsApi.myLogs({ from: day, to: day, limit: 200 });
      selectedLogs.value = page.items;
    } finally {
      selectedLoading.value = false;
    }
  }

  async function loadBalance() {
    await auth.refreshCurrentUser().catch(() => null);
    const [keyList, summary] = await Promise.all([
      apiKeysApi.list().catch(() => [] as ApiKey[]),
      codingPlanApi.summary().catch(() => null),
    ]);
    keys.value = keyList;
    planAvailable.value = summary ? fmtMoney(summary.availableCredits) : '—';
    planReserved.value = summary ? fmtMoney(summary.reservedCredits) : '—';
    planActive.value = summary ? fmtInt(summary.activeCount) : '—';
  }

  async function loadPrices() {
    models.value = await systemApi.models().catch(() => []);
  }

  async function refresh() {
    exportMessage.value = '';
    if (mainTab.value === 'balance') {
      loading.value = true;
      try {
        await loadBalance();
      } finally {
        loading.value = false;
      }
      return;
    }
    if (subTab.value === 'prices') {
      loading.value = true;
      try {
        await loadPrices();
      } finally {
        loading.value = false;
      }
      return;
    }
    if (subTab.value === 'calendar') {
      loading.value = true;
      try {
        await Promise.all([loadCalendar(), loadPrices()]);
      } finally {
        loading.value = false;
      }
      return;
    }
    await Promise.all([loadTrackerAndLogs(), loadPrices()]);
  }

  watch([preset, customFrom, customTo], () => {
    if (mainTab.value === 'usage' && (subTab.value === 'overview' || subTab.value === 'calls')) {
      void loadTrackerAndLogs();
    }
  });

  watch([calendarYear, calendarMonth], () => {
    selectedDay.value = null;
    if (mainTab.value === 'usage' && subTab.value === 'calendar') void loadCalendar();
  });

  watch([mainTab, subTab], () => {
    void refresh();
  });

  onMounted(() => {
    void refresh();
  });

  function stamp() {
    return shanghaiDateTime(Date.now())
      .replace(/[-: ]/g, '')
      .replace(/(\d{8})(\d{6})/, '$1-$2');
  }

  function downloadBlob(name: string, blob: Blob) {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = name;
    link.click();
    URL.revokeObjectURL(url);
  }

  async function collectExportLogs() {
    const query = range.value;
    const rows: RequestLog[] = [];
    let before: string | undefined;
    for (let i = 0; i < 20 && rows.length < 2000; i += 1) {
      const page = await logsApi.myLogs({ ...query, before, limit: 100 });
      rows.push(...page.items);
      if (!page.hasMore || !page.nextBefore) break;
      before = page.nextBefore;
    }
    return rows.slice(0, 2000);
  }

  async function exportCsv() {
    exportMessage.value = t('导出中…');
    try {
      const rows = await collectExportLogs();
      const header = [
        t('时间(北京)'),
        t('模型'),
        t('路径'),
        t('成功'),
        t('输入token'),
        t('输出token'),
        t('消耗'),
        t('耗时ms'),
        t('时段'),
        t('状态码'),
        t('错误'),
      ];
      const lines = [
        header.join(','),
        ...rows.map((row) =>
          [
            shanghaiDateTime(row.createdAt),
            row.model ?? '',
            row.path,
            row.success ? '1' : '0',
            row.inputTokens ?? 0,
            row.outputTokens ?? 0,
            num(row.credits),
            row.latencyMs ?? 0,
            isPeakAt(row.createdAt) ? t('峰') : t('谷'),
            row.statusCode ?? '',
            (row.errorMessage ?? '').replaceAll('"', '""'),
          ]
            .map((cell) => `"${cell}"`)
            .join(','),
        ),
      ];
      downloadBlob(
        `starry-usage-${stamp()}.csv`,
        new Blob([`\ufeff${lines.join('\n')}`], { type: 'text/csv;charset=utf-8' }),
      );
      exportMessage.value = t('已导出 CSV · {p0} 条', { p0: rows.length });
    } catch (error) {
      exportMessage.value =
        error instanceof Error ? t('导出失败：{p0}', { p0: error.message }) : t('导出失败');
    }
  }

  async function exportJson() {
    exportMessage.value = t('导出中…');
    try {
      const rows = await collectExportLogs();
      downloadBlob(
        `starry-usage-${stamp()}.json`,
        new Blob([JSON.stringify({ tracker: tracker.value, records: rows }, null, 2)], {
          type: 'application/json',
        }),
      );
      exportMessage.value = t('已导出 JSON · {p0} 条', { p0: rows.length });
    } catch (error) {
      exportMessage.value =
        error instanceof Error ? t('导出失败：{p0}', { p0: error.message }) : t('导出失败');
    }
  }

  async function exportPng() {
    exportMessage.value = t('生成图片中…');
    try {
      const rows = await collectExportLogs();
      const canvas = document.createElement('canvas');
      const width = 1100;
      const line = 22;
      const header = 170;
      const tableRows = Math.min(rows.length, 40);
      canvas.width = width;
      canvas.height = header + (overviewModels.value.length + tableRows + 8) * line;
      const ctx = canvas.getContext('2d');
      if (!ctx) {
        exportMessage.value = t('画布不可用');
        return;
      }
      ctx.fillStyle = '#0f1728';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.fillStyle = '#f3f6ff';
      ctx.font = '600 22px sans-serif';
      ctx.fillText(t('用量与消耗报告'), 28, 40);
      ctx.font = '13px sans-serif';
      ctx.fillStyle = 'rgba(243,246,255,.72)';
      const summary = tracker.value;
      ctx.fillText(
        t('生成时间 {p0}（北京） · 共 {p1} 条调用 · 合计 {p2}', {
          p0: shanghaiDateTime(Date.now()),
          p1: fmtInt(summary?.totalRequests),
          p2: fmtMoney(summary?.totalCredits),
        }),
        28,
        68,
      );
      ctx.fillText(
        t('输入 {p0} · 输出 {p1} · 高峰 {p2} · 空闲 {p3}', {
          p0: fmtInt(summary?.totalInputTokens),
          p1: fmtInt(summary?.totalOutputTokens),
          p2: fmtMoney(summary?.peakCredits),
          p3: fmtMoney(summary?.offPeakCredits),
        }),
        28,
        90,
      );
      ctx.fillStyle = '#f3f6ff';
      ctx.font = '600 14px sans-serif';
      ctx.fillText(t('按模型'), 28, 128);
      ctx.font = '12px sans-serif';
      let y = 154;
      for (const model of overviewModels.value.slice(0, 20)) {
        ctx.fillStyle = 'rgba(243,246,255,.88)';
        ctx.fillText(
          t('{p0}  {p1} 次  {p2} / {p3}  {p4}', {
            p0: modelLabel(model.model),
            p1: fmtInt(model.requestCount),
            p2: fmtInt(model.inputTokens),
            p3: fmtInt(model.outputTokens),
            p4: fmtMoney(model.credits),
          }),
          28,
          y,
        );
        y += line;
      }
      y += 12;
      ctx.fillStyle = '#f3f6ff';
      ctx.font = '600 14px sans-serif';
      ctx.fillText(t('调用明细（最近 {p0} 条）', { p0: tableRows }), 28, y);
      y += 24;
      ctx.font = '12px sans-serif';
      for (const row of rows.slice(0, tableRows)) {
        ctx.fillStyle = row.success ? 'rgba(243,246,255,.88)' : '#ef5b6b';
        ctx.fillText(
          `${shanghaiDateTime(row.createdAt)}  ${modelLabel(row.model)}  ${fmtInt(row.inputTokens)} / ${fmtInt(row.outputTokens)}  ${fmtMoney(row.credits)}  ${isPeakAt(row.createdAt) ? t('峰') : t('谷')}`,
          28,
          y,
        );
        y += line;
      }
      await new Promise<void>((resolve, reject) => {
        canvas.toBlob((blob) => {
          if (!blob) {
            reject(new Error(t('生成图片失败')));
            return;
          }
          downloadBlob(`starry-usage-${stamp()}.png`, blob);
          resolve();
        }, 'image/png');
      });
      exportMessage.value =
        rows.length > 2000
          ? t('已导出 PNG · 报告仅含最近 2000 条')
          : t('已导出 PNG · {p0} 条', { p0: rows.length });
    } catch (error) {
      exportMessage.value =
        error instanceof Error ? t('生成图片失败：{p0}', { p0: error.message }) : t('生成图片失败');
    }
  }

  function prevMonth() {
    if (calendarMonth.value === 0) {
      calendarYear.value -= 1;
      calendarMonth.value = 11;
    } else {
      calendarMonth.value -= 1;
    }
  }

  function nextMonth() {
    if (calendarMonth.value === 11) {
      calendarYear.value += 1;
      calendarMonth.value = 0;
    } else {
      calendarMonth.value += 1;
    }
  }

  function goToday() {
    const today = shanghaiDay(Date.now());
    const parts = today.split('-').map(Number);
    calendarYear.value = parts[0];
    calendarMonth.value = parts[1] - 1;
    selectedDay.value = null;
    void selectDay(today);
  }
</script>

<template>
  <section class="maid-panel" role="dialog" :aria-label="title">
    <header class="maid-panel__head">
      <div>
        <p class="maid-panel__kicker">{{ title }}</p>
        <h2>{{ mainTab === 'usage' ? t('用量与消耗') : t('剩余余额查询') }}</h2>
        <p class="maid-panel__sub">
          {{
            mainTab === 'usage'
              ? t('当前账号在 Starry 的实扣用量与调用记录')
              : t('钱包、套餐与 API Key 额度')
          }}
        </p>
      </div>
      <div class="maid-panel__head-actions">
        <span class="badge" :class="periodNow.includes(t('高峰')) ? 'is-peak' : 'is-off'">{{
          periodNow
        }}</span>
        <button type="button" class="ghost" @click="refresh">{{ t('刷新') }}</button>
        <button type="button" class="ghost" :aria-label="t('关闭')" @click="emit('close')">
          {{ t('关闭') }}
        </button>
      </div>
    </header>

    <div class="tabs">
      <button type="button" :class="{ 'is-on': mainTab === 'usage' }" @click="mainTab = 'usage'">
        {{ t('用量与消耗') }}
      </button>
      <button
        type="button"
        :class="{ 'is-on': mainTab === 'balance' }"
        @click="mainTab = 'balance'"
      >
        {{ t('剩余余额查询') }}
      </button>
    </div>

    <p v-if="errorMessage" class="status">{{ errorMessage }}</p>
    <p v-else-if="loading" class="status">{{ t('正在查看用量…') }}</p>

    <template v-if="mainTab === 'usage'">
      <div class="subtabs">
        <button
          type="button"
          :class="{ 'is-on': subTab === 'overview' }"
          @click="subTab = 'overview'"
        >
          {{ t('概览') }}
        </button>
        <button
          type="button"
          :class="{ 'is-on': subTab === 'calendar' }"
          @click="subTab = 'calendar'"
        >
          {{ t('用量日历') }}
        </button>
        <button type="button" :class="{ 'is-on': subTab === 'calls' }" @click="subTab = 'calls'">
          {{ t('调用明细') }}
        </button>
        <button type="button" :class="{ 'is-on': subTab === 'prices' }" @click="subTab = 'prices'">
          {{ t('价格表') }}
        </button>
      </div>

      <div v-if="subTab !== 'prices' && subTab !== 'calendar'" class="toolbar">
        <div class="seg">
          <button
            type="button"
            :class="{ 'is-on': preset === 'today' }"
            @click="setPreset('today')"
          >
            {{ t('今天') }}
          </button>
          <button type="button" :class="{ 'is-on': preset === '7d' }" @click="setPreset('7d')">
            {{ t('近7天') }}
          </button>
          <button type="button" :class="{ 'is-on': preset === '30d' }" @click="setPreset('30d')">
            {{ t('近30天') }}
          </button>
          <button type="button" :class="{ 'is-on': preset === 'all' }" @click="setPreset('all')">
            {{ t('全部') }}
          </button>
        </div>
        <span class="note">{{ t('自定义区间：') }}</span>
        <input v-model="customFrom" type="date" @change="setCustom">
        <span class="note">{{ t('至') }}</span>
        <input v-model="customTo" type="date" @change="setCustom">
        <button v-if="preset !== 'all'" type="button" class="ghost" @click="setPreset('all')">
          {{ t('清除筛选') }}
        </button>
      </div>

      <div class="banner">
        <strong>{{ t('与官方后台的差异') }}</strong>
        <p>
          {{
            t(
              '本面板统计的是当前账号在 Starry 实扣的调用（请求日志 + 日/小时聚合）。消耗单位是额度，不是 DeepSeek 官方峰谷价。',
            )
          }}
        </p>
        <p>
          {{
            t(
              '高峰 / 空闲按时段拆分：工作日北京时间 9:00–12:00、14:00–18:00 为高峰，其余为空闲；自 2026-08-23 起周末全天空闲。已滚入日汇总的历史调用只计入总消耗，高峰/空闲可能小于合计。',
            )
          }}
        </p>
        <p v-if="tracker && tracker.failedRequests > 0" class="warn">
          {{ t('当前范围有') }} {{ fmtInt(tracker.failedRequests) }}
          {{ t('次失败调用——Starry 仍可能已按实际 token 计费。') }}
        </p>
      </div>

      <template v-if="subTab === 'overview' && tracker">
        <p class="note">
          {{ t('当前范围：共') }} {{ fmtInt(tracker.totalRequests) }} {{ t('条') }}
          {{ preset === 'all' ? t('（全部记录）') : '' }} {{ t('· 总消耗') }}
          {{ fmtMoney(tracker.totalCredits) }}
        </p>
        <div class="cards">
          <article>
            <span>{{ t('调用次数') }}</span>
            <strong>{{ fmtInt(tracker.totalRequests) }}</strong>
            <small>{{ t('高峰') }} {{ fmtInt(tracker.peakRequests) }} {{ t('· 空闲') }}
              {{ fmtInt(tracker.offPeakRequests) }} {{ t('· 失败') }}
              {{ fmtInt(tracker.failedRequests) }}</small>
          </article>
          <article>
            <span>{{ t('输入') }}</span>
            <strong>{{ fmtInt(tracker.totalInputTokens) }}</strong>
            <small>token</small>
          </article>
          <article>
            <span>{{ t('输出') }}</span>
            <strong>{{ fmtInt(tracker.totalOutputTokens) }}</strong>
            <small>token</small>
          </article>
          <article>
            <span>{{ t('高峰消耗') }}</span>
            <strong class="is-peak">{{ fmtMoney(tracker.peakCredits) }}</strong>
            <small>{{ t('工作日高峰 9:00–12:00、14:00–18:00') }}</small>
          </article>
          <article>
            <span>{{ t('空闲消耗') }}</span>
            <strong class="is-off">{{ fmtMoney(tracker.offPeakCredits) }}</strong>
            <small>{{ t('其余空闲时段') }}</small>
          </article>
          <article>
            <span>{{ t('总消耗') }}</span>
            <strong>{{ fmtMoney(tracker.totalCredits) }}</strong>
            <small>{{ t('Starry 实扣额度') }}</small>
          </article>
        </div>

        <h3>{{ t('消耗表（按模型）') }}</h3>
        <div class="scroll">
          <table>
            <thead>
              <tr>
                <th>{{ t('模型') }}</th>
                <th>{{ t('API 服务商') }}</th>
                <th>{{ t('调用') }}</th>
                <th>{{ t('成功 / 失败') }}</th>
                <th>{{ t('输入') }}</th>
                <th>{{ t('输出') }}</th>
                <th>{{ t('总消耗') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in overviewModels" :key="row.model || 'unknown'">
                <td>{{ modelLabel(row.model) }}</td>
                <td>{{ providerOf(row.model) }}</td>
                <td>{{ fmtInt(row.requestCount) }}</td>
                <td>{{ fmtInt(row.successfulRequests) }} / {{ fmtInt(row.failedRequests) }}</td>
                <td>{{ fmtInt(row.inputTokens) }}</td>
                <td>{{ fmtInt(row.outputTokens) }}</td>
                <td>{{ fmtMoney(row.credits) }}</td>
              </tr>
              <tr class="is-total">
                <td>{{ t('合计') }}</td>
                <td>{{ providerGroups.length }} {{ t('个服务商') }}</td>
                <td>{{ fmtInt(tracker.totalRequests) }}</td>
                <td>
                  {{ fmtInt(tracker.successfulRequests) }} / {{ fmtInt(tracker.failedRequests) }}
                </td>
                <td>{{ fmtInt(tracker.totalInputTokens) }}</td>
                <td>{{ fmtInt(tracker.totalOutputTokens) }}</td>
                <td>{{ fmtMoney(tracker.totalCredits) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <h3>{{ t('消耗明细（按 API 服务商 × 模型）') }}</h3>
        <div class="scroll">
          <table>
            <thead>
              <tr>
                <th>{{ t('API 服务商') }}</th>
                <th>{{ t('模型') }}</th>
                <th>{{ t('调用') }}</th>
                <th>{{ t('输入') }}</th>
                <th>{{ t('输出') }}</th>
                <th>{{ t('总消耗') }}</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="group in providerGroups" :key="group.provider">
                <tr class="is-group">
                  <td>{{ group.provider }}</td>
                  <td>（{{ group.models.length }} {{ t('个模型）') }}</td>
                  <td>{{ fmtInt(group.calls) }}</td>
                  <td colspan="2" />
                  <td>{{ fmtMoney(group.credits) }}</td>
                </tr>
                <tr v-for="row in group.models" :key="`${group.provider}-${row.model}`">
                  <td />
                  <td>{{ modelLabel(row.model) }}</td>
                  <td>{{ fmtInt(row.requestCount) }}</td>
                  <td>{{ fmtInt(row.inputTokens) }}</td>
                  <td>{{ fmtInt(row.outputTokens) }}</td>
                  <td>{{ fmtMoney(row.credits) }}</td>
                </tr>
              </template>
              <tr class="is-total">
                <td>{{ t('总费用合计') }}</td>
                <td>{{ providerGroups.length }} {{ t('个服务商') }}</td>
                <td>{{ fmtInt(tracker.totalRequests) }}</td>
                <td>{{ fmtInt(tracker.totalInputTokens) }}</td>
                <td>{{ fmtInt(tracker.totalOutputTokens) }}</td>
                <td>{{ fmtMoney(tracker.totalCredits) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <template v-else-if="subTab === 'calendar'">
        <div class="toolbar">
          <button type="button" class="ghost" @click="prevMonth">{{ t('‹ 上月') }}</button>
          <strong>{{ calendarYear }}-{{ pad2(calendarMonth + 1) }}</strong>
          <button type="button" class="ghost" @click="nextMonth">{{ t('下月 ›') }}</button>
          <button type="button" class="ghost" @click="goToday">{{ t('今天') }}</button>
          <div class="seg">
            <button
              type="button"
              :class="{ 'is-on': calendarDim === 'cost' }"
              @click="calendarDim = 'cost'"
            >
              {{ t('按消耗') }}
            </button>
            <button
              type="button"
              :class="{ 'is-on': calendarDim === 'calls' }"
              @click="calendarDim = 'calls'"
            >
              {{ t('按调用数') }}
            </button>
          </div>
        </div>
        <div class="cal">
          <span v-for="label in WEEKDAYS" :key="label" class="cal__wk">{{ label }}</span>
          <button
            v-for="cell in calendarCells"
            :key="cell.key"
            type="button"
            class="cal__cell"
            :class="{ 'is-empty': !cell.label, 'is-on': selectedDay === cell.key }"
            :style="{ background: cell.label ? heatBackground(cell.day) : 'transparent' }"
            :disabled="!cell.label"
            :title="
              cell.day
                ? t(
                  '{p0}\n调用 {p1} 次（高峰 {p2} / 空闲 {p3}）\n输入 {p4} · 输出 {p5}\n高峰消耗 {p6} · 空闲消耗 {p7} · 合计 {p8}',
                  {
                    p0: cell.key,
                    p1: cell.day.requestCount,
                    p2: cell.day.peakRequests,
                    p3: cell.day.offPeakRequests,
                    p4: cell.day.inputTokens,
                    p5: cell.day.outputTokens,
                    p6: fmtMoney(cell.day.peakCredits),
                    p7: fmtMoney(cell.day.offPeakCredits),
                    p8: fmtMoney(cell.day.credits),
                  },
                )
                : cell.label
                  ? t('{p0}\n无记录', { p0: cell.key })
                  : ''
            "
            @click="cell.label && selectDay(cell.key)"
          >
            <span>{{ cell.label }}</span>
            <small v-if="cell.day">{{
              calendarDim === 'cost' ? fmtMoney(cell.day.credits) : fmtInt(cell.day.requestCount)
            }}</small>
            <small v-else-if="cell.label">—</small>
          </button>
        </div>
        <p class="note">
          {{ t('热力：低 → 高（') }}{{ calendarDim === 'cost' ? t('当日消耗') : t('当日调用数')
          }}{{ t('）· 悬停查看详情，点击某天查看当日调用') }}
        </p>
        <div class="cards">
          <article>
            <span>{{ t('本月调用') }}</span>
            <strong>{{ fmtInt(calendarTracker?.totalRequests) }}</strong>
            <small>{{ t('高峰') }} {{ fmtInt(calendarTracker?.peakRequests) }} {{ t('· 空闲') }}
              {{ fmtInt(calendarTracker?.offPeakRequests) }}</small>
          </article>
          <article>
            <span>{{ t('本月消耗') }}</span>
            <strong>{{ fmtMoney(calendarTracker?.totalCredits) }}</strong>
            <small>{{ t('高峰') }} {{ fmtMoney(calendarTracker?.peakCredits) }} {{ t('· 空闲') }}
              {{ fmtMoney(calendarTracker?.offPeakCredits) }}</small>
          </article>
        </div>
        <h3>{{ calendarYear }}{{ t('年') }}{{ calendarMonth + 1 }}{{ t('月每日统计') }}</h3>
        <p v-if="!monthRows.length" class="status">{{ t('本月暂无记录。') }}</p>
        <div v-else class="scroll">
          <table>
            <thead>
              <tr>
                <th>{{ t('日期') }}</th>
                <th>{{ t('调用') }}</th>
                <th>{{ t('高峰/空闲') }}</th>
                <th>{{ t('输入') }}</th>
                <th>{{ t('输出') }}</th>
                <th>{{ t('高峰消耗') }}</th>
                <th>{{ t('空闲消耗') }}</th>
                <th>{{ t('总消耗') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in monthRows" :key="row.day">
                <td>
                  <button
                    type="button"
                    class="link"
                    :class="{ 'is-on': selectedDay === row.day }"
                    @click="selectDay(row.day)"
                  >
                    {{ row.day }}
                  </button>
                </td>
                <td>{{ fmtInt(row.requestCount) }}</td>
                <td>
                  <span class="badge is-peak">{{ fmtInt(row.peakRequests) }}</span>
                  /
                  <span class="badge is-off">{{ fmtInt(row.offPeakRequests) }}</span>
                </td>
                <td>{{ fmtInt(row.inputTokens) }}</td>
                <td>{{ fmtInt(row.outputTokens) }}</td>
                <td class="is-peak">{{ fmtMoney(row.peakCredits) }}</td>
                <td class="is-off">{{ fmtMoney(row.offPeakCredits) }}</td>
                <td>{{ fmtMoney(row.credits) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <template v-if="selectedDay">
          <h3>{{ selectedDay }} {{ t('调用明细') }}</h3>
          <p v-if="selectedLoading" class="status">{{ t('加载当日调用…') }}</p>
          <p v-else-if="!selectedLogs.length" class="status">{{ t('该日无记录。') }}</p>
          <div v-else class="scroll">
            <table>
              <thead>
                <tr>
                  <th>{{ t('时间(北京)') }}</th>
                  <th>{{ t('模型') }}</th>
                  <th>{{ t('输入') }}</th>
                  <th>{{ t('输出') }}</th>
                  <th>{{ t('时段') }}</th>
                  <th>{{ t('结束') }}</th>
                  <th>{{ t('消耗') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in selectedLogs" :key="row.requestId">
                  <td>{{ shanghaiDateTime(row.createdAt) }}</td>
                  <td>
                    {{ modelLabel(row.model) }}
                    <span v-if="!row.success" class="badge is-warn">{{ t('失败') }}</span>
                  </td>
                  <td>{{ fmtInt(row.inputTokens) }}</td>
                  <td>{{ fmtInt(row.outputTokens) }}</td>
                  <td>
                    <span class="badge" :class="isPeakAt(row.createdAt) ? 'is-peak' : 'is-off'">
                      {{ isPeakAt(row.createdAt) ? t('峰') : t('谷') }}
                    </span>
                  </td>
                  <td>{{ row.success ? t('完成') : row.errorMessage || t('错误') }}</td>
                  <td :class="isPeakAt(row.createdAt) ? 'is-peak' : 'is-off'">
                    {{ fmtMoney(row.credits) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </template>

      <template v-else-if="subTab === 'calls'">
        <p class="note">
          {{ t('当前范围：共本页') }} {{ fmtInt(logItems.length) }} {{ t('条 · 第') }}
          {{ logPage + 1 }} {{ t('页 · 每页 100 条') }}
        </p>
        <div class="toolbar">
          <button
            type="button"
            class="ghost"
            :disabled="logPage <= 0"
            @click="loadLogPage(logPage - 1)"
          >
            {{ t('‹ 上一页') }}
          </button>
          <button
            type="button"
            class="ghost"
            :disabled="!logHasMore"
            @click="loadLogPage(logPage + 1)"
          >
            {{ t('下一页 ›') }}
          </button>
        </div>
        <p v-if="!logItems.length" class="status">{{ t('该时间范围内没有记录。') }}</p>
        <div v-else class="scroll">
          <table>
            <thead>
              <tr>
                <th>{{ t('时间(北京)') }}</th>
                <th>{{ t('模型') }}</th>
                <th>{{ t('输入') }}</th>
                <th>{{ t('输出') }}</th>
                <th>{{ t('时段') }}</th>
                <th>{{ t('结束') }}</th>
                <th>{{ t('消耗') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in logItems" :key="row.requestId">
                <td>{{ shanghaiDateTime(row.createdAt) }}</td>
                <td>
                  <div>
                    {{ modelLabel(row.model) }}
                    <span v-if="!row.success" class="badge is-warn">{{ t('失败') }}</span>
                  </div>
                  <div class="note">{{ row.path }}</div>
                </td>
                <td>{{ fmtInt(row.inputTokens) }}</td>
                <td>{{ fmtInt(row.outputTokens) }}</td>
                <td>
                  <span class="badge" :class="isPeakAt(row.createdAt) ? 'is-peak' : 'is-off'">
                    {{ isPeakAt(row.createdAt) ? t('峰') : t('谷') }}
                  </span>
                </td>
                <td>{{ row.success ? t('完成') : row.errorMessage || t('错误') }}</td>
                <td :class="isPeakAt(row.createdAt) ? 'is-peak' : 'is-off'">
                  {{ fmtMoney(row.credits) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <template v-else-if="subTab === 'prices'">
        <p class="note">
          {{
            t(
              '价格来自当前账号可见的模型目录，单位：额度 / 百万 tokens。这不是 DeepSeek 官方峰谷价。',
            )
          }}
        </p>
        <p v-if="!models.length" class="status">{{ t('暂时没有可展示的价格。') }}</p>
        <div v-else class="scroll">
          <table>
            <thead>
              <tr>
                <th>{{ t('模型') }}</th>
                <th>{{ t('服务商') }}</th>
                <th>{{ t('计费') }}</th>
                <th>{{ t('输入') }}</th>
                <th>{{ t('输出') }}</th>
                <th>{{ t('缓存写入') }}</th>
                <th>{{ t('缓存命中') }}</th>
                <th>{{ t('合计') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="model in models" :key="model.id">
                <td>{{ model.name || model.modelName || model.id }}</td>
                <td>{{ model.provider || model.owned_by || '—' }}</td>
                <td>{{ model.billingMode || '—' }}</td>
                <td>
                  {{
                    model.inputPricePerMillionTokens == null
                      ? '—'
                      : fmtMoney(model.inputPricePerMillionTokens)
                  }}
                </td>
                <td>
                  {{
                    model.outputPricePerMillionTokens == null
                      ? '—'
                      : fmtMoney(model.outputPricePerMillionTokens)
                  }}
                </td>
                <td>
                  {{
                    model.cacheWritePricePerMillionTokens == null
                      ? '—'
                      : fmtMoney(model.cacheWritePricePerMillionTokens)
                  }}
                </td>
                <td>
                  {{
                    model.cacheReadPricePerMillionTokens == null
                      ? '—'
                      : fmtMoney(model.cacheReadPricePerMillionTokens)
                  }}
                </td>
                <td>
                  {{
                    model.totalPricePerMillionTokens == null
                      ? '—'
                      : fmtMoney(model.totalPricePerMillionTokens)
                  }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <div class="toolbar">
        <button type="button" class="ghost" @click="exportCsv">{{ t('导出 CSV') }}</button>
        <button type="button" class="ghost" @click="exportJson">{{ t('导出 JSON') }}</button>
        <button type="button" class="ghost" @click="exportPng">{{ t('导出图片 (PNG)') }}</button>
        <span v-if="exportMessage" class="note">{{ exportMessage }}</span>
      </div>
    </template>

    <template v-else>
      <div class="cards">
        <article>
          <span>{{ t('钱包余额') }}</span>
          <strong>{{ fmtMoney(auth.user?.balance) }}</strong>
          <small>{{ t('当前账号') }}</small>
        </article>
        <article>
          <span>{{ t('套餐剩余') }}</span>
          <strong>{{ planAvailable }}</strong>
          <small>{{ t('预占') }} {{ planReserved }}</small>
        </article>
        <article>
          <span>{{ t('有效订阅') }}</span>
          <strong>{{ planActive }}</strong>
        </article>
        <article>
          <span>{{ t('API Key 已用') }}</span>
          <strong>{{ fmtMoney(keyUsed) }}</strong>
          <small>/ {{ keyLimit == null ? t('不限') : fmtMoney(keyLimit) }}</small>
        </article>
      </div>
      <h3>API Key</h3>
      <p v-if="!keys.length" class="status">{{ t('还没有 API Key。') }}</p>
      <div v-else class="scroll">
        <table>
          <thead>
            <tr>
              <th>{{ t('名称') }}</th>
              <th>{{ t('计费') }}</th>
              <th>{{ t('已用额度') }}</th>
              <th>{{ t('调用') }}</th>
              <th>{{ t('最近使用') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="key in keys" :key="key.keyId">
              <td>
                {{ key.name }} <span class="note">{{ key.keyPrefix }}</span>
              </td>
              <td>{{ key.billingSource === 'CODING_PLAN' ? 'Coding Plan' : t('余额') }}</td>
              <td>
                {{ fmtMoney(key.totalCredits)
                }}{{ key.creditsLimit ? ` / ${fmtMoney(key.creditsLimit)}` : '' }}
              </td>
              <td>{{ fmtInt(key.totalRequests) }}</td>
              <td>{{ key.lastUsedAt ? shanghaiDateTime(key.lastUsedAt) : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>

<style scoped lang="scss">
  .maid-panel {
    width: min(960px, calc(100vw - 28px));
    max-height: min(82vh, 860px);
    overflow: auto;
    padding: 14px 16px 12px;
    border: 1px solid var(--el-border-color);
    border-radius: 14px 14px 4px 14px;
    background: color-mix(in srgb, white 78%, transparent);
    color: var(--kp-text);
    box-shadow: 0 16px 36px rgba(26, 48, 92, 0.16);
  }

  html[data-ui-chrome='editorial'] .maid-panel {
    background: transparent;
    box-shadow: none;
    border-radius: 6px;
  }

  .maid-panel__head {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
    align-items: flex-start;
  }

  .maid-panel__kicker {
    margin: 0 0 4px;
    color: #3a5ea8;
    font-size: 11px;
    letter-spacing: 0.08em;
  }

  h2 {
    margin: 0;
    font-size: 15px;
  }

  h3 {
    margin: 14px 0 8px;
    font-size: 13px;
  }

  .maid-panel__sub,
  .note,
  .status {
    margin: 4px 0 0;
    color: var(--kp-text-muted);
    font-size: 12px;
  }

  .maid-panel__head-actions,
  .toolbar,
  .tabs,
  .subtabs,
  .seg {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    align-items: center;
  }

  .tabs,
  .subtabs,
  .toolbar {
    margin-top: 10px;
  }

  button.ghost,
  .tabs button,
  .subtabs button,
  .seg button {
    border: 1px solid rgba(128, 128, 128, 0.35);
    background: transparent;
    border-radius: 6px;
    padding: 4px 10px;
    font-size: 12px;
    color: inherit;
    cursor: pointer;
  }

  .tabs button.is-on,
  .subtabs button.is-on,
  .seg button.is-on,
  .link.is-on {
    border-color: rgba(90, 140, 255, 0.5);
    background: rgba(90, 140, 255, 0.22);
    font-weight: 600;
  }

  button:disabled {
    opacity: 0.45;
    cursor: default;
  }

  input[type='date'] {
    border: 1px solid rgba(128, 128, 128, 0.35);
    background: transparent;
    border-radius: 6px;
    padding: 3px 8px;
    color: inherit;
    font-size: 12px;
  }

  .banner {
    margin-top: 10px;
    border: 1px solid rgba(255, 152, 0, 0.4);
    background: rgba(255, 152, 0, 0.07);
    border-radius: 8px;
    padding: 8px 12px;
    font-size: 11px;
    line-height: 1.6;
  }

  .banner strong {
    color: #e08700;
  }

  .banner p {
    margin: 4px 0 0;
  }

  .warn {
    color: #e74c3c;
    font-weight: 600;
  }

  .cards {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 10px;
    margin-top: 10px;
  }

  .cards article {
    border: 1px solid rgba(128, 128, 128, 0.35);
    border-radius: 8px;
    padding: 10px 12px;
    min-width: 0;
  }

  .cards span,
  .cards small {
    display: block;
    color: var(--kp-text-muted);
    font-size: 11px;
  }

  .cards strong {
    display: block;
    margin: 4px 0 2px;
    font-size: 18px;
    overflow-wrap: anywhere;
  }

  .scroll {
    overflow-x: auto;
    width: 100%;
  }

  table {
    width: max-content;
    min-width: 720px;
    border-collapse: collapse;
    font-size: 12px;
  }

  th,
  td {
    padding: 7px 8px;
    border-bottom: 1px solid rgba(128, 128, 128, 0.2);
    text-align: right;
    word-break: break-word;
  }

  th:first-child,
  td:first-child,
  th:nth-child(2),
  td:nth-child(2) {
    text-align: left;
  }

  th {
    color: var(--kp-text-muted);
    font-weight: 500;
  }

  .is-total td,
  .is-group td {
    font-weight: 600;
    border-top: 1px solid rgba(128, 128, 128, 0.35);
  }

  .is-group td {
    background: rgba(128, 128, 128, 0.09);
  }

  .badge {
    display: inline-block;
    padding: 2px 8px;
    border-radius: 999px;
    font-size: 11px;
    font-weight: 500;
    white-space: nowrap;
  }

  .is-peak {
    color: #e08700;
  }

  .is-off {
    color: #3d6bd6;
  }

  .badge.is-peak {
    background: rgba(255, 152, 0, 0.18);
    color: #ff9800;
  }

  .badge.is-off {
    background: rgba(90, 140, 255, 0.18);
    color: #5a8cff;
  }

  .badge.is-warn {
    background: rgba(231, 76, 60, 0.16);
    color: #e74c3c;
  }

  .cal {
    display: grid;
    grid-template-columns: repeat(7, minmax(0, 1fr));
    gap: 6px;
    margin-top: 10px;
  }

  .cal__wk,
  .cal__cell {
    min-height: 54px;
    border-radius: 8px;
    font-size: 12px;
  }

  .cal__wk {
    min-height: auto;
    color: var(--kp-text-muted);
    text-align: center;
  }

  .cal__cell {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
    padding: 6px 8px;
    border: 1px solid rgba(128, 128, 128, 0.2);
    background: rgba(128, 128, 128, 0.06);
    color: inherit;
    cursor: pointer;
  }

  .cal__cell.is-empty {
    border: 0;
    background: transparent;
    cursor: default;
  }

  .cal__cell.is-on {
    outline: 2px solid #5a8cff;
  }

  .cal__cell small {
    font-size: 10px;
    opacity: 0.8;
  }

  .link {
    border: 0;
    background: transparent;
    padding: 0;
    color: inherit;
    cursor: pointer;
  }

  @media (max-width: 900px) {
    table {
      min-width: 0;
      width: 100%;
    }
  }
</style>
