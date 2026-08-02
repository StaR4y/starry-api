<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { Delete, Download, FolderAdd, Refresh, Upload, Warning } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { dataTransferApi, saveDownloadedFile } from '@/api';
  import type { BackupFile, DataTransferStatus } from '@/types/api';
  import { formatDateTime, formatNumber } from '@/utils/format';

  const loading = ref(false);
  const downloading = ref('');
  const creating = ref(false);
  const importing = ref(false);
  const selectedFile = ref<File | null>(null);
  const fileInput = ref<HTMLInputElement | null>(null);
  const status = ref<DataTransferStatus | null>(null);
  const backups = ref<BackupFile[]>([]);

  const maxImportSizeText = computed(() =>
    status.value ? formatBytes(status.value.maxImportBytes) : '-',
  );

  async function load() {
    loading.value = true;
    try {
      const [nextStatus, backupList] = await Promise.all([
        dataTransferApi.status(),
        dataTransferApi.backups(),
      ]);
      status.value = nextStatus;
      backups.value = backupList.files;
    } finally {
      loading.value = false;
    }
  }

  async function exportDatabase() {
    downloading.value = 'database';
    try {
      saveDownloadedFile(await dataTransferApi.exportDatabase());
      ElMessage.success('PostgreSQL 数据库归档已开始下载');
    } finally {
      downloading.value = '';
    }
  }

  async function exportBackup() {
    downloading.value = 'backup';
    try {
      saveDownloadedFile(await dataTransferApi.exportBackup());
      ElMessage.success('完整备份包已开始下载');
    } finally {
      downloading.value = '';
    }
  }

  async function createBackup() {
    creating.value = true;
    try {
      const result = await dataTransferApi.createBackup();
      ElMessage.success(`已创建备份：${result.file.fileName}`);
      await load();
    } finally {
      creating.value = false;
    }
  }

  function chooseFile() {
    fileInput.value?.click();
  }

  function handleFileChange(event: Event) {
    const target = event.target as HTMLInputElement;
    selectedFile.value = target.files?.[0] ?? null;
  }

  async function importData() {
    if (!selectedFile.value) {
      ElMessage.warning('请先选择 .dump 或 .zip 备份文件');
      return;
    }

    const lowerName = selectedFile.value.name.toLowerCase();
    if (!lowerName.endsWith('.dump') && !lowerName.endsWith('.zip')) {
      ElMessage.warning('仅支持 PostgreSQL .dump 或 Starry API .zip 备份文件');
      return;
    }

    const promptResult = await ElMessageBox.prompt(
      '导入会用 PostgreSQL 归档覆盖当前数据库。系统会在导入前自动创建一份服务器备份。请输入 RESTORE_DATABASE 确认继续。',
      '确认导入数据库',
      {
        confirmButtonText: '开始导入',
        cancelButtonText: '取消',
        inputPattern: /^RESTORE_DATABASE$/,
        inputErrorMessage: '请输入 RESTORE_DATABASE',
        type: 'warning',
      },
    ).catch(() => null);

    if (!promptResult || promptResult.value !== 'RESTORE_DATABASE') return;

    importing.value = true;
    try {
      const result = await dataTransferApi.importBackup(selectedFile.value);
      const rollbackBackup = result.preRestoreBackupName
        ? `，回滚备份：${result.preRestoreBackupName}`
        : '';
      ElMessage.success(`PostgreSQL 数据库恢复完成${rollbackBackup}`);
      selectedFile.value = null;
      if (fileInput.value) fileInput.value.value = '';
      await load();
    } finally {
      importing.value = false;
    }
  }

  async function downloadBackup(file: BackupFile) {
    downloading.value = file.fileName;
    try {
      saveDownloadedFile(await dataTransferApi.downloadBackup(file.fileName));
    } finally {
      downloading.value = '';
    }
  }

  async function deleteBackup(file: BackupFile) {
    const confirmed = await ElMessageBox.confirm(`确认删除备份 ${file.fileName}？`, '删除备份', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    }).catch(() => null);
    if (!confirmed) return;
    await dataTransferApi.deleteBackup(file.fileName);
    ElMessage.success('备份已删除');
    await load();
  }

  function formatBytes(bytes: number) {
    if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let value = bytes;
    let index = 0;
    while (value >= 1024 && index < units.length - 1) {
      value /= 1024;
      index++;
    }
    return `${value.toFixed(value >= 10 || index === 0 ? 0 : 1)} ${units[index]}`;
  }

  onMounted(load);
</script>

<template>
  <PageSection title="数据管理" subtitle="导出、备份、迁移和恢复 Starry API 数据库">
    <template #actions>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </template>

    <div v-loading="loading" class="data-transfer">
      <section class="summary-grid">
        <div class="summary-card glass-panel">
          <span class="summary-card__label">当前数据库</span>
          <strong>{{ status?.database || '-' }}</strong>
        </div>
        <div class="summary-card glass-panel">
          <span class="summary-card__label">服务器备份</span>
          <strong>{{ formatNumber(status?.backupCount ?? 0) }}</strong>
        </div>
        <div class="summary-card glass-panel">
          <span class="summary-card__label">备份占用</span>
          <strong>{{ formatBytes(status?.backupBytes ?? 0) }}</strong>
        </div>
        <div class="summary-card glass-panel">
          <span class="summary-card__label">导入上限</span>
          <strong>{{ maxImportSizeText }}</strong>
        </div>
      </section>

      <section class="action-grid">
        <article class="operation-card">
          <div>
            <h3>导出 PostgreSQL 归档</h3>
            <p>下载 custom-format 数据库归档，包含表结构和数据。</p>
          </div>
          <el-button
            type="primary"
            :icon="Download"
            :loading="downloading === 'database'"
            @click="exportDatabase"
          >
            导出 DUMP
          </el-button>
        </article>

        <article class="operation-card">
          <div>
            <h3>下载完整备份包</h3>
            <p>打包 database.dump、备份元数据和说明文件，适合跨服务器迁移。</p>
          </div>
          <el-button :icon="Download" :loading="downloading === 'backup'" @click="exportBackup">
            下载 ZIP
          </el-button>
        </article>

        <article class="operation-card">
          <div>
            <h3>创建服务器备份</h3>
            <p>备份会保存在服务器备份目录，导入前也会自动创建一份。</p>
          </div>
          <el-button type="success" :icon="FolderAdd" :loading="creating" @click="createBackup">
            创建备份
          </el-button>
        </article>
      </section>

      <section class="import-panel">
        <div class="import-panel__warning">
          <el-icon><Warning /></el-icon>
          <div>
            <strong>导入会修改当前数据库</strong>
            <p>
              支持上传 PostgreSQL .dump 或 Starry API .zip
              备份包。导入前会自动创建回滚备份，但仍建议先下载一份离线备份。
            </p>
          </div>
        </div>

        <div class="import-panel__controls">
          <input
            ref="fileInput"
            class="import-panel__file"
            type="file"
            accept=".dump,.zip"
            @change="handleFileChange"
          >
          <el-button :icon="Upload" @click="chooseFile">选择文件</el-button>
          <span class="import-panel__filename">
            {{
              selectedFile
                ? `${selectedFile.name} · ${formatBytes(selectedFile.size)}`
                : '未选择文件'
            }}
          </span>
          <el-button
            type="danger"
            :loading="importing"
            :disabled="!selectedFile"
            @click="importData"
          >
            导入并恢复
          </el-button>
        </div>
      </section>

      <section class="backup-list">
        <div class="backup-list__header">
          <div>
            <h3>服务器备份</h3>
            <p>目录：{{ status?.backupDir || '-' }}</p>
          </div>
          <el-button :icon="Refresh" text @click="load">刷新列表</el-button>
        </div>

        <el-table :data="backups" empty-text="暂无备份文件">
          <el-table-column prop="fileName" label="文件名" min-width="280" />
          <el-table-column label="大小" width="120">
            <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="190">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :loading="downloading === row.fileName"
                @click="downloadBackup(row)"
              >
                下载
              </el-button>
              <el-button link type="danger" :icon="Delete" @click="deleteBackup(row)">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>
  </PageSection>
</template>

<style scoped lang="scss">
  .data-transfer {
    display: flex;
    flex-direction: column;
    gap: 18px;
  }

  .summary-grid,
  .action-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 14px;
  }

  .summary-card {
    padding: 18px;
    display: flex;
    flex-direction: column;
    gap: 8px;

    &__label {
      color: var(--kp-text-muted);
      font-size: 13px;
    }

    strong {
      color: var(--kp-text);
      font-size: 22px;
      line-height: 1.2;
    }
  }

  .action-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .operation-card,
  .import-panel,
  .backup-list {
    border: 1px solid rgba(91, 141, 239, 0.14);
    background: rgba(255, 255, 255, 0.76);
    border-radius: 16px;
    padding: 20px;
    box-shadow: 0 14px 36px rgba(79, 108, 168, 0.08);
  }

  .operation-card {
    min-height: 164px;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    gap: 18px;

    h3 {
      margin: 0 0 8px;
      font-size: 16px;
      color: var(--kp-text);
    }

    p {
      margin: 0;
      color: var(--kp-text-muted);
      line-height: 1.7;
      font-size: 13px;
    }
  }

  .import-panel {
    display: flex;
    flex-direction: column;
    gap: 18px;

    &__warning {
      display: flex;
      gap: 12px;
      align-items: flex-start;
      padding: 14px;
      border-radius: 12px;
      background: rgba(239, 91, 107, 0.08);
      color: #9f1239;

      p {
        margin: 4px 0 0;
        color: #9f1239;
        line-height: 1.6;
        font-size: 13px;
      }
    }

    &__controls {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }

    &__file {
      display: none;
    }

    &__filename {
      color: var(--kp-text-secondary);
      font-size: 13px;
      flex: 1;
      min-width: 240px;
      word-break: break-all;
    }
  }

  .backup-list {
    &__header {
      display: flex;
      justify-content: space-between;
      gap: 16px;
      align-items: flex-start;
      margin-bottom: 14px;

      h3 {
        margin: 0 0 6px;
        font-size: 16px;
      }

      p {
        margin: 0;
        color: var(--kp-text-muted);
        font-size: 13px;
        word-break: break-all;
      }
    }
  }

  @media (max-width: 1100px) {
    .summary-grid,
    .action-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media (max-width: 720px) {
    .summary-grid,
    .action-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
