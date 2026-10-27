<script setup lang="ts">
  /**
   * 门控：每个入口（Chat、Messages、Responses、Gemini）是否把请求派给这个厂商的中转，以及派给哪些 Key 的请求。
   * 状态是关闭 / 名单里的 Key / 所有 Key：每个对在代码里有自己的状态（未提供、试用中、已验证），门控只能开到它允许的
   * 程度——试用中的对只能对名单里的 Key 打开，已验证的才能对所有 Key。启用厂商的分级不会打开任何一个门控。
   *
   * 门控在号池配置的草稿里，随页面的「保存」一起写入（与号池同一个事务）；关闭门控是紧急开关：进行中的请求按开始时的
   * 计划完成，之后的请求不再派给中转。每个入口旁是最近的指标和被丢掉的请求特性（降级摘要），取自上层读到的状态。
   */
  import { computed, ref } from 'vue';
  import { Document } from '@element-plus/icons-vue';
  import { t } from '@/i18n';
  import { formatNumber } from '@/utils/format';
  import type {
    SourceTierEntryEndpoint,
    SourceTierGate,
    SourceTierGateState,
    SourceTierRuntime,
    SourceTierXpPair,
    SourceTierXpStatus,
  } from '@/api/sourceTiers';
  import type { ApiKey } from '@/types/api';
  import PairEvidenceDialog from './PairEvidenceDialog.vue';
  import TierSectionHead from './TierSectionHead.vue';
  import { formatPercent } from './tierStatusMeta';
  import {
    ENTRY_ENDPOINTS,
    MAX_GATE_KEYS,
    allowedGateStates,
    conversionRejectedPercent,
    entryInfo,
    estimatedPercent,
    figureTone,
    formatLatency,
    gateKeys,
    gateProblem,
    gateState,
    gateStateLabel,
    pairStatusHint,
    pairStatusLabel,
    pairStatusOf,
    setGate,
    successPercent,
    unbilledPercent,
    withheldPercent,
  } from './xpMeta';

  const props = defineProps<{
    vendor: string;
    gates: SourceTierGate[];
    runtime: SourceTierRuntime | null;
    /** 名单里可选的 API Key；读取失败时为空，名单里已有的 Key ID 仍然显示 */
    keys: ApiKey[];
    /** 这个厂商的中转状态（指标）；没有登记中转时为 null */
    xp: SourceTierXpStatus | null;
    windowMinutes: number;
    disabled?: boolean;
  }>();
  const emit = defineEmits<{ 'update:gates': [gates: SourceTierGate[]] }>();

  const evidenceVisible = ref(false);

  const rows = computed(() =>
    ENTRY_ENDPOINTS.map((entry) => {
      const status = pairStatusOf(props.runtime, props.vendor, entry);
      return {
        entry,
        info: entryInfo(entry),
        status,
        state: gateState(props.gates, entry),
        keys: gateKeys(props.gates, entry),
        allowed: allowedGateStates(status),
        problem: gateProblem(props.gates, entry, status),
        pair: props.xp?.pairs.find((pair) => pair.entryEndpoint === entry) ?? null,
        figures: figuresOf(props.xp?.pairs.find((pair) => pair.entryEndpoint === entry) ?? null),
      };
    }),
  );
  const anyOffered = computed(() => rows.value.some((row) => row.status !== 'NOT_OFFERED'));

  const keyById = computed(() => new Map(props.keys.map((key) => [key.keyId, key])));

  function keyLabel(keyId: string) {
    const key = keyById.value.get(keyId);
    if (!key) return keyId;
    return key.keyPrefix ? `${key.name} · ${key.keyPrefix}…` : key.name;
  }

  const STATES: SourceTierGateState[] = ['OFF', 'KEYS', 'ON'];

  function setState(entry: SourceTierEntryEndpoint, value: string | number | boolean | undefined) {
    const state = STATES.find((candidate) => candidate === value);
    if (state) emit('update:gates', setGate(props.gates, entry, { state }));
  }

  function setKeys(entry: SourceTierEntryEndpoint, allowKeyIds: string[]) {
    emit('update:gates', setGate(props.gates, entry, { allowKeyIds }));
  }

  /** 门控打开时，名单里的 Key 数；没有名单的 ON 不用 */
  function listed(row: { state: SourceTierGateState; keys: string[] }) {
    return row.state === 'KEYS' ? row.keys.length : null;
  }

  /** 该入口最近的指标行：没有尝试时为 null */
  function figuresOf(pair: SourceTierXpPair | null) {
    if (!pair || pair.figures.attempts <= 0) return null;
    const figures = pair.figures;
    return {
      attempts: figures.attempts,
      success: successPercent(figures),
      estimated: estimatedPercent(figures),
      unbilled: unbilledPercent(figures),
      withheld: withheldPercent(figures),
      rejected: conversionRejectedPercent(figures),
      p95: formatLatency(figures.p95Ms),
    };
  }
</script>

<template>
  <section class="gates">
    <TierSectionHead
      :title="t('门控')"
      :description="
        t(
          '每个入口是否把请求派给这个厂商的中转。默认全部关闭，启用分级不会打开任何一个；先对名单里的 Key（比如你自己的）打开试用，数字达标、对升级为「已验证」后才能对所有 Key 打开。关闭是紧急开关，之后的请求不再派给中转。所有节点都升级到支持跨协议的版本之后再打开。',
        )
      "
    >
      <el-button :icon="Document" @click="evidenceVisible = true">{{ t('升级证据') }}</el-button>
    </TierSectionHead>

    <el-alert
      v-if="!anyOffered"
      class="gates__alert"
      type="info"
      show-icon
      :closable="false"
      :title="t('这个版本还没有为这个厂商提供任何（入口，厂商）对，门控暂时都打不开。')"
    />

    <div class="gates__list surface-card" role="table" :aria-label="t('门控')">
      <article
        v-for="row in rows"
        :key="row.entry"
        class="gate"
        role="row"
        :class="{ 'is-off': row.status === 'NOT_OFFERED' }"
      >
        <div class="gate__entry" role="cell">
          <strong>{{ row.info.title }}</strong>
          <el-tooltip :content="pairStatusHint(row.status)" placement="top">
            <el-tag
              size="small"
              effect="plain"
              tabindex="0"
              :type="
                row.status === 'VERIFIED'
                  ? 'success'
                  : row.status === 'EXPERIMENTAL'
                    ? 'warning'
                    : 'info'
              "
            >
              {{ pairStatusLabel(row.status) }}
            </el-tag>
          </el-tooltip>
          <small>{{ row.info.clients }}</small>
        </div>

        <div class="gate__control" role="cell">
          <el-radio-group
            :model-value="row.state"
            size="small"
            :disabled="disabled || row.status === 'NOT_OFFERED'"
            :aria-label="row.info.title"
            @update:model-value="setState(row.entry, $event)"
          >
            <el-radio-button
              v-for="state in STATES"
              :key="state"
              :value="state"
              :label="state"
              :disabled="!row.allowed.includes(state)"
            >
              {{ gateStateLabel(state) }}
            </el-radio-button>
          </el-radio-group>

          <el-select
            v-if="row.state === 'KEYS' || row.keys.length"
            :model-value="row.keys"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            :multiple-limit="MAX_GATE_KEYS"
            :disabled="disabled || row.status === 'NOT_OFFERED'"
            class="gate__keys"
            :placeholder="t('选择名单里的 Key')"
            :no-data-text="t('没有可选的 API Key')"
            :aria-label="t('名单里的 Key')"
            @update:model-value="setKeys(row.entry, $event)"
          >
            <el-option
              v-for="key in keys"
              :key="key.keyId"
              :value="key.keyId"
              :label="keyLabel(key.keyId)"
              :disabled="!key.enabled && !row.keys.includes(key.keyId)"
            >
              <span class="gate__option">
                <span>{{ key.name }}</span>
                <small>{{ key.keyPrefix }}</small>
              </span>
            </el-option>
            <!-- 名单里已有、但已经不在 Key 列表里的 ID（Key 被删除或读取失败）保留显示 -->
            <el-option
              v-for="keyId in row.keys.filter((id) => !keyById.has(id))"
              :key="keyId"
              :value="keyId"
              :label="keyId"
            />
          </el-select>
          <small v-if="listed(row) !== null" class="gate__count">
            {{ t('名单里 {p0} 把 Key（最多 {p1}）', { p0: listed(row), p1: MAX_GATE_KEYS }) }}
          </small>
        </div>

        <div class="gate__figures" role="cell">
          <template v-if="row.figures">
            <span class="gate__metric">
              {{
                t('{p0} 分钟内 {p1} 次尝试', {
                  p0: windowMinutes,
                  p1: formatNumber(row.figures.attempts),
                })
              }}
            </span>
            <el-tag :type="figureTone('success', row.figures.success)" size="small" effect="plain">
              {{ t('成功 {p0}', { p0: formatPercent(row.figures.success) }) }}
            </el-tag>
            <el-tooltip
              :content="t('用量由网关估算的占已服务尝试的比例；超过 5% 标红')"
              placement="top"
            >
              <el-tag
                :type="figureTone('estimated', row.figures.estimated)"
                size="small"
                effect="plain"
                tabindex="0"
              >
                {{ t('估算 {p0}', { p0: formatPercent(row.figures.estimated) }) }}
              </el-tag>
            </el-tooltip>
            <el-tooltip
              :content="t('中转可能已经生成、没人付费的中断占全部尝试的比例，由你承担')"
              placement="top"
            >
              <el-tag
                :type="figureTone('unbilled', row.figures.unbilled)"
                size="small"
                effect="plain"
                tabindex="0"
              >
                {{ t('中断 {p0}', { p0: formatPercent(row.figures.unbilled) }) }}
              </el-tag>
            </el-tooltip>
            <el-tooltip
              :content="
                t(
                  '网关把中转的应答扣下（内容过滤，或在网关自己的输出上限处丢了调用）：没人为它付费，由你承担；不是中转的故障',
                )
              "
              placement="top"
            >
              <el-tag
                :type="figureTone('withheld', row.figures.withheld)"
                size="small"
                effect="plain"
                tabindex="0"
              >
                {{ t('扣下 {p0}', { p0: formatPercent(row.figures.withheld) }) }}
              </el-tag>
            </el-tooltip>
            <el-tooltip :content="t('中转拒绝了转换后的请求的比例')" placement="top">
              <el-tag
                :type="figureTone('conversionRejected', row.figures.rejected)"
                size="small"
                effect="plain"
                tabindex="0"
              >
                {{ t('转换被拒 {p0}', { p0: formatPercent(row.figures.rejected) }) }}
              </el-tag>
            </el-tooltip>
            <span class="gate__metric">p95 {{ row.figures.p95 }}</span>
          </template>
          <span v-else-if="row.status !== 'NOT_OFFERED'" class="gate__metric">
            {{ t('最近 {p0} 分钟没有发给中转的尝试', { p0: windowMinutes }) }}
          </span>
          <p v-if="row.pair?.degradedFeatures.length" class="gate__features">
            {{ t('丢掉的请求特性：') }}
            {{
              row.pair.degradedFeatures.map((item) => `${item.feature} × ${item.count}`).join('，')
            }}
          </p>
        </div>

        <p v-if="row.problem" class="gate__problem">{{ row.problem }}</p>
      </article>
    </div>

    <PairEvidenceDialog v-model:visible="evidenceVisible" />
  </section>
</template>

<style scoped lang="scss">
  .gates {
    min-width: 0;

    &__alert {
      margin-bottom: 10px;
    }

    &__list {
      min-width: 0;
    }
  }

  .gate {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-start;
    gap: 8px 16px;
    padding: 14px 16px;
    border-top: 1px solid var(--kp-divider);

    &:first-child {
      border-top: 0;
    }

    &.is-off {
      opacity: 0.65;
    }

    &__entry {
      display: flex;
      flex: 1 1 200px;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 8px;
      min-width: 0;

      strong {
        font-size: 13.5px;
        color: var(--kp-text);
      }

      small {
        flex-basis: 100%;
        font-size: 11.5px;
        line-height: 1.45;
        color: var(--kp-text-muted);
      }
    }

    &__control {
      display: flex;
      flex: 1 1 260px;
      flex-direction: column;
      gap: 8px;
      min-width: 0;
    }

    &__keys {
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

    &__count {
      font-size: 11.5px;
      color: var(--kp-text-muted);
    }

    &__figures {
      display: flex;
      flex: 1 1 240px;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 8px;
      min-width: 0;
    }

    &__metric {
      font-size: 12px;
      color: var(--kp-text-muted);
      font-variant-numeric: tabular-nums;
    }

    &__features {
      flex-basis: 100%;
      margin: 0;
      font-size: 12px;
      color: var(--kp-text-muted);
    }

    &__problem {
      flex-basis: 100%;
      margin: 0;
      font-size: 12px;
      color: var(--el-color-warning);
    }
  }
</style>
