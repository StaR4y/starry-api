<script setup lang="ts">
  /**
   * 新账号路由与限制：各类账号新建时的初始限制，按类别并排对比。
   * 每个账号仍可在自己的设置里单独调整；这里的修改不影响已有账号。
   *
   * 后端 PUT 会整体替换配置，所以每次保存都带上所有类别当前已保存的值，只改动被编辑的那一类。
   */
  import { computed, onMounted, ref } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { EditPen, InfoFilled, Odometer, RefreshLeft } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { t } from '@/i18n';
  import { ApiError } from '@/utils/http';
  import {
    allocationDefaultsApi,
    type AllocationCategory,
    type AllocationCategoryDefaults,
    type AllocationDefaults,
    type AllocationPolicy,
  } from '@/api/allocationDefaults';
  import { proxyChannelsApi } from '@/api/proxyChannels';
  import AccountTypeLogo from '@/views/accounts/components/account-form/AccountTypeLogo.vue';
  import AllocationEditDialog from './components/allocation/AllocationEditDialog.vue';
  import {
    CATEGORY_LOGO,
    LIMIT_COLUMNS,
    allocationErrorMessage,
    categoryMeta,
    limitCell,
  } from './components/allocation/allocationMeta';

  const state = ref<'loading' | 'ready' | 'error'>('loading');
  const loadError = ref('');
  const defaults = ref<AllocationDefaults | null>(null);
  const tls = ref({ enabled: false, profiles: [] as string[] });

  const dialogOpen = ref(false);
  const editingCategory = ref<AllocationCategory | null>(null);
  const editing = computed(
    () =>
      defaults.value?.categories.find((item) => item.category === editingCategory.value) ?? null,
  );
  const saving = ref(false);
  const serverError = ref('');
  const resetting = ref<AllocationCategory | null>(null);

  /** 一行的各列取值 */
  function rowCells(item: AllocationCategoryDefaults) {
    return LIMIT_COLUMNS.map((column) => ({ column, cell: limitCell(column.key, item) }));
  }

  const customizedCount = computed(
    () => defaults.value?.categories.filter((item) => item.configured).length ?? 0,
  );

  async function load() {
    state.value = 'loading';
    loadError.value = '';
    try {
      const [loaded, catalog] = await Promise.all([
        allocationDefaultsApi.get(),
        // TLS 组件状态只影响可选项，失败时按未启用处理
        proxyChannelsApi.catalog().catch(() => null),
      ]);
      defaults.value = loaded;
      tls.value = { enabled: !!catalog?.tlsEnabled, profiles: catalog?.tlsProfiles ?? [] };
      state.value = 'ready';
    } catch (err) {
      loadError.value = err instanceof ApiError ? err.message : t('服务暂时不可用');
      state.value = 'error';
    }
  }

  function openEdit(item: AllocationCategoryDefaults) {
    editingCategory.value = item.category;
    serverError.value = '';
    dialogOpen.value = true;
  }

  /** 与内置值完全相同的配置没有保存的意义，按「内置默认」处理 */
  function sameAsBuiltIn(policy: AllocationPolicy, builtIn: AllocationPolicy) {
    return (Object.keys(builtIn) as (keyof AllocationPolicy)[]).every(
      (key) => policy[key] === builtIn[key],
    );
  }

  /** PUT 是整体替换：其他类别原样带上各自已保存的值（未配置的为 null） */
  async function commit(category: AllocationCategory, policy: AllocationPolicy | null) {
    if (!defaults.value) return;
    const categories: Partial<Record<AllocationCategory, AllocationPolicy | null>> = {};
    for (const item of defaults.value.categories) categories[item.category] = item.configured;
    categories[category] = policy;
    defaults.value = await allocationDefaultsApi.update(categories);
  }

  async function saveEdited(policy: AllocationPolicy) {
    const item = editing.value;
    if (!item) return;
    saving.value = true;
    serverError.value = '';
    try {
      await commit(item.category, sameAsBuiltIn(policy, item.builtIn) ? null : policy);
      dialogOpen.value = false;
      ElMessage.success(t('已保存，新建的账号将使用这些限制'));
    } catch (err) {
      serverError.value = allocationErrorMessage(err);
    } finally {
      saving.value = false;
    }
  }

  async function restoreBuiltIn(item: AllocationCategoryDefaults) {
    const confirmed = await ElMessageBox.confirm(
      t('将「{p0}」恢复为内置值？之后新建的该类账号使用内置限制，已有账号不受影响。', {
        p0: categoryMeta(item.category).title,
      }),
      t('恢复内置值'),
      { type: 'warning', confirmButtonText: t('恢复'), cancelButtonText: t('取消') },
    )
      .then(() => true)
      .catch(() => false);
    if (!confirmed) return;
    resetting.value = item.category;
    try {
      await commit(item.category, null);
      ElMessage.success(t('已恢复内置值'));
    } catch {
      /* 已提示 */
    } finally {
      resetting.value = null;
    }
  }

  onMounted(load);
</script>

<template>
  <PageSection :title="t('路由与限制')" :subtitle="t('运行与安全')" :icon="Odometer">
    <p class="limits-intro">
      {{
        t(
          '各类账号新建时的初始限制。每个账号仍可在自己的「路由与限制」里单独调整；修改这里不会影响已有账号。',
        )
      }}
    </p>

    <el-skeleton v-if="state === 'loading'" class="limits-state" :rows="6" animated />

    <el-result
      v-else-if="state === 'error'"
      class="limits-state"
      icon="warning"
      :title="t('设置加载失败')"
      :sub-title="loadError"
    >
      <template #extra>
        <el-button type="primary" @click="load">{{ t('重新加载') }}</el-button>
      </template>
    </el-result>

    <template v-else-if="defaults">
      <div class="limits-table surface-card" role="table" :aria-label="t('各类账号的初始限制')">
        <div class="limits-row limits-row--head" role="row">
          <span class="limits-cell limits-cell--category" role="columnheader">
            {{ t('账号类别') }}
          </span>
          <span
            v-for="column in LIMIT_COLUMNS"
            :key="column.key"
            class="limits-cell"
            role="columnheader"
          >
            <el-tooltip :content="column.tip()" placement="top" :show-after="150">
              <span class="limits-head">
                {{ column.label() }}
                <el-icon class="limits-head__icon"><InfoFilled /></el-icon>
              </span>
            </el-tooltip>
          </span>
          <span class="limits-cell limits-cell--actions" role="columnheader">
            <span class="sr-only">{{ t('操作') }}</span>
          </span>
        </div>

        <div
          v-for="item in defaults.categories"
          :key="item.category"
          class="limits-row"
          :class="{ 'is-customized': item.configured }"
          role="row"
        >
          <span class="limits-cell limits-cell--category" role="rowheader">
            <AccountTypeLogo :type="CATEGORY_LOGO[item.category]" :size="32" />
            <span class="category">
              <span class="category__name">
                <strong>{{ categoryMeta(item.category).title }}</strong>
                <el-tag v-if="item.configured" size="small" effect="light" round>
                  {{ t('已自定义') }}
                </el-tag>
              </span>
              <span class="category__desc">{{ categoryMeta(item.category).description }}</span>
            </span>
          </span>

          <span
            v-for="{ column, cell } in rowCells(item)"
            :key="column.key"
            class="limits-cell"
            :class="{ 'is-na': !cell.applicable }"
            role="cell"
          >
            <span class="limits-cell__label">{{ column.label() }}</span>
            <span v-if="!cell.applicable" class="value value--na">
              <el-tooltip :content="t('仅对 Anthropic 账号生效')" placement="top" :show-after="150">
                <span>—</span>
              </el-tooltip>
            </span>
            <el-tooltip
              v-else
              :content="
                cell.changed
                  ? t('{p0}（内置值：{p1}）', { p0: cell.full, p1: cell.builtIn })
                  : cell.full
              "
              :disabled="!cell.changed && cell.full === cell.value"
              placement="top"
              :show-after="150"
            >
              <span class="value" :class="{ 'value--changed': cell.changed }">
                {{ cell.value }}
                <small v-if="cell.detail">{{ cell.detail }}</small>
              </span>
            </el-tooltip>
          </span>

          <span class="limits-cell limits-cell--actions" role="cell">
            <el-tooltip v-if="item.configured" :content="t('恢复内置值')" placement="top">
              <el-button
                link
                :icon="RefreshLeft"
                :aria-label="t('恢复内置值')"
                :loading="resetting === item.category"
                :disabled="resetting !== null"
                @click="restoreBuiltIn(item)"
              />
            </el-tooltip>
            <el-tooltip :content="t('编辑')" placement="top">
              <el-button
                size="small"
                :icon="EditPen"
                :aria-label="t('编辑')"
                @click="openEdit(item)"
              >
                {{ t('编辑') }}
              </el-button>
            </el-tooltip>
          </span>
        </div>
      </div>

      <p class="limits-note">
        <template v-if="customizedCount">
          {{
            t('{p0} 类已自定义；高亮的取值与内置值不同，悬停可查看内置值。', {
              p0: customizedCount,
            })
          }}
        </template>
        <template v-else>{{ t('全部使用内置值。') }}</template>
      </p>
    </template>

    <AllocationEditDialog
      v-model="dialogOpen"
      :category="editing"
      :tls-enabled="tls.enabled"
      :tls-profiles="tls.profiles"
      :saving="saving"
      :server-error="serverError"
      @save="saveEdited"
    />
  </PageSection>
</template>

<style scoped lang="scss">
  .limits-intro {
    margin: 0 0 16px;
    font-size: 13px;
    line-height: 1.6;
    color: var(--kp-text-secondary);
  }

  .limits-state {
    min-height: 320px;
  }

  .limits-table {
    /* 是否改成卡片取决于表格本身的宽度（两侧导航都会占位），不是窗口宽度 */
    container: limits / inline-size;
    min-width: 0;
    padding: 4px 0;
  }

  .limits-row {
    display: grid;
    grid-template-columns: minmax(150px, 1.6fr) repeat(7, minmax(56px, 1fr)) 64px;
    align-items: center;
    column-gap: 10px;
    padding: 12px 16px;
    border-top: 1px solid var(--kp-divider);

    &--head {
      padding-block: 10px;
      border-top: 0;
      font-size: 12px;
      color: var(--kp-text-muted);
    }

    &.is-customized {
      background: color-mix(in srgb, var(--kp-primary) 4%, transparent);
    }
  }

  .limits-cell {
    min-width: 0;

    &__label {
      display: none;
    }

    &--category {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    &--actions {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 6px;

      .el-button {
        margin: 0;
      }

      /* 表格里按钮只显示图标（文字在 Element Plus 按钮内部的 span 里） */
      :deep(.el-button > span) {
        display: none;
      }
    }
  }

  .limits-head {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    white-space: nowrap;
    cursor: help;

    &__icon {
      font-size: 12px;
      opacity: 0.55;
    }
  }

  .category {
    display: flex;
    flex-direction: column;
    min-width: 0;

    &__name {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px;

      strong {
        font-size: 14px;
        color: var(--kp-text);
      }
    }

    &__desc {
      margin-top: 2px;
      font-size: 12px;
      color: var(--kp-text-muted);
      overflow-wrap: anywhere;
    }
  }

  .value {
    display: inline-flex;
    flex-direction: column;
    font-size: 13.5px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    line-height: 1.35;
    color: var(--kp-text);
    white-space: nowrap;

    small {
      font-size: 11.5px;
      font-weight: 400;
      color: var(--kp-text-muted);
    }

    &--changed {
      color: var(--kp-primary);
      cursor: help;
    }

    &--na {
      font-weight: 400;
      color: var(--kp-text-muted);
    }
  }

  .limits-note {
    margin: 10px 2px 0;
    font-size: 12px;
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

  /* 表格放不下时（各列最小宽度合计约 720px）：每类一张卡片，取值按宽度排成几列并带上自己的名称 */
  @container limits (max-width: 720px) {
    .limits-row {
      grid-template-columns: repeat(auto-fill, minmax(110px, 1fr));
      row-gap: 10px;
      padding: 14px 16px;

      &--head {
        display: none;
      }

      &:nth-child(2) {
        border-top: 0;
      }
    }

    .limits-cell {
      &--category {
        grid-column: 1 / -1;
      }

      &--actions {
        grid-column: 1 / -1;
        justify-content: flex-start;

        :deep(.el-button > span) {
          display: inline;
        }
      }

      &__label {
        display: block;
        margin-bottom: 2px;
        font-size: 11.5px;
        color: var(--kp-text-muted);
      }

      /* 卡片里不显示对该类别无效的项 */
      &.is-na {
        display: none;
      }
    }
  }
</style>
