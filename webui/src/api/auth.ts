/** 认证模块 API。对应后端 /auth/*。 */
import http, { unwrap } from '@/utils/http';
import type {
  AdminUser,
  ChangePasswordRequest,
  LoginRequest,
  LoginResponse,
  RegisterRequest,
  RegistrationSettings,
  SendVerifyCodeRequest,
  SendVerifyCodeResult,
  SmtpVerifyResult,
  ApiResponse,
} from '@/types/api';

export const authApi = {
  login(payload: LoginRequest) {
    return unwrap<LoginResponse>(http.post<ApiResponse<LoginResponse>>('/auth/login', payload));
  },
  me() {
    return unwrap<AdminUser>(http.get<ApiResponse<AdminUser>>('/auth/me'));
  },
  permissions() {
    return unwrap<{ root: boolean }>(
      http.get<ApiResponse<{ root: boolean }>>('/auth/permissions', { timeout: 5_000 }),
    );
  },
  register(payload: RegisterRequest) {
    return unwrap<LoginResponse>(http.post<ApiResponse<LoginResponse>>('/auth/register', payload));
  },
  sendVerifyCode(payload: SendVerifyCodeRequest) {
    return unwrap<SendVerifyCodeResult>(
      http.post<ApiResponse<SendVerifyCodeResult>>('/auth/send-verify-code', payload),
    );
  },
  registrationSettings() {
    return unwrap<RegistrationSettings>(
      http.get<ApiResponse<RegistrationSettings>>('/auth/registration-settings'),
    );
  },
  changePassword(payload: ChangePasswordRequest) {
    return unwrap<void>(http.post<ApiResponse<void>>('/auth/password', payload));
  },
  verifySmtp(token: string) {
    return unwrap<SmtpVerifyResult>(
      http.post<ApiResponse<SmtpVerifyResult>>('/auth/smtp-verify', { token }),
    );
  },
};
