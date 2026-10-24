<script setup lang="ts">
  /** 绑定到代理节点的账号列表（节点抽屉与「仍在使用」对话框共用）。 */
  import { t } from '@/i18n';
  import type { ProxyBoundAccount } from '@/api/proxyChannels';
  import ProviderBadge from '@/views/accounts/components/ProviderBadge.vue';
  import StatusTag from '@/components/StatusTag.vue';

  defineProps<{
    accounts: ProxyBoundAccount[];
    /** 订阅下的账号需要说明绑定的是哪个节点 */
    showNode?: boolean;
  }>();
</script>

<template>
  <el-table :data="accounts" row-key="accountId" size="small" max-height="420">
    <el-table-column :label="t('账号')" min-width="180">
      <template #default="{ row }">
        <div class="account-name">{{ row.email || row.accountId }}</div>
        <div v-if="row.email" class="account-id">{{ row.accountId }}</div>
      </template>
    </el-table-column>
    <el-table-column :label="t('平台')" min-width="130">
      <template #default="{ row }">
        <ProviderBadge :provider="row.provider" :auth-method="row.authMethod" />
      </template>
    </el-table-column>
    <el-table-column v-if="showNode" :label="t('节点')" min-width="150">
      <template #default="{ row }">
        <span class="account-node">{{ row.channelName }}</span>
      </template>
    </el-table-column>
    <el-table-column :label="t('状态')" width="90">
      <template #default="{ row }">
        <StatusTag
          :label="row.enabled ? t('已启用') : t('已停用')"
          :tone="row.enabled ? 'success' : 'info'"
        />
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
  .account-name {
    font-size: 13px;
    color: var(--kp-text);
    overflow-wrap: anywhere;
  }
  .account-id,
  .account-node {
    font-size: 12px;
    color: var(--kp-text-muted);
    overflow-wrap: anywhere;
  }
  .account-id {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  }
</style>
