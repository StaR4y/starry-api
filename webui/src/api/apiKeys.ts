/** API Key 管理 API。对应后端 /admin/api-keys/*。 */
import http, { unwrap } from '@/utils/http';
import type {
  ApiKey,
  ApiResponse,
  CreateApiKeyRequest,
  CreatedApiKey,
  UpdateApiKeyRequest,
} from '@/types/api';

export const apiKeysApi = {
  list() {
    return unwrap<ApiKey[]>(http.get<ApiResponse<ApiKey[]>>('/admin/api-keys'));
  },
  create(payload: CreateApiKeyRequest) {
    return unwrap<CreatedApiKey>(
      http.post<ApiResponse<CreatedApiKey>>('/admin/api-keys', payload),
    );
  },
  update(keyId: string, payload: UpdateApiKeyRequest) {
    return unwrap<ApiKey>(http.patch<ApiResponse<ApiKey>>(`/admin/api-keys/${keyId}`, payload));
  },
  remove(keyId: string) {
    return unwrap<void>(http.delete<ApiResponse<void>>(`/admin/api-keys/${keyId}`));
  },
};
