<script setup lang="ts">
  /** 模型天梯数据源、同步节奏与手动刷新。 */
  import { computed, onMounted, reactive, ref } from 'vue';
  import { ElMessage } from 'element-plus';
  import { Check, Refresh, Trophy, Upload } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { modelRankingApi } from '@/api';
  import { useRankingStore } from '@/stores/ranking';
  import { formatDateTime } from '@/utils/format';
  import type { ModelRankingBenchmark, ModelRankingSettings } from '@/types/api';

  const ranking = useRankingStore();

  const loading = ref(false);
  const saving = ref(false);
  const syncing = ref(false);
  const settings = ref<ModelRankingSettings | null>(null);

  const form = reactive({
    enabled: true,
    remoteRefreshEnabled: true,
    maxEntries: 12,
    programbenchUrl: '',
    frontierbenchUrl: '',
  });

  const labels: Record<ModelRankingBenchmark, string> = {
    programbench: 'ProgramBench',
    frontierbench: 'FrontierBench',
  };

  const benchmarks = computed(() => settings.value?.benchmarks ?? []);

  function apply(next: ModelRankingSettings) {
    settings.value = next;
    form.enabled = next.enabled;
    ranking.replace(next.enabled);
    form.remoteRefreshEnabled = next.remoteRefreshEnabled;
    form.maxEntries = next.maxEntries;
    form.programbenchUrl = next.programbenchUrl;
    form.frontierbenchUrl = next.frontierbenchUrl;
  }

  async function load() {
    loading.value = true;
    try {
      apply(await modelRankingApi.settings());
    } finally {
      loading.value = false;
    }
  }

  async function save() {
    saving.value = true;
    try {
      apply(
        await modelRankingApi.update({
          enabled: form.enabled,
          remoteRefreshEnabled: form.remoteRefreshEnabled,
          maxEntries: form.maxEntries,
          programbenchUrl: form.programbenchUrl.trim(),
          frontierbenchUrl: form.frontierbenchUrl.trim(),
        }),
      );
      ElMessage.success('天梯设置已保存');
    } finally {
      saving.value = false;
    }
  }

  async function syncNow() {
    syncing.value = true;
    try {
      apply(await modelRankingApi.refresh());
      if (settings.value?.lastError) {
        ElMessage.warning('部分榜单同步失败，已保留可用数据');
      } else {
        ElMessage.success('天梯数据已同步');
      }
    } finally {
      syncing.value = false;
    }
  }

  function statusType(due: boolean, fetchedAt: string | null) {
    if (!fetchedAt) return 'info';
    return due ? 'warning' : 'success';
  }

  function statusText(due: boolean, fetchedAt: string | null) {
    if (!fetchedAt) return '内置快照';
    return due ? '待同步' : '已同步';
  }

  onMounted(load);
</script>

<template>
  <PageSection title="模型天梯" subtitle="内容与界面" :icon="Trophy">
    <template #actions>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button :icon="Upload" :loading="syncing" @click="syncNow">立即同步</el-button>
      <el-button type="primary" :icon="Check" :loading="saving" @click="save">保存</el-button>
    </template>

    <el-skeleton v-if="loading && !settings" :rows="8" animated />
    <el-form v-else :model="form" label-position="top" class="smtp-form">
      <section class="smtp-section">
        <div class="smtp-grid">
          <el-form-item label="启用模型天梯">
            <el-switch v-model="form.enabled"/>
          </el-form-item>
        </div>
      </section>

      <section class="smtp-section">
        <h3>同步状态</h3>
        <div class="status-grid">
          <article v-for="item in benchmarks" :key="item.benchmark" class="status-card">
            <div class="status-card__head">
              <strong>{{ labels[item.benchmark] }}</strong>
              <el-tag :type="statusType(item.due, item.fetchedAt)" effect="plain" size="small">
                {{ statusText(item.due, item.fetchedAt) }}
              </el-tag>
            </div>
            <p>{{ item.title }}</p>
            <p class="text-secondary">
              {{ formatDateTime(item.fetchedAt, '尚未从远程同步') }} · {{ item.entryCount }} 条
            </p>
          </article>
        </div>
        <p v-if="settings?.lastError" class="sync-error">{{ settings.lastError }}</p>
      </section>

      <section class="smtp-section">
        <h3>自动同步</h3>
        <div class="smtp-grid">
          <el-form-item label="每日自动拉取">
            <el-switch v-model="form.remoteRefreshEnabled"/>
          </el-form-item>
          <el-form-item :label="`展示条数 ${form.maxEntries}`">
            <el-slider v-model="form.maxEntries" :min="5" :max="20" :step="1" />
          </el-form-item>
          <el-form-item label="定时任务">
            <el-input :model-value="settings?.refreshCron || '0 40 4 * * *'" disabled />
          </el-form-item>
        </div>
      </section>

      <section class="smtp-section">
        <h3>数据源</h3>
        <div class="smtp-grid smtp-grid--urls">
          <el-form-item label="ProgramBench 地址">
            <el-input v-model="form.programbenchUrl" placeholder="https://raw.githubusercontent.com/..." />
          </el-form-item>
          <el-form-item label="FrontierBench 地址">
            <el-input v-model="form.frontierbenchUrl" placeholder="https://raw.githubusercontent.com/..." />
          </el-form-item>
        </div>
      </section>
    </el-form>
  </PageSection>
</template>

<style scoped lang="scss">
  .smtp-form {
    display: grid;
    gap: 22px;
  }

  .smtp-section {
    & + & {
      padding-top: 22px;
      border-top: 1px solid var(--kp-divider);
    }

    h3 {
      margin: 0 0 14px;
      font-size: 15px;
      font-weight: 650;
      letter-spacing: 0;
    }
  }

  .smtp-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 16px;

    &--urls {
      grid-template-columns: 1fr;
    }

    :deep(.el-form-item) {
      margin-bottom: 0;
    }

    :deep(.el-slider) {
      margin: 0 10px;
    }
  }

  .status-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }

  .status-card {
    padding: 14px 16px;
    background: rgba(255, 255, 255, 0.46);
    border: 1px solid var(--kp-divider);
    border-radius: 12px;

    &__head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      margin-bottom: 8px;
    }

    p {
      margin: 0;
      font-size: 13px;
      line-height: 1.55;
    }
  }

  .sync-error {
    margin: 12px 0 0;
    font-size: 13px;
    color: var(--kp-danger);
  }

  @media (max-width: 720px) {
    .smtp-grid,
    .status-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
