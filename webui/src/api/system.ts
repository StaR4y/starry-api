/**
 * 公共系统端点：运行状态、健康检查与模型清单。
 * 运行状态使用 ApiResponse 包装，OpenAI 兼容端点返回原始结构。
 */
import http, { unwrap } from '@/utils/http';
import { withCanonicalCatalogProvider } from '@/platforms/models';
import type { ApiResponse, HealthInfo, ModelCatalogViewSettings, ModelInfo, SystemStatus } from '@/types/api';
import { modelRankingApi } from './modelRanking';

export const systemApi = {
  status() {
    return unwrap<SystemStatus>(
      http.get<ApiResponse<SystemStatus>>('/system/status', { timeout: 5_000 }),
    );
  },
  modelRanking: modelRankingApi.public,
  async health(): Promise<HealthInfo> {
    const { data } = await http.get<HealthInfo>('/health');
    return data;
  },
  async models(): Promise<ModelInfo[]> {
    const { data } = await http.get<{ data: ModelInfo[] }>('/v1/models');
    return (data?.data ?? []).map(withCanonicalCatalogProvider);
  },
  modelCatalogDisplay() {
    return unwrap<ModelCatalogViewSettings>(
      http.get<ApiResponse<ModelCatalogViewSettings>>('/system/model-catalog-display', { timeout: 5_000 }),
    );
  },
};
