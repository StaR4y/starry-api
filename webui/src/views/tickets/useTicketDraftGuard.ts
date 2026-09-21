import { onMounted, onBeforeUnmount, type Ref } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';

export function useTicketDraftGuard(dirty: Readonly<Ref<boolean>>, busy: Readonly<Ref<boolean>>) {
  async function confirmLeave() {
    if (busy.value) {
      ElMessage.warning('正在提交，请稍候');
      return false;
    }
    if (!dirty.value) return true;
    return ElMessageBox.confirm('尚有未提交的内容，离开后将丢失。', '离开工单？', {
      confirmButtonText: '离开',
      cancelButtonText: '继续编辑',
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
