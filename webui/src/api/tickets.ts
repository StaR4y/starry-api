import http, { unwrap } from '@/utils/http';

export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'WAITING_USER' | 'RESOLVED' | 'CLOSED';
export const ticketStatuses: Record<TicketStatus, string> = {
  OPEN: '待处理',
  IN_PROGRESS: '处理中',
  WAITING_USER: '等待用户',
  RESOLVED: '已解决',
  CLOSED: '已关闭',
};
export const ticketCategories: Record<string, string> = {
  GENERAL: '一般咨询',
  API: 'API 调用',
  BILLING: '计费与支付',
  ACCOUNT: '账号问题',
  BUG: '问题反馈',
};
export interface Ticket {
  id: string;
  userId: string;
  username: string;
  title: string;
  category: string;
  status: TicketStatus;
  version: number;
  messageCount: number;
  createdAt: string;
  updatedAt: string;
}
export interface Attachment {
  id: string;
  filename: string;
  sizeBytes: number;
}
export interface TicketMessage {
  id: number;
  actorName: string;
  admin: boolean;
  kind: 'MESSAGE' | 'STATUS';
  body: string;
  status: TicketStatus;
  createdAt: string;
  attachments: Attachment[];
}
export interface TicketPage {
  items: Ticket[];
  total: number;
  page: number;
  pageSize: number;
  counts: Partial<Record<TicketStatus, number>>;
}
export interface TicketThread {
  ticket: Ticket;
  messages: TicketMessage[];
  hasMore: boolean;
  nextBefore: number;
}
export interface TicketSettings {
  enabled: boolean;
  mode: 'SUMMARY' | 'CONTENT';
  recipients: string[];
  reminderMinutes: number;
  consoleUrl: string;
}
export interface TicketSettingsView {
  settings: TicketSettings;
  smtpConfigured: boolean;
  effectiveRecipients: string[];
  availableRecipients: string[];
  failures: {
    ticketId: string;
    title: string;
    reason: string;
    attempts: number;
    nextAttemptAt: string;
  }[];
}
export interface TicketSubmission {
  title?: string;
  category?: string;
  body: string;
  version?: number;
  status?: TicketStatus;
}
function multipart(input: TicketSubmission, files: File[]) {
  const form = new FormData();
  form.append('request', new Blob([JSON.stringify(input)], { type: 'application/json' }));
  files.forEach((file) => form.append('files', file));
  return form;
}
export const ticketApi = {
  list(params: { page: number; pageSize: number; status: string; search: string }) {
    return unwrap<TicketPage>(http.get('/tickets', { params }));
  },
  thread(id: string, before = 0) {
    return unwrap<TicketThread>(http.get(`/tickets/${id}`, { params: { before } }));
  },
  create(input: TicketSubmission, files: File[]) {
    return unwrap<Ticket>(http.post('/tickets', multipart(input, files)));
  },
  reply(id: string, input: TicketSubmission, files: File[]) {
    return unwrap<Ticket>(http.post(`/tickets/${id}/replies`, multipart(input, files)));
  },
  status(id: string, status: TicketStatus, version: number) {
    return unwrap<Ticket>(http.patch(`/tickets/${id}/status`, { status, version }));
  },
  async download(id: string, file: Attachment) {
    const result = await http.get<Blob>(`/tickets/${id}/attachments/${file.id}`, {
      responseType: 'blob',
    });
    const url = URL.createObjectURL(result.data);
    const link = document.createElement('a');
    link.href = url;
    link.download = file.filename;
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  },
  settings() {
    return unwrap<TicketSettingsView>(http.get('/admin/ticket-settings'));
  },
  saveSettings(input: TicketSettings) {
    return unwrap<TicketSettingsView>(http.put('/admin/ticket-settings', input));
  },
  retryNotification(id: string) {
    return unwrap<void>(http.post(`/admin/ticket-settings/${id}/retry`));
  },
};
