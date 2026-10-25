<script setup lang="ts">
  import { t } from '@/i18n';
  /**
   * 请求日志页面。
   *
   * 后端目前只返回最新 100 条 (/admin/logs 无分页参数)。
   * 这里提供：客户端关键字过滤、状态过滤、错误详情查看。
   * 按号源分级服务的请求另有「号源」列（服务这次尝试的号池、级别和系数），详情里还有派发方式、
   * 上游模型名、用量来源、停止原因和降级特性；没有任何分级请求时不显示这一列。
   */
  import { computed, onMounted, ref } from 'vue';
  import { Document, Refresh, Search } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { logsApi } from '@/api';
  import type { RequestLog } from '@/types/api';
  import { formatDateTime, formatNumber, shorten } from '@/utils/format';
  import { hasSourceTiers, priceFactorLabel, sourceTierLabel } from './logSource';

  const loading = ref(false);
  const logs = ref<RequestLog[]>([]);
  const keyword = ref('');
  const statusFilter = ref<'all' | 'success' | 'failed'>('all');

  const showSource = computed(() => hasSourceTiers(logs.value));

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
        (log.errorMessage ?? '').toLowerCase().includes(q) ||
        sourceTierLabel(log).toLowerCase().includes(q)
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
          :placeholder="t('搜索路径 / 模型 / 请求 ID / 错误信息 / 号源')"
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
        <el-table-column v-if="showSource" :label="t('号源')" min-width="150">
          <template #default="{ row }">
            <template v-if="sourceTierLabel(row)">
              <span>{{ sourceTierLabel(row) }}</span>
              <span v-if="priceFactorLabel(row)" class="text-secondary tier-factor">
                {{ priceFactorLabel(row) }}
              </span>
              <el-tag v-if="row.tierSelection === 'PINNED'" size="small" effect="plain" type="info">
                {{ t('指定') }}
              </el-tag>
            </template>
            <span v-else class="text-secondary">—</span>
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
          <template v-if="sourceTierLabel(detail)">
            <el-descriptions-item :label="t('号源')">
              {{ sourceTierLabel(detail) }}
              <span v-if="priceFactorLabel(detail)" class="text-secondary tier-factor">
                {{ priceFactorLabel(detail) }}
              </span>
              <span v-if="detail.sourceTierRef" class="mono text-secondary tier-factor">
                {{ detail.sourceTierRef }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.tierSelection" :label="t('选号方式')">
              {{ detail.tierSelection === 'PINNED' ? t('客户端指定号池') : t('网关自动选号') }}
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.sourceDispatch" :label="t('派发方式')">
              <span class="mono">{{ detail.sourceDispatch }}</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.wireModel" :label="t('上游模型名')">
              <span class="mono">{{ detail.wireModel }}</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.usageSource" :label="t('用量来源')">
              <span class="mono">{{ detail.usageSource }}</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.stopReason" :label="t('停止原因')">
              <span class="mono">{{ detail.stopReason }}</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.degradedFeatures" :label="t('降级特性')">
              {{ detail.degradedFeatures }}
            </el-descriptions-item>
          </template>
          <el-descriptions-item v-if="detail.errorMessage" :label="t('错误')">
            <pre class="error-block">{{ detail.errorMessage }}</pre>
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
  .tier-factor {
    margin-left: 6px;
    font-size: 12px;
  }

  .error-block {
    white-space: pre-wrap;
    word-break: break-word;
    margin: 0;
    color: var(--kp-danger);
    font-family: ui-monospace, 'SF Mono', Consolas, monospace;
    font-size: 12px;
  }
</style>
