import { t } from '@/i18n';
import { onMounted, onBeforeUnmount, type Ref } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';

export function useTicketDraftGuard(dirty: Readonly<Ref<boolean>>, busy: Readonly<Ref<boolean>>) {
  async function confirmLeave() {
    if (busy.value) {
      ElMessage.warning(t('正在提交，请稍候'));
      return false;
    }
    if (!dirty.value) return true;
    return ElMessageBox.confirm(t('尚有未提交的内容，离开后将丢失。'), t('离开工单？'), {
      get confirmButtonText() {
        return t('离开');
      },
      get cancelButtonText() {
        return t('继续编辑');
      },
      type: 'warning',
      distinguishCancelAndClose: true,
    })
      .then(() => true)
      .catch(() => false);
  }

  function beforeUnload(event: BeforeUnloadEvent) {
    if (!dirty.value && !busy.value) return;
    event.preventDefault();
    event.returnValue = '';
  }

  onBeforeRouteLeave(confirmLeave);
  onBeforeRouteUpdate((to, from) => to.path === from.path || confirmLeave());
  onMounted(() => window.addEventListener('beforeunload', beforeUnload));
  onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload));
}
