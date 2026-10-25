<script setup lang="ts">
  /**
   * 号池表：一个厂商的有序号池，排在越前面级别越好（L1 最好）。
   * 名称、标识、系数、自动收下的账号类别、可选与启用都在行内编辑；拖动把手或用上下箭头调整级别。
   *
   * 表格只改传入的草稿的副本：每次编辑都通过 update:tiers 交回新的数组，删除交给上层
   * （要同时处理固定到该号池的账号）。
   */
  import { computed, ref } from 'vue';
  import { ElMessage } from 'element-plus';
  import {
    ArrowDown,
    ArrowUp,
    CopyDocument,
    Delete,
    InfoFilled,
    Rank,
  } from '@element-plus/icons-vue';
  import { t } from '@/i18n';
  import { ALLOCATION_CATEGORIES, type AllocationCategory } from '@/api/allocationDefaults';
  import { copyText } from '@/utils/clipboard';
  import { categoryMeta } from '../allocation/allocationMeta';
  import {
    factorExample,
    formatFactor,
    lowestEnabledKey,
    moveItem,
    suffixExample,
    tierProblems,
    type TierDraft,
  } from './sourceTiersMeta';

  const props = defineProps<{
    tiers: TierDraft[];
    vendor: string;
    /** 每个号池（按行 key）手动指定的账号数 */
    pinCounts: Record<string, number>;
    /** 每个号池（按行 key）在已保存配置里实际收下的启用账号数；没有已保存对应的号池没有数字 */
    placedCounts: Record<string, number>;
    /** 当前版本是否能按号池选号；为假时「模型@标识」写法还不会生效，复制按钮禁用 */
    copyEnabled: boolean;
    disabled?: boolean;
  }>();
  const emit = defineEmits<{
    'update:tiers': [tiers: TierDraft[]];
    remove: [tier: TierDraft];
  }>();

  const problems = computed(() => tierProblems(props.tiers));
  const lowestKey = computed(() => lowestEnabledKey(props.tiers));

  function patch(index: number, change: Partial<TierDraft>) {
    emit(
      'update:tiers',
      props.tiers.map((tier, position) => (position === index ? { ...tier, ...change } : tier)),
    );
  }

  function setName(index: number, value: string) {
    patch(index, { name: value });
  }

  function setTierId(index: number, value: string) {
    patch(index, { tierId: value.toLowerCase().replace(/\s+/g, '') });
  }

  function setFactor(index: number, value: number | undefined | null) {
    patch(index, { priceFactor: typeof value === 'number' ? value : 1 });
  }

  function setCategories(index: number, value: AllocationCategory[]) {
    patch(index, { memberCategories: value });
  }

  /** 类别只能属于本厂商的一个号池：已被别的号池占用的选项禁用并写明在哪 */
  function categoryOptions(tier: TierDraft) {
    return ALLOCATION_CATEGORIES.map((category) => {
      const owner = props.tiers.find(
        (other) => other.key !== tier.key && other.memberCategories.includes(category),
      );
      const title = categoryMeta(category).title;
      return {
        value: category,
        label: owner ? t('{p0}（已在「{p1}」）', { p0: title, p1: owner.name.trim() }) : title,
        disabled: !!owner,
      };
    });
  }

  function move(index: number, to: number) {
    emit('update:tiers', moveItem(props.tiers, index, to));
  }

  const dragKey = ref('');
  const dropKey = ref('');

  function onDragStart(event: DragEvent, tier: TierDraft) {
    dragKey.value = tier.key;
    if (!event.dataTransfer) return;
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', tier.tierId);
    const row = (event.currentTarget as HTMLElement | null)?.closest('.tier-row');
    if (row) event.dataTransfer.setDragImage(row, 24, 24);
  }

  function onDrop(target: TierDraft) {
    const from = props.tiers.findIndex((tier) => tier.key === dragKey.value);
    const to = props.tiers.findIndex((tier) => tier.key === target.key);
    dragKey.value = '';
    dropKey.value = '';
    if (from >= 0 && to >= 0) move(from, to);
  }

  function onDragEnd() {
    dragKey.value = '';
    dropKey.value = '';
  }

  async function copySuffix(tier: TierDraft) {
    const text = suffixExample(props.vendor, tier.tierId);
    try {
      await copyText(text);
      ElMessage.success(t('已复制 {p0}', { p0: text }));
    } catch {
      ElMessage.error(t('复制失败，请手动复制'));
    }
  }
</script>

<template>
  <div class="tier-table surface-card" role="table" :aria-label="t('号池列表')">
    <div class="tier-row tier-row--head" role="row">
      <span class="tier-cell tier-cell--level" role="columnheader">{{ t('级别') }}</span>
      <span class="tier-cell" role="columnheader">
        <el-tooltip
          :content="
            t(
              '名称给人看；标识是「模型@标识」写法里的号池引用。修改标识后，按旧标识固定号池的客户端配置会失效。',
            )
          "
          placement="top"
          :show-after="150"
        >
          <span class="tier-head">
            {{ t('名称与标识') }}
            <el-icon class="tier-head__icon"><InfoFilled /></el-icon>
          </span>
        </el-tooltip>
      </span>
      <span class="tier-cell" role="columnheader">
        <el-tooltip
          :content="
            t('号池里的最终单价 = 标准价 × 系数。标准价是模型定价里命中的价格，默认系数 1。')
          "
          placement="top"
          :show-after="150"
        >
          <span class="tier-head">
            {{ t('价格系数') }}
            <el-icon class="tier-head__icon"><InfoFilled /></el-icon>
          </span>
        </el-tooltip>
      </span>
      <span class="tier-cell" role="columnheader">
        <el-tooltip
          :content="
            t(
              '按类别把账号自动归入这个号池，一个类别只能属于本厂商的一个号池。没有被类别或指定账号收下的账号落入最低级。',
            )
          "
          placement="top"
          :show-after="150"
        >
          <span class="tier-head">
            {{ t('自动收下的类别') }}
            <el-icon class="tier-head__icon"><InfoFilled /></el-icon>
          </span>
        </el-tooltip>
      </span>
      <span class="tier-cell" role="columnheader">
        <el-tooltip
          :content="
            t(
              '手动指定到这个号池的账号数，在下方「指定账号」里调整。其下的「共 N 个」是已保存配置里这个号池实际收下的启用账号数，含按类别收下的和落入最低级的账号。',
            )
          "
          placement="top"
          :show-after="150"
        >
          <span class="tier-head">
            {{ t('指定账号') }}
            <el-icon class="tier-head__icon"><InfoFilled /></el-icon>
          </span>
        </el-tooltip>
      </span>
      <span class="tier-cell tier-cell--switch" role="columnheader">
        <el-tooltip
          :content="t('允许用户用后缀、请求头或 Key 偏好把请求固定到这个号池。')"
          placement="top"
          :show-after="150"
        >
          <span class="tier-head">
            {{ t('可选') }}
            <el-icon class="tier-head__icon"><InfoFilled /></el-icon>
          </span>
        </el-tooltip>
      </span>
      <span class="tier-cell tier-cell--switch" role="columnheader">
        <el-tooltip
          :content="t('停用的号池不再服务本厂商的分级请求，它的账号也不会改由其他号池接收。')"
          placement="top"
          :show-after="150"
        >
          <span class="tier-head">
            {{ t('启用') }}
            <el-icon class="tier-head__icon"><InfoFilled /></el-icon>
          </span>
        </el-tooltip>
      </span>
      <span class="tier-cell tier-cell--actions" role="columnheader">
        <span class="sr-only">{{ t('操作') }}</span>
      </span>
    </div>

    <div
      v-for="(tier, index) in tiers"
      :key="tier.key"
      class="tier-row"
      :class="{
        'is-off': !tier.enabled,
        'is-dragging': dragKey === tier.key,
        'is-drop-target': dropKey === tier.key && dragKey !== tier.key,
      }"
      role="row"
      @dragover.prevent="dragKey && (dropKey = tier.key)"
      @drop.prevent="onDrop(tier)"
    >
      <span class="tier-cell tier-cell--level" role="cell">
        <span
          class="tier-handle"
          draggable="true"
          :title="t('拖动调整级别')"
          @dragstart="onDragStart($event, tier)"
          @dragend="onDragEnd"
        >
          <el-icon><Rank /></el-icon>
        </span>
        <span class="tier-level" :title="t('级别 {p0}，1 最好', { p0: index + 1 })">
          L{{ index + 1 }}
        </span>
      </span>

      <span class="tier-cell tier-cell--name" role="cell">
        <el-input
          :model-value="tier.name"
          :disabled="disabled"
          :aria-label="t('号池名称')"
          :placeholder="t('号池名称')"
          :class="{ 'is-invalid': !!problems[tier.key]?.name }"
          @update:model-value="setName(index, String($event))"
        />
        <small v-if="problems[tier.key]?.name" class="tier-problem">
          {{ problems[tier.key]?.name }}
        </small>
        <span class="tier-id">
          <el-input
            size="small"
            class="tier-id__input"
            :model-value="tier.tierId"
            :maxlength="40"
            :disabled="disabled"
            :aria-label="t('号池标识')"
            :class="{ 'is-invalid': !!problems[tier.key]?.id }"
            @update:model-value="setTierId(index, String($event))"
          >
            <template #prefix>@</template>
          </el-input>
          <el-tooltip
            :content="
              copyEnabled
                ? t('复制「模型@标识」写法')
                : t('当前版本尚不支持按号池选号，这个写法暂时不会生效')
            "
            placement="top"
          >
            <span>
              <el-button
                link
                :icon="CopyDocument"
                :disabled="!copyEnabled || !!problems[tier.key]?.id"
                :aria-label="t('复制「模型@标识」写法')"
                @click="copySuffix(tier)"
              />
            </span>
          </el-tooltip>
        </span>
        <small v-if="problems[tier.key]?.id" class="tier-problem">
          {{ problems[tier.key]?.id }}
        </small>
      </span>

      <span class="tier-cell tier-cell--factor" role="cell">
        <span class="tier-cell__label">{{ t('价格系数') }}</span>
        <el-input-number
          class="tier-factor"
          controls-position="right"
          :model-value="tier.priceFactor"
          :min="0.000001"
          :max="1000"
          :step="0.1"
          :step-strictly="false"
          :value-on-clear="1"
          :disabled="disabled"
          :aria-label="t('价格系数')"
          @update:model-value="setFactor(index, $event)"
        />
        <small v-if="problems[tier.key]?.factor" class="tier-problem">
          {{ problems[tier.key]?.factor }}
        </small>
        <small v-else class="tier-hint">
          {{
            t('标准价 × {p0}，例：$10 → ${p1}', {
              p0: formatFactor(tier.priceFactor),
              p1: factorExample(tier.priceFactor),
            })
          }}
        </small>
      </span>

      <span class="tier-cell tier-cell--categories" role="cell">
        <span class="tier-cell__label">{{ t('自动收下的类别') }}</span>
        <el-select
          multiple
          collapse-tags
          collapse-tags-tooltip
          :model-value="tier.memberCategories"
          :disabled="disabled"
          :placeholder="t('不按类别收账号')"
          :aria-label="t('自动收下的类别')"
          @update:model-value="setCategories(index, $event as AllocationCategory[])"
        >
          <el-option
            v-for="option in categoryOptions(tier)"
            :key="option.value"
            :value="option.value"
            :label="option.label"
            :disabled="option.disabled"
          />
        </el-select>
      </span>

      <span class="tier-cell tier-cell--members" role="cell">
        <span class="tier-cell__label">{{ t('指定账号') }}</span>
        <span class="tier-count">{{ t('{p0} 个', { p0: pinCounts[tier.key] ?? 0 }) }}</span>
        <small v-if="placedCounts[tier.key] !== undefined" class="tier-hint">
          {{ t('共 {p0} 个', { p0: placedCounts[tier.key] }) }}
        </small>
        <el-tag v-if="tier.key === lowestKey" size="small" type="info" effect="plain">
          {{ t('未归类账号归入此级') }}
        </el-tag>
      </span>

      <span class="tier-cell tier-cell--switch" role="cell">
        <span class="tier-cell__label">{{ t('可选') }}</span>
        <el-switch
          :model-value="tier.userSelectable"
          :disabled="disabled"
          :aria-label="t('可选')"
          @update:model-value="patch(index, { userSelectable: Boolean($event) })"
        />
      </span>

      <span class="tier-cell tier-cell--switch" role="cell">
        <span class="tier-cell__label">{{ t('启用') }}</span>
        <el-switch
          :model-value="tier.enabled"
          :disabled="disabled"
          :aria-label="t('启用')"
          @update:model-value="patch(index, { enabled: Boolean($event) })"
        />
      </span>

      <span class="tier-cell tier-cell--actions" role="cell">
        <el-button
          link
          :icon="ArrowUp"
          :disabled="disabled || index === 0"
          :aria-label="t('上移')"
          @click="move(index, index - 1)"
        />
        <el-button
          link
          :icon="ArrowDown"
          :disabled="disabled || index === tiers.length - 1"
          :aria-label="t('下移')"
          @click="move(index, index + 1)"
        />
        <el-button
          link
          type="danger"
          :icon="Delete"
          :disabled="disabled"
          :aria-label="t('删除号池')"
          @click="emit('remove', tier)"
        />
      </span>
    </div>

    <p v-if="!tiers.length" class="tier-empty">
      {{ t('还没有号池。可以套用预设，或添加第一个号池。') }}
    </p>
  </div>
</template>

<style scoped lang="scss">
  .tier-table {
    /* 是否改成卡片取决于表格本身的宽度（左侧导航会占位），不是窗口宽度 */
    container: tiers / inline-size;
    min-width: 0;
    padding: 4px 0;
  }

  .tier-row {
    display: grid;
    grid-template-columns:
      68px minmax(180px, 1.4fr) 150px minmax(150px, 1.1fr) minmax(110px, 0.8fr)
      78px 78px 88px;
    align-items: start;
    column-gap: 10px;
    padding: 12px 16px;
    border-top: 1px solid var(--kp-divider);
    transition:
      background-color 0.16s ease,
      opacity 0.16s ease;

    &--head {
      align-items: center;
      padding-block: 10px;
      border-top: 0;
      font-size: 12px;
      color: var(--kp-text-muted);
    }

    &.is-off {
      background: color-mix(in srgb, var(--kp-text-muted) 6%, transparent);
    }

    &.is-dragging {
      opacity: 0.45;
    }

    &.is-drop-target {
      box-shadow: inset 0 2px 0 var(--kp-primary);
    }
  }

  .tier-cell {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
    min-width: 0;

    &__label {
      display: none;
    }

    &--level {
      flex-direction: row;
      align-items: center;
      gap: 8px;
      min-height: 32px;
    }

    &--switch {
      min-height: 32px;
      justify-content: center;
    }

    &--actions {
      flex-direction: row;
      align-items: center;
      justify-content: flex-end;
      min-height: 32px;

      .el-button {
        margin: 0;
      }
    }

    &--members {
      min-height: 32px;
      justify-content: center;
    }

    &--factor,
    &--categories,
    &--name {
      :deep(.el-input),
      :deep(.el-input-number),
      :deep(.el-select) {
        width: 100%;
      }
    }
  }

  .tier-head {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    line-height: 1.3;
    cursor: help;

    &__icon {
      font-size: 12px;
      opacity: 0.55;
    }
  }

  .tier-handle {
    display: inline-grid;
    place-items: center;
    width: 22px;
    height: 28px;
    color: var(--kp-text-muted);
    cursor: grab;
    user-select: none;

    &:active {
      cursor: grabbing;
    }
  }

  .tier-level {
    display: inline-grid;
    place-items: center;
    min-width: 30px;
    height: 24px;
    padding: 0 6px;
    border-radius: 6px;
    background: var(--kp-primary-soft);
    color: var(--kp-primary);
    font-size: 12px;
    font-weight: 650;
    font-variant-numeric: tabular-nums;
  }

  .tier-id {
    display: flex;
    align-items: center;
    gap: 2px;
    width: 100%;

    &__input {
      flex: 1 1 auto;
      min-width: 0;

      :deep(.el-input__inner) {
        font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
        font-size: 12px;
      }
    }
  }

  .is-invalid {
    :deep(.el-input__wrapper) {
      box-shadow: 0 0 0 1px var(--el-color-danger) inset;
    }
  }

  .tier-hint,
  .tier-problem {
    font-size: 11.5px;
    line-height: 1.45;
    overflow-wrap: anywhere;
  }

  .tier-hint {
    color: var(--kp-text-muted);
  }

  .tier-problem {
    color: var(--el-color-danger);
  }

  .tier-count {
    font-size: 13.5px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    color: var(--kp-text);
  }

  .tier-empty {
    margin: 0;
    padding: 28px 16px;
    text-align: center;
    font-size: 13px;
    color: var(--kp-text-muted);
  }

  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    white-space: nowrap;
  }

  /* 表格放不下时（各列最小宽度合计约 1000px）：每个号池一张卡片，字段带上自己的名称 */
  @container tiers (max-width: 1000px) {
    .tier-row {
      grid-template-columns: repeat(2, minmax(0, 1fr));
      row-gap: 12px;
      align-items: start;
      padding: 14px 16px;

      &--head {
        display: none;
      }
    }

    .tier-cell {
      &--level {
        grid-row: 1;
        grid-column: 1;
      }

      &--actions {
        grid-row: 1;
        grid-column: 2;
        justify-self: end;
      }

      &--name,
      &--members {
        grid-column: 1 / -1;
      }

      &--members {
        flex-direction: row;
        flex-wrap: wrap;
        align-items: center;
        justify-content: flex-start;
        gap: 8px;
      }

      &--switch {
        align-items: flex-start;
        justify-content: flex-start;
      }

      &__label {
        display: block;
        font-size: 11.5px;
        color: var(--kp-text-muted);
      }
    }
  }
</style>
