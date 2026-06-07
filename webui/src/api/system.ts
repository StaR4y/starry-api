/**
 * 公共代理端点：健康检查 / 模型清单。
 * 注意：这些是 OpenAI 兼容接口，**不**走 ApiResponse 包装，直接返回原始结构。
 */
import http from '@/utils/http';
import type { HealthInfo, ModelInfo } from '@/types/api';

export const systemApi = {
  async health(): Promise<HealthInfo> {
    const { data } = await http.get<HealthInfo>('/health');
    return data;
  },
  async models(): Promise<ModelInfo[]> {
    const { data } = await http.get<{ data: ModelInfo[] }>('/v1/models');
    return data?.data ?? [];
  },
};
