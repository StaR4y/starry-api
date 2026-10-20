<script setup lang="ts">
  import { t } from '@/i18n';
  import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
  import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';
  import { ElMessage, ElMessageBox, type InputInstance } from 'element-plus';
  import {
    Check,
    CircleCheck,
    Clock,
    Close,
    Coin,
    Document,
    Files,
    FolderOpened,
    Search,
    UploadFilled,
    ArrowDown,
    ArrowRight,
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
  const activeTab = ref('backups');
  const search = ref('');
  const sort = ref('newest');
  const page = ref(1);
  const pageSize = 8;
  const loadError = ref('');
  const fileError = ref('');
  const dragging = ref(false);
  const uploadProgress = ref(0);
  const operationPending = ref(false);
  const backups = ref<BackupFile[]>([]);
  const busy = computed(() => loading.value || operationPending.value);
  const sortedBackups = computed(() =>
    [...backups.value].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt)),
  );
  const latestBackup = computed(() => sortedBackups.value[0]);
  const filteredBackups = computed(() => {
    const query = search.value.trim().toLowerCase();
    const files = sortedBackups.value.filter((file) => file.fileName.toLowerCase().includes(query));
    if (sort.value === 'oldest') files.reverse();
    if (sort.value === 'largest') files.sort((a, b) => b.sizeBytes - a.sizeBytes);
    return files;
  });
  const visibleBackups = computed(() =>
    filteredBackups.value.slice((page.value - 1) * pageSize, page.value * pageSize),
  );
  watch([search, sort], () => {
    page.value = 1;
  });
  watch(
    () => filteredBackups.value.length,
    (count) => {
      page.value = Math.min(page.value, Math.max(1, Math.ceil(count / pageSize)));
    },
  );

  async function runOperation(operation: () => Promise<void>) {
    if (busy.value) return;
    operationPending.value = true;
    try {
      await operation();
    } catch (error) {
      handleProtectedOperationError(error);
    } finally {
      operationPending.value = false;
    }
  }

  const fileInput = ref<HTMLInputElement | null>(null);
  const status = ref<DataTransferStatus | null>(null);
  const security = ref<DataManagementSecurityStatus | null>(null);
  const generatedSecurityKey = ref('');
  const securityKeyAcknowledged = ref(false);
  const pageToken = ref('');
  const pageKeyInput = ref<InputInstance>();
  const pageTokenExpiresAt = ref(0);
  let pageExpiryTimer: number | undefined;

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
    () =>
      security.value?.initialized &&
      !security.value.locked &&
      !generatedSecurityKey.value &&
      Boolean(pageToken.value),
  );
  const showAccessGate = computed(
    () =>
      security.value?.initialized &&
      !security.value.locked &&
      !generatedSecurityKey.value &&
      !pageToken.value &&
      !loadError.value,
  );
  watch([showAccessGate, busy], async ([visible, pending]) => {
    if (visible && !pending) {
      await nextTick();
      pageKeyInput.value?.focus();
    }
  });
  const isTemporarilyUnlocked = computed(
    () => Boolean(securityToken.value) && securityTokenExpiresAt.value > Date.now(),
  );
  const unlockExpiryText = computed(() =>
    isTemporarilyUnlocked.value
      ? t('{p0}已解锁至 {p1}', {
          p0: actionLabel(securityTokenAction.value),
          p1: formatDateTime(new Date(securityTokenExpiresAt.value).toISOString()),
        })
      : '',
  );

  async function loadManagementData() {
    const token = activePageToken();
    if (!token) return;
    try {
      const [nextStatus, backupList] = await Promise.all([
        dataTransferApi.status(token),
        dataTransferApi.backups(token),
      ]);
      // Ignore responses from a page grant that expired or was cleared while loading.
      if (activePageToken() !== token) return;
      status.value = nextStatus;
      backups.value = backupList.files;
    } catch (error) {
      if (pageToken.value !== token) return;
      if (error instanceof ApiError && error.code === 'DATA_MANAGEMENT_UNLOCK_REQUIRED') {
        lockPage();
        return;
      }
      handleProtectedOperationError(error);
      throw error;
    }
  }

  function activePageToken() {
    if (!pageToken.value || pageTokenExpiresAt.value <= Date.now()) {
      lockPage();
      return null;
    }
    return pageToken.value;
  }

  function lockPage() {
    pageToken.value = '';
    pageTokenExpiresAt.value = 0;
    if (pageExpiryTimer !== undefined) {
      window.clearTimeout(pageExpiryTimer);
      pageExpiryTimer = undefined;
    }
    status.value = null;
    backups.value = [];
    search.value = '';
    page.value = 1;
    activeTab.value = 'backups';
    fileError.value = '';
    if (!importing.value) selectedFile.value = null;
    clearSecurityToken();
    finishUnlockRequest(null);
  }

  function rememberPageToken(result: UnlockDataManagementResponse) {
    if (pageExpiryTimer !== undefined) window.clearTimeout(pageExpiryTimer);
    pageToken.value = result.accessToken;
    pageTokenExpiresAt.value = new Date(result.expiresAt).getTime();
    pageExpiryTimer = window.setTimeout(
      lockPage,
      Math.max(0, pageTokenExpiresAt.value - Date.now()),
    );
  }

  async function unlockManagement() {
    pendingUnlockAction.value = 'VIEW_DATA_MANAGEMENT';
    pendingUnlockTarget.value = null;
    await submitUnlock();
    if (pageToken.value) await load();
  }

  async function load() {
    loading.value = true;
    loadError.value = '';
    try {
      const nextSecurity = await dataTransferApi.securityStatus();
      security.value = nextSecurity;
      if (!nextSecurity.initialized || nextSecurity.locked || generatedSecurityKey.value) {
        lockPage();
        return;
      }
      await loadManagementData();
    } catch (error) {
      loadError.value = (error as Error).message || t('数据管理暂时无法加载');
    } finally {
      loading.value = false;
    }
  }

  async function initializeSecurity() {
    if (!isApplicationRoot.value) {
      ElMessage.warning(t('仅系统 Root 可初始化数据管理安全密钥'));
      return;
    }
    const confirmed = await ElMessageBox.confirm(
      t(
        '默认管理员是系统唯一的应用层最高权限账号，其密码泄漏即代表服务完全失控，数据管理安全密钥无法弥补该风险。继续后生成的密钥也仅展示一次，请先打开受控的密码管理器以便立即保存。',
      ),
      t('初始化数据管理安全密钥'),
      {
        get confirmButtonText() {
          return t('生成安全密钥');
        },
        get cancelButtonText() {
          return t('取消');
        },
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
      ElMessage.success(t('安全密钥已复制'));
    } else {
      ElMessage.warning(t('自动复制失败，请手动选择并复制安全密钥'));
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
      ElMessage.warning(t('请先确认安全密钥已妥善保存'));
      return;
    }
    generatedSecurityKey.value = '';
    securityKeyAcknowledged.value = false;
    await load();
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
    if (action !== 'VIEW_DATA_MANAGEMENT' && !activePageToken()) {
      return Promise.resolve(null);
    }
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
    if (unlockLoading.value) return;
    const key = unlockKeyInput.value.trim();
    if (!key) {
      unlockError.value = t('请输入安全密钥');
      return;
    }

    unlockLoading.value = true;
    unlockError.value = '';
    try {
      if (!pendingUnlockAction.value) {
        finishUnlockRequest(null);
        return;
      }
      const action = pendingUnlockAction.value;
      const result = await dataTransferApi.unlockSecurity(key, action, pendingUnlockTarget.value);
      if (pendingUnlockAction.value !== action) return;
      if (action === 'VIEW_DATA_MANAGEMENT') {
        rememberPageToken(result);
      } else {
        if (!activePageToken()) return;
        rememberSecurityToken(result);
      }
      if (security.value) {
        security.value = { ...security.value, failedAttempts: 0, lastFailureAt: null };
      }
      finishUnlockRequest(result.accessToken);
      if (action !== 'VIEW_DATA_MANAGEMENT') ElMessage.success(t('身份验证通过'));
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
        unlockError.value = t('密钥不正确');
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

      unlockError.value = t('暂时无法验证，请稍后重试');
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
      lockPage();
      router.replace({ name: 'maintenance', query: { redirect: route.fullPath } });
    }
  }

  async function exportDatabase() {
    const token = await requestSecurityToken('EXPORT_DATABASE');
    if (!token) return;
    downloading.value = 'database';
    try {
      saveDownloadedFile(await dataTransferApi.exportDatabase(token));
      ElMessage.success(t('PostgreSQL 数据库归档已开始下载'));
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
      ElMessage.success(t('完整备份包已开始下载'));
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
      ElMessage.success(t('已创建备份：{p0}', { p0: result.file.fileName }));
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

  function selectFile(file?: File) {
    if (!file || busy.value) return;
    fileError.value = '';
    if (!/\.(dump|zip)$/i.test(file.name)) {
      fileError.value = t('请选择 .dump 或 .zip 格式的备份文件');
    } else if (file.size === 0) {
      fileError.value = t('文件为空，请重新选择');
    } else if (status.value && file.size > status.value.maxImportBytes) {
      fileError.value = t('文件超出上传上限（') + maxImportSizeText.value + '）';
    }
    selectedFile.value = fileError.value ? null : file;
  }

  function handleFileChange(event: Event) {
    const target = event.target as HTMLInputElement;
    selectFile(target.files?.[0]);
    target.value = '';
  }

  function handleDrop(event: DragEvent) {
    dragging.value = false;
    if (busy.value) return;
    if (event.dataTransfer?.files.length !== 1) {
      fileError.value = t('每次只能选择一个备份文件');
      return;
    }
    selectFile(event.dataTransfer.files[0]);
  }

  function removeFile() {
    if (busy.value) return;
    selectedFile.value = null;
    fileError.value = '';
  }

  async function importData() {
    if (!selectedFile.value) {
      ElMessage.warning(t('请先选择 .dump 或 .zip 备份文件'));
      return;
    }

    const lowerName = selectedFile.value.name.toLowerCase();
    if (!lowerName.endsWith('.dump') && !lowerName.endsWith('.zip')) {
      ElMessage.warning(t('仅支持 PostgreSQL .dump 或 Starry API .zip 备份文件'));
      return;
    }

    const promptResult = await ElMessageBox.prompt(
      t(
        '导入会用 PostgreSQL 归档覆盖当前数据库。系统会在导入前自动创建一份服务器备份。请输入 RESTORE_DATABASE 确认继续。',
      ),
      t('确认导入数据库'),
      {
        get confirmButtonText() {
          return t('继续验证安全密钥');
        },
        get cancelButtonText() {
          return t('取消');
        },
        inputPattern: /^RESTORE_DATABASE$/,
        get inputErrorMessage() {
          return t('请输入 RESTORE_DATABASE');
        },
        type: 'warning',
      },
    ).catch(() => null);
    if (!promptResult || promptResult.value !== 'RESTORE_DATABASE') return;

    const token = await requestSecurityToken('RESTORE_DATABASE');
    if (!token) return;
    importing.value = true;
    uploadProgress.value = 0;
    try {
      const result = await dataTransferApi.importBackup(selectedFile.value, token, (percent) => {
        uploadProgress.value = percent;
      });
      const rollbackBackup = result.preRestoreBackupName
        ? t('，回滚备份：{p0}', { p0: result.preRestoreBackupName })
        : '';
      ElMessage.success(t('PostgreSQL 数据库恢复完成{p0}', { p0: rollbackBackup }));
      selectedFile.value = null;
      if (fileInput.value) fileInput.value.value = '';
      activeTab.value = 'backups';
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
      t('删除后将失去该恢复点。请输入备份文件名 {p0} 确认删除。', { p0: file.fileName }),
      t('删除服务器备份'),
      {
        get confirmButtonText() {
          return t('继续验证安全密钥');
        },
        get cancelButtonText() {
          return t('取消');
        },
        inputPattern: new RegExp(`^${escapeRegExp(file.fileName)}$`),
        get inputErrorMessage() {
          return t('请输入完整的备份文件名');
        },
        type: 'warning',
      },
    ).catch(() => null);
    if (!confirmed || confirmed.value !== file.fileName) return;

    const token = await requestSecurityToken('DELETE_BACKUP', file.fileName);
    if (!token) return;
    try {
      await dataTransferApi.deleteBackup(file.fileName, token);
      ElMessage.success(t('备份已删除'));
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
      case 'VIEW_DATA_MANAGEMENT':
        return t('进入数据管理');
      case 'EXPORT_DATABASE':
        return t('数据库导出');
      case 'EXPORT_BACKUP':
        return t('完整备份导出');
      case 'CREATE_BACKUP':
        return t('服务器备份创建');
      case 'DOWNLOAD_BACKUP':
        return t('服务器备份下载');
      case 'DELETE_BACKUP':
        return t('服务器备份删除');
      case 'RESTORE_DATABASE':
        return t('数据库恢复');
      default:
        return t('高危操作');
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
    if (!hasUnsavedGeneratedKey.value && !importing.value) return;
    event.preventDefault();
    event.returnValue = '';
  }

  onBeforeRouteLeave(() => {
    if (importing.value) {
      ElMessage.warning(t('数据库恢复中，请等待操作完成'));
      return false;
    }
    if (!hasUnsavedGeneratedKey.value) return true;
    return window.confirm(t('安全密钥仅显示一次，尚未确认已妥善保存。确定离开当前页面？'));
  });

  onMounted(() => {
    window.addEventListener('beforeunload', handleBeforeUnload);
    load();
  });

  onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', handleBeforeUnload);
    lockPage();
    finishUnlockRequest(null);
  });
</script>

<template>
  <PageSection
    class="data-page"
    :class="{ 'is-access-locked': showAccessGate }"
    :title="t('数据管理')"
    :subtitle="t('备份与恢复')"
    :icon="Coin"
  >
    <template #actions>
      <span class="root-badge"><el-icon><Lock /></el-icon> {{ t('Root 专属') }}</span>
      <el-tooltip v-if="managementReady" :content="t('刷新数据')" placement="bottom">
        <el-button
          class="icon-button"
          :icon="Refresh"
          :loading="loading"
          :disabled="busy || !!generatedSecurityKey"
          :aria-label="t('刷新数据')"
          @click="load"
        />
      </el-tooltip>
      <template v-if="managementReady && status && !loadError">
        <el-tooltip :content="t('锁定数据管理')" placement="bottom">
          <el-button
            class="icon-button"
            :icon="Lock"
            :disabled="busy"
            :aria-label="t('锁定数据管理')"
            @click="lockPage"
          />
        </el-tooltip>
        <el-dropdown trigger="click" :disabled="busy">
          <el-button
            :disabled="busy"
            :loading="downloading === 'database' || downloading === 'backup'"
            :icon="Download"
          >
            {{ t('导出') }} <el-icon class="dropdown-arrow"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item :icon="Files" @click="runOperation(exportBackup)">
                {{ t('完整备份包 · ZIP') }}
              </el-dropdown-item>
              <el-dropdown-item :icon="Document" @click="runOperation(exportDatabase)">
                {{ t('数据库归档 · DUMP') }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button
          type="primary"
          :icon="FolderAdd"
          :loading="creating"
          :disabled="busy"
          @click="runOperation(createBackup)"
        >
          {{ t('创建备份') }}
        </el-button>
      </template>
    </template>

    <div v-loading="loading" class="data-workspace" :aria-busy="loading">
      <section v-if="loadError" class="empty-state" role="alert">
        <el-icon class="state-icon"><Warning /></el-icon>
        <h2>{{ t('暂时无法加载') }}</h2>
        <p>{{ loadError }}</p>
        <el-button :icon="Refresh" :disabled="busy" @click="load">{{ t('重新加载') }}</el-button>
      </section>

      <section v-else-if="security?.locked" class="empty-state locked-state">
        <el-icon class="state-icon"><Lock /></el-icon>
        <h2>{{ t('数据管理暂不可用') }}</h2>
      </section>

      <section v-else-if="showSecurityInitialization" class="setup-layout">
        <div class="setup-intro">
          <span class="section-kicker">{{ t('数据安全') }}</span>
          <el-icon class="setup-symbol"><Key /></el-icon>
          <h2>{{ generatedSecurityKey ? t('保存你的安全密钥') : t('为数据操作设置保护') }}</h2>
          <p>
            {{
              generatedSecurityKey
                ? t('密钥仅在此刻显示。请妥善保存，再进入数据管理。')
                : t('初始化后，导出、备份与恢复操作都需要独立的安全密钥。')
            }}
          </p>
          <span class="root-badge"><Lock /> {{ t('仅系统 Root 可初始化') }}</span>
        </div>
        <div v-if="generatedSecurityKey" class="setup-content">
          <label class="field-label" for="generated-security-key">{{
            t('数据管理安全密钥')
          }}</label>
          <el-input
            id="generated-security-key"
            :model-value="generatedSecurityKey"
            type="textarea"
            :rows="3"
            readonly
            resize="none"
            class="key-value"
          />
          <el-button :icon="CopyDocument" @click="copySecurityKey">{{ t('复制密钥') }}</el-button>
          <p class="key-note">
            {{ t('请存入受控密码管理器或加密介质。离开后无法再次查看，也无法通过控制台找回。') }}
          </p>
          <el-checkbox v-model="securityKeyAcknowledged" class="key-acknowledge">
            {{ t('我已保存安全密钥，并妥善保护 Root 账号凭据') }}
          </el-checkbox>
          <el-button
            type="primary"
            :icon="Check"
            :disabled="!securityKeyAcknowledged || busy"
            @click="runOperation(enterDataManagement)"
          >
            {{ t('进入数据管理') }}
          </el-button>
        </div>
        <div v-else class="setup-content">
          <div class="setup-point">
            <Key />
            <div>
              <h3>{{ t('一次生成，独立保管') }}</h3>
              <p>{{ t('密钥只展示一次，遗失后无法在控制台找回。') }}</p>
            </div>
          </div>
          <div class="setup-point">
            <Lock />
            <div>
              <h3>{{ t('保护最高权限账号') }}</h3>
              <p>{{ t('安全密钥不能替代 Root 账号与密码的安全保护。') }}</p>
            </div>
          </div>
          <el-button
            v-if="isApplicationRoot"
            type="primary"
            :icon="Key"
            :loading="initializing"
            :disabled="busy"
            @click="runOperation(initializeSecurity)"
          >
            {{ t('生成安全密钥') }}
          </el-button>
          <p v-else class="muted">{{ t('请联系系统 Root 完成首次初始化。') }}</p>
        </div>
      </section>

      <section v-else-if="showAccessGate" class="access-gate">
        <form class="access-gate__form" @submit.prevent="runOperation(unlockManagement)">
          <Lock class="access-gate__symbol" aria-hidden="true" />
          <h1>{{ t('输入安全密钥') }}</h1>
          <div class="access-gate__input" :class="{ 'has-error': unlockError }">
            <el-input
              ref="pageKeyInput"
              v-model="unlockKeyInput"
              type="password"
              show-password
              autocomplete="off"
              autocapitalize="off"
              :spellcheck="false"
              :placeholder="t('安全密钥')"
              :aria-label="t('安全密钥')"
              aria-describedby="page-unlock-feedback"
              :aria-invalid="Boolean(unlockError)"
              :disabled="busy || unlockLoading"
              @input="unlockError = ''"
            />
            <el-tooltip :content="t('解锁')" placement="top">
              <el-button
                class="access-gate__submit"
                native-type="submit"
                type="primary"
                :icon="ArrowRight"
                :loading="unlockLoading"
                :disabled="busy || !unlockKeyInput.trim()"
                :aria-label="t('解锁')"
              />
            </el-tooltip>
          </div>
          <p id="page-unlock-feedback" class="access-gate__feedback" aria-live="polite">
            {{ importing ? t('恢复中，请稍候') : unlockError }}
          </p>
        </form>
      </section>

      <template v-else-if="managementReady && status">
        <dl class="data-summary">
          <div>
            <dt>{{ t('当前数据库') }}</dt>
            <dd class="database-name" :title="status.database"><Coin />{{ status.database }}</dd>
            <span>PostgreSQL</span>
          </div>
          <div>
            <dt>{{ t('服务器备份') }}</dt>
            <dd>
              {{ formatNumber(status.backupCount) }}<small>{{ t('份') }}</small>
            </dd>
            <span>{{ formatBytes(status.backupBytes) }} {{ t('已占用') }}</span>
          </div>
          <div>
            <dt>{{ t('最近备份') }}</dt>
            <dd class="last-backup">
              {{
                latestBackup ? formatDateTime(latestBackup.createdAt).slice(0, 10) : t('尚无备份')
              }}
            </dd>
            <span>{{
              latestBackup
                ? formatDateTime(latestBackup.createdAt).slice(11)
                : t('创建第一份恢复点')
            }}</span>
          </div>
          <div>
            <dt>{{ t('安全状态') }}</dt>
            <dd class="security-state"><CircleCheck />{{ t('密钥保护') }}</dd>
            <span>{{ isTemporarilyUnlocked ? unlockExpiryText : t('敏感操作独立验证') }}</span>
          </div>
        </dl>

        <el-tabs v-model="activeTab" class="data-tabs" :before-leave="() => !busy">
          <el-tab-pane name="backups">
            <template #label>
              <span class="tab-label"><Files />{{ t('备份文件')
              }}<span class="tab-count">{{ backups.length }}</span></span>
            </template>
            <section class="backup-pane" :aria-label="t('备份文件')">
              <div class="backup-toolbar">
                <el-input
                  v-model="search"
                  class="backup-search"
                  :prefix-icon="Search"
                  :placeholder="t('搜索备份文件')"
                  clearable
                  :aria-label="t('搜索备份文件')"
                />
                <el-select v-model="sort" class="backup-sort" :aria-label="t('备份排序')">
                  <el-option value="newest" :label="t('最新创建优先')" />
                  <el-option value="oldest" :label="t('最早创建优先')" />
                  <el-option value="largest" :label="t('文件从大到小')" />
                </el-select>
              </div>
              <div v-if="visibleBackups.length" class="backup-table-wrap">
                <table class="backup-table">
                  <thead>
                    <tr>
                      <th scope="col">{{ t('文件名称') }}</th>
                      <th scope="col">{{ t('大小') }}</th>
                      <th scope="col">{{ t('创建时间') }}</th>
                      <th scope="col" class="actions-cell">{{ t('操作') }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="file in visibleBackups" :key="file.fileName">
                      <td class="file-cell">
                        <span
                          class="file-symbol"
                          :class="{ 'is-dump': file.fileName.toLowerCase().endsWith('.dump') }"
                        ><el-icon><component
                          :is="
                            file.fileName.toLowerCase().endsWith('.dump') ? Document : Files
                          "
                        /></el-icon></span>
                        <div class="file-identity">
                          <div class="file-title">
                            <span :title="file.fileName">{{ file.fileName }}</span><span
                              v-if="file.fileName === latestBackup?.fileName"
                              class="latest-tag"
                            >{{ t('最新') }}</span>
                          </div>
                          <span class="file-subtitle">
                            {{
                              file.fileName.toLowerCase().endsWith('.dump')
                                ? t('数据库归档')
                                : t('完整备份包')
                            }}
                            <span class="mobile-metadata">
                              · {{ formatBytes(file.sizeBytes) }} ·
                              {{ formatDateTime(file.createdAt) }}</span>
                          </span>
                        </div>
                      </td>
                      <td class="size-cell">{{ formatBytes(file.sizeBytes) }}</td>
                      <td class="date-cell">{{ formatDateTime(file.createdAt) }}</td>
                      <td class="actions-cell">
                        <div class="file-actions">
                          <el-tooltip :content="t('下载备份')" placement="top">
                            <el-button
                              class="icon-button"
                              text
                              :icon="Download"
                              :loading="downloading === file.fileName"
                              :disabled="busy"
                              :aria-label="t('下载 ') + file.fileName"
                              @click="runOperation(() => downloadBackup(file))"
                            />
                          </el-tooltip>
                          <el-tooltip :content="t('删除备份')" placement="top">
                            <el-button
                              class="icon-button delete-button"
                              text
                              :icon="Delete"
                              :disabled="busy"
                              :aria-label="t('删除 ') + file.fileName"
                              @click="runOperation(() => deleteBackup(file))"
                            />
                          </el-tooltip>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div v-else class="empty-state backup-empty">
                <el-icon class="state-icon">
                  <component :is="search ? Search : FolderOpened" />
                </el-icon>
                <h3>{{ search ? t('没有匹配的备份') : t('还没有服务器备份') }}</h3>
                <p>
                  {{
                    search
                      ? t('试试其他文件名，或清除搜索条件。')
                      : t('创建一份备份，为当前数据保留恢复点。')
                  }}
                </p>
                <el-button v-if="search" @click="search = ''">{{ t('清除搜索') }}</el-button>
                <el-button
                  v-else
                  type="primary"
                  :icon="FolderAdd"
                  :disabled="busy"
                  @click="runOperation(createBackup)"
                >
                  {{ t('创建第一份备份') }}
                </el-button>
              </div>
              <footer class="backup-footer">
                <span>{{ t('共') }} {{ filteredBackups.length }} {{ t('份备份')
                }}<span v-if="search"> {{ t('· 已筛选') }}</span></span>
                <el-pagination
                  v-if="filteredBackups.length > pageSize"
                  v-model:current-page="page"
                  :page-size="pageSize"
                  :total="filteredBackups.length"
                  layout="prev, pager, next"
                  :pager-count="5"
                  small
                />
              </footer>
              <div class="storage-location">
                <FolderOpened /><span>{{ t('存储位置') }}</span><code>{{ status.backupDir }}</code>
              </div>
            </section>
          </el-tab-pane>

          <el-tab-pane name="restore">
            <template #label>
              <span class="tab-label"><Upload />{{ t('恢复数据') }}</span>
            </template>
            <section class="restore-layout">
              <div class="restore-main">
                <div class="section-heading">
                  <h2>{{ t('从备份恢复') }}</h2>
                  <span>{{ t('单文件上限') }} {{ maxImportSizeText }}</span>
                </div>
                <input
                  ref="fileInput"
                  class="visually-hidden"
                  type="file"
                  accept=".dump,.zip"
                  tabindex="-1"
                  :aria-label="t('选择备份文件')"
                  :disabled="busy"
                  @change="handleFileChange"
                >
                <button
                  v-if="!selectedFile"
                  class="upload-zone"
                  :class="{ 'is-dragging': dragging, 'has-error': fileError }"
                  type="button"
                  :disabled="busy"
                  :aria-label="t('上传备份文件')"
                  @click="chooseFile"
                  @dragover.prevent="dragging = !busy"
                  @dragleave.prevent="dragging = false"
                  @drop.prevent="handleDrop"
                >
                  <el-icon><UploadFilled /></el-icon>
                  <strong>{{ dragging ? t('松开以选择文件') : t('拖拽备份文件到此处') }}</strong>
                  <span>{{ t('或') }}<span class="upload-link">{{ t('点击选择文件') }}</span></span>
                  <small>.dump / .zip</small>
                </button>
                <div v-else class="selected-file">
                  <span class="file-symbol"><Document /></span>
                  <div class="selected-file__name">
                    <strong>{{ selectedFile.name }}</strong><span>{{ formatBytes(selectedFile.size)
                    }}<span v-if="!importing"> {{ t('· 已准备就绪') }}</span></span>
                  </div>
                  <el-tooltip :content="t('移除文件')">
                    <el-button
                      class="icon-button"
                      :icon="Close"
                      text
                      :disabled="busy"
                      :aria-label="t('移除文件')"
                      @click="removeFile"
                    />
                  </el-tooltip>
                </div>
                <p v-if="fileError" class="inline-error" role="alert">{{ fileError }}</p>
                <div v-if="importing" class="restore-progress" role="status" aria-live="polite">
                  <div>
                    <span>{{ uploadProgress < 100 ? t('正在上传备份') : t('正在恢复数据库') }}</span><span v-if="uploadProgress < 100">{{ uploadProgress }}%</span>
                  </div>
                  <el-progress
                    :percentage="uploadProgress"
                    :show-text="false"
                    :indeterminate="uploadProgress === 100"
                    :duration="2"
                    :stroke-width="4"
                  />
                  <p>{{ t('请保持页面打开，恢复期间服务将暂时不可用。') }}</p>
                </div>
                <div class="restore-warning">
                  <Warning />
                  <p>{{ t('恢复将覆盖当前数据库。执行前会自动保留回滚备份。') }}</p>
                </div>
                <div class="restore-controls">
                  <el-button
                    type="primary"
                    :icon="Upload"
                    :disabled="!selectedFile || busy"
                    :loading="importing"
                    @click="runOperation(importData)"
                  >
                    {{ t('验证并恢复') }}
                  </el-button>
                  <span><Lock />{{ t('需要安全密钥') }}</span>
                </div>
              </div>
              <aside class="restore-aside">
                <h3>{{ t('恢复流程') }}</h3>
                <ol class="restore-steps">
                  <li :class="{ 'is-complete': selectedFile }">
                    <span>01</span>
                    <div>
                      <strong>{{ t('选择备份') }}</strong>
                      <p>{{ t('PostgreSQL 归档或完整备份包') }}</p>
                    </div>
                  </li>
                  <li :class="{ 'is-complete': importing }">
                    <span>02</span>
                    <div>
                      <strong>{{ t('确认并验证') }}</strong>
                      <p>{{ t('确认覆盖操作，验证安全密钥') }}</p>
                    </div>
                  </li>
                  <li :class="{ 'is-current': importing }">
                    <span>03</span>
                    <div>
                      <strong>{{ t('恢复数据库') }}</strong>
                      <p>{{ t('自动备份当前数据，再执行恢复') }}</p>
                    </div>
                  </li>
                </ol>
                <div class="restore-aside__note">
                  <Clock />
                  <p>{{ t('大文件可能需要几分钟。恢复完成后，现有登录会话可能需要重新登录。') }}</p>
                </div>
              </aside>
            </section>
          </el-tab-pane>

          <el-tab-pane name="security">
            <template #label>
              <span class="tab-label"><Lock />{{ t('安全状态') }}</span>
            </template>
            <section class="security-layout">
              <div>
                <div class="section-heading">
                  <h2>{{ t('操作保护') }}</h2>
                  <span class="security-status-pill"><CircleCheck />{{ t('已启用') }}</span>
                </div>
                <dl class="security-details">
                  <div>
                    <dt>{{ t('访问权限') }}</dt>
                    <dd>{{ t('系统 Root') }}</dd>
                  </div>
                  <div>
                    <dt>{{ t('安全密钥') }}</dt>
                    <dd>
                      {{ t('已初始化') }} <span class="muted">{{ t('· 不可再次查看') }}</span>
                    </dd>
                  </div>
                  <div>
                    <dt>{{ t('初始化时间') }}</dt>
                    <dd>{{ formatDateTime(security?.initializedAt) }}</dd>
                  </div>
                  <div>
                    <dt>{{ t('页面访问') }}</dt>
                    <dd>
                      {{ t('已解锁至') }}
                      {{ formatDateTime(new Date(pageTokenExpiresAt).toISOString()) }}
                    </dd>
                  </div>
                  <div>
                    <dt>{{ t('当前授权') }}</dt>
                    <dd>{{ isTemporarilyUnlocked ? unlockExpiryText : t('未解锁任何操作') }}</dd>
                  </div>
                  <div>
                    <dt>{{ t('授权有效期') }}</dt>
                    <dd>{{ security?.unlockTtlSeconds }} {{ t('秒') }}</dd>
                  </div>
                </dl>
                <el-button
                  v-if="isTemporarilyUnlocked"
                  :icon="Lock"
                  :disabled="busy"
                  @click="clearSecurityToken"
                >
                  {{ t('清除当前授权') }}
                </el-button>
              </div>
              <aside class="security-aside">
                <h3>{{ t('验证记录') }}</h3>
                <div class="attempt-summary">
                  <strong>{{ remainingAttempts }}</strong><span>/ {{ security?.maxFailedAttempts }} {{ t('次剩余尝试') }}</span>
                </div>
                <div class="attempt-track" aria-hidden="true">
                  <span
                    v-for="attempt in security?.maxFailedAttempts"
                    :key="attempt"
                    :class="{ 'is-used': attempt > remainingAttempts }"
                  />
                </div>
                <p>
                  {{ t('连续输错') }} {{ security?.maxFailedAttempts }}
                  {{ t('次会锁定系统服务，需在服务器上恢复。') }}
                </p>
                <span class="muted">{{ t('最近错误：')
                }}{{
                  security?.lastFailureAt ? formatDateTime(security.lastFailureAt) : t('暂无记录')
                }}</span>
              </aside>
            </section>
          </el-tab-pane>
        </el-tabs>
      </template>
      <el-skeleton v-else-if="loading" :rows="7" animated class="loading-placeholder" />
    </div>

    <el-dialog
      v-model="unlockDialogVisible"
      class="data-security-unlock-dialog"
      :title="t('验证密钥')"
      width="min(460px, calc(100vw - 32px))"
      append-to-body
      :close-on-click-modal="false"
      :close-on-press-escape="!unlockLoading"
      :show-close="!unlockLoading"
      @close="cancelUnlock"
    >
      <form class="unlock-dialog" @submit.prevent="submitUnlock">
        <div class="unlock-operation">
          <span class="file-symbol"><Key /></span>
          <div>
            <span class="muted">{{ t('本次操作') }}</span><strong>{{ actionLabel(pendingUnlockAction) }}</strong>
          </div>
        </div>
        <p v-if="pendingUnlockTarget" class="unlock-target">{{ pendingUnlockTarget }}</p>
        <label class="field-label" for="data-management-security-key">{{ t('安全密钥') }}</label>
        <el-input
          id="data-management-security-key"
          v-model="unlockKeyInput"
          type="password"
          show-password
          autocomplete="off"
          :placeholder="t('输入安全密钥')"
          :disabled="unlockLoading"
          autofocus
        />
        <p v-if="unlockError" class="inline-error" role="alert">{{ unlockError }}</p>
        <div class="unlock-actions">
          <el-button :disabled="unlockLoading" @click="cancelUnlock">{{ t('取消') }}</el-button>
          <el-button native-type="submit" type="primary" :icon="Key" :loading="unlockLoading">
            {{ t('验证并继续') }}
          </el-button>
        </div>
      </form>
    </el-dialog>
  </PageSection>
</template>

<style scoped lang="scss">
  .data-page {
    :deep(.page-section__title),
    :deep(.page-section__subtitle),
    :deep(.page-section__eyebrow) {
      letter-spacing: 0;
    }
    :deep(.page-section__actions) {
      gap: 8px;
    }
    :deep(.el-button + .el-button) {
      margin-left: 0;
    }
    :deep(.el-button),
    :deep(.el-input__wrapper),
    :deep(.el-select__wrapper) {
      border-radius: 6px;
    }
  }
  .data-workspace {
    min-height: 390px;
  }
  .data-page.is-access-locked {
    background: transparent;
    border-color: transparent;
    box-shadow: none;
    backdrop-filter: none;
    :deep(.page-section__header) {
      display: none;
    }
  }
  .access-gate {
    display: grid;
    place-items: center;
    min-height: clamp(340px, 65dvh, 640px);
    padding: 32px 12px;
    text-align: center;
  }
  .access-gate__form {
    width: min(100%, 320px);
    h1 {
      margin: 22px 0 28px;
      color: var(--kp-text);
      font-size: 18px;
      font-weight: 500;
      letter-spacing: 0;
    }
  }
  .access-gate__symbol {
    width: 32px;
    height: 32px;
    color: var(--kp-text-secondary);
  }
  .access-gate__input {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 44px;
    align-items: center;
    gap: 12px;
    :deep(.el-input__wrapper) {
      min-height: 44px;
      padding: 0 14px;
      border-radius: 8px;
    }
    &.has-error {
      animation: unlock-shake 240ms ease-in-out;
      :deep(.el-input__wrapper) {
        box-shadow: 0 0 0 1px var(--kp-danger) inset;
      }
    }
  }
  .access-gate .access-gate__submit {
    width: 44px;
    height: 44px;
    padding: 0;
    border-radius: 50% !important;
    font-size: 18px;
  }
  .access-gate__feedback {
    min-height: 20px;
    margin: 12px 0 0;
    color: var(--kp-danger);
    font-size: 12px;
    line-height: 20px;
  }
  .muted {
    color: var(--kp-text-muted);
    font-size: 12px;
  }
  .root-badge {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: 12px;
    color: var(--kp-text-secondary);
    white-space: nowrap;
    margin-right: 8px;
    svg {
      width: 13px;
      height: 13px;
    }
  }
  .icon-button {
    width: 32px;
    height: 32px;
    padding: 7px;
    flex: 0 0 32px;
  }
  .dropdown-arrow {
    margin-left: 8px;
  }
  .data-summary {
    display: grid;
    grid-template-columns: 1.2fr 0.8fr 1fr 1fr;
    gap: 24px;
    margin: 0;
    padding: 27px 0 29px;
    border-top: 1px solid var(--kp-divider);
    > div {
      min-width: 0;
      padding-left: 24px;
      border-left: 1px solid var(--kp-divider);
    }
    > div:first-child {
      padding-left: 0;
      border-left: 0;
    }
    dt {
      color: var(--kp-text-secondary);
      font-size: 12px;
      margin-bottom: 8px;
    }
    dd {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0 0 5px;
      font-size: 25px;
      font-weight: 600;
      line-height: 1.4;
      font-variant-numeric: tabular-nums;
    }
    dd small {
      margin-left: 1px;
      color: var(--kp-text-muted);
      font-size: 12px;
      font-weight: 400;
    }
    dd svg {
      width: 19px;
      height: 19px;
      flex: 0 0 19px;
      color: var(--kp-primary);
    }
    .database-name,
    .last-backup {
      font-size: 18px;
      min-height: 35px;
      overflow-wrap: anywhere;
    }
    .security-state {
      font-size: 16px;
      min-height: 35px;
      svg {
        color: var(--kp-success);
      }
    }
    > div > span {
      display: block;
      color: var(--kp-text-muted);
      font-size: 12px;
      overflow-wrap: anywhere;
    }
  }
  .data-tabs {
    :deep(.el-tabs__header) {
      margin-bottom: 24px;
    }
    :deep(.el-tabs__nav-wrap::after) {
      height: 1px;
      background: var(--kp-divider);
    }
    :deep(.el-tabs__item) {
      height: 48px;
      padding: 0 24px;
      font-size: 13px;
    }
    :deep(.el-tab-pane) {
      animation: data-pane-enter 180ms ease-out;
    }
    :deep(.el-tabs__content) {
      overflow: visible;
    }
  }
  .tab-label {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    svg {
      width: 15px;
      height: 15px;
    }
  }
  .tab-count {
    background: var(--el-fill-color-light);
    color: var(--kp-text-secondary);
    padding: 0 6px;
    border-radius: 4px;
    min-width: 22px;
    font-size: 11px;
    line-height: 20px;
    text-align: center;
  }
  .backup-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 18px;
  }
  .backup-search {
    width: 300px;
    max-width: 100%;
  }
  .backup-sort {
    width: 160px;
    flex: 0 0 160px;
  }
  .backup-table {
    width: 100%;
    border-collapse: collapse;
    table-layout: fixed;
    font-size: 13px;
    th {
      color: var(--kp-text-muted);
      text-align: left;
      font-size: 12px;
      font-weight: 500;
      padding: 10px 12px;
      border-bottom: 1px solid var(--kp-divider);
    }
    th:first-child {
      width: auto;
      padding-left: 0;
    }
    th:nth-child(2) {
      width: 100px;
    }
    th:nth-child(3) {
      width: 180px;
    }
    th:last-child {
      width: 92px;
      padding-right: 0;
    }
    td {
      padding: 18px 12px;
      border-bottom: 1px solid var(--kp-divider);
      vertical-align: middle;
    }
    td:first-child {
      padding-left: 0;
    }
    td:last-child {
      padding-right: 0;
    }
    tbody tr {
      transition: background 150ms ease;
    }
    tbody tr:hover {
      background: color-mix(in srgb, var(--kp-primary) 3%, transparent);
    }
    .file-cell {
      display: flex;
      align-items: center;
      gap: 12px;
      min-height: 82px;
    }
    .size-cell,
    .date-cell {
      color: var(--kp-text-secondary);
      font-variant-numeric: tabular-nums;
      font-size: 12px;
      white-space: nowrap;
    }
    .actions-cell {
      text-align: right;
    }
  }
  .file-symbol {
    display: grid;
    place-items: center;
    width: 38px;
    height: 42px;
    flex: 0 0 38px;
    border-radius: 6px;
    color: #23836b;
    background: color-mix(in srgb, var(--kp-success) 10%, transparent);
    svg,
    .el-icon {
      width: 20px;
      height: 20px;
      font-size: 20px;
    }
    &.is-dump {
      color: var(--kp-primary);
      background: color-mix(in srgb, var(--kp-primary) 9%, transparent);
    }
  }
  .file-identity {
    min-width: 0;
  }
  .file-title {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
    font-weight: 500;
    color: var(--kp-text);
    > span:first-child {
      overflow-wrap: anywhere;
    }
  }
  .latest-tag {
    flex-shrink: 0;
    color: #23836b;
    background: color-mix(in srgb, var(--kp-success) 9%, transparent);
    font-size: 10px;
    font-weight: 500;
    padding: 1px 5px;
    border-radius: 3px;
  }
  .file-subtitle {
    display: block;
    color: var(--kp-text-muted);
    font-size: 11px;
    margin-top: 5px;
  }
  .mobile-metadata {
    display: none;
  }
  .file-actions {
    display: flex;
    justify-content: flex-end;
    gap: 4px;
  }
  .delete-button {
    color: var(--kp-text-muted);
    &:hover {
      color: var(--kp-danger);
    }
  }
  .backup-footer {
    display: flex;
    justify-content: space-between;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    min-height: 56px;
    color: var(--kp-text-muted);
    font-size: 12px;
  }
  .storage-location {
    display: flex;
    align-items: flex-start;
    gap: 7px;
    color: var(--kp-text-muted);
    padding-top: 15px;
    border-top: 1px solid var(--kp-divider);
    font-size: 11px;
    svg {
      width: 14px;
      height: 14px;
      flex-shrink: 0;
      margin-top: 2px;
    }
    > span {
      flex-shrink: 0;
    }
    code {
      overflow-wrap: anywhere;
      font-size: 11px;
    }
  }
  .section-heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 20px;
    h2 {
      margin: 0;
      font-size: 16px;
      font-weight: 600;
    }
    > span {
      font-size: 12px;
      color: var(--kp-text-muted);
    }
  }
  .restore-layout,
  .security-layout {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 270px;
    gap: 40px;
    padding: 6px 0 20px;
  }
  .restore-main {
    min-width: 0;
  }
  .upload-zone {
    width: 100%;
    min-height: 228px;
    padding: 28px 18px;
    border: 1px dashed var(--el-border-color);
    border-radius: 8px;
    background: color-mix(in srgb, var(--kp-surface) 35%, transparent);
    display: flex;
    align-items: center;
    justify-content: center;
    flex-direction: column;
    gap: 10px;
    color: var(--kp-text-secondary);
    font: inherit;
    cursor: pointer;
    transition:
      border-color 160ms,
      background 160ms;
    > .el-icon {
      font-size: 36px;
      color: var(--kp-primary);
      margin-bottom: 2px;
    }
    strong {
      font-weight: 500;
      color: var(--kp-text);
      font-size: 14px;
    }
    > span {
      font-size: 12px;
    }
    small {
      color: var(--kp-text-muted);
      font-size: 11px;
    }
    &:hover,
    &.is-dragging,
    &:focus-visible {
      border-color: var(--kp-primary);
      background: color-mix(in srgb, var(--kp-primary) 4%, transparent);
      outline: none;
    }
    &.has-error {
      border-color: var(--kp-danger);
    }
    &:disabled {
      cursor: not-allowed;
      opacity: 0.6;
    }
  }
  .upload-link {
    color: var(--kp-primary);
    margin-left: 4px;
  }
  .selected-file {
    display: flex;
    gap: 14px;
    align-items: center;
    padding: 22px 0;
    border-top: 1px solid var(--kp-divider);
    border-bottom: 1px solid var(--kp-divider);
  }
  .selected-file__name {
    min-width: 0;
    flex: 1;
    strong {
      display: block;
      overflow-wrap: anywhere;
      font-weight: 500;
      font-size: 13px;
    }
    > span {
      display: block;
      margin-top: 5px;
      font-size: 12px;
      color: var(--kp-text-muted);
    }
  }
  .restore-warning {
    display: flex;
    gap: 8px;
    align-items: flex-start;
    margin: 18px 0;
    color: var(--el-color-warning-dark-2, #996817);
    font-size: 12px;
    svg {
      width: 16px;
      height: 16px;
      flex-shrink: 0;
      margin-top: 2px;
    }
    p {
      margin: 0;
    }
  }
  .restore-controls {
    display: flex;
    align-items: center;
    gap: 15px;
    flex-wrap: wrap;
    > span {
      display: inline-flex;
      gap: 5px;
      align-items: center;
      color: var(--kp-text-muted);
      font-size: 11px;
      svg {
        width: 13px;
        height: 13px;
      }
    }
  }
  .restore-aside,
  .security-aside {
    border-left: 1px solid var(--kp-divider);
    padding-left: 30px;
    min-width: 0;
    h3 {
      margin: 0 0 24px;
      font-size: 13px;
      font-weight: 600;
    }
  }
  .restore-steps {
    list-style: none;
    padding: 0;
    margin: 0;
    li {
      display: flex;
      gap: 12px;
      margin-bottom: 25px;
      > span {
        display: grid;
        place-items: center;
        height: 26px;
        flex: 0 0 26px;
        border: 1px solid var(--kp-divider);
        border-radius: 50%;
        font-size: 10px;
        color: var(--kp-text-muted);
        font-variant-numeric: tabular-nums;
      }
      strong {
        font-size: 12px;
        font-weight: 500;
      }
      p {
        margin: 5px 0 0;
        font-size: 11px;
        line-height: 1.6;
        color: var(--kp-text-muted);
      }
    }
    .is-complete > span {
      color: #23836b;
      border-color: color-mix(in srgb, var(--kp-success) 50%, transparent);
    }
    .is-current > span {
      color: var(--kp-primary);
      border-color: var(--kp-primary);
    }
  }
  .restore-aside__note {
    display: flex;
    gap: 8px;
    border-top: 1px solid var(--kp-divider);
    padding-top: 17px;
    color: var(--kp-text-muted);
    font-size: 11px;
    svg {
      width: 15px;
      height: 15px;
      flex-shrink: 0;
      margin-top: 2px;
    }
    p {
      margin: 0;
      line-height: 1.7;
    }
  }
  .restore-progress {
    margin-top: 22px;
    > div:first-child {
      display: flex;
      justify-content: space-between;
      font-size: 12px;
      margin-bottom: 10px;
    }
    p {
      font-size: 12px;
      color: var(--kp-text-muted);
    }
  }
  .security-details {
    margin: 0 0 20px;
    > div {
      display: grid;
      grid-template-columns: 110px minmax(0, 1fr);
      gap: 15px;
      padding: 16px 0;
      border-bottom: 1px solid var(--kp-divider);
      font-size: 13px;
    }
    dt {
      color: var(--kp-text-muted);
    }
    dd {
      margin: 0;
      overflow-wrap: anywhere;
    }
  }
  .section-heading .security-status-pill {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    color: #23836b;
    svg {
      width: 14px;
      height: 14px;
    }
  }
  .attempt-summary {
    display: flex;
    align-items: baseline;
    gap: 8px;
    strong {
      font-size: 30px;
      line-height: 1;
      font-weight: 600;
    }
    span {
      color: var(--kp-text-muted);
      font-size: 12px;
    }
  }
  .attempt-track {
    display: flex;
    gap: 6px;
    margin: 18px 0;
    > span {
      flex: 1;
      height: 4px;
      border-radius: 2px;
      background: var(--kp-success);
    }
    .is-used {
      background: var(--kp-danger);
    }
  }
  .security-aside > p {
    font-size: 12px;
    color: var(--kp-text-secondary);
    line-height: 1.8;
  }
  .empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 12px;
    min-height: 330px;
    padding: 35px 15px;
    text-align: center;
    .state-icon {
      font-size: 34px;
      color: var(--kp-text-muted);
      margin-bottom: 5px;
    }
    h2,
    h3 {
      font-size: 16px;
      font-weight: 500;
      margin: 0;
    }
    p {
      margin: 0 0 8px;
      color: var(--kp-text-muted);
      font-size: 13px;
      max-width: 470px;
      overflow-wrap: anywhere;
    }
    &.locked-state .state-icon {
      color: var(--kp-danger);
    }
  }
  .backup-empty {
    min-height: 300px;
  }
  .setup-layout {
    display: grid;
    grid-template-columns: 1fr 1.1fr;
    gap: 64px;
    max-width: 870px;
    margin: 0 auto;
    padding: 50px 10px 60px;
  }
  .section-kicker {
    display: block;
    color: var(--kp-text-muted);
    font-size: 12px;
    margin-bottom: 24px;
  }
  .setup-symbol {
    font-size: 32px;
    color: var(--kp-primary);
    margin-bottom: 18px;
  }
  .setup-intro {
    h2 {
      margin: 0;
      font-size: 24px;
      font-weight: 600;
    }
    p {
      font-size: 13px;
      color: var(--kp-text-secondary);
      margin: 16px 0 24px;
      line-height: 1.9;
    }
  }
  .setup-content {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 18px;
    min-width: 0;
  }
  .setup-point {
    display: flex;
    gap: 12px;
    padding-bottom: 17px;
    width: 100%;
    border-bottom: 1px solid var(--kp-divider);
    > svg {
      width: 18px;
      height: 18px;
      flex-shrink: 0;
      margin-top: 2px;
      color: var(--kp-text-secondary);
    }
    h3 {
      font-size: 13px;
      margin: 0 0 5px;
      font-weight: 600;
    }
    p {
      margin: 0;
      font-size: 12px;
      color: var(--kp-text-muted);
      line-height: 1.7;
    }
  }
  .field-label {
    display: block;
    font-size: 13px;
    font-weight: 500;
  }
  .key-value :deep(textarea) {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 13px;
    line-height: 1.7;
    word-break: break-all;
  }
  .key-note {
    margin: 0;
    font-size: 12px;
    color: var(--kp-text-secondary);
    line-height: 1.8;
  }
  .key-acknowledge {
    max-width: 100%;
    height: auto;
    white-space: normal;
    :deep(.el-checkbox__label) {
      white-space: normal;
      font-size: 12px;
      line-height: 1.7;
    }
  }
  .unlock-dialog {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  .unlock-operation {
    display: flex;
    gap: 12px;
    align-items: center;
    strong {
      display: block;
      margin-top: 2px;
      color: var(--kp-text);
      font-size: 15px;
    }
  }
  .unlock-target {
    margin: 0 0 3px;
    padding-bottom: 12px;
    border-bottom: 1px solid var(--kp-divider);
    font-size: 12px;
    overflow-wrap: anywhere;
    color: var(--kp-text-secondary);
  }
  .unlock-actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 10px;
  }
  .inline-error {
    color: var(--kp-danger);
    font-size: 12px;
    margin: 8px 0 0;
  }
  .loading-placeholder {
    padding: 30px 0;
  }
  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    border: 0;
    clip-path: inset(50%);
    overflow: hidden;
    white-space: nowrap;
  }
  @keyframes data-pane-enter {
    from {
      opacity: 0;
      transform: translateY(5px);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }
  @keyframes unlock-shake {
    25%,
    75% {
      transform: translateX(-3px);
    }
    50% {
      transform: translateX(3px);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .access-gate__input.has-error,
    .data-tabs :deep(.el-tab-pane) {
      animation: none;
    }
  }
  @media (max-width: 1100px) {
    .data-summary {
      gap: 16px;
      > div {
        padding-left: 16px;
      }
    }
    .restore-layout,
    .security-layout {
      grid-template-columns: minmax(0, 1fr) 230px;
      gap: 25px;
    }
    .restore-aside,
    .security-aside {
      padding-left: 22px;
    }
    .backup-table th:nth-child(3) {
      width: 165px;
    }
    .backup-table th:nth-child(2) {
      width: 80px;
    }
  }
  @media (max-width: 760px) {
    .root-badge {
      margin-right: auto;
      font-size: 11px;
    }
    .data-page :deep(.page-section__actions) {
      width: 100%;
    }
    .data-summary {
      grid-template-columns: 1fr 1fr;
      padding: 22px 0;
      gap: 22px 20px;
      > div {
        padding-left: 0;
        border: 0;
      }
      dd {
        font-size: 23px;
      }
      .database-name,
      .last-backup {
        font-size: 16px;
      }
      .security-state {
        font-size: 15px;
      }
    }
    .restore-layout,
    .security-layout {
      grid-template-columns: 1fr;
      gap: 30px;
    }
    .restore-aside,
    .security-aside {
      padding: 24px 0 0;
      border-left: 0;
      border-top: 1px solid var(--kp-divider);
    }
    .restore-steps {
      display: flex;
      gap: 18px;
      li {
        flex: 1;
        flex-direction: column;
        margin-bottom: 10px;
        > span {
          flex: none;
          width: 26px;
        }
      }
    }
    .backup-table th:nth-child(3),
    .backup-table .date-cell,
    .backup-table th:nth-child(2),
    .backup-table .size-cell {
      display: none;
    }
    .backup-table .file-cell {
      gap: 10px;
      padding-block: 16px;
    }
    .file-title {
      flex-wrap: wrap;
      gap: 4px;
      font-size: 12px;
    }
    .mobile-metadata {
      display: inline;
    }
    .file-subtitle {
      font-size: 10px;
      line-height: 1.7;
    }
    .backup-table th:last-child {
      width: 72px;
    }
    .file-actions {
      gap: 0;
    }
    .file-symbol {
      width: 32px;
      height: 38px;
      flex-basis: 32px;
      svg {
        width: 18px;
        height: 18px;
      }
    }
    .setup-layout {
      grid-template-columns: 1fr;
      gap: 30px;
      padding: 25px 0;
    }
    .setup-intro h2 {
      font-size: 22px;
    }
  }
  @media (max-width: 430px) {
    .root-badge {
      display: none;
    }
    .backup-toolbar {
      gap: 8px;
    }
    .backup-sort {
      width: 140px;
      flex-basis: 140px;
    }
    .data-tabs :deep(.el-tabs__item) {
      padding-inline: 13px;
      font-size: 12px;
    }
    .tab-label {
      gap: 5px;
    }
    .storage-location {
      flex-wrap: wrap;
      code {
        flex-basis: 100%;
        padding-left: 21px;
      }
    }
    .backup-table .file-cell {
      gap: 7px;
    }
    .security-details > div {
      grid-template-columns: 85px minmax(0, 1fr);
      gap: 8px;
      font-size: 12px;
    }
  }
</style>
