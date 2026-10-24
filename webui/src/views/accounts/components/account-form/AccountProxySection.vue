<script setup lang="ts">
  /**
   * 出站代理：为账号选择一个代理节点；旧账号上遗留的代理 URL 仍可见，并提示改用节点。
   */
  import { computed } from 'vue';
  import { t } from '@/i18n';
  import ProxyChannelSelect from '../ProxyChannelSelect.vue';
  import FormSection from './FormSection.vue';
  import { useAccountFormContext } from './context';

  const { account, form, catalog, catalogLoading, catalogFailed, reloadCatalog } =
    useAccountFormContext();

  /** 只有原本就带旧代理 URL 的账号才显示这一项，新建账号不再提供原始 URL 输入 */
  const hasLegacyProxy = computed(() => !!account.value?.proxyUrl);

  const legacyHint = computed(() => {
    if (form.proxyUrl) {
      return t(
        '这是旧版的代理 URL，仅在未绑定节点时生效；建议改用代理节点，选择节点后该 URL 会被清除。',
      );
    }
    return form.proxyChannelId
      ? t('旧版代理 URL 已被所选节点取代，保存时会移除。')
      : t('旧版代理 URL 将在保存时移除，该账号会直接连接上游。');
  });

  /** 选择节点（或改为直连）时清除旧的代理 URL，避免两种配置并存 */
  function selectChannel(value: string) {
    form.proxyChannelId = value;
    form.proxyUrl = '';
  }
</script>

<template>
  <FormSection
    :title="t('出站代理')"
    :description="t('账号访问上游时经过的代理节点；不选择则直接连接。')"
  >
    <el-form-item :label="t('代理出口节点')" class="proxy-item">
      <ProxyChannelSelect
        :model-value="form.proxyChannelId"
        :catalog="catalog"
        :loading="catalogLoading"
        :failed="catalogFailed"
        @update:model-value="selectChannel"
        @refresh="reloadCatalog"
      />
      <p class="af-hint">
        {{
          t('绑定节点后账号只经该节点出站，不会回退直连；节点不可用时请求会失败并转给其他账号。')
        }}
      </p>
    </el-form-item>

    <div v-if="hasLegacyProxy" class="legacy-proxy">
      <el-form-item :label="t('出站代理 URL')">
        <el-input
          v-model="form.proxyUrl"
          type="password"
          show-password
          clearable
          autocomplete="off"
        />
        <p class="af-hint">{{ legacyHint }}</p>
      </el-form-item>
    </div>
  </FormSection>
</template>

<style scoped lang="scss">
  .legacy-proxy {
    margin-top: 4px;
  }
</style>
