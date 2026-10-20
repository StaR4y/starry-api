<script setup lang="ts">
  import { t } from '@/i18n';
  import { computed, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import { ElMessage } from 'element-plus';
  import { ArrowLeft, EditPen } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { ticketApi, type TicketSubmission } from '@/api/tickets';
  import TicketComposer from './TicketComposer.vue';
  import { useTicketDraftGuard } from './useTicketDraftGuard';

  const router = useRouter();
  const saving = ref(false);
  const creator = ref<InstanceType<typeof TicketComposer>>();
  useTicketDraftGuard(
    computed(() => creator.value?.dirty ?? false),
    saving,
  );

  async function create(input: TicketSubmission, files: File[]) {
    if (saving.value) return;
    saving.value = true;
    try {
      const ticket = await ticketApi.create(input, files);
      creator.value?.clear();
      saving.value = false;
      ElMessage.success(t('工单已提交'));
      await router.replace({ name: 'ticket-detail', params: { id: ticket.id } });
    } catch {
      // Retain the draft and attachments when submission fails.
    } finally {
      saving.value = false;
    }
  }
</script>

<template>
  <PageSection :title="t('新建工单')" :subtitle="t('问题反馈')" :icon="EditPen">
    <template #actions>
      <el-button :icon="ArrowLeft" :disabled="saving" @click="router.push({ name: 'tickets' })">
        {{ t('返回工单列表') }}
      </el-button>
    </template>
    <TicketComposer ref="creator" creating :busy="saving" @submit="create" />
  </PageSection>
</template>
