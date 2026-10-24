<script setup lang="ts">
  /**
   * 出站代理：代理节点与订阅管理、Mihomo / TLS 组件状态与出口测试。
   *
   * 页面只负责编排：加载与测试在 useProxyCatalog，列表和对话框是 components/proxy/ 下的子组件。
   * 全局 axios 拦截器已经为失败的请求弹出提示，这里不再重复提示。
   */
  import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { Connection, Plus, Refresh } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { t } from '@/i18n';
  import { ApiError } from '@/utils/http';
  import {
    PROXY_NODE_IN_USE,
    proxyChannelsApi,
    type ProxyBoundAccount,
    type ProxyChannel,
    type ProxySubscription,
    type ProxySyncResult,
  } from '@/api/proxyChannels';
  import ProxyAccountsDrawer from './components/proxy/ProxyAccountsDrawer.vue';
  import ProxyAddDialog from './components/proxy/ProxyAddDialog.vue';
  import ProxyInUseDialog from './components/proxy/ProxyInUseDialog.vue';
  import ProxyNodeTable from './components/proxy/ProxyNodeTable.vue';
  import ProxyRenameDialog from './components/proxy/ProxyRenameDialog.vue';
  import ProxyStatusStrip from './components/proxy/ProxyStatusStrip.vue';
  import ProxySubscriptionList from './components/proxy/ProxySubscriptionList.vue';
  import ProxySyncResultView from './components/proxy/ProxySyncResult.vue';
  import { mihomoBoundAccounts } from './components/proxy/helpers';
  import { useProxyCatalog } from './components/proxy/useProxyCatalog';

  const { catalog, state, errorMessage, refreshing, testing, batch, load, runTests, cancelTests } =
    useProxyCatalog();
  /** 正在变更的节点 / 订阅 */
  const busy = reactive(new Set<string>());
  /** Mihomo 开关的请求进行中 */
  const switchingMihomo = ref(false);
  const sourceFilter = ref('');
  const nodesAnchor = ref<HTMLElement | null>(null);

  const addDialog = reactive({ open: false, tab: 'node' as 'node' | 'subscription' });
  const renaming = reactive({
    open: false,
    kind: 'node' as 'node' | 'subscription',
    id: '',
    name: '',
    saving: false,
  });
  const accountsDrawer = reactive({ open: false, id: '', name: '' });
  const inUse = reactive({
    open: false,
    name: '',
    subscription: false,
    accounts: [] as ProxyBoundAccount[],
  });
  const syncDialog = reactive({ open: false, result: null as ProxySyncResult | null });

  function openAdd(tab: 'node' | 'subscription') {
    Object.assign(addDialog, { open: true, tab });
  }
  const addNode = () => openAdd('node');
  const addSubscription = () => openAdd('subscription');

  async function reload() {
    await load({ silent: true });
  }

  async function withBusy(id: string, action: () => Promise<void>) {
    busy.add(id);
    try {
      await action();
    } finally {
      busy.delete(id);
    }
  }

  /* ---------- Mihomo 开关 ---------- */

  /** 启用或关闭 Mihomo；关闭会让绑定了相关节点的账号改走其他账号，所以先确认 */
  async function switchMihomo(enabled: boolean) {
    if (switchingMihomo.value) return;
    if (!enabled) {
      const accounts = mihomoBoundAccounts(catalog.value.channels);
      const confirmed = await ElMessageBox.confirm(
        accounts > 0
          ? t(
              '关闭后，需要 Mihomo 的节点将无法使用。已有 {p0} 个账号绑定了这些节点，它们的请求会改由其他账号处理，直到重新启用。',
              { p0: accounts },
            )
          : t('关闭后，需要 Mihomo 的节点将无法使用，直到重新启用。'),
        t('关闭 Mihomo？'),
        { type: 'warning', confirmButtonText: t('关闭 Mihomo'), cancelButtonText: t('取消') },
      )
        .then(() => true)
        .catch(() => false);
      if (!confirmed) return;
    }
    switchingMihomo.value = true;
    try {
      const updated = await proxyChannelsApi.setMihomoEnabled(enabled);
      if (!enabled) ElMessage.success(t('已关闭 Mihomo'));
      else if (updated.runtime.lastFailureCode) {
        ElMessage.warning(t('已启用 Mihomo，但节点未能加载，详见页面顶部的组件状态。'));
      } else ElMessage.success(t('已启用 Mihomo'));
    } catch {
      /* 已提示 */
    } finally {
      switchingMihomo.value = false;
    }
    await reload();
  }

  /* ---------- 节点 ---------- */

  function toggleNode(node: ProxyChannel, enabled: boolean) {
    return withBusy(node.channelId, async () => {
      try {
        await proxyChannelsApi.update(node.channelId, { enabled });
      } catch {
        /* 已提示 */
      }
      await reload();
    });
  }

  function showInUse(name: string, subscription: boolean, data: unknown) {
    Object.assign(inUse, {
      open: true,
      name,
      subscription,
      accounts: Array.isArray(data) ? (data as ProxyBoundAccount[]) : [],
    });
  }

  function isInUse(err: unknown): err is ApiError {
    return err instanceof ApiError && err.code === PROXY_NODE_IN_USE;
  }

  async function removeNode(node: ProxyChannel) {
    // 已知有账号绑定时不必先确认：直接列出账号
    if (node.boundAccounts > 0) {
      const accounts = await proxyChannelsApi.boundAccounts(node.channelId).catch(() => []);
      if (accounts.length) return showInUse(node.name, false, accounts);
    }
    const confirmed = await ElMessageBox.confirm(
      t('确定删除节点「{p0}」？此操作无法撤销。', { p0: node.name }),
      t('删除代理节点？'),
      { type: 'warning', confirmButtonText: t('删除'), cancelButtonText: t('取消') },
    )
      .then(() => true)
      .catch(() => false);
    if (!confirmed) return;
    await withBusy(node.channelId, async () => {
      try {
        await proxyChannelsApi.remove(node.channelId);
        ElMessage.success(t('已删除节点'));
      } catch (err) {
        if (isInUse(err)) showInUse(node.name, false, err.data);
      }
      await reload();
    });
  }

  function showAccounts(node: ProxyChannel) {
    Object.assign(accountsDrawer, { open: true, id: node.channelId, name: node.name });
  }

  /* ---------- 重命名（节点与订阅共用） ---------- */

  function startRename(kind: 'node' | 'subscription', id: string, name: string) {
    Object.assign(renaming, { open: true, kind, id, name, saving: false });
  }

  async function confirmRename(name: string) {
    renaming.saving = true;
    try {
      if (renaming.kind === 'node') await proxyChannelsApi.update(renaming.id, { name });
      else await proxyChannelsApi.renameSubscription(renaming.id, name);
      renaming.open = false;
      await reload();
    } catch {
      /* 已提示，保留对话框以便修改 */
    } finally {
      renaming.saving = false;
    }
  }

  /* ---------- 订阅 ---------- */

  function syncSubscription(id: string) {
    return withBusy(id, async () => {
      try {
        const result = await proxyChannelsApi.sync(id);
        if (result.issues.length || result.unavailable > 0) {
          Object.assign(syncDialog, { open: true, result });
        } else {
          ElMessage.success(t('已同步：{p0} 个节点', { p0: result.imported }));
        }
      } catch {
        /* 失败原因由后端记录在订阅上，刷新后可见 */
      }
      await reload();
    });
  }

  async function removeSubscription(subscription: ProxySubscription) {
    const confirmed = await ElMessageBox.confirm(
      t('将删除订阅「{p0}」及其 {p1} 个节点。此操作无法撤销。', {
        p0: subscription.name,
        p1: subscription.nodeCount,
      }),
      t('删除订阅？'),
      { type: 'warning', confirmButtonText: t('删除'), cancelButtonText: t('取消') },
    )
      .then(() => true)
      .catch(() => false);
    if (!confirmed) return;
    await withBusy(subscription.subscriptionId, async () => {
      try {
        await proxyChannelsApi.removeSubscription(subscription.subscriptionId);
        ElMessage.success(t('已删除订阅'));
        if (sourceFilter.value === subscription.subscriptionId) sourceFilter.value = '';
      } catch (err) {
        if (isInUse(err)) showInUse(subscription.name, true, err.data);
      }
      await reload();
    });
  }

  async function showSubscriptionNodes(id: string) {
    sourceFilter.value = id;
    await nextTick();
    nodesAnchor.value?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  onMounted(() => load());
  onBeforeUnmount(cancelTests);
</script>

<template>
  <PageSection :title="t('出站代理')" :subtitle="t('运行与安全')" :icon="Connection">
    <template #actions>
      <el-tooltip :content="t('刷新')">
        <el-button
          :icon="Refresh"
          :loading="refreshing"
          :disabled="state === 'loading'"
          :aria-label="t('刷新')"
          @click="reload"
        />
      </el-tooltip>
      <el-button :icon="Plus" @click="addSubscription">{{ t('添加订阅') }}</el-button>
      <el-button type="primary" :icon="Plus" @click="addNode">{{ t('添加节点') }}</el-button>
    </template>

    <el-skeleton v-if="state === 'loading'" class="egress-state" :rows="8" animated />

    <el-result
      v-else-if="state === 'error'"
      class="egress-state"
      icon="warning"
      :title="t('出站代理加载失败')"
      :sub-title="errorMessage"
    >
      <template #extra>
        <el-button type="primary" @click="load()">{{ t('重新加载') }}</el-button>
      </template>
    </el-result>

    <div v-else class="egress-stack">
      <ProxyStatusStrip :catalog="catalog" :switching="switchingMihomo" @toggle="switchMihomo" />

      <el-empty
        v-if="catalog.channels.length === 0 && catalog.subscriptions.length === 0"
        class="surface-card"
        :image-size="72"
        :description="
          t('还没有出站代理。添加一个代理节点或导入订阅后，就可以在账号设置里为账号选择出站线路。')
        "
      >
        <el-button type="primary" :icon="Plus" @click="openAdd('node')">
          {{ t('添加节点') }}
        </el-button>
        <el-button :icon="Plus" @click="openAdd('subscription')">{{ t('添加订阅') }}</el-button>
      </el-empty>

      <template v-else>
        <ProxySubscriptionList
          v-if="catalog.subscriptions.length"
          :subscriptions="catalog.subscriptions"
          :busy="busy"
          @sync="syncSubscription"
          @rename="startRename('subscription', $event.subscriptionId, $event.name)"
          @remove="removeSubscription"
          @show="showSubscriptionNodes"
        />
        <div ref="nodesAnchor" class="egress-anchor">
          <ProxyNodeTable
            v-model:source="sourceFilter"
            :channels="catalog.channels"
            :subscriptions="catalog.subscriptions"
            :mihomo-enabled="catalog.mihomoEnabled"
            :testing="testing"
            :busy="busy"
            :batch="batch"
            @test="runTests"
            @cancel="cancelTests"
            @toggle="toggleNode"
            @rename="startRename('node', $event.channelId, $event.name)"
            @remove="removeNode"
            @accounts="showAccounts"
          />
        </div>
      </template>
    </div>

    <ProxyAddDialog v-model="addDialog.open" :initial-tab="addDialog.tab" @changed="reload" />
    <ProxyRenameDialog
      v-model="renaming.open"
      :title="renaming.kind === 'node' ? t('重命名节点') : t('重命名订阅')"
      :name="renaming.name"
      :max-length="renaming.kind === 'node' ? 200 : 160"
      :saving="renaming.saving"
      @confirm="confirmRename"
    />
    <ProxyAccountsDrawer
      v-model="accountsDrawer.open"
      :channel-id="accountsDrawer.id"
      :name="accountsDrawer.name"
    />
    <ProxyInUseDialog
      v-model="inUse.open"
      :name="inUse.name"
      :subscription="inUse.subscription"
      :accounts="inUse.accounts"
    />
    <el-dialog
      v-model="syncDialog.open"
      append-to-body
      :title="t('同步完成')"
      width="min(560px, 94vw)"
      destroy-on-close
    >
      <ProxySyncResultView v-if="syncDialog.result" :result="syncDialog.result" />
      <template #footer>
        <el-button type="primary" @click="syncDialog.open = false">{{ t('完成') }}</el-button>
      </template>
    </el-dialog>
  </PageSection>
</template>

<style scoped>
  .egress-state {
    min-height: 320px;
  }
  .egress-stack {
    display: flex;
    flex-direction: column;
    gap: 20px;
    min-width: 0;
  }
  .egress-anchor {
    min-width: 0;
    scroll-margin-top: 90px;
  }
</style>
