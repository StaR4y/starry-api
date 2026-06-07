/** 模型映射 API。对应后端 /admin/model-mappings/*。 */
import http, { unwrap } from '@/utils/http';
import type {
  ApiResponse,
  CreateModelMappingRequest,
  ModelMapping,
  UpdateModelMappingRequest,
} from '@/types/api';

export const modelMappingsApi = {
  list() {
    return unwrap<ModelMapping[]>(http.get<ApiResponse<ModelMapping[]>>('/admin/model-mappings'));
  },
  create(payload: CreateModelMappingRequest) {
    return unwrap<ModelMapping>(
      http.post<ApiResponse<ModelMapping>>('/admin/model-mappings', payload),
    );
  },
  update(mappingId: string, payload: UpdateModelMappingRequest) {
    return unwrap<ModelMapping>(
      http.patch<ApiResponse<ModelMapping>>(`/admin/model-mappings/${mappingId}`, payload),
    );
  },
  remove(mappingId: string) {
    return unwrap<void>(http.delete<ApiResponse<void>>(`/admin/model-mappings/${mappingId}`));
  },
};
