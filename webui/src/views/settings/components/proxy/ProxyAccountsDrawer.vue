<script setup lang="ts">
  /** 某个节点上绑定的账号。打开时按需加载。 */
  import { ref, watch } from 'vue';
  import { t } from '@/i18n';
  import { proxyChannelsApi, type ProxyBoundAccount } from '@/api/proxyChannels';
  import ProxyAccountList from './ProxyAccountList.vue';

  const props = defineProps<{ modelValue: boolean; channelId: string; name: string }>();
  const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();

  const state = ref<'loading' | 'ready' | 'error'>('loading');
  const accounts = ref<ProxyBoundAccount[]>([]);
  let requestId = 0;

  async function load() {
    const current = ++requestId;
    state.value = 'loading';
    try {
      const result = await proxyChannelsApi.boundAccounts(props.channelId);
      if (current !== requestId) return;
      accounts.value = result;
      state.value = 'ready';
    } catch {
      if (current === requestId) state.value = 'error';
    }
  }

  watch(
    () => [props.modelValue, props.channelId] as const,
    ([open]) => {
      if (open) void load();
    },
    { immediate: true },
  );
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    append-to-body
    :title="t('{p0} · 绑定的账号', { p0: name })"
    size="min(560px, 100vw)"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-skeleton v-if="state === 'loading'" :rows="4" animated />
    <el-result v-else-if="state === 'error'" icon="warning" :title="t('账号列表加载失败')">
      <template #extra>
        <el-button type="primary" @click="load">{{ t('重新加载') }}</el-button>
      </template>
    </el-result>
    <el-empty
      v-else-if="accounts.length === 0"
      :image-size="72"
      :description="t('没有账号使用该节点')"
    />
    <ProxyAccountList v-else :accounts="accounts" />
  </el-drawer>
</template>
