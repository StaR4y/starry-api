<script setup lang="ts">
  import { locale, t } from '@/i18n';
  import { computed, nextTick, onBeforeUnmount, reactive, ref } from 'vue';
  import { onBeforeRouteLeave, useRouter } from 'vue-router';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import {
    Reading,
    Plus,
    Search,
    Delete,
    Upload,
    Link,
    Picture,
    VideoCamera,
    Paperclip,
    Document,
    Refresh,
    TopRight,
  } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import DocumentationContent from '@/components/DocumentationContent.vue';
  import {
    documentationApi,
    type DocAdminPage,
    type DocAdminEntry,
    type DocAttachment,
    type DocContent,
  } from '@/api/documentation';
  import { documentationTemplates } from '@/views/documentation/templates';
  import { safeDocLink } from '@/utils/documentMarkdown';
  import { ApiError } from '@/utils/http';

  const router = useRouter();
  const tab = ref('pages');
  const entries = ref<DocAdminEntry[]>([]);
  const current = ref<DocAdminPage>();
  const draft = ref<DocContent>();
  const contentLanguage = ref('original');
  const editingContent = computed(() =>
    contentLanguage.value === 'english' ? draft.value?.english : draft.value,
  );
  function changeContentLanguage(value: string | number | boolean | undefined) {
    if (!draft.value || locked.value) return;
    if (value === 'english' && !draft.value.english) {
      draft.value.english = { title: '', category: 'User guide', summary: '', body: '' };
    }
    contentLanguage.value = String(value);
    selection = { start: 0, end: 0 };
  }
  function removeEnglish() {
    if (!draft.value || locked.value) return;
    draft.value.english = null;
    contentLanguage.value = 'original';
  }
  const query = ref('');
  const mode = ref('edit');
  const busy = ref(false);
  const loading = ref(true);
  const loadError = ref('');
  const conflict = ref(false);
  const actionError = ref('');
  const apifoxUrl = ref('');
  const savedApifoxUrl = ref('');
  const settingsReady = ref(false);
  const settingsBusy = ref(false);
  const newDialog = ref(false);
  const newTemplate = ref('blank');
  const newTitle = ref('');
  const editor = ref<HTMLTextAreaElement>();
  const fileInput = ref<HTMLInputElement>();
  const fileAccept = ref('');
  const uploading = ref(false);
  const uploadProgress = ref(0);
  const linkDialog = ref(false);
  const linkForm = reactive({ text: '', url: '' });
  const codeDialog = ref(false);
  const codeForm = reactive({ language: 'bash', content: '' });
  let selection = { start: 0, end: 0 };
  const filtered = computed(() =>
    entries.value.filter((e) =>
      `${e.title} ${e.category}`.toLowerCase().includes(query.value.toLowerCase()),
    ),
  );
  const dirty = computed(
    () => !!draft.value && JSON.stringify(draft.value) !== JSON.stringify(current.value?.draft),
  );
  const settingsDirty = computed(() => apifoxUrl.value !== savedApifoxUrl.value);
  const locked = computed(() => busy.value || uploading.value);
  const hasUnpublishedChanges = computed(
    () => !!draft.value && JSON.stringify(draft.value) !== JSON.stringify(current.value?.published),
  );

  function accept(page: DocAdminPage) {
    if (current.value?.id !== page.id) selection = { start: 0, end: 0 };
    current.value = page;
    draft.value = structuredClone(page.draft);
    if (contentLanguage.value === 'english' && !draft.value.english)
      contentLanguage.value = 'original';
    conflict.value = false;
    actionError.value = '';
  }
  async function refreshList() {
    entries.value = await documentationApi.list();
  }
  async function load() {
    loading.value = true;
    loadError.value = '';
    try {
      await refreshList();
      if (entries.value[0]) accept(await documentationApi.detail(entries.value[0].id));
    } catch (e) {
      loadError.value = e instanceof Error ? e.message : t('文档加载失败');
    } finally {
      loading.value = false;
    }
  }
  async function loadSettings() {
    try {
      const settings = await documentationApi.settings();
      apifoxUrl.value = savedApifoxUrl.value = settings.apifoxUrl;
      settingsReady.value = true;
    } catch {
      settingsReady.value = false;
    }
  }
  async function discard(all = false) {
    if (locked.value || settingsBusy.value) return false;
    if (!dirty.value && !(all && settingsDirty.value)) return true;
    return ElMessageBox.confirm(t('有尚未保存的修改，确定离开？'), t('放弃修改'), {
      get confirmButtonText() {
        return t('放弃修改');
      },
      get cancelButtonText() {
        return t('继续编辑');
      },
      type: 'warning',
    })
      .then(() => true)
      .catch(() => false);
  }
  async function select(id: string, force = false) {
    if ((!force && id === current.value?.id) || !(await discard())) return;
    busy.value = true;
    actionError.value = '';
    try {
      accept(await documentationApi.detail(id));
    } catch (e) {
      actionError.value = e instanceof Error ? e.message : t('加载失败');
    } finally {
      busy.value = false;
    }
  }
  async function create() {
    if (!newTitle.value.trim() || locked.value) return;
    busy.value = true;
    try {
      const template = documentationTemplates.find((t) => t.id === newTemplate.value)!;
      accept(await documentationApi.create({ ...template.content, title: newTitle.value.trim() }));
      newDialog.value = false;
      mode.value = 'edit';
      await refreshList();
    } catch (e) {
      report(e);
    } finally {
      busy.value = false;
    }
  }
  async function startCreate() {
    if (!(await discard())) return;
    newTitle.value = '';
    newTemplate.value = 'blank';
    newDialog.value = true;
  }
  function report(e: unknown) {
    actionError.value = e instanceof Error ? e.message : t('操作失败');
    conflict.value = e instanceof ApiError && e.code === 'DOCUMENTATION_CONFLICT';
  }
  async function save(publish = false) {
    if (!draft.value || !current.value || locked.value || conflict.value) return;
    if (!draft.value.title.trim() || !draft.value.category.trim()) {
      ElMessage.warning(t('请填写标题和分类'));
      return;
    }
    if (publish && !draft.value.body.trim()) {
      ElMessage.warning(t('请先填写正文'));
      return;
    }
    const english = draft.value.english;
    if (english && (!english.title.trim() || !english.category.trim() || !english.body.trim())) {
      ElMessage.warning(t('请填写英文版标题、分类和正文，或移除英文版'));
      return;
    }
    busy.value = true;
    actionError.value = '';
    try {
      accept(
        await documentationApi.save(
          current.value.id,
          { ...draft.value },
          current.value.version,
          publish,
        ),
      );
      ElMessage.success(publish ? t('文档已发布') : t('草稿已保存'));
      await refreshList();
    } catch (e) {
      report(e);
    } finally {
      busy.value = false;
    }
  }
  async function withdraw(remove: boolean) {
    if (!current.value || locked.value || !(await discard())) return;
    const confirmed = await ElMessageBox.confirm(
      remove
        ? t('删除后，用户将无法阅读这篇文档。确定删除？')
        : t('下线后用户将无法阅读，草稿会保留。确定下线？'),
      remove ? t('删除文档') : t('下线文档'),
      {
        confirmButtonText: remove ? t('删除') : t('下线'),
        get cancelButtonText() {
          return t('取消');
        },
        type: 'warning',
      },
    )
      .then(() => true)
      .catch(() => false);
    if (!confirmed) return;
    busy.value = true;
    try {
      const id = current.value.id;
      await documentationApi.withdraw(id, current.value.version, remove);
      if (remove) {
        current.value = undefined;
        draft.value = undefined;
      } else accept(await documentationApi.detail(id));
      await refreshList();
      if (remove && entries.value[0]) accept(await documentationApi.detail(entries.value[0].id));
      ElMessage.success(remove ? t('文档已删除') : t('文档已下线'));
    } catch (e) {
      report(e);
    } finally {
      busy.value = false;
    }
  }
  async function saveSettings() {
    const url = apifoxUrl.value.trim();
    if (url) {
      try {
        const parsed = new URL(url);
        if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password)
          throw new Error();
      } catch {
        ElMessage.warning(t('请输入有效的 HTTP/HTTPS 文档链接'));
        return;
      }
    }
    settingsBusy.value = true;
    try {
      const result = await documentationApi.saveSettings({ apifoxUrl: url });
      apifoxUrl.value = savedApifoxUrl.value = result.apifoxUrl;
      ElMessage.success(t('设置已保存'));
    } catch {
      /* The HTTP client displays the server error. */
    } finally {
      settingsBusy.value = false;
    }
  }
  function rememberSelection() {
    if (editor.value)
      selection = { start: editor.value.selectionStart, end: editor.value.selectionEnd };
  }
  async function insert(before: string, after = '', replace = false) {
    if (!editingContent.value) return;
    const body = editingContent.value.body;
    const start = Math.min(selection.start, body.length);
    const end = Math.min(selection.end, body.length);
    const selected = replace ? '' : body.slice(start, end);
    const text = body.slice(0, start) + before + selected + after + body.slice(end);
    if (text.length > 100000) {
      ElMessage.warning(t('正文最多 100,000 字符'));
      return;
    }
    editingContent.value.body = text;
    mode.value = 'edit';
    await nextTick();
    editor.value?.focus();
    const caret = start + before.length + selected.length + after.length;
    editor.value?.setSelectionRange(caret, caret);
    selection = { start: caret, end: caret };
  }
  function openLink() {
    rememberSelection();
    linkForm.text = editingContent.value?.body.slice(selection.start, selection.end) || '';
    linkForm.url = '';
    linkDialog.value = true;
  }
  function addLink() {
    if (!safeDocLink(linkForm.url.trim())) {
      ElMessage.warning(t('请输入 HTTP/HTTPS、邮件或站内跳转链接'));
      return;
    }
    const label = (linkForm.text || linkForm.url).replace(/[[\]\\]/g, '\\$&');
    const url = linkForm.url.trim().replace(/\(/g, '%28').replace(/\)/g, '%29');
    void insert(`[${label}](${url})`, '', true);
    linkDialog.value = false;
  }
  function addCode() {
    const longest = Math.max(2, ...(codeForm.content.match(/`+/g) ?? []).map((s) => s.length));
    const fence = '`'.repeat(longest + 1);
    void insert(`\n\n${fence}${codeForm.language}\n${codeForm.content}\n${fence}\n\n`);
    codeDialog.value = false;
  }
  async function pickFile(kind: 'image' | 'video' | 'all') {
    rememberSelection();
    fileAccept.value =
      kind === 'image'
        ? '.png,.jpg,.jpeg,.gif,.webp'
        : kind === 'video'
          ? '.mp4,.webm'
          : '.png,.jpg,.jpeg,.gif,.webp,.mp4,.webm,.pdf,.zip,.txt,.json,.md';
    await nextTick();
    fileInput.value?.click();
  }
  function insertAttachment(file: DocAttachment) {
    const label = file.filename.replace(/[[\]\\]/g, '\\$&');
    void insert(
      `\n\n${/^(image|video)\//.test(file.contentType) ? '!' : ''}[${label}](attachment:${file.id})\n\n`,
    );
  }
  async function upload(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !current.value || locked.value) return;
    if (file.size === 0 || file.size > 64 * 1024 * 1024) {
      ElMessage.warning(t('附件大小须在 1 B 到 64 MB 之间'));
      return;
    }
    uploading.value = true;
    uploadProgress.value = 0;
    try {
      const result = await documentationApi.upload(
        current.value.id,
        file,
        (p) => (uploadProgress.value = p),
      );
      current.value.attachments.push(result);
      insertAttachment(result);
    } catch (e) {
      report(e);
    } finally {
      uploading.value = false;
    }
  }
  async function removeAttachment(file: DocAttachment) {
    if (!current.value || locked.value) return;
    if (
      [
        draft.value?.body,
        draft.value?.english?.body,
        current.value.draft.body,
        current.value.draft.english?.body,
        current.value.published?.body,
        current.value.published?.english?.body,
      ].some((body) => body?.includes(`attachment:${file.id}`))
    ) {
      ElMessage.warning(t('请先移除正文中的附件引用，并保存草稿、更新发布后再删除'));
      return;
    }
    const confirmed = await ElMessageBox.confirm(
      t('删除附件“{p0}”？', { p0: file.filename }),
      t('删除附件'),
      {
        get confirmButtonText() {
          return t('删除');
        },
        get cancelButtonText() {
          return t('取消');
        },
      },
    )
      .then(() => true)
      .catch(() => false);
    if (!confirmed) return;
    busy.value = true;
    try {
      await documentationApi.removeAttachment(current.value.id, file.id);
      current.value.attachments = current.value.attachments.filter((a) => a.id !== file.id);
    } catch (e) {
      report(e);
    } finally {
      busy.value = false;
    }
  }
  function beforeUnload(event: BeforeUnloadEvent) {
    if (dirty.value || settingsDirty.value || locked.value) {
      event.preventDefault();
      event.returnValue = '';
    }
  }
  onBeforeRouteLeave(() => discard(true));
  window.addEventListener('beforeunload', beforeUnload);
  onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload));
  void load();
  void loadSettings();
</script>

<template>
  <PageSection :title="t('文档管理')" :icon="Reading">
    <template #actions>
      <el-button :icon="Reading" @click="router.push({ name: 'documentation' })">
        {{ t('阅读文档') }}
      </el-button>
    </template>
    <el-tabs v-model="tab">
      <el-tab-pane :label="t('文档')" name="pages">
        <el-skeleton v-if="loading" :rows="8" animated />
        <div v-else-if="loadError" class="editor-empty">
          <p>{{ loadError }}</p>
          <el-button @click="load">{{ t('重试') }}</el-button>
        </div>
        <div v-else class="doc-manager">
          <div class="doc-manager__mobile">
            <el-select
              :model-value="current?.id"
              filterable
              :placeholder="t('选择文档')"
              :aria-label="t('选择文档')"
              :disabled="locked"
              @change="select"
            >
              <el-option
                v-for="entry in entries"
                :key="entry.id"
                :value="entry.id"
                :label="`${entry.title} · ${entry.published ? t('已发布') : t('草稿')}`"
              />
            </el-select>
            <el-button
              :icon="Plus"
              :aria-label="t('新建文档')"
              :disabled="locked"
              @click="startCreate"
            />
          </div>
          <aside class="doc-manager__list">
            <div class="doc-manager__search">
              <el-input
                v-model="query"
                :prefix-icon="Search"
                :placeholder="t('查找文档')"
                clearable
                :aria-label="t('查找文档')"
              /><el-tooltip :content="t('新建文档')">
                <el-button
                  :icon="Plus"
                  :aria-label="t('新建文档')"
                  :disabled="locked"
                  @click="startCreate"
                />
              </el-tooltip>
            </div>
            <button
              v-for="entry in filtered"
              :key="entry.id"
              class="doc-list-row"
              :class="{ active: current?.id === entry.id }"
              :disabled="locked"
              @click="select(entry.id)"
            >
              <span class="doc-list-row__category">{{ entry.category }}</span>
              <strong>{{ entry.title }}</strong>
              <span class="doc-list-row__status" :class="{ published: entry.published }"><i />{{
                entry.published
                  ? entry.hasChanges
                    ? t('已发布 · 有草稿')
                    : t('已发布')
                  : t('草稿')
              }}</span>
            </button>
            <p v-if="!filtered.length" class="editor-empty">
              {{ query ? t('没有匹配的文档') : t('暂无文档') }}
            </p>
          </aside>
          <section v-if="draft && current && editingContent" class="doc-editor">
            <div class="doc-editor__actions">
              <span class="doc-editor__state">{{
                dirty
                  ? t('未保存')
                  : current.published
                    ? hasUnpublishedChanges
                      ? t('已发布 · 有草稿')
                      : t('已发布')
                    : t('草稿')
              }}</span>
              <el-tooltip :content="t('重新加载')">
                <el-button
                  :icon="Refresh"
                  :aria-label="t('重新加载')"
                  :disabled="locked"
                  @click="select(current.id, true)"
                />
              </el-tooltip>
              <el-button
                :disabled="locked || !dirty || conflict"
                :loading="busy"
                @click="save(false)"
              >
                {{ t('保存草稿') }}
              </el-button>
              <el-button
                type="primary"
                :icon="Upload"
                :disabled="locked || !hasUnpublishedChanges || conflict"
                @click="save(true)"
              >
                {{ current.published ? t('更新发布') : t('发布') }}
              </el-button>
            </div>
            <el-alert
              v-if="actionError"
              :title="actionError"
              type="error"
              :closable="false"
              class="doc-error"
            />
            <div class="doc-editor__languages">
              <el-radio-group
                :model-value="contentLanguage"
                :disabled="locked"
                :aria-label="t('文档语言')"
                @change="changeContentLanguage"
              >
                <el-radio-button value="original">{{ t('原文') }}</el-radio-button>
                <el-radio-button value="english">English</el-radio-button>
              </el-radio-group>
              <el-button
                v-if="draft.english && contentLanguage === 'english'"
                :disabled="locked"
                :icon="Delete"
                text
                @click="removeEnglish"
              >
                {{ t('移除英文版') }}
              </el-button>
            </div>
            <el-form label-position="top" :disabled="locked">
              <el-form-item :label="t('标题')">
                <el-input
                  v-model="editingContent!.title"
                  maxlength="100"
                  :aria-label="t('文档标题')"
                />
              </el-form-item>
              <div class="doc-editor__metadata">
                <el-form-item :label="t('分类')">
                  <el-input
                    v-model="editingContent!.category"
                    maxlength="60"
                    :aria-label="t('文档分类')"
                  />
                </el-form-item>
                <el-form-item :label="t('排序')">
                  <el-input-number
                    v-model="draft.sortOrder"
                    :min="0"
                    :max="9999"
                    controls-position="right"
                    :aria-label="t('文档排序')"
                  />
                </el-form-item>
              </div>
              <el-form-item :label="t('摘要')">
                <el-input
                  v-model="editingContent!.summary"
                  maxlength="240"
                  :aria-label="t('文档摘要')"
                />
              </el-form-item>
            </el-form>
            <div class="doc-editor__toolbar">
              <div class="doc-editor__tools">
                <el-tooltip :content="t('二级标题')">
                  <button :disabled="locked" :aria-label="t('二级标题')" @click="insert('\n\n## ')">
                    H₂
                  </button>
                </el-tooltip>
                <el-tooltip :content="t('加粗')">
                  <button :disabled="locked" :aria-label="t('加粗')" @click="insert('**', '**')">
                    <b>B</b>
                  </button>
                </el-tooltip>
                <el-tooltip :content="t('插入链接')">
                  <button :disabled="locked" :aria-label="t('插入链接')" @click="openLink">
                    <el-icon><Link /></el-icon>
                  </button>
                </el-tooltip>
                <el-tooltip :content="t('插入代码')">
                  <button
                    :disabled="locked"
                    :aria-label="t('插入代码')"
                    @click="
                      rememberSelection();
                      codeForm.content = '';
                      codeDialog = true;
                    "
                  >
                    <el-icon><Document /></el-icon>
                  </button>
                </el-tooltip>
                <el-tooltip :content="t('上传图片')">
                  <button :disabled="locked" :aria-label="t('上传图片')" @click="pickFile('image')">
                    <el-icon><Picture /></el-icon>
                  </button>
                </el-tooltip>
                <el-tooltip :content="t('上传视频')">
                  <button :disabled="locked" :aria-label="t('上传视频')" @click="pickFile('video')">
                    <el-icon><VideoCamera /></el-icon>
                  </button>
                </el-tooltip>
                <el-tooltip :content="t('上传附件')">
                  <button :disabled="locked" :aria-label="t('上传附件')" @click="pickFile('all')">
                    <el-icon><Paperclip /></el-icon>
                  </button>
                </el-tooltip>
              </div>
              <el-radio-group v-model="mode" size="small" :aria-label="t('编辑模式')">
                <el-radio-button value="edit">{{ t('编辑') }}</el-radio-button><el-radio-button value="preview">{{ t('预览') }}</el-radio-button>
              </el-radio-group>
            </div>
            <textarea
              v-if="mode === 'edit'"
              ref="editor"
              v-model="editingContent!.body"
              class="doc-editor__source"
              :disabled="locked"
              maxlength="100000"
              spellcheck="false"
              :aria-label="t('Markdown 正文')"
              @select="rememberSelection"
              @click="rememberSelection"
              @keyup="rememberSelection"
              @blur="rememberSelection"
            />
            <div v-else class="doc-editor__preview">
              <DocumentationContent
                :body="editingContent!.body"
                :attachments="current.attachments"
              />
            </div>
            <div class="doc-editor__footnote">
              <span>Markdown</span><span>{{ editingContent!.body.length.toLocaleString(locale) }} / 100,000</span>
            </div>
            <input ref="fileInput" type="file" :accept="fileAccept" hidden @change="upload">
            <el-progress v-if="uploading" :percentage="uploadProgress" :stroke-width="3" />
            <div class="doc-attachments">
              <header>
                <span>{{ t('附件') }} <small>{{ current.attachments.length }} / 20</small></span><el-button
                  :icon="Plus"
                  text
                  size="small"
                  :disabled="locked"
                  @click="pickFile('all')"
                >
                  {{ t('添加') }}
                </el-button>
              </header>
              <div v-for="file in current.attachments" :key="file.id" class="doc-attachment">
                <el-icon>
                  <Picture v-if="file.contentType.startsWith('image/')" /><VideoCamera
                    v-else-if="file.contentType.startsWith('video/')"
                  /><Paperclip v-else />
                </el-icon>
                <a :href="file.url" target="_blank" rel="noopener noreferrer">{{
                  file.filename
                }}</a>
                <small>{{ (file.sizeBytes / 1024 / 1024).toFixed(1) }} MB</small>
                <el-tooltip :content="t('插入正文')">
                  <el-button
                    :icon="Plus"
                    text
                    size="small"
                    :aria-label="t('插入正文')"
                    :disabled="locked"
                    @click="insertAttachment(file)"
                  />
                </el-tooltip>
                <el-tooltip :content="t('删除附件')">
                  <el-button
                    :icon="Delete"
                    text
                    size="small"
                    :aria-label="t('删除附件')"
                    :disabled="locked"
                    @click="removeAttachment(file)"
                  />
                </el-tooltip>
              </div>
              <p v-if="!current.attachments.length" class="doc-attachment__hint">
                {{ t('图片、MP4 / WebM 视频、PDF、ZIP、TXT、JSON、Markdown；单个最大 64 MB。') }}
              </p>
            </div>
            <footer class="doc-editor__footer">
              <a
                v-if="current.published"
                :href="`#/documentation/${current.id}`"
                target="_blank"
                rel="noopener noreferrer"
              >{{ t('查看发布版本') }} <el-icon><TopRight /></el-icon></a>
              <el-button v-if="current.published" text :disabled="locked" @click="withdraw(false)">
                {{ t('下线') }}
              </el-button>
              <el-button
                text
                type="danger"
                :icon="Delete"
                :disabled="locked"
                @click="withdraw(true)"
              >
                {{ t('删除文档') }}
              </el-button>
            </footer>
          </section>
          <div v-else class="editor-empty">
            <el-empty :description="t('创建第一篇文档')">
              <el-button type="primary" :icon="Plus" @click="startCreate">
                {{ t('新建文档') }}
              </el-button>
            </el-empty>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane :label="t('文档设置')" name="settings">
        <div class="doc-settings">
          <template v-if="settingsReady">
            <el-form label-position="top" @submit.prevent="saveSettings">
              <el-form-item :label="t('Apifox 文档链接')">
                <el-input
                  v-model="apifoxUrl"
                  :disabled="settingsBusy"
                  placeholder="https://…"
                  maxlength="2000"
                  clearable
                  :aria-label="t('Apifox 文档链接')"
                />
              </el-form-item><el-button
                type="primary"
                native-type="submit"
                :disabled="!settingsDirty"
                :loading="settingsBusy"
              >
                {{ t('保存设置') }}
              </el-button>
            </el-form>
          </template>
          <el-button v-else @click="loadSettings">{{ t('重新加载设置') }}</el-button>
        </div>
      </el-tab-pane>
    </el-tabs>
    <el-dialog
      v-model="newDialog"
      :title="t('新建文档')"
      width="440px"
      :close-on-click-modal="!busy"
      :show-close="!busy"
      :close-on-press-escape="!busy"
    >
      <el-form label-position="top" :disabled="busy" @submit.prevent="create">
        <el-form-item :label="t('标题')">
          <el-input v-model="newTitle" maxlength="100" autofocus :aria-label="t('新文档标题')" />
        </el-form-item>
        <el-form-item :label="t('模板')">
          <el-select v-model="newTemplate" :aria-label="t('文档模板')">
            <el-option
              v-for="template in documentationTemplates"
              :key="template.id"
              :label="template.name"
              :value="template.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="busy" @click="newDialog = false">{{ t('取消') }}</el-button><el-button type="primary" :disabled="!newTitle.trim()" :loading="busy" @click="create">
          {{ t('创建草稿') }}
        </el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="linkDialog" :title="t('插入链接')" width="440px">
      <el-form label-position="top">
        <el-form-item :label="t('文字')"><el-input v-model="linkForm.text" /></el-form-item><el-form-item :label="t('地址')">
          <el-input
            v-model="linkForm.url"
            :placeholder="t('https:// 或 #/站内路径')"
            @keyup.enter="addLink"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="linkDialog = false">{{ t('取消') }}</el-button><el-button type="primary" :disabled="!linkForm.url.trim()" @click="addLink">
          {{ t('插入') }}
        </el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="codeDialog" :title="t('插入代码')" width="600px">
      <el-form label-position="top">
        <el-form-item :label="t('语言')">
          <el-select v-model="codeForm.language">
            <el-option
              v-for="language in [
                'bash',
                'json',
                'python',
                'javascript',
                'typescript',
                'toml',
                'yaml',
                'http',
                'sql',
                'xml',
                'css',
                'text',
              ]"
              :key="language"
              :label="language"
              :value="language"
            />
          </el-select>
        </el-form-item><el-form-item :label="t('代码')">
          <el-input v-model="codeForm.content" type="textarea" :rows="9" maxlength="90000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="codeDialog = false">{{ t('取消') }}</el-button><el-button type="primary" @click="addCode">{{ t('插入') }}</el-button>
      </template>
    </el-dialog>
  </PageSection>
</template>

<style scoped lang="scss">
  .doc-editor__languages {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
    margin-bottom: 20px;
  }
  .doc-manager {
    display: grid;
    grid-template-columns: 215px minmax(0, 1fr);
    gap: 26px;
    padding-top: 4px;
  }
  .doc-manager__list {
    min-width: 0;
    border-right: 1px solid var(--kp-divider);
    padding-right: 18px;
  }
  .doc-manager__mobile {
    display: none;
  }
  .doc-manager__search {
    display: flex;
    gap: 6px;
    margin-bottom: 16px;
  }
  .doc-manager__search > .el-button {
    width: 32px;
    padding: 0;
    flex-shrink: 0;
  }
  .doc-list-row {
    display: flex;
    flex-direction: column;
    gap: 6px;
    width: 100%;
    padding: 12px 10px;
    text-align: left;
    border: 0;
    border-radius: 5px;
    background: transparent;
    color: var(--kp-text);
    cursor: pointer;
    margin-bottom: 3px;
  }
  .doc-list-row:hover {
    background: var(--el-fill-color);
  }
  .doc-list-row.active {
    background: var(--el-color-primary-light-9);
  }
  .doc-list-row strong {
    font-size: 13px;
    font-weight: 500;
    line-height: 1.5;
    overflow-wrap: anywhere;
  }
  .doc-list-row__category {
    color: var(--kp-text-muted);
    font-size: 10px;
  }
  .doc-list-row__status {
    display: flex;
    align-items: center;
    gap: 5px;
    color: var(--kp-text-secondary);
    font-size: 10px;
  }
  .doc-list-row__status i {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: var(--el-color-warning);
  }
  .doc-list-row__status.published i {
    background: var(--el-color-success);
  }
  .doc-editor {
    min-width: 0;
  }
  .doc-editor__actions {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
    padding: 0 0 18px;
  }
  .doc-editor__actions .el-button + .el-button {
    margin-left: 0;
  }
  .doc-editor__state {
    color: var(--kp-text-muted);
    font-size: 11px;
    margin-right: auto;
  }
  .doc-editor__metadata {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 112px;
    gap: 16px;
  }
  .doc-editor__metadata .el-input-number {
    width: 100%;
  }
  .doc-editor :deep(.el-form-item) {
    margin-bottom: 16px;
  }
  .doc-editor__toolbar {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    justify-content: space-between;
    gap: 8px;
    border: 1px solid var(--kp-divider);
    border-bottom: 0;
    padding: 8px;
    border-radius: 6px 6px 0 0;
  }
  .doc-editor__tools {
    display: flex;
    gap: 2px;
  }
  .doc-editor__tools button {
    display: grid;
    place-items: center;
    width: 29px;
    height: 29px;
    border: 0;
    border-radius: 4px;
    background: transparent;
    color: var(--kp-text-secondary);
    cursor: pointer;
    font-size: 14px;
  }
  .doc-editor__tools button:hover {
    background: var(--el-fill-color);
    color: var(--kp-primary);
  }
  .doc-editor__source {
    display: block;
    box-sizing: border-box;
    width: 100%;
    min-height: 440px;
    resize: vertical;
    border: 1px solid var(--kp-divider);
    border-radius: 0;
    padding: 18px;
    background: var(--el-bg-color);
    color: var(--kp-text);
    font:
      13px/1.9 ui-monospace,
      SFMono-Regular,
      Menlo,
      Consolas,
      monospace;
    outline: none;
    tab-size: 2;
  }
  .doc-editor__source:focus {
    border-color: var(--kp-primary);
  }
  .doc-editor__preview {
    min-height: 440px;
    border: 1px solid var(--kp-divider);
    padding: 20px;
    overflow: hidden;
  }
  .doc-editor__footnote {
    display: flex;
    justify-content: space-between;
    font-size: 10px;
    color: var(--kp-text-muted);
    border: 1px solid var(--kp-divider);
    border-top: 0;
    padding: 7px 12px;
    border-radius: 0 0 6px 6px;
  }
  .doc-attachments {
    margin-top: 22px;
  }
  .doc-attachments > header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    color: var(--kp-text);
    font-size: 13px;
  }
  .doc-attachments small,
  .doc-attachment__hint {
    color: var(--kp-text-muted);
    font-size: 11px;
  }
  .doc-attachment__hint {
    line-height: 1.8;
  }
  .doc-attachment {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 9px 0;
    border-bottom: 1px solid var(--kp-divider);
    font-size: 12px;
  }
  .doc-attachment a {
    flex: 1;
    min-width: 0;
    color: var(--kp-text);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .doc-attachment .el-button {
    margin: 0;
    padding: 4px;
  }
  .doc-editor__footer {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: flex-end;
    gap: 8px;
    border-top: 1px solid var(--kp-divider);
    margin-top: 28px;
    padding-top: 14px;
  }
  .doc-editor__footer a {
    margin-right: auto;
    display: inline-flex;
    gap: 5px;
    align-items: center;
    font-size: 12px;
    color: var(--kp-primary);
    text-decoration: none;
  }
  .doc-settings {
    max-width: 650px;
    padding: 14px 0 30px;
  }
  .doc-error {
    margin-bottom: 16px;
  }
  .editor-empty {
    padding: 20px 0;
    color: var(--kp-text-muted);
    font-size: 12px;
    text-align: center;
  }
  @media (max-width: 1200px) {
    .doc-manager {
      grid-template-columns: 180px minmax(0, 1fr);
      gap: 18px;
    }
    .doc-manager__list {
      padding-right: 12px;
    }
  }
  @media (max-width: 820px) {
    .doc-manager__mobile {
      display: flex;
      gap: 8px;
      margin-bottom: 20px;
    }
    .doc-manager__mobile .el-select {
      min-width: 0;
      flex: 1;
    }
    .doc-manager {
      display: block;
    }
    .doc-manager__list {
      display: none;
      max-height: 225px;
      overflow: auto;
      border: 0;
      padding: 0 0 14px;
      margin-bottom: 18px;
      border-bottom: 1px solid var(--kp-divider);
    }
    .doc-list-row {
      padding: 7px 10px;
      gap: 3px;
    }
    .doc-editor__source {
      min-height: 340px;
    }
  }
  @media (max-width: 480px) {
    .doc-editor__actions {
      gap: 4px;
    }
    .doc-editor__actions .el-button {
      padding: 8px;
    }
    .doc-editor__state {
      flex-basis: 100%;
      margin-bottom: 6px;
    }
    .doc-editor__preview {
      padding: 12px;
    }
  }
</style>
