<script setup lang="ts">
  import { t } from '@/i18n';
  import { computed, nextTick, ref, watch } from 'vue';
  import { CopyDocument, Check } from '@element-plus/icons-vue';
  import { ElMessage } from 'element-plus';
  import { renderDocument, type DocHeading } from '@/utils/documentMarkdown';
  import type { DocAttachment } from '@/api/documentation';
  import { copyText } from '@/utils/clipboard';

  const props = defineProps<{ body: string; attachments: DocAttachment[] }>();
  const emit = defineEmits<{ headings: [value: DocHeading[]] }>();
  const root = ref<HTMLElement>();
  const rendered = computed(() => renderDocument(props.body, props.attachments, location.origin));
  const targets = ref<{ element: HTMLElement; index: number }[]>([]);
  const copied = ref(-1);
  let revision = 0;
  watch(
    rendered,
    async (value) => {
      const current = ++revision;
      targets.value = [];
      copied.value = -1;
      emit('headings', value.headings);
      await nextTick();
      if (current !== revision) return;
      targets.value = Array.from(
        root.value?.querySelectorAll<HTMLElement>('.doc-code__tools') ?? [],
      ).map((element) => ({ element, index: Number(element.dataset.codeIndex) }));
    },
    { immediate: true },
  );

  async function copy(index: number) {
    try {
      await copyText(rendered.value.codes[index]);
      copied.value = index;
      ElMessage.success(t('已复制'));
    } catch {
      ElMessage.error(t('复制失败，请手动选择代码复制'));
    }
  }
  function navigate(event: MouseEvent) {
    const link = (event.target as HTMLElement).closest('a');
    const href = link?.getAttribute('href') ?? '';
    if (href.startsWith('#') && !href.startsWith('#/')) {
      event.preventDefault();
      if (href === '#attachment-unavailable') {
        ElMessage.warning(t('附件不可用，请刷新文档'));
        return;
      }
      let id = href.slice(1);
      try {
        id = decodeURIComponent(id);
      } catch {
        return;
      }
      const heading = rendered.value.headings.find((item) => item.id === id || item.text === id);
      if (heading)
        root.value
          ?.querySelector(`[id="${heading.id}"]`)
          ?.scrollIntoView({ block: 'start', behavior: 'smooth' });
    }
  }
</script>

<template>
  <div class="documentation-content">
    <!-- The Markdown renderer disables raw HTML and sanitizes its output. -->
    <!-- eslint-disable-next-line vue/no-v-html -->
    <div ref="root" class="doc-prose" @click="navigate" v-html="rendered.html" />
    <Teleport v-for="target in targets" :key="target.index" :to="target.element">
      <el-tooltip :content="copied === target.index ? t('已复制') : t('复制代码')">
        <button
          class="doc-copy"
          type="button"
          :aria-label="copied === target.index ? t('已复制') : t('复制代码')"
          @click="copy(target.index)"
        >
          <el-icon><Check v-if="copied === target.index" /><CopyDocument v-else /></el-icon>
        </button>
      </el-tooltip>
    </Teleport>
  </div>
</template>

<style lang="scss">
  .documentation-content {
    min-width: 0;
    width: 100%;
  }
  .doc-prose {
    color: var(--kp-text);
    font-size: 14px;
    line-height: 1.9;
    overflow-wrap: anywhere;
    letter-spacing: 0;
    > :first-child {
      margin-top: 0;
    }
    h1,
    h2,
    h3,
    h4,
    h5,
    h6 {
      line-height: 1.5;
      scroll-margin-top: 28px;
      margin: 32px 0 14px;
      font-weight: 600;
    }
    h1 {
      font-size: 24px;
    }
    h2 {
      font-size: 19px;
    }
    h3 {
      font-size: 16px;
    }
    h4,
    h5,
    h6 {
      font-size: 14px;
    }
    p,
    ul,
    ol {
      margin: 14px 0;
    }
    ul,
    ol {
      padding-left: 24px;
    }
    li + li {
      margin-top: 6px;
    }
    a {
      color: var(--kp-primary);
      text-decoration: underline;
      text-underline-offset: 3px;
    }
    a:hover {
      opacity: 0.8;
    }
    blockquote {
      margin: 20px 0;
      padding: 2px 18px;
      border-left: 3px solid var(--kp-primary);
      background: var(--el-fill-color-light);
      color: var(--kp-text-secondary);
    }
    img,
    video {
      display: block;
      max-width: 100%;
      max-height: 560px;
      height: auto;
      border-radius: 6px;
      margin: 18px auto;
    }
    video {
      width: 100%;
      background: #17191d;
    }
    hr {
      border: 0;
      border-top: 1px solid var(--kp-divider);
      margin: 28px 0;
    }
    table {
      border-collapse: collapse;
      display: block;
      max-width: 100%;
      overflow-x: auto;
      margin: 20px 0;
    }
    th,
    td {
      border: 1px solid var(--kp-divider);
      padding: 8px 12px;
      text-align: left;
    }
    th {
      background: var(--el-fill-color-light);
    }
    :not(pre) > code {
      background: var(--el-fill-color);
      border-radius: 4px;
      padding: 2px 5px;
      font-size: 12px;
    }
    code {
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    }
    .doc-media-missing {
      color: var(--el-color-warning);
    }
    .doc-code {
      margin: 20px 0;
      border: 1px solid var(--kp-divider);
      border-radius: 6px;
      overflow: hidden;
      background: var(--el-fill-color-light);
    }
    .doc-code__header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      min-height: 35px;
      padding: 0 10px 0 14px;
      border-bottom: 1px solid var(--kp-divider);
      color: var(--kp-text-secondary);
      font-size: 11px;
      line-height: 1.4;
    }
    .doc-code__tools {
      display: flex;
      align-items: center;
      width: 28px;
      height: 32px;
    }
    pre {
      margin: 0;
      padding: 16px;
      overflow: auto;
      font-size: 12px;
      line-height: 1.8;
      tab-size: 2;
    }
    .doc-copy {
      display: grid;
      place-items: center;
      width: 28px;
      height: 28px;
      padding: 0;
      border: 0;
      border-radius: 4px;
      color: var(--kp-text-secondary);
      background: transparent;
      cursor: pointer;
    }
    .doc-copy:hover {
      background: var(--el-fill-color-darker);
      color: var(--kp-primary);
    }
    .hljs-keyword,
    .hljs-selector-tag,
    .hljs-literal {
      color: #b34772;
    }
    .hljs-string,
    .hljs-attr,
    .hljs-template-variable {
      color: #18764b;
    }
    .hljs-title,
    .hljs-built_in,
    .hljs-type {
      color: #276aaf;
    }
    .hljs-number,
    .hljs-symbol {
      color: #9b580b;
    }
    .hljs-comment {
      color: var(--kp-text-muted);
    }
  }
  html.dark .doc-prose {
    .hljs-keyword,
    .hljs-selector-tag,
    .hljs-literal {
      color: #ef94b6;
    }
    .hljs-string,
    .hljs-attr,
    .hljs-template-variable {
      color: #8bd7a8;
    }
    .hljs-title,
    .hljs-built_in,
    .hljs-type {
      color: #90bffc;
    }
    .hljs-number,
    .hljs-symbol {
      color: #ebbd7e;
    }
  }
</style>
