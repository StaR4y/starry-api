/** 管理员用户管理 API。对应后端 /admin/users/*。 */
import http, { unwrap } from '@/utils/http';
import type {
  AdminUser,
  AdminUserCreated,
  ApiResponse,
  CreateAdminUserRequest,
  RegistrationSettings,
  ResetPasswordResult,
  UpdateRegistrationSettingsRequest,
  UpdateAdminUserRequest,
} from '@/types/api';

export const usersApi = {
  list() {
    return unwrap<AdminUser[]>(http.get<ApiResponse<AdminUser[]>>('/admin/users'));
  },
  create(payload: CreateAdminUserRequest) {
    return unwrap<AdminUserCreated>(
      http.post<ApiResponse<AdminUserCreated>>('/admin/users', payload),
    );
  },
  update(userId: string, payload: UpdateAdminUserRequest) {
    return unwrap<AdminUser>(
      http.patch<ApiResponse<AdminUser>>(`/admin/users/${userId}`, payload),
    );
  },
  resetPassword(userId: string) {
    return unwrap<ResetPasswordResult>(
      http.post<ApiResponse<ResetPasswordResult>>(`/admin/users/${userId}/reset-password`),
    );
  },
  registrationSettings() {
    return unwrap<RegistrationSettings>(
      http.get<ApiResponse<RegistrationSettings>>('/admin/users/registration-settings'),
    );
  },
  updateRegistrationSettings(payload: UpdateRegistrationSettingsRequest) {
    return unwrap<RegistrationSettings>(
      http.patch<ApiResponse<RegistrationSettings>>('/admin/users/registration-settings', payload),
    );
  },
};
