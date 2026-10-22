<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { Plus, Refresh, Delete, Connection, Search, Edit } from '@element-plus/icons-vue';
  import { t } from '@/i18n';
  import {
    proxyChannelsApi,
    type ProxyCatalog,
    type ProxyChannel,
    type ProxyProbe,
    type ProxySyncResult,
  } from '@/api/proxyChannels';

  const catalog = ref<ProxyCatalog>({
    channels: [],
    subscriptions: [],
    mihomoEnabled: false,
    tlsEnabled: false,
    tlsProfiles: [],
  });
  const loading = ref(false);
  const search = ref('');
  const dialog = ref<'node' | 'subscription' | null>(null);
  const saving = ref(false);
  const form = reactive({ name: '', mode: 'url', content: '' });
  const busy = reactive(new Set<string>());
  const probes = reactive<Record<string, ProxyProbe>>({});
  const issues = ref<string[]>([]);
  const channels = computed(() =>
    catalog.value.channels.filter((node) =>
      `${node.name} ${node.protocol}`.toLowerCase().includes(search.value.toLowerCase()),
    ),
  );
  const source = (id?: string | null) =>
    catalog.value.subscriptions.find((s) => s.subscriptionId === id)?.name ?? t('手动添加');
  const status = (node: ProxyChannel) =>
    !node.enabled
      ? t('已停用')
      : node.runtimeStatus === 'ACTIVE'
        ? t('已加载')
        : node.runtimeStatus === 'ERROR'
          ? t('不可用')
          : t('待加载');
  async function load() {
    loading.value = true;
    try {
      catalog.value = await proxyChannelsApi.catalog();
    } finally {
      loading.value = false;
    }
  }
  function open(kind: 'node' | 'subscription') {
    Object.assign(form, { name: '', mode: 'url', content: '' });
    dialog.value = kind;
  }
  function synced(result: ProxySyncResult) {
    issues.value = result.issues;
    ElMessage.success(t('已导入 {p0} 个节点', { p0: result.imported }));
  }
  async function save() {
    if (!form.name.trim() || !form.content.trim()) return;
    saving.value = true;
    try {
      if (dialog.value === 'node') {
        await proxyChannelsApi.create({
          name: form.name.trim(),
          ...(form.mode === 'url'
            ? { proxyUrl: form.content.trim() }
            : { clashYaml: form.content }),
        });
      } else {
        synced(
          await proxyChannelsApi.subscribe({
            name: form.name.trim(),
            ...(form.mode === 'url' ? { url: form.content.trim() } : { yaml: form.content }),
          }),
        );
      }
      dialog.value = null;
      await load();
    } finally {
      saving.value = false;
    }
  }
  async function sync(id: string) {
    busy.add(id);
    try {
      synced(await proxyChannelsApi.sync(id));
      await load();
    } finally {
      busy.delete(id);
    }
  }
  async function probe(id: string) {
    busy.add(id);
    try {
      probes[id] = await proxyChannelsApi.test(id);
    } finally {
      busy.delete(id);
    }
  }
  async function toggle(node: ProxyChannel, enabled: boolean) {
    busy.add(node.channelId);
    try {
      await proxyChannelsApi.update(node.channelId, { enabled });
      await load();
    } finally {
      busy.delete(node.channelId);
    }
  }
  async function rename(node: ProxyChannel) {
    const { value } = await ElMessageBox.prompt(t('节点名称'), t('重命名'), {
      inputValue: node.name,
      inputValidator: (value) => !!value.trim() && value.length <= 200,
    }).catch(() => ({ value: '' }));
    if (!value) return;
    await proxyChannelsApi.update(node.channelId, { name: value.trim() });
    await load();
  }
  async function remove(node: ProxyChannel) {
    const confirmed = await ElMessageBox.confirm(t('删除代理节点？'), node.name, {
      type: 'warning',
    })
      .then(() => true)
      .catch(() => false);
    if (!confirmed) return;
    await proxyChannelsApi.remove(node.channelId);
    await load();
  }
  onMounted(load);
</script>

<template>
  <div v-loading="loading" class="egress-page">
    <div class="egress-heading">
      <div>
        <h2>{{ t('出站代理') }}</h2>
        <span class="muted">{{ catalog.channels.length }} {{ t('个节点') }}</span>
      </div>
      <div class="egress-actions">
        <el-tooltip :content="t('刷新')"
          ><el-button :icon="Refresh" :aria-label="t('刷新')" @click="load"
        /></el-tooltip>
        <el-button :icon="Plus" @click="open('subscription')">{{ t('添加订阅') }}</el-button>
        <el-button type="primary" :icon="Plus" @click="open('node')">{{ t('添加节点') }}</el-button>
      </div>
    </div>
    <div class="runtime-strip">
      <span
        ><i :class="{ ready: catalog.mihomoEnabled }" />Mihomo ·
        {{ catalog.mihomoEnabled ? t('已启用') : t('未启用') }}</span
      >
      <span
        ><i :class="{ ready: catalog.tlsEnabled }" />TLS ·
        {{ catalog.tlsEnabled ? t('已启用') : t('未启用') }}</span
      >
    </div>
    <el-alert
      v-if="issues.length"
      type="warning"
      :title="t('部分节点不可用')"
      show-icon
      @close="issues = []"
    >
      <p v-for="issue in issues" :key="issue">{{ issue }}</p>
    </el-alert>
    <section v-if="catalog.subscriptions.length" class="egress-section">
      <h3>{{ t('订阅来源') }}</h3>
      <div v-for="sub in catalog.subscriptions" :key="sub.subscriptionId" class="subscription-row">
        <div>
          <strong>{{ sub.name }}</strong
          ><span class="muted">{{ sub.nodeCount }} {{ t('个节点') }}</span>
          <p v-if="sub.lastError" class="error">{{ sub.lastError }}</p>
        </div>
        <el-button
          v-if="sub.refreshable"
          :icon="Refresh"
          :loading="busy.has(sub.subscriptionId)"
          @click="sync(sub.subscriptionId)"
          >{{ t('同步') }}</el-button
        >
      </div>
    </section>
    <section class="egress-section">
      <div class="egress-heading">
        <h3>{{ t('代理节点') }}</h3>
        <el-input
          v-model="search"
          :prefix-icon="Search"
          :placeholder="t('搜索节点')"
          clearable
          class="node-search"
        />
      </div>
      <el-table :data="channels" row-key="channelId" :empty-text="t('暂无代理节点')">
        <el-table-column :label="t('节点')" min-width="190"
          ><template #default="{ row }"
            ><strong>{{ row.name }}</strong>
            <div class="muted">{{ source(row.subscriptionId) }}</div></template
          ></el-table-column
        >
        <el-table-column prop="protocol" :label="t('协议')" width="100" />
        <el-table-column :label="t('状态')" min-width="150"
          ><template #default="{ row }"
            ><el-tag
              :type="
                row.runtimeStatus === 'ACTIVE'
                  ? 'success'
                  : row.runtimeStatus === 'ERROR'
                    ? 'danger'
                    : 'info'
              "
              effect="plain"
              >{{ status(row) }}</el-tag
            >
            <div v-if="row.runtimeError" class="error">{{ row.runtimeError }}</div></template
          ></el-table-column
        >
        <el-table-column :label="t('出口测试')" min-width="175"
          ><template #default="{ row }"
            ><template v-if="probes[row.channelId]"
              ><span :class="{ error: !probes[row.channelId].success }">{{
                probes[row.channelId].success ? probes[row.channelId].exitIp : t('连接失败')
              }}</span>
              <div class="muted">{{ probes[row.channelId].latencyMs }} ms</div></template
            ><span v-else class="muted">—</span></template
          ></el-table-column
        >
        <el-table-column prop="boundAccounts" :label="t('绑定账号')" width="100" />
        <el-table-column :label="t('启用')" width="80"
          ><template #default="{ row }"
            ><el-switch
              :model-value="row.enabled"
              :loading="busy.has(row.channelId)"
              @change="toggle(row, Boolean($event))" /></template
        ></el-table-column>
        <el-table-column :label="t('操作')" width="130"
          ><template #default="{ row }">
            <el-tooltip :content="t('测试出口')"
              ><el-button
                link
                :icon="Connection"
                :disabled="busy.has(row.channelId) || !row.enabled"
                :aria-label="t('测试出口')"
                @click="probe(row.channelId)"
            /></el-tooltip>
            <el-tooltip :content="t('重命名')"
              ><el-button link :icon="Edit" :aria-label="t('重命名')" @click="rename(row)"
            /></el-tooltip>
            <el-tooltip :content="t('删除')"
              ><el-button
                link
                type="danger"
                :icon="Delete"
                :disabled="row.boundAccounts > 0"
                :aria-label="t('删除')"
                @click="remove(row)"
            /></el-tooltip> </template
        ></el-table-column>
      </el-table>
    </section>
    <el-dialog
      :model-value="dialog !== null"
      :title="dialog === 'node' ? t('添加节点') : t('添加订阅')"
      width="min(600px, 94vw)"
      destroy-on-close
      @close="dialog = null"
    >
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item :label="t('名称')" required
          ><el-input v-model="form.name" :maxlength="160"
        /></el-form-item>
        <el-form-item
          ><el-radio-group v-model="form.mode"
            ><el-radio-button value="url">URL</el-radio-button
            ><el-radio-button value="yaml">Clash YAML</el-radio-button></el-radio-group
          ></el-form-item
        >
        <el-form-item
          :label="
            form.mode === 'url' ? (dialog === 'node' ? t('代理 URL') : t('订阅 URL')) : 'Clash YAML'
          "
          required
        >
          <el-input
            v-model="form.content"
            :type="form.mode === 'url' ? 'password' : 'textarea'"
            :show-password="form.mode === 'url'"
            :rows="10"
            autocomplete="off"
            :placeholder="
              form.mode === 'url'
                ? dialog === 'node'
                  ? 'http://user:pass@host:port'
                  : 'https://example.com/subscription'
                : 'proxies:\n  - name: ...'
            "
          />
        </el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="dialog = null">{{ t('取消') }}</el-button
        ><el-button
          type="primary"
          :loading="saving"
          :disabled="!form.name.trim() || !form.content.trim()"
          @click="save"
          >{{ t('添加') }}</el-button
        ></template
      >
    </el-dialog>
  </div>
</template>

<style scoped>
  .egress-page {
    display: flex;
    flex-direction: column;
    gap: 24px;
    min-width: 0;
  }
  .egress-heading {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 16px;
    flex-wrap: wrap;
  }
  h2 {
    font-size: 20px;
    margin: 0 0 6px;
    color: var(--kp-text);
  }
  h3 {
    font-size: 15px;
    margin: 0;
    color: var(--kp-text);
  }
  .egress-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
  .egress-actions .el-button {
    margin: 0;
  }
  .muted {
    font-size: 12px;
    color: var(--kp-text-muted);
    overflow-wrap: anywhere;
  }
  .runtime-strip {
    display: flex;
    flex-wrap: wrap;
    gap: 24px;
    font-size: 12px;
    color: var(--kp-text-secondary);
  }
  .runtime-strip span {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .runtime-strip i {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--kp-text-muted);
  }
  .runtime-strip i.ready {
    background: var(--el-color-success);
  }
  .egress-section {
    display: flex;
    flex-direction: column;
    gap: 16px;
    padding-top: 20px;
    border-top: 1px solid var(--kp-divider);
    min-width: 0;
  }
  .subscription-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 16px;
    padding: 10px 0;
  }
  .subscription-row strong {
    margin-right: 14px;
    font-size: 14px;
    overflow-wrap: anywhere;
  }
  .error {
    color: var(--el-color-danger);
    font-size: 12px;
    margin: 4px 0;
    overflow-wrap: anywhere;
  }
  .node-search {
    width: min(260px, 100%);
  }
  :deep(.el-table) {
    --el-table-bg-color: transparent;
    --el-table-tr-bg-color: transparent;
  }
</style>
