<script setup lang="ts">
  /**
   * 编辑某一类账号新建时的初始路由限制。
   * 约束与后端一致：平均每分钟请求数为 0（不限速）时突发容量必须为 0，否则突发容量至少为 1；
   * 窗口内不同会话上限与 TLS 指纹只对 Anthropic 类账号生效。
   */
  import { computed, reactive, ref, watch } from 'vue';
  import { t } from '@/i18n';
  import type { AllocationCategoryDefaults, AllocationPolicy } from '@/api/allocationDefaults';
  import {
    POLICY_RANGES,
    categoryMeta,
    policyFieldLabel,
    type NumericPolicyKey,
  } from './allocationMeta';

  type Draft = Omit<AllocationPolicy, NumericPolicyKey> &
    Record<NumericPolicyKey, number | undefined>;

  const props = defineProps<{
    modelValue: boolean;
    category: AllocationCategoryDefaults | null;
    tlsEnabled: boolean;
    tlsProfiles: string[];
    saving: boolean;
    /** 后端拒绝时的说明（已翻译） */
    serverError: string;
  }>();
  const emit = defineEmits<{
    'update:modelValue': [value: boolean];
    save: [policy: AllocationPolicy];
  }>();

  const draft = reactive<Draft>({
    safeConcurrency: 0,
    safeRpm: 0,
    burstCapacity: 0,
    maxSessions: 0,
    sessionIdleSeconds: 600,
    cooldownSeconds: 0,
    maxInflightTokens: 1_000_000,
    allocationPriority: 100,
    tlsProfile: '',
  });
  const localError = ref('');
  /** 关闭限速前的突发容量，重新开启限速时恢复 */
  let lastBurst = 0;

  const anthropic = computed(() => !!props.category?.anthropicSettings);
  const title = computed(() =>
    props.category
      ? t('编辑「{p0}」的初始限制', { p0: categoryMeta(props.category.category).title })
      : '',
  );

  watch(
    () => [props.modelValue, props.category?.category] as const,
    ([open]) => {
      if (!open || !props.category) return;
      lastBurst = 0;
      localError.value = '';
      Object.assign(draft, props.category.configured ?? props.category.effective);
    },
    { immediate: true },
  );

  // 不限速时没有突发容量；重新限速时必须有突发容量
  watch(
    () => draft.safeRpm,
    (rpm) => {
      if (rpm === 0) {
        if (draft.burstCapacity) lastBurst = draft.burstCapacity;
        draft.burstCapacity = 0;
      } else if (typeof rpm === 'number' && !draft.burstCapacity) {
        draft.burstCapacity = lastBurst || props.category?.builtIn.burstCapacity || 4;
      }
    },
  );
  watch(draft, () => (localError.value = ''), { deep: true });

  const tlsOptions = computed(() => {
    const options = [{ value: '', label: t('默认（不使用）') }];
    if (props.tlsEnabled) {
      for (const profile of props.tlsProfiles) {
        if (profile) options.push({ value: profile, label: profile });
      }
    }
    const current = draft.tlsProfile;
    if (current && !options.some((option) => option.value === current)) {
      options.push({ value: current, label: t('{p0}（当前未提供）', { p0: current }) });
    }
    return options;
  });

  function range(key: NumericPolicyKey) {
    return { min: POLICY_RANGES[key][0], max: POLICY_RANGES[key][1] };
  }

  function validate(): AllocationPolicy | null {
    const numbers = {} as Record<NumericPolicyKey, number>;
    for (const key of Object.keys(POLICY_RANGES) as NumericPolicyKey[]) {
      const value = draft[key];
      const [min, max] = POLICY_RANGES[key];
      if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max) {
        localError.value = t('{p0} 必须在 {p1} 到 {p2} 之间。', {
          p0: policyFieldLabel(key),
          p1: min,
          p2: max,
        });
        return null;
      }
      numbers[key] = value;
    }
    if (numbers.safeRpm > 0 && numbers.burstCapacity < 1) {
      localError.value = t('设置了平均每分钟请求数后，突发容量必须大于 0。');
      return null;
    }
    return {
      ...numbers,
      burstCapacity: numbers.safeRpm === 0 ? 0 : numbers.burstCapacity,
      maxSessions: anthropic.value ? numbers.maxSessions : 0,
      tlsProfile: anthropic.value ? draft.tlsProfile : '',
    };
  }

  function submit() {
    const policy = validate();
    if (policy) emit('save', policy);
  }
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    append-to-body
    :title="title"
    width="min(640px, 94vw)"
    :close-on-click-modal="!saving"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-position="top" class="limits-form" @submit.prevent="submit">
      <el-form-item :label="policyFieldLabel('allocationPriority')">
        <el-input-number
          v-model="draft.allocationPriority"
          v-bind="range('allocationPriority')"
          controls-position="right"
          class="w-full"
        />
        <p class="field-hint">{{ t('数值越小，账号越优先。') }}</p>
      </el-form-item>
      <el-form-item :label="t('并发上限（0 为不限）')">
        <el-input-number
          v-model="draft.safeConcurrency"
          v-bind="range('safeConcurrency')"
          controls-position="right"
          class="w-full"
        />
      </el-form-item>
      <el-form-item :label="t('平均每分钟请求数（0 为不限）')">
        <el-input-number
          v-model="draft.safeRpm"
          v-bind="range('safeRpm')"
          controls-position="right"
          class="w-full"
        />
      </el-form-item>
      <el-form-item :label="t('突发容量')">
        <el-input-number
          v-model="draft.burstCapacity"
          :min="draft.safeRpm ? 1 : 0"
          :max="POLICY_RANGES.burstCapacity[1]"
          :disabled="!draft.safeRpm"
          controls-position="right"
          class="w-full"
        />
        <p class="field-hint">
          {{
            draft.safeRpm ? t('短时间内允许超过平均速率的请求数。') : t('不限速时没有突发容量。')
          }}
        </p>
      </el-form-item>
      <template v-if="anthropic">
        <el-form-item :label="t('窗口内不同会话上限（0 为不限）')">
          <el-input-number
            v-model="draft.maxSessions"
            v-bind="range('maxSessions')"
            controls-position="right"
            class="w-full"
          />
        </el-form-item>
        <el-form-item :label="t('会话窗口（秒）')">
          <el-input-number
            v-model="draft.sessionIdleSeconds"
            v-bind="range('sessionIdleSeconds')"
            controls-position="right"
            class="w-full"
          />
        </el-form-item>
      </template>
      <el-form-item :label="t('冷却时间（秒，0 为自动）')">
        <el-input-number
          v-model="draft.cooldownSeconds"
          v-bind="range('cooldownSeconds')"
          controls-position="right"
          class="w-full"
        />
      </el-form-item>
      <el-form-item :label="t('最大在途令牌数')">
        <el-input-number
          v-model="draft.maxInflightTokens"
          v-bind="range('maxInflightTokens')"
          :step="10000"
          controls-position="right"
          class="w-full"
        />
      </el-form-item>
      <el-form-item v-if="anthropic" :label="t('TLS 指纹')">
        <el-select v-model="draft.tlsProfile" class="w-full">
          <el-option
            v-for="option in tlsOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <p v-if="!tlsEnabled" class="field-hint">
          {{ t('TLS 组件未启用，只能选择默认；启用 STARRY_EGRESS_TLS_ENABLED 后可选择指纹。') }}
        </p>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="localError || serverError"
      class="limits-alert"
      type="error"
      show-icon
      :closable="false"
      :title="localError || serverError"
    />

    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">
        {{ t('取消') }}
      </el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ t('保存') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
  .limits-form {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0 20px;

    :deep(.el-form-item) {
      margin-bottom: 16px;
    }

    :deep(.el-form-item__label) {
      font-size: 12.5px;
      font-weight: 600;
      color: var(--kp-text-secondary);
    }
  }

  .w-full {
    width: 100%;
  }

  .field-hint {
    margin: 4px 0 0;
    font-size: 11.5px;
    line-height: 1.4;
    color: var(--kp-text-muted);
  }

  .limits-alert {
    margin-top: 4px;
  }

  @media (max-width: 560px) {
    .limits-form {
      grid-template-columns: 1fr;
    }
  }
</style>
