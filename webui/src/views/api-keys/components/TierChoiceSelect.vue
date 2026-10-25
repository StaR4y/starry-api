<script setup lang="ts">
  /**
   * 号池的下拉框：每个选项两行，名称和它的价格或含义。API Key 表单的号源偏好和客户端配置对话框的号池用同一个。
   */
  import type { PreferenceChoice } from '../tierPreference';

  // 其余属性（aria-label）原样交给下拉框
  defineProps<{
    options: PreferenceChoice[];
    size?: 'small' | 'default' | 'large';
  }>();
  const model = defineModel<string>({ required: true });
</script>

<template>
  <el-select
    v-model="model"
    :size="size"
    class="tier-preference-select"
    popper-class="tier-preference-popper"
  >
    <el-option
      v-for="choice in options"
      :key="choice.value"
      :label="choice.label"
      :value="choice.value"
    >
      <div class="tier-option">
        <strong>{{ choice.label }}</strong>
        <span>{{ choice.hint }}</span>
      </div>
    </el-option>
  </el-select>
</template>

<style scoped lang="scss">
  .tier-preference-select {
    width: 100%;
  }

  /* 下拉层挂在 body 上；选项有两行（名称和价格），不能被限制在一行的高度里 */
  :global(.tier-preference-popper .el-select-dropdown__item) {
    height: auto;
    min-height: 34px;
    padding-top: 6px;
    padding-bottom: 6px;
    line-height: 1.5;
  }

  .tier-option {
    display: flex;
    flex-direction: column;
    justify-content: center;
    min-width: 0;
    line-height: 1.35;

    strong {
      overflow: hidden;
      font-size: 13px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    > span {
      overflow: hidden;
      color: var(--kp-text-muted);
      font-size: 11.5px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }
</style>
