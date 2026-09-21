export const subscriptionStates = [
  { value: 'ACTIVE', label: '使用中' },
  { value: 'SCHEDULED', label: '待生效' },
  { value: 'EXHAUSTED', label: '已用尽' },
  { value: 'EXPIRED', label: '已到期' },
  { value: 'REVOKED', label: '已撤销' },
  { value: 'CANCELLED', label: '已取消' },
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
    GRANT: '开通额度',
    MIGRATION_OPENING: '期初余额',
    RESERVE: '请求预占',
    SETTLE: '请求结算',
    RELEASE: '退回预占',
    TERM_ADVANCED: '下一期提前生效',
    ACTIVE: '恢复订阅',
    REVOKED: '撤销订阅',
    CANCELLED: '取消订阅',
  })[value] ?? value;
