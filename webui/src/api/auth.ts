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
  ApiResponse,
} from '@/types/api';

export const authApi = {
  login(payload: LoginRequest) {
    return unwrap<LoginResponse>(http.post<ApiResponse<LoginResponse>>('/auth/login', payload));
  },
  me() {
    return unwrap<AdminUser>(http.get<ApiResponse<AdminUser>>('/auth/me'));
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
};
