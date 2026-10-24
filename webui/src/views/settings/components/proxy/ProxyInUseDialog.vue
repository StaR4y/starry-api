<script setup lang="ts">
  /** 删除节点或订阅被 409 PROXY_NODE_IN_USE 拒绝时，列出仍在使用它的账号。 */
  import { computed } from 'vue';
  import { t } from '@/i18n';
  import type { ProxyBoundAccount } from '@/api/proxyChannels';
  import ProxyAccountList from './ProxyAccountList.vue';

  const props = defineProps<{
    modelValue: boolean;
    /** 被删除的节点或订阅名称 */
    name: string;
    /** 订阅会涉及多个节点，需要显示每个账号绑定的是哪一个 */
    subscription?: boolean;
    accounts: ProxyBoundAccount[];
  }>();
  const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();

  const hint = computed(() =>
    props.subscription
      ? t('订阅「{p0}」下的节点仍被以下账号使用。请先在账号设置中解绑或改绑出站代理，再删除。', {
          p0: props.name,
        })
      : t('节点「{p0}」仍被以下账号使用。请先在账号设置中解绑或改绑出站代理，再删除。', {
          p0: props.name,
        }),
  );
  const close = () => emit('update:modelValue', false);
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    append-to-body
    :title="t('无法删除：仍有账号在使用')"
    width="min(640px, 94vw)"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="in-use-hint">{{ hint }}</p>
    <ProxyAccountList v-if="accounts.length" :accounts="accounts" :show-node="subscription" />
    <el-empty
      v-else
      :image-size="64"
      :description="t('未能获取账号列表，请在账号列表中检查出站代理设置。')"
    />
    <template #footer>
      <el-button type="primary" @click="close">{{ t('知道了') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
  .in-use-hint {
    margin: 0 0 14px;
    font-size: 13px;
    line-height: 1.6;
    color: var(--kp-text-secondary);
    overflow-wrap: anywhere;
  }
</style>
