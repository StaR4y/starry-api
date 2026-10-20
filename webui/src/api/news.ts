import http, { unwrap } from '@/utils/http';
import type { ApiResponse } from '@/types/api';

export interface NewsDailyIndexItem {
  date: string;
  generatedAt: string;
  leadTitle: string;
  href: string;
}

export interface NewsItem {
  title: string;
  summary: string;
  source: string;
  aihotUrl: string;
  originalUrl: string;
}

export interface NewsSection {
  label: string;
  items: NewsItem[];
}

export interface NewsDailyReport {
  date: string;
  generatedAt: string;
  href: string;
  sections: NewsSection[];
  flashes: NewsItem[];
}

export interface NewsHotTopic {
  rank: number;
  title: string;
  source: string;
  sourceCount: number;
  aihotUrl: string;
  originalUrl: string;
}

export const newsApi = {
  dailies(limit = 60) {
    return unwrap<{ items: NewsDailyIndexItem[] }>(
      http.get<ApiResponse<{ items: NewsDailyIndexItem[] }>>('/news/dailies', { params: { limit } }),
    );
  },
  daily(date: string) {
    return unwrap<NewsDailyReport>(http.get<ApiResponse<NewsDailyReport>>(`/news/dailies/${date}`));
  },
  hotTopics() {
    return unwrap<{ items: NewsHotTopic[] }>(
      http.get<ApiResponse<{ items: NewsHotTopic[] }>>('/news/hot-topics'),
    );
  },
};
