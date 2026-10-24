import { t } from '@/i18n';

/** 账号生命周期状态的显示名称 */
export function lifecycleLabel(state: string | null | undefined): string {
  switch (state) {
    case 'WARMING':
      return t('预热中');
    case 'HEALTHY':
      return t('健康');
    case 'DEGRADED':
      return t('性能下降');
    case 'COOLDOWN':
      return t('冷却中');
    case 'QUARANTINED':
      return t('已隔离');
    case 'DISABLED':
      return t('已停用');
    default:
      return state ?? '-';
  }
}

export function lifecycleTagType(state: string | null | undefined) {
  if (state === 'HEALTHY') return 'success';
  if (state === 'WARMING') return 'primary';
  if (state === 'DEGRADED' || state === 'COOLDOWN') return 'warning';
  return 'danger';
}
