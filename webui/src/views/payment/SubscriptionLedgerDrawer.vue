<script setup lang="ts">
  import { t } from '@/i18n';
  import { ref, watch } from 'vue';
  import {
    adminCodingPlanApi,
    codingPlanApi,
    type SubscriptionLedgerEntry,
  } from '@/api/codingPlans';
  import { credits, ledgerLabel } from './codingPlanDisplay';
  import { formatDateTime } from '@/utils/format';
  const visible = defineModel<boolean>({ default: false });
  const props = defineProps<{ subscriptionId: string; name: string; admin?: boolean }>();
  const rows = ref<SubscriptionLedgerEntry[]>([]);
  const loading = ref(false);
  const more = ref(false);
  const failed = ref(false);
  let generation = 0;
  async function load(append = false) {
    if (append && loading.value) return;
    const current = ++generation;
    loading.value = true;
    failed.value = false;
    try {
      const result = await (props.admin ? adminCodingPlanApi : codingPlanApi).ledger(
        props.subscriptionId,
        append ? rows.value.at(-1)?.id : 0,
      );
      if (current !== generation) return;
      rows.value = append ? [...rows.value, ...result] : result;
      more.value = result.length === 100;
    } catch {
      if (current === generation) failed.value = true;
    } finally {
      if (current === generation) loading.value = false;
    }
  }
  watch(
    () => [visible.value, props.subscriptionId],
    () => {
      generation++;
      if (visible.value) {
        rows.value = [];
        more.value = false;
        void load();
      }
    },
    { immediate: true },
  );
</script>
<template>
  <el-drawer v-model="visible" :title="t('{p0} · 额度明细', { p0: name })" size="min(620px, 100vw)">
    <div v-loading="loading" class="ledger-list">
      <el-alert v-if="failed" :title="t('额度明细加载失败')" type="error" :closable="false" />
      <el-button v-if="failed" @click="load()">{{ t('重新加载') }}</el-button>
      <el-empty v-if="!rows.length && !loading && !failed" :description="t('暂无额度变动')" />
      <article v-for="row in rows" :key="row.id" class="ledger-row">
        <div>
          <strong>{{ ledgerLabel(row.entryType) }}</strong><time>{{ formatDateTime(row.createdAt) }}</time>
        </div>
        <b :class="{ debit: row.amount < 0 }">{{ row.amount > 0 ? '+' : '' }}{{ credits(row.amount) }}</b>
        <p v-if="row.reservedDelta">
          {{ t('预占变动') }} {{ row.reservedDelta > 0 ? '+' : '' }}{{ credits(row.reservedDelta) }}
        </p>
        <p v-if="row.reason">{{ row.reason }}</p>
        <small v-if="admin && row.actorId">{{ t('操作人') }} {{ row.actorId }}</small>
      </article>
      <el-button v-if="more" :loading="loading" @click="load(true)">
        {{
          t('加载更早记录')
        }}
      </el-button>
    </div>
  </el-drawer>
</template>
<style scoped>
  .ledger-row {
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 8px 16px;
    padding: 20px 0;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }
  .ledger-row time {
    display: block;
    margin-top: 6px;
    font-size: 12px;
    color: var(--kp-text-muted);
  }
  .ledger-row p,
  .ledger-row small {
    grid-column: 1/-1;
    margin: 0;
    font-size: 12px;
    color: var(--kp-text-secondary);
    overflow-wrap: anywhere;
  }
  .ledger-row b {
    font-variant-numeric: tabular-nums;
    color: #18806b;
  }
  .ledger-row b.debit {
    color: var(--kp-text);
  }
</style>
