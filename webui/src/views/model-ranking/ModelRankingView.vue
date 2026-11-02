<script setup lang="ts">
  import { t } from '@/i18n';
  import { computed, ref, watch, type CSSProperties } from 'vue';
  import { Trophy } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { systemApi } from '@/api';
  import type { ModelRankingBenchmark, ModelRankingSnapshot } from '@/types/api';
  import { webHost, webLink } from '@/utils/webLink';

  type BarStyle = CSSProperties & Record<`--${string}`, string>;

  interface BenchmarkConfig {
    label: string;
    fallbackTitle: string;
  }

  const props = defineProps<{ benchmark: ModelRankingBenchmark }>();
  const benchmarkConfigs: Record<ModelRankingBenchmark, BenchmarkConfig> = {
    programbench: {
      label: 'ProgramBench',
      fallbackTitle: 'ProgramBench Leaderboard',
    },
    frontierbench: {
      label: 'FrontierBench',
      fallbackTitle: 'Terminal-Bench 3.0 Leaderboard',
    },
  };

  const snapshot = ref<ModelRankingSnapshot | null>(null);
  const loading = ref(true);
  const error = ref('');
  const chartKey = ref(0);
  const benchmarkConfig = computed(() => benchmarkConfigs[props.benchmark]);
  const isProgramBench = computed(() => props.benchmark === 'programbench');

  const chartMax = computed(() => {
    const scores = snapshot.value?.entries.flatMap((entry) => [
      entry.score,
      entry.secondaryScore ?? 0,
    ]) ?? [0];
    return Math.max(10, Math.ceil(Math.max(...scores) / 10) * 10);
  });
  const axisTicks = computed(() =>
    Array.from({ length: chartMax.value / 10 + 1 }, (_, index) => index * 10),
  );
  const gridStyle = computed<BarStyle>(() => ({
    '--grid-size': `${100 / Math.max(1, axisTicks.value.length - 1)}%`,
  }));
  const benchmarkTitle = computed(() =>
    (snapshot.value?.title || benchmarkConfig.value.fallbackTitle).replace(/\s+Leaderboard$/i, ''),
  );

  const sourceLink = computed(() => webLink(snapshot.value?.sourceUrl));

  async function loadRanking() {
    loading.value = true;
    error.value = '';
    snapshot.value = null;
    try {
      const payload = await systemApi.modelRanking(props.benchmark);
      if (!Array.isArray(payload.entries)) throw new Error('INVALID_DATA');
      snapshot.value = {
        ...payload,
        title: payload.title || benchmarkConfig.value.fallbackTitle,
        entries: payload.entries.slice(0, 12),
      };
      chartKey.value += 1;
    } catch {
      error.value = t('天梯数据暂时无法载入');
    } finally {
      loading.value = false;
    }
  }

  function hasSecondaryScore(entry: ModelRankingSnapshot['entries'][number]) {
    return entry.secondaryScore != null;
  }

  function barStyle(entry: ModelRankingSnapshot['entries'][number], index: number): BarStyle {
    const colors = ['#c18a39', '#4f7ebd', '#71819b', '#3f78b8', '#3f8195', '#5576a8'];
    return {
      '--bar-width': `${Math.min(100, (entry.score / chartMax.value) * 100)}%`,
      '--secondary-width': `${Math.min(
        100,
        ((entry.secondaryScore ?? 0) / chartMax.value) * 100,
      )}%`,
      '--bar-color': isProgramBench.value ? '#3f78b8' : colors[index] || '#4b76ad',
      '--secondary-color': '#299879',
      '--bar-delay': `${Math.min(index * 45, 360)}ms`,
    };
  }

  function scoreText(score: number) {
    return `${score.toFixed(1)}%`;
  }

  watch(() => props.benchmark, loadRanking, { immediate: true });
</script>

<template>
  <section class="ranking-page">
    <PageSection :title="benchmarkConfig.label" :subtitle="t('每日更新')" :icon="Trophy">
      <template #actions>
        <nav class="ranking-page__benchmarks" :aria-label="t('模型基准榜单')">
          <router-link :to="{ name: 'model-ranking-programbench' }">ProgramBench</router-link>
          <router-link :to="{ name: 'model-ranking-frontierbench' }">FrontierBench</router-link>
        </nav>
      </template>
      <p v-if="snapshot || benchmarkTitle !== benchmarkConfig.label" class="ranking-page__meta">
        {{ benchmarkTitle }}
        <template v-if="sourceLink">
          · {{ t('数据来源') }}
          <a :href="sourceLink" target="_blank" rel="noreferrer">{{ webHost(sourceLink) }}</a>
        </template>
      </p>

      <div class="ranking-chart">
        <div
          v-if="loading && !snapshot"
          class="ranking-chart__skeleton"
          :aria-label="t('正在载入天梯')"
        >
          <div v-for="index in 9" :key="index" class="skeleton-row">
            <span />
            <span />
            <span :style="{ width: `${88 - index * 6}%` }" />
          </div>
        </div>

        <div v-else-if="error && !snapshot" class="ranking-chart__error">
          <span>{{ error }}</span>
          <el-button text @click="loadRanking">{{ t('重试') }}</el-button>
        </div>

        <div v-else-if="snapshot" :key="chartKey" class="ranking-chart__content">
          <div v-if="isProgramBench" class="chart-legend" :aria-label="t('ProgramBench 指标')">
            <span><i class="is-primary" aria-hidden="true" />{{ t('已解决') }}</span>
            <span><i class="is-secondary" aria-hidden="true" />{{ t('接近解决') }}</span>
          </div>

          <div class="chart-head">
            <span class="chart-head__rank">{{ t('排名') }}</span>
            <span>{{ t('模型 / Agent') }}</span>
            <div class="chart-axis">
              <span v-for="tick in axisTicks" :key="tick">{{ tick }}%</span>
            </div>
          </div>

          <div class="chart-rows">
            <article
              v-for="(entry, index) in snapshot.entries"
              :key="`${entry.rank}-${entry.model}`"
              class="chart-row"
              :style="barStyle(entry, index)"
            >
              <span class="chart-row__rank" :class="`is-rank-${Math.min(entry.rank, 4)}`">
                {{ String(entry.rank).padStart(2, '0') }}
              </span>

              <div class="chart-row__identity">
                <strong>{{ entry.model }}</strong>
                <span>{{ entry.organization }} · {{ entry.agent }}</span>
              </div>

              <div class="chart-row__mobile-scores">
                <strong class="is-primary">{{ scoreText(entry.score) }}</strong>
                <strong v-if="hasSecondaryScore(entry)" class="is-secondary">
                  {{ scoreText(entry.secondaryScore ?? 0) }}
                </strong>
              </div>

              <div
                class="chart-row__plot"
                :class="{ 'is-dual': hasSecondaryScore(entry) }"
                :style="gridStyle"
              >
                <div class="chart-row__metric is-primary">
                  <div class="chart-row__bar" />
                  <strong class="chart-row__score is-primary">{{ scoreText(entry.score) }}</strong>
                </div>
                <div v-if="hasSecondaryScore(entry)" class="chart-row__metric is-secondary">
                  <div class="chart-row__bar is-secondary" />
                  <strong class="chart-row__score is-secondary">
                    {{ scoreText(entry.secondaryScore ?? 0) }}
                  </strong>
                </div>
              </div>
            </article>
          </div>
        </div>
      </div>
    </PageSection>
  </section>
</template>

<style scoped lang="scss">
  .ranking-page {
    overflow: hidden;

    &__meta {
      margin: 0 0 16px;
      color: var(--kp-text-muted);
      font-size: 12px;

      a {
        color: inherit;
        text-decoration: underline;
        text-underline-offset: 2px;

        &:hover {
          color: var(--kp-primary);
        }
      }
    }

    &__live {
      width: 6px;
      height: 6px;
      flex: 0 0 auto;
      border-radius: 50%;
      background: #31a77c;
      box-shadow: 0 0 0 3px rgba(49, 167, 124, 0.11);
    }

    &__benchmarks {
      display: inline-flex;
      flex: 0 0 auto;
      gap: 2px;
      padding: 3px;
      border: 1px solid rgba(15, 23, 42, 0.06);
      border-radius: 6px;
      background: rgba(76, 91, 116, 0.065);

      a {
        min-width: 112px;
        padding: 7px 12px;
        border-radius: 4px;
        color: #758094;
        font-size: 11px;
        font-weight: 600;
        line-height: 1;
        text-align: center;
        text-decoration: none;
        transition:
          color 0.2s ease,
          background-color 0.2s ease,
          box-shadow 0.2s ease,
          transform 0.2s ease;

        &:hover {
          color: #33445e;
        }

        &:active {
          transform: scale(0.98);
        }

        &.router-link-active {
          color: #365f99;
          background: rgba(255, 255, 255, 0.92);
          box-shadow:
            0 1px 2px rgba(31, 45, 80, 0.06),
            0 3px 9px rgba(31, 45, 80, 0.05);
        }
      }
    }
  }

  .ranking-chart {
    min-height: 650px;
    overflow: hidden;
    border: 1px solid rgba(15, 23, 42, 0.08);
    border-radius: 6px;
    background: rgba(255, 255, 255, 0.66);
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.78);

    &__content {
      padding: 16px 20px 12px;
    }

    &__skeleton {
      display: flex;
      flex-direction: column;
      gap: 18px;
      padding: 28px 24px;
    }

    &__error {
      min-height: 650px;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 12px;
      color: #7d8799;
      font-size: 13px;
    }
  }

  .skeleton-row {
    display: grid;
    grid-template-columns: 28px 220px minmax(0, 1fr);
    align-items: center;
    gap: 18px;

    span {
      height: 12px;
      border-radius: 4px;
      background: linear-gradient(90deg, #edf0f4 25%, #f7f8fa 50%, #edf0f4 75%);
      background-size: 200% 100%;
      animation: skeleton-shimmer 1.4s infinite linear;

      &:first-child {
        height: 28px;
      }

      &:last-child {
        height: 28px;
      }
    }
  }

  .chart-legend {
    display: flex;
    min-height: 22px;
    align-items: center;
    justify-content: flex-end;
    gap: 14px;
    color: #7f899b;
    font-size: 9px;

    span {
      display: inline-flex;
      align-items: center;
      gap: 5px;
    }

    i {
      width: 7px;
      height: 7px;
      border-radius: 2px;

      &.is-primary {
        background: #3f78b8;
      }

      &.is-secondary {
        background: #299879;
      }
    }
  }

  .chart-head,
  .chart-row {
    display: grid;
    grid-template-columns: 44px 250px minmax(260px, 1fr);
    align-items: center;
    gap: 16px;
  }

  .chart-head {
    min-height: 36px;
    padding-bottom: 8px;
    color: #929aac;
    font-size: 10px;
    font-weight: 600;

    &__rank {
      text-align: center;
    }
  }

  .chart-axis {
    display: flex;
    justify-content: space-between;
    padding-right: 1px;
    font-variant-numeric: tabular-nums;
  }

  .chart-rows {
    border-top: 1px solid rgba(15, 23, 42, 0.07);
  }

  .chart-row {
    min-height: 51px;
    padding: 6px 0;
    border-bottom: 1px solid rgba(15, 23, 42, 0.055);

    &:last-child {
      border-bottom: 0;
    }

    &__rank {
      display: inline-flex;
      width: 29px;
      height: 29px;
      align-items: center;
      justify-content: center;
      justify-self: center;
      border-radius: 6px;
      color: #758094;
      background: rgba(105, 119, 143, 0.08);
      font-size: 10px;
      font-weight: 700;
      font-variant-numeric: tabular-nums;

      &.is-rank-1 {
        color: #96651e;
        background: rgba(193, 138, 57, 0.14);
      }

      &.is-rank-2 {
        color: #416da8;
        background: rgba(79, 126, 189, 0.12);
      }

      &.is-rank-3 {
        color: #66738a;
        background: rgba(113, 129, 155, 0.12);
      }
    }

    &__identity {
      min-width: 0;

      strong,
      span {
        display: block;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      strong {
        color: #253047;
        font-size: 12px;
        font-weight: 650;
        letter-spacing: 0;
      }

      span {
        margin-top: 2px;
        color: #8b94a5;
        font-size: 9px;
      }
    }

    &__plot {
      position: relative;
      height: 29px;
      border-left: 1px solid rgba(15, 23, 42, 0.08);
      background-image: linear-gradient(to right, rgba(31, 45, 68, 0.07) 1px, transparent 1px);
      background-size: var(--grid-size) 100%;

      &.is-dual {
        display: flex;
        flex-direction: column;
        justify-content: center;
        gap: 4px;

        .chart-row__metric {
          height: 11px;
        }
      }
    }

    &__metric {
      position: relative;
      height: 100%;
    }

    &__bar {
      width: var(--bar-width);
      height: 100%;
      border-radius: 0 4px 4px 0;
      background: var(--bar-color);
      box-shadow: 0 3px 9px color-mix(in srgb, var(--bar-color) 20%, transparent);
      transform: scaleX(0);
      transform-origin: left center;
      animation: bar-grow 0.62s cubic-bezier(0.22, 1, 0.36, 1) var(--bar-delay) forwards;

      &.is-secondary {
        width: var(--secondary-width);
        background: var(--secondary-color);
        box-shadow: 0 3px 9px color-mix(in srgb, var(--secondary-color) 20%, transparent);
        animation-delay: calc(var(--bar-delay) + 45ms);
      }
    }

    &__score {
      position: absolute;
      top: 50%;
      left: min(calc(var(--bar-width) + 8px), calc(100% - 38px));
      color: #46546a;
      font-size: 10px;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
      transform: translateY(-50%);
      letter-spacing: 0;

      &.is-secondary {
        left: min(calc(var(--secondary-width) + 8px), calc(100% - 38px));
      }
    }

    &__mobile-scores {
      display: none;
    }
  }

  @keyframes bar-grow {
    to {
      transform: scaleX(1);
    }
  }

  @keyframes skeleton-shimmer {
    from {
      background-position: 200% 0;
    }
    to {
      background-position: -200% 0;
    }
  }

  @media (max-width: 960px) {
    .chart-head,
    .chart-row {
      grid-template-columns: 40px 190px minmax(220px, 1fr);
      gap: 12px;
    }
  }

  @media (max-width: 720px) {
    .ranking-page {
      padding: 18px 14px 12px;

      &__header {
        flex-direction: column;
        align-items: stretch;
        gap: 14px;
        margin-bottom: 15px;

        h1 {
          font-size: 20px;
        }
      }

      &__benchmarks {
        display: flex;

        a {
          min-width: 0;
          flex: 1;
          padding: 8px 6px;
        }
      }
    }

    .ranking-chart {
      min-height: 0;

      &__content {
        padding: 8px 12px;
      }

      &__error {
        min-height: 320px;
      }
    }

    .chart-head {
      display: none;
    }

    .chart-row {
      grid-template-columns: 32px minmax(0, 1fr) auto;
      gap: 8px;
      min-height: 72px;
      padding: 10px 0;

      &__rank {
        width: 27px;
        height: 27px;
      }

      &__identity strong {
        font-size: 12px;
      }

      &__identity span {
        font-size: 9px;
      }

      &__mobile-scores {
        display: grid;
        justify-items: end;
        gap: 2px;
        color: #45536a;
        font-size: 11px;
        font-variant-numeric: tabular-nums;
        letter-spacing: 0;

        strong {
          font-size: 11px;

          &.is-secondary {
            color: #24856b;
          }
        }
      }

      &__plot {
        grid-column: 2 / -1;
        height: 8px;
        margin-top: -2px;
        border-left: 0;
        border-radius: 4px;
        background-color: rgba(64, 79, 105, 0.065);
        background-image: none;

        &.is-dual {
          height: 18px;
          gap: 4px;

          .chart-row__metric {
            height: 7px;
          }
        }
      }

      &__bar {
        border-radius: 4px;
        box-shadow: none;
      }

      &__score {
        display: none;
      }
    }

    .skeleton-row {
      grid-template-columns: 28px minmax(0, 1fr);

      span:last-child {
        grid-column: 2;
        height: 8px;
      }
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .chart-row__bar,
    .skeleton-row span {
      animation-duration: 0.01ms;
      animation-delay: 0ms;
    }

    .ranking-page__benchmarks a {
      transition-duration: 0.01ms;
    }
  }
</style>
