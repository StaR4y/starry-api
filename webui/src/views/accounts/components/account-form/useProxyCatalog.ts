import { ref } from 'vue';
import { proxyChannelsApi, type ProxyCatalog } from '@/api/proxyChannels';

/**
 * 账号表单与 ChatGPT 授权用的出站代理目录：打开时加载一次，也可手动刷新。
 * 全局 axios 拦截器已经弹出错误提示，这里只记录失败状态供界面显示。
 */
export function useProxyCatalog() {
  const catalog = ref<ProxyCatalog | null>(null);
  const loading = ref(false);
  const failed = ref(false);
  let request = 0;

  async function load() {
    const current = ++request;
    loading.value = true;
    failed.value = false;
    try {
      const value = await proxyChannelsApi.catalog();
      if (current === request) catalog.value = value;
    } catch {
      if (current === request) failed.value = true;
    } finally {
      if (current === request) loading.value = false;
    }
  }

  return { catalog, loading, failed, load };
}
