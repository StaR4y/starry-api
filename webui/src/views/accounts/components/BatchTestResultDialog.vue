<script setup lang="ts">
  /**
   * 批量账号测试 / 复检结果对话框。
   *
   * 从 AccountListView.vue 原样抽出：汇总条与结果表格；
   * 单条详情与复制动作通过事件交回父组件或本地处理。
   */
  import { computed } from 'vue';
  import type { TestAccountItem, TestAccountsResult } from '@/types/api';
  import { formatDateTime } from '@/utils/format';
  import { copy, testStatusType } from '../utils';

  const props = defineProps<{
    visible: boolean;
    result: TestAccountsResult | null;
  }>();

  const emit = defineEmits<{
    (e: 'update:visible', value: boolean): void;
    (e: 'show-detail', item: TestAccountItem): void;
  }>();

  const visibleValue = computed({
    get: () => props.visible,
    set: (value: boolean) => emit('update:visible', value),
  });

  const batchTestJson = computed(() =>
    props.result ? JSON.stringify(props.result, null, 2) : '',
  );
</script>

<template>
  <el-dialog v-model="visibleValue" title="批量账号测试结果" width="1080px">
    <div v-if="result" class="batch-test-result">
      <div class="batch-test-result__summary glass-panel">
        <div class="batch-test-result__metric">
          <span>总计</span>
          <strong>{{ result.total }}</strong>
        </div>
        <div class="batch-test-result__metric success">
          <span>成功</span>
          <strong>{{ result.success }}</strong>
        </div>
        <div class="batch-test-result__metric danger">
          <span>失败</span>
          <strong>{{ result.failed }}</strong>
        </div>
        <div class="batch-test-result__metric muted">
          <span>跳过</span>
          <strong>{{ result.skipped }}</strong>
        </div>
        <div class="batch-test-result__time">
          {{ formatDateTime(result.startedAt) }} -
          {{ formatDateTime(result.finishedAt) }}
        </div>
        <el-button size="small" @click="copy(batchTestJson)">复制完整 JSON</el-button>
      </div>

      <el-table :data="result.items" size="small" class="mt-16" max-height="460">
        <el-table-column label="状态" width="105" fixed>
          <template #default="{ row }">
            <el-tag :type="testStatusType(row.status)" effect="light" round>
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="账号 ID" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.accountId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip />
        <el-table-column prop="model" label="模型" min-width="150" show-overflow-tooltip />
        <el-table-column label="HTTP" width="90" align="center">
          <template #default="{ row }">{{ row.statusCode ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="105" align="right">
          <template #default="{ row }">
            {{ row.latencyMs == null ? '—' : `${row.latencyMs}ms` }}
          </template>
        </el-table-column>
        <el-table-column prop="message" label="消息" min-width="260" show-overflow-tooltip />
        <el-table-column label="操作" width="140" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="emit('show-detail', row)">详情</el-button>
            <el-button link @click="copy(row.detail || row.message || '')">复制</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </el-dialog>
</template>

<style scoped lang="scss">
  .batch-test-result {
    display: grid;
    gap: 12px;

    &__summary {
      display: grid;
      grid-template-columns: repeat(4, minmax(80px, 1fr)) minmax(220px, 2fr) auto;
      align-items: center;
      gap: 12px;
      padding: 14px 16px;
    }

    &__metric {
      display: grid;
      gap: 4px;

      span {
        color: var(--kp-text-secondary);
        font-size: 12px;
      }

      strong {
        color: var(--kp-text-primary);
        font-size: 22px;
        line-height: 1;
      }

      &.success strong {
        color: var(--kp-success);
      }

      &.danger strong {
        color: var(--kp-danger);
      }

      &.muted strong {
        color: var(--kp-text-secondary);
      }
    }

    &__time {
      color: var(--kp-text-secondary);
      font-size: 13px;
      text-align: right;
    }
  }
</style>
