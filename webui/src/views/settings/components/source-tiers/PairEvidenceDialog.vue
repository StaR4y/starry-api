<script setup lang="ts">
  /**
   * 升级证据：把一个（入口，厂商）对从「试用中」升为「已验证」之前要看的数字（设计 7.3）。对每个这个版本提供的对，
   * 列出最近若干天里发给中转的尝试、它们的结果，和升级标准是否达到；可以下载成 JSON 作为升级任务的证据。
   *
   * 数字取自请求日志，覆盖所有节点；只有「严格检查失败」是应答节点自己数的（被跳过的中转在日志里没有痕迹）。
   * 试用中的对的门控只能对名单里的 Key 打开，所以这些尝试都来自名单里的 Key。
   */
  import { computed, ref, watch } from 'vue';
  import { ElMessage } from 'element-plus';
  import { Download, Refresh } from '@element-plus/icons-vue';
  import { t } from '@/i18n';
  import { ApiError } from '@/utils/http';
  import { formatDateTime, formatNumber } from '@/utils/format';
  import { sourceTiersApi, type SourceTierEvidence } from '@/api/sourceTiers';
  import {
    criterionLabel,
    criterionNote,
    criterionValue,
    entryInfo,
    evidenceFileName,
    formatLatency,
    gateStateLabel,
    lintCountPartial,
    pairStatusLabel,
    successPercent,
    unmetCriteria,
    withheldTokens,
  } from './xpMeta';
  import { formatPercent } from './tierStatusMeta';

  const props = defineProps<{ visible: boolean }>();
  const emit = defineEmits<{ 'update:visible': [value: boolean] }>();

  const visibleValue = computed({
    get: () => props.visible,
    set: (value: boolean) => emit('update:visible', value),
  });

  /** 请求日志只保留三个多月：窗口最长 90 天 */
  const DAY_CHOICES = [7, 30, 90];
  const days = ref(30);
  const state = ref<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const error = ref('');
  const evidence = ref<SourceTierEvidence | null>(null);

  async function load() {
    state.value = 'loading';
    error.value = '';
    try {
      evidence.value = await sourceTiersApi.evidence(days.value);
      state.value = 'ready';
    } catch (err) {
      state.value = 'error';
      error.value = err instanceof ApiError ? err.message : t('服务暂时不可用');
    }
  }

  watch(
    () => props.visible,
    (shown) => {
      if (shown) void load();
    },
  );

  function download() {
    if (!evidence.value) return;
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(evidence.value, null, 2)], { type: 'application/json' }),
    );
    const link = document.createElement('a');
    link.href = url;
    link.download = evidenceFileName(evidence.value);
    link.click();
    URL.revokeObjectURL(url);
    ElMessage.success(t('已导出'));
  }

  /** 有尝试的对排在前面，其余保持原来的顺序 */
  const pairs = computed(() =>
    [...(evidence.value?.pairs ?? [])].sort(
      (a, b) => Number(b.figures.attempts > 0) - Number(a.figures.attempts > 0),
    ),
  );
</script>

<template>
  <el-dialog
    v-model="visibleValue"
    append-to-body
    class="pair-evidence-dialog"
    :title="t('升级证据')"
    width="min(980px, calc(100vw - 24px))"
    destroy-on-close
  >
    <div class="evidence">
      <p class="evidence__intro">
        {{
          t(
            '一个对要从「试用中」升为「已验证」（门控才能对所有 Key 打开），要在名单里的 Key 下累计：至少 200 次尝试、严格检查失败 0 次、转换被拒不到 1%、用量估算不到 5%（占已服务的尝试）、中断（UNBILLED）不到 2%。数字取自请求日志，覆盖所有节点；负责人可以在升级时书面放宽尝试数。',
          )
        }}
      </p>

      <div class="evidence__bar">
        <el-radio-group v-model="days" size="small" :disabled="state === 'loading'" @change="load">
          <el-radio-button
            v-for="choice in DAY_CHOICES"
            :key="choice"
            :value="choice"
            :label="choice"
          >
            {{ t('最近 {p0} 天', { p0: choice }) }}
          </el-radio-button>
        </el-radio-group>
        <span v-if="evidence" class="evidence__at">
          {{ t('读取于 {p0}', { p0: formatDateTime(evidence.at) }) }}
        </span>
        <el-button :icon="Refresh" :loading="state === 'loading'" @click="load">
          {{ t('刷新') }}
        </el-button>
        <el-button type="primary" :icon="Download" :disabled="!evidence" @click="download">
          {{ t('下载 JSON') }}
        </el-button>
      </div>

      <el-skeleton v-if="state === 'loading' && !evidence" :rows="4" animated />
      <el-alert
        v-else-if="state === 'error'"
        type="error"
        show-icon
        :closable="false"
        :title="t('升级证据加载失败')"
        :description="error"
      />

      <template v-else-if="evidence">
        <p class="evidence__note">
          {{
            t(
              '严格检查失败是应答节点自 {p0} 起的计数（只数这一对的入口，不数同一厂商别的入口）；多节点部署时请到每个节点各看一次。',
              { p0: formatDateTime(evidence.skipsSince) },
            )
          }}
        </p>
        <el-alert
          v-if="lintCountPartial(evidence)"
          type="warning"
          show-icon
          :closable="false"
          :title="t('严格检查失败这一项判不了')"
          :description="
            t(
              '应答节点自 {p0} 才开始计数，比所选窗口（自 {p1}）短，窗口前面那一段它没看到，所以这一项算未达标。请等它运行满窗口再看，或选更短的窗口。',
              { p0: formatDateTime(evidence.skipsSince), p1: formatDateTime(evidence.since) },
            )
          "
        />
        <p v-if="!evidence.pairs.length" class="evidence__empty">
          {{ t('这个版本没有提供任何对，没有可升级的。') }}
        </p>

        <article
          v-for="pair in pairs"
          :key="`${pair.vendor}/${pair.entryEndpoint}`"
          class="pair surface-card"
        >
          <header class="pair__head">
            <div>
              <strong>{{ pair.label }} · {{ entryInfo(pair.entryEndpoint).title }}</strong>
              <el-tag
                size="small"
                effect="plain"
                :type="pair.status === 'VERIFIED' ? 'success' : 'warning'"
              >
                {{ pairStatusLabel(pair.status) }}
              </el-tag>
              <el-tag size="small" effect="plain" type="info">
                {{ t('门控：{p0}', { p0: gateStateLabel(pair.gate) }) }}
                <template v-if="pair.gate === 'KEYS'">
                  {{ t('（{p0} 把 Key）', { p0: pair.allowedKeys }) }}
                </template>
              </el-tag>
            </div>
            <el-tag :type="pair.meetsStandard ? 'success' : 'info'" effect="light">
              {{ pair.meetsStandard ? t('达到升级标准') : t('还没有达到') }}
            </el-tag>
          </header>

          <ul class="pair__criteria">
            <li
              v-for="criterion in pair.criteria"
              :key="criterion.name"
              :class="{ 'is-unmet': !criterion.met }"
            >
              <el-tag :type="criterion.met ? 'success' : 'danger'" size="small" effect="light">
                {{ criterion.met ? t('达标') : t('未达标') }}
              </el-tag>
              <span class="pair__criterion">{{ criterionLabel(criterion.name) }}</span>
              <span class="pair__rule">{{ criterion.rule }}</span>
              <strong>{{ criterionValue(criterion) }}</strong>
              <span v-if="criterionNote(criterion.name)" class="pair__rule pair__note">
                {{ criterionNote(criterion.name) }}
              </span>
            </li>
          </ul>

          <p class="pair__figures">
            {{
              t(
                '尝试 {p0}，已服务 {p1}（{p2}），失败 {p3}，中断 {p4}，扣下 {p5}，转换被拒 {p6}，估算 {p7}，p95 {p8}',
                {
                  p0: formatNumber(pair.figures.attempts),
                  p1: formatNumber(pair.figures.served),
                  p2: formatPercent(successPercent(pair.figures)),
                  p3: formatNumber(pair.figures.failed),
                  p4: formatNumber(pair.figures.interrupted),
                  p5: formatNumber(pair.figures.withheld),
                  p6: formatNumber(pair.figures.conversionRejected),
                  p7: formatNumber(pair.figures.estimated),
                  p8: formatLatency(pair.figures.p95Ms),
                },
              )
            }}
          </p>
          <p v-if="withheldTokens(pair.figures)" class="pair__figures">
            {{
              t(
                '被扣下的应答，中转报告的用量（没向用户收费，由你承担）：输入 {p0}、输出 {p1} 个令牌',
                {
                  p0: formatNumber(withheldTokens(pair.figures)?.input),
                  p1: formatNumber(withheldTokens(pair.figures)?.output),
                },
              )
            }}
          </p>
          <p v-if="pair.degradedFeatures.length" class="pair__figures">
            {{ t('被丢掉的请求特性：') }}
            {{ pair.degradedFeatures.map((item) => `${item.feature} × ${item.count}`).join('，') }}
          </p>
          <p
            v-if="unmetCriteria(pair.criteria).length && pair.figures.attempts === 0"
            class="pair__figures"
          >
            {{
              t('这个对在窗口里没有任何尝试：把门控对名单里的 Key 打开并用它们实际使用后才有数字。')
            }}
          </p>

          <el-collapse v-if="pair.members.length" class="pair__members">
            <el-collapse-item :title="t('按中转（{p0} 个）', { p0: pair.members.length })">
              <ul>
                <li v-for="member in pair.members" :key="`${member.tierId}/${member.accountId}`">
                  <code>{{ member.accountId }}</code>
                  <span>{{ member.tierId }}</span>
                  <span>
                    {{
                      t('尝试 {p0}，已服务 {p1}，中断 {p2}，扣下 {p3}，转换被拒 {p4}，估算 {p5}', {
                        p0: formatNumber(member.figures.attempts),
                        p1: formatNumber(member.figures.served),
                        p2: formatNumber(member.figures.interrupted),
                        p3: formatNumber(member.figures.withheld),
                        p4: formatNumber(member.figures.conversionRejected),
                        p5: formatNumber(member.figures.estimated),
                      })
                    }}
                  </span>
                </li>
              </ul>
            </el-collapse-item>
          </el-collapse>
        </article>
      </template>
    </div>

    <template #footer>
      <el-button @click="visibleValue = false">{{ t('关闭') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
  .evidence {
    display: flex;
    flex-direction: column;
    gap: 14px;
    min-width: 0;

    &__intro,
    &__note {
      margin: 0;
      font-size: 12.5px;
      line-height: 1.6;
      color: var(--kp-text-secondary);
    }

    &__note,
    &__empty {
      color: var(--kp-text-muted);
    }

    &__empty {
      margin: 0;
      padding: 18px;
      text-align: center;
      font-size: 13px;
    }

    &__bar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
    }

    &__at {
      margin-right: auto;
      font-size: 12px;
      color: var(--kp-text-muted);
    }
  }

  .pair {
    padding: 14px 16px;

    &__head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;

      > div {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 8px;
        min-width: 0;
      }

      strong {
        font-size: 14px;
        color: var(--kp-text);
      }
    }

    &__criteria {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
      gap: 6px 16px;
      margin: 12px 0 0;
      padding: 0;
      list-style: none;

      li {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 4px 8px;
        font-size: 13px;
        color: var(--kp-text-secondary);
      }

      .is-unmet strong {
        color: var(--kp-danger);
      }
    }

    &__criterion {
      min-width: 110px;
    }

    &__rule {
      font-size: 12px;
      color: var(--kp-text-muted);
      font-variant-numeric: tabular-nums;
    }

    /* 说明单独占一行，不挤进一格只有 260px 的标准行里 */
    &__note {
      flex-basis: 100%;
    }

    &__figures {
      margin: 10px 0 0;
      font-size: 12.5px;
      color: var(--kp-text-muted);
      font-variant-numeric: tabular-nums;
    }

    &__members {
      margin-top: 8px;
      border: 0;

      ul {
        display: flex;
        flex-direction: column;
        gap: 6px;
        margin: 0;
        padding: 0;
        list-style: none;
        font-size: 12.5px;
      }

      li {
        display: flex;
        flex-wrap: wrap;
        gap: 4px 14px;
        color: var(--kp-text-secondary);
      }
    }
  }
</style>

<style lang="scss">
  /* 内容长时对话框不超出屏幕，只滚动正文 */
  .pair-evidence-dialog.el-dialog {
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
