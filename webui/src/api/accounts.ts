/** 上游账号管理 API。对应后端 /admin/accounts/*。 */
import http, { unwrap } from '@/utils/http';
import type {
  Account,
  AccountAllocationPolicy,
  AccountPoolStatus,
  AccountSettings,
  ApiResponse,
  ClearAccountsResult,
  ChatGptOAuthStartRequest,
  ChatGptOAuthStartResult,
  ChatGptOAuthStatusResult,
  CreateAccountRequest,
  ImportAccountsRequest,
  ImportAccountsResult,
  SuspendAccountRequest,
  TestModelOptionsResult,
  TestAccountsRequest,
  TestAccountsResult,
  UpdateAccountSettingsRequest,
  UpdateAccountAllocationPolicyRequest,
  UpdateAccountRequest,
  OpenAiQuotaResponse,
} from '@/types/api';

export const accountsApi = {
  list() {
    return unwrap<Account[]>(http.get<ApiResponse<Account[]>>('/admin/accounts'));
  },
  openAiQuota(accountId: string) {
    return unwrap<OpenAiQuotaResponse>(
      http.get<ApiResponse<OpenAiQuotaResponse>>(`/admin/accounts/${accountId}/openai-quota`),
    );
  },
  allocationPolicy(accountId: string) {
    return unwrap<AccountAllocationPolicy>(
      http.get<ApiResponse<AccountAllocationPolicy>>(
        `/admin/accounts/${accountId}/allocation-policy`,
      ),
    );
  },
  create(payload: CreateAccountRequest) {
    return unwrap<Account>(http.post<ApiResponse<Account>>('/admin/accounts', payload));
  },
  startChatGptOAuth(payload: ChatGptOAuthStartRequest = {}) {
    return unwrap<ChatGptOAuthStartResult>(
      http.post<ApiResponse<ChatGptOAuthStartResult>>(
        '/admin/accounts/chatgpt-oauth/start',
        payload,
      ),
    );
  },
  pollChatGptOAuth(loginId: string) {
    return unwrap<ChatGptOAuthStatusResult>(
      http.post<ApiResponse<ChatGptOAuthStatusResult>>(
        `/admin/accounts/chatgpt-oauth/${loginId}/poll`,
      ),
    );
  },
  cancelChatGptOAuth(loginId: string) {
    return unwrap<ChatGptOAuthStatusResult>(
      http.delete<ApiResponse<ChatGptOAuthStatusResult>>(
        `/admin/accounts/chatgpt-oauth/${loginId}`,
      ),
    );
  },
  importBatch(payload: ImportAccountsRequest | Record<string, unknown>) {
    return unwrap<ImportAccountsResult>(
      http.post<ApiResponse<ImportAccountsResult>>('/admin/accounts/import', payload),
    );
  },
  settings() {
    return unwrap<AccountSettings>(
      http.get<ApiResponse<AccountSettings>>('/admin/accounts/settings'),
    );
  },
  poolStatus() {
    return unwrap<AccountPoolStatus>(
      http.get<ApiResponse<AccountPoolStatus>>('/admin/accounts/pool-status'),
    );
  },
  updateSettings(payload: UpdateAccountSettingsRequest) {
    return unwrap<AccountSettings>(
      http.patch<ApiResponse<AccountSettings>>('/admin/accounts/settings', payload),
    );
  },
  update(accountId: string, payload: UpdateAccountRequest) {
    return unwrap<Account>(
      http.patch<ApiResponse<Account>>(`/admin/accounts/${accountId}`, payload),
    );
  },
  updateAllocationPolicy(accountId: string, payload: UpdateAccountAllocationPolicyRequest) {
    return unwrap<AccountAllocationPolicy>(
      http.patch<ApiResponse<AccountAllocationPolicy>>(
        `/admin/accounts/${accountId}/allocation-policy`,
        payload,
      ),
    );
  },
  suspend(accountId: string, payload: SuspendAccountRequest) {
    return unwrap<Account>(
      http.post<ApiResponse<Account>>(`/admin/accounts/${accountId}/suspend`, payload),
    );
  },
  reset(accountId: string) {
    return unwrap<Account>(http.post<ApiResponse<Account>>(`/admin/accounts/${accountId}/reset`));
  },
  test(payload: TestAccountsRequest) {
    return unwrap<TestAccountsResult>(
      http.post<ApiResponse<TestAccountsResult>>('/admin/accounts/test', payload),
    );
  },
  runDisabledScheduledTest() {
    return unwrap<TestAccountsResult>(
      http.post<ApiResponse<TestAccountsResult>>('/admin/accounts/disabled-scheduled-test/run'),
    );
  },
  testModels() {
    return unwrap<TestModelOptionsResult>(
      http.get<ApiResponse<TestModelOptionsResult>>('/admin/accounts/test-models'),
    );
  },
  remove(accountId: string) {
    return unwrap<void>(http.delete<ApiResponse<void>>(`/admin/accounts/${accountId}`));
  },
  clearAll() {
    return unwrap<ClearAccountsResult>(
      http.delete<ApiResponse<ClearAccountsResult>>('/admin/accounts'),
    );
  },
};
