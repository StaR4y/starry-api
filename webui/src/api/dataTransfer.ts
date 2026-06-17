/** 管理员数据迁移与备份 API。 */
import http, { unwrap } from '@/utils/http';
import type {
  ApiResponse,
  BackupCreateResponse,
  BackupListResponse,
  DataTransferStatus,
  DeleteBackupResponse,
  ImportDataResult,
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

async function download(url: string, fallbackName: string): Promise<DownloadedFile> {
  const response = await http.get<Blob>(url, { responseType: 'blob' });
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
  status() {
    return unwrap<DataTransferStatus>(
      http.get<ApiResponse<DataTransferStatus>>('/admin/data-transfer/status'),
    );
  },
  exportDatabase() {
    return download('/admin/data-transfer/database.sql', 'starry-api-database.sql');
  },
  exportBackup() {
    return download('/admin/data-transfer/backup.zip', 'starry-api-backup.zip');
  },
  backups() {
    return unwrap<BackupListResponse>(
      http.get<ApiResponse<BackupListResponse>>('/admin/data-transfer/backups'),
    );
  },
  createBackup() {
    return unwrap<BackupCreateResponse>(
      http.post<ApiResponse<BackupCreateResponse>>('/admin/data-transfer/backups'),
    );
  },
  downloadBackup(fileName: string) {
    return download(
      `/admin/data-transfer/backups/${encodeURIComponent(fileName)}`,
      fileName,
    );
  },
  deleteBackup(fileName: string) {
    return unwrap<DeleteBackupResponse>(
      http.delete<ApiResponse<DeleteBackupResponse>>(
        `/admin/data-transfer/backups/${encodeURIComponent(fileName)}`,
      ),
    );
  },
  importBackup(file: File) {
    const formData = new FormData();
    formData.append('file', file);
    return unwrap<ImportDataResult>(
      http.post<ApiResponse<ImportDataResult>>(
        '/admin/data-transfer/import?confirm=RESTORE_DATABASE',
        formData,
        { timeout: 180_000 },
      ),
    );
  },
};
