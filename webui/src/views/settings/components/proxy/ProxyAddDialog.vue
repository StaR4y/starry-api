<script setup lang="ts">
  /**
   * 添加代理：单个节点（代理 URL 或一条 Clash 节点）或订阅（链接或粘贴 YAML）。
   * 提交后立即清空 URL / YAML，订阅链接与代理凭据不会再显示在界面上。
   */
  import { computed, reactive, ref, watch } from 'vue';
  import { ElMessage } from 'element-plus';
  import { t } from '@/i18n';
  import { proxyChannelsApi, type ProxySyncResult } from '@/api/proxyChannels';
  import ProxySyncResultView from './ProxySyncResult.vue';

  type Tab = 'node' | 'subscription';
  type Mode = 'url' | 'yaml';

  const props = defineProps<{ modelValue: boolean; initialTab: Tab }>();
  const emit = defineEmits<{
    'update:modelValue': [value: boolean];
    /** 节点或订阅已写入，需要刷新目录 */
    changed: [];
  }>();

  const tab = ref<Tab>('node');
  const forms = reactive<Record<Tab, { name: string; mode: Mode; content: string }>>({
    node: { name: '', mode: 'url', content: '' },
    subscription: { name: '', mode: 'url', content: '' },
  });
  const saving = ref(false);
  const result = ref<ProxySyncResult | null>(null);

  watch(
    () => props.modelValue,
    (open) => {
      if (!open) return;
      tab.value = props.initialTab;
      result.value = null;
      for (const form of Object.values(forms))
        Object.assign(form, { name: '', mode: 'url', content: '' });
    },
    { immediate: true },
  );

  const form = computed(() => forms[tab.value]);
  const canSubmit = computed(() => !!form.value.name.trim() && !!form.value.content.trim());
  const formats = ['http://', 'https://', 'socks5://', 'socks5h://'];

  function close() {
    emit('update:modelValue', false);
  }

  async function submit() {
    if (!canSubmit.value || saving.value) return;
    const current = form.value;
    const name = current.name.trim();
    const content = current.content;
    saving.value = true;
    try {
      if (tab.value === 'node') {
        await proxyChannelsApi.create({
          name,
          ...(current.mode === 'url' ? { proxyUrl: content.trim() } : { clashYaml: content }),
        });
        current.content = '';
        ElMessage.success(t('已添加节点'));
        emit('changed');
        close();
      } else {
        const synced = await proxyChannelsApi.subscribe({
          name,
          ...(current.mode === 'url' ? { url: content.trim() } : { yaml: content }),
        });
        current.content = '';
        result.value = synced;
        emit('changed');
      }
    } catch {
      /* 错误已由全局拦截器提示；保留输入以便修改后重试 */
    } finally {
      saving.value = false;
    }
  }
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    append-to-body
    :title="result ? t('订阅已导入') : t('添加代理')"
    width="min(620px, 94vw)"
    :close-on-click-modal="!saving"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <ProxySyncResultView v-if="result" :result="result" />

    <el-tabs v-else v-model="tab" class="add-tabs">
      <el-tab-pane :label="t('单个节点')" name="node">
        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item :label="t('名称')" required>
            <el-input v-model="forms.node.name" :maxlength="200" />
          </el-form-item>
          <el-form-item>
            <el-radio-group v-model="forms.node.mode">
              <el-radio-button value="url">{{ t('代理 URL') }}</el-radio-button>
              <el-radio-button value="yaml">Clash YAML</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="forms.node.mode === 'url'" :label="t('代理 URL')" required>
            <el-input
              v-model="forms.node.content"
              type="password"
              show-password
              :maxlength="4096"
              autocomplete="new-password"
              placeholder="socks5://user:pass@host:1080"
            />
            <div class="add-hint">
              <span class="add-hint__formats">
                <code v-for="format in formats" :key="format">{{ format }}</code>
              </span>
              {{ t('必须包含端口，可带 user:pass@ 凭据。') }}
            </div>
          </el-form-item>
          <el-form-item v-else label="Clash YAML" required>
            <el-input
              v-model="forms.node.content"
              type="textarea"
              :rows="8"
              :maxlength="65536"
              autocomplete="off"
              :placeholder="'name: my-node\ntype: socks5\nserver: 203.0.113.8\nport: 1080'"
            />
            <div class="add-hint">
              {{ t('只粘贴一个节点。VLESS、Hysteria2 等协议需要启用 Mihomo 后才能使用。') }}
            </div>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane :label="t('订阅')" name="subscription">
        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item :label="t('名称')" required>
            <el-input v-model="forms.subscription.name" :maxlength="160" />
          </el-form-item>
          <el-form-item>
            <el-radio-group v-model="forms.subscription.mode">
              <el-radio-button value="url">{{ t('订阅 URL') }}</el-radio-button>
              <el-radio-button value="yaml">{{ t('粘贴 YAML') }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="forms.subscription.mode === 'url'" :label="t('订阅 URL')" required>
            <el-input
              v-model="forms.subscription.content"
              type="password"
              show-password
              :maxlength="8192"
              autocomplete="new-password"
              placeholder="https://example.com/subscription"
            />
            <div class="add-hint">
              {{ t('订阅链接可能含有访问令牌，提交后不会再次显示。') }}
            </div>
          </el-form-item>
          <el-form-item v-else label="Clash YAML" required>
            <el-input
              v-model="forms.subscription.content"
              type="textarea"
              :rows="10"
              autocomplete="off"
              :placeholder="'proxies:\n  - name: my-node\n    type: ss'"
            />
            <div class="add-hint">{{ t('粘贴的 YAML 只导入一次，之后无法同步刷新。') }}</div>
          </el-form-item>
        </el-form>
      </el-tab-pane>
    </el-tabs>

    <template #footer>
      <el-button v-if="result" type="primary" @click="close">{{ t('完成') }}</el-button>
      <template v-else>
        <el-button :disabled="saving" @click="close">{{ t('取消') }}</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canSubmit" @click="submit">
          {{ tab === 'node' ? t('添加节点') : t('导入订阅') }}
        </el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
  .add-hint {
    margin-top: 6px;
    font-size: 12px;
    line-height: 1.6;
    color: var(--kp-text-muted);

    &__formats {
      display: inline-flex;
      flex-wrap: wrap;
      gap: 4px;
      margin-right: 6px;

      code {
        padding: 0 6px;
        border-radius: 4px;
        background: var(--kp-divider);
        font-size: 11.5px;
        color: var(--kp-text-secondary);
      }
    }
  }
</style>
