<script setup lang="ts">
  import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
  import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import {
    Check,
    CopyDocument,
    Delete,
    Download,
    FolderAdd,
    Key,
    Lock,
    Refresh,
    Upload,
    Warning,
  } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { dataTransferApi, saveDownloadedFile } from '@/api';
  import { useAuthStore } from '@/stores/auth';
  import type {
    BackupFile,
    DataManagementAction,
    DataManagementSecurityStatus,
    DataTransferStatus,
    UnlockDataManagementResponse,
  } from '@/types/api';
  import { formatDateTime, formatNumber } from '@/utils/format';
  import { ApiError } from '@/utils/http';

  const route = useRoute();
  const router = useRouter();
  const auth = useAuthStore();
  const loading = ref(false);
  const initializing = ref(false);
  const downloading = ref('');
  const creating = ref(false);
  const importing = ref(false);
  const selectedFile = ref<File | null>(null);
  const fileInput = ref<HTMLInputElement | null>(null);
  const status = ref<DataTransferStatus | null>(null);
  const backups = ref<BackupFile[]>([]);
  const security = ref<DataManagementSecurityStatus | null>(null);
  const generatedSecurityKey = ref('');
  const securityKeyAcknowledged = ref(false);

  const unlockDialogVisible = ref(false);
  const unlockLoading = ref(false);
  const unlockKeyInput = ref('');
  const unlockError = ref('');
  const securityToken = ref('');
  const securityTokenExpiresAt = ref(0);
  const securityTokenAction = ref<DataManagementAction | null>(null);
  const securityTokenTarget = ref<string | null>(null);
  const pendingUnlockAction = ref<DataManagementAction | null>(null);
  const pendingUnlockTarget = ref<string | null>(null);
  let tokenExpiryTimer: number | undefined;
  let unlockRequest: Promise<string | null> | null = null;
  let resolveUnlockRequest: ((token: string | null) => void) | null = null;

  const maxImportSizeText = computed(() =>
    status.value ? formatBytes(status.value.maxImportBytes) : '-',
  );
  const remainingAttempts = computed(() => {
    const current = security.value;
    if (!current) return 0;
    return Math.max(0, current.maxFailedAttempts - current.failedAttempts);
  });
  const hasUnsavedGeneratedKey = computed(
    () => Boolean(generatedSecurityKey.value) && !securityKeyAcknowledged.value,
  );
  const showSecurityInitialization = computed(
    () => security.value && (!security.value.initialized || Boolean(generatedSecurityKey.value)),
  );
  const isApplicationRoot = computed(() => auth.isRoot);
  const managementReady = computed(
    () => security.value?.initialized && !security.value.locked && !generatedSecurityKey.value,
  );
  const isTemporarilyUnlocked = computed(
    () => Boolean(securityToken.value) && securityTokenExpiresAt.value > Date.now(),
  );
  const unlockExpiryText = computed(() =>
    isTemporarilyUnlocked.value
      ? `${actionLabel(securityTokenAction.value)}已解锁至 ${formatDateTime(
          new Date(securityTokenExpiresAt.value).toISOString(),
        )}`
      : '',
  );

  async function loadManagementData() {
    const [nextStatus, backupList] = await Promise.all([
      dataTransferApi.status(),
      dataTransferApi.backups(),
    ]);
    status.value = nextStatus;
    backups.value = backupList.files;
  }

  async function load() {
    loading.value = true;
    try {
      const nextSecurity = await dataTransferApi.securityStatus();
      security.value = nextSecurity;
      if (!nextSecurity.initialized || nextSecurity.locked || generatedSecurityKey.value) {
        status.value = null;
        backups.value = [];
        return;
      }
      await loadManagementData();
    } finally {
      loading.value = false;
    }
  }

  async function initializeSecurity() {
    if (!isApplicationRoot.value) {
      ElMessage.warning('仅系统 Root 可初始化数据管理安全密钥');
      return;
    }
    const confirmed = await ElMessageBox.confirm(
      '默认管理员是系统唯一的应用层最高权限账号，其密码泄漏即代表服务完全失控，数据管理安全密钥无法弥补该风险。继续后生成的密钥也仅展示一次，请先打开受控的密码管理器以便立即保存。',
      '初始化数据管理安全密钥',
      {
        confirmButtonText: '生成安全密钥',
        cancelButtonText: '取消',
        type: 'warning',
      },
    ).catch(() => null);
    if (!confirmed) return;

    initializing.value = true;
    try {
      const result = await dataTransferApi.initializeSecurity();
      security.value = result.status;
      generatedSecurityKey.value = result.securityKey;
      securityKeyAcknowledged.value = false;
    } finally {
      initializing.value = false;
    }
  }

  async function copySecurityKey() {
    if (!generatedSecurityKey.value) return;
    let copied = false;
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(generatedSecurityKey.value);
        copied = true;
      } else {
        copied = fallbackCopy(generatedSecurityKey.value);
      }
    } catch {
      copied = fallbackCopy(generatedSecurityKey.value);
    }
    if (copied) {
      ElMessage.success('安全密钥已复制');
    } else {
      ElMessage.warning('自动复制失败，请手动选择并复制安全密钥');
    }
  }

  function fallbackCopy(value: string) {
    const input = document.createElement('textarea');
    input.value = value;
    input.style.position = 'fixed';
    input.style.opacity = '0';
    document.body.appendChild(input);
    try {
      input.focus();
      input.select();
      input.setSelectionRange(0, value.length);
      return document.execCommand('copy');
    } catch {
      return false;
    } finally {
      input.remove();
    }
  }

  async function enterDataManagement() {
    if (!securityKeyAcknowledged.value) {
      ElMessage.warning('请先确认安全密钥已妥善保存');
      return;
    }
    generatedSecurityKey.value = '';
    securityKeyAcknowledged.value = false;
    loading.value = true;
    try {
      await loadManagementData();
    } finally {
      loading.value = false;
    }
  }

  function activeSecurityToken() {
    if (!securityToken.value || securityTokenExpiresAt.value <= Date.now() + 1_000) {
      clearSecurityToken();
      return null;
    }
    return securityToken.value;
  }

  function rememberSecurityToken(result: UnlockDataManagementResponse) {
    clearSecurityToken();
    securityToken.value = result.accessToken;
    securityTokenExpiresAt.value = new Date(result.expiresAt).getTime();
    securityTokenAction.value = result.action;
    securityTokenTarget.value = result.target;
    const remainingMs = Math.max(0, securityTokenExpiresAt.value - Date.now());
    tokenExpiryTimer = window.setTimeout(clearSecurityToken, remainingMs);
  }

  function clearSecurityToken() {
    securityToken.value = '';
    securityTokenExpiresAt.value = 0;
    securityTokenAction.value = null;
    securityTokenTarget.value = null;
    if (tokenExpiryTimer !== undefined) {
      window.clearTimeout(tokenExpiryTimer);
      tokenExpiryTimer = undefined;
    }
  }

  function requestSecurityToken(
    action: DataManagementAction,
    target: string | null = null,
  ): Promise<string | null> {
    const activeToken = activeSecurityToken();
    if (
      activeToken &&
      securityTokenAction.value === action &&
      securityTokenTarget.value === target
    ) {
      return Promise.resolve(activeToken);
    }
    if (activeToken) clearSecurityToken();
    if (unlockRequest) return unlockRequest;

    pendingUnlockAction.value = action;
    pendingUnlockTarget.value = target;
    unlockKeyInput.value = '';
    unlockError.value = '';
    unlockDialogVisible.value = true;
    unlockRequest = new Promise((resolve) => {
      resolveUnlockRequest = resolve;
    });
    return unlockRequest;
  }

  function finishUnlockRequest(token: string | null) {
    const resolver = resolveUnlockRequest;
    resolveUnlockRequest = null;
    unlockRequest = null;
    unlockDialogVisible.value = false;
    unlockKeyInput.value = '';
    unlockError.value = '';
    pendingUnlockAction.value = null;
    pendingUnlockTarget.value = null;
    resolver?.(token);
  }

  function cancelUnlock() {
    if (!unlockLoading.value) finishUnlockRequest(null);
  }

  async function refreshSecurityStatus() {
    try {
      security.value = await dataTransferApi.securityStatus();
    } catch {
      // 锁定后业务接口可能已停止，仅保留当前页面掌握的失败计数。
    }
  }

  async function submitUnlock() {
    const key = unlockKeyInput.value.trim();
    if (!key) {
      unlockError.value = '请输入数据管理安全密钥';
      return;
    }

    unlockLoading.value = true;
    unlockError.value = '';
    try {
      if (!pendingUnlockAction.value) {
        finishUnlockRequest(null);
        return;
      }
      const result = await dataTransferApi.unlockSecurity(
        key,
        pendingUnlockAction.value,
        pendingUnlockTarget.value,
      );
      rememberSecurityToken(result);
      if (security.value) {
        security.value = { ...security.value, failedAttempts: 0, lastFailureAt: null };
      }
      finishUnlockRequest(result.accessToken);
      ElMessage.success('数据管理高危操作已临时解锁');
    } catch (error) {
      if (error instanceof ApiError && error.code === 'DATA_MANAGEMENT_SECURITY_KEY_INVALID') {
        if (security.value) {
          security.value = {
            ...security.value,
            failedAttempts: Math.min(
              security.value.maxFailedAttempts,
              security.value.failedAttempts + 1,
            ),
            lastFailureAt: new Date().toISOString(),
          };
        }
        await refreshSecurityStatus();
        unlockKeyInput.value = '';
        unlockError.value = `安全密钥错误，剩余 ${remainingAttempts.value} 次尝试机会`;
        return;
      }

      if (error instanceof ApiError && error.code === 'DATA_MANAGEMENT_SECURITY_LOCKED') {
        if (security.value) {
          security.value = {
            ...security.value,
            failedAttempts: security.value.maxFailedAttempts,
            locked: true,
            lockedAt: new Date().toISOString(),
          };
        }
        finishUnlockRequest(null);
        await router.replace({
          name: 'maintenance',
          query: { redirect: route.fullPath },
        });
        return;
      }

      unlockError.value = (error as Error).message || '安全密钥验证失败';
    } finally {
      unlockLoading.value = false;
    }
  }

  function handleProtectedOperationError(error: unknown) {
    if (!(error instanceof ApiError)) return;
    if (
      error.code === 'DATA_MANAGEMENT_UNLOCK_REQUIRED' ||
      error.code === 'DATA_MANAGEMENT_SECURITY_LOCKED'
    ) {
      clearSecurityToken();
    }
    if (error.code === 'DATA_MANAGEMENT_SECURITY_LOCKED') {
      router.replace({ name: 'maintenance', query: { redirect: route.fullPath } });
    }
  }

  async function exportDatabase() {
    const token = await requestSecurityToken('EXPORT_DATABASE');
    if (!token) return;
    downloading.value = 'database';
    try {
      saveDownloadedFile(await dataTransferApi.exportDatabase(token));
      ElMessage.success('PostgreSQL 数据库归档已开始下载');
    } catch (error) {
      handleProtectedOperationError(error);
    } finally {
      downloading.value = '';
    }
  }

  async function exportBackup() {
    const token = await requestSecurityToken('EXPORT_BACKUP');
    if (!token) return;
    downloading.value = 'backup';
    try {
      saveDownloadedFile(await dataTransferApi.exportBackup(token));
      ElMessage.success('完整备份包已开始下载');
    } catch (error) {
      handleProtectedOperationError(error);
    } finally {
      downloading.value = '';
    }
  }

  async function createBackup() {
    const token = await requestSecurityToken('CREATE_BACKUP');
    if (!token) return;
    creating.value = true;
    try {
      const result = await dataTransferApi.createBackup(token);
      ElMessage.success(`已创建备份：${result.file.fileName}`);
      await loadManagementData();
    } catch (error) {
      handleProtectedOperationError(error);
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
        confirmButtonText: '继续验证安全密钥',
        cancelButtonText: '取消',
        inputPattern: /^RESTORE_DATABASE$/,
        inputErrorMessage: '请输入 RESTORE_DATABASE',
        type: 'warning',
      },
    ).catch(() => null);
    if (!promptResult || promptResult.value !== 'RESTORE_DATABASE') return;

    const token = await requestSecurityToken('RESTORE_DATABASE');
    if (!token) return;
    importing.value = true;
    try {
      const result = await dataTransferApi.importBackup(selectedFile.value, token);
      const rollbackBackup = result.preRestoreBackupName
        ? `，回滚备份：${result.preRestoreBackupName}`
        : '';
      ElMessage.success(`PostgreSQL 数据库恢复完成${rollbackBackup}`);
      selectedFile.value = null;
      if (fileInput.value) fileInput.value.value = '';
      await load();
    } catch (error) {
      handleProtectedOperationError(error);
    } finally {
      importing.value = false;
      clearSecurityToken();
    }
  }

  async function downloadBackup(file: BackupFile) {
    const token = await requestSecurityToken('DOWNLOAD_BACKUP', file.fileName);
    if (!token) return;
    downloading.value = file.fileName;
    try {
      saveDownloadedFile(await dataTransferApi.downloadBackup(file.fileName, token));
    } catch (error) {
      handleProtectedOperationError(error);
    } finally {
      downloading.value = '';
    }
  }

  async function deleteBackup(file: BackupFile) {
    const confirmed = await ElMessageBox.prompt(
      `删除后将失去该恢复点。请输入备份文件名 ${file.fileName} 确认删除。`,
      '删除服务器备份',
      {
        confirmButtonText: '继续验证安全密钥',
        cancelButtonText: '取消',
        inputPattern: new RegExp(`^${escapeRegExp(file.fileName)}$`),
        inputErrorMessage: '请输入完整的备份文件名',
        type: 'warning',
      },
    ).catch(() => null);
    if (!confirmed || confirmed.value !== file.fileName) return;

    const token = await requestSecurityToken('DELETE_BACKUP', file.fileName);
    if (!token) return;
    try {
      await dataTransferApi.deleteBackup(file.fileName, token);
      ElMessage.success('备份已删除');
      await loadManagementData();
    } catch (error) {
      handleProtectedOperationError(error);
    } finally {
      clearSecurityToken();
    }
  }

  function escapeRegExp(value: string) {
    return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }

  function actionLabel(action: DataManagementAction | null) {
    switch (action) {
      case 'EXPORT_DATABASE':
        return '数据库导出';
      case 'EXPORT_BACKUP':
        return '完整备份导出';
      case 'CREATE_BACKUP':
        return '服务器备份创建';
      case 'DOWNLOAD_BACKUP':
        return '服务器备份下载';
      case 'DELETE_BACKUP':
        return '服务器备份删除';
      case 'RESTORE_DATABASE':
        return '数据库恢复';
      default:
        return '高危操作';
    }
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

  function handleBeforeUnload(event: BeforeUnloadEvent) {
    if (!hasUnsavedGeneratedKey.value) return;
    event.preventDefault();
    event.returnValue = '';
  }

  onBeforeRouteLeave(() => {
    if (!hasUnsavedGeneratedKey.value) return true;
    return window.confirm('安全密钥仅显示一次，尚未确认已妥善保存。确定离开当前页面？');
  });

  onMounted(() => {
    window.addEventListener('beforeunload', handleBeforeUnload);
    load();
  });

  onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', handleBeforeUnload);
    clearSecurityToken();
    finishUnlockRequest(null);
  });
</script>

<template>
  <PageSection title="数据管理" subtitle="备份与迁移" :icon="FolderAdd">
    <template #actions>
      <span v-if="isTemporarilyUnlocked" class="unlock-status">
        <el-icon><Check /></el-icon>
        {{ unlockExpiryText }}
      </span>
      <el-button
        v-if="!generatedSecurityKey"
        :icon="Refresh"
        :loading="loading"
        @click="load"
      >
        刷新
      </el-button>
    </template>

    <div v-loading="loading" class="data-transfer">
      <section v-if="showSecurityInitialization" class="security-setup">
        <template v-if="!generatedSecurityKey && isApplicationRoot">
          <el-icon class="security-setup__icon" :size="34"><Key /></el-icon>
          <h2>初始化数据管理安全密钥</h2>
          <p>仅初始管理员可以完成初始化。密钥用于解锁数据库导出、备份和恢复等高危操作。</p>
          <el-alert
            title="默认管理员等同于系统 root 权限"
            description="必须严格保护默认管理员账号和密码。该账号密码一旦泄漏，攻击者将拥有完整系统控制权，数据管理安全密钥不能替代或补偿 root 凭据安全。"
            type="error"
            show-icon
            :closable="false"
          />
          <el-alert
            title="确认安全告警具备有效收件地址"
            description="如已配置 SMTP，请确保默认管理员已绑定可用邮箱，否则安全密钥错误告警将无法送达；此项不会阻止初始化。"
            type="info"
            show-icon
            :closable="false"
          />
          <el-alert
            title="密钥仅展示一次，遗失后无法通过控制台找回"
            description="建议直接存入受控的密码管理器或加密安全介质，不要保存到代码仓库、浏览器或聊天记录。"
            type="warning"
            show-icon
            :closable="false"
          />
          <el-button type="primary" :icon="Key" :loading="initializing" @click="initializeSecurity">
            初始化安全密钥
          </el-button>
        </template>

        <template v-else-if="!generatedSecurityKey">
          <el-icon class="security-setup__icon" :size="34"><Lock /></el-icon>
          <h2>等待系统 Root 初始化</h2>
          <p>数据管理安全尚未初始化，请联系系统 Root 完成首次初始化。</p>
          <el-alert
            title="Root 专属安全操作"
            description="普通管理员不能生成或查看数据管理安全密钥。"
            type="info"
            show-icon
            :closable="false"
          />
        </template>

        <template v-else>
          <el-icon class="security-setup__icon security-setup__icon--warning" :size="34">
            <Warning />
          </el-icon>
          <h2>立即保存安全密钥</h2>
          <el-alert
            title="这是安全密钥唯一一次显示"
            description="关闭或离开此页面后将无法再次查看。请立即复制到受控的密码管理器，并按组织策略保存加密恢复副本；不要下载或落盘为明文文件。"
            type="error"
            show-icon
            :closable="false"
          />
          <div class="security-key">
            <span class="security-key__label">数据管理安全密钥</span>
            <el-input :model-value="generatedSecurityKey" readonly />
            <div class="security-key__actions">
              <el-button :icon="CopyDocument" @click="copySecurityKey">复制</el-button>
            </div>
          </div>
          <el-checkbox v-model="securityKeyAcknowledged" class="security-setup__acknowledge">
            我已妥善保护默认管理员凭据，并将安全密钥保存到受控密码管理器或加密介质，知悉离开后无法再次查看
          </el-checkbox>
          <el-button
            type="primary"
            :icon="Check"
            :disabled="!securityKeyAcknowledged"
            @click="enterDataManagement"
          >
            已妥善保存，进入数据管理
          </el-button>
        </template>
      </section>

      <section v-else-if="security?.locked" class="security-locked">
        <el-icon :size="34"><Lock /></el-icon>
        <h2>数据管理已锁定</h2>
        <p>安全密钥已连续输入错误 {{ security.failedAttempts }} 次，需要服务器运维人员恢复。</p>
      </section>

      <template v-else-if="managementReady">
        <section class="security-session-bar">
          <div>
            <el-icon><Lock /></el-icon>
            <span>高危操作需要验证数据管理安全密钥</span>
          </div>
          <span>剩余尝试机会：{{ remainingAttempts }}</span>
        </section>

        <section class="summary-grid">
          <div class="summary-card surface-card">
            <span class="summary-card__label">当前数据库</span>
            <strong>{{ status?.database || '-' }}</strong>
          </div>
          <div class="summary-card surface-card">
            <span class="summary-card__label">服务器备份</span>
            <strong>{{ formatNumber(status?.backupCount ?? 0) }}</strong>
          </div>
          <div class="summary-card surface-card">
            <span class="summary-card__label">备份占用</span>
            <strong>{{ formatBytes(status?.backupBytes ?? 0) }}</strong>
          </div>
          <div class="summary-card surface-card">
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
            <el-button :icon="Refresh" text @click="loadManagementData">刷新列表</el-button>
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
      </template>
    </div>

    <el-dialog
      v-model="unlockDialogVisible"
      class="data-security-unlock-dialog"
      title="验证数据管理安全密钥"
      width="min(460px, calc(100vw - 32px))"
      append-to-body
      :close-on-click-modal="false"
      :close-on-press-escape="!unlockLoading"
      :show-close="!unlockLoading"
      @closed="cancelUnlock"
    >
      <div class="unlock-dialog">
        <div class="unlock-dialog__operation">
          <span>本次授权</span>
          <strong>{{ actionLabel(pendingUnlockAction) }}</strong>
          <small v-if="pendingUnlockTarget">{{ pendingUnlockTarget }}</small>
        </div>
        <el-alert
          :title="`连续输错 ${security?.maxFailedAttempts ?? 5} 次将锁定系统服务`"
          :description="`当前已错误 ${security?.failedAttempts ?? 0} 次，剩余 ${remainingAttempts} 次尝试机会。错误行为将被记录并通知初始管理员。`"
          type="warning"
          show-icon
          :closable="false"
        />
        <label for="data-management-security-key">安全密钥</label>
        <el-input
          id="data-management-security-key"
          v-model="unlockKeyInput"
          type="password"
          show-password
          autocomplete="off"
          placeholder="输入初始化时生成的安全密钥"
          :disabled="unlockLoading"
          @keyup.enter="submitUnlock"
        />
        <p v-if="unlockError" class="unlock-dialog__error">{{ unlockError }}</p>
      </div>
      <template #footer>
        <el-button :disabled="unlockLoading" @click="cancelUnlock">取消</el-button>
        <el-button type="primary" :icon="Key" :loading="unlockLoading" @click="submitUnlock">
          验证并解锁
        </el-button>
      </template>
    </el-dialog>
  </PageSection>
</template>

<style scoped lang="scss">
  .data-transfer {
    display: flex;
    flex-direction: column;
    gap: 18px;
    min-height: 180px;
  }

  .unlock-status {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: #18794e;
    font-size: 13px;
  }

  .security-setup,
  .security-locked {
    width: min(720px, 100%);
    margin: 8px auto;
    padding: 28px;
    border: 1px solid rgba(91, 141, 239, 0.16);
    border-radius: 12px;
    background: rgba(255, 255, 255, 0.76);
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 18px;

    &__icon {
      color: var(--kp-primary);

      &--warning {
        color: var(--kp-danger);
      }
    }

    h2 {
      margin: 0;
      font-size: 20px;
      letter-spacing: 0;
    }

    > p {
      margin: -8px 0 0;
      color: var(--kp-text-secondary);
      line-height: 1.7;
    }

    .el-alert {
      align-items: flex-start;
    }
  }

  .security-setup__acknowledge {
    max-width: 100%;
    height: auto;
    white-space: normal;
  }

  .security-key {
    width: 100%;
    display: grid;
    gap: 10px;

    &__label {
      color: var(--kp-text-secondary);
      font-size: 13px;
      font-weight: 600;
    }

    :deep(.el-input__inner) {
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 13px;
    }

    &__actions {
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
    }
  }

  .security-locked {
    align-items: center;
    text-align: center;
    color: var(--kp-danger);

    p {
      margin: 0;
    }
  }

  .security-session-bar {
    min-height: 44px;
    padding: 10px 14px;
    border: 1px solid rgba(240, 168, 77, 0.3);
    border-radius: 8px;
    background: rgba(255, 247, 230, 0.78);
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    color: #8a5615;
    font-size: 13px;

    div {
      display: flex;
      align-items: center;
      gap: 8px;
      font-weight: 600;
    }
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
      overflow-wrap: anywhere;
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
    border-radius: 12px;
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
      border-radius: 8px;
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

  .unlock-dialog {
    display: flex;
    flex-direction: column;
    gap: 14px;

    label {
      color: var(--kp-text-secondary);
      font-size: 13px;
      font-weight: 600;
    }

    &__error {
      margin: -4px 0 0;
      color: var(--kp-danger);
      font-size: 13px;
    }

    &__operation {
      display: grid;
      grid-template-columns: auto 1fr;
      align-items: baseline;
      gap: 4px 12px;
      padding: 10px 12px;
      border-radius: 8px;
      background: rgba(91, 141, 239, 0.08);

      span,
      small {
        color: var(--kp-text-muted);
        font-size: 12px;
      }

      small {
        grid-column: 2;
        overflow-wrap: anywhere;
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
    :global(.data-security-unlock-dialog) {
      display: flex;
      flex-direction: column;
      max-height: calc(100vh - 24px);
      max-height: calc(100dvh - 24px);
      margin: 12px auto;
    }

    :global(.data-security-unlock-dialog .el-dialog__header),
    :global(.data-security-unlock-dialog .el-dialog__footer) {
      flex: 0 0 auto;
    }

    :global(.data-security-unlock-dialog .el-dialog__body) {
      flex: 1 1 auto;
      min-height: 0;
      overflow-y: auto;
      overscroll-behavior: contain;
    }

    .summary-grid,
    .action-grid {
      grid-template-columns: 1fr;
    }

    .security-session-bar {
      align-items: flex-start;
      flex-direction: column;
    }

    .security-setup,
    .security-locked {
      padding: 22px 18px;
    }

    .import-panel__filename {
      min-width: 100%;
    }

    .backup-list {
      overflow-x: auto;
    }
  }
</style>
