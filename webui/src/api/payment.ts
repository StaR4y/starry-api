/** 支付 API。用户侧 /payment/*，管理员侧 /admin/payment/*。 */
import http, { unwrap } from '@/utils/http';
import type {
  ApiResponse,
  CreatePaymentOrderRequest,
  CreatePaymentOrderResponse,
  CreateRedemptionCodeRequest,
  CreateRedemptionCodesResponse,
  PaymentOrder,
  PaymentSettings,
  RedeemCodeRequest,
  RedeemCodeResponse,
  RedemptionCode,
  UpdatePaymentSettingsRequest,
} from '@/types/api';

export const paymentApi = {
  settings() {
    return unwrap<PaymentSettings>(http.get<ApiResponse<PaymentSettings>>('/payment/settings'));
  },
  orders() {
    return unwrap<PaymentOrder[]>(http.get<ApiResponse<PaymentOrder[]>>('/payment/orders'));
  },
  createOrder(payload: CreatePaymentOrderRequest) {
    return unwrap<CreatePaymentOrderResponse>(
      http.post<ApiResponse<CreatePaymentOrderResponse>>('/payment/orders', payload),
    );
  },
  cancelOrder(orderId: string) {
    return unwrap<PaymentOrder>(
      http.post<ApiResponse<PaymentOrder>>(`/payment/orders/${orderId}/cancel`),
    );
  },
  redeemCode(payload: RedeemCodeRequest) {
    return unwrap<RedeemCodeResponse>(
      http.post<ApiResponse<RedeemCodeResponse>>('/payment/redeem-code', payload),
    );
  },
};

export const adminPaymentApi = {
  settings() {
    return unwrap<PaymentSettings>(http.get<ApiResponse<PaymentSettings>>('/admin/payment/settings'));
  },
  updateSettings(payload: UpdatePaymentSettingsRequest) {
    return unwrap<PaymentSettings>(
      http.patch<ApiResponse<PaymentSettings>>('/admin/payment/settings', payload),
    );
  },
  orders() {
    return unwrap<PaymentOrder[]>(http.get<ApiResponse<PaymentOrder[]>>('/admin/payment/orders'));
  },
  redemptionCodes() {
    return unwrap<RedemptionCode[]>(
      http.get<ApiResponse<RedemptionCode[]>>('/admin/payment/redemption-codes'),
    );
  },
  createRedemptionCodes(payload: CreateRedemptionCodeRequest) {
    return unwrap<CreateRedemptionCodesResponse>(
      http.post<ApiResponse<CreateRedemptionCodesResponse>>('/admin/payment/redemption-codes', payload),
    );
  },
  revokeRedemptionCode(codeId: string) {
    return unwrap<RedemptionCode>(
      http.post<ApiResponse<RedemptionCode>>(`/admin/payment/redemption-codes/${codeId}/revoke`),
    );
  },
};
