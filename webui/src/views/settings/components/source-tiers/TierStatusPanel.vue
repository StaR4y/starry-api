<script setup lang="ts">
  /**
   * 运行状态：一个厂商的每个号池此刻有多少账号能服务、被什么挡住，本节点现在会不会从它服务，
   * 以及最近一段时间请求是怎么被服务的（取自请求日志，覆盖所有节点）。
   *
   * 数据由上层定时读取并传入；本组件只负责展示，不改任何配置。
   */
  import { computed } from 'vue';
  import { Refresh } from '@element-plus/icons-vue';
  import { t } from '@/i18n';
  import { formatDateTime, formatNumber } from '@/utils/format';
  import type { SourceTierVendorStatus } from '@/api/sourceTiers';
  import TierSectionHead from './TierSectionHead.vue';
  import {
    LAYERS,
    degradedPercent,
    degradedTone,
    formatPercent,
    layerCount,
    layerHint,
    layerLabel,
    layerSegments,
    layerTotal,
    notServingReason,
  } from './tierStatusMeta';

  const props = defineProps<{
    /** 这个厂商的状态；还没有读到、或这个厂商没有号池时为 null */
    vendor: SourceTierVendorStatus | null;
    state: 'loading' | 'ready' | 'error';
    error: string;
    refreshing: boolean;
    /** 读取状态的时间 */
    at: string | null;
    windowMinutes: number;
    /** 当前版本是否能按号池选号，用来说明为什么一个厂商没有在服务 */
    nativeTiers: boolean;
  }>();
  const emit = defineEmits<{ refresh: [] }>();

  const percent = computed(() => (props.vendor ? degradedPercent(props.vendor) : null));
  const reason = computed(() =>
    props.vendor ? notServingReason(props.vendor, props.nativeTiers) : null,
  );
</script>

<template>
  <section class="tier-status">
    <TierSectionHead
      :title="t('运行状态')"
      :description="
        t(
          '账号数是当前这个节点看到的（账号缓存最多晚 30 秒），最近 {p0} 分钟的服务和失败取自请求日志，覆盖所有节点。每 15 秒自动刷新。',
          { p0: windowMinutes },
        )
      "
    >
      <span v-if="at" class="tier-status__at">
        {{ t('更新于 {p0}', { p0: formatDateTime(at) }) }}
      </span>
      <el-button :icon="Refresh" :loading="refreshing" @click="emit('refresh')">
        {{ t('刷新') }}
      </el-button>
    </TierSectionHead>

    <el-skeleton v-if="state === 'loading'" :rows="3" animated />

    <el-alert
      v-else-if="state === 'error' && !vendor"
      type="warning"
      show-icon
      :closable="false"
      :title="t('运行状态加载失败')"
      :description="error || undefined"
    />

    <p v-else-if="!vendor" class="tier-status__empty surface-card">
      {{ t('这个厂商还没有保存的号池，保存后这里会显示每个号池的账号和服务情况。') }}
    </p>

    <template v-else>
      <el-alert
        v-if="state === 'error'"
        class="tier-status__alert"
        type="warning"
        show-icon
        :closable="false"
        :title="t('刷新失败，下面是上一次读到的状态')"
        :description="error || undefined"
      />
      <el-alert
        v-if="reason"
        class="tier-status__alert"
        type="info"
        show-icon
        :closable="false"
        :title="reason"
      />

      <div class="tier-status__summary">
        <el-tag :type="vendor.serving ? 'success' : 'info'" effect="light">
          {{ vendor.serving ? t('正在按号池服务') : t('未按号池服务') }}
        </el-tag>
        <span class="tier-status__metric">
          {{ t('自动选号 {p0} 次', { p0: formatNumber(vendor.autoServed) }) }}
        </span>
        <el-tooltip
          :content="t('自动选号的请求里，由低于已启用的最好一级的号池服务的比例')"
          placement="top"
        >
          <el-tag :type="degradedTone(percent)" effect="plain" tabindex="0">
            {{ t('降级率 {p0}', { p0: formatPercent(percent) }) }}
          </el-tag>
        </el-tooltip>
      </div>

      <div class="tier-status__table surface-card" role="table" :aria-label="t('号池运行状态')">
        <div class="tier-status__row tier-status__row--head" role="row">
          <span role="columnheader">{{ t('级别') }}</span>
          <span role="columnheader">{{ t('号池') }}</span>
          <span role="columnheader">{{ t('现在服务') }}</span>
          <span role="columnheader">{{ t('账号') }}</span>
          <span role="columnheader" class="is-number">
            {{ t('最近 {p0} 分钟：服务', { p0: windowMinutes }) }}
          </span>
          <span role="columnheader" class="is-number">{{ t('失败') }}</span>
        </div>
        <div
          v-for="tier in vendor.tiers"
          :key="tier.tierId"
          class="tier-status__row"
          :class="{ 'is-off': !tier.enabled }"
          role="row"
        >
          <span class="tier-status__level" role="cell">L{{ tier.level }}</span>
          <span class="tier-status__name" role="cell">
            <strong>{{ tier.name }}</strong>
            <code>{{ tier.tierId }}</code>
            <el-tag v-if="!tier.enabled" size="small" type="info" effect="plain">
              {{ t('已关闭') }}
            </el-tag>
          </span>
          <span role="cell">
            <el-tag :type="tier.serves ? 'success' : 'info'" size="small" effect="light">
              {{ tier.serves ? t('是') : t('否') }}
            </el-tag>
          </span>
          <span class="tier-status__accounts" role="cell">
            <span
              v-if="layerTotal(tier.accounts) > 0"
              class="layer-bar"
              role="img"
              :aria-label="
                LAYERS.map((key) => `${layerLabel(key)} ${layerCount(tier.accounts, key)}`).join(
                  '，',
                )
              "
            >
              <i
                v-for="segment in layerSegments(tier.accounts)"
                :key="segment.key"
                :class="`layer-${segment.key}`"
                :style="{ width: `${segment.percent}%` }"
              />
            </span>
            <span v-else class="tier-status__none">{{ t('没有账号') }}</span>
            <span class="layer-legend">
              <el-tooltip
                v-for="key in LAYERS.filter((layer) => layerCount(tier.accounts, layer) > 0)"
                :key="key"
                :content="layerHint(key)"
                placement="top"
              >
                <span class="layer-legend__item" :class="`layer-${key}`" tabindex="0">
                  <i />{{ layerLabel(key) }} {{ layerCount(tier.accounts, key) }}
                </span>
              </el-tooltip>
            </span>
          </span>
          <span class="is-number" role="cell">
            {{ formatNumber(tier.served) }}
            <small v-if="tier.servedPinned > 0">
              {{ t('（指定 {p0}）', { p0: formatNumber(tier.servedPinned) }) }}
            </small>
          </span>
          <span class="is-number" :class="{ 'is-failed': tier.failed > 0 }" role="cell">
            {{ formatNumber(tier.failed) }}
          </span>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped lang="scss">
  .tier-status {
    min-width: 0;

    &__at {
      align-self: center;
      font-size: 12px;
      color: var(--kp-text-muted);
    }

    &__alert {
      margin-bottom: 10px;
    }

    &__empty {
      margin: 0;
      padding: 18px;
      font-size: 13px;
      color: var(--kp-text-secondary);
    }

    &__summary {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
      margin-bottom: 10px;
    }

    &__metric {
      font-size: 13px;
      color: var(--kp-text-secondary);
      font-variant-numeric: tabular-nums;
    }

    &__table {
      overflow-x: auto;
    }

    &__row {
      display: grid;
      grid-template-columns: 52px minmax(140px, 1.1fr) 84px minmax(220px, 2fr) 140px 76px;
      align-items: center;
      gap: 12px;
      min-width: 720px;
      padding: 12px 16px;
      font-size: 13px;
      border-top: 1px solid var(--kp-divider);

      &:first-child {
        border-top: 0;
      }

      &--head {
        padding-block: 9px;
        font-size: 12px;
        color: var(--kp-text-muted);
        background: color-mix(in srgb, var(--kp-text-muted) 6%, transparent);
      }

      &.is-off {
        opacity: 0.6;
      }

      .is-number {
        text-align: right;
        font-variant-numeric: tabular-nums;

        small {
          color: var(--kp-text-muted);
        }
      }

      .is-failed {
        color: var(--kp-danger);
      }
    }

    &__level {
      font-weight: 700;
      color: var(--kp-text-secondary);
    }

    &__name {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px;
      min-width: 0;

      code {
        font-size: 11px;
        color: var(--kp-text-muted);
      }
    }

    &__accounts {
      display: flex;
      flex-direction: column;
      gap: 6px;
      min-width: 0;
    }

    &__none {
      font-size: 12px;
      color: var(--kp-text-muted);
    }
  }

  .layer-bar {
    display: flex;
    height: 8px;
    overflow: hidden;
    border-radius: 4px;
    background: color-mix(in srgb, var(--kp-text-muted) 18%, transparent);

    i {
      display: block;
      height: 100%;
    }
  }

  .layer-legend {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 12px;

    &__item {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      font-size: 12px;
      color: var(--kp-text-secondary);
      font-variant-numeric: tabular-nums;

      i {
        width: 7px;
        height: 7px;
        border-radius: 50%;
      }
    }
  }

  /* 每一层一个颜色：状态条的分段和图例的圆点用同一组 */
  .layer-ready {
    --layer-color: var(--kp-success);
  }

  .layer-busy {
    --layer-color: var(--kp-warning);
  }

  .layer-resting {
    --layer-color: var(--el-color-info);
  }

  .layer-unavailable {
    --layer-color: var(--kp-danger);
  }

  .layer-unopened {
    --layer-color: var(--el-color-primary);
  }

  .layer-disabled {
    --layer-color: var(--kp-text-muted);
  }

  .layer-bar i,
  .layer-legend__item i {
    background: var(--layer-color);
  }
</style>
