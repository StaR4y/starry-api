/** Coding 套餐订阅 API。用户侧 /payment/coding-plans，管理员侧 /admin/coding-plans。 */
import http, { unwrap } from '@/utils/http';
import type {
  AdminGrantSubscriptionRequest,
  ApiResponse,
  CodingPlan,
  CodingPlanSubscription,
  CreateCodingPlanRequest,
  PurchaseCodingPlanResponse,
  UpdateCodingPlanRequest,
} from '@/types/api';

export const codingPlanApi = {
  plans() {
    return unwrap<CodingPlan[]>(http.get<ApiResponse<CodingPlan[]>>('/payment/coding-plans'));
  },
  purchase(planId: string, version: number, idempotencyKey: string) {
    return unwrap<PurchaseCodingPlanResponse>(
      http.post<ApiResponse<PurchaseCodingPlanResponse>>(
        `/payment/coding-plans/${planId}/purchase`,
        {
          version,
        },
        { headers: { 'Idempotency-Key': idempotencyKey } },
      ),
    );
  },
  subscriptions() {
    return unwrap<CodingPlanSubscription[]>(
      http.get<ApiResponse<CodingPlanSubscription[]>>('/payment/coding-plan-subscriptions'),
    );
  },
  page(params: SubscriptionQuery = {}) {
    return unwrap<SubscriptionPage>(
      http.get('/payment/coding-plan-subscriptions/page', { params }),
    );
  },
  summary(provider = '') {
    return unwrap<SubscriptionSummary>(
      http.get('/payment/coding-plan-subscriptions/summary', { params: { provider } }),
    );
  },
  ledger(id: string, before = 0) {
    return unwrap<SubscriptionLedgerEntry[]>(
      http.get(`/payment/coding-plan-subscriptions/${id}/ledger`, { params: { before } }),
    );
  },
};

export const adminCodingPlanApi = {
  plans() {
    return unwrap<CodingPlan[]>(http.get<ApiResponse<CodingPlan[]>>('/admin/coding-plans'));
  },
  createPlan(payload: CreateCodingPlanRequest) {
    return unwrap<CodingPlan>(http.post<ApiResponse<CodingPlan>>('/admin/coding-plans', payload));
  },
  updatePlan(planId: string, payload: UpdateCodingPlanRequest) {
    return unwrap<CodingPlan>(
      http.patch<ApiResponse<CodingPlan>>(`/admin/coding-plans/${planId}`, payload),
    );
  },
  subscriptions(userId?: string) {
    return unwrap<CodingPlanSubscription[]>(
      http.get<ApiResponse<CodingPlanSubscription[]>>('/admin/coding-plan-subscriptions', {
        params: userId ? { userId } : undefined,
      }),
    );
  },
  grantSubscription(payload: AdminGrantSubscriptionRequest, idempotencyKey: string) {
    return unwrap<CodingPlanSubscription>(
      http.post<ApiResponse<CodingPlanSubscription>>('/admin/coding-plan-subscriptions', payload, {
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    );
  },
  updateSubscriptionStatus(subscriptionId: string, status: string, reason: string) {
    return unwrap<CodingPlanSubscription>(
      http.patch<ApiResponse<CodingPlanSubscription>>(
        `/admin/coding-plan-subscriptions/${subscriptionId}`,
        {
          status,
          reason,
        },
      ),
    );
  },
  page(params: SubscriptionQuery = {}) {
    return unwrap<SubscriptionPage>(http.get('/admin/coding-plan-subscriptions/page', { params }));
  },
  ledger(id: string, before = 0) {
    return unwrap<SubscriptionLedgerEntry[]>(
      http.get(`/admin/coding-plan-subscriptions/${id}/ledger`, { params: { before } }),
    );
  },
  reviews() {
    return unwrap<BillingReview[]>(http.get('/admin/billing/reviews'));
  },
  resolve(hash: string, payload: { action: string; reason: string; credits?: string }) {
    return unwrap<void>(http.post(`/admin/billing/reviews/${hash}/resolve`, payload));
  },
};

export interface SubscriptionQuery {
  page?: number;
  pageSize?: number;
  status?: string;
  search?: string;
}
export interface SubscriptionSummary {
  availableCredits: string;
  reservedCredits: string;
  nextExpiry: string | null;
  activeCount: number;
}
export interface SubscriptionPage {
  items: CodingPlanSubscription[];
  total: number;
  page: number;
  pageSize: number;
}
export interface SubscriptionLedgerEntry {
  id: number;
  entryType: string;
  amount: number;
  reservedDelta: number;
  referenceId: string | null;
  actorId: string | null;
  reason: string | null;
  createdAt: string;
}
export interface BillingReview {
  request_hash: string;
  request_id: string;
  user_id: string;
  billing_source: string;
  reserved_credits: number;
  actual_credits: number | null;
  reason: string;
  created_at: string;
}
