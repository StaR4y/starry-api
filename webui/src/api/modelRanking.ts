/** 模型天梯：公开读 /system/model-ranking，管理设置走 /admin/model-ranking。 */
import http, { unwrap } from '@/utils/http';
import type {
  ApiResponse,
  ModelRankingBenchmark,
  ModelRankingPublicConfig,
  ModelRankingSettings,
  ModelRankingSnapshot,
  UpdateModelRankingSettingsRequest,
} from '@/types/api';

export const modelRankingApi = {
  publicConfig() {
    return unwrap<ModelRankingPublicConfig>(
      http.get<ApiResponse<ModelRankingPublicConfig>>('/system/model-ranking', { timeout: 5_000 }),
    );
  },
  public(benchmark: ModelRankingBenchmark) {
    return unwrap<ModelRankingSnapshot>(
      http.get<ApiResponse<ModelRankingSnapshot>>(`/system/model-ranking/${benchmark}`),
    );
  },
  settings() {
    return unwrap<ModelRankingSettings>(http.get<ApiResponse<ModelRankingSettings>>('/admin/model-ranking'));
  },
  update(payload: UpdateModelRankingSettingsRequest) {
    return unwrap<ModelRankingSettings>(
      http.patch<ApiResponse<ModelRankingSettings>>('/admin/model-ranking', payload),
    );
  },
  refresh() {
    return unwrap<ModelRankingSettings>(
      http.patch<ApiResponse<ModelRankingSettings>>(
        '/admin/model-ranking',
        { refreshNow: true },
        { timeout: 60_000 },
      ),
    );
  },
};
