<script setup lang="ts">
  import { Connection, Check } from '@element-plus/icons-vue';
  import { locale, setLocale, type Locale } from '@/i18n';
</script>

<template>
  <el-dropdown trigger="click" @command="(value: Locale) => setLocale(value)">
    <button class="language-select" type="button" aria-label="Language / 语言">
      <el-icon><Connection /></el-icon>
      <span>{{ locale === 'en-US' ? 'English' : '简体中文' }}</span>
    </button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item command="zh-CN" lang="zh-CN">
          简体中文 <el-icon v-if="locale === 'zh-CN'"><Check /></el-icon>
        </el-dropdown-item>
        <el-dropdown-item command="en-US" lang="en">
          English <el-icon v-if="locale === 'en-US'"><Check /></el-icon>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
  .language-select {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    min-height: 36px;
    padding: 6px 8px;
    border: 0;
    border-radius: 5px;
    color: var(--kp-text-secondary);
    background: transparent;
    font: inherit;
    font-size: 13px;
    white-space: nowrap;
    cursor: pointer;
  }
  .language-select:hover,
  .language-select:focus-visible {
    background: var(--el-fill-color-light);
    color: var(--kp-primary);
  }
</style>
