import { t } from '@/i18n';
import MarkdownIt from 'markdown-it';
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/core';
import bash from 'highlight.js/lib/languages/bash';
import json from 'highlight.js/lib/languages/json';
import javascript from 'highlight.js/lib/languages/javascript';
import typescript from 'highlight.js/lib/languages/typescript';
import python from 'highlight.js/lib/languages/python';
import ini from 'highlight.js/lib/languages/ini';
import yaml from 'highlight.js/lib/languages/yaml';
import xml from 'highlight.js/lib/languages/xml';
import css from 'highlight.js/lib/languages/css';
import sql from 'highlight.js/lib/languages/sql';
import http from 'highlight.js/lib/languages/http';
import type { DocAttachment } from '../api/documentation';

for (const [name, language] of Object.entries({
  bash,
  json,
  javascript,
  typescript,
  python,
  ini,
  yaml,
  xml,
  css,
  sql,
  http,
})) {
  hljs.registerLanguage(name, language);
}
hljs.registerAliases(['toml'], { languageName: 'ini' });
export interface DocHeading {
  id: string;
  text: string;
  level: number;
}
export interface RenderedDocument {
  html: string;
  codes: string[];
  headings: DocHeading[];
}
const attachmentId = /^attachment:([0-9a-f-]{36})$/;

export function safeDocLink(url: string): boolean {
  return (
    /^https?:\/\/[^\s<>]+$/i.test(url) ||
    /^mailto:[^\s<>]+$/i.test(url) ||
    /^#[^\s<>]*$/.test(url) ||
    attachmentId.test(url)
  );
}

export function renderDocument(
  body: string,
  attachments: DocAttachment[],
  origin: string,
): RenderedDocument {
  const codes: string[] = [];
  const headings: DocHeading[] = [];
  const files = new Map(attachments.map((file) => [file.id, file]));
  const md = new MarkdownIt({ html: false, linkify: true, breaks: false, typographer: false });
  md.validateLink = safeDocLink;
  const escape = md.utils.escapeHtml;
  // Resolve only server-issued, same-origin attachment URLs, never arbitrary HTML from Markdown.
  function resolve(url: string): string {
    const match = attachmentId.exec(url);
    if (!match) return safeDocLink(url) ? url : '';
    const file = files.get(match[1]);
    return file?.url.startsWith(`/documentation/media/${file.id}?`) ? file.url : '';
  }
  md.renderer.rules.fence = (tokens, index) => {
    const token = tokens[index];
    const requested = token.info.trim().split(/\s+/)[0].toLowerCase();
    const language = requested && hljs.getLanguage(requested) ? requested : '';
    const plain = ['text', 'txt', 'plaintext'].includes(requested);
    const result = language
      ? hljs.highlight(token.content, { language, ignoreIllegals: true })
      : !plain && !requested && token.content.length < 8000
        ? hljs.highlightAuto(token.content, ['bash', 'json', 'python', 'javascript', 'yaml'])
        : null;
    const id = codes.push(token.content) - 1;
    const label = requested || result?.language || 'text';
    return `<div class="doc-code"><div class="doc-code__header"><span>${escape(label.slice(0, 40))}</span><span class="doc-code__tools" data-code-index="${id}"></span></div><pre><code>${result?.value ?? escape(token.content)}</code></pre></div>`;
  };
  md.renderer.rules.code_block = (tokens, index, options, env, renderer) =>
    md.renderer.rules.fence!(tokens, index, options, env, renderer);
  md.renderer.rules.heading_open = (tokens, index, options, _env, renderer) => {
    const id = `doc-heading-${headings.length + 1}`;
    const inline = tokens[index + 1];
    const text = (inline.children ?? [])
      .map((token) => (token.type === 'text' || token.type === 'code_inline' ? token.content : ''))
      .join('');
    headings.push({ id, text, level: Number(tokens[index].tag.slice(1)) });
    tokens[index].attrSet('id', id);
    return renderer.renderToken(tokens, index, options);
  };
  md.renderer.rules.link_open = (tokens, index, options, _env, renderer) => {
    const token = tokens[index];
    const href = resolve(String(token.attrGet('href') ?? ''));
    token.attrSet('href', href || '#attachment-unavailable');
    if (/^https?:/i.test(href)) {
      token.attrSet('target', '_blank');
      token.attrSet('rel', 'noopener noreferrer');
    }
    return renderer.renderToken(tokens, index, options);
  };
  md.renderer.rules.image = (tokens, index) => {
    const token = tokens[index];
    const raw = String(token.attrGet('src') ?? '');
    const url = resolve(raw);
    const file = files.get(attachmentId.exec(raw)?.[1] ?? '');
    const label = escape(token.content);
    if (!url || (!file && !/^https?:\/\//i.test(url)))
      return `<span class="doc-media-missing">${label || t('附件不可用')}</span>`;
    if (file?.contentType.startsWith('video/')) {
      return `<video controls preload="metadata" playsinline src="${escape(url)}" aria-label="${label || t('视频')}"></video>`;
    }
    if (file && !file.contentType.startsWith('image/'))
      return `<a href="${escape(url)}">${label || escape(file.filename)}</a>`;
    return `<img src="${escape(url)}" alt="${label}" loading="lazy" referrerpolicy="no-referrer">`;
  };
  const site = /^https?:\/\/[^/]+$/.test(origin) ? origin : '';
  const source = body.replace(
    /\{\{(site_url|api_base_url)\}\}/g,
    (_, key) => site + (key === 'api_base_url' ? '/v1' : ''),
  );
  return {
    html: DOMPurify.sanitize(md.render(source), {
      ALLOWED_TAGS: [
        'p',
        'br',
        'hr',
        'h1',
        'h2',
        'h3',
        'h4',
        'h5',
        'h6',
        'a',
        'img',
        'video',
        'strong',
        'em',
        's',
        'blockquote',
        'ul',
        'ol',
        'li',
        'pre',
        'code',
        'div',
        'span',
        'table',
        'thead',
        'tbody',
        'tr',
        'th',
        'td',
      ],
      ALLOWED_ATTR: [
        'href',
        'target',
        'rel',
        'src',
        'alt',
        'title',
        'class',
        'id',
        'start',
        'controls',
        'preload',
        'playsinline',
        'loading',
        'referrerpolicy',
        'aria-label',
        'data-code-index',
      ],
      ALLOW_DATA_ATTR: false,
    }),
    codes,
    headings,
  };
}
