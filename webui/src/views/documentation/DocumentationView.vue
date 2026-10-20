<script setup lang="ts">
  import { locale, t } from '@/i18n';
  import { computed, onBeforeUnmount, ref, watch } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import {
    Reading,
    Search,
    TopRight,
    EditPen,
    ArrowLeft,
    ArrowRight,
  } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import DocumentationContent from '@/components/DocumentationContent.vue';
  import { documentationApi, type DocCatalog, type DocPage } from '@/api/documentation';
  import type { DocHeading } from '@/utils/documentMarkdown';
  import { useAuthStore } from '@/stores/auth';

  const route = useRoute();
  const router = useRouter();
  const auth = useAuthStore();
  const catalog = ref<DocCatalog>({ settings: { apifoxUrl: '' }, entries: [] });
  const page = ref<DocPage>();
  const query = ref('');
  const loading = ref(true);
  const catalogLoading = ref(true);
  const error = ref('');
  const catalogError = ref(false);
  const headings = ref<DocHeading[]>([]);
  const article = ref<HTMLElement>();
  const groups = computed(() =>
    [...new Set(catalog.value.entries.map((e) => e.category))].map((category) => ({
      category,
      entries: catalog.value.entries.filter((e) => e.category === category),
    })),
  );
  const pageIndex = computed(() => catalog.value.entries.findIndex((e) => e.id === page.value?.id));
  const previous = computed(() => catalog.value.entries[pageIndex.value - 1]);
  const next = computed(() =>
    pageIndex.value < 0 ? undefined : catalog.value.entries[pageIndex.value + 1],
  );
  let searchTimer: ReturnType<typeof setTimeout>;
  let searchRevision = 0;
  let readRevision = 0;

  async function loadCatalog() {
    const revision = ++searchRevision;
    catalogLoading.value = true;
    catalogError.value = false;
    try {
      const result = await documentationApi.catalog(query.value);
      if (revision !== searchRevision) return;
      catalog.value = result;
      if (!route.params.id && result.entries[0])
        await router.replace({ name: 'documentation', params: { id: result.entries[0].id } });
    } catch {
      if (revision === searchRevision) catalogError.value = true;
    } finally {
      if (revision === searchRevision) {
        catalogLoading.value = false;
        if (!route.params.id) loading.value = false;
      }
    }
  }
  async function loadPage(id: string) {
    const revision = ++readRevision;
    page.value = undefined;
    headings.value = [];
    error.value = '';
    if (!id) {
      loading.value = false;
      return;
    }
    loading.value = true;
    try {
      const result = await documentationApi.read(id);
      if (revision === readRevision) page.value = result;
    } catch (e) {
      if (revision === readRevision)
        error.value = e instanceof Error ? e.message : t('文档加载失败');
    } finally {
      if (revision === readRevision) loading.value = false;
    }
  }
  function open(id: string) {
    void router.push({ name: 'documentation', params: { id } });
  }
  function scrollTo(id: string) {
    article.value
      ?.querySelector(`[id="${id}"]`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
  watch(query, () => {
    clearTimeout(searchTimer);
    ++searchRevision;
    searchTimer = setTimeout(() => void loadCatalog(), 250);
  });
  watch(locale, () => {
    clearTimeout(searchTimer);
    void loadCatalog();
    void loadPage(String(route.params.id ?? ''));
  });
  watch(
    () => route.params.id,
    (id) => void loadPage(String(id ?? '')),
    { immediate: true },
  );
  void loadCatalog();
  onBeforeUnmount(() => {
    clearTimeout(searchTimer);
    ++searchRevision;
    ++readRevision;
  });
</script>

<template>
  <PageSection :title="t('使用文档')" :icon="Reading">
    <template #actions>
      <a
        v-if="catalog.settings.apifoxUrl"
        :href="catalog.settings.apifoxUrl"
        target="_blank"
        rel="noopener noreferrer"
        class="docs-external"
      >{{ t('API 参考') }} <el-icon><TopRight /></el-icon></a>
      <el-button
        v-if="auth.isAdmin"
        :icon="EditPen"
        @click="router.push({ name: 'settings-documentation' })"
      >
        {{ t('管理文档') }}
      </el-button>
    </template>
    <div class="docs-layout">
      <aside class="docs-sidebar" :aria-label="t('文档目录')">
        <el-input
          v-model="query"
          :placeholder="t('搜索文档')"
          :prefix-icon="Search"
          clearable
          maxlength="100"
          :aria-label="t('搜索文档')"
        />
        <div v-if="catalogError" class="docs-empty">
          <p>{{ t('目录加载失败') }}</p>
          <el-button @click="loadCatalog">{{ t('重试') }}</el-button>
        </div>
        <div v-else-if="catalogLoading" class="docs-loading">
          <el-skeleton :rows="5" animated />
        </div>
        <nav v-else class="docs-nav">
          <section v-for="group in groups" :key="group.category">
            <h2>{{ group.category }}</h2>
            <button
              v-for="entry in group.entries"
              :key="entry.id"
              :class="{ active: route.params.id === entry.id }"
              :aria-current="route.params.id === entry.id ? 'page' : undefined"
              @click="open(entry.id)"
            >
              {{ entry.title }}
            </button>
          </section>
          <p v-if="!groups.length" class="docs-empty">
            {{ query ? t('没有匹配的文档') : t('暂无已发布文档') }}
          </p>
        </nav>
      </aside>
      <main ref="article" class="docs-article">
        <el-skeleton v-if="loading" :rows="10" animated />
        <div v-else-if="error" class="docs-empty">
          <p>{{ error }}</p>
          <el-button @click="loadPage(String(route.params.id))">{{ t('重新加载') }}</el-button>
        </div>
        <template v-else-if="page">
          <header class="docs-article__header">
            <div class="docs-meta">
              <span>{{ page.content.category }}</span><time>{{ t('更新于') }} {{ new Date(page.updatedAt).toLocaleDateString(locale) }}</time>
            </div>
            <h1>{{ page.content.title }}</h1>
            <p v-if="page.content.summary">{{ page.content.summary }}</p>
          </header>
          <DocumentationContent
            :key="page.id"
            :body="page.content.body"
            :attachments="page.attachments"
            @headings="headings = $event"
          />
          <footer class="docs-pagination">
            <button v-if="previous" @click="open(previous.id)">
              <el-icon><ArrowLeft /></el-icon><span><small>{{ t('上一篇') }}</small>{{ previous.title }}</span>
            </button>
            <button v-if="next" class="docs-pagination__next" @click="open(next.id)">
              <span><small>{{ t('下一篇') }}</small>{{ next.title }}</span><el-icon><ArrowRight /></el-icon>
            </button>
          </footer>
        </template>
        <el-empty v-else :description="query ? t('选择一篇文档开始阅读') : t('暂无已发布文档')" />
      </main>
      <aside v-if="page && headings.length" class="docs-toc" :aria-label="t('本页目录')">
        <span>{{ t('本页目录') }}</span>
        <button
          v-for="heading in headings.filter((h) => h.level <= 3)"
          :key="heading.id"
          :class="{ indent: heading.level > 2 }"
          @click="scrollTo(heading.id)"
        >
          {{ heading.text }}
        </button>
      </aside>
    </div>
  </PageSection>
</template>

<style scoped lang="scss">
  .docs-external {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    color: var(--kp-primary);
    font-size: 13px;
    text-decoration: none;
    padding: 6px;
  }
  .docs-layout {
    display: grid;
    grid-template-columns: 206px minmax(0, 1fr) 160px;
    gap: 30px;
    border-top: 1px solid var(--kp-divider);
    padding-top: 24px;
    min-height: 65vh;
  }
  .docs-sidebar {
    min-width: 0;
  }
  .docs-nav {
    margin-top: 24px;
  }
  .docs-nav section + section {
    margin-top: 24px;
  }
  .docs-nav h2 {
    font-size: 11px;
    color: var(--kp-text-muted);
    font-weight: 500;
    margin: 0 10px 8px;
  }
  .docs-nav button {
    display: block;
    width: 100%;
    border: 0;
    background: transparent;
    color: var(--kp-text-secondary);
    border-radius: 5px;
    padding: 10px;
    text-align: left;
    font-size: 13px;
    line-height: 1.5;
    overflow-wrap: anywhere;
    cursor: pointer;
    transition:
      background 0.15s,
      color 0.15s;
  }
  .docs-nav button:hover {
    background: var(--el-fill-color);
    color: var(--kp-text);
  }
  .docs-nav button.active {
    background: var(--el-color-primary-light-9);
    color: var(--kp-primary);
    font-weight: 600;
  }
  .docs-article {
    min-width: 0;
    width: 100%;
    max-width: 780px;
    margin: 0 auto;
  }
  .docs-article__header {
    margin-bottom: 32px;
  }
  .docs-meta {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 12px;
    font-size: 11px;
    color: var(--kp-text-muted);
  }
  .docs-meta span {
    color: var(--kp-primary);
  }
  .docs-article__header h1 {
    font-size: 26px;
    font-weight: 600;
    line-height: 1.5;
    margin: 12px 0;
    overflow-wrap: anywhere;
  }
  .docs-article__header p {
    margin: 0;
    color: var(--kp-text-secondary);
    font-size: 13px;
    line-height: 1.8;
  }
  .docs-toc {
    padding-left: 16px;
    border-left: 1px solid var(--kp-divider);
    align-self: start;
    position: sticky;
    top: 20px;
  }
  .docs-toc > span {
    display: block;
    color: var(--kp-text-muted);
    font-size: 11px;
    margin: 2px 0 14px;
  }
  .docs-toc button {
    display: block;
    padding: 6px 0;
    background: none;
    border: 0;
    color: var(--kp-text-secondary);
    text-align: left;
    font-size: 12px;
    line-height: 1.6;
    cursor: pointer;
    overflow-wrap: anywhere;
  }
  .docs-toc button:hover {
    color: var(--kp-primary);
  }
  .docs-toc button.indent {
    padding-left: 10px;
  }
  .docs-empty {
    color: var(--kp-text-muted);
    font-size: 13px;
    padding: 24px 0;
    text-align: center;
  }
  .docs-loading {
    padding: 24px 0;
  }
  .docs-pagination {
    display: flex;
    gap: 20px;
    flex-wrap: wrap;
    margin-top: 48px;
    padding: 20px 0;
    border-top: 1px solid var(--kp-divider);
  }
  .docs-pagination button {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 0;
    background: none;
    border: 0;
    color: var(--kp-text);
    cursor: pointer;
    font-size: 13px;
    text-align: left;
    max-width: 100%;
    overflow-wrap: anywhere;
  }
  .docs-pagination small {
    display: block;
    color: var(--kp-text-muted);
    font-size: 11px;
    margin-bottom: 6px;
  }
  .docs-pagination__next {
    margin-left: auto;
    text-align: right !important;
  }
  @media (min-width: 1650px) {
    .docs-layout {
      gap: 48px;
      grid-template-columns: 230px minmax(0, 1fr) 190px;
    }
  }
  @media (max-width: 1200px) {
    .docs-layout {
      grid-template-columns: 185px minmax(0, 1fr);
      gap: 24px;
    }
    .docs-toc {
      display: none;
    }
  }
  @media (max-width: 720px) {
    .docs-layout {
      display: flex;
      flex-direction: column;
      padding-top: 16px;
      gap: 24px;
    }
    .docs-nav {
      display: flex;
      gap: 18px;
      max-height: 210px;
      overflow: auto;
      margin-top: 12px;
      padding-bottom: 12px;
      border-bottom: 1px solid var(--kp-divider);
    }
    .docs-nav section {
      flex: 0 0 175px;
      margin: 0 !important;
    }
    .docs-nav button {
      padding: 6px 10px;
      font-size: 12px;
    }
    .docs-article__header h1 {
      font-size: 22px;
    }
  }
</style>
