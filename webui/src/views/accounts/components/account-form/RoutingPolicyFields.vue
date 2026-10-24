<script setup lang="ts">
  /**
   * 单个账号的路由与限制：可编辑的字段。
   * 只在新建「自定义此账号」或编辑已有账号时出现，取值来自类别默认值或账号已保存的策略。
   */
  import { computed } from 'vue';
  import { t } from '@/i18n';
  import { useAccountFormContext } from './context';
  import { isAnthropicProvider } from './accountTypes';
  import { burstProblem } from './routingPolicy';

  const { form, catalog, routing } = useAccountFormContext();

  const values = routing.values;
  const anthropic = computed(() => isAnthropicProvider(form.provider));
  const tlsEnabled = computed(() => catalog.value?.tlsEnabled ?? false);
  /** TLS 组件未启用时只保留已保存的取值，方便查看和清除 */
  const tlsOptions = computed(() => {
    const available = tlsEnabled.value ? (catalog.value?.tlsProfiles ?? []) : [];
    const current = values.value?.tlsProfile;
    return current && !available.includes(current) ? [current, ...available] : available;
  });
  const tlsLocked = computed(() => !tlsEnabled.value && !values.value?.tlsProfile);
  const burstWarning = computed(() => (values.value ? burstProblem(values.value) : null));

  function onRpmChange() {
    routing.syncBurstWithRpm();
    routing.markDirty();
  }
</script>

<template>
  <div v-if="values" class="af-grid">
    <el-form-item :label="t('分配优先级')">
      <el-input-number
        v-model="values.allocationPriority"
        :min="1"
        :max="10000"
        value-on-clear="min"
        controls-position="right"
        class="af-full"
        @change="routing.markDirty()"
      />
      <p class="af-hint">{{ t('数值越小，账号越优先。') }}</p>
    </el-form-item>
    <el-form-item :label="t('并发上限（0 为不限）')">
      <el-input-number
        v-model="values.safeConcurrency"
        :min="0"
        :max="10000"
        value-on-clear="min"
        controls-position="right"
        class="af-full"
        @change="routing.markDirty()"
      />
    </el-form-item>
    <el-form-item :label="t('平均每分钟请求数（0 为不限）')">
      <el-input-number
        v-model="values.safeRpm"
        :min="0"
        :max="1000000"
        value-on-clear="min"
        controls-position="right"
        class="af-full"
        @change="onRpmChange"
      />
    </el-form-item>
    <el-form-item :label="t('突发容量')">
      <el-input-number
        v-model="values.burstCapacity"
        :min="0"
        :max="100000"
        :disabled="values.safeRpm === 0"
        value-on-clear="min"
        controls-position="right"
        class="af-full"
        @change="routing.markDirty()"
      />
      <p v-if="burstWarning" class="af-hint af-hint--warn">{{ burstWarning }}</p>
      <p v-else class="af-hint">{{ t('令牌桶容量：未限制每分钟请求数时为 0，否则至少为 1。') }}</p>
    </el-form-item>
    <el-form-item :label="t('冷却时间（秒，0 为自动）')">
      <el-input-number
        v-model="values.cooldownSeconds"
        :min="0"
        :max="86400"
        value-on-clear="min"
        controls-position="right"
        class="af-full"
        @change="routing.markDirty()"
      />
    </el-form-item>
    <el-form-item :label="t('最大在途令牌数')">
      <el-input-number
        v-model="values.maxInflightTokens"
        :min="1"
        :max="1000000000"
        :step="10000"
        value-on-clear="min"
        controls-position="right"
        class="af-full"
        @change="routing.markDirty()"
      />
    </el-form-item>

    <template v-if="anthropic">
      <el-form-item :label="t('窗口内不同会话上限（0 为不限）')">
        <el-input-number
          v-model="values.maxSessions"
          :min="0"
          :max="100000"
          value-on-clear="min"
          controls-position="right"
          class="af-full"
          @change="routing.markDirty()"
        />
      </el-form-item>
      <el-form-item :label="t('会话窗口（秒）')">
        <el-input-number
          v-model="values.sessionIdleSeconds"
          :min="60"
          :max="86400"
          value-on-clear="min"
          controls-position="right"
          class="af-full"
          @change="routing.markDirty()"
        />
      </el-form-item>
      <el-form-item :label="t('TLS 指纹')">
        <el-select
          v-model="values.tlsProfile"
          clearable
          class="af-full"
          :placeholder="t('关闭')"
          :disabled="tlsLocked"
          @change="routing.markDirty()"
        >
          <el-option
            v-for="profile in tlsOptions"
            :key="profile"
            :label="profile"
            :value="profile"
          />
        </el-select>
        <p v-if="!tlsEnabled && values.tlsProfile" class="af-hint af-hint--warn">
          {{ t('TLS 组件未启用，已设置的指纹无法使用，该账号的请求会失败；请清除或启用组件。') }}
        </p>
        <p v-else-if="!tlsEnabled" class="af-hint">{{ t('TLS 组件未启用，暂时无法选择指纹。') }}</p>
      </el-form-item>
    </template>
  </div>
</template>
