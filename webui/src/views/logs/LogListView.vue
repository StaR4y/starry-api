<script setup lang="ts">
  import { t } from '@/i18n';
  /**
   * 请求日志页面。
   *
   * 后端目前只返回最新 100 条 (/admin/logs 无分页参数)。
   * 这里提供：客户端关键字过滤、状态过滤、错误详情查看。
   */
  import { computed, onMounted, ref } from 'vue';
  import { Document, Refresh, Search } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { logsApi } from '@/api';
  import type { RequestLog } from '@/types/api';
  import { formatDateTime, formatNumber, shorten } from '@/utils/format';

  const loading = ref(false);
  const logs = ref<RequestLog[]>([]);
  const keyword = ref('');
  const statusFilter = ref<'all' | 'success' | 'failed'>('all');

  const filtered = computed(() => {
    const q = keyword.value.trim().toLowerCase();
    return logs.value.filter((log) => {
      if (statusFilter.value === 'success' && !log.success) return false;
      if (statusFilter.value === 'failed' && log.success) return false;
      if (!q) return true;
      return (
        log.path.toLowerCase().includes(q) ||
        (log.model ?? '').toLowerCase().includes(q) ||
        (log.requestId ?? '').toLowerCase().includes(q) ||
        (log.errorMessage ?? '').toLowerCase().includes(q)
      );
    });
  });

  async function loadLogs() {
    loading.value = true;
    try {
      logs.value = await logsApi.list();
    } finally {
      loading.value = false;
    }
  }

  const detail = ref<RequestLog | null>(null);
  const detailVisible = ref(false);
  function openDetail(row: RequestLog) {
    detail.value = row;
    detailVisible.value = true;
  }

  onMounted(loadLogs);
</script>

<template>
  <div class="log-list-view">
    <PageSection :title="t('请求日志')" :subtitle="t('调用记录')" :icon="Document">
      <template #toolbar>
        <el-input
          v-model="keyword"
          :prefix-icon="Search"
          :placeholder="t('搜索路径 / 模型 / 请求 ID / 错误信息')"
          clearable
          style="max-width: 320px"
        />
        <el-select v-model="statusFilter" style="width: 120px" :aria-label="t('筛选状态')">
          <el-option :label="t('全部')" value="all" />
          <el-option :label="t('成功')" value="success" />
          <el-option :label="t('失败')" value="failed" />
        </el-select>
        <el-button :icon="Refresh" :loading="loading" @click="loadLogs">{{ t('刷新') }}</el-button>
      </template>

      <el-table v-loading="loading" :data="filtered" @row-click="openDetail">
        <el-table-column :label="t('时间')" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="path" :label="t('路径')" min-width="220" />
        <el-table-column prop="model" :label="t('模型')" min-width="180" />
        <el-table-column :label="t('状态')" width="90">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" effect="light" round>
              {{ row.statusCode || '—' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Tokens (in/out)" width="160">
          <template #default="{ row }">
            {{ formatNumber(row.inputTokens) }} / {{ formatNumber(row.outputTokens) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('耗时')" width="100">
          <template #default="{ row }">{{ row.latencyMs ? `${row.latencyMs} ms` : '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('账号 / Key')" min-width="200">
          <template #default="{ row }">
            <div class="text-secondary mono">acct: {{ shorten(row.accountId, 6, 4) }}</div>
            <div class="text-secondary mono">key&nbsp;: {{ shorten(row.apiKeyId, 6, 4) }}</div>
          </template>
        </el-table-column>
      </el-table>
    </PageSection>

    <el-drawer
      v-model="detailVisible"
      :title="t('请求详情')"
      size="480px"
      direction="rtl"
      :with-header="true"
    >
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item :label="t('请求 ID')">
            <span class="mono">{{ detail.requestId }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('时间')">
            {{ formatDateTime(detail.createdAt) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('路径')">{{ detail.path }}</el-descriptions-item>
          <el-descriptions-item :label="t('模型')">{{ detail.model || '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('状态')">
            {{
              detail.statusCode || '—'
            }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('耗时')">
            {{ detail.latencyMs ? `${detail.latencyMs} ms` : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="Token (in/out)">
            {{ formatNumber(detail.inputTokens) }} / {{ formatNumber(detail.outputTokens) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('积分')">
            {{
              formatNumber(detail.credits)
            }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('账号')">
            <span class="mono">{{ detail.accountId || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="API Key">
            <span class="mono">{{ detail.apiKeyId || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.errorMessage" :label="t('错误')">
            <pre class="error-block">{{ detail.errorMessage }}</pre>
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
  .error-block {
    white-space: pre-wrap;
    word-break: break-word;
    margin: 0;
    color: var(--kp-danger);
    font-family: ui-monospace, 'SF Mono', Consolas, monospace;
    font-size: 12px;
  }
</style>
