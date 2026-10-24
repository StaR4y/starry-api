<script setup lang="ts">
  /**
   * 出站代理节点选择器（账号表单与 ChatGPT 授权共用）。
   *
   * v-model 是节点 ID，空字符串表示不使用代理。选项按手动节点 / 订阅分组，
   * 每项显示线路类型、协议、状态和最近一次出口探测；不可用的节点禁用并说明原因。
   * 目录由调用方加载并传入，这样同一份目录还能用于判断 TLS 组件等其他能力。
   */
  import { computed } from 'vue';
  import { useRouter } from 'vue-router';
  import { Refresh } from '@element-plus/icons-vue';
  import { t } from '@/i18n';
  import type { ProxyCatalog, ProxyChannel } from '@/api/proxyChannels';
  import ProxyNodeMeta from './account-form/ProxyNodeMeta.vue';
  import { groupChannels, nodeBlocker } from './account-form/proxyNodes';

  const props = withDefaults(
    defineProps<{
      modelValue: string;
      catalog: ProxyCatalog | null;
      loading?: boolean;
      /** 目录加载失败 */
      failed?: boolean;
      disabled?: boolean;
      /** 「不使用代理」选项的文字，例如重新授权时改为「沿用账号当前设置」 */
      emptyLabel?: string;
      showManageLink?: boolean;
    }>(),
    { loading: false, failed: false, disabled: false, emptyLabel: '', showManageLink: true },
  );

  const emit = defineEmits<{
    (e: 'update:modelValue', value: string): void;
    (e: 'refresh'): void;
  }>();

  const router = useRouter();

  /** el-select 把空字符串当作「未选择」，所以用占位值代表直连选项 */
  const DIRECT = '__direct__';

  const selected = computed({
    get: () => props.modelValue || DIRECT,
    set: (value: string) => emit('update:modelValue', value === DIRECT ? '' : value),
  });

  const directLabel = computed(() => props.emptyLabel || t('直连（不使用代理）'));
  const mihomoEnabled = computed(() => props.catalog?.mihomoEnabled ?? false);
  const groups = computed(() => groupChannels(props.catalog));
  const selectedNode = computed<ProxyChannel | null>(
    () => props.catalog?.channels.find((node) => node.channelId === props.modelValue) ?? null,
  );
  /** 已绑定的节点不在目录里（目录未加载或节点被清理）时，仍要显示当前值 */
  const orphanValue = computed(() =>
    props.modelValue && !selectedNode.value && !props.loading ? props.modelValue : '',
  );
  const selectedBlocker = computed(() =>
    selectedNode.value ? nodeBlocker(selectedNode.value, mihomoEnabled.value) : null,
  );
  const isEmptyCatalog = computed(
    () => !!props.catalog && !props.failed && props.catalog.channels.length === 0,
  );

  function blockerOf(node: ProxyChannel) {
    return nodeBlocker(node, mihomoEnabled.value);
  }

  const manageHref = computed(() =>
    router.hasRoute('settings-proxy-channels')
      ? router.resolve({ name: 'settings-proxy-channels' }).href
      : '',
  );
</script>

<template>
  <div class="proxy-select">
    <div class="proxy-select__row">
      <el-select
        v-model="selected"
        class="proxy-select__control"
        filterable
        :loading="loading"
        :disabled="disabled"
        popper-class="proxy-select-popper"
      >
        <el-option :value="DIRECT" :label="directLabel" />
        <el-option v-if="orphanValue" :value="orphanValue" :label="orphanValue" disabled />
        <el-option-group v-for="group in groups" :key="group.key" :label="group.label">
          <el-option
            v-for="node in group.nodes"
            :key="node.channelId"
            :value="node.channelId"
            :label="`${node.name} · ${node.protocol}`"
            :disabled="!!blockerOf(node)"
          >
            <div class="proxy-option">
              <div class="proxy-option__head">
                <span class="proxy-option__name">{{ node.name }}</span>
                <span v-if="blockerOf(node)" class="proxy-option__reason">
                  {{ blockerOf(node) }}
                </span>
              </div>
              <ProxyNodeMeta :node="node" :mihomo-enabled="mihomoEnabled" />
            </div>
          </el-option>
        </el-option-group>
      </el-select>
      <el-tooltip :content="t('刷新')" placement="top">
        <el-button
          class="proxy-select__refresh"
          :icon="Refresh"
          :loading="loading"
          :disabled="disabled"
          :aria-label="t('刷新')"
          @click="emit('refresh')"
        />
      </el-tooltip>
    </div>

    <div v-if="selectedNode" class="proxy-select__detail">
      <ProxyNodeMeta :node="selectedNode" :mihomo-enabled="mihomoEnabled" />
      <p v-if="selectedBlocker" class="proxy-select__warn">
        {{
          t('当前节点不可用：{p0}。使用它的账号请求会失败，并转给其他账号。', {
            p0: selectedBlocker,
          })
        }}
      </p>
    </div>
    <p v-else-if="failed" class="proxy-select__warn">
      {{ t('代理节点加载失败') }}
      <el-button link type="primary" @click="emit('refresh')">{{ t('重试') }}</el-button>
    </p>
    <p v-else-if="isEmptyCatalog" class="proxy-select__hint">
      {{ t('还没有可用的代理节点，请先在出站代理设置中添加。') }}
    </p>

    <el-link
      v-if="showManageLink && manageHref"
      class="proxy-select__manage"
      type="primary"
      :href="manageHref"
      target="_blank"
      rel="noopener"
    >
      {{ t('管理出站代理') }}
    </el-link>
  </div>
</template>

<style scoped lang="scss">
  .proxy-select {
    width: 100%;
    min-width: 0;

    &__row {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    &__control {
      flex: 1;
      min-width: 0;
    }

    &__refresh {
      flex: 0 0 auto;
    }

    &__detail {
      margin-top: 8px;
    }

    &__hint,
    &__warn {
      margin: 8px 0 0;
      font-size: 12px;
      line-height: 1.5;
    }

    &__hint {
      color: var(--kp-text-muted);
    }

    &__warn {
      color: var(--kp-warning);
    }

    &__manage {
      margin-top: 8px;
      font-size: 12px;
    }
  }

  .proxy-option {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
    padding: 2px 0;

    &__head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 12px;
      min-width: 0;
    }

    &__name {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &__reason {
      flex: 0 0 auto;
      color: var(--kp-warning);
      font-size: 12px;
    }
  }
</style>

<style lang="scss">
  /* 下拉层挂在 body 上，需要全局样式；选项要能容纳两行内容 */
  .proxy-select-popper .el-select-dropdown__item {
    height: auto;
    min-height: 34px;
    padding-top: 6px;
    padding-bottom: 6px;
    padding-right: 40px;
    line-height: 1.5;
  }
</style>
