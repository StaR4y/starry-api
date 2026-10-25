import { ref, watch } from 'vue';
import { sourceTiersApi, type SourceTierChoices } from '@/api/sourceTiers';

/**
 * 这个厂商里客户端能指定的号池（`GET /admin/source-tiers/me`）：Key 表单的号源偏好和客户端配置对话框的号池共用。
 * 厂商没有启用分级、没有公开号池名称（对普通用户）或读取失败时是 null，页面和以前一样。
 *
 * @param provider 这把 Key 的模型分类；空和旧的不限厂商的 Key（`*`）没有号池可选
 * @param open 页面（表单或对话框）是否打开：每次打开、或换了厂商都重新读取
 * @param onReset 每次重新读取之前调用，用来把依赖这份列表的选择退回「自动」
 */
export function useTierChoices(
  provider: () => string | null | undefined,
  open: () => boolean,
  onReset?: () => void,
) {
  const choices = ref<SourceTierChoices | null>(null);

  watch(
    () => [open(), provider()] as const,
    async ([visible, name], _, onCleanup) => {
      choices.value = null;
      onReset?.();
      if (!visible || !name || name === '*') return;
      let current = true;
      onCleanup(() => {
        current = false;
      });
      try {
        const loaded = await sourceTiersApi.choices(name);
        if (current) choices.value = loaded;
      } catch {
        /* 号池只是附加选项：读取失败时页面照常使用，不指定号池。 */
      }
    },
    { immediate: true },
  );

  return choices;
}
