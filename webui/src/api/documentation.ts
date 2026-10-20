import http, { unwrap } from '@/utils/http';
import { locale } from '@/i18n';
import type { ApiResponse } from '@/types/api';

export interface DocTranslation {
  title: string;
  category: string;
  summary: string;
  body: string;
}
export interface DocContent extends DocTranslation {
  sortOrder: number;
  english?: DocTranslation | null;
}
export interface DocAttachment {
  id: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  url: string;
}
export interface DocEntry extends Omit<DocContent, 'body'> {
  id: string;
  updatedAt: string;
}
export interface DocAdminEntry extends DocEntry {
  version: number;
  published: boolean;
  hasChanges: boolean;
}
export interface DocPage {
  id: string;
  content: DocContent;
  updatedAt: string;
  attachments: DocAttachment[];
}
export interface DocAdminPage {
  id: string;
  draft: DocContent;
  published: DocContent | null;
  version: number;
  publishedAt: string | null;
  updatedAt: string;
  attachments: DocAttachment[];
}
export interface DocSettings {
  apifoxUrl: string;
}
export interface DocCatalog {
  settings: DocSettings;
  entries: DocEntry[];
}
const base = '/admin/documentation';
const idPath = (id: string) => `${base}/pages/${encodeURIComponent(id)}`;
export const documentationApi = {
  catalog: (q = '') =>
    unwrap(
      http.get<ApiResponse<DocCatalog>>('/documentation', { params: { q, lang: locale.value } }),
    ),
  read: (id: string) =>
    unwrap(
      http.get<ApiResponse<DocPage>>(`/documentation/pages/${encodeURIComponent(id)}`, {
        params: { lang: locale.value },
      }),
    ),
  list: () => unwrap(http.get<ApiResponse<DocAdminEntry[]>>(`${base}/pages`)),
  detail: (id: string) => unwrap(http.get<ApiResponse<DocAdminPage>>(idPath(id))),
  create: (content: DocContent) =>
    unwrap(http.post<ApiResponse<DocAdminPage>>(`${base}/pages`, content)),
  save: (id: string, content: DocContent, version: number, publish: boolean) =>
    unwrap(http.put<ApiResponse<DocAdminPage>>(idPath(id), { content, version, publish })),
  withdraw: (id: string, version: number, remove = false) =>
    unwrap(
      http.delete<ApiResponse<boolean>>(`${idPath(id)}${remove ? '' : '/publication'}`, {
        data: { version },
      }),
    ),
  settings: () => unwrap(http.get<ApiResponse<DocSettings>>(`${base}/settings`)),
  saveSettings: (settings: DocSettings) =>
    unwrap(http.put<ApiResponse<DocSettings>>(`${base}/settings`, settings)),
  upload: (id: string, file: File, onProgress: (percent: number) => void) => {
    const data = new FormData();
    data.append('file', file);
    return unwrap(
      http.post<ApiResponse<DocAttachment>>(`${idPath(id)}/attachments`, data, {
        timeout: 180_000,
        onUploadProgress: (event) =>
          onProgress(event.total ? Math.round((event.loaded / event.total) * 100) : 0),
      }),
    );
  },
  removeAttachment: (id: string, file: string) =>
    unwrap(
      http.delete<ApiResponse<boolean>>(`${idPath(id)}/attachments/${encodeURIComponent(file)}`),
    ),
};
