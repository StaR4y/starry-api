<script setup lang="ts">
  /**
   * 路由与限制（默认折叠）。
   *
   * 新建：默认使用管理员为该账号类别配置的默认值（只读展示）；「自定义此账号」后才能修改，
   * 也只有这时才会提交 allocationPolicy。编辑：显示运行状态和账号已保存的值，可恢复为默认值。
   */
  import { computed } from 'vue';
  import { useRouter } from 'vue-router';
  import { t } from '@/i18n';
  import AccountRuntimeState from './AccountRuntimeState.vue';
  import FormSection from './FormSection.vue';
  import RoutingPolicyFields from './RoutingPolicyFields.vue';
  import { isAnthropicProvider } from './accountTypes';
  import { useAccountFormContext } from './context';
  import { lifecycleLabel } from './lifecycle';
  import { categoryLabel, describePolicy } from './routingPolicy';

  const open = defineModel<boolean>('open', { default: false });

  const { mode, form, routing } = useAccountFormContext();
  const router = useRouter();

  const anthropic = computed(() => isAnthropicProvider(form.provider));
  const category = computed(() =>
    routing.resolved.value ? categoryLabel(routing.resolved.value.category) : '',
  );
  const defaultRows = computed(() =>
    routing.resolved.value ? describePolicy(routing.resolved.value.policy, anthropic.value) : [],
  );
  const limitsHref = computed(() =>
    router.hasRoute('settings-account-limits')
      ? router.resolve({ name: 'settings-account-limits' }).href
      : '',
  );
  const summary = computed(() => {
    if (mode.value === 'edit') {
      return routing.runtime.value ? lifecycleLabel(routing.runtime.value.lifecycleState) : '';
    }
    if (routing.customize.value) return t('已自定义');
    return category.value ? t('使用默认值 · {p0}', { p0: category.value }) : '';
  });
  /** 编辑时账号策略尚未返回 */
  const busy = computed(
    () =>
      routing.loading.value ||
      (mode.value === 'edit' && !routing.runtime.value && !routing.loadFailed.value),
  );
  const concurrencyLimit = computed(() => routing.values.value?.safeConcurrency ?? null);
</script>

<template>
  <FormSection v-model:open="open" collapsible :title="t('路由与限制')">
    <template #summary>{{ summary }}</template>

    <div v-loading="busy">
      <!-- 新建：默认值 / 自定义 -->
      <template v-if="mode === 'create'">
        <div class="routing-switch">
          <el-switch
            :model-value="routing.customize.value"
            :disabled="!routing.resolved.value"
            :aria-label="t('自定义此账号')"
            @change="(on: string | number | boolean) => routing.setCustomize(!!on)"
          />
          <span class="routing-switch__text">
            <strong>{{ t('自定义此账号') }}</strong>
            <small>{{ t('关闭时，新账号使用其账号类别的默认值。') }}</small>
          </span>
        </div>
      </template>

      <!-- 类别与默认值入口 -->
      <p class="routing-category">
        <template v-if="routing.resolved.value">
          {{ t('账号类别：{p0}', { p0: category }) }}
          <el-link
            v-if="limitsHref"
            type="primary"
            :href="limitsHref"
            target="_blank"
            rel="noopener"
          >
            {{ t('修改类别默认值') }}
          </el-link>
        </template>
        <template v-else-if="routing.resolving.value">{{ t('正在读取默认值') }}</template>
        <template v-else-if="routing.resolveFailed.value">
          {{ t('无法读取默认值') }}
          <el-button link type="primary" @click="routing.resolveNow()">{{ t('重试') }}</el-button>
        </template>
      </p>

      <!-- 新建且未自定义：只读展示 -->
      <dl
        v-if="mode === 'create' && !routing.customize.value && defaultRows.length"
        class="defaults"
      >
        <div v-for="row in defaultRows" :key="row.key" class="defaults__item">
          <dt>{{ row.label }}</dt>
          <dd>{{ row.value }}</dd>
        </div>
      </dl>

      <!-- 可编辑的值 -->
      <template v-if="routing.values.value">
        <AccountRuntimeState
          v-if="mode === 'edit' && routing.runtime.value"
          :runtime="routing.runtime.value"
          :concurrency-limit="concurrencyLimit"
        />

        <div class="routing-actions">
          <el-button
            link
            type="primary"
            :loading="routing.resolving.value"
            @click="routing.restoreDefaults()"
          >
            {{ t('恢复为默认值') }}
          </el-button>
          <span v-if="routing.restored.value" class="routing-actions__note">
            {{ t('已填入默认值，保存后生效。') }}
          </span>
          <span v-else-if="routing.categoryMoved.value" class="routing-actions__note is-warn">
            {{ t('账号类别已变化，已保留你修改过的值；可恢复为新类别的默认值。') }}
          </span>
        </div>

        <RoutingPolicyFields />
      </template>
      <el-empty
        v-else-if="mode === 'edit' && routing.loadFailed.value"
        :image-size="72"
        :description="t('暂无分配策略')"
      />
    </div>
  </FormSection>
</template>

<style scoped lang="scss">
  .routing-switch {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 12px;

    &__text {
      display: flex;
      flex-direction: column;
      gap: 2px;

      strong {
        color: var(--kp-text);
        font-size: 13px;
      }

      small {
        color: var(--kp-text-muted);
        font-size: 12px;
      }
    }
  }

  .routing-category {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 4px 12px;
    margin: 0 0 12px;
    color: var(--kp-text-secondary);
    font-size: 12px;
  }

  .defaults {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 8px;
    margin: 0 0 8px;

    &__item {
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
      padding: 10px 12px;
      border: 1px solid var(--kp-divider);
      border-radius: 8px;

      dt {
        color: var(--kp-text-muted);
        font-size: 12px;
      }

      dd {
        margin: 0;
        overflow: hidden;
        color: var(--kp-text);
        font-size: 14px;
        font-weight: 600;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }
  }

  .routing-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 4px 12px;
    margin-bottom: 10px;

    &__note {
      color: var(--kp-text-muted);
      font-size: 12px;

      &.is-warn {
        color: var(--kp-warning);
      }
    }
  }
</style>
