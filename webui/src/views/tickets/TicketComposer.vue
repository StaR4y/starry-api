<script setup lang="ts">
  import { computed, ref } from 'vue';
  import { ElMessage } from 'element-plus';
  import { Paperclip, Close, Promotion } from '@element-plus/icons-vue';
  import { ticketCategories, ticketStatuses, type TicketStatus } from '@/api/tickets';
  const props = defineProps<{ creating?: boolean; admin?: boolean; busy: boolean }>();
  const emit = defineEmits<{
    submit: [
      input: { title: string; category: string; body: string; status: TicketStatus },
      files: File[],
    ];
  }>();
  const title = ref('');
  const category = ref('GENERAL');
  const body = ref('');
  const status = ref<TicketStatus>('WAITING_USER');
  const files = ref<File[]>([]);
  const picker = ref<HTMLInputElement>();
  const dirty = computed(() =>
    Boolean(
      title.value.trim() ||
        body.value.trim() ||
        files.value.length ||
        (props.creating && category.value !== 'GENERAL'),
    ),
  );
  function addFiles(event: Event) {
    const input = event.target as HTMLInputElement;
    if (props.busy) {
      input.value = '';
      return;
    }
    const selected = Array.from(input.files ?? []);
    if (selected.length + files.value.length > 3) ElMessage.warning('每次最多上传 3 个附件');
    else if (selected.some((file) => file.size === 0 || file.size > 2 * 1024 * 1024))
      ElMessage.warning('每个附件需大于 0 字节且不超过 2 MB');
    else files.value.push(...selected);
    input.value = '';
  }
  function submit() {
    if (props.busy || !body.value.trim() || (props.creating && !title.value.trim())) return;
    emit(
      'submit',
      {
        title: title.value.trim(),
        category: category.value,
        body: body.value.trim(),
        status: status.value,
      },
      files.value,
    );
  }
  function clear() {
    body.value = '';
    title.value = '';
    files.value = [];
    category.value = 'GENERAL';
  }
  defineExpose({ clear, dirty });
</script>

<template>
  <el-form
    class="composer"
    :class="{ 'is-creating': creating }"
    label-position="top"
    @submit.prevent="submit"
  >
    <div v-if="creating" class="composer__fields">
      <el-form-item label="标题" required>
        <el-input
          v-model="title"
          aria-label="工单标题"
          placeholder="简要概括遇到的问题"
          :maxlength="160"
          show-word-limit
          :disabled="busy"
        />
      </el-form-item>
      <el-form-item label="问题分类">
        <el-select v-model="category" aria-label="问题分类" :disabled="busy">
          <el-option
            v-for="(label, value) in ticketCategories"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
      </el-form-item>
    </div>
    <el-form-item :label="creating ? '问题描述' : '回复内容'" required>
      <el-input
        v-model="body"
        type="textarea"
        :aria-label="creating ? '问题描述' : '回复内容'"
        :autosize="creating ? { minRows: 12, maxRows: 24 } : { minRows: 6, maxRows: 18 }"
        :placeholder="
          creating ? '问题发生时间、相关请求与错误信息…' : '补充问题详情或回复处理结果…'
        "
        :maxlength="20000"
        show-word-limit
        :disabled="busy"
      />
    </el-form-item>
    <div v-if="files.length" class="composer__files" aria-label="待上传附件">
      <div v-for="(file, index) in files" :key="index" class="composer__file">
        <el-icon><Paperclip /></el-icon><span>{{ file.name }}</span><small>{{ Math.ceil(file.size / 1024) }} KB</small><el-button
          :icon="Close"
          text
          :disabled="busy"
          :aria-label="`移除 ${file.name}`"
          @click="files.splice(index, 1)"
        />
      </div>
    </div>
    <div class="composer__actions">
      <input ref="picker" type="file" multiple hidden @change="addFiles">
      <el-tooltip content="最多 3 个附件，每个不超过 2 MB">
        <el-button :icon="Paperclip" :disabled="busy || files.length >= 3" @click="picker?.click()">
          添加附件
        </el-button>
      </el-tooltip>
      <div class="composer__send">
        <el-select
          v-if="admin && !creating"
          v-model="status"
          aria-label="回复后的状态"
          :disabled="busy"
        >
          <el-option
            v-for="(label, value) in ticketStatuses"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
        <el-button
          type="primary"
          :icon="Promotion"
          :loading="busy"
          :disabled="busy || !body.trim() || (creating && !title.trim())"
          native-type="submit"
        >
          {{ creating ? '提交工单' : '发送回复' }}
        </el-button>
      </div>
    </div>
  </el-form>
</template>

<style scoped lang="scss">
  .composer {
    min-width: 0;
    :deep(.el-form-item) {
      min-width: 0;
    }
    :deep(.el-textarea__inner) {
      line-height: 1.8;
      padding: 12px 14px 28px;
    }
    :deep(.el-input__count) {
      font-size: 11px;
    }
    &__fields {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 200px;
      gap: 16px;
    }
    &__actions,
    &__send {
      display: flex;
      align-items: center;
      gap: 12px;
      justify-content: space-between;
      flex-wrap: wrap;
      .el-button + .el-button {
        margin-left: 0;
      }
    }
    &__actions {
      padding-top: 16px;
      border-top: 1px solid var(--kp-divider);
    }
    &__send .el-select {
      width: 136px;
    }
    &__files {
      display: grid;
      gap: 8px;
      margin-bottom: 16px;
    }
    &__file {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 12px;
      padding: 6px 10px;
      border: 1px solid var(--kp-divider);
      border-radius: 6px;
      min-width: 0;
      span {
        overflow-wrap: anywhere;
        flex: 1;
        min-width: 0;
      }
      small {
        white-space: nowrap;
        color: var(--kp-text-muted);
      }
    }
    @media (max-width: 600px) {
      &__fields {
        grid-template-columns: 1fr;
        gap: 0;
      }
      &__actions {
        align-items: flex-start;
        flex-direction: column;
      }
      &__send {
        width: 100%;
        justify-content: flex-end;
        flex-wrap: wrap;
      }
    }
  }
</style>
