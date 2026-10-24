<script setup lang="ts">
  /**
   * 代理节点表：筛选、分页、批量出口测试与逐行操作。
   * 数据与变更由父组件负责，这里只维护筛选 / 选择状态并发出事件。
   */
  import { computed, reactive, ref, watch } from 'vue';
  import { Connection, Delete, Edit, Search } from '@element-plus/icons-vue';
  import { t } from '@/i18n';
  import type { ProxyChannel, ProxySubscription } from '@/api/proxyChannels';
  import ProxyNodeStatus from './ProxyNodeStatus.vue';
  import ProxyProbeCell from './ProxyProbeCell.vue';
  import { canTest, nodeStatus, routeLabel } from './helpers';

  const MANUAL = '__manual__';

  const props = defineProps<{
    channels: ProxyChannel[];
    subscriptions: ProxySubscription[];
    mihomoEnabled: boolean;
    /** 正在测试 / 正在变更的节点 */
    testing: Set<string>;
    busy: Set<string>;
    batch: { running: boolean; total: number; done: number };
  }>();
  const emit = defineEmits<{
    test: [ids: string[]];
    cancel: [];
    toggle: [node: ProxyChannel, enabled: boolean];
    rename: [node: ProxyChannel];
    remove: [node: ProxyChannel];
    accounts: [node: ProxyChannel];
  }>();
  /** 来源筛选：'' 全部，MANUAL 手动添加，其余为订阅 ID。订阅区的「查看节点」会设置它。 */
  const source = defineModel<string>('source', { default: '' });

  const filters = reactive({ keyword: '', route: '', status: '' });
  const page = ref(1);
  const pageSize = ref(20);
  const selected = ref<ProxyChannel[]>([]);
  const tableRef = ref<{ clearSelection: () => void } | null>(null);

  function matchesSource(node: ProxyChannel) {
    if (!source.value) return true;
    if (source.value === MANUAL) return !node.subscriptionId;
    return node.subscriptionId === source.value;
  }

  const filtered = computed(() => {
    const keyword = filters.keyword.trim().toLowerCase();
    return props.channels.filter((node) => {
      if (!matchesSource(node)) return false;
      if (filters.route && node.route !== filters.route) return false;
      if (filters.status && nodeStatus(node, props.mihomoEnabled).group !== filters.status) {
        return false;
      }
      if (!keyword) return true;
      return [node.name, node.protocol, node.subscriptionName, node.lastTestExitIp].some((field) =>
        (field ?? '').toLowerCase().includes(keyword),
      );
    });
  });
  const paged = computed(() =>
    filtered.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value),
  );
  const filtering = computed(
    () => !!(filters.keyword.trim() || filters.route || filters.status || source.value),
  );
  watch([filtered, pageSize], () => {
    const last = Math.max(1, Math.ceil(filtered.value.length / pageSize.value));
    if (page.value > last) page.value = last;
  });
  // 筛选条件变化后，被隐藏的行不应继续处于选中状态
  watch([() => filters.keyword, () => filters.route, () => filters.status, source], () => {
    page.value = 1;
    tableRef.value?.clearSelection();
  });

  /** 选中项以最新的目录数据为准（目录刷新后行对象会被替换，节点也可能已被删除） */
  const selectedNodes = computed(() => {
    const ids = new Set(selected.value.map((node) => node.channelId));
    return props.channels.filter((node) => ids.has(node.channelId));
  });
  const scope = computed(() => (selectedNodes.value.length ? selectedNodes.value : filtered.value));
  const testTargets = computed(() =>
    scope.value.filter((node) => canTest(node, props.mihomoEnabled)).map((node) => node.channelId),
  );
  const skipped = computed(() => scope.value.length - testTargets.value.length);
  const progress = computed(() =>
    props.batch.total ? Math.round((props.batch.done / props.batch.total) * 100) : 0,
  );

  function clearFilters() {
    Object.assign(filters, { keyword: '', route: '', status: '' });
    source.value = '';
  }
</script>

<template>
  <section class="node-section">
    <header class="node-head">
      <h3 class="section-title">
        {{ t('代理节点') }}
        <span class="section-count">
          {{ filtering ? `${filtered.length} / ${channels.length}` : channels.length }}
        </span>
      </h3>
      <div class="node-batch">
        <el-tooltip
          :content="t('另有 {p0} 个节点当前无法测试，将被跳过', { p0: skipped })"
          :disabled="skipped === 0"
          placement="top"
        >
          <span>
            <el-button
              :icon="Connection"
              :disabled="batch.running || testTargets.length === 0"
              @click="emit('test', testTargets)"
            >
              {{
                selectedNodes.length
                  ? t('测试所选（{p0}）', { p0: testTargets.length })
                  : t('测试筛选结果（{p0}）', { p0: testTargets.length })
              }}
            </el-button>
          </span>
        </el-tooltip>
      </div>
    </header>

    <div class="node-filters">
      <el-input
        v-model="filters.keyword"
        class="node-filters__search"
        :prefix-icon="Search"
        :placeholder="t('搜索名称、协议或出口 IP')"
        clearable
      />
      <el-select
        v-model="source"
        class="node-filters__select"
        :placeholder="t('全部来源')"
        :aria-label="t('来源')"
        clearable
      >
        <el-option :label="t('手动添加')" :value="MANUAL" />
        <el-option
          v-for="sub in subscriptions"
          :key="sub.subscriptionId"
          :label="sub.name"
          :value="sub.subscriptionId"
        />
      </el-select>
      <el-select
        v-model="filters.route"
        class="node-filters__select"
        :placeholder="t('全部线路')"
        :aria-label="t('线路')"
        clearable
      >
        <el-option :label="routeLabel('DIRECT_PROXY')" value="DIRECT_PROXY" />
        <el-option :label="routeLabel('MIHOMO')" value="MIHOMO" />
      </el-select>
      <el-select
        v-model="filters.status"
        class="node-filters__select"
        :placeholder="t('全部状态')"
        :aria-label="t('状态')"
        clearable
      >
        <el-option :label="t('可用')" value="active" />
        <el-option :label="t('待加载')" value="pending" />
        <el-option :label="t('异常')" value="abnormal" />
        <el-option :label="t('已停用')" value="disabled" />
      </el-select>
    </div>

    <div v-if="batch.running && batch.total > 1" class="node-progress" role="status">
      <el-progress :percentage="progress" :stroke-width="6" :show-text="false" />
      <span class="node-progress__text">
        {{ t('正在测试 {p0} / {p1}', { p0: batch.done, p1: batch.total }) }}
      </span>
      <el-button link type="primary" @click="emit('cancel')">{{ t('停止') }}</el-button>
    </div>

    <div class="node-table surface-card">
      <el-table
        ref="tableRef"
        :data="paged"
        row-key="channelId"
        @selection-change="selected = $event"
      >
        <template #empty>
          <div class="node-empty">
            <span>{{ filtering ? t('没有符合条件的节点') : t('暂无代理节点') }}</span>
            <el-button v-if="filtering" link type="primary" @click="clearFilters">
              {{ t('清除筛选') }}
            </el-button>
          </div>
        </template>
        <el-table-column type="selection" width="36" reserve-selection />
        <el-table-column :label="t('节点')" min-width="140">
          <template #default="{ row }">
            <div class="node-name">{{ row.name }}</div>
            <div class="node-source">
              {{ row.subscriptionName || t('手动添加') }} · {{ row.protocol }}
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('线路与状态')" width="140">
          <template #default="{ row }">
            <ProxyNodeStatus :node="row" :mihomo-enabled="mihomoEnabled" />
            <div class="node-route">
              <el-tag
                size="small"
                effect="plain"
                :type="
                  row.route === 'DIRECT_PROXY' ? 'success' : mihomoEnabled ? 'info' : 'warning'
                "
              >
                {{ routeLabel(row.route) }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('最近测试')" min-width="130">
          <template #default="{ row }">
            <ProxyProbeCell :node="row" :testing="testing.has(row.channelId)" />
          </template>
        </el-table-column>
        <el-table-column :label="t('账号')" width="68" align="center">
          <template #default="{ row }">
            <el-button
              v-if="row.boundAccounts > 0"
              link
              type="primary"
              @click="emit('accounts', row)"
            >
              {{ row.boundAccounts }}
            </el-button>
            <span v-else class="node-source">0</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('启用')" width="60" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :loading="busy.has(row.channelId)"
              :aria-label="t('启用')"
              @change="emit('toggle', row, Boolean($event))"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('操作')" width="96">
          <template #default="{ row }">
            <div class="node-actions">
              <el-tooltip
                :content="canTest(row, mihomoEnabled) ? t('测试出口') : t('当前状态下无法测试')"
              >
                <span>
                  <el-button
                    link
                    :icon="Connection"
                    :disabled="
                      !canTest(row, mihomoEnabled) || testing.has(row.channelId) || batch.running
                    "
                    :aria-label="t('测试出口')"
                    @click="emit('test', [row.channelId])"
                  />
                </span>
              </el-tooltip>
              <el-tooltip :content="t('重命名')">
                <el-button
                  link
                  :icon="Edit"
                  :disabled="busy.has(row.channelId)"
                  :aria-label="t('重命名')"
                  @click="emit('rename', row)"
                />
              </el-tooltip>
              <el-tooltip :content="t('删除')">
                <el-button
                  link
                  type="danger"
                  :icon="Delete"
                  :disabled="busy.has(row.channelId)"
                  :aria-label="t('删除')"
                  @click="emit('remove', row)"
                />
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="filtered.length > 20"
        v-model:current-page="page"
        v-model:page-size="pageSize"
        class="node-pagination"
        background
        small
        layout="total, sizes, prev, pager, next"
        :page-sizes="[20, 50, 100]"
        :total="filtered.length"
        :pager-count="5"
      />
    </div>
  </section>
</template>

<style scoped lang="scss">
  .node-section {
    display: flex;
    flex-direction: column;
    gap: 12px;
    min-width: 0;
  }

  .node-head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 8px 16px;
  }

  .section-title {
    display: flex;
    align-items: baseline;
    gap: 8px;
    margin: 0;
    font-size: 15px;
    color: var(--kp-text);
  }

  .section-count {
    font-size: 12px;
    font-weight: 400;
    color: var(--kp-text-muted);
  }

  .node-filters {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;

    &__search {
      flex: 1 1 200px;
      min-width: 0;
    }

    &__select {
      flex: 0 1 136px;
      min-width: 110px;
    }
  }

  .node-progress {
    display: flex;
    align-items: center;
    gap: 12px;

    .el-progress {
      flex: 1;
      min-width: 0;
    }

    &__text {
      font-size: 12px;
      color: var(--kp-text-secondary);
      white-space: nowrap;
    }
  }

  .node-table {
    min-width: 0;
    padding: 4px 8px 8px;
    overflow: hidden;
  }

  .node-name {
    font-size: 14px;
    font-weight: 600;
    color: var(--kp-text);
    overflow-wrap: anywhere;
  }

  .node-source {
    font-size: 12px;
    color: var(--kp-text-muted);
    overflow-wrap: anywhere;
  }

  .node-route {
    margin-top: 4px;
  }

  .node-actions {
    display: flex;
    align-items: center;
    gap: 2px;

    .el-button {
      margin: 0;
    }
  }

  .node-table :deep(.el-table .cell) {
    padding: 0 8px;
  }

  .node-empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 4px;
    padding: 24px 0;
    color: var(--kp-text-muted);
  }

  .node-pagination {
    justify-content: flex-end;
    margin-top: 12px;
    flex-wrap: wrap;
  }
</style>
