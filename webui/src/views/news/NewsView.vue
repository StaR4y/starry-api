<script setup lang="ts">
  import { locale, t } from '@/i18n';
  import { computed, onMounted, ref, watch } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { Notebook, Refresh } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import {
    newsApi,
    type NewsDailyIndexItem,
    type NewsDailyReport,
    type NewsHotTopic,
  } from '@/api/news';
  import { useAppearanceStore } from '@/stores/appearance';

  const route = useRoute();
  const router = useRouter();
  const appearance = useAppearanceStore();
  const dates = ref<NewsDailyIndexItem[]>([]);
  const report = ref<NewsDailyReport | null>(null);
  const topics = ref<NewsHotTopic[]>([]);
  const loadingDates = ref(false);
  const loadingReport = ref(false);
  const activeDate = computed(() => String(route.query.date || dates.value[0]?.date || ''));
  const latestDate = computed(() => dates.value[0]?.date || '');
  const activeIndex = computed(() => dates.value.find((item) => item.date === activeDate.value));
  const showTopics = computed(
    () => activeDate.value !== '' && activeDate.value === latestDate.value,
  );

  function parts(date: string) {
    const parsed = new Date(`${date}T00:00:00`);
    if (Number.isNaN(parsed.getTime())) return { day: date, label: date };
    return {
      day: String(parsed.getDate()),
      label: parsed.toLocaleDateString(locale.value, { month: 'short', weekday: 'short' }),
    };
  }

  async function loadDates() {
    loadingDates.value = true;
    try {
      dates.value = (await newsApi.dailies(90)).items;
      if (!route.query.date && dates.value[0]) {
        await router.replace({ name: 'news', query: { date: dates.value[0].date } });
      }
    } finally {
      loadingDates.value = false;
    }
  }

  async function loadReport(date: string) {
    if (!date) return;
    loadingReport.value = true;
    try {
      report.value = await newsApi.daily(date);
    } catch {
      report.value = null;
    } finally {
      loadingReport.value = false;
    }
  }

  async function loadTopics() {
    try {
      topics.value = (await newsApi.hotTopics()).items.slice(0, 6);
    } catch {
      topics.value = [];
    }
  }

  function openDate(date: string) {
    if (date !== activeDate.value) void router.push({ name: 'news', query: { date } });
  }

  async function reload() {
    await Promise.all([loadDates(), loadTopics()]);
    if (activeDate.value) await loadReport(activeDate.value);
  }

  watch(activeDate, (date) => {
    if (date) void loadReport(date);
  });

  onMounted(reload);
</script>

<template>
  <PageSection :title="t('新闻')" :subtitle="t('按日期回看')" :icon="Notebook">
    <template #actions>
      <el-tooltip :content="t('重新加载')">
        <el-button :icon="Refresh" :aria-label="t('重新加载新闻')" @click="reload" />
      </el-tooltip>
    </template>

    <div class="news" :class="{ 'is-editorial': appearance.isEditorial }">
      <aside v-loading="loadingDates" class="news__dates">
        <button
          v-for="item in dates"
          :key="item.date"
          type="button"
          class="news__date"
          :class="{ 'is-active': item.date === activeDate }"
          @click="openDate(item.date)"
        >
          <em>{{ parts(item.date).day }}</em>
          <span>
            <strong>{{ parts(item.date).label }}</strong>
            <small>{{ item.leadTitle || t('当天没有导语') }}</small>
          </span>
        </button>
        <p v-if="!dates.length && !loadingDates" class="news__empty">
          {{ t('还没有可看的日期。') }}
        </p>
      </aside>

      <div v-loading="loadingReport" class="news__main">
        <header v-if="activeDate" class="news__hero">
          <p>{{ parts(activeDate).label }} · {{ activeDate }}</p>
          <h2>{{ activeIndex?.leadTitle || t('当日要闻') }}</h2>
        </header>

        <section v-if="showTopics && topics.length" class="news__hot">
          <h3>{{ t('正在发生') }}</h3>
          <div class="news__hot-list">
            <component
              :is="topic.originalUrl ? 'a' : 'div'"
              v-for="topic in topics"
              :key="topic.rank"
              class="news__hot-item"
              :href="topic.originalUrl || undefined"
              :target="topic.originalUrl ? '_blank' : undefined"
              :rel="topic.originalUrl ? 'noreferrer' : undefined"
            >
              <em>{{ topic.rank }}</em>
              <span>
                <strong>{{ topic.title }}</strong>
                <small>{{ topic.source }} · {{ topic.sourceCount }} {{ t('个来源') }}</small>
              </span>
            </component>
          </div>
        </section>

        <section
          v-for="section in report?.sections || []"
          :key="section.label"
          class="news__section"
        >
          <h3>
            {{ section.label }}
            <small>{{ section.items.length }}</small>
          </h3>
          <article v-for="item in section.items" :key="item.title" class="news__item">
            <h4>
              <a
                v-if="item.originalUrl"
                :href="item.originalUrl"
                target="_blank"
                rel="noreferrer"
              >{{ item.title }}</a>
              <template v-else>{{ item.title }}</template>
            </h4>
            <p v-if="item.summary">{{ item.summary }}</p>
            <footer v-if="item.source">{{ item.source }}</footer>
          </article>
        </section>
        <p v-if="report && !report.sections.length" class="news__empty">
          {{ t('这一天没有条目。') }}
        </p>
      </div>
    </div>
  </PageSection>
</template>

<style scoped lang="scss">
  .news {
    display: grid;
    grid-template-columns: 248px minmax(0, 1fr);
    gap: 28px;
    align-items: start;
  }

  .news__dates {
    display: flex;
    flex-direction: column;
    gap: 6px;
    max-height: calc(100vh - 210px);
    overflow: auto;
    padding-right: 4px;
    position: sticky;
    top: 12px;
  }

  .news__date {
    display: grid;
    grid-template-columns: 36px minmax(0, 1fr);
    gap: 10px;
    align-items: center;
    width: 100%;
    padding: 8px 10px;
    border: 1px solid transparent;
    border-radius: 14px;
    background: transparent;
    color: var(--kp-text);
    text-align: left;
    cursor: pointer;
    transition:
      background 0.2s ease,
      border-color 0.2s ease;

    em {
      font-style: normal;
      font-size: 18px;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
      color: var(--kp-text-secondary);
    }

    strong,
    small {
      display: block;
    }

    strong {
      font-size: 12px;
      font-weight: 650;
    }

    small {
      margin-top: 2px;
      color: var(--kp-text-muted);
      font-size: 12px;
      line-height: 1.4;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }

    &:hover {
      background: color-mix(in srgb, var(--kp-text) 4%, transparent);
    }

    &.is-active {
      border-color: color-mix(in srgb, var(--kp-primary) 35%, transparent);
      background: var(--kp-primary-soft);

      em {
        color: var(--kp-primary);
      }
    }
  }

  .news__hero {
    p {
      margin: 0;
      color: var(--kp-text-muted);
      font-size: 12px;
      letter-spacing: 0.04em;
    }

    h2 {
      margin: 6px 0 0;
      font-size: 22px;
      line-height: 1.35;
      font-weight: 700;
    }
  }

  .news__hot,
  .news__section {
    margin-top: 22px;

    h3 {
      display: flex;
      align-items: baseline;
      gap: 8px;
      margin: 0 0 12px;
      font-size: 13px;
      letter-spacing: 0.04em;
      color: var(--kp-text-secondary);

      small {
        color: var(--kp-text-muted);
        font-weight: 500;
      }
    }
  }

  .news__hot-list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  .news__hot-item {
    display: grid;
    grid-template-columns: 22px minmax(0, 1fr);
    gap: 8px;
    padding: 10px 12px;
    border-radius: 14px;
    background: color-mix(in srgb, var(--kp-text) 4%, transparent);
    color: inherit;
    text-decoration: none;

    em {
      font-style: normal;
      font-weight: 700;
      color: var(--kp-primary);
    }

    strong,
    small {
      display: block;
    }

    strong {
      font-size: 13px;
      line-height: 1.45;
    }

    small {
      margin-top: 4px;
      color: var(--kp-text-muted);
      font-size: 11px;
    }

    &:hover strong {
      color: var(--kp-primary);
    }
  }

  .news__item {
    padding: 14px 0;
    border-top: 1px solid var(--kp-divider);

    h4 {
      margin: 0;
      font-size: 16px;
      line-height: 1.45;
      font-weight: 650;
    }

    a {
      color: inherit;
      text-decoration: none;
    }

    a:hover {
      color: var(--kp-primary);
    }

    p {
      margin: 8px 0 0;
      color: var(--kp-text-secondary);
      font-size: 13px;
      line-height: 1.7;
    }

    footer {
      margin-top: 8px;
      color: var(--kp-text-muted);
      font-size: 12px;
    }
  }

  .news__empty {
    color: var(--kp-text-muted);
    font-size: 13px;
  }

  .is-editorial {
    .news__date,
    .news__hot-item {
      border-radius: 6px;
    }
  }

  @media (max-width: 900px) {
    .news {
      grid-template-columns: 1fr;
      gap: 16px;
    }

    .news__dates {
      position: static;
      max-height: 210px;
      flex-direction: row;
      overflow: auto;
    }

    .news__date {
      min-width: 210px;
    }

    .news__hot-list {
      grid-template-columns: 1fr;
    }

    .news__hero h2 {
      font-size: 18px;
    }
  }
</style>
