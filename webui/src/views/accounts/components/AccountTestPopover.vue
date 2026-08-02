<script setup lang="ts">
  /**
   * 账号列表操作列内嵌的单账号测试弹层。
   *
   * 从 AccountListView.vue 原样抽出：模型选择、提示词、
   * 测试选项与触发按钮；测试执行与结果详情由父组件处理。
   */
  import { computed } from 'vue';
  import AppIcon from './AppIcon.vue';
  import type { TestAccountItem, TestModelOption } from '@/types/api';
  import { testStatusType } from '../utils';

  const props = defineProps<{
    latestTest?: TestAccountItem;
    testing: boolean;
    modelOptions: TestModelOption[];
    model: string;
    prompt: string;
    onlyEnabled: boolean;
    updateStatus: boolean;
  }>();

  const emit = defineEmits<{
    (e: 'update:model', value: string): void;
    (e: 'update:prompt', value: string): void;
    (e: 'update:onlyEnabled', value: boolean): void;
    (e: 'update:updateStatus', value: boolean): void;
    (e: 'ensure-model'): void;
    (e: 'test'): void;
    (e: 'show-detail'): void;
  }>();

  const modelValue = computed({
    get: () => props.model,
    set: (value: string) => emit('update:model', value),
  });
  const promptValue = computed({
    get: () => props.prompt,
    set: (value: string) => emit('update:prompt', value),
  });
  const onlyEnabledValue = computed({
    get: () => props.onlyEnabled,
    set: (value: boolean) => emit('update:onlyEnabled', value),
  });
  const updateStatusValue = computed({
    get: () => props.updateStatus,
    set: (value: boolean) => emit('update:updateStatus', value),
  });
</script>

<template>
  <el-popover placement="bottom-end" width="320" trigger="click" @show="emit('ensure-model')">
    <template #reference>
      <el-button link type="primary" class="test-trigger-btn">
        <template #icon>
          <AppIcon name="play" width="12" height="12" class="trigger-icon" />
        </template>
        测试
      </el-button>
    </template>
    <div class="row-test-panel">
      <div class="row-test-panel__title">
        <span>账号测试</span>
        <el-tag v-if="latestTest" :type="testStatusType(latestTest.status)" effect="light" round>
          {{ latestTest.status }}
        </el-tag>
      </div>
      <el-form label-position="top" size="small">
        <el-form-item label="模型">
          <el-select
            v-model="modelValue"
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入模型 ID"
            class="w-full"
            @focus="emit('ensure-model')"
          >
            <el-option
              v-for="item in modelOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="提示词">
          <el-input v-model="promptValue" />
        </el-form-item>
        <div class="row-test-panel__checks">
          <el-checkbox v-model="onlyEnabledValue">仅测试当前可用账号</el-checkbox>
          <el-checkbox v-model="updateStatusValue">测试后自动更新账号状态</el-checkbox>
        </div>
      </el-form>
      <div class="row-test-panel__actions">
        <el-button size="small" type="primary" :loading="testing" @click="emit('test')">
          自动测试
        </el-button>
        <el-button size="small" :disabled="!latestTest" @click="emit('show-detail')">
          查看结果
        </el-button>
      </div>
    </div>
  </el-popover>
</template>

<style scoped lang="scss">
  // 原父级 .actions-wrapper .el-button { margin: 0 } 对触发按钮的等效规则
  .test-trigger-btn.el-button {
    margin: 0;
  }

  .trigger-icon {
    margin-right: 2px;
  }

  .w-full {
    width: 100%;
  }

  .row-test-panel {
    display: grid;
    gap: 10px;

    &__title,
    &__actions {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
    }

    &__title {
      font-weight: 700;
    }

    &__checks {
      display: grid;
      gap: 4px;
    }
  }
</style>
