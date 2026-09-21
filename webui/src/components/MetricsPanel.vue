<script setup lang="ts">
  /**
   * 页眉指标条。classic 为玻璃横排发光圆点；editorial 为 API Key 线框分割条。
   */
  import { computed } from 'vue';
  import { formatNumber } from '@/utils/format';
  import { useAppearanceStore } from '@/stores/appearance';

  export interface MetricPanelItem {
    label: string;
    value: string | number;
    unit?: string;
    tone?: 'primary' | 'success' | 'warning' | 'danger' | 'accent' | 'info';
    valueTone?: 'success' | 'warning' | 'danger';
    sub?: string;
  }

  defineProps<{
    items: MetricPanelItem[];
  }>();

  const appearance = useAppearanceStore();
  const editorial = computed(() => appearance.isEditorial);

  function displayValue(value: string | number) {
    return typeof value === 'number' ? formatNumber(value) : value;
  }
</script>

<template>
  <div class="metrics-panel" :class="[{ 'glass-panel': !editorial }, editorial ? 'is-editorial' : 'is-classic']">
    <template v-for="(item, index) in items" :key="item.label">
      <div v-if="!editorial && index > 0" class="metric-divider" />
      <div class="metric-item">
        <div class="metric-item__header">
          <span class="metric-dot" :class="`dot-${item.tone ?? 'primary'}`" />
          <span class="metric-label">{{ item.label }}</span>
        </div>
        <div class="metric-value" :class="item.valueTone ? `is-${item.valueTone}` : ''">
          {{ displayValue(item.value)
          }}<small v-if="item.unit">{{ item.unit }}</small>
        </div>
        <div v-if="item.sub" class="metric-sub">{{ item.sub }}</div>
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
  .metrics-panel.is-classic {
    display: flex;
    align-items: center;
    padding: 20px 28px;
    margin-bottom: 24px;
    width: 100%;

    .metric-item {
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 0;
    }

    .metric-item__header {
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .metric-dot {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      display: inline-block;
      flex: 0 0 auto;

      &.dot-primary {
        background-color: var(--kp-primary);
        box-shadow: 0 0 6px rgba(91, 141, 239, 0.6);
      }
      &.dot-success {
        background-color: var(--kp-success);
        box-shadow: 0 0 6px rgba(54, 192, 138, 0.6);
      }
      &.dot-warning {
        background-color: var(--kp-warning);
        box-shadow: 0 0 6px rgba(240, 168, 77, 0.6);
      }
      &.dot-danger {
        background-color: var(--kp-danger);
        box-shadow: 0 0 6px rgba(239, 91, 107, 0.6);
      }
      &.dot-accent {
        background-color: var(--kp-accent);
        box-shadow: 0 0 6px rgba(179, 135, 255, 0.6);
      }
      &.dot-info {
        background-color: var(--kp-text-muted);
        box-shadow: 0 0 6px rgba(138, 147, 168, 0.5);
      }
    }

    .metric-label {
      font-size: 12.5px;
      font-weight: 600;
      color: var(--kp-text-secondary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .metric-value {
      font-size: 26px;
      font-weight: 800;
      color: var(--kp-text);
      line-height: 1.15;
      font-family: ui-monospace, 'JetBrains Mono', 'SF Mono', Consolas, monospace;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;

      &.is-success {
        color: var(--kp-success);
      }
      &.is-warning {
        color: var(--kp-warning);
      }
      &.is-danger {
        color: var(--kp-danger);
      }

      small {
        margin-left: 6px;
        font-size: 13px;
        font-weight: 500;
        color: var(--kp-text-muted);
        font-family: inherit;
      }
    }

    .metric-sub {
      font-size: 11px;
      color: var(--kp-text-muted);
      font-weight: 500;
    }

    .metric-divider {
      width: 1px;
      height: 40px;
      background-color: var(--kp-divider);
      margin: 0 28px;
      align-self: center;
      flex-shrink: 0;
    }
  }

  .metrics-panel.is-editorial {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(min(120px, 100%), 1fr));
    gap: 8px 12px;
    width: 100%;
    max-width: 100%;
    min-width: 0;

    .metric-item {
      min-width: 0;
      max-width: 100%;
      padding-left: 12px;
      border-left: 1px solid var(--el-border-color);

      &:first-child {
        padding-left: 0;
        border-left: 0;
      }
    }

    .metric-dot {
      display: none;
    }

    .metric-label {
      display: block;
      color: var(--kp-text-secondary);
      font-size: 11px;
    }

    .metric-value {
      display: block;
      margin-top: 4px;
      color: var(--kp-text);
      font-size: 18px;
      font-weight: 600;
      line-height: 1.2;
      overflow-wrap: anywhere;
      font-variant-numeric: tabular-nums;

      &.is-success {
        color: var(--kp-success);
      }
      &.is-warning {
        color: var(--kp-warning);
      }
      &.is-danger {
        color: var(--kp-danger);
      }

      small {
        margin-left: 6px;
        color: var(--kp-text-muted);
        font-size: 11px;
        font-weight: 400;
      }
    }

    .metric-sub {
      display: block;
      margin-top: 6px;
      color: var(--kp-text-muted);
      font-size: 11px;
    }
  }

  @media (max-width: 768px) {
    .metrics-panel.is-classic {
      flex-direction: column;
      align-items: stretch;
      gap: 16px;
      padding: 20px;

      .metric-divider {
        width: 100%;
        height: 1px;
        margin: 4px 0;
      }
    }
  }

  @media (max-width: 760px) {
    .metrics-panel.is-editorial {
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 12px;

      .metric-item:nth-child(odd) {
        padding-left: 0;
        border-left: 0;
      }
    }
  }
</style>
