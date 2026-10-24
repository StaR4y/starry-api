import { computed, onBeforeUnmount, ref, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import { t } from '@/i18n';
import { accountsApi } from '@/api';
import {
  allocationDefaultsApi,
  type AllocationPolicy,
  type AllocationResolveResult,
} from '@/api/allocationDefaults';
import type { AccountAllocationPolicy, UpdateAccountAllocationPolicyRequest } from '@/types/api';
import {
  burstProblem,
  clonePolicy,
  diffPolicy,
  fullPolicy,
  policyOfAccount,
} from './routingPolicy';

export interface RoutingIdentity {
  provider: string;
  authMethod: string;
  baseUrl: string;
}

const RESOLVE_DEBOUNCE_MS = 350;

/**
 * 「路由与限制」的状态。
 *
 * 新建：默认「使用默认值」，只读展示 resolve 得到的类别默认值；打开「自定义此账号」后
 * 才有可编辑的值，也只有这时才会提交 allocationPolicy。
 * 编辑：载入账号已保存的策略，只提交被修改的字段；「恢复为默认值」按当前身份重新 resolve。
 */
export function useAccountRouting(options: {
  mode: Ref<'create' | 'edit'>;
  identity: () => RoutingIdentity;
  isAnthropic: () => boolean;
}) {
  const resolved = ref<AllocationResolveResult | null>(null);
  const resolving = ref(false);
  const resolveFailed = ref(false);
  /** 仅新建：是否自定义此账号 */
  const customize = ref(false);
  /** 当前可编辑的值；新建且未自定义时为 null */
  const values = ref<AllocationPolicy | null>(null);
  /** 仅编辑：账号已保存的策略，用来只提交被修改的字段 */
  const baseline = ref<AllocationPolicy | null>(null);
  const runtime = ref<AccountAllocationPolicy | null>(null);
  const loading = ref(false);
  const loadFailed = ref(false);
  /** 管理员改过可编辑的值 */
  const dirty = ref(false);
  /** 刚刚用类别默认值填入过 */
  const restored = ref(false);
  /** 自定义期间账号类别发生了变化，而管理员已改过值，所以没有自动换成新类别的默认值 */
  const categoryMoved = ref(false);
  /** RPM 关闭前的突发容量，重新启用 RPM 时恢复 */
  let rememberedBurst = 0;
  let resolveRequest = 0;
  let loadRequest = 0;
  let timer: number | undefined;

  const isEdit = computed(() => options.mode.value === 'edit');
  const editable = computed(() => values.value !== null);

  function clearTimer() {
    if (timer !== undefined) {
      window.clearTimeout(timer);
      timer = undefined;
    }
  }

  function identityPayload() {
    const identity = options.identity();
    return {
      provider: identity.provider.trim() || undefined,
      authMethod: identity.authMethod.trim() || undefined,
      baseUrl: identity.baseUrl.trim() || undefined,
    };
  }

  /** 向后端请求「这样的账号落入哪个类别、起始策略是什么」 */
  async function resolveNow(): Promise<AllocationResolveResult | null> {
    clearTimer();
    const request = ++resolveRequest;
    resolving.value = true;
    resolveFailed.value = false;
    try {
      const result = await allocationDefaultsApi.resolve(identityPayload());
      if (request !== resolveRequest) return null;
      const previous = resolved.value?.category;
      resolved.value = result;
      if (!isEdit.value && customize.value) {
        if (!dirty.value) values.value = clonePolicy(result.policy);
        else if (previous && previous !== result.category) categoryMoved.value = true;
      }
      return result;
    } catch {
      if (request === resolveRequest) resolveFailed.value = true;
      return null;
    } finally {
      if (request === resolveRequest) resolving.value = false;
    }
  }

  /** 账号身份（类型、认证方式、Base URL）变化后稍作等待再 resolve */
  function scheduleResolve() {
    clearTimer();
    timer = window.setTimeout(() => void resolveNow(), RESOLVE_DEBOUNCE_MS);
  }

  function setCustomize(on: boolean) {
    customize.value = on;
    dirty.value = false;
    restored.value = false;
    categoryMoved.value = false;
    values.value = on && resolved.value ? clonePolicy(resolved.value.policy) : null;
  }

  async function loadForEdit(accountId: string) {
    const request = ++loadRequest;
    loading.value = true;
    loadFailed.value = false;
    try {
      const policy = await accountsApi.allocationPolicy(accountId);
      if (request !== loadRequest) return;
      runtime.value = policy;
      baseline.value = policyOfAccount(policy);
      values.value = clonePolicy(baseline.value);
    } catch {
      if (request === loadRequest) {
        loadFailed.value = true;
        ElMessage.error(t('账号分配策略加载失败'));
      }
    } finally {
      if (request === loadRequest) loading.value = false;
    }
  }

  /** 用该类别当前的默认值填入可编辑的值；编辑时仍需点保存才生效 */
  async function restoreDefaults() {
    const result = await resolveNow();
    if (!result) return;
    values.value = clonePolicy(result.policy);
    dirty.value = false;
    categoryMoved.value = false;
    restored.value = true;
  }

  function markDirty() {
    dirty.value = true;
    restored.value = false;
  }

  /** 修改每分钟请求数后保持「突发容量为 0 当且仅当 RPM 为 0」 */
  function syncBurstWithRpm() {
    const current = values.value;
    if (!current) return;
    if (current.safeRpm === 0) {
      if (current.burstCapacity > 0) rememberedBurst = current.burstCapacity;
      current.burstCapacity = 0;
    } else if (current.burstCapacity < 1) {
      current.burstCapacity = Math.max(1, rememberedBurst);
    }
  }

  /** 提交前校验；返回问题说明，通过则返回 null。编辑时只在 RPM / 突发容量被改过时检查 */
  function validate(): string | null {
    const current = values.value;
    if (!current) return null;
    if (isEdit.value && baseline.value) {
      const untouched =
        current.safeRpm === baseline.value.safeRpm &&
        current.burstCapacity === baseline.value.burstCapacity;
      if (untouched) return null;
    }
    return burstProblem(current);
  }

  /** 提交的 allocationPolicy：新建仅在自定义时提交，编辑仅提交被修改的字段 */
  function buildPayload(): UpdateAccountAllocationPolicyRequest | undefined {
    const current = values.value;
    if (!current) return undefined;
    const anthropic = options.isAnthropic();
    if (!isEdit.value) return customize.value ? fullPolicy(current, anthropic) : undefined;
    if (!baseline.value) return undefined;
    const changes = diffPolicy(baseline.value, current, anthropic);
    return Object.keys(changes).length ? changes : undefined;
  }

  function reset() {
    clearTimer();
    resolveRequest += 1;
    loadRequest += 1;
    resolved.value = null;
    resolving.value = false;
    resolveFailed.value = false;
    customize.value = false;
    values.value = null;
    baseline.value = null;
    runtime.value = null;
    loading.value = false;
    loadFailed.value = false;
    dirty.value = false;
    restored.value = false;
    categoryMoved.value = false;
    rememberedBurst = 0;
  }

  onBeforeUnmount(clearTimer);

  return {
    resolved,
    resolving,
    resolveFailed,
    customize,
    values,
    baseline,
    runtime,
    loading,
    loadFailed,
    dirty,
    restored,
    categoryMoved,
    editable,
    resolveNow,
    scheduleResolve,
    setCustomize,
    loadForEdit,
    restoreDefaults,
    markDirty,
    syncBurstWithRpm,
    validate,
    buildPayload,
    reset,
  };
}

export type AccountRouting = ReturnType<typeof useAccountRouting>;
