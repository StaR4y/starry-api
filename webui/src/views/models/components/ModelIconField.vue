<script setup lang="ts">
  /** 模型图标字段：紧凑的预览 + 上传/替换/移除，添加页与编辑弹窗共用。 */
  import { ref, watch } from 'vue';
  import { ElMessage, type UploadRawFile, type UploadRequestOptions } from 'element-plus';
  import { Delete, Upload } from '@element-plus/icons-vue';
  import ProviderLogo from '@/components/ProviderLogo.vue';
  import { modelsApi } from '@/api';

  const props = defineProps<{
    icon?: string | null;
    provider?: string;
  }>();

  const emit = defineEmits<{
    'update:icon': [value: string | null];
  }>();

  const uploading = ref(false);
  const broken = ref(false);

  watch(
    () => props.icon,
    () => {
      broken.value = false;
    },
  );

  function validate(file: UploadRawFile) {
    const supportedTypes = ['image/png', 'image/jpeg', 'image/webp'];
    if (!supportedTypes.includes(file.type)) {
      ElMessage.warning('请选择 PNG、JPEG 或 WebP 图片');
      return false;
    }
    if (file.size > 2 * 1024 * 1024) {
      ElMessage.warning('模型图标不能超过 2 MB');
      return false;
    }
    return true;
  }

  async function upload(options: UploadRequestOptions) {
    uploading.value = true;
    try {
      const uploaded = await modelsApi.uploadIcon(options.file);
      emit('update:icon', uploaded.url);
      options.onSuccess(uploaded);
      ElMessage.success('模型图标已上传');
    } catch (error) {
      const uploadError = Object.assign(
        new Error(error instanceof Error ? error.message : '图标上传失败'),
        { status: 0, method: 'POST', url: '/admin/models/icon' },
      );
      options.onError(uploadError);
    } finally {
      uploading.value = false;
    }
  }

  function remove() {
    emit('update:icon', null);
  }
</script>

<template>
  <div class="model-icon-field">
    <el-avatar
      v-if="icon && !broken"
      :src="icon"
      :size="46"
      shape="square"
      class="preview"
      @error="broken = true"
    />
    <ProviderLogo v-else :provider="provider || 'Custom'" :size="46" />
    <div class="meta">
      <div class="actions">
        <el-upload
          action="#"
          accept=".png,.jpg,.jpeg,.webp,image/png,image/jpeg,image/webp"
          :show-file-list="false"
          :before-upload="validate"
          :http-request="upload"
          :disabled="uploading"
        >
          <el-button size="small" :icon="Upload" :loading="uploading">
            {{ uploading ? '上传中…' : icon ? '替换图标' : '上传图标' }}
          </el-button>
        </el-upload>
        <el-tooltip v-if="icon" content="移除图标" placement="top">
          <el-button
            size="small"
            text
            type="danger"
            :icon="Delete"
            aria-label="移除图标"
            @click="remove"
          />
        </el-tooltip>
      </div>
      <p class="tip">PNG / JPEG / WebP，不超过 2 MB；不上传则使用厂商默认图标</p>
    </div>
  </div>
</template>

<style scoped lang="scss">
  .model-icon-field {
    display: flex;
    align-items: center;
    width: 100%;
    min-width: 0;
    gap: 12px;
  }

  .preview {
    flex: 0 0 46px;
    border: 1px solid var(--kp-divider);
    border-radius: 7px;
    background: #f2f4f8;
  }

  .meta {
    display: flex;
    min-width: 0;
    flex: 1;
    flex-direction: column;
    gap: 4px;
  }

  .actions {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .tip {
    margin: 0;
    color: var(--kp-text-muted);
    font-size: 11.5px;
    line-height: 1.4;
  }
</style>
