<script setup lang="ts">
  import { computed } from 'vue';
  import type { Account } from '@/types/api';
  import ChatGptOAuthFlow from './ChatGptOAuthFlow.vue';

  const props = defineProps<{
    visible: boolean;
    accountId?: string | null;
  }>();

  const emit = defineEmits<{
    (e: 'update:visible', value: boolean): void;
    (e: 'saved', account: Account): void;
  }>();

  const visibleValue = computed({
    get: () => props.visible,
    set: (value: boolean) => emit('update:visible', value),
  });

  const title = computed(() => (props.accountId ? '重新授权 ChatGPT 账号' : '授权 ChatGPT 账号'));
</script>

<template>
  <el-dialog
    v-model="visibleValue"
    :title="title"
    width="min(520px, calc(100vw - 24px))"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <ChatGptOAuthFlow
      :active="visible"
      :account-id="accountId"
      @saved="(account) => emit('saved', account)"
    />

    <template #footer>
      <el-button @click="visibleValue = false">关闭</el-button>
    </template>
  </el-dialog>
</template>
