<script setup lang="ts">
  import { t } from '@/i18n';
  /**
   * 单账号测试结果对话框。
   *
   * 从 AccountListView.vue 原样抽出：状态头部、el-descriptions
   * 摘要与两段完整信息展示（pre）。
   */
  import { computed } from 'vue';
  import type { TestAccountItem } from '@/types/api';
  import { formatDateTime } from '@/utils/format';
  import { copy, testStatusType } from '../utils';

  const props = defineProps<{
    visible: boolean;
    detail: TestAccountItem | null;
  }>();

  const emit = defineEmits<{
    (e: 'update:visible', value: boolean): void;
  }>();

  const visibleValue = computed({
    get: () => props.visible,
    set: (value: boolean) => emit('update:visible', value),
  });

  const testDetailJson = computed(() =>
    props.detail ? JSON.stringify(props.detail, null, 2) : '',
  );
</script>

<template>
  <el-dialog v-model="visibleValue" :title="t('账号测试结果')" width="760px">
    <div v-if="detail" class="test-detail">
      <div class="test-detail__head">
        <div class="test-detail__title">
          <el-tag :type="testStatusType(detail.status)" effect="light" round>
            {{ detail.status }}
          </el-tag>
          <span class="mono">{{ detail.accountId }}</span>
        </div>
        <div class="test-detail__actions">
          <el-button size="small" @click="copy(testDetailJson)">{{ t('复制完整结果') }}</el-button>
          <el-button
            size="small"
            type="primary"
            @click="copy(detail.detail || detail.message || '')"
          >
            {{ detail.status === 'FAILED' ? t('复制报错信息') : t('复制返回信息') }}
          </el-button>
        </div>
      </div>

      <el-descriptions :column="2" border size="small">
        <el-descriptions-item :label="t('邮箱')">{{ detail.email || '—' }}</el-descriptions-item>
        <el-descriptions-item :label="t('模型')">{{ detail.model || '—' }}</el-descriptions-item>
        <el-descriptions-item label="HTTP">{{ detail.statusCode ?? '—' }}</el-descriptions-item>
        <el-descriptions-item :label="t('耗时')">
          {{ detail.latencyMs == null ? '—' : `${detail.latencyMs}ms` }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('测试时间')" :span="2">
          {{ formatDateTime(detail.testedAt) }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="test-detail__section">
        <div class="test-detail__section-head">
          <span>{{ detail.status === 'FAILED' ? t('完整报错信息') : t('完整返回信息') }}</span>
          <el-button link type="primary" @click="copy(detail.detail || detail.message || '')">
            {{ t('复制') }}
          </el-button>
        </div>
        <pre class="test-detail__pre mono">{{ detail.detail || detail.message || '—' }}</pre>
      </div>

      <div class="test-detail__section">
        <div class="test-detail__section-head">
          <span>{{ t('完整 JSON') }}</span>
          <el-button link type="primary" @click="copy(testDetailJson)">{{ t('复制') }}</el-button>
        </div>
        <pre class="test-detail__pre mono">{{ testDetailJson }}</pre>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped lang="scss">
  .test-detail {
    display: grid;
    gap: 14px;

    &__head,
    &__actions {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }

    &__title {
      display: flex;
      align-items: center;
      gap: 10px;
      min-width: 0;
    }

    &__section {
      display: grid;
      gap: 6px;
    }

    &__section-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      color: var(--kp-text-secondary);
      font-weight: 700;
    }

    &__pre {
      max-height: 220px;
      overflow: auto;
      margin: 0;
      padding: 12px;
      border: 1px solid rgba(15, 23, 42, 0.08);
      border-radius: 10px;
      background: rgba(15, 23, 42, 0.86);
      color: #e5edff;
      font-size: 12px;
      line-height: 1.55;
      white-space: pre-wrap;
      word-break: break-word;
    }
  }
</style>
