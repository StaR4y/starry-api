/**
 * 账号表单各分区共享的状态，由 AccountFormDialog 通过 provide 提供。
 * 分区直接读写这些对象，避免把整个表单当作 prop 逐层传递和回写。
 */
import { inject, type ComputedRef, type InjectionKey, type Ref } from 'vue';
import type { ProxyCatalog } from '@/api/proxyChannels';
import type { Account } from '@/types/api';
import type { AccountTypeKey, CredentialSpec } from './accountTypes';
import type { AccountFormModel } from './formModel';
import type { AccountRouting } from './useAccountRouting';

export interface AccountFormContext {
  mode: ComputedRef<'create' | 'edit'>;
  /** 正在编辑的账号；新建时为 null */
  account: ComputedRef<Account | null>;
  form: AccountFormModel;
  accountType: Ref<AccountTypeKey>;
  /** Codex 新建：浏览器授权登录或手动填写令牌 */
  createMethod: Ref<'credentials' | 'chatgpt'>;
  spec: ComputedRef<CredentialSpec>;
  catalog: Ref<ProxyCatalog | null>;
  catalogLoading: Ref<boolean>;
  catalogFailed: Ref<boolean>;
  reloadCatalog: () => void;
  routing: AccountRouting;
}

export const accountFormKey: InjectionKey<AccountFormContext> = Symbol('accountForm');

export function useAccountFormContext(): AccountFormContext {
  const context = inject(accountFormKey);
  if (!context) throw new Error('Account form sections must be rendered inside AccountFormDialog');
  return context;
}
