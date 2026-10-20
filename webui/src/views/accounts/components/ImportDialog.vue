<script setup lang="ts">
  import { t } from '@/i18n';
  /**
   * 批量导入账号对话框。
   *
   * 从 AccountListView.vue 原样抽出：拖拽上传 / 粘贴 JSON、导入提交与
   * 结果展示；导入完成后通知父组件刷新列表。
   */
  import { computed, ref } from 'vue';
  import { ElMessage } from 'element-plus';
  import AppIcon from './AppIcon.vue';
  import { accountsApi } from '@/api';
  import { ApiError } from '@/utils/http';
  import type { ImportAccountItem, ImportAccountsResult } from '@/types/api';

  const props = defineProps<{
    visible: boolean;
  }>();

  const emit = defineEmits<{
    (e: 'update:visible', value: boolean): void;
    (e: 'imported'): void;
  }>();

  const visibleValue = computed({
    get: () => props.visible,
    set: (value: boolean) => emit('update:visible', value),
  });

  const importContent = ref('');
  const importUpsert = ref(false);
  const importResult = ref<ImportAccountsResult | null>(null);
  const importSubmitting = ref(false);
  const importFileInput = ref<HTMLInputElement | null>(null);
  const importFileNames = ref<string[]>([]);
  const importFilePayloads = ref<Array<{ name: string; payload: unknown }>>([]);
  const isDragover = ref(false);

  // 对话框每次打开时重置状态，与原 openImport 行为一致
  function resetState() {
    importContent.value = '';
    importResult.value = null;
    importUpsert.value = false;
    importFileNames.value = [];
    importFilePayloads.value = [];
    isDragover.value = false;
  }

  function handleVisibleChange(visible: boolean) {
    if (visible) resetState();
    emit('update:visible', visible);
  }

  function chooseImportFiles() {
    importFileInput.value?.click();
  }

  async function processFiles(files: File[]) {
    try {
      const parsed = await Promise.all(
        files.map(async (file) => ({
          name: file.name,
          payload: JSON.parse(await file.text()) as unknown,
        })),
      );
      const invalid = parsed.find((item) => !isImportPayload(item.payload));
      if (invalid) {
        throw new Error(t('{p0} 不是可识别的账号导入 JSON', { p0: invalid.name }));
      }
      const next = [...importFilePayloads.value, ...parsed];
      importFilePayloads.value = next;
      importFileNames.value = next.map((item) => item.name);
      importContent.value = next.length === 1 ? JSON.stringify(next[0].payload, null, 2) : '';
      importResult.value = null;
      ElMessage.success(
        t('已加入 {p0} 个 JSON 文件，待导入 {p1} 个', { p0: files.length, p1: next.length }),
      );
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      ElMessage.error(t('JSON 文件解析失败：{p0}', { p0: message }));
    }
  }

  async function handleImportFiles(event: Event) {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    if (files.length === 0) return;
    await processFiles(files);
  }

  function onDragOver() {
    isDragover.value = true;
  }
  function onDragEnter() {
    isDragover.value = true;
  }
  function onDragLeave() {
    isDragover.value = false;
  }
  async function onDrop(event: DragEvent) {
    isDragover.value = false;
    const files = Array.from(event.dataTransfer?.files ?? []).filter(
      (file) => file.type === 'application/json' || file.name.endsWith('.json'),
    );
    if (files.length === 0) {
      ElMessage.warning(t('请拖拽有效的 JSON 配置文件'));
      return;
    }
    await processFiles(files);
  }

  function clearImportFiles() {
    if (importFilePayloads.value.length === 0) return;
    importFilePayloads.value = [];
    importFileNames.value = [];
  }

  async function submitImport() {
    if (importFilePayloads.value.length > 0) {
      await submitImportFiles();
      return;
    }

    let parsed: unknown;
    try {
      parsed = JSON.parse(importContent.value);
    } catch {
      ElMessage.error(t('JSON 格式错误'));
      return;
    }
    const payload = Array.isArray(parsed)
      ? { accounts: parsed, upsert: importUpsert.value }
      : parsed && typeof parsed === 'object'
        ? { ...(parsed as Record<string, unknown>), upsert: importUpsert.value }
        : null;
    if (!isImportPayload(payload)) {
      ElMessage.error(t('数据应为 Kiro / OpenAI Codex / OpenAI-API / Anthropic 导入 JSON'));
      return;
    }

    const importPayload = payload as Record<string, unknown>;
    importSubmitting.value = true;
    try {
      importResult.value = await accountsApi.importBatch(importPayload);
      ElMessage.success(
        t('导入完成：新建 {p0} / 更新 {p1} / 跳过 {p2} / 失败 {p3}', {
          p0: importResult.value.created,
          p1: importResult.value.updated,
          p2: importResult.value.skipped,
          p3: importResult.value.failed,
        }),
      );
      emit('imported');
    } catch (err) {
      if (err instanceof ApiError) ElMessage.error(err.message);
    } finally {
      importSubmitting.value = false;
    }
  }

  async function submitImportFiles() {
    const items: ImportAccountItem[] = [];
    let created = 0;
    let updated = 0;
    let skipped = 0;
    let failed = 0;

    importSubmitting.value = true;
    try {
      for (const file of importFilePayloads.value) {
        try {
          const result = await accountsApi.importBatch(importPayloadForFile(file.payload));
          created += result.created;
          updated += result.updated;
          skipped += result.skipped;
          failed += result.failed;
          for (const item of result.items) {
            items.push({
              ...item,
              index: items.length,
              message: `${file.name}: ${item.message ?? ''}`.trim(),
            });
          }
        } catch (err) {
          failed += 1;
          items.push({
            index: items.length,
            accountId: null,
            email: null,
            status: 'FAILED',
            message: `${file.name}: ${err instanceof ApiError ? err.message : String(err)}`,
            account: null,
          });
        }
      }

      importResult.value = {
        total: items.length,
        created,
        updated,
        skipped,
        failed,
        items,
      };
      const message = t('导入完成：新建 {p0} / 更新 {p1} / 跳过 {p2} / 失败 {p3}', {
        p0: created,
        p1: updated,
        p2: skipped,
        p3: failed,
      });
      if (failed > 0) {
        ElMessage.warning(message);
      } else {
        ElMessage.success(message);
      }
      emit('imported');
    } finally {
      importSubmitting.value = false;
    }
  }

  function importPayloadForFile(payload: unknown) {
    if (Array.isArray(payload)) {
      return { accounts: payload, upsert: importUpsert.value };
    }
    return {
      ...(payload as Record<string, unknown>),
      upsert: importUpsert.value,
    };
  }

  function isImportPayload(payload: unknown) {
    if (!payload || typeof payload !== 'object') return false;
    if (Array.isArray(payload)) return payload.length > 0;
    const record = payload as Record<string, unknown>;
    if (Array.isArray(record.documents) && record.documents.length > 0) return true;
    if (Array.isArray(record.imports) && record.imports.length > 0) return true;
    if (Array.isArray(record.files) && record.files.length > 0) return true;
    if (Array.isArray(record.accounts) && record.accounts.length > 0) return true;
    const openAiCompatibility =
      record['openai-compatibility'] ?? record.openaiCompatibility ?? record.openai_compatibility;
    if (Array.isArray(openAiCompatibility) && openAiCompatibility.length > 0) return true;
    if (openAiCompatibility && typeof openAiCompatibility === 'object') return true;
    if (looksLikeOpenAiCompatibilityGroup(record)) return true;
    return (
      'credentials' in record ||
      'accessToken' in record ||
      'access_token' in record ||
      'apiKey' in record ||
      'api_key' in record ||
      'api-key' in record ||
      'type' in record ||
      'provider' in record
    );
  }

  function looksLikeOpenAiCompatibilityGroup(record: Record<string, unknown>) {
    return (
      'api-key-entries' in record ||
      'apiKeyEntries' in record ||
      'api_key_entries' in record ||
      'keys' in record
    );
  }
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="t('批量导入账号')"
    width="720px"
    @update:model-value="handleVisibleChange"
  >
    <div class="text-secondary mt-8 mb-8">
      {{
        t(
          '上传或粘贴 Kiro Account Manager、OpenAI Codex token、OpenAI-compatible / Anthropic API Key 配置 JSON，或账号数组 /',
        )
      }}
      <span class="mono">{ "accounts": [...] }</span>
    </div>

    <div
      class="import-drop-zone"
      :class="{ 'is-dragover': isDragover, 'has-files': importFileNames.length > 0 }"
      @dragover.prevent="onDragOver"
      @dragenter.prevent="onDragEnter"
      @dragleave.prevent="onDragLeave"
      @drop.prevent="onDrop"
      @click="chooseImportFiles"
    >
      <input
        ref="importFileInput"
        class="import-file-row__input"
        type="file"
        accept=".json,application/json"
        multiple
        @change="handleImportFiles"
      >

      <div v-if="importFileNames.length === 0" class="drop-zone-prompt">
        <AppIcon name="upload" width="36" height="36" class="drop-icon" />
        <div class="prompt-text">
          {{ t('将 JSON 配置文件拖拽到此处，或') }}<span>{{ t('点击上传') }}</span>
        </div>
        <div class="prompt-hint">{{ t('支持单个或多个 .json 配置文件') }}</div>
      </div>
      <div v-else class="drop-zone-files" @click.stop>
        <div class="files-header">
          <AppIcon name="check-circle" width="18" height="18" class="success-icon" />
          <span class="files-title">{{ t('已选择') }} {{ importFileNames.length }} {{ t('个 JSON 文件') }}</span>
          <el-button size="small" type="danger" link @click="clearImportFiles">
            {{
              t('清除文件')
            }}
          </el-button>
        </div>
        <div class="files-list">
          <div v-for="name in importFileNames" :key="name" class="file-item">
            <AppIcon name="file" width="13" height="13" class="file-icon" />
            <span class="file-name" :title="name">{{ name }}</span>
          </div>
        </div>
      </div>
    </div>

    <div class="text-secondary mt-16 mb-8">{{ t('或者，在此直接粘贴 JSON 文本：') }}</div>
    <el-input
      v-model="importContent"
      type="textarea"
      :rows="8"
      placeholder='{"type":"codex","access_token":"...","refresh_token":"...","id_token":"..."}'
      @input="clearImportFiles"
    />
    <el-checkbox v-model="importUpsert" class="mt-16">
      {{ t('已存在邮箱时覆盖更新旧账号（谨慎开启）') }}
    </el-checkbox>

    <div v-if="importResult" class="import-summary glass-panel mt-16">
      <div class="flex gap-16">
        <div>{{ t('总计') }} {{ importResult.total }}</div>
        <div>{{ t('新建') }} {{ importResult.created }}</div>
        <div>{{ t('更新') }} {{ importResult.updated }}</div>
        <div>{{ t('跳过') }} {{ importResult.skipped }}</div>
        <div>{{ t('失败') }} {{ importResult.failed }}</div>
      </div>
      <el-table :data="importResult.items" size="small" class="mt-8" max-height="240">
        <el-table-column prop="index" label="#" width="60" />
        <el-table-column prop="accountId" :label="t('账号 ID')" min-width="180" />
        <el-table-column prop="status" :label="t('结果')" width="100" />
        <el-table-column prop="message" :label="t('信息')" />
      </el-table>
    </div>

    <template #footer>
      <el-button @click="visibleValue = false">{{ t('关闭') }}</el-button>
      <el-button type="primary" :loading="importSubmitting" @click="submitImport">
        {{ t('开始导入') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
  .import-summary {
    padding: 12px 16px;
  }

  .mb-8 {
    margin-bottom: 8px;
  }

  .import-file-row__input {
    display: none;
  }

  /* Import Drop Zone */
  .import-drop-zone {
    border: 2px dashed rgba(15, 23, 42, 0.12);
    border-radius: var(--kp-radius-sm);
    padding: 24px 20px;
    text-align: center;
    background: rgba(255, 255, 255, 0.45);
    cursor: pointer;
    transition: all 0.25s ease;
    margin-top: 12px;
    margin-bottom: 12px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    min-height: 120px;
    user-select: none;

    &:hover,
    &.is-dragover {
      border-color: var(--kp-primary);
      background: rgba(91, 141, 239, 0.04);

      .drop-icon {
        color: var(--kp-primary);
        transform: translateY(-2px);
      }
    }

    &.has-files {
      cursor: default;
      border-style: solid;
      background: rgba(255, 255, 255, 0.55);
      align-items: stretch;
      padding: 16px 20px;
    }

    .drop-icon {
      color: var(--kp-text-muted);
      margin-bottom: 8px;
      transition: all 0.25s ease;
    }

    .prompt-text {
      font-size: 13.5px;
      font-weight: 600;
      color: var(--kp-text);
      margin-bottom: 4px;

      span {
        color: var(--kp-primary);
        font-weight: 700;
        text-decoration: underline;
        margin-left: 4px;
      }
    }

    .prompt-hint {
      font-size: 11px;
      color: var(--kp-text-muted);
    }

    .drop-zone-files {
      display: flex;
      flex-direction: column;
      gap: 10px;
      text-align: left;

      .files-header {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 13px;
        font-weight: 700;
        color: var(--kp-text);
        border-bottom: 1px solid var(--kp-divider);
        padding-bottom: 6px;

        .success-icon {
          color: var(--kp-success);
        }

        .files-title {
          flex: 1;
        }
      }

      .files-list {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        max-height: 100px;
        overflow-y: auto;
      }

      .file-item {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        padding: 3px 8px;
        border-radius: 5px;
        background: rgba(15, 23, 42, 0.03);
        border: 1px solid var(--kp-divider);
        font-size: 11.5px;
        color: var(--kp-text-secondary);
        max-width: 200px;

        .file-icon {
          flex-shrink: 0;
          color: var(--kp-text-muted);
        }

        .file-name {
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
      }
    }
  }
</style>
