<script setup lang="ts">
  /**
   * 出站代理页顶部的状态条：直连代理、Mihomo 组件与 TLS 组件各占一格。
   */
  import { computed } from 'vue';
  import { t } from '@/i18n';
  import type { ProxyCatalog } from '@/api/proxyChannels';
  import { runtimeFailureMessage } from '@/utils/egressDiagnostics';
  import { formatRelative } from './helpers';

  const props = defineProps<{ catalog: ProxyCatalog }>();

  const runtime = computed(() => props.catalog.runtime);
  const failure = computed(() => runtimeFailureMessage(runtime.value));
  /** 需要 Mihomo 的节点数；组件未启用时这些节点都不能使用 */
  const mihomoNodes = computed(
    () => props.catalog.channels.filter((node) => node.route === 'MIHOMO').length,
  );

  type Tone = 'on' | 'warn' | 'bad' | 'off';
  const mihomoTone = computed<Tone>(() => {
    if (!props.catalog.mihomoEnabled) return 'off';
    if (runtime.value.controllerReachable === false) return 'bad';
    if (runtime.value.controllerReachable === null || failure.value) return 'warn';
    return 'on';
  });
  const controllerText = computed(() => {
    if (runtime.value.controllerReachable === true) return t('控制端可连接');
    if (runtime.value.controllerReachable === false) return t('控制端无法连接');
    return t('尚未检测控制端');
  });
</script>

<template>
  <section class="status-wrap" :aria-label="t('出站代理组件状态')">
    <div class="status-strip">
      <div class="status-cell surface-card">
        <div class="status-cell__head">
          <i class="status-dot is-on" aria-hidden="true" />
          <strong>{{ t('直连代理') }}</strong>
          <span class="status-cell__state">{{ t('始终可用') }}</span>
        </div>
        <p class="status-cell__note">
          {{ t('HTTP / HTTPS / SOCKS5 节点，无需 Mihomo。') }}
        </p>
      </div>

      <div class="status-cell status-cell--mihomo surface-card">
        <div class="status-cell__head">
          <i class="status-dot" :class="`is-${mihomoTone}`" aria-hidden="true" />
          <strong>Mihomo</strong>
          <span class="status-cell__state">
            {{ catalog.mihomoEnabled ? t('已启用') : t('未启用') }}
          </span>
        </div>
        <template v-if="catalog.mihomoEnabled">
          <p class="status-cell__note">
            {{ controllerText }} · {{ t('活动节点 {p0}', { p0: runtime.activeNodes }) }} ·
            {{
              runtime.lastActivatedAt
                ? t('上次加载 {p0}', { p0: formatRelative(runtime.lastActivatedAt) })
                : t('尚未加载节点')
            }}
          </p>
          <p v-if="failure" class="status-cell__note is-danger" role="status">
            {{ failure }}
            <span v-if="runtime.lastFailureAt" class="status-cell__time">
              ({{ formatRelative(runtime.lastFailureAt) }})
            </span>
          </p>
        </template>
        <template v-else>
          <p class="status-cell__note is-warning">
            {{
              mihomoNodes > 0
                ? t('需要 Mihomo 的 {p0} 个节点暂时无法使用。', { p0: mihomoNodes })
                : t('需要 Mihomo 的节点（VLESS、Hysteria2、TUIC 等）暂时无法使用。')
            }}
          </p>
          <p class="status-cell__note">
            {{
              t('启用：部署 Mihomo 可选组件，设置 STARRY_EGRESS_MIHOMO_ENABLED=true 并重启后端。')
            }}
          </p>
        </template>
      </div>

      <div class="status-cell surface-card">
        <div class="status-cell__head">
          <i
            class="status-dot"
            :class="catalog.tlsEnabled ? 'is-on' : 'is-off'"
            aria-hidden="true"
          />
          <strong>{{ t('TLS 组件') }}</strong>
          <span class="status-cell__state">
            {{ catalog.tlsEnabled ? t('已启用') : t('未启用') }}
          </span>
        </div>
        <div v-if="catalog.tlsEnabled && catalog.tlsProfiles.length" class="status-cell__tags">
          <el-tag v-for="profile in catalog.tlsProfiles" :key="profile" size="small" effect="plain">
            {{ profile }}
          </el-tag>
        </div>
        <p v-else-if="catalog.tlsEnabled" class="status-cell__note">
          {{ t('未提供可选的 TLS 指纹。') }}
        </p>
        <p v-else class="status-cell__note">
          {{ t('Anthropic 账号的 TLS 指纹需要启用 TLS 组件。') }}
        </p>
      </div>
    </div>
  </section>
</template>

<style scoped lang="scss">
  .status-wrap {
    container-type: inline-size;
  }

  .status-strip {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 2fr) minmax(0, 1fr);
    gap: 12px;
  }

  /* 容器较窄时 Mihomo 独占一行并排到最前，其余两格并排；再窄则逐格堆叠 */
  @container (max-width: 560px) {
    .status-strip {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .status-cell--mihomo {
      grid-column: 1 / -1;
      order: -1;
    }
  }

  @container (max-width: 400px) {
    .status-strip {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  .status-cell {
    display: flex;
    flex-direction: column;
    gap: 6px;
    min-width: 0;
    padding: 12px 14px;

    &__head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 2px 8px;
      min-width: 0;

      strong {
        font-size: 14px;
        color: var(--kp-text);
      }
    }

    &__state {
      margin-left: auto;
      font-size: 12px;
      color: var(--kp-text-secondary);
      white-space: nowrap;
    }

    &__note {
      margin: 0;
      font-size: 12px;
      line-height: 1.5;
      color: var(--kp-text-muted);
      overflow-wrap: anywhere;

      &.is-warning {
        color: var(--el-color-warning);
      }

      &.is-danger {
        color: var(--el-color-danger);
      }
    }

    &__time {
      white-space: nowrap;
      opacity: 0.8;
    }

    &__tags {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }
  }

  .status-dot {
    flex: 0 0 auto;
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--kp-text-muted);

    &.is-on {
      background: var(--kp-success);
    }

    &.is-warn {
      background: var(--kp-warning);
    }

    &.is-bad {
      background: var(--kp-danger);
    }
  }
</style>
