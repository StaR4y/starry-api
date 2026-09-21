/**
 * 模型天梯是否对控制台开放。公开接口即可读取，用来隐藏侧栏入口。
 */
import { defineStore } from 'pinia';
import { modelRankingApi } from '@/api';

export const useRankingStore = defineStore('ranking', {
  state: (): { enabled: boolean; loaded: boolean } => ({
    enabled: true,
    loaded: false,
  }),

  actions: {
    replace(enabled: boolean) {
      this.enabled = enabled;
      this.loaded = true;
    },
    async load() {
      try {
        this.replace((await modelRankingApi.publicConfig()).enabled);
      } catch {
        this.replace(true);
      }
    },
  },
});
