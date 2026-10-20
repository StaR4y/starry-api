import { t } from '@/i18n';
export const subscriptionStates = [
  {
    value: 'ACTIVE',
    get label() {
      return t('使用中');
    },
  },
  {
    value: 'SCHEDULED',
    get label() {
      return t('待生效');
    },
  },
  {
    value: 'EXHAUSTED',
    get label() {
      return t('已用尽');
    },
  },
  {
    value: 'EXPIRED',
    get label() {
      return t('已到期');
    },
  },
  {
    value: 'REVOKED',
    get label() {
      return t('已撤销');
    },
  },
  {
    value: 'CANCELLED',
    get label() {
      return t('已取消');
    },
  },
];
export const stateLabel = (value: string) =>
  subscriptionStates.find((s) => s.value === value)?.label ?? value;
export const stateType = (value: string) =>
  value === 'ACTIVE' ? 'success' : value === 'SCHEDULED' ? 'primary' : 'info';
export function credits(value: string | number | null | undefined) {
  const [integer, decimal] = String(value ?? 0).split('.');
  const fraction = decimal?.replace(/0+$/, '');
  return integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (fraction ? `.${fraction}` : '');
}
export const ledgerLabel = (value: string) =>
  ({
    get GRANT() {
      return t('开通额度');
    },
    get MIGRATION_OPENING() {
      return t('期初余额');
    },
    get RESERVE() {
      return t('请求预占');
    },
    get SETTLE() {
      return t('请求结算');
    },
    get RELEASE() {
      return t('退回预占');
    },
    get TERM_ADVANCED() {
      return t('下一期提前生效');
    },
    get ACTIVE() {
      return t('恢复订阅');
    },
    get REVOKED() {
      return t('撤销订阅');
    },
    get CANCELLED() {
      return t('取消订阅');
    },
  })[value] ?? value;
