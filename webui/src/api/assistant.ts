import http, { unwrap } from '@/utils/http';
import type { ApiResponse } from '@/types/api';

export interface AssistantPublicSettings {
  enabled: boolean;
  displayName: string;
  greeting: string;
}

export interface AssistantChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

export const assistantApi = {
  publicSettings() {
    return unwrap<AssistantPublicSettings>(http.get<ApiResponse<AssistantPublicSettings>>('/assistant/settings'));
  },
  chat(messages: AssistantChatMessage[]) {
    return unwrap<{ reply: string; sources: string[] }>(
      http.post<ApiResponse<{ reply: string; sources: string[] }>>('/assistant/chat', { messages }),
    );
  },
};
