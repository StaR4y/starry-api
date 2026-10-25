import { computed, ref } from 'vue';
import { t } from '@/i18n';
import { AUTO_PREFERENCE, preferenceChoices, type PreferenceChoice } from './tierPreference';
import { useTierChoices } from './tierChoices';

/**
 * 客户端配置对话框里的「号池」：这把 Key 的厂商里客户端能指定的号池（与 Key 表单的号源偏好同一份），选中的写进
 * 生成的配置。厂商没有启用分级、没有公开号池名称（对普通用户）或读取失败时没有可选项，对话框和以前一样。
 *
 * @param provider 这把 Key 的模型分类；旧的不限厂商的 Key（`*`）没有号池可选
 * @param open 对话框是否打开：每次打开都重新读取，并回到「自动」
 */
export function useSetupTier(provider: () => string | null | undefined, open: () => boolean) {
  const selected = ref(AUTO_PREFERENCE);
  const choices = useTierChoices(provider, open, () => {
    selected.value = AUTO_PREFERENCE;
  });

  /** 有号池可选才显示这一项 */
  const show = computed(() => (choices.value?.tiers.length ?? 0) > 0);
  const options = computed<PreferenceChoice[]>(() =>
    preferenceChoices(
      choices.value?.tiers ?? [],
      choices.value?.referenceModel,
      selected.value,
    ).map((choice) =>
      choice.value === AUTO_PREFERENCE
        ? { ...choice, hint: t('配置里不写号池；请求按这把 Key 的号源偏好，没有就自动') }
        : choice,
    ),
  );
  /** 写进配置的号池写法，「自动」时为 null */
  const tier = computed(() => (selected.value === AUTO_PREFERENCE ? null : selected.value));

  return { selected, options, show, tier };
}
