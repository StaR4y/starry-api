<script setup lang="ts">
  /**
   * 新账号路由与限制：各类账号新建时的初始限制。
   * 每个账号仍可在自己的设置里单独调整；这里的修改不影响已有账号。
   *
   * 后端 PUT 会整体替换配置，所以每次保存都带上所有类别当前已保存的值，只改动被编辑的那一类。
   */
  import { computed, onMounted, ref, type Component } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import {
    Box,
    ChatDotRound,
    EditPen,
    Key,
    Odometer,
    RefreshLeft,
    Share,
    User,
  } from '@element-plus/icons-vue';
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
  import AllocationEditDialog from './components/allocation/AllocationEditDialog.vue';
  import {
    allocationErrorMessage,
    categoryMeta,
    policyCells,
  } from './components/allocation/allocationMeta';

  const icons: Record<AllocationCategory, Component> = {
    OFFICIAL_API_KEY: Key,
    COMPATIBLE_API_KEY: Share,
    CODEX: ChatDotRound,
    CLAUDE_OAUTH: User,
    KIRO: Box,
  };

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

  const anyCustomized = computed(
    () => !!defaults.value?.categories.some((item) => item.configured),
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
  <PageSection :title="t('新账号路由与限制')" :subtitle="t('运行与安全')" :icon="Odometer">
    <p class="limits-intro">
      {{
        t(
          '这是各类账号新建时的初始限制。每个账号仍可在自己的设置里单独调整；修改这里不会影响已有账号。',
        )
      }}
    </p>

    <el-skeleton v-if="state === 'loading'" class="limits-state" :rows="8" animated />

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

    <div v-else-if="defaults" class="limits-list">
      <article
        v-for="item in defaults.categories"
        :key="item.category"
        class="limit-card surface-card"
      >
        <header class="limit-card__head">
          <span class="limit-card__icon" aria-hidden="true">
            <el-icon :size="18"><component :is="icons[item.category]" /></el-icon>
          </span>
          <div class="limit-card__title">
            <div class="limit-card__name">
              <strong>{{ categoryMeta(item.category).title }}</strong>
              <el-tag
                :type="item.configured ? 'primary' : 'info'"
                size="small"
                effect="light"
                round
              >
                {{ item.configured ? t('已自定义') : t('内置默认') }}
              </el-tag>
            </div>
            <p>{{ categoryMeta(item.category).description }}</p>
          </div>
          <div class="limit-card__actions">
            <el-button
              v-if="item.configured"
              link
              :icon="RefreshLeft"
              :loading="resetting === item.category"
              :disabled="resetting !== null"
              @click="restoreBuiltIn(item)"
            >
              {{ t('恢复内置值') }}
            </el-button>
            <el-button :icon="EditPen" @click="openEdit(item)">{{ t('编辑') }}</el-button>
          </div>
        </header>
        <dl class="limit-card__cells">
          <div
            v-for="cell in policyCells(item.effective, item.builtIn, item.anthropicSettings)"
            :key="cell.key"
            class="limit-cell"
            :class="{ 'is-changed': cell.changed }"
          >
            <dt>{{ cell.label }}</dt>
            <dd>{{ cell.value }}</dd>
          </div>
        </dl>
      </article>
      <p v-if="anyCustomized" class="limits-note">{{ t('主题色的取值与内置值不同。') }}</p>
    </div>

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

  .limits-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
    min-width: 0;
  }

  .limit-card {
    display: flex;
    flex-direction: column;
    gap: 12px;
    min-width: 0;
    padding: 14px 16px;

    &__head {
      display: flex;
      align-items: center;
      gap: 12px;
      min-width: 0;
    }

    &__icon {
      display: grid;
      flex: 0 0 auto;
      place-items: center;
      width: 36px;
      height: 36px;
      border-radius: var(--kp-radius-sm);
      background: var(--kp-primary-soft);
      color: var(--kp-primary);
    }

    &__title {
      flex: 1 1 auto;
      min-width: 0;

      p {
        margin: 2px 0 0;
        font-size: 12.5px;
        color: var(--kp-text-muted);
        overflow-wrap: anywhere;
      }
    }

    &__name {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;

      strong {
        font-size: 15px;
        color: var(--kp-text);
      }
    }

    &__actions {
      display: flex;
      flex: 0 0 auto;
      flex-wrap: wrap;
      align-items: center;
      justify-content: flex-end;
      gap: 4px 8px;

      .el-button {
        margin: 0;
      }
    }

    &__cells {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(118px, 1fr));
      gap: 10px 16px;
      margin: 0;
      padding-top: 12px;
      border-top: 1px solid var(--kp-divider);
    }
  }

  .limit-cell {
    min-width: 0;

    dt {
      font-size: 11.5px;
      color: var(--kp-text-muted);
    }

    dd {
      margin: 2px 0 0;
      font-size: 13.5px;
      font-weight: 600;
      color: var(--kp-text);
      overflow-wrap: anywhere;
    }

    &.is-changed dd {
      color: var(--kp-primary);
    }
  }

  .limits-note {
    margin: 0;
    font-size: 12px;
    color: var(--kp-text-muted);
  }

  @media (max-width: 640px) {
    .limit-card__head {
      flex-wrap: wrap;
    }

    .limit-card__actions {
      width: 100%;
      justify-content: flex-start;
    }
  }
</style>
