<script setup lang="ts">
  /**
   * 批量把选中的账号设为某个号池（号源分级），或取消它们的手动指定。
   *
   * 走 POST /admin/source-tiers/{vendor}/members：只增改、移除成员行，不重发整份配置。请求带读取时的
   * 配置版本，期间被别人改过会被拒绝（409），这里重新读取最新配置后让管理员确认再试。账号在号池里的位置
   * 默认按类别自动归级，指定只是把某个账号固定到一个号池；取消指定就回到按类别归级。
   */
  import { computed, ref, watch } from 'vue';
  import { ElMessage } from 'element-plus';
  import { t } from '@/i18n';
  import { ApiError } from '@/utils/http';
  import {
    SOURCE_TIER_VERSION_CONFLICT,
    sourceTiersApi,
    type SourceTierVendor,
  } from '@/api/sourceTiers';
  import type { Account } from '@/types/api';
  import { UNPIN, batchPlan } from '@/views/settings/components/source-tiers/tierStatusMeta';

  const props = defineProps<{
    visible: boolean;
    /** 选中的账号 */
    accounts: Account[];
  }>();
  const emit = defineEmits<{
    (e: 'update:visible', value: boolean): void;
    (e: 'saved'): void;
  }>();

  const visibleValue = computed({
    get: () => props.visible,
    set: (value: boolean) => emit('update:visible', value),
  });

  const state = ref<'loading' | 'ready' | 'error'>('loading');
  const loadError = ref('');
  const vendors = ref<SourceTierVendor[]>([]);
  const vendorId = ref('');
  const target = ref('');
  const saving = ref(false);
  const saveError = ref('');

  const vendor = computed(() => vendors.value.find((item) => item.vendor === vendorId.value) ?? null);
  const tiers = computed(() =>
    [...(vendor.value?.tiers ?? [])].sort((a, b) => a.level - b.level),
  );
  const plan = computed(() =>
    vendor.value && target.value
      ? batchPlan(
          vendor.value,
          props.accounts.map((account) => account.accountId),
          target.value,
        )
      : null,
  );
  const unpinning = computed(() => target.value === UNPIN);
  const canSave = computed(() => !!plan.value?.requests.length && !saving.value);

  async function load() {
    state.value = 'loading';
    loadError.value = '';
    try {
      const overview = await sourceTiersApi.overview();
      // 只有已经有号池的厂商才能把账号放进去
      vendors.value = overview.vendors.filter((item) => item.tiers.length > 0);
      if (!vendors.value.some((item) => item.vendor === vendorId.value)) {
        vendorId.value = vendors.value[0]?.vendor ?? '';
      }
      chooseTarget();
      state.value = 'ready';
    } catch (err) {
      loadError.value = err instanceof ApiError ? err.message : t('服务暂时不可用');
      state.value = 'error';
    }
  }

  /** 目标号池不在当前厂商里时，改选它的第一个号池 */
  function chooseTarget() {
    if (target.value === UNPIN) return;
    if (!tiers.value.some((tier) => tier.tierId === target.value)) {
      target.value = tiers.value[0]?.tierId ?? '';
    }
  }

  watch(
    () => props.visible,
    (open) => {
      if (!open) return;
      saveError.value = '';
      target.value = '';
      void load();
    },
    { immediate: true },
  );
  watch(vendorId, () => {
    saveError.value = '';
    chooseTarget();
  });

  function tierLabel(level: number, name: string) {
    return `L${level} ${name}`;
  }

  async function save() {
    if (!vendor.value || !plan.value || !canSave.value) return;
    const requests = plan.value.requests;
    const id = vendor.value.vendor;
    saving.value = true;
    saveError.value = '';
    let done = 0;
    try {
      for (const request of requests) {
        await sourceTiersApi.upsertMembers(id, request);
        done += 1;
      }
      ElMessage.success(unpinning.value ? t('已取消指定') : t('已设置号池'));
      emit('saved');
      visibleValue.value = false;
    } catch (err) {
      if (done > 0) emit('saved');
      if (err instanceof ApiError && err.code === SOURCE_TIER_VERSION_CONFLICT) {
        saveError.value = t(
          '这个厂商的号池配置刚被其他人修改，已重新读取最新配置。请确认下面的结果后再试一次。',
        );
      } else {
        saveError.value = err instanceof ApiError ? err.message : t('服务暂时不可用');
      }
      if (done > 0) {
        saveError.value =
          t('已完成 {p0} / {p1} 批。', { p0: done, p1: requests.length }) + saveError.value;
      }
      // 配置可能已经变了：重新读取，下面的汇总和下一次保存都按最新版本算
      await load();
    } finally {
      saving.value = false;
    }
  }
</script>

<template>
  <el-dialog
    v-model="visibleValue"
    :title="t('设为号池')"
    width="min(520px, calc(100vw - 24px))"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <el-skeleton v-if="state === 'loading' && !vendors.length" :rows="3" animated />

    <el-alert
      v-else-if="state === 'error'"
      type="error"
      show-icon
      :closable="false"
      :title="t('号源分级配置加载失败')"
      :description="loadError"
    />

    <el-empty
      v-else-if="!vendors.length"
      :image-size="64"
      :description="t('还没有配置号池。请先到「系统设置 → 号源分级」创建号池。')"
    />

    <template v-else>
      <p class="set-tier__selected">
        {{ t('已选 {p0} 个账号', { p0: accounts.length }) }}
      </p>

      <el-alert
        v-if="saveError"
        class="set-tier__alert"
        type="warning"
        show-icon
        :closable="false"
        :title="saveError"
      />

      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('厂商')">
          <el-select v-model="vendorId" :disabled="saving" :aria-label="t('厂商')">
            <el-option v-for="item in vendors" :key="item.vendor" :label="item.label" :value="item.vendor" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('号池')">
          <el-select v-model="target" :disabled="saving" :aria-label="t('号池')">
            <el-option
              v-for="tier in tiers"
              :key="tier.tierId"
              :label="tierLabel(tier.level, tier.name) + (tier.enabled ? '' : t('（已关闭）'))"
              :value="tier.tierId"
            />
            <el-option :label="t('取消指定（按类别自动归级）')" :value="UNPIN" />
          </el-select>
        </el-form-item>
      </el-form>

      <p v-if="vendor && !vendor.enabled" class="set-tier__note">
        {{ t('这个厂商还没有启用分级，设置会先保存，启用后才按号池服务。') }}
      </p>

      <ul v-if="plan" class="set-tier__summary">
        <li v-if="plan.pin.length">
          {{ t('{p0} 个账号会被指定到这个号池', { p0: plan.pin.length }) }}
        </li>
        <li v-if="plan.unpin.length">
          {{ t('{p0} 个账号会取消指定，回到按类别归级', { p0: plan.unpin.length }) }}
        </li>
        <li v-if="plan.unchanged.length" class="is-muted">
          {{
            unpinning
              ? t('{p0} 个账号本来就没有被指定，不用改', { p0: plan.unchanged.length })
              : t('{p0} 个账号已经指定在这个号池，不用改', { p0: plan.unchanged.length })
          }}
        </li>
        <li v-if="plan.skippedRelay.length" class="is-muted">
          {{
            t('{p0} 个账号在这个厂商里登记为跨协议中转，不能在这里改，已跳过', {
              p0: plan.skippedRelay.length,
            })
          }}
        </li>
        <li v-if="!plan.requests.length" class="is-muted">{{ t('没有需要修改的账号') }}</li>
      </ul>
    </template>

    <template #footer>
      <el-button :disabled="saving" @click="visibleValue = false">{{ t('取消') }}</el-button>
      <el-button type="primary" :loading="saving" :disabled="!canSave" @click="save">
        {{ t('确定') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
  .set-tier {
    &__selected {
      margin: 0 0 12px;
      font-size: 13px;
      color: var(--kp-text-secondary);
    }

    &__alert {
      margin-bottom: 12px;
    }

    &__note {
      margin: 0 0 10px;
      font-size: 12px;
      line-height: 1.55;
      color: var(--kp-text-muted);
    }

    &__summary {
      margin: 0;
      padding-left: 18px;
      font-size: 13px;
      line-height: 1.8;

      .is-muted {
        color: var(--kp-text-muted);
      }
    }
  }
</style>
