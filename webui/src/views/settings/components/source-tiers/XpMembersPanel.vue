<script setup lang="ts">
  /**
   * 跨协议成员（中转）：登记在这个厂商号池里的 OpenAI 兼容 Key，替这个厂商的模型服务。每一行是一个中转：它在哪个号池、
   * 映射了几个模型（几个已验证）、此刻为什么能或不能服务（层），和最近的指标；还有本节点为什么没有给请求用中转。
   *
   * 中转的增改和移除各自单独保存（POST …/members，带读取时的配置版本），不经页面的「保存」，所以页面上有未保存的修改时
   * 不能改它们：保存的结果是整个厂商的最新配置，会替换掉草稿。
   */
  import { computed, ref } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { Delete, Edit, Plus } from '@element-plus/icons-vue';
  import { t } from '@/i18n';
  import { ApiError } from '@/utils/http';
  import { formatDateTime, formatNumber, shorten } from '@/utils/format';
  import {
    SOURCE_TIER_VERSION_CONFLICT,
    sourceTiersApi,
    type SourceTierMember,
    type SourceTierVendor,
    type SourceTierXpRelay,
    type SourceTierXpStatus,
  } from '@/api/sourceTiers';
  import type { Account } from '@/types/api';
  import ProviderBadge from '@/views/accounts/components/ProviderBadge.vue';
  import TierSectionHead from './TierSectionHead.vue';
  import { formatPercent, layerHint, layerLabel } from './tierStatusMeta';
  import {
    estimatedPercent,
    figureTone,
    formatLatency,
    relayErrorMessage,
    skipReasonHint,
    skipReasonLabel,
    successPercent,
    unbilledPercent,
    withheldPercent,
  } from './xpMeta';

  const props = defineProps<{
    /** 已保存的厂商配置 */
    vendor: SourceTierVendor;
    accounts: Account[];
    /** 这个厂商的中转状态；还没有读到、或没有中转时为 null */
    xp: SourceTierXpStatus | null;
    windowMinutes: number;
    /** 不能增改移除中转的原因（页面有未保存的修改）；可以时为空 */
    blocked: string;
    /** 这个版本是否接受跨协议成员行 */
    supported: boolean;
  }>();
  const emit = defineEmits<{
    add: [];
    edit: [accountId: string];
    /** 一个中转已移除：上层用返回的配置替换已保存的配置 */
    saved: [vendor: SourceTierVendor];
    /** 移除被 409 版本冲突拒绝：上层重新读取最新配置 */
    conflict: [];
  }>();

  const removing = ref('');

  const relays = computed<SourceTierMember[]>(() =>
    props.vendor.members.filter((member) => member.protocol),
  );
  const accountById = computed(() => new Map(props.accounts.map((a) => [a.accountId, a])));
  const tierOf = computed(() => new Map(props.vendor.tiers.map((tier) => [tier.tierId, tier])));
  const hasTiers = computed(() => props.vendor.tiers.length > 0);

  function statusOf(accountId: string): SourceTierXpRelay | null {
    return props.xp?.relays.find((relay) => relay.accountId === accountId) ?? null;
  }

  function accountName(accountId: string) {
    return accountById.value.get(accountId)?.email || accountId;
  }

  function tierLabel(tierId: string) {
    const tier = tierOf.value.get(tierId);
    return tier ? `L${tier.level} · ${tier.name}` : tierId;
  }

  /** 已启用的映射数和其中已验证的（取自保存的配置，状态没读到时也能显示） */
  function mappingCounts(member: SourceTierMember) {
    const enabled = (member.models ?? []).filter((model) => model.enabled !== false);
    return { enabled: enabled.length, verified: enabled.filter((model) => model.verified).length };
  }

  const rows = computed(() =>
    relays.value.map((member) => {
      const status = statusOf(member.accountId);
      const counts = mappingCounts(member);
      return {
        member,
        account: accountById.value.get(member.accountId),
        status,
        counts,
        allVerified: counts.enabled > 0 && counts.verified === counts.enabled,
        figures: status && status.figures.attempts > 0 ? status.figures : null,
      };
    }),
  );

  const skips = computed(() => props.xp?.skips ?? []);

  async function remove(member: SourceTierMember) {
    if (removing.value || props.blocked) return;
    const confirmed = await ElMessageBox.confirm(
      t(
        '移除中转「{p0}」？它的映射和验证都会删除。账号本身不受影响；如果它没有在别的厂商登记为专用中转，会重新服务 GLM 等兼容模型请求。',
        {
          p0: accountName(member.accountId),
        },
      ),
      t('移除中转'),
      { type: 'warning', confirmButtonText: t('移除'), cancelButtonText: t('取消') },
    )
      .then(() => true)
      .catch(() => false);
    if (!confirmed) return;
    removing.value = member.accountId;
    try {
      const saved = await sourceTiersApi.upsertMembers(props.vendor.vendor, {
        expectedVersion: props.vendor.version,
        removeAccountIds: [member.accountId],
      });
      emit('saved', saved);
      ElMessage.success(t('已移除'));
    } catch (err) {
      if (err instanceof ApiError && err.code === SOURCE_TIER_VERSION_CONFLICT) {
        emit('conflict');
        ElMessage.warning(
          t('这个厂商的号池配置刚被其他人修改，已重新读取最新配置，请确认后再试一次。'),
        );
      } else {
        ElMessage.error(err instanceof ApiError ? relayErrorMessage(err) : t('服务暂时不可用'));
      }
    } finally {
      removing.value = '';
    }
  }
</script>

<template>
  <section class="relays">
    <TierSectionHead
      :title="t('跨协议成员（中转）')"
      :description="
        t(
          '把一把 OpenAI 兼容 Key 登记到号池里，让它替这个厂商的模型（比如 Claude）服务：网关把请求转成 Chat Completions 发给它，再把应答转回客户端的格式。映射验证过、门控打开之后，请求才会派给它。',
        )
      "
    >
      <el-tooltip
        :disabled="!blocked && hasTiers"
        :content="blocked || t('先保存至少一个号池')"
        placement="top"
      >
        <span>
          <el-button
            :icon="Plus"
            type="primary"
            :disabled="!supported || !!blocked || !hasTiers"
            @click="emit('add')"
          >
            {{ t('登记中转') }}
          </el-button>
        </span>
      </el-tooltip>
    </TierSectionHead>

    <el-alert
      v-if="!supported"
      class="relays__alert"
      type="info"
      show-icon
      :closable="false"
      :title="t('当前版本尚不支持跨协议成员。')"
    />
    <el-alert
      v-else-if="blocked"
      class="relays__alert"
      type="warning"
      show-icon
      :closable="false"
      :title="blocked"
    />

    <div class="relays__list surface-card" role="table" :aria-label="t('跨协议成员（中转）')">
      <article v-for="row in rows" :key="row.member.accountId" class="relay" role="row">
        <div class="relay__who" role="cell">
          <span class="relay__name">
            <strong :title="accountName(row.member.accountId)">{{
              accountName(row.member.accountId)
            }}</strong>
            <small :title="row.member.accountId">{{ shorten(row.member.accountId, 12, 6) }}</small>
          </span>
          <ProviderBadge
            v-if="row.account"
            :provider="row.account.provider"
            :auth-method="row.account.authMethod"
            :color="row.account.badgeColor"
          />
          <el-tag size="small" effect="plain">{{ tierLabel(row.member.tierId) }}</el-tag>
          <el-tag v-if="row.member.exclusive === false" size="small" effect="plain" type="warning">
            {{ t('非专用') }}
          </el-tag>
        </div>

        <div class="relay__state" role="cell">
          <el-tooltip v-if="row.status" :content="layerHint(row.status.layer)" placement="top">
            <el-tag
              size="small"
              effect="light"
              tabindex="0"
              :type="row.status.layer === 'ready' ? 'success' : 'info'"
            >
              {{ layerLabel(row.status.layer) }}
            </el-tag>
          </el-tooltip>
          <el-tag size="small" effect="plain" :type="row.allVerified ? 'success' : 'warning'">
            {{ t('映射 {p0}，已验证 {p1}', { p0: row.counts.enabled, p1: row.counts.verified }) }}
          </el-tag>
        </div>

        <div class="relay__figures" role="cell">
          <template v-if="row.figures">
            <span>
              {{
                t('{p0} 分钟内 {p1} 次尝试', {
                  p0: windowMinutes,
                  p1: formatNumber(row.figures.attempts),
                })
              }}
            </span>
            <el-tag
              :type="figureTone('success', successPercent(row.figures))"
              size="small"
              effect="plain"
            >
              {{ t('成功 {p0}', { p0: formatPercent(successPercent(row.figures)) }) }}
            </el-tag>
            <el-tag
              :type="figureTone('estimated', estimatedPercent(row.figures))"
              size="small"
              effect="plain"
            >
              {{ t('估算 {p0}', { p0: formatPercent(estimatedPercent(row.figures)) }) }}
            </el-tag>
            <el-tag
              :type="figureTone('unbilled', unbilledPercent(row.figures))"
              size="small"
              effect="plain"
            >
              {{ t('中断 {p0}', { p0: formatNumber(row.figures.interrupted) }) }}
            </el-tag>
            <el-tooltip
              :content="
                t(
                  '网关把中转的应答扣下（内容过滤，或在网关自己的输出上限处丢了调用）：没人为它付费，由你承担；不是中转的故障',
                )
              "
              placement="top"
            >
              <el-tag
                :type="figureTone('withheld', withheldPercent(row.figures))"
                size="small"
                effect="plain"
                tabindex="0"
              >
                {{ t('扣下 {p0}', { p0: formatNumber(row.figures.withheld) }) }}
              </el-tag>
            </el-tooltip>
            <span>p95 {{ formatLatency(row.figures.p95Ms) }}</span>
          </template>
          <span v-else class="relay__none">
            {{ t('最近 {p0} 分钟没有发给它的尝试', { p0: windowMinutes }) }}
          </span>
        </div>

        <div class="relay__actions" role="cell">
          <el-button
            link
            type="primary"
            :icon="Edit"
            :disabled="!supported || !!blocked"
            @click="emit('edit', row.member.accountId)"
          >
            {{ t('编辑与验证') }}
          </el-button>
          <el-button
            link
            type="danger"
            :icon="Delete"
            :loading="removing === row.member.accountId"
            :disabled="!supported || !!blocked || !!removing"
            :aria-label="t('移除中转')"
            @click="remove(row.member)"
          />
        </div>
      </article>

      <p v-if="!rows.length" class="relays__empty">
        {{
          t(
            '这个厂商还没有登记中转。登记一把 OpenAI 兼容 Key，写好映射并验证，再在下面的「门控」里对你自己的 Key 打开入口，就可以试用了。',
          )
        }}
      </p>
    </div>

    <div v-if="skips.length && xp" class="relays__skips">
      <p>
        {{
          t('本节点自 {p0} 起，没有把请求交给中转的原因（数的是被跳过的中转次数，不是请求数）：', {
            p0: formatDateTime(xp.skipsSince),
          })
        }}
      </p>
      <ul>
        <li v-for="skip in skips" :key="`${skip.tierId}/${skip.reason}`">
          <el-tooltip :content="skipReasonHint(skip.reason)" placement="top">
            <el-tag size="small" effect="plain" tabindex="0">
              {{ tierOf.get(skip.tierId)?.name ?? skip.tierId }} ·
              {{ skipReasonLabel(skip.reason) }} ×
              {{ formatNumber(skip.count) }}
            </el-tag>
          </el-tooltip>
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped lang="scss">
  .relays {
    min-width: 0;

    &__alert {
      margin-bottom: 10px;
    }

    &__list {
      min-width: 0;
      padding: 4px 0;
    }

    &__empty {
      margin: 0;
      padding: 22px 16px;
      text-align: center;
      font-size: 13px;
      line-height: 1.6;
      color: var(--kp-text-muted);
    }

    &__skips {
      margin-top: 10px;

      p {
        margin: 0 0 6px;
        font-size: 12px;
        color: var(--kp-text-muted);
      }

      ul {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        margin: 0;
        padding: 0;
        list-style: none;
      }
    }
  }

  .relay {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px 16px;
    padding: 12px 16px;
    border-top: 1px solid var(--kp-divider);

    &:first-child {
      border-top: 0;
    }

    &__who {
      display: flex;
      flex: 1 1 240px;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 10px;
      min-width: 0;
    }

    &__name {
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

    &__state,
    &__figures {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 8px;
      min-width: 0;
      font-size: 12px;
      color: var(--kp-text-muted);
      font-variant-numeric: tabular-nums;
    }

    &__state {
      flex: 0 1 auto;
    }

    &__figures {
      flex: 1 1 260px;
    }

    &__actions {
      display: flex;
      align-items: center;
      margin-left: auto;
    }
  }
</style>
