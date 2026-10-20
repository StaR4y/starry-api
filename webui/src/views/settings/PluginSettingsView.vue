<script setup lang="ts">
  import { locale, t } from '@/i18n';
  import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import {
    Connection,
    Refresh,
    Upload,
    VideoPlay,
    Tools,
    Download,
    Lock,
    Message,
    Check,
  } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import PluginActions from './components/PluginActions.vue';
  import {
    pluginsApi,
    type PluginStatus,
    type PluginInfo,
    type PluginLog,
    type PluginAudit,
  } from '@/api/plugins';

  const status = ref<PluginStatus>();
  const compact = ref(window.innerWidth < 768);
  function resize() {
    compact.value = window.innerWidth < 768;
  }
  const loading = ref(false);
  const busy = ref(false);
  const loadError = ref('');
  const tab = ref('plugins');
  const filter = ref('');
  const fileInput = ref<HTMLInputElement>();
  const logs = ref<PluginLog[]>([]);
  const audits = ref<PluginAudit[]>([]);
  const logLoading = ref(false);
  const live = ref(true);
  const moreAudit = ref(true);
  const enableDialog = ref(false);
  const sending = ref(false);
  const verificationError = ref('');
  const form = reactive({ password: '', code: '', challengeId: '', acceptRisk: false });
  const resendAt = ref(0);
  const expiry = ref(0);
  const now = ref(Date.now());
  const countdown = computed(() => Math.max(0, Math.ceil((resendAt.value - now.value) / 1000)));
  const canEdit = computed(
    () =>
      status.value?.root &&
      status.value.developerMode &&
      !status.value.runtimeDisabled &&
      !busy.value,
  );
  const configDialog = ref(false);
  const debugDialog = ref(false);
  const selected = ref('');
  const config = ref('{}');
  const debugCommand = ref('health');
  const debugInput = ref('{}');
  const debugOutput = ref('');
  const debugDuration = ref<number>();
  let timer: ReturnType<typeof setInterval> | undefined;
  let poll: ReturnType<typeof setInterval> | undefined;
  let unmounted = false;
  let pendingLogReset = false;

  const stateLabels: Record<string, string> = {
    get STOPPED() {
      return t('已停止');
    },
    get STARTING() {
      return t('启动中');
    },
    get STARTED() {
      return t('运行中');
    },
    get STOPPING() {
      return t('停止中');
    },
    get FAILED() {
      return t('失败');
    },
    get RESTART_REQUIRED() {
      return t('需重启');
    },
  };
  const actionLabels: Record<string, string> = {
    get START() {
      return t('启动');
    },
    get RELOAD() {
      return t('重载');
    },
    get RESTART_REQUIRED() {
      return t('重启诊断');
    },
    get STOP() {
      return t('停止');
    },
    get INSTALL() {
      return t('安装');
    },
    get REMOVE() {
      return t('移除');
    },
    get CONFIGURE() {
      return t('配置');
    },
    get DEBUG() {
      return t('调试');
    },
    get DEVELOPER_MODE() {
      return t('开发者模式');
    },
    get CODE_REQUEST() {
      return t('验证申请');
    },
    get CODE_DELIVERY() {
      return t('邮件发送');
    },
    get PASSWORD_REJECTED() {
      return t('密码验证');
    },
    get CHALLENGE_REJECTED() {
      return t('验证码验证');
    },
    get VERIFICATION_REJECTED() {
      return t('双重验证');
    },
  };
  function message(error: unknown) {
    return error instanceof Error ? error.message : t('操作失败');
  }
  function date(value: string) {
    return new Date(value).toLocaleString(locale.value, { hour12: false });
  }

  async function load() {
    loading.value = true;
    loadError.value = '';
    try {
      status.value = await pluginsApi.status();
    } catch (error) {
      loadError.value = message(error);
    } finally {
      loading.value = false;
    }
  }

  function clearVerification() {
    form.password = '';
    form.code = '';
    form.challengeId = '';
    form.acceptRisk = false;
    expiry.value = 0;
    verificationError.value = '';
  }

  async function toggleMode(enabled: string | number | boolean) {
    if (enabled) {
      clearVerification();
      enableDialog.value = true;
      return;
    }
    try {
      await ElMessageBox.confirm(
        t(
          '将禁止加载第三方插件并尝试停止现有插件。已执行代码和持有的资源可能需要重启服务才能彻底清除。',
        ),
        t('关闭开发者模式'),
        {
          type: 'warning',
          get confirmButtonText() {
            return t('关闭');
          },
          get cancelButtonText() {
            return t('取消');
          },
        },
      );
    } catch {
      return;
    }
    busy.value = true;
    try {
      await pluginsApi.disable();
      ElMessage.success(t('开发者模式已关闭'));
    } catch {
      /* Global HTTP handler reports the error. */
    } finally {
      busy.value = false;
      await load();
    }
  }

  async function sendCode() {
    if (!form.password || sending.value || countdown.value > 0) return;
    sending.value = true;
    verificationError.value = '';
    form.challengeId = '';
    form.code = '';
    try {
      const challenge = await pluginsApi.sendCode(form.password);
      form.challengeId = challenge.id;
      expiry.value = Date.parse(challenge.expiresAt);
      resendAt.value = Date.now() + challenge.resendAfterSeconds * 1000;
      ElMessage.success(t('验证码已发送至 {p0}', { p0: challenge.email }));
    } catch (error) {
      verificationError.value = message(error);
    } finally {
      sending.value = false;
    }
  }

  async function enable() {
    if (!form.challengeId || !/^\d{6}$/.test(form.code) || !form.acceptRisk) return;
    busy.value = true;
    verificationError.value = '';
    try {
      await pluginsApi.enable({ ...form });
      enableDialog.value = false;
      clearVerification();
      await load();
      ElMessage.success(t('开发者模式已开启'));
    } catch (error) {
      verificationError.value = message(error);
    } finally {
      busy.value = false;
    }
  }

  async function upload(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    if (!file.name.toLowerCase().endsWith('.jar') || file.size > 32 * 1024 * 1024) {
      ElMessage.warning(t('请选择不超过 32 MiB 的 JAR 文件'));
      return;
    }
    try {
      await ElMessageBox.confirm(
        t('插件安装后保持停止状态。同 ID 的已安装插件将被停止并替换，配置和数据保留。'),
        t('上传插件'),
        {
          get confirmButtonText() {
            return t('上传');
          },
          get cancelButtonText() {
            return t('取消');
          },
          type: 'warning',
        },
      );
    } catch {
      return;
    }
    busy.value = true;
    try {
      await pluginsApi.upload(file);
      ElMessage.success(t('插件已安装'));
    } catch {
      /* Global HTTP handler reports the error. */
    } finally {
      busy.value = false;
      await load();
    }
  }

  async function action(plugin: PluginInfo, operation: string) {
    if (operation === 'remove') {
      try {
        await ElMessageBox.confirm(
          t('移除 {p0}？插件数据目录会保留。', { p0: plugin.id }),
          t('移除插件'),
          {
            type: 'warning',
            get confirmButtonText() {
              return t('移除');
            },
            get cancelButtonText() {
              return t('取消');
            },
          },
        );
      } catch {
        return;
      }
    }
    busy.value = true;
    try {
      await pluginsApi.action(plugin.id, operation);
      ElMessage.success(t('操作已完成'));
    } catch {
      /* Global HTTP handler reports the error. */
    } finally {
      busy.value = false;
      await load();
    }
  }

  async function openConfig(plugin: PluginInfo) {
    selected.value = plugin.id;
    busy.value = true;
    try {
      config.value = JSON.stringify(JSON.parse(await pluginsApi.config(plugin.id)), null, 2);
      configDialog.value = true;
    } catch {
      /* Global HTTP handler reports the error. */
    } finally {
      busy.value = false;
    }
  }

  async function saveConfig() {
    try {
      const value = JSON.parse(config.value);
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error();
    } catch {
      ElMessage.warning(t('配置必须为有效的 JSON 对象'));
      return;
    }
    busy.value = true;
    try {
      await pluginsApi.configure(selected.value, config.value);
      configDialog.value = false;
      ElMessage.success(t('配置已保存，重载插件后生效'));
    } catch {
      /* Global HTTP handler reports the error. */
    } finally {
      busy.value = false;
    }
  }

  function openDebug(plugin: PluginInfo) {
    selected.value = plugin.id;
    debugCommand.value = 'health';
    debugInput.value = '{}';
    debugOutput.value = '';
    debugDuration.value = undefined;
    debugDialog.value = true;
  }

  async function runDebug() {
    if (!/^[A-Za-z0-9_.-]{1,64}$/.test(debugCommand.value)) {
      ElMessage.warning(t('调试命令格式无效'));
      return;
    }
    try {
      JSON.parse(debugInput.value);
    } catch {
      ElMessage.warning(t('参数必须为有效 JSON'));
      return;
    }
    busy.value = true;
    debugOutput.value = '';
    try {
      const result = await pluginsApi.debug(selected.value, debugCommand.value, debugInput.value);
      debugOutput.value = JSON.stringify(JSON.parse(result.json), null, 2);
      debugDuration.value = result.durationMs;
    } catch (error) {
      debugOutput.value = message(error);
    } finally {
      busy.value = false;
    }
  }

  async function loadLogs(reset = false) {
    if (!status.value?.root || unmounted) return;
    if (logLoading.value) {
      pendingLogReset ||= reset;
      return;
    }
    const requestedTab = tab.value;
    const requestedFilter = filter.value;
    logLoading.value = true;
    try {
      if (tab.value === 'logs') {
        const latest = await pluginsApi.logs(
          filter.value,
          reset ? 0 : (logs.value.at(-1)?.sequence ?? 0),
        );
        if (tab.value !== requestedTab || filter.value !== requestedFilter || unmounted) return;
        logs.value = (reset ? latest : [...logs.value, ...latest]).slice(-500);
      } else if (tab.value === 'audit') {
        const rows = await pluginsApi.audit(
          filter.value,
          reset ? 0 : (audits.value.at(-1)?.id ?? 0),
        );
        if (tab.value !== requestedTab || filter.value !== requestedFilter || unmounted) return;
        audits.value = reset ? rows : [...audits.value, ...rows];
        moreAudit.value = rows.length === 100;
      }
    } catch {
      live.value = false;
    } finally {
      logLoading.value = false;
      if (pendingLogReset && !unmounted) {
        pendingLogReset = false;
        void loadLogs(true);
      }
    }
  }

  function exportLogs() {
    const content = tab.value === 'audit' ? audits.value : logs.value;
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(content, null, 2)], { type: 'application/json' }),
    );
    const link = document.createElement('a');
    link.href = url;
    link.download = `plugin-${tab.value}-${Date.now()}.json`;
    link.click();
    URL.revokeObjectURL(url);
  }

  watch([tab, filter], () => {
    logs.value = [];
    audits.value = [];
    void loadLogs(true);
  });
  onMounted(async () => {
    window.addEventListener('resize', resize);
    await load();
    if (unmounted) return;
    timer = setInterval(() => {
      now.value = Date.now();
    }, 1000);
    poll = setInterval(() => {
      if (tab.value === 'logs' && live.value && !document.hidden) void loadLogs();
    }, 3000);
  });
  onBeforeUnmount(() => {
    unmounted = true;
    window.removeEventListener('resize', resize);
    clearInterval(timer);
    clearInterval(poll);
    clearVerification();
    config.value = '{}';
    debugInput.value = '{}';
  });
</script>

<template>
  <PageSection :title="t('插件开发')" :subtitle="t('运行与安全')" :icon="Connection">
    <template #actions>
      <el-tooltip :content="t('刷新状态')">
        <el-button :icon="Refresh" :loading="loading" :aria-label="t('刷新状态')" @click="load" />
      </el-tooltip>
    </template>
    <el-skeleton v-if="loading && !status" :rows="5" animated />
    <el-alert v-else-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
    <el-empty
      v-else-if="status && !status.root"
      :description="t('仅初始管理员 root 可以管理插件')"
      :image-size="80"
    />
    <template v-else-if="status">
      <section class="mode-bar">
        <div class="mode-title">
          <el-icon :size="22"><Tools /></el-icon>
          <div>
            <h3>{{ t('开发者模式') }}</h3>
            <span>{{ status.rootEmail || t('root 邮箱未绑定') }}</span>
          </div>
        </div>
        <div class="mode-control">
          <el-tag effect="plain" :type="status.developerMode ? 'warning' : 'info'">
            {{ status.developerMode ? t('完整信任') : t('第三方插件已禁用') }}
          </el-tag>
          <el-switch
            :model-value="status.developerMode"
            :loading="busy"
            :disabled="busy || (status.runtimeDisabled && !status.developerMode)"
            :aria-label="t('开发者模式')"
            @change="toggleMode"
          />
        </div>
      </section>
      <el-alert
        v-if="status.runtimeDisabled"
        :title="t('启动配置已强制禁用插件，后台无法解除')"
        type="warning"
        :closable="false"
        show-icon
      />
      <el-alert
        v-if="status.developerMode"
        :title="t('第三方插件拥有服务进程的完整权限，可访问数据、文件与网络。请只运行可信代码。')"
        type="warning"
        :closable="false"
        show-icon
      />
      <el-alert
        v-if="status.restartRequired"
        :title="t('插件清理未完成，相关插件已禁止重新启动，请检查后重启服务')"
        type="warning"
        :closable="false"
        show-icon
      >
        <ul class="restart-reasons">
          <li v-for="reason in status.restartReasons" :key="`${reason.pluginId}:${reason.code}`">
            <strong>{{ reason.pluginId }}</strong>
            <span>{{ reason.detail }}</span>
            <small><code>{{ reason.operation }} / {{ reason.code }}</code> ·
              {{ date(reason.at) }}</small>
          </li>
        </ul>
      </el-alert>
      <el-tabs v-model="tab" class="plugin-tabs">
        <el-tab-pane :label="t('已安装插件')" name="plugins" />
        <el-tab-pane :label="t('运行日志')" name="logs" />
        <el-tab-pane :label="t('操作审计')" name="audit" />
      </el-tabs>
      <template v-if="tab === 'plugins'">
        <div class="plugin-toolbar">
          <span class="muted">{{ status.plugins.length }} {{ t('个插件 · API') }} {{ status.apiVersion }}</span>
          <el-button
            type="primary"
            :icon="Upload"
            :disabled="!canEdit"
            :loading="busy"
            @click="fileInput?.click()"
          >
            {{ t('上传 JAR') }}
          </el-button>
          <input ref="fileInput" type="file" accept=".jar" hidden @change="upload">
        </div>
        <el-empty v-if="!status.plugins.length" :description="t('暂无插件')" :image-size="88" />
        <div v-else-if="compact" class="plugin-mobile-list">
          <article v-for="plugin in status.plugins" :key="plugin.id" class="plugin-mobile-row">
            <div class="plugin-mobile-heading">
              <strong>{{ plugin.id }}</strong>
              <el-tag
                effect="plain"
                :type="
                  plugin.state === 'STARTED'
                    ? 'success'
                    : ['FAILED', 'RESTART_REQUIRED'].includes(plugin.state)
                      ? 'danger'
                      : 'info'
                "
              >
                {{ stateLabels[plugin.state] || plugin.state }}
              </el-tag>
            </div>
            <p>{{ plugin.version }} · {{ plugin.provider || t('本地插件') }}</p>
            <code>{{ plugin.sha256.slice(0, 16) }}</code>
            <p v-if="plugin.error" class="error-text">{{ plugin.error }}</p>
            <PluginActions
              :plugin="plugin"
              :disabled="!canEdit"
              @action="action(plugin, $event)"
              @configure="openConfig(plugin)"
              @debug="openDebug(plugin)"
            />
          </article>
        </div>
        <div v-else class="table-wrap">
          <el-table :data="status.plugins" row-key="id">
            <el-table-column :label="t('插件')" min-width="230">
              <template #default="{ row }">
                <div class="plugin-identity">
                  <el-icon :size="20"><Connection /></el-icon>
                  <div>
                    <strong>{{ row.id }}</strong><span>{{ row.description || row.provider || t('本地插件') }}</span>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="version" :label="t('版本')" width="100" />
            <el-table-column :label="t('状态')" width="100">
              <template #default="{ row }">
                <el-tooltip :content="row.error || stateLabels[row.state] || row.state">
                  <el-tag
                    :type="
                      row.state === 'STARTED'
                        ? 'success'
                        : ['FAILED', 'RESTART_REQUIRED'].includes(row.state)
                          ? 'danger'
                          : 'info'
                    "
                    effect="plain"
                  >
                    {{ stateLabels[row.state] || row.state }}
                  </el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="SHA-256" width="130">
              <template #default="{ row }">
                <el-tooltip :content="row.sha256">
                  <code>{{ row.sha256.slice(0, 12) }}</code>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column :label="t('操作')" width="250" fixed="right">
              <template #default="{ row }">
                <PluginActions
                  :plugin="row"
                  :disabled="!canEdit"
                  @action="action(row, $event)"
                  @configure="openConfig(row)"
                  @debug="openDebug(row)"
                />
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
      <template v-else>
        <div class="plugin-toolbar">
          <el-select
            v-model="filter"
            clearable
            :placeholder="t('全部插件')"
            :aria-label="t('筛选插件')"
            class="plugin-filter"
          >
            <el-option
              v-for="plugin in status.plugins"
              :key="plugin.id"
              :label="plugin.id"
              :value="plugin.id"
            />
          </el-select>
          <div class="log-actions">
            <el-switch v-if="tab === 'logs'" v-model="live" :active-text="t('实时')" />
            <el-tooltip :content="t('刷新日志')">
              <el-button
                :icon="Refresh"
                :loading="logLoading"
                :aria-label="t('刷新日志')"
                @click="loadLogs(true)"
              />
            </el-tooltip>
            <el-tooltip :content="t('导出当前记录')">
              <el-button :icon="Download" :aria-label="t('导出当前记录')" @click="exportLogs" />
            </el-tooltip>
          </div>
        </div>
        <template v-if="tab === 'logs'">
          <el-empty v-if="!logs.length" :description="t('暂无运行日志')" :image-size="72" />
          <div v-else class="runtime-log" role="log" :aria-label="t('插件运行日志')">
            <div v-for="entry in logs" :key="entry.sequence" class="log-line">
              <time>{{ date(entry.at) }}</time><span :class="['log-level', entry.level.toLowerCase()]">{{ entry.level }}</span><code>[{{ entry.pluginId || 'system' }}] {{ entry.message }}</code>
            </div>
          </div>
        </template>
        <template v-else>
          <div class="table-wrap">
            <el-table :data="audits" row-key="id">
              <el-table-column type="expand">
                <template #default="{ row }">
                  <dl class="audit-detail">
                    <dt>{{ t('操作人') }}</dt>
                    <dd>{{ row.actor }}</dd>
                    <dt>{{ t('详细信息') }}</dt>
                    <dd>{{ row.detail }}</dd>
                  </dl>
                </template>
              </el-table-column>
              <el-table-column :label="t('时间')" width="180">
                <template #default="{ row }">{{ date(row.at) }}</template>
              </el-table-column>
              <el-table-column :label="t('插件')" min-width="150">
                <template #default="{ row }">
                  {{ row.pluginId || t('系统') }}
                </template>
              </el-table-column>
              <el-table-column :label="t('操作')" min-width="120">
                <template #default="{ row }">
                  {{ actionLabels[row.action] || row.action }}
                </template>
              </el-table-column>
              <el-table-column :label="t('结果')" min-width="130">
                <template #default="{ row }">
                  <el-tag
                    effect="plain"
                    :type="['FAILED', 'DENIED'].includes(row.outcome) ? 'danger' : 'info'"
                  >
                    {{ row.outcome }}
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <el-button
            v-if="moreAudit && audits.length"
            class="more-audit"
            :loading="logLoading"
            @click="loadLogs()"
          >
            {{ t('加载更早记录') }}
          </el-button>
        </template>
      </template>
    </template>

    <el-dialog
      v-model="enableDialog"
      append-to-body
      :title="t('开启开发者模式')"
      width="480px"
      class="plugin-dialog"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy && !sending"
      :show-close="!busy && !sending"
      @closed="clearVerification"
    >
      <el-alert
        :title="
          t(
            '插件与主服务同进程运行，不提供安全沙箱。开启后，你将承担第三方代码带来的数据和服务风险。',
          )
        "
        type="warning"
        :closable="false"
        show-icon
      />
      <el-form label-position="top" class="verify-form" @submit.prevent="enable">
        <el-form-item :label="t('初始管理员 root 的当前密码')">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :prefix-icon="Lock"
            autocomplete="current-password"
            maxlength="256"
            :disabled="busy || sending"
          />
        </el-form-item>
        <el-form-item :label="t('root 邮箱：{p0}', { p0: status?.rootEmail || t('未绑定') })">
          <div class="verification-code">
            <el-input
              v-model="form.code"
              :prefix-icon="Message"
              :placeholder="t('六位验证码')"
              inputmode="numeric"
              autocomplete="one-time-code"
              maxlength="6"
              :disabled="busy"
            /><el-button
              :loading="sending"
              :disabled="!form.password || countdown > 0 || !status?.rootEmail || busy"
              @click="sendCode"
            >
              {{ countdown ? `${countdown}s` : t('发送验证码') }}
            </el-button>
          </div>
        </el-form-item>
        <p v-if="expiry && now >= expiry" class="error-text">
          {{ t('验证码已过期，请重新发送。') }}
        </p>
        <el-checkbox v-model="form.acceptRisk" :disabled="busy">
          {{ t('我理解并接受插件的完整访问权限与运行风险') }}
        </el-checkbox>
        <el-alert
          v-if="verificationError"
          :title="verificationError"
          type="error"
          :closable="false"
        />
      </el-form>
      <template #footer>
        <el-button :disabled="busy || sending" @click="enableDialog = false">
          {{
            t('取消')
          }}
        </el-button><el-button
          type="primary"
          :icon="Check"
          :loading="busy"
          :disabled="
            !form.acceptRisk ||
              !form.challengeId ||
              !/^\d{6}$/.test(form.code) ||
              now >= expiry ||
              sending
          "
          @click="enable"
        >
          {{ t('验证并开启') }}
        </el-button>
      </template>
    </el-dialog>
    <el-dialog
      v-model="configDialog"
      append-to-body
      :title="t('{p0} · 配置', { p0: selected })"
      width="680px"
      class="plugin-dialog"
      :close-on-click-modal="false"
      @closed="config = '{}'"
    >
      <el-input
        v-model="config"
        type="textarea"
        :rows="14"
        maxlength="65536"
        class="json-editor"
        :aria-label="t('插件 JSON 配置')"
      />
      <template #footer>
        <el-button :disabled="busy" @click="configDialog = false">{{ t('取消') }}</el-button><el-button type="primary" :icon="Check" :loading="busy" @click="saveConfig">
          {{ t('保存') }}
        </el-button>
      </template>
    </el-dialog>
    <el-dialog
      v-model="debugDialog"
      append-to-body
      :title="t('{p0} · 调试', { p0: selected })"
      width="720px"
      class="plugin-dialog"
      :close-on-click-modal="false"
      @closed="
        debugInput = '{}';
        debugOutput = '';
      "
    >
      <el-form label-position="top">
        <el-form-item :label="t('命令')">
          <el-input v-model="debugCommand" maxlength="64" :disabled="busy" />
        </el-form-item><el-form-item :label="t('参数 JSON')">
          <el-input
            v-model="debugInput"
            type="textarea"
            :rows="5"
            maxlength="65536"
            class="json-editor"
            :disabled="busy"
          />
        </el-form-item><el-form-item
          v-if="debugOutput"
          :label="
            t('执行结果{p0}', { p0: debugDuration !== undefined ? ` · ${debugDuration} ms` : '' })
          "
        >
          <el-input
            :model-value="debugOutput"
            type="textarea"
            :rows="9"
            readonly
            class="json-editor"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" :icon="VideoPlay" :loading="busy" @click="runDebug">
          {{ t('执行') }}
        </el-button>
      </template>
    </el-dialog>
  </PageSection>
</template>

<style scoped lang="scss">
  .plugin-mobile-row {
    padding: 16px 0;
    border-top: 1px solid var(--kp-divider);
    display: grid;
    gap: 12px;
    p {
      margin: 0;
      color: var(--kp-text-muted);
      font-size: 12px;
    }
    code {
      overflow-wrap: anywhere;
      color: var(--kp-text-muted);
      font-size: 12px;
    }
  }
  .plugin-mobile-heading {
    display: flex;
    align-items: start;
    justify-content: space-between;
    gap: 10px;
    strong {
      min-width: 0;
      overflow-wrap: anywhere;
    }
    .el-tag {
      flex: 0 0 auto;
    }
  }
  .mode-bar,
  .plugin-toolbar,
  .mode-title,
  .mode-control,
  .log-actions {
    display: flex;
    align-items: center;
    gap: 12px;
  }
  .mode-bar {
    justify-content: space-between;
    padding: 4px 0 22px;
    flex-wrap: wrap;
  }
  .mode-title {
    color: var(--kp-primary);
    h3 {
      margin: 0 0 3px;
      color: var(--kp-text);
      font-size: 16px;
    }
    span {
      color: var(--kp-text-muted);
      font-size: 12px;
    }
  }
  .plugin-toolbar {
    justify-content: space-between;
    margin: 2px 0 16px;
    flex-wrap: wrap;
  }
  .muted {
    color: var(--kp-text-muted);
    font-size: 13px;
  }
  .plugin-tabs {
    margin-top: 16px;
  }
  .table-wrap {
    min-width: 0;
    width: 100%;
    overflow: auto;
  }
  .plugin-identity {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
    .el-icon {
      flex: 0 0 auto;
      color: var(--kp-primary);
    }
    div {
      min-width: 0;
    }
    strong {
      display: block;
      overflow-wrap: anywhere;
      font-size: 13px;
    }
    span {
      display: block;
      color: var(--kp-text-muted);
      font-size: 12px;
      overflow-wrap: anywhere;
    }
  }
  .row-actions {
    display: flex;
    gap: 6px;
    .el-button {
      margin: 0;
      width: 34px;
      height: 32px;
      padding: 0;
      flex: 0 0 34px;
    }
  }
  .plugin-filter {
    width: 200px;
    max-width: 100%;
  }
  .runtime-log {
    max-height: 560px;
    overflow: auto;
    border: 1px solid var(--kp-divider);
    border-radius: 6px;
    padding: 8px 12px;
    background: var(--el-fill-color-extra-light);
  }
  .log-line {
    display: grid;
    grid-template-columns: 158px 48px minmax(0, 1fr);
    gap: 10px;
    padding: 6px 0;
    border-bottom: 1px solid var(--kp-divider);
    font-size: 12px;
    line-height: 1.6;
    time {
      color: var(--kp-text-muted);
    }
    code {
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }
  }
  .log-level {
    font-weight: 650;
    &.error {
      color: var(--el-color-danger);
    }
    &.warn {
      color: var(--el-color-warning);
    }
    &.info {
      color: var(--el-color-success);
    }
  }
  .audit-detail {
    padding: 0 20px;
    display: grid;
    grid-template-columns: 80px minmax(0, 1fr);
    gap: 10px;
    font-size: 12px;
    dt {
      color: var(--kp-text-muted);
    }
    dd {
      margin: 0;
      overflow-wrap: anywhere;
    }
  }
  .more-audit {
    margin-top: 16px;
  }
  .verify-form {
    margin-top: 20px;
    .el-checkbox {
      height: auto;
      margin-bottom: 12px;
      align-items: flex-start;
    }
    :deep(.el-checkbox__label) {
      white-space: normal;
      line-height: 1.5;
    }
    :deep(.el-checkbox__input) {
      margin-top: 3px;
    }
  }
  .verification-code {
    display: flex;
    gap: 8px;
    width: 100%;
    .el-input {
      min-width: 0;
    }
    .el-button {
      flex: 0 0 116px;
    }
  }
  .error-text {
    color: var(--el-color-danger);
    font-size: 12px;
  }
  .json-editor :deep(textarea) {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
    line-height: 1.7;
  }
  .restart-reasons {
    margin: 8px 0 0;
    padding-left: 18px;
    max-height: 240px;
    overflow-y: auto;
    overflow-wrap: anywhere;

    li + li {
      margin-top: 10px;
    }
    strong,
    span,
    small {
      display: block;
    }
    small {
      margin-top: 3px;
    }
  }

  :deep(.el-alert + .el-alert) {
    margin-top: 10px;
  }
  @media (max-width: 640px) {
    .log-line {
      grid-template-columns: minmax(0, 1fr) 48px;
      code {
        grid-column: 1 / -1;
      }
    }
    .mode-control {
      width: 100%;
      justify-content: space-between;
    }
  }
</style>

<style lang="scss">
  .plugin-dialog {
    max-width: calc(100vw - 32px);
    .el-dialog__title {
      overflow-wrap: anywhere;
    }
  }
</style>
