/** 管理员模型目录 API。对应后端 /admin/models。 */
import http, { unwrap } from '@/utils/http';
import { withCanonicalCatalogProvider } from '@/platforms/models';
import type {
  ApiResponse,
  ManagedModel,
  ModelCatalogDisplaySettings,
  ModelIconUploadResponse,
  ReorderManagedModelsRequest,
  UpdateModelCatalogDisplayRequest,
  UpsertManagedModelRequest,
} from '@/types/api';

export const modelsApi = {
  list() {
    return unwrap<ManagedModel[]>(http.get<ApiResponse<ManagedModel[]>>('/admin/models')).then((models) =>
      models.map(withCanonicalCatalogProvider),
    );
  },
  upsert(payload: UpsertManagedModelRequest) {
    return unwrap<ManagedModel>(http.put<ApiResponse<ManagedModel>>('/admin/models', payload)).then(
      withCanonicalCatalogProvider,
    );
  },
  uploadIcon(file: File) {
    const data = new FormData();
    data.append('file', file);
    return unwrap<ModelIconUploadResponse>(
      http.post<ApiResponse<ModelIconUploadResponse>>('/admin/models/icon', data),
    );
  },
  remove(modelId: string) {
    return unwrap<void>(http.delete<ApiResponse<void>>('/admin/models', { params: { modelId } }));
  },
  removeVendor(provider: string) {
    return unwrap<void>(http.delete<ApiResponse<void>>('/admin/models/vendors', { params: { provider } }));
  },
  reorder(payload: ReorderManagedModelsRequest) {
    return unwrap<void>(http.put<ApiResponse<void>>('/admin/models/order', payload));
  },
  getDisplay() {
    return unwrap<ModelCatalogDisplaySettings>(
      http.get<ApiResponse<ModelCatalogDisplaySettings>>('/admin/models/display'),
    );
  },
  updateDisplay(payload: UpdateModelCatalogDisplayRequest) {
    return unwrap<ModelCatalogDisplaySettings>(
      http.patch<ApiResponse<ModelCatalogDisplaySettings>>('/admin/models/display', payload),
    );
  },
};
