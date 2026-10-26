<script setup lang="ts">
  /**
   * 跨协议成员（中转）对话框：把一把 OpenAI 兼容 Key 登记为某个号池的中转，设置它能承载什么、怎样读它的用量，
   * 写映射、验证映射。
   *
   * 保存走 POST /admin/source-tiers/{vendor}/members：只增改这一个成员行，不重发整份配置，请求带读取时的配置
   * 版本（期间被别人改过返回 409，页面重新读取后这里的修改仍在，确认后再保存）。验证向中转发探针，只对已保存的
   * 配置有意义，所以有未保存的修改时不能验证；验证由运营者付费，同一个账号 30 秒一次，整体最长 4 分钟。
   *
   * 两个缓存口径开关说中转的 prompt_tokens 是否已含缓存读 / 写。「含」是安全的缺省（错配只会少收）；「不含」会把缓存
   * 令牌再加到输入上，配错就多收，所以只有缓存探针看到了证据才能保存：先保存为「含」，带缓存探针验证，再按建议改。
   */
  import { computed, reactive, ref, watch } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { t } from '@/i18n';
  import { ApiError } from '@/utils/http';
  import { accountsApi } from '@/api/accounts';
  import { modelsApi } from '@/api/models';
  import {
    SOURCE_TIER_VERSION_CONFLICT,
    sourceTiersApi,
    type SourceTierMember,
    type SourceTierRuntime,
    type SourceTierUpstreamModel,
    type SourceTierVendor,
    type SourceTierVerifyResult,
  } from '@/api/sourceTiers';
  import type { Account, AccountAllocationPolicy, ManagedModel } from '@/types/api';
  import ProviderBadge from '@/views/accounts/components/ProviderBadge.vue';
  import { shorten } from '@/utils/format';
  import ModelMapEditor from './ModelMapEditor.vue';
  import {
    DEFAULT_DURATION_SECONDS,
    MAX_DURATION_SECONDS,
    MAX_VERIFIED_MAPPINGS,
    MIN_DURATION_SECONDS,
    applyCacheSuggestion,
    cacheCostWarning,
    cacheSuggestion,
    cacheVerdict,
    catalogModelsOf,
    durationNote,
    exclusiveWarning,
    invalidatedMappings,
    limitsNote,
    newRelayDraft,
    pairStatusOf,
    prefillContexts,
    privacyNote,
    relayCandidates,
    relayDraftOf,
    relayErrorMessage,
    relayMemberOf,
    relayProblem,
    relaySignature,
    unprovenCache,
    verifyTargets,
    ENTRY_ENDPOINTS,
    type MappingDraft,
    type RelayDraft,
  } from './xpMeta';

  const props = defineProps<{
    visible: boolean;
    /** 已保存的配置：版本、号池和成员行都取自这里 */
    vendor: SourceTierVendor;
    /** 要编辑的中转的账号；登记新中转时为 null */
    editing: string | null;
    accounts: Account[];
    runtime: SourceTierRuntime | null;
  }>();
  const emit = defineEmits<{
    'update:visible': [value: boolean];
    /** 成员行已保存：上层用返回的配置替换已保存的配置 */
    saved: [vendor: SourceTierVendor];
    /** 一次验证结束，服务器上的验证状态变了：上层重新读取已保存的配置 */
    verified: [];
    /** 保存被 409 版本冲突拒绝：上层重新读取最新配置，这里的修改保留 */
    conflict: [];
  }>();

  const visibleValue = computed({
    get: () => props.visible,
    set: (value: boolean) => emit('update:visible', value),
  });

  const draft = reactive<RelayDraft>(newRelayDraft());
  const baseline = ref('');
  /** 已经保存的这个中转的账号：登记新中转保存之后它也有了，之后的验证和比较都以保存的配置为准 */
  const savedAccountId = ref<string | null>(null);
  const saving = ref(false);
  const saveError = ref('');
  const conflicted = ref(false);

  function assign(next: RelayDraft) {
    Object.assign(draft, next);
  }

  const savedMember = computed<SourceTierMember | null>(() =>
    savedAccountId.value
      ? (props.vendor.members.find(
          (member) => member.accountId === savedAccountId.value && member.protocol,
        ) ?? null)
      : null,
  );
  const saved = computed(() => (savedMember.value ? relayDraftOf(savedMember.value) : null));
  const dirty = computed(() => relaySignature(draft) !== baseline.value);
  const problem = computed(() => relayProblem(draft));
  const tiers = computed(() => [...props.vendor.tiers].sort((a, b) => a.level - b.level));
  const account = computed(() => props.accounts.find((item) => item.accountId === draft.accountId));

  /* ---------- 账号与目录 ---------- */

  const candidates = computed(() =>
    relayCandidates(
      props.accounts,
      props.vendor.members.map((member) => member.accountId),
      '',
    ),
  );
  const models = ref<ManagedModel[]>([]);
  const catalog = computed(() => catalogModelsOf(props.vendor.vendor, models.value));
  let catalogLoaded = false;

  async function loadCatalog() {
    if (catalogLoaded) return;
    try {
      models.value = await modelsApi.list();
      catalogLoaded = true;
    } catch {
      // 只用来给目录模型下拉预填：读不到时仍可手填
    }
  }

  const policy = ref<AccountAllocationPolicy | null>(null);
  async function loadPolicy(accountId: string) {
    policy.value = null;
    if (!accountId) return;
    try {
      const loaded = await accountsApi.allocationPolicy(accountId);
      if (accountId === draft.accountId) policy.value = loaded;
    } catch {
      // 提示而已：读不到就不提示
    }
  }
  watch(
    () => draft.accountId,
    (accountId) => {
      void loadPolicy(accountId);
      upstream.value = [];
      upstreamState.value = 'idle';
      upstreamError.value = '';
    },
  );

  /* ---------- 从上游拉取 ---------- */

  const upstream = ref<SourceTierUpstreamModel[]>([]);
  const upstreamState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const upstreamError = ref('');
  const upstreamTruncated = ref(false);

  async function fetchUpstream() {
    if (!draft.accountId || upstreamState.value === 'loading') return;
    upstreamState.value = 'loading';
    upstreamError.value = '';
    try {
      const listed = await sourceTiersApi.upstreamModels(props.vendor.vendor, draft.accountId);
      upstream.value = listed.models;
      upstreamTruncated.value = listed.truncated;
      upstreamState.value = 'ready';
      // 中转写明了窗口的，给还空着上限的行预填
      draft.mappings = prefillContexts(draft.mappings, catalog.value, listed.models);
    } catch (err) {
      upstreamState.value = 'error';
      upstreamError.value =
        err instanceof ApiError ? relayErrorMessage(err, 'models') : t('服务暂时不可用');
    }
  }

  function setMappings(next: MappingDraft[]) {
    draft.mappings = next;
  }

  /* ---------- 验证 ---------- */

  const cacheProbe = ref(false);
  const verifying = ref(false);
  const verifyError = ref('');
  const verifyResult = ref<SourceTierVerifyResult | null>(null);
  /** 启用的映射多于一次能验证的个数时，管理员选这次验证哪些 */
  const verifyPick = ref<string[]>([]);

  const enabledModels = computed(() => (saved.value ? verifyTargets(saved.value) : []));
  const tooManyToVerify = computed(() => enabledModels.value.length > MAX_VERIFIED_MAPPINGS);
  const verifyModels = computed(() =>
    tooManyToVerify.value
      ? verifyPick.value.filter((id) => enabledModels.value.includes(id))
      : enabledModels.value,
  );
  const verifyBlocked = computed(() => {
    if (!savedMember.value) return t('先保存这个中转，再验证它的映射。');
    if (dirty.value) return t('有未保存的修改。验证的是已保存的配置，请先保存。');
    if (!enabledModels.value.length) return t('没有启用的映射可以验证。');
    if (tooManyToVerify.value && !verifyModels.value.length) {
      return t('启用的映射多于 {p0} 个，请选出这次要验证的。', { p0: MAX_VERIFIED_MAPPINGS });
    }
    if (verifyModels.value.length > MAX_VERIFIED_MAPPINGS) {
      return t('一次最多验证 {p0} 个映射。', { p0: MAX_VERIFIED_MAPPINGS });
    }
    return '';
  });

  async function verify() {
    if (verifyBlocked.value || verifying.value || !savedMember.value) return;
    verifying.value = true;
    verifyError.value = '';
    try {
      verifyResult.value = await sourceTiersApi.verify(props.vendor.vendor, draft.accountId, {
        modelIds: verifyModels.value,
        cacheProbe: cacheProbe.value,
      });
      emit('verified');
    } catch (err) {
      verifyError.value =
        err instanceof ApiError ? relayErrorMessage(err, 'verify') : t('服务暂时不可用');
      // 超时之后服务器可能已经写入了结果
      if (err instanceof ApiError && err.code === 'NETWORK_ERROR') emit('verified');
    } finally {
      verifying.value = false;
    }
  }

  /** 缓存探针给了明确的建议、而且与现在的设置不同 */
  function adoptable(modelId: string) {
    const advice = verifyResult.value?.models.find((model) => model.modelId === modelId)?.cache;
    const suggestion = cacheSuggestion(advice);
    return applyCacheSuggestion(draft, suggestion) !== draft ? suggestion : null;
  }

  function adopt(modelId: string) {
    const advice = verifyResult.value?.models.find((model) => model.modelId === modelId)?.cache;
    assign(applyCacheSuggestion({ ...draft }, cacheSuggestion(advice)));
  }

  /* ---------- 保存 ---------- */

  const willInvalidate = computed(() => invalidatedMappings(savedMember.value ?? undefined, draft));
  const unproven = computed(() => unprovenCache(draft));

  async function save() {
    if (saving.value) return;
    const found = relayProblem(draft);
    if (found) {
      saveError.value = found;
      return;
    }
    saving.value = true;
    saveError.value = '';
    conflicted.value = false;
    try {
      const vendor = await sourceTiersApi.upsertMembers(props.vendor.vendor, {
        expectedVersion: props.vendor.version,
        members: [relayMemberOf(draft)],
      });
      emit('saved', vendor);
      const member = vendor.members.find((candidate) => candidate.accountId === draft.accountId);
      savedAccountId.value = draft.accountId;
      if (member) {
        const next = relayDraftOf(member);
        assign(next);
        baseline.value = relaySignature(next);
      }
      verifyResult.value = null;
      verifyPick.value = verifyTargets(draft).slice(0, MAX_VERIFIED_MAPPINGS);
      ElMessage.success(t('已保存'));
    } catch (err) {
      if (err instanceof ApiError && err.code === SOURCE_TIER_VERSION_CONFLICT) {
        conflicted.value = true;
        emit('conflict');
        saveError.value = t(
          '这个厂商的号池配置刚被其他人修改，已重新读取最新配置。你在这里的修改还在，确认后再保存一次。',
        );
      } else {
        saveError.value = err instanceof ApiError ? relayErrorMessage(err) : t('服务暂时不可用');
      }
    } finally {
      saving.value = false;
    }
  }

  async function beforeClose(done: () => void) {
    if (!dirty.value || saving.value) {
      done();
      return;
    }
    const discard = await ElMessageBox.confirm(
      t('这个中转有尚未保存的修改，关闭后将丢失。'),
      t('关闭中转设置？'),
      {
        type: 'warning',
        confirmButtonText: t('放弃修改'),
        cancelButtonText: t('继续编辑'),
      },
    )
      .then(() => true)
      .catch(() => false);
    if (discard) done();
  }

  /* ---------- 打开 ---------- */

  function open() {
    saveError.value = '';
    conflicted.value = false;
    verifyError.value = '';
    verifyResult.value = null;
    cacheProbe.value = false;
    upstream.value = [];
    upstreamState.value = 'idle';
    upstreamError.value = '';
    policy.value = null;
    const member = props.editing
      ? props.vendor.members.find(
          (candidate) => candidate.accountId === props.editing && candidate.protocol,
        )
      : undefined;
    savedAccountId.value = member ? member.accountId : null;
    const next = member
      ? relayDraftOf(member)
      : newRelayDraft(
          '',
          tiers.value.find((tier) => tier.enabled)?.tierId ?? tiers.value[0]?.tierId ?? '',
        );
    assign(next);
    baseline.value = relaySignature(next);
    verifyPick.value = verifyTargets(next).slice(0, MAX_VERIFIED_MAPPINGS);
    void loadCatalog();
    void loadPolicy(next.accountId);
  }

  watch(
    () => props.visible,
    (shown) => {
      if (shown) open();
    },
    { immediate: true },
  );

  function accountLabel(item: Account) {
    return item.email || item.accountId;
  }

  function tierLabel(level: number, name: string, enabled: boolean) {
    return `L${level} · ${name}${enabled ? '' : t('（已关闭）')}`;
  }

  const costWarning = computed(() => cacheCostWarning(props.vendor.vendor));
  const exclusiveNote = computed(() => exclusiveWarning(draft));
  const limits = computed(() => limitsNote(policy.value));
  const duration = computed(() => durationNote(draft));
  /** 这个厂商有没有这个版本提供的对：没有的话，中转登记了也收不到请求 */
  const anyPairOffered = computed(() =>
    ENTRY_ENDPOINTS.some(
      (entry) => pairStatusOf(props.runtime, props.vendor.vendor, entry) !== 'NOT_OFFERED',
    ),
  );

  const REASONING_OPTIONS = computed(() => [
    { value: 'NONE', label: t('不发送') },
    { value: 'EFFORT', label: t('映射为 reasoning_effort') },
  ]);

  function setDuration(value: number | null | undefined) {
    draft.maxDurationSeconds = typeof value === 'number' ? value : DEFAULT_DURATION_SECONDS;
  }
</script>

<template>
  <el-dialog
    v-model="visibleValue"
    append-to-body
    class="xp-member-dialog"
    :title="savedAccountId ? t('编辑中转') : t('登记中转')"
    width="min(960px, calc(100vw - 24px))"
    destroy-on-close
    :close-on-click-modal="false"
    :before-close="beforeClose"
  >
    <div class="xp-dialog">
      <el-alert
        v-if="saveError"
        :type="conflicted ? 'warning' : 'error'"
        show-icon
        :closable="false"
        :title="saveError"
      />

      <el-alert
        v-if="!anyPairOffered"
        type="info"
        show-icon
        :closable="false"
        :title="
          t(
            '这个版本还没有为这个厂商提供任何（入口，厂商）对：中转可以先登记，但门控打不开，请求暂时不会派给它。',
          )
        "
      />

      <p class="xp-dialog__intro">
        {{
          t(
            '中转是一把 OpenAI 兼容 Key，在这个号池里替这个厂商的模型服务：网关把请求转成 Chat Completions 发给它，再把应答转回客户端的格式，按这个号池的价格计费。',
          )
        }}
      </p>

      <section class="xp-dialog__block">
        <div class="xp-dialog__grid">
          <label class="xp-field">
            <span>{{ t('账号') }}</span>
            <el-select
              v-model="draft.accountId"
              filterable
              :disabled="saving || !!savedAccountId"
              :placeholder="t('选择一把 OpenAI 兼容 Key')"
              :no-data-text="t('没有可登记的账号：只有 OpenAI 兼容 Key 能当中转')"
              :aria-label="t('账号')"
            >
              <el-option
                v-for="item in savedAccountId
                  ? accounts.filter((a) => a.accountId === savedAccountId)
                  : candidates"
                :key="item.accountId"
                :value="item.accountId"
                :label="accountLabel(item)"
              >
                <span class="xp-option">
                  <span>{{ accountLabel(item) }}</span>
                  <small>{{ shorten(item.accountId, 12, 6) }}</small>
                </span>
              </el-option>
            </el-select>
            <small v-if="account" class="xp-field__hint">
              <ProviderBadge
                :provider="account.provider"
                :auth-method="account.authMethod"
                :color="account.badgeColor"
              />
              {{ account.baseUrl || '' }}
            </small>
          </label>

          <label class="xp-field">
            <span>{{ t('号池') }}</span>
            <el-select v-model="draft.tierId" :disabled="saving" :aria-label="t('号池')">
              <el-option
                v-for="tier in tiers"
                :key="tier.tierId"
                :value="tier.tierId"
                :label="tierLabel(tier.level, tier.name, tier.enabled)"
              />
            </el-select>
            <small class="xp-field__hint">
              {{ t('中转只在这个号池服务；价格是标准价乘这个号池的系数。') }}
            </small>
          </label>
        </div>
      </section>

      <section class="xp-dialog__block">
        <h4>{{ t('能力与用量口径') }}</h4>
        <div class="xp-opts">
          <div class="xp-opt surface-card">
            <div>
              <strong>{{ t('专用') }}</strong>
              <p>
                {{
                  t(
                    '默认开启：这把 Key 只服务中转的请求，不再服务 GLM 等未分级的兼容模型，也不作为 Responses 链的 owner。',
                  )
                }}
              </p>
              <p v-if="exclusiveNote" class="is-warning">{{ exclusiveNote }}</p>
            </div>
            <el-switch v-model="draft.exclusive" :disabled="saving" :aria-label="t('专用')" />
          </div>

          <div class="xp-opt surface-card">
            <div>
              <strong>{{ t('图片') }}</strong>
              <p>
                {{
                  t(
                    '中转的这些模型能看图。关闭时图片被丢掉（Kiro 也只取文本），而原生账号能看图的 gpt 类请求带了图就不派给中转。',
                  )
                }}
              </p>
            </div>
            <el-switch v-model="draft.vision" :disabled="saving" :aria-label="t('图片')" />
          </div>

          <div class="xp-opt surface-card">
            <div>
              <strong>json_schema</strong>
              <p>
                {{
                  t(
                    '中转支持 response_format 的 json_schema。关闭时降为 json_object，或（原生账号会照做的 gpt 类请求）不派给中转。',
                  )
                }}
              </p>
            </div>
            <el-switch v-model="draft.jsonSchema" :disabled="saving" aria-label="json_schema" />
          </div>

          <div class="xp-opt surface-card">
            <div>
              <strong>priority</strong>
              <p>
                {{
                  t(
                    '中转支持 service_tier: priority。只有按 fast 计费的 OpenAI 请求会用到；关闭时 fast 请求不派给中转。',
                  )
                }}
              </p>
            </div>
            <el-switch v-model="draft.priorityTier" :disabled="saving" aria-label="priority" />
          </div>

          <div class="xp-opt surface-card">
            <div>
              <strong>cache_control</strong>
              <p>
                {{
                  t(
                    '把客户端消息里的 cache_control 原样发给中转。只在中转认得它时打开，严格的中转会因它回 400。',
                  )
                }}
              </p>
            </div>
            <el-switch
              v-model="draft.cacheControlPassthrough"
              :disabled="saving"
              aria-label="cache_control"
            />
          </div>

          <div class="xp-opt surface-card">
            <div>
              <strong>{{ t('上游流式') }}</strong>
              <p>
                {{
                  t(
                    '用流式向中转取应答（网关缓冲完再合成给客户端），长生成不会被读超时切断。关闭则整体返回，中转要在读超时内给出第一个字节。',
                  )
                }}
              </p>
              <p v-if="duration" class="is-warning">{{ duration }}</p>
            </div>
            <el-switch
              v-model="draft.upstreamStream"
              :disabled="saving"
              :aria-label="t('上游流式')"
            />
          </div>

          <div class="xp-opt surface-card">
            <div>
              <strong>{{ t('思考映射') }}</strong>
              <p>
                {{
                  t(
                    '客户端请求思考时，把预算映射成 reasoning_effort（小于 4k 为 low，小于 16k 为 medium，否则 high）发给中转。中转不认这个参数就选「不发送」。',
                  )
                }}
              </p>
            </div>
            <el-select
              v-model="draft.reasoningMode"
              :disabled="saving"
              class="xp-opt__select"
              :aria-label="t('思考映射')"
            >
              <el-option
                v-for="option in REASONING_OPTIONS"
                :key="option.value"
                :value="option.value"
                :label="option.label"
              />
            </el-select>
          </div>

          <div class="xp-opt surface-card">
            <div>
              <strong>{{ t('单次时限') }}</strong>
              <p>
                {{
                  t(
                    '一次请求等中转多久（{p0} 到 {p1} 秒）。到时还没完整返回，就当中转可能仍在生成：不再派给别的账号，这次请求不收费，由你承担。',
                    { p0: MIN_DURATION_SECONDS, p1: MAX_DURATION_SECONDS },
                  )
                }}
              </p>
            </div>
            <el-input-number
              :model-value="draft.maxDurationSeconds"
              :min="MIN_DURATION_SECONDS"
              :max="MAX_DURATION_SECONDS"
              :step="30"
              :disabled="saving"
              controls-position="right"
              :aria-label="t('单次时限')"
              @update:model-value="setDuration"
            />
          </div>
        </div>

        <div class="xp-caliber surface-card">
          <h5>{{ t('中转的 prompt_tokens 是否已含缓存') }}</h5>
          <p>
            {{
              t(
                '网关按平台口径计费：输入含缓存。「含」表示中转报的 prompt_tokens 已经包括缓存读（写）的令牌；「不含」表示它们另外报，网关会加到输入上。配成「含」的错误只会少收；配成「不含」的错误会多收，所以「不含」要缓存探针的证据才能保存。',
              )
            }}
          </p>
          <div class="xp-caliber__switches">
            <label>
              <el-switch v-model="draft.usageCacheReadInPrompt" :disabled="saving" />
              <span>{{ t('缓存读已含在 prompt_tokens 内') }}</span>
            </label>
            <label>
              <el-switch v-model="draft.usageCacheWriteInPrompt" :disabled="saving" />
              <span>{{ t('缓存写已含在 prompt_tokens 内') }}</span>
            </label>
          </div>
          <p v-if="unproven" class="is-warning">
            {{
              t(
                '「不含」的设置需要缓存探针的证据（{p0}）。先保存为「含」，下面带缓存探针验证，再按探针的建议改。已有这个中转同一模型名的证据时可以直接保存。',
                { p0: unproven === 'both' ? t('读和写') : unproven === 'read' ? t('读') : t('写') },
              )
            }}
          </p>
        </div>
      </section>

      <section class="xp-dialog__block">
        <ModelMapEditor
          :models="draft.mappings"
          :catalog="catalog"
          :upstream="upstream"
          :upstream-state="upstreamState"
          :upstream-error="upstreamError"
          :upstream-truncated="upstreamTruncated"
          :saved="saved"
          :verification="verifyResult?.models ?? null"
          :can-fetch="!!draft.accountId"
          :disabled="saving"
          @update:models="setMappings"
          @fetch="fetchUpstream"
        />
        <p v-if="willInvalidate.length" class="is-warning xp-dialog__note">
          {{
            t('保存会让这些映射变回未验证，需要重新验证：{p0}', {
              p0: willInvalidate.join('、'),
            })
          }}
        </p>
      </section>

      <section class="xp-dialog__block">
        <h4>{{ t('验证') }}</h4>
        <p class="xp-dialog__note">
          {{
            t(
              '用请求真正使用的客户端和解析器向中转发探针：基本问答、函数调用、两轮工具循环，以及（按设置）流式和图片。全部通过的映射才会被请求用到；设置、模型名或账号地址变了，验证自动失效。探针要花中转的令牌，由你承担；同一个账号 30 秒只能验证一次，整体最长 4 分钟。',
            )
          }}
        </p>

        <div v-if="tooManyToVerify" class="xp-verify__pick">
          <span>{{ t('这次验证（最多 {p0} 个）', { p0: MAX_VERIFIED_MAPPINGS }) }}</span>
          <el-checkbox-group
            v-model="verifyPick"
            :max="MAX_VERIFIED_MAPPINGS"
            :disabled="verifying"
          >
            <el-checkbox v-for="id in enabledModels" :key="id" :value="id" :label="id" />
          </el-checkbox-group>
        </div>

        <div class="xp-verify">
          <el-checkbox v-model="cacheProbe" :disabled="verifying">
            {{ t('同时运行缓存探针（每个映射多问两次约 1,200 令牌的提示）') }}
          </el-checkbox>
          <el-tooltip :disabled="!verifyBlocked" :content="verifyBlocked" placement="top">
            <span>
              <el-button
                type="primary"
                :loading="verifying"
                :disabled="!!verifyBlocked || saving"
                @click="verify"
              >
                {{ t('验证映射') }}
              </el-button>
            </span>
          </el-tooltip>
        </div>
        <p v-if="verifyBlocked && !verifying" class="xp-dialog__note">{{ verifyBlocked }}</p>

        <el-alert
          v-if="verifyError"
          type="error"
          show-icon
          :closable="false"
          :title="verifyError"
        />
        <el-alert
          v-else-if="verifyResult"
          :type="verifyResult.verified ? 'success' : 'warning'"
          show-icon
          :closable="false"
          :title="
            verifyResult.verified
              ? t('验证的映射都通过了。')
              : t('有映射没有通过验证，探针的结果在上面每一行下面；没通过的映射不会被请求用到。')
          "
        />

        <ul v-if="verifyResult?.models.some((model) => model.cache)" class="xp-advice">
          <template v-for="model in verifyResult.models" :key="model.modelId">
            <li v-if="model.cache" class="xp-advice__item surface-card">
              <strong>{{ model.modelId }}</strong>
              <span>{{ t('缓存读：{p0}', { p0: cacheVerdict(model.cache.readInPrompt) }) }}</span>
              <small v-if="model.cache.readEvidence">{{ model.cache.readEvidence }}</small>
              <span>{{ t('缓存写：{p0}', { p0: cacheVerdict(model.cache.writeInPrompt) }) }}</span>
              <small v-if="model.cache.writeEvidence">{{ model.cache.writeEvidence }}</small>
              <small v-if="model.cache.message">{{ model.cache.message }}</small>
              <el-button v-if="adoptable(model.modelId)" size="small" @click="adopt(model.modelId)">
                {{ t('按建议改设置') }}
              </el-button>
            </li>
          </template>
        </ul>
      </section>

      <el-alert
        v-if="costWarning"
        type="warning"
        show-icon
        :closable="false"
        :title="costWarning"
      />
      <el-alert v-if="limits" type="info" show-icon :closable="false" :title="limits" />
      <el-alert type="info" show-icon :closable="false" :title="privacyNote()" />
    </div>

    <template #footer>
      <el-button :disabled="saving" @click="beforeClose(() => (visibleValue = false))">
        {{ dirty ? t('取消') : t('关闭') }}
      </el-button>
      <el-tooltip :disabled="!problem" :content="problem ?? ''" placement="top">
        <span>
          <el-button
            type="primary"
            :loading="saving"
            :disabled="!dirty && !!savedAccountId"
            @click="save"
          >
            {{ t('保存') }}
          </el-button>
        </span>
      </el-tooltip>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
  .xp-dialog {
    display: flex;
    flex-direction: column;
    gap: 16px;
    min-width: 0;

    &__intro,
    &__note {
      margin: 0;
      font-size: 12.5px;
      line-height: 1.6;
      color: var(--kp-text-secondary);
    }

    &__note {
      color: var(--kp-text-muted);
    }

    &__block {
      display: flex;
      flex-direction: column;
      gap: 10px;
      min-width: 0;

      h4 {
        margin: 0;
        font-size: 14px;
        font-weight: 600;
        color: var(--kp-text);
      }
    }

    &__grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 12px 16px;
    }

    .is-warning {
      color: var(--el-color-warning);
    }
  }

  .xp-field {
    display: flex;
    flex-direction: column;
    gap: 6px;
    min-width: 0;

    > span {
      font-size: 13px;
      font-weight: 600;
      color: var(--kp-text);
    }

    :deep(.el-select) {
      width: 100%;
    }

    &__hint {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px;
      font-size: 12px;
      color: var(--kp-text-muted);
      overflow-wrap: anywhere;
    }
  }

  .xp-option {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;

    small {
      color: var(--kp-text-muted);
    }
  }

  .xp-opts {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }

  .xp-opt {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 14px;
    min-width: 0;
    padding: 12px 14px;

    strong {
      font-size: 13.5px;
      color: var(--kp-text);
    }

    p {
      margin: 4px 0 0;
      font-size: 12px;
      line-height: 1.55;
      color: var(--kp-text-muted);
    }

    .is-warning {
      color: var(--el-color-warning);
    }

    &__select {
      width: 190px;
      flex: 0 0 auto;
    }

    :deep(.el-input-number) {
      width: 130px;
      flex: 0 0 auto;
    }
  }

  .xp-caliber {
    padding: 12px 14px;

    h5 {
      margin: 0;
      font-size: 13.5px;
      font-weight: 600;
      color: var(--kp-text);
    }

    p {
      margin: 4px 0 0;
      font-size: 12px;
      line-height: 1.55;
      color: var(--kp-text-muted);
    }

    .is-warning {
      color: var(--el-color-warning);
    }

    &__switches {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 28px;
      margin-top: 10px;

      label {
        display: inline-flex;
        align-items: center;
        gap: 8px;
        font-size: 13px;
        color: var(--kp-text-secondary);
      }
    }
  }

  .xp-verify {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 10px;

    &__pick {
      display: flex;
      flex-direction: column;
      gap: 6px;
      font-size: 12.5px;
      color: var(--kp-text-secondary);
    }
  }

  .xp-advice {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin: 0;
    padding: 0;
    list-style: none;

    &__item {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: 4px 14px;
      padding: 10px 14px;
      font-size: 12.5px;

      small {
        color: var(--kp-text-muted);
      }
    }
  }

  @media (max-width: 720px) {
    .xp-dialog__grid,
    .xp-opts {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>

<style lang="scss">
  /* 内容长时对话框不超出屏幕，只滚动正文 */
  .xp-member-dialog.el-dialog {
    display: flex;
    flex-direction: column;
    max-height: calc(100vh - 32px);
    margin-top: 16px;
    margin-bottom: 16px;

    .el-dialog__body {
      flex: 1;
      min-height: 0;
      overflow-y: auto;
    }
  }
</style>
