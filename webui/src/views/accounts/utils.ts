/**
 * 账号管理页共享小函数。
 *
 * 阶段 4 样式清理时从 AccountListView.vue 与 components/ 各组件中
 * 收敛的重复定义，实现与原逐字一致，仅供 accounts 模块内部复用。
 */
import { ElMessage } from 'element-plus';
import type { Account, TestAccountItem } from '@/types/api';

export function normalized(value: string | null | undefined) {
  return (value ?? '').trim().toLowerCase();
}

export function normalizedPoolTier(value: string | null | undefined) {
  return normalized(value) === 'secondary' ? 'secondary' : 'primary';
}

export function poolTierLabel(value: string | null | undefined) {
  return normalizedPoolTier(value) === 'secondary' ? '二级号池' : '一级号池';
}

export function poolTierTagType(value: string | null | undefined) {
  return normalizedPoolTier(value) === 'secondary' ? 'warning' : 'success';
}

export function statusOf(account: Account) {
  if (!account.enabled) return { label: '已停用', tone: 'info' as const };
  if (account.suspendedAt) return { label: '挂起', tone: 'danger' as const };
  if (account.quotaExhaustedAt) return { label: '配额用尽', tone: 'warning' as const };
  return { label: '可用', tone: 'success' as const };
}

export function isCodexAccount(row: Account) {
  const provider = (row.provider || '').trim().toLowerCase();
  const authMethod = (row.authMethod || '').trim().toLowerCase();
  return provider === 'codex' || authMethod === 'codex';
}

export function getProviderDisplayName(provider: string | null | undefined): string {
  if (!provider) return 'Unknown';
  const p = provider.trim().toLowerCase();
  if (p === 'openai-compatible' || p === 'openaicompatibility') {
    return 'OpenAI-API';
  }
  return provider;
}

export function testStatusType(status: TestAccountItem['status']) {
  if (status === 'SUCCESS') return 'success';
  if (status === 'SKIPPED') return 'info';
  return 'danger';
}

export async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success('已复制');
  } catch {
    ElMessage.warning('请手动复制');
  }
}
