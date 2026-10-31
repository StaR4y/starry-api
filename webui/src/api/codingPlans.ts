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
/**
 * 按号源分级服务的请求的核对行里，一个号池的信息。预留是按请求可能被服务的号池里预留最多的那个定额的，所以核对要看
 * 这里该号池的报价与估算，而不是预留金额。level 和 factor 对实际服务的号池是结算时用的值，对其他号池是预留时冻结的计划值。
 */
export interface BillingReviewTier {
  tierRef: string;
  level?: number | null;
  name?: string | null;
  /** 价格系数，十进制字符串 */
  factor?: string | null;
  /** 该号池在预留时的预留估算（credits，十进制字符串）：按它的报价和请求上界算出的上限 */
  estimate?: string | null;
  /** 最后派发到该号池的时间（只有 last_dispatched 有） */
  at?: string | null;
  /** 该号池的每 token 报价，十进制字符串：标准价乘系数 */
  quote?: BillingReviewQuote | null;
}

export interface BillingReviewQuote {
  input: string;
  output: string;
  cacheRead: string;
  cacheWrite: string;
  fallback: string;
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
  /** 以下只在请求按号源分级服务时才有 */
  tier_plan?: BillingReviewPlan | null;
  /** 实际服务（已按它结算过）的号池 */
  served?: BillingReviewTier | null;
  /** 最后派发到的号池：还没有结算时，用户最可能被哪个号池服务 */
  last_dispatched?: BillingReviewTier | null;
}

/** 预留时冻结的号池计划；levels 是请求可能服务的号池，按级别从好到坏 */
export interface BillingReviewPlan {
  vendor?: string | null;
  /** auto 网关选号池；pinned 客户端指定 */
  mode?: string | null;
  levels?: BillingReviewPlanLevel[] | null;
}

export interface BillingReviewPlanLevel {
  tierRef: string;
  level: number;
  name: string;
  factor: number;
  estimate: number | null;
}
