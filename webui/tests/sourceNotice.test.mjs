import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';
import { build, transform } from 'esbuild';
import { compileScript, parse } from '@vue/compiler-sfc';
import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'https://test.example' });
globalThis.window = dom.window;
globalThis.document = dom.window.document;
globalThis.localStorage = dom.window.localStorage;
Object.defineProperty(globalThis, 'navigator', { value: dom.window.navigator, configurable: true });
// What Vue's DOM renderer reaches for while patching elements.
for (const name of ['Element', 'HTMLElement', 'SVGElement', 'Node', 'Event', 'MouseEvent']) globalThis[name] = dom.window[name];

// Single-file components compile without Vite: script setup with its template inlined, styles left out.
const singleFileComponents = {
  name: 'single-file-components',
  setup(b) {
    b.onResolve({ filter: /^@\/api$/ }, () => ({ path: 'api', namespace: 'stub' }));
    b.onLoad({ filter: /.*/, namespace: 'stub' }, () => ({
      contents: 'export const appearanceApi = { public: async () => ({}) };',
      loader: 'js',
    }));
    b.onLoad({ filter: /\.vue$/ }, async (args) => {
      const { descriptor } = parse(readFileSync(args.path, 'utf8'), { filename: args.path });
      const script = compileScript(descriptor, { id: 'test', inlineTemplate: true });
      const { code } = await transform(script.content, { loader: 'ts' });
      return { contents: code, loader: 'js', resolveDir: path.dirname(args.path) };
    });
  },
};
const compiled = await build({
  stdin: {
    contents: `
      export { createApp, h, nextTick } from 'vue';
      export { createPinia, setActivePinia } from 'pinia';
      export { default as SourceNotice } from './src/components/SourceNotice.vue';
      export { DEFAULT_APPEARANCE, useAppearanceStore } from './src/stores/appearance';
      export * from './src/utils/sourceCode';
      export { setLocale } from './src/i18n';`,
    resolveDir: process.cwd(),
    loader: 'ts',
  },
  bundle: true,
  write: false,
  format: 'esm',
  platform: 'node',
  alias: { '@': path.resolve('src') },
  plugins: [singleFileComponents],
  define: { 'process.env.NODE_ENV': '"production"' },
});
const app = await import(`data:text/javascript;base64,${Buffer.from(compiled.outputFiles[0].text).toString('base64')}`);
const english = JSON.parse(readFileSync('src/i18n/en.json', 'utf8'));
const read = (file) => readFileSync(path.resolve('..', file), 'utf8');
const THIRD_PARTY_NOTICES_FILE = 'THIRD-PARTY-NOTICES.txt';

// Element Plus registers el-dialog globally in the real build; this stand-in shows the dialog only while it is open.
const Dialog = {
  props: ['modelValue', 'title'],
  setup(props, { slots }) {
    return () => (props.modelValue ? app.h('div', { class: 'dialog' }, [app.h('h2', props.title), slots.default?.()]) : null);
  },
};

async function mountNotice(sourceUrl) {
  const pinia = app.createPinia();
  app.setActivePinia(pinia);
  app.useAppearanceStore().replace({ ...app.DEFAULT_APPEARANCE, sourceUrl });
  const host = document.createElement('div');
  document.body.append(host);
  const vue = app.createApp(app.SourceNotice);
  vue.use(pinia);
  vue.component('ElDialog', Dialog);
  vue.mount(host);
  await app.nextTick();
  return host;
}

test('the footer links to the licence, the operator\'s source and the third-party notices, in both languages', async () => {
  const host = await mountNotice('https://git.example.com/me/starry-api-fork');
  const link = (name) => host.querySelector(`[data-test="${name}"]`);

  app.setLocale('zh-CN');
  await app.nextTick();
  assert.equal(link('license-link').textContent, 'AGPL-3.0-only');
  assert.equal(link('license-link').getAttribute('href'), 'https://www.gnu.org/licenses/agpl-3.0.html');
  assert.equal(link('source-link').textContent, '源代码');
  assert.equal(link('source-link').getAttribute('href'), 'https://git.example.com/me/starry-api-fork');
  assert.equal(link('notices-link').textContent, '第三方许可');
  assert.equal(link('notices-link').getAttribute('href'), './THIRD-PARTY-NOTICES.txt');
  assert.equal(link('disclaimer-button').textContent.trim(), '免责声明');

  app.setLocale('en-US');
  await app.nextTick();
  assert.equal(link('license-link').textContent, 'AGPL-3.0-only');
  assert.equal(link('source-link').textContent, 'Source code');
  assert.equal(link('source-link').getAttribute('href'), 'https://git.example.com/me/starry-api-fork');
  assert.equal(link('notices-link').textContent, 'Third-party licenses');
  assert.equal(link('disclaimer-button').textContent.trim(), 'Disclaimer');
  assert.equal(host.querySelector('nav').getAttribute('aria-label'), 'License and source code');
  for (const anchor of host.querySelectorAll('a')) {
    assert.equal(anchor.getAttribute('target'), '_blank');
    assert.match(anchor.getAttribute('rel'), /noopener/);
  }
});

test('the source link is the upstream project until the server says otherwise, and never an unusable address', async () => {
  const defaultUrl = 'https://github.com/StaR4y/starry-api';
  assert.equal(app.DEFAULT_SOURCE_URL, defaultUrl);
  assert.equal((await mountNotice(undefined)).querySelector('[data-test="source-link"]').getAttribute('href'), defaultUrl);
  for (const unusable of ['', '  ', 'javascript:alert(1)', 'data:text/html,x', 'ftp://example.com/x', 'example.com/x', '//example.com/x',
    'https://user:secret@example.com/x', 'https://example.com/a b', 'https://example.com/"x', 'not a url']) {
    assert.equal(app.resolveSourceUrl(unusable), defaultUrl, unusable);
    assert.equal((await mountNotice(unusable)).querySelector('[data-test="source-link"]').getAttribute('href'), defaultUrl, unusable);
  }
  assert.equal(app.resolveSourceUrl(' http://git.internal:3000/team/starry-api?ref=prod '), 'http://git.internal:3000/team/starry-api?ref=prod');

  // An answer without the address (a draft, an old server) keeps the one the server gave before.
  const pinia = app.createPinia();
  app.setActivePinia(pinia);
  const store = app.useAppearanceStore();
  store.replace({ ...app.DEFAULT_APPEARANCE, sourceUrl: 'https://git.example.com/me/starry-api-fork' });
  const { sourceUrl: _omitted, ...withoutAddress } = app.DEFAULT_APPEARANCE;
  store.replace(withoutAddress);
  assert.equal(store.sourceUrl, 'https://git.example.com/me/starry-api-fork');
  store.preview({ ...app.DEFAULT_APPEARANCE, siteName: 'Draft' });
  assert.equal(store.sourceUrl, 'https://git.example.com/me/starry-api-fork');
});

test('the disclaimer opens from the footer and says the same in the README, the docs index and both languages', async () => {
  app.setLocale('zh-CN');
  const chinese = app.disclaimerText();
  app.setLocale('en-US');
  const englishText = app.disclaimerText();
  assert.match(chinese, /^Starry API 是独立的开源项目/);
  assert.match(englishText, /^Starry API is an independent open-source project\. It is not affiliated with, endorsed by or sponsored by/);
  assert.equal(english[chinese], englishText);
  for (const file of ['README.md', 'docs/README.md']) {
    const text = read(file);
    assert.ok(text.includes(chinese), `${file} must carry the Chinese disclaimer verbatim`);
    assert.ok(text.includes(englishText), `${file} must carry the English disclaimer verbatim`);
  }

  const host = await mountNotice('https://github.com/StaR4y/starry-api');
  assert.equal(host.querySelector('.dialog'), null);
  host.querySelector('[data-test="disclaimer-button"]').click();
  await app.nextTick();
  assert.equal(host.querySelector('.dialog h2').textContent, 'Disclaimer');
  assert.equal(host.querySelector('[data-test="disclaimer-text"]').textContent, englishText);
  app.setLocale('zh-CN');
  await app.nextTick();
  assert.equal(host.querySelector('.dialog h2').textContent, '免责声明');
  assert.equal(host.querySelector('[data-test="disclaimer-text"]').textContent, chinese);
});

test('the layout shows the notice instead of claiming all rights', () => {
  const layout = readFileSync('src/layouts/DefaultLayout.vue', 'utf8');
  assert.ok(layout.includes('<SourceNotice />'));
  assert.ok(!/all rights reserved/i.test(layout));
});

test('every page outside the layout shows the notice too: sign-in, the forced password change, SMTP verification and maintenance', () => {
  // The routes that come before the one with DefaultLayout are standalone pages; during maintenance the router sends every
  // route to one of them, so a user may never see the layout.
  const router = readFileSync('src/router/index.ts', 'utf8');
  const standalone = router.slice(0, router.indexOf("import('@/layouts/DefaultLayout.vue')"));
  const views = [...standalone.matchAll(/import\('@\/(views\/[\w/]+\.vue)'\)/g)].map((match) => match[1]);
  assert.deepEqual([...new Set(views)].sort(), [
    'views/auth/ChangePasswordView.vue',
    'views/auth/LoginView.vue',
    'views/auth/SmtpVerifyView.vue',
    'views/system/MaintenanceView.vue',
  ]);
  for (const view of new Set(views)) {
    assert.ok(readFileSync(`src/${view}`, 'utf8').includes('<SourceNotice'), `${view} must show the license and source links`);
  }
});

test('maintenance mode still serves the third-party notices the footer links to', () => {
  const filter = read('service/src/main/java/tech/starryapi/admin/SystemMaintenanceWebFilter.java');
  assert.ok(filter.includes(`"/${THIRD_PARTY_NOTICES_FILE}"`), 'the maintenance filter must let the notices file through');
  assert.equal(app.THIRD_PARTY_NOTICES_HREF, `./${THIRD_PARTY_NOTICES_FILE}`);
});

test('the package metadata and the licence files name the same licence', () => {
  assert.equal(JSON.parse(readFileSync('package.json', 'utf8')).license, 'AGPL-3.0-only');
  assert.equal(JSON.parse(readFileSync('package-lock.json', 'utf8')).packages[''].license, 'AGPL-3.0-only');
  const says = (file, pattern) => assert.ok(pattern.test(read(file)), `${file} must match ${pattern}`);
  says('LICENSE', /GNU AFFERO GENERAL PUBLIC LICENSE\s+Version 3, 19 November 2007/);
  says('plugin-api/LICENSE', /Apache License\s+Version 2\.0, January 2004/);
  says('NOTICE', /SPDX-License-Identifier: AGPL-3\.0-only/);
  says('NOTICE', /Additional permission under section 7/);
  says('README.md', /Additional permission under section 7/);
});
