<script setup lang="ts">
  /**
   * 指定账号（原生覆盖）：把某个账号手动固定到一个号池，优先于「自动收下的类别」。
   * 跨协议中转账号不在这里登记，它们的成员行在本页保存时原样保留（一个账号在一个厂商里只能有一行，
   * 所以它们也不能再被固定）。
   *
   * 固定关系按号池行的 key 引用，号池改标识后仍然有效；后端对账号做最终校验
   * （账号不存在 → 400，账号不属于该厂商的路由 → 保存后在警告里提示）。
   */
  import { computed, ref } from 'vue';
  import { Delete, Plus } from '@element-plus/icons-vue';
  import { t } from '@/i18n';
  import type { SourceTierWarning } from '@/api/sourceTiers';
  import type { Account } from '@/types/api';
  import ProviderBadge from '@/views/accounts/components/ProviderBadge.vue';
  import { shorten } from '@/utils/format';
  import TierSectionHead from './TierSectionHead.vue';
  import { pinChoices, type PinDraft, type TierDraft } from './sourceTiersMeta';

  const props = defineProps<{
    pins: PinDraft[];
    tiers: TierDraft[];
    accounts: Account[];
    /** 不能再被固定的账号：草稿里登记为中转的（已有成员行），以及在别的厂商登记为专用中转的（固定无效） */
    relayAccountIds: string[];
    /** 已保存配置的警告：这里只用 MEMBER_NOT_SERVING_VENDOR 和 PIN_ON_EXCLUSIVE_RELAY 标出无效的固定 */
    warnings: SourceTierWarning[];
    disabled?: boolean;
  }>();
  const emit = defineEmits<{ 'update:pins': [pins: PinDraft[]] }>();

  const accountById = computed(
    () => new Map(props.accounts.map((account) => [account.accountId, account])),
  );
  const notServing = computed(
    () =>
      new Set(
        props.warnings
          .filter((warning) => warning.code === 'MEMBER_NOT_SERVING_VENDOR')
          .flatMap((warning) => warning.accountIds),
      ),
  );
  const exclusiveRelays = computed(
    () =>
      new Set(
        props.warnings
          .filter((warning) => warning.code === 'PIN_ON_EXCLUSIVE_RELAY')
          .flatMap((warning) => warning.accountIds),
      ),
  );

  function accountName(accountId: string) {
    return accountById.value.get(accountId)?.email || accountId;
  }

  function tierLabel(tier: TierDraft, index: number) {
    return `L${index + 1} · ${tier.name.trim() || tier.tierId}`;
  }

  function setTier(accountId: string, tierKey: string) {
    emit(
      'update:pins',
      props.pins.map((pin) => (pin.accountId === accountId ? { ...pin, tierKey } : pin)),
    );
  }

  function remove(accountId: string) {
    emit(
      'update:pins',
      props.pins.filter((pin) => pin.accountId !== accountId),
    );
  }

  const dialogVisible = ref(false);
  const picked = ref<string[]>([]);
  const target = ref('');
  const query = ref('');

  /** 还没有成员行的账号，按输入过滤（邮箱、账号 ID） */
  const choices = computed(() =>
    pinChoices(props.accounts, props.pins, props.relayAccountIds, query.value),
  );

  function setQuery(value: string) {
    query.value = value;
  }

  function openDialog() {
    picked.value = [];
    query.value = '';
    target.value = props.tiers[0]?.key ?? '';
    dialogVisible.value = true;
  }

  function confirmAdd() {
    if (!picked.value.length || !target.value) return;
    emit('update:pins', [
      ...props.pins,
      ...picked.value.map((accountId) => ({ accountId, tierKey: target.value })),
    ]);
    dialogVisible.value = false;
  }
</script>

<template>
  <section class="pins">
    <TierSectionHead
      :title="t('指定账号')"
      :description="
        t('把某个账号固定放进一个号池，优先于「自动收下的类别」。适合同一类别里想区别对待的账号。')
      "
    >
      <el-button :icon="Plus" :disabled="disabled || !tiers.length" @click="openDialog">
        {{ t('添加账号') }}
      </el-button>
    </TierSectionHead>

    <div class="pins__list surface-card" role="table" :aria-label="t('指定账号')">
      <div v-for="pin in pins" :key="pin.accountId" class="pin-row" role="row">
        <span class="pin-account" role="cell">
          <span class="pin-account__text">
            <strong :title="accountName(pin.accountId)">{{ accountName(pin.accountId) }}</strong>
            <small :title="pin.accountId">{{ shorten(pin.accountId, 12, 6) }}</small>
          </span>
          <ProviderBadge
            v-if="accountById.get(pin.accountId)"
            :provider="accountById.get(pin.accountId)?.provider"
            :auth-method="accountById.get(pin.accountId)?.authMethod"
            :color="accountById.get(pin.accountId)?.badgeColor"
          />
          <el-tag v-if="notServing.has(pin.accountId)" size="small" type="warning" effect="plain">
            {{ t('不属于该厂商的路由，固定无效') }}
          </el-tag>
          <el-tag
            v-if="exclusiveRelays.has(pin.accountId)"
            size="small"
            type="warning"
            effect="plain"
          >
            {{ t('是别的厂商的专用中转，固定无效') }}
          </el-tag>
        </span>
        <span class="pin-tier" role="cell">
          <el-select
            :model-value="pin.tierKey"
            :disabled="disabled"
            :aria-label="t('号池')"
            @update:model-value="setTier(pin.accountId, String($event))"
          >
            <el-option
              v-for="(tier, index) in tiers"
              :key="tier.key"
              :value="tier.key"
              :label="tierLabel(tier, index)"
            />
          </el-select>
        </span>
        <span class="pin-actions" role="cell">
          <el-button
            link
            type="danger"
            :icon="Delete"
            :disabled="disabled"
            :aria-label="t('取消固定')"
            @click="remove(pin.accountId)"
          />
        </span>
      </div>
      <p v-if="!pins.length" class="pins__empty">
        {{ t('没有指定账号。账号按所属类别归入号池，没有被收下的落入最低级。') }}
      </p>
    </div>

    <el-dialog
      v-model="dialogVisible"
      append-to-body
      destroy-on-close
      width="min(520px, 94vw)"
      :title="t('添加指定账号')"
    >
      <el-form label-position="top" @submit.prevent="confirmAdd">
        <el-form-item :label="t('账号')">
          <el-select
            v-model="picked"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            class="pins__picker"
            :filter-method="setQuery"
            :placeholder="t('选择账号')"
            :no-data-text="t('没有可添加的账号')"
          >
            <el-option
              v-for="account in choices"
              :key="account.accountId"
              :value="account.accountId"
              :label="account.email || account.accountId"
            >
              <span class="pins__option">
                <span>{{ account.email || account.accountId }}</span>
                <small>{{ shorten(account.accountId, 12, 6) }}</small>
              </span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item :label="t('固定到')">
          <el-select v-model="target" class="pins__picker">
            <el-option
              v-for="(tier, index) in tiers"
              :key="tier.key"
              :value="tier.key"
              :label="tierLabel(tier, index)"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('取消') }}</el-button>
        <el-button type="primary" :disabled="!picked.length || !target" @click="confirmAdd">
          {{ t('添加') }}
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped lang="scss">
  .pins {
    &__list {
      min-width: 0;
      padding: 4px 0;
    }

    &__empty {
      margin: 0;
      padding: 22px 16px;
      text-align: center;
      font-size: 13px;
      color: var(--kp-text-muted);
    }

    &__picker {
      width: 100%;
    }

    &__option {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 12px;

      small {
        color: var(--kp-text-muted);
      }
    }
  }

  .pin-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(160px, 240px) 40px;
    align-items: center;
    column-gap: 12px;
    padding: 10px 16px;
    border-top: 1px solid var(--kp-divider);

    &:first-child {
      border-top: 0;
    }
  }

  .pin-account {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px 10px;
    min-width: 0;

    &__text {
      display: flex;
      flex-direction: column;
      min-width: 0;

      strong {
        overflow: hidden;
        font-size: 13.5px;
        text-overflow: ellipsis;
        white-space: nowrap;
        color: var(--kp-text);
      }

      small {
        font-size: 11.5px;
        color: var(--kp-text-muted);
      }
    }
  }

  .pin-tier :deep(.el-select) {
    width: 100%;
  }

  .pin-actions {
    display: flex;
    justify-content: flex-end;
  }

  @media (max-width: 640px) {
    .pin-row {
      grid-template-columns: minmax(0, 1fr) 40px;
      row-gap: 8px;
    }

    .pin-tier {
      grid-row: 2;
      grid-column: 1 / -1;
    }
  }
</style>
