/** 站点品牌与控制台外观。公开读 /system/appearance，写入走 /admin/appearance。 */
import http, { unwrap } from '@/utils/http';
import type { ApiResponse, Appearance, UpdateAppearanceRequest } from '@/types/api';

export const appearanceApi = {
  public() {
    return unwrap<Appearance>(http.get<ApiResponse<Appearance>>('/system/appearance', { timeout: 5_000 }));
  },
  get() {
    return unwrap<Appearance>(http.get<ApiResponse<Appearance>>('/admin/appearance'));
  },
  update(payload: UpdateAppearanceRequest) {
    return unwrap<Appearance>(http.patch<ApiResponse<Appearance>>('/admin/appearance', payload));
  },
  reset() {
    return unwrap<Appearance>(
      http.patch<ApiResponse<Appearance>>('/admin/appearance', { restoreDefault: true }),
    );
  },
  upload(kind: 'logo' | 'favicon' | 'background', file: File) {
    const data = new FormData();
    data.append('file', file);
    return unwrap<Appearance>(http.post<ApiResponse<Appearance>>(`/admin/appearance/${kind}`, data));
  },
  remove(kind: 'logo' | 'favicon' | 'background') {
    return unwrap<Appearance>(http.delete<ApiResponse<Appearance>>(`/admin/appearance/${kind}`));
  },
};
