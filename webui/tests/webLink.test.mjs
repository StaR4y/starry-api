import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { transform } from 'esbuild';

const { code } = await transform(readFileSync('src/utils/webLink.ts', 'utf8'), { loader: 'ts', format: 'esm' });
const { webHost, webLink } = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`);

test('a link from remote data is used only when it is an absolute http(s) address without credentials', () => {
  assert.equal(webLink('https://aihot.news/daily/2026-11-03'), 'https://aihot.news/daily/2026-11-03');
  assert.equal(webLink('  http://example.com/a?b=c  '), 'http://example.com/a?b=c');
  for (const unsafe of [
    'javascript:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'vbscript:msgbox(1)',
    '//example.com/relative',
    '/relative/path',
    'https://user:secret@example.com/',
    'https://example.com/"onmouseover="alert(1)',
    'https://example.com/a b',
    'https://',
    '',
    '   ',
    null,
    undefined,
  ]) {
    assert.equal(webLink(unsafe), null, String(unsafe));
  }
});

test('the text of a link is its host without a leading www', () => {
  assert.equal(webHost('https://www.programbench.com/leaderboard'), 'programbench.com');
  assert.equal(webHost('https://aihot.news/x'), 'aihot.news');
});
