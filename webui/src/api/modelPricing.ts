/** 管理员模型价格 API。对应后端 /admin/model-pricing。 */
import http, { unwrap } from '@/utils/http';
import type {
  ApiResponse,
  ModelPricingSettings,
  UpdateModelPricingRequest,
} from '@/types/api';

export const modelPricingApi = {
  settings() {
    return unwrap<ModelPricingSettings>(
      http.get<ApiResponse<ModelPricingSettings>>('/admin/model-pricing'),
    );
  },
  update(payload: UpdateModelPricingRequest) {
    return unwrap<ModelPricingSettings>(
      http.put<ApiResponse<ModelPricingSettings>>('/admin/model-pricing', payload),
    );
  },
};
