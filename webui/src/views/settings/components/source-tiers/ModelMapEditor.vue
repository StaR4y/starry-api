<script setup lang="ts">
  /**
   * 模型映射：把这个厂商的目录模型映射到中转自己给它起的名字（wire 名，原样发给中转）。
   *
   * 每一行：目录模型、中转的模型名、上下文上限（必填：请求超过它就不派给这个中转）、图片上限（每张内联图片按多少令牌
   * 估算，用来给预留定上界）、是否启用，和验证状态。映射只有验证通过才会被请求用到；验证、「从上游拉取」由上层的
   * 成员对话框执行，本组件只编辑这张表，每次编辑都通过 update:models 交回新的数组。
   *
   * 「别名」是指向同一个目录模型、自己没有映射行的目录模型（`sonnet` 与 `claude-sonnet-4.5`）：它们共用这一行。
   */
  import { computed } from 'vue';
  import { Delete, Plus, Refresh } from '@element-plus/icons-vue';
  import { t } from '@/i18n';
  import { formatNumber } from '@/utils/format';
  import type { SourceTierModelVerification, SourceTierUpstreamModel } from '@/api/sourceTiers';
  import {
    DEFAULT_IMAGE_TOKEN_CAP,
    MAX_IMAGE_TOKEN_CAP,
    coveredAliases,
    mappingProblems,
    mappingStatus,
    mappingStatusLabel,
    mappingStatusTone,
    newMapping,
    prefillContext,
    probeLabel,
    probeStatusLabel,
    probeTone,
    suggestWireName,
    verificationOf,
    type CatalogModel,
    type MappingDraft,
    type RelayDraft,
  } from './xpMeta';

  const props = defineProps<{
    models: MappingDraft[];
    /** 这个厂商能映射的目录模型 */
    catalog: CatalogModel[];
    /** 中转自己列出的模型（「从上游拉取」的结果），没有拉取时为空 */
    upstream: SourceTierUpstreamModel[];
    upstreamState: 'idle' | 'loading' | 'ready' | 'error';
    upstreamError: string;
    /** 中转列得比返回的更多 */
    upstreamTruncated: boolean;
    /** 已保存的这个成员（按行标出验证状态）；还没保存时为 null */
    saved: RelayDraft | null;
    /** 最近一次验证的结果，按映射给出探针；没有验证过时为 null */
    verification: SourceTierModelVerification[] | null;
    /** 没有选账号就不能向中转取模型列表 */
    canFetch: boolean;
    disabled?: boolean;
  }>();
  const emit = defineEmits<{
    'update:models': [models: MappingDraft[]];
    fetch: [];
  }>();

  /**
   * 每一行（按 key）要显示的问题。刚添加、什么都还没填的行先不报：这时的每一项都「有问题」，只会吓人；
   * 这些问题照样拦住保存（成员对话框的保存按钮提示的是同一个）。
   */
  const problems = computed(() => {
    const found = mappingProblems(props.models);
    for (const mapping of props.models) {
      if (!mapping.modelId && !mapping.upstreamModel && mapping.maxContextTokens === null) {
        delete found[mapping.key];
      }
    }
    return found;
  });
  const mappedIds = computed(() => props.models.map((mapping) => mapping.modelId));
  const catalogOptions = computed(() => props.catalog.filter((model) => model.enabled));

  function patch(key: string, change: Partial<MappingDraft>) {
    emit(
      'update:models',
      props.models.map((mapping) => (mapping.key === key ? { ...mapping, ...change } : mapping)),
    );
  }

  /** 选了目录模型：上下文上限还没填时预填（中转写的窗口优先，其次目录窗口） */
  function setModel(mapping: MappingDraft, value: string) {
    patch(
      mapping.key,
      prefillContext({ ...mapping, modelId: value.trim() }, props.catalog, props.upstream),
    );
  }

  /** 选了中转的模型名：上下文上限还没填时取中转写的窗口 */
  function setUpstream(mapping: MappingDraft, value: string) {
    patch(
      mapping.key,
      prefillContext({ ...mapping, upstreamModel: value.trim() }, props.catalog, props.upstream),
    );
  }

  function setContext(mapping: MappingDraft, value: number | null | undefined) {
    patch(mapping.key, { maxContextTokens: typeof value === 'number' ? value : null });
  }

  function setImageCap(mapping: MappingDraft, value: number | null | undefined) {
    patch(mapping.key, {
      imageTokenCap: typeof value === 'number' ? value : DEFAULT_IMAGE_TOKEN_CAP,
    });
  }

  function add() {
    emit('update:models', [...props.models, newMapping()]);
  }

  function remove(key: string) {
    emit(
      'update:models',
      props.models.filter((mapping) => mapping.key !== key),
    );
  }

  /** 空着的 wire 名可以采用的建议：拉取到的列表里和这个目录模型对得上的唯一一个 */
  function suggestion(mapping: MappingDraft): string | null {
    if (mapping.upstreamModel || !mapping.modelId) return null;
    return suggestWireName(mapping.modelId, props.upstream);
  }

  /** 「从上游拉取」之后的说明：拉到了多少个，空的、列得不全时怎么办 */
  const upstreamNote = computed(() =>
    props.upstream.length
      ? t(
          '中转列出了 {p0} 个模型{p1}，可以在「中转的模型名」里选；它写明窗口的会预填上下文上限。',
          {
            p0: props.upstream.length,
            p1: props.upstreamTruncated ? t('（它列得更多，这里只取了一部分）') : '',
          },
        )
      : t('中转的模型列表是空的，请手动填写模型名。'),
  );

  function upstreamLabel(model: SourceTierUpstreamModel) {
    return model.contextLength ? `${model.id} · ${formatNumber(model.contextLength)}` : model.id;
  }

  function probesOf(mapping: MappingDraft) {
    return verificationOf(props.verification, mapping.modelId);
  }
</script>

<template>
  <section class="map-editor">
    <header class="map-editor__head">
      <div>
        <h4>{{ t('模型映射') }}</h4>
        <p>
          {{
            t(
              '把目录模型映射到中转自己的模型名（原样发给中转，不能有通配）。只有验证通过的映射才会被请求用到；请求的模型没有映射的，这个中转不参与。',
            )
          }}
        </p>
      </div>
      <div class="map-editor__actions">
        <el-tooltip :disabled="canFetch" :content="t('先选择账号')" placement="top">
          <span>
            <el-button
              :icon="Refresh"
              :loading="upstreamState === 'loading'"
              :disabled="disabled || !canFetch"
              @click="emit('fetch')"
            >
              {{ t('从上游拉取') }}
            </el-button>
          </span>
        </el-tooltip>
        <el-button :icon="Plus" :disabled="disabled" @click="add">{{ t('添加映射') }}</el-button>
      </div>
    </header>

    <p v-if="upstreamState === 'ready'" class="map-editor__note">{{ upstreamNote }}</p>
    <p v-else-if="upstreamState === 'error'" class="map-editor__note is-warning">
      {{ upstreamError }}
    </p>

    <p v-if="!models.length" class="map-editor__empty">
      {{ t('还没有映射。添加一条：选择目录模型，填写中转给它起的名字和上下文上限。') }}
    </p>

    <ul v-else class="map-editor__rows">
      <li
        v-for="mapping in models"
        :key="mapping.key"
        class="map-row surface-card"
        :class="{ 'is-off': !mapping.enabled }"
      >
        <div class="map-row__main">
          <div class="map-row__field">
            <label>{{ t('目录模型') }}</label>
            <el-select
              :model-value="mapping.modelId"
              filterable
              allow-create
              default-first-option
              :disabled="disabled"
              :placeholder="t('选择目录模型')"
              :aria-label="t('目录模型')"
              @update:model-value="setModel(mapping, String($event ?? ''))"
            >
              <el-option
                v-for="model in catalogOptions"
                :key="model.id"
                :value="model.id"
                :label="model.id"
              >
                <span class="map-row__option">
                  <span>{{ model.id }}</span>
                  <small v-if="model.name && model.name !== model.id">{{ model.name }}</small>
                </span>
              </el-option>
            </el-select>
            <small v-if="problems[mapping.key]?.model" class="map-row__problem">
              {{ problems[mapping.key].model }}
            </small>
          </div>

          <div class="map-row__field">
            <label>{{ t('中转的模型名') }}</label>
            <el-select
              :model-value="mapping.upstreamModel"
              filterable
              allow-create
              default-first-option
              :disabled="disabled"
              :placeholder="t('中转给这个模型起的名字')"
              :aria-label="t('中转的模型名')"
              @update:model-value="setUpstream(mapping, String($event ?? ''))"
            >
              <el-option
                v-for="model in upstream"
                :key="model.id"
                :value="model.id"
                :label="upstreamLabel(model)"
              />
            </el-select>
            <small v-if="problems[mapping.key]?.upstream" class="map-row__problem">
              {{ problems[mapping.key].upstream }}
            </small>
            <small v-if="suggestion(mapping)" class="map-row__hint">
              {{ t('建议：') }}
              <el-link
                type="primary"
                :underline="false"
                @click="setUpstream(mapping, suggestion(mapping) ?? '')"
              >
                {{ suggestion(mapping) }}
              </el-link>
            </small>
          </div>

          <div class="map-row__field map-row__field--number">
            <label>{{ t('上下文上限（令牌）') }}</label>
            <el-input-number
              :model-value="mapping.maxContextTokens ?? undefined"
              :min="1"
              :step="1000"
              :disabled="disabled"
              :controls="false"
              :placeholder="t('必填')"
              :aria-label="t('上下文上限')"
              @update:model-value="setContext(mapping, $event)"
            />
            <small v-if="problems[mapping.key]?.context" class="map-row__problem">
              {{ problems[mapping.key].context }}
            </small>
          </div>

          <div class="map-row__field map-row__field--number">
            <label>{{ t('每张图片按（令牌）') }}</label>
            <el-input-number
              :model-value="mapping.imageTokenCap"
              :min="1"
              :max="MAX_IMAGE_TOKEN_CAP"
              :step="1024"
              :disabled="disabled"
              :controls="false"
              :aria-label="t('图片上限')"
              @update:model-value="setImageCap(mapping, $event)"
            />
            <small v-if="problems[mapping.key]?.image" class="map-row__problem">
              {{ problems[mapping.key].image }}
            </small>
          </div>

          <div class="map-row__end">
            <el-switch
              :model-value="mapping.enabled"
              :disabled="disabled"
              :aria-label="t('启用')"
              @update:model-value="patch(mapping.key, { enabled: Boolean($event) })"
            />
            <el-tag
              :type="mappingStatusTone(mappingStatus(mapping, saved))"
              size="small"
              effect="plain"
            >
              {{ mappingStatusLabel(mappingStatus(mapping, saved)) }}
            </el-tag>
            <el-button
              link
              type="danger"
              :icon="Delete"
              :disabled="disabled"
              :aria-label="t('删除映射')"
              @click="remove(mapping.key)"
            />
          </div>
        </div>

        <p
          v-if="coveredAliases(mapping.modelId, catalog, mappedIds).length"
          class="map-row__aliases"
        >
          {{ t('同时服务别名：')
          }}{{ coveredAliases(mapping.modelId, catalog, mappedIds).join('、') }}
        </p>

        <ul v-if="probesOf(mapping)?.probes.length" class="map-row__probes">
          <li v-for="probe in probesOf(mapping)?.probes" :key="probe.name">
            <el-tag :type="probeTone(probe)" size="small" effect="light">
              {{ probeLabel(probe.name) }} · {{ probeStatusLabel(probe) }}
            </el-tag>
            <small v-if="probe.status !== 'PASSED' && probe.message">{{ probe.message }}</small>
          </li>
          <li v-if="probesOf(mapping)?.stale">
            <el-tag type="warning" size="small" effect="light">
              {{ t('验证期间配置被改动，没有写入') }}
            </el-tag>
          </li>
        </ul>
      </li>
    </ul>
  </section>
</template>

<style scoped lang="scss">
  .map-editor {
    min-width: 0;

    &__head {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: 10px;
      margin-bottom: 8px;

      h4 {
        margin: 0;
        font-size: 14px;
        font-weight: 600;
        color: var(--kp-text);
      }

      p {
        margin: 4px 0 0;
        max-width: 560px;
        font-size: 12px;
        line-height: 1.55;
        color: var(--kp-text-secondary);
      }
    }

    &__actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }

    &__note {
      margin: 0 0 8px;
      font-size: 12px;
      color: var(--kp-text-muted);

      &.is-warning {
        color: var(--el-color-warning);
      }
    }

    &__empty {
      margin: 0;
      padding: 18px 14px;
      text-align: center;
      font-size: 13px;
      color: var(--kp-text-muted);
      border: 1px dashed var(--kp-divider);
      border-radius: 8px;
    }

    &__rows {
      display: flex;
      flex-direction: column;
      gap: 8px;
      margin: 0;
      padding: 0;
      list-style: none;
    }
  }

  .map-row {
    padding: 12px 14px;

    &.is-off {
      opacity: 0.65;
    }

    &__main {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: 10px 12px;
    }

    &__field {
      display: flex;
      flex: 1 1 170px;
      flex-direction: column;
      gap: 4px;
      min-width: 0;

      label {
        font-size: 12px;
        color: var(--kp-text-muted);
      }

      :deep(.el-select),
      :deep(.el-input-number) {
        width: 100%;
      }

      small {
        font-size: 11.5px;
        line-height: 1.45;
      }
    }

    &__field--number {
      flex: 0 1 140px;
    }

    &__problem {
      color: var(--kp-danger);
    }

    &__hint {
      color: var(--kp-text-muted);
    }

    &__option {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 12px;

      small {
        color: var(--kp-text-muted);
      }
    }

    &__end {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-left: auto;
      padding-top: 20px;
    }

    &__aliases {
      margin: 8px 0 0;
      font-size: 12px;
      color: var(--kp-text-muted);
    }

    &__probes {
      display: flex;
      flex-wrap: wrap;
      gap: 6px 14px;
      margin: 8px 0 0;
      padding: 0;
      list-style: none;

      li {
        display: flex;
        align-items: center;
        gap: 6px;
      }

      small {
        color: var(--kp-text-muted);
      }
    }
  }
</style>
