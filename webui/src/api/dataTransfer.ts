/** 管理员数据迁移与备份 API。 */
import http, { unwrap } from '@/utils/http';
import type {
  ApiResponse,
  BackupCreateResponse,
  BackupListResponse,
  DataManagementAction,
  DataManagementSecurityStatus,
  DataTransferStatus,
  DeleteBackupResponse,
  ImportDataResult,
  InitializeDataManagementSecurityResponse,
  UnlockDataManagementResponse,
} from '@/types/api';

export interface DownloadedFile {
  blob: Blob;
  fileName: string;
}

function fileNameFromDisposition(disposition?: string): string | null {
  if (!disposition) return null;
  const utf8 = disposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (utf8?.[1]) return decodeURIComponent(utf8[1]);
  const quoted = disposition.match(/filename="([^"]+)"/i);
  if (quoted?.[1]) return quoted[1];
  const plain = disposition.match(/filename=([^;]+)/i);
  return plain?.[1]?.trim() ?? null;
}

function securityHeaders(securityToken: string) {
  return { 'X-Data-Management-Token': securityToken };
}

async function download(
  url: string,
  fallbackName: string,
  securityToken: string,
): Promise<DownloadedFile> {
  const response = await http.get<Blob>(url, {
    responseType: 'blob',
    headers: securityHeaders(securityToken),
  });
  return {
    blob: response.data,
    fileName: fileNameFromDisposition(response.headers['content-disposition']) ?? fallbackName,
  };
}

export function saveDownloadedFile(file: DownloadedFile) {
  const objectUrl = URL.createObjectURL(file.blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = file.fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(objectUrl);
}

export const dataTransferApi = {
  securityStatus() {
    return unwrap<DataManagementSecurityStatus>(
      http.get<ApiResponse<DataManagementSecurityStatus>>(
        '/admin/data-transfer/security/status',
      ),
    );
  },
  initializeSecurity() {
    return unwrap<InitializeDataManagementSecurityResponse>(
      http.post<ApiResponse<InitializeDataManagementSecurityResponse>>(
        '/admin/data-transfer/security/init',
      ),
    );
  },
  unlockSecurity(securityKey: string, action: DataManagementAction, target: string | null = null) {
    return unwrap<UnlockDataManagementResponse>(
      http.post<ApiResponse<UnlockDataManagementResponse>>(
        '/admin/data-transfer/security/unlock',
        { securityKey, action, target },
      ),
    );
  },
  status() {
    return unwrap<DataTransferStatus>(
      http.get<ApiResponse<DataTransferStatus>>('/admin/data-transfer/status'),
    );
  },
  exportDatabase(securityToken: string) {
    return download(
      '/admin/data-transfer/database.dump',
      'starry-api-database.dump',
      securityToken,
    );
  },
  exportBackup(securityToken: string) {
    return download('/admin/data-transfer/backup.zip', 'starry-api-backup.zip', securityToken);
  },
  backups() {
    return unwrap<BackupListResponse>(
      http.get<ApiResponse<BackupListResponse>>('/admin/data-transfer/backups'),
    );
  },
  createBackup(securityToken: string) {
    return unwrap<BackupCreateResponse>(
      http.post<ApiResponse<BackupCreateResponse>>('/admin/data-transfer/backups', undefined, {
        headers: securityHeaders(securityToken),
      }),
    );
  },
  downloadBackup(fileName: string, securityToken: string) {
    return download(
      `/admin/data-transfer/backups/${encodeURIComponent(fileName)}`,
      fileName,
      securityToken,
    );
  },
  deleteBackup(fileName: string, securityToken: string) {
    return unwrap<DeleteBackupResponse>(
      http.delete<ApiResponse<DeleteBackupResponse>>(
        `/admin/data-transfer/backups/${encodeURIComponent(fileName)}`,
        { headers: securityHeaders(securityToken) },
      ),
    );
  },
  importBackup(file: File, securityToken: string) {
    const formData = new FormData();
    formData.append('file', file);
    return unwrap<ImportDataResult>(
      http.post<ApiResponse<ImportDataResult>>(
        '/admin/data-transfer/import?confirm=RESTORE_DATABASE',
        formData,
        {
          timeout: 180_000,
          headers: securityHeaders(securityToken),
        },
      ),
    );
  },
};
