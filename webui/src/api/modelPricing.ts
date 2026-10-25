/** 管理员模型价格 API。对应后端 /admin/model-pricing。 */
import http, { unwrap } from '@/utils/http';
import type {
  ApiResponse,
  ModelPricingSettings,
  ModelPricingPreview,
  ModelPricingSourceStatus,
  UpdateModelPricingRequest,
  UpdateModelPricingSourceRequest,
} from '@/types/api';

export const modelPricingApi = {
  /** factors：要一并算价的号池价格系数（号源分级）；省略则不算 */
  preview(model: string, rules: UpdateModelPricingRequest['rules'], factors?: number[]) {
    return unwrap<ModelPricingPreview>(
      http.post('/admin/model-pricing/preview', {
        model,
        rules,
        ...(factors?.length ? { factors } : {}),
      }),
    );
  },
  settings() {
    return unwrap<ModelPricingSettings>(
      http.get<ApiResponse<ModelPricingSettings>>('/admin/model-pricing'),
    );
  },
  update(payload: UpdateModelPricingRequest) {
    return unwrap<ModelPricingSettings>(
      http.post<ApiResponse<ModelPricingSettings>>('/admin/model-pricing', payload),
    );
  },
  updateSource(payload: UpdateModelPricingSourceRequest) {
    return unwrap<ModelPricingSourceStatus>(
      http.patch<ApiResponse<ModelPricingSourceStatus>>('/admin/model-pricing/source', payload),
    );
  },
  refresh() {
    return unwrap<ModelPricingSourceStatus>(
      http.post<ApiResponse<ModelPricingSourceStatus>>('/admin/model-pricing/refresh'),
    );
  },
};
