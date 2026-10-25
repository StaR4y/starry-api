<script setup lang="ts">
  /**
   * 号源分级：把一个厂商的账号分成有序的号池（级别，L1 最好），每个号池有名称和价格系数。
   * 管理员在这里配置厂商开关、号池列表、手动指定账号，也可以先套用预设再改。
   *
   * 每个厂商有自己的草稿和版本。保存是整体替换：请求带上草稿所基于的版本，期间被别人改过
   * 就返回 409，页面提示重新加载，不会覆盖别人的修改。本页不编辑的部分（跨协议成员行、门控）
   * 放在草稿里原样带回。后端报告当前版本能运行什么（能力位），不能运行的开关在这里禁用并说明。
   *
   * 页面下方的「运行状态」每 15 秒读一次（页面不可见时暂停）：它不属于草稿，读取失败只影响那一块。
   */
  import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
  import { onBeforeRouteLeave } from 'vue-router';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { ArrowDown, MagicStick, Plus, Refresh, Sort } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { t } from '@/i18n';
  import { ApiError } from '@/utils/http';
  import { accountsApi } from '@/api/accounts';
  import {
    SOURCE_TIER_RUNTIME_UNSUPPORTED,
    SOURCE_TIER_VERSION_CONFLICT,
    sourceTiersApi,
    type SourceTierRuntime,
    type SourceTierStatus,
    type SourceTierVendor,
    type SourceTierWarning,
  } from '@/api/sourceTiers';
  import type { Account } from '@/types/api';
  import NativePinsPanel from './components/source-tiers/NativePinsPanel.vue';
  import TierSectionHead from './components/source-tiers/TierSectionHead.vue';
  import TierSettingRow from './components/source-tiers/TierSettingRow.vue';
  import TierStatusPanel from './components/source-tiers/TierStatusPanel.vue';
  import TierTable from './components/source-tiers/TierTable.vue';
  import {
    DEFAULT_REFUSAL_CAP,
    MAX_REFUSAL_CAP,
    MAX_TIERS,
    MIN_REFUSAL_CAP,
    draftOf,
    draftSignature,
    enableNote,
    exclusiveRelayIds,
    newTier,
    placedCounts,
    replaceRequest,
    tierPresets,
    unifyNote,
    unsupportedMessage,
    validateDraft,
    type PinDraft,
    type TierDraft,
    type TierPreset,
    type VendorDraft,
  } from './components/source-tiers/sourceTiersMeta';

  /** 一个厂商：后端最近一次给的配置、正在编辑的草稿、以及草稿开始时的签名（用来判断有没有改动） */
  interface VendorState {
    loaded: SourceTierVendor;
    draft: VendorDraft;
    baseline: string;
  }

  const state = ref<'loading' | 'ready' | 'error'>('loading');
  const loadError = ref('');
  const refreshing = ref(false);
  const runtime = ref<SourceTierRuntime | null>(null);
  const order = ref<string[]>([]);
  const vendors = reactive<Record<string, VendorState>>({});
  const accounts = ref<Account[]>([]);
  const active = ref('');

  const saving = ref(false);
  const saveError = ref('');
  /** 保存被 409 版本冲突拒绝：需要重新加载这个厂商 */
  const conflict = ref(false);

  const current = computed(() => vendors[active.value] ?? null);
  // 保存的错误属于刚才保存的那个厂商，切换厂商后不再显示
  watch(active, () => {
    saveError.value = '';
    conflict.value = false;
  });
  const nativeTiers = computed(() => !!runtime.value?.nativeTiers);
  /** 「启用」下方的提示：能启用时提醒先升级所有节点，不能启用时说明原因 */
  const enableNoteText = computed(() =>
    current.value
      ? (enableNote(nativeTiers.value, current.value.loaded.enabled, current.value.draft.enabled) ??
        undefined)
      : undefined,
  );
  const selectors = computed(() => !!runtime.value?.selectors);

  /* ---------- 运行状态 ---------- */

  /** 状态每隔这么久读一次 */
  const STATUS_INTERVAL_MS = 15_000;
  const status = ref<SourceTierStatus | null>(null);
  const statusState = ref<'loading' | 'ready' | 'error'>('loading');
  const statusError = ref('');
  const statusRefreshing = ref(false);
  let statusTimer: ReturnType<typeof setInterval> | undefined;
  let statusLoading = false;

  const currentStatus = computed(
    () => status.value?.vendors.find((vendor) => vendor.vendor === active.value) ?? null,
  );

  async function loadStatus(manual = false) {
    if (statusLoading) return;
    statusLoading = true;
    if (manual) statusRefreshing.value = true;
    try {
      status.value = await sourceTiersApi.status();
      statusState.value = 'ready';
      statusError.value = '';
    } catch (err) {
      statusState.value = 'error';
      statusError.value = err instanceof ApiError ? err.message : t('服务暂时不可用');
    } finally {
      statusLoading = false;
      statusRefreshing.value = false;
    }
  }

  /** 页面看不见时不读，回到页面时马上读一次 */
  function refreshStatusIfVisible() {
    if (!document.hidden && state.value === 'ready') void loadStatus();
  }
  const unifyEndpoints = computed(() => !!runtime.value?.unifyEndpoints);
  /** 「全端点统一」下方的提示：不能运行时说明原因，没有启用分级时说明不起作用，还没升级所有节点时提醒先升级 */
  const unifyNoteText = computed(() =>
    current.value
      ? (unifyNote(
          unifyEndpoints.value,
          current.value.loaded.unifyEndpoints,
          current.value.draft.unifyEndpoints,
          current.value.draft.enabled,
        ) ?? undefined)
      : undefined,
  );

  function adopt(vendor: SourceTierVendor) {
    const draft = draftOf(vendor);
    vendors[vendor.vendor] = { loaded: vendor, draft, baseline: draftSignature(draft) };
  }

  function isDirty(vendor: string) {
    const entry = vendors[vendor];
    return !!entry && draftSignature(entry.draft) !== entry.baseline;
  }

  const currentDirty = computed(() => isDirty(active.value));
  const anyDirty = computed(() => order.value.some(isDirty));

  async function load(initial: boolean) {
    if (initial) state.value = 'loading';
    else refreshing.value = true;
    loadError.value = '';
    try {
      const [overview, list] = await Promise.all([
        sourceTiersApi.overview(),
        // 账号只用来给指定账号显示名称，取不到时显示账号 ID
        accountsApi.list().catch(() => [] as Account[]),
      ]);
      runtime.value = overview.runtime;
      accounts.value = list;
      for (const key of Object.keys(vendors)) delete vendors[key];
      overview.vendors.forEach(adopt);
      order.value = overview.vendors.map((vendor) => vendor.vendor);
      if (!order.value.includes(active.value)) active.value = order.value[0] ?? '';
      saveError.value = '';
      conflict.value = false;
      state.value = 'ready';
      void loadStatus();
    } catch (err) {
      if (initial || state.value !== 'ready') {
        loadError.value = err instanceof ApiError ? err.message : t('服务暂时不可用');
        state.value = 'error';
      }
    } finally {
      refreshing.value = false;
    }
  }

  async function confirmDiscard(message: string, title: string) {
    return ElMessageBox.confirm(message, title, {
      type: 'warning',
      confirmButtonText: t('放弃修改'),
      cancelButtonText: t('继续编辑'),
    })
      .then(() => true)
      .catch(() => false);
  }

  async function reload() {
    if (
      anyDirty.value &&
      !(await confirmDiscard(t('重新加载会丢弃所有厂商尚未保存的修改。'), t('重新加载号源分级？')))
    ) {
      return;
    }
    await load(false);
  }

  /** 把当前厂商恢复为已保存的配置 */
  function discard() {
    const entry = current.value;
    if (entry) adopt(entry.loaded);
    saveError.value = '';
    conflict.value = false;
  }

  /** 版本冲突后只重新读取冲突的这个厂商，其他厂商的草稿和基准版本不动 */
  async function reloadVendor() {
    const id = active.value;
    try {
      const overview = await sourceTiersApi.overview();
      runtime.value = overview.runtime;
      const fresh = overview.vendors.find((vendor) => vendor.vendor === id);
      if (fresh) adopt(fresh);
      saveError.value = '';
      conflict.value = false;
    } catch (err) {
      saveError.value = err instanceof ApiError ? err.message : t('服务暂时不可用');
    }
  }

  function describeSaveError(err: unknown): string {
    if (!(err instanceof ApiError)) return t('服务暂时不可用');
    if (err.code === SOURCE_TIER_VERSION_CONFLICT) {
      conflict.value = true;
      const data = err.data as { currentVersion?: number } | undefined;
      return typeof data?.currentVersion === 'number'
        ? t(
            '这个厂商的号池配置已被其他人修改（现在是第 {p0} 版）。重新加载最新配置后再改一次；你未保存的修改会丢失。',
            { p0: data.currentVersion },
          )
        : t('这个厂商的号池配置已被其他人修改。重新加载最新配置后再改一次；你未保存的修改会丢失。');
    }
    if (err.code === SOURCE_TIER_RUNTIME_UNSUPPORTED) {
      return unsupportedMessage(err.data) ?? err.message;
    }
    return err.message;
  }

  async function save() {
    const entry = current.value;
    if (!entry || saving.value) return;
    const problem = validateDraft(entry.draft);
    if (problem) {
      saveError.value = problem;
      conflict.value = false;
      return;
    }
    saving.value = true;
    saveError.value = '';
    conflict.value = false;
    try {
      const saved = await sourceTiersApi.replace(
        entry.loaded.vendor,
        replaceRequest(entry.loaded.version, entry.draft),
      );
      adopt(saved);
      ElMessage.success(t('已保存'));
      void loadStatus();
    } catch (err) {
      saveError.value = describeSaveError(err);
    } finally {
      saving.value = false;
    }
  }

  /* ---------- 草稿编辑 ---------- */

  function setDraft<K extends keyof VendorDraft>(key: K, value: VendorDraft[K]) {
    const entry = current.value;
    if (entry) entry.draft[key] = value;
  }

  function setRefusalCap(value: number | undefined | null) {
    setDraft('refusalCap', typeof value === 'number' ? value : DEFAULT_REFUSAL_CAP);
  }

  function addTier() {
    const entry = current.value;
    if (!entry) return;
    if (entry.draft.tiers.length >= MAX_TIERS) {
      ElMessage.warning(t('一个厂商最多 {p0} 个号池', { p0: MAX_TIERS }));
      return;
    }
    entry.draft.tiers = [...entry.draft.tiers, newTier(entry.draft.tiers)];
  }

  async function removeTier(tier: TierDraft) {
    const entry = current.value;
    if (!entry) return;
    const pinned = entry.draft.pins.filter((pin) => pin.tierKey === tier.key).length;
    if (pinned > 0) {
      const confirmed = await ElMessageBox.confirm(
        t('删除号池「{p0}」？固定在它上面的 {p1} 个账号会一并从草稿中移除，保存前不会生效。', {
          p0: tier.name.trim() || tier.tierId,
          p1: pinned,
        }),
        t('删除号池'),
        { type: 'warning', confirmButtonText: t('删除'), cancelButtonText: t('取消') },
      )
        .then(() => true)
        .catch(() => false);
      if (!confirmed) return;
    }
    entry.draft.tiers = entry.draft.tiers.filter((candidate) => candidate.key !== tier.key);
    entry.draft.pins = entry.draft.pins.filter((pin) => pin.tierKey !== tier.key);
  }

  const presets = computed(() => tierPresets(active.value));

  /** 预设只改草稿，保存前不会生效 */
  async function applyPreset(id: string | number | object) {
    const entry = current.value;
    const preset: TierPreset | undefined = presets.value.find((candidate) => candidate.id === id);
    if (!entry || !preset) return;
    if (entry.draft.tiers.length || entry.draft.pins.length) {
      const confirmed = await ElMessageBox.confirm(
        t('套用预设会替换草稿里的所有号池，并移除固定在它们上面的账号。保存前不会生效。'),
        t('套用预设'),
        { type: 'warning', confirmButtonText: t('套用'), cancelButtonText: t('取消') },
      )
        .then(() => true)
        .catch(() => false);
      if (!confirmed) return;
    }
    entry.draft.tiers = preset.build();
    entry.draft.pins = [];
  }

  function setTiers(tiers: TierDraft[]) {
    setDraft('tiers', tiers);
  }

  function setPins(pins: PinDraft[]) {
    setDraft('pins', pins);
  }

  const pinCounts = computed(() => {
    const counts: Record<string, number> = {};
    for (const pin of current.value?.draft.pins ?? []) {
      counts[pin.tierKey] = (counts[pin.tierKey] ?? 0) + 1;
    }
    return counts;
  });

  const placed = computed(() =>
    current.value ? placedCounts(current.value.loaded, current.value.draft) : {},
  );
  /**
   * 不能再被固定的账号：这个厂商草稿里登记为中转的（它们已有成员行），以及在别的厂商登记为专用中转的
   * （按已保存的配置；它们不是任何厂商的原生成员，固定了也不会生效）
   */
  const relayAccountIds = computed(() => [
    ...(current.value?.draft.relayMembers.map((member) => member.accountId) ?? []),
    ...exclusiveRelayIds(
      Object.entries(vendors)
        .filter(([vendorId]) => vendorId !== active.value)
        .map(([, entry]) => entry.loaded),
    ),
  ]);

  /* ---------- 警告与说明 ---------- */

  function tierName(entry: VendorState, tierId: string | null | undefined) {
    return entry.loaded.tiers.find((tier) => tier.tierId === tierId)?.name ?? tierId ?? '';
  }

  /** 已保存配置的警告；按 code 措辞，不认识的 code 显示后端的原文 */
  const warnings = computed(() => {
    const entry = current.value;
    if (!entry) return [];
    return entry.loaded.warnings.map((warning: SourceTierWarning) => {
      const name = tierName(entry, warning.tierId);
      if (warning.code === 'LOWEST_TIER_COLLECTS_OFFICIAL_KEYS') {
        return {
          key: `${warning.code}:${warning.tierId}`,
          title: t(
            '{p0} 个官方 API Key 没有被任何号池收下，会落入最低级「{p1}」并按它的系数计费。',
            { p0: warning.count, p1: name },
          ),
          detail:
            t('把「官方 API Key」类别放进应该拥有它们的号池，可以消除这个提示。账号：') +
            warning.accountIds.join('、') +
            (warning.count > warning.accountIds.length ? '…' : ''),
        };
      }
      if (warning.code === 'MEMBER_NOT_SERVING_VENDOR') {
        return {
          key: `${warning.code}:${warning.accountIds.join(',')}`,
          title: t('账号 {p0} 不是任何访问本厂商模型的路由会用到的账号，固定到「{p1}」不会生效。', {
            p0: warning.accountIds.join('、'),
            p1: name,
          }),
          detail: '',
        };
      }
      if (warning.code === 'PIN_ON_EXCLUSIVE_RELAY') {
        return {
          key: `${warning.code}:${warning.accountIds.join(',')}`,
          title: t(
            '账号 {p0} 在别的厂商登记为专用中转，不是任何号池的原生成员，固定到「{p1}」不会生效。',
            { p0: warning.accountIds.join('、'), p1: name },
          ),
          detail: '',
        };
      }
      return { key: `${warning.code}:${warning.tierId}`, title: warning.message, detail: '' };
    });
  });

  const hiddenMembers = computed(() => {
    const entry = current.value;
    return {
      relays: entry?.draft.relayMembers.length ?? 0,
      gates: entry?.draft.gates.length ?? 0,
    };
  });

  function vendorStatus(vendor: string): string {
    const loaded = vendors[vendor]?.loaded;
    if (loaded?.enabled) return t('已启用');
    return loaded?.configured ? t('已配置') : '';
  }

  /* ---------- 离开页面 ---------- */

  onBeforeRouteLeave(async () => {
    if (!anyDirty.value) return true;
    return ElMessageBox.confirm(
      t('号源分级有尚未保存的修改，离开后将丢失。'),
      t('离开号源分级？'),
      {
        type: 'warning',
        distinguishCancelAndClose: true,
        confirmButtonText: t('离开'),
        cancelButtonText: t('继续编辑'),
      },
    )
      .then(() => true)
      .catch(() => false);
  });

  function beforeUnload(event: BeforeUnloadEvent) {
    if (!anyDirty.value) return;
    event.preventDefault();
    event.returnValue = '';
  }

  onMounted(() => {
    window.addEventListener('beforeunload', beforeUnload);
    document.addEventListener('visibilitychange', refreshStatusIfVisible);
    statusTimer = setInterval(refreshStatusIfVisible, STATUS_INTERVAL_MS);
    void load(true);
  });
  onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', beforeUnload);
    document.removeEventListener('visibilitychange', refreshStatusIfVisible);
    clearInterval(statusTimer);
  });
</script>

<template>
  <PageSection :title="t('号源分级')" :subtitle="t('运行与安全')" :icon="Sort">
    <template v-if="state === 'ready'" #actions>
      <el-button :icon="Refresh" :loading="refreshing" :disabled="saving" @click="reload">
        {{ t('刷新') }}
      </el-button>
      <el-button v-if="currentDirty" :disabled="saving" @click="discard">
        {{ t('放弃修改') }}
      </el-button>
      <el-button type="primary" :loading="saving" :disabled="!currentDirty" @click="save">
        {{ t('保存') }}
      </el-button>
    </template>

    <p class="tiers-intro">
      {{
        t(
          '把一个厂商的账号分成有序的号池：新会话从 1 级开始，一个请求里 1 级的账号全部不可用（包括繁忙）才会用到 2 级，每个号池有自己的价格系数。没有配置的厂商，路由、价格和结算保持不变。',
        )
      }}
      {{ t('这里的分级与「账号池设置」里的一级 / 二级号池熔断策略相互独立。') }}
    </p>

    <el-skeleton v-if="state === 'loading'" class="tiers-state" :rows="6" animated />

    <el-result
      v-else-if="state === 'error'"
      class="tiers-state"
      icon="warning"
      :title="t('设置加载失败')"
      :sub-title="loadError"
    >
      <template #extra>
        <el-button type="primary" @click="load(true)">{{ t('重新加载') }}</el-button>
      </template>
    </el-result>

    <template v-else>
      <el-alert
        v-if="!nativeTiers"
        class="tiers-alert"
        type="info"
        show-icon
        :closable="false"
        :title="
          t(
            '当前版本只保存号池配置，还不会按它选号或计费：现有的路由、价格和结算保持不变。保存的配置在后续版本支持时才会生效，那时「启用」才能打开。',
          )
        "
      />

      <el-tabs v-model="active" class="vendor-tabs">
        <el-tab-pane v-for="vendor in order" :key="vendor" :name="vendor">
          <template #label>
            <span class="vendor-tab">
              {{ vendors[vendor].loaded.label }}
              <el-tag
                v-if="vendorStatus(vendor)"
                size="small"
                effect="plain"
                :type="vendors[vendor].loaded.enabled ? 'success' : 'info'"
              >
                {{ vendorStatus(vendor) }}
              </el-tag>
              <i v-if="isDirty(vendor)" class="vendor-tab__dirty" :title="t('有未保存的修改')" />
            </span>
          </template>
        </el-tab-pane>
      </el-tabs>

      <div v-if="current" class="tiers-body">
        <el-alert
          v-if="saveError"
          class="tiers-alert"
          :type="conflict ? 'warning' : 'error'"
          show-icon
          :closable="false"
          :title="saveError"
        >
          <el-button v-if="conflict" size="small" type="primary" @click="reloadVendor">
            {{ t('重新加载最新配置') }}
          </el-button>
        </el-alert>

        <div class="tier-settings">
          <TierSettingRow
            class="surface-card"
            :title="t('启用')"
            :description="
              t('打开后，这个厂商的请求按号池分级选号，并按号池系数计费；关闭时一切照旧。')
            "
            :note="enableNoteText"
          >
            <el-switch
              :model-value="current.draft.enabled"
              :disabled="saving || (!nativeTiers && !current.draft.enabled)"
              :aria-label="t('启用')"
              @update:model-value="setDraft('enabled', Boolean($event))"
            />
          </TierSettingRow>

          <TierSettingRow
            v-if="active === 'anthropic'"
            class="surface-card"
            :title="t('全端点统一')"
            :description="
              t(
                'Chat 和 Responses 入口上的 Claude 模型原本只由 Kiro 服务。打开后，它们和 Messages 入口一样先用官方 API Key，再用 Kiro，号池和系数照常生效；Gemini 入口、auto 等路由预设和其他厂商的模型不变。这会让这些请求先用到官方 Key，成本可能上升，所以默认不勾选。',
              )
            "
            :note="unifyNoteText"
          >
            <el-switch
              :model-value="current.draft.unifyEndpoints"
              :disabled="saving || (!unifyEndpoints && !current.draft.unifyEndpoints)"
              :aria-label="t('全端点统一')"
              @update:model-value="setDraft('unifyEndpoints', Boolean($event))"
            />
          </TierSettingRow>

          <TierSettingRow
            class="surface-card"
            :title="t('公开号池名称')"
            :description="
              t('在 /v1/models 的模型信息里附带各号池的名称和价格（不含上游细节）。默认关闭。')
            "
          >
            <el-switch
              :model-value="current.draft.publicListing"
              :disabled="saving"
              :aria-label="t('公开号池名称')"
              @update:model-value="setDraft('publicListing', Boolean($event))"
            />
          </TierSettingRow>

          <TierSettingRow
            class="surface-card"
            :title="t('会话保持在降级号池')"
            :description="
              t(
                '默认开启：会话降级后一直留在降级后的号池，空闲 10 分钟后回到 1 级。低级比 1 级贵时，已降级的会话在 1 级恢复后仍按低级价计费，直到空闲。会话所在的号池只记在处理它的那个节点上：多节点部署时，负载均衡若不把同一个会话固定到同一个节点，换了节点的请求会从 1 级重新开始。',
              )
            "
          >
            <el-switch
              :model-value="current.draft.stickySessions"
              :disabled="saving"
              :aria-label="t('会话保持在降级号池')"
              @update:model-value="setDraft('stickySessions', Boolean($event))"
            />
          </TierSettingRow>

          <TierSettingRow
            class="surface-card"
            :title="t('每级拒绝上限')"
            :description="
              t(
                '一个请求在某一级被拒绝（并发、限速、冷却）达到这个次数后改用下一级，范围 {p0} 到 {p1}，默认 {p2}。全局的 64 次上限不变。',
                { p0: MIN_REFUSAL_CAP, p1: MAX_REFUSAL_CAP, p2: DEFAULT_REFUSAL_CAP },
              )
            "
          >
            <el-input-number
              :model-value="current.draft.refusalCap"
              :min="MIN_REFUSAL_CAP"
              :max="MAX_REFUSAL_CAP"
              :step="1"
              step-strictly
              :value-on-clear="DEFAULT_REFUSAL_CAP"
              :disabled="saving"
              :aria-label="t('每级拒绝上限')"
              controls-position="right"
              @update:model-value="setRefusalCap"
            />
          </TierSettingRow>

          <TierSettingRow
            class="surface-card"
            :title="t('跨级保留槽')"
            :description="
              t(
                '默认开启：重试预算里留一个只能用于跨级的名额，两个 1 级账号都确定失败时仍能用到 2 级，代价是一个请求最多多派发 1 次。关闭则预算用尽即失败。',
              )
            "
          >
            <el-switch
              :model-value="current.draft.crossTierReservedSlot"
              :disabled="saving"
              :aria-label="t('跨级保留槽')"
              @update:model-value="setDraft('crossTierReservedSlot', Boolean($event))"
            />
          </TierSettingRow>
        </div>

        <el-alert
          v-for="warning in warnings"
          :key="warning.key"
          class="tiers-alert"
          type="warning"
          show-icon
          :closable="false"
          :title="warning.title"
          :description="warning.detail || undefined"
        />
        <p v-if="warnings.length && currentDirty" class="tiers-note">
          {{ t('以上提示按已保存的配置计算，保存后更新。') }}
        </p>

        <section>
          <TierSectionHead
            :title="t('号池')"
            :description="
              t(
                '排在越前面级别越好。一个厂商最多 {p0} 个号池；最低一级会收下没有被类别或指定账号归类的账号。',
                { p0: MAX_TIERS },
              )
            "
          >
            <el-dropdown v-if="presets.length" trigger="click" @command="applyPreset">
              <el-button :icon="MagicStick" :disabled="saving">
                {{ t('套用预设') }}
                <el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-for="preset in presets" :key="preset.id" :command="preset.id">
                    <span class="preset-item">
                      <strong>{{ preset.title }}</strong>
                      <small>{{ preset.description }}</small>
                    </span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button
              :icon="Plus"
              :disabled="saving || current.draft.tiers.length >= MAX_TIERS"
              @click="addTier"
            >
              {{ t('添加号池') }}
            </el-button>
          </TierSectionHead>

          <TierTable
            :tiers="current.draft.tiers"
            :vendor="active"
            :pin-counts="pinCounts"
            :placed-counts="placed"
            :copy-enabled="selectors"
            :disabled="saving"
            @update:tiers="setTiers"
            @remove="removeTier"
          />
        </section>

        <TierStatusPanel
          :vendor="currentStatus"
          :state="statusState"
          :error="statusError"
          :refreshing="statusRefreshing"
          :at="status?.at ?? null"
          :window-minutes="status?.windowMinutes ?? 15"
          :native-tiers="nativeTiers"
          @refresh="loadStatus(true)"
        />

        <NativePinsPanel
          :pins="current.draft.pins"
          :tiers="current.draft.tiers"
          :accounts="accounts"
          :relay-account-ids="relayAccountIds"
          :warnings="current.loaded.warnings"
          :disabled="saving"
          @update:pins="setPins"
        />

        <el-alert
          v-if="hiddenMembers.relays || hiddenMembers.gates"
          class="tiers-alert"
          type="info"
          show-icon
          :closable="false"
          :title="
            t('这个厂商含 {p0} 个跨协议成员和 {p1} 条门控设置，本页不编辑它们，保存时原样保留。', {
              p0: hiddenMembers.relays,
              p1: hiddenMembers.gates,
            })
          "
        />
      </div>
    </template>
  </PageSection>
</template>

<style scoped lang="scss">
  .tiers-intro {
    margin: 0 0 16px;
    font-size: 13px;
    line-height: 1.6;
    color: var(--kp-text-secondary);
  }

  .tiers-state {
    min-height: 320px;
  }

  .tiers-alert {
    margin-bottom: 14px;
  }

  .tiers-note {
    margin: -6px 2px 14px;
    font-size: 12px;
    color: var(--kp-text-muted);
  }

  .vendor-tabs {
    margin-bottom: 14px;

    :deep(.el-tabs__header) {
      margin-bottom: 0;
    }
  }

  .vendor-tab {
    display: inline-flex;
    align-items: center;
    gap: 6px;

    &__dirty {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: var(--el-color-warning);
    }
  }

  .tiers-body {
    display: flex;
    flex-direction: column;
    gap: 22px;
    min-width: 0;

    > :deep(.el-alert) {
      margin-bottom: 0;
    }
  }

  .tier-settings {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
    gap: 12px;
  }

  .preset-item {
    display: flex;
    flex-direction: column;
    gap: 2px;
    max-width: 340px;
    white-space: normal;
    line-height: 1.45;

    small {
      color: var(--kp-text-muted);
    }
  }

  @media (max-width: 640px) {
    .tier-settings {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
