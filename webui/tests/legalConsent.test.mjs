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

// Single-file components compile without Vite: script setup with its template inlined, styles left out. The API, the
// HTTP helpers, the router and Element Plus are stand-ins: the test drives the stores and reads what the dialog renders.
const stubs = {
  '@/api': `
    export const calls = { accept: 0 };
    export const appearanceApi = { public: async () => ({}) };
    export const authApi = {
      acceptLegalConsent: async () => { calls.accept += 1; return { root: true, legalConsentRequired: false }; },
      logout: async () => {},
    };`,
  '@/utils/http': `
    export class ApiError extends Error {}
    export const getStoredToken = () => null;
    export const setStoredToken = () => {};`,
  'vue-router': `export const pushed = []; export const useRouter = () => ({ push: async (to) => { pushed.push(to); } });`,
  'element-plus': `export const ElMessage = { error: () => {} };`,
};
const singleFileComponents = {
  name: 'single-file-components',
  setup(b) {
    b.onResolve({ filter: /^(@\/api|@\/utils\/http|vue-router|element-plus)$/ }, (args) => ({ path: args.path, namespace: 'stub' }));
    b.onLoad({ filter: /.*/, namespace: 'stub' }, (args) => ({ contents: stubs[args.path], loader: 'js' }));
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
      export { default as LegalConsentDialog } from './src/components/LegalConsentDialog.vue';
      export { DEFAULT_APPEARANCE, useAppearanceStore } from './src/stores/appearance';
      export { useAuthStore } from './src/stores/auth';
      export { calls } from '@/api';
      export { pushed } from 'vue-router';
      export * from './src/utils/sourceCode';
      export { pluginDisclaimer } from './src/utils/pluginDisclaimer';
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

// Element Plus registers these globally in the real build; this dialog shows only while it is open, with its footer.
const Dialog = {
  props: ['modelValue', 'title'],
  setup(props, { slots }) {
    return () => (props.modelValue
      ? app.h('div', { class: 'dialog' }, [app.h('h2', props.title), slots.default?.(), app.h('footer', slots.footer?.())])
      : null);
  },
};
const Button = {
  emits: ['click'],
  setup(_props, { slots, emit, attrs }) {
    return () => app.h('button', { ...attrs, onClick: () => emit('click') }, slots.default?.());
  },
};

/** Mounts the dialog for an administrator with the given permissions; sourceUrl is what the server's appearance says. */
async function mountDialog({ sourceUrl, permissions = { root: true, legalConsentRequired: true }, role = 'ADMIN' } = {}) {
  const pinia = app.createPinia();
  app.setActivePinia(pinia);
  app.useAppearanceStore().replace({ ...app.DEFAULT_APPEARANCE, sourceUrl });
  const auth = app.useAuthStore();
  auth.$patch({ token: 'session', user: { role }, permissions, permissionsToken: 'session' });
  const host = document.createElement('div');
  document.body.append(host);
  const vue = app.createApp(app.LegalConsentDialog);
  vue.use(pinia);
  vue.component('ElDialog', Dialog);
  vue.component('ElButton', Button);
  vue.mount(host);
  await app.nextTick();
  return { host, auth };
}

test('Root of a new deployment is shown the licence, the operator\'s source and the third-party notices, in both languages', async () => {
  const { host } = await mountDialog({ sourceUrl: 'https://git.example.com/me/starry-api-fork' });
  const link = (name) => host.querySelector(`[data-test="${name}"]`);

  app.setLocale('zh-CN');
  await app.nextTick();
  assert.equal(host.querySelector('.dialog h2').textContent, '许可条款与免责声明');
  assert.deepEqual([...host.querySelectorAll('.dialog h3')].map((heading) => heading.textContent),
    ['一、软件许可', '二、源代码', '三、第三方组件', '四、免责声明']);
  assert.match(link('consent-intro').textContent, /^\s*使用本软件前，请仔细阅读以下许可条款与免责声明。/);
  assert.equal(link('license-link').textContent, 'AGPL-3.0-only');
  assert.equal(link('license-link').getAttribute('href'), 'https://www.gnu.org/licenses/agpl-3.0.html');
  assert.equal(link('source-link').textContent, 'https://git.example.com/me/starry-api-fork');
  assert.equal(link('source-link').getAttribute('href'), 'https://git.example.com/me/starry-api-fork');
  assert.equal(link('notices-link').getAttribute('href'), './THIRD-PARTY-NOTICES.txt');
  assert.equal(link('notices-link').textContent, THIRD_PARTY_NOTICES_FILE);
  assert.equal(link('consent-accept').textContent.trim(), '同意并继续');
  assert.equal(link('consent-decline').textContent.trim(), '不同意并退出');

  app.setLocale('en-US');
  await app.nextTick();
  assert.equal(host.querySelector('.dialog h2').textContent, 'License Terms and Disclaimer');
  assert.deepEqual([...host.querySelectorAll('.dialog h3')].map((heading) => heading.textContent),
    ['1. Software license', '2. Source code', '3. Third-party components', '4. Disclaimer']);
  assert.equal(link('consent-accept').textContent.trim(), 'Agree and continue');
  assert.equal(link('consent-decline').textContent.trim(), 'Decline and sign out');
  assert.match(link('consent-intro').textContent, /^\s*Before using this software/);
  for (const anchor of host.querySelectorAll('a')) {
    assert.equal(anchor.getAttribute('target'), '_blank');
    assert.match(anchor.getAttribute('rel'), /noopener/);
  }
});

test('the dialog asks Root only, only until Root agrees, and cannot be dismissed otherwise', async () => {
  for (const [permissions, role] of [[{ root: false, legalConsentRequired: false }, 'ADMIN'],
    [{ root: true, legalConsentRequired: false }, 'ADMIN'], [{ root: true, legalConsentRequired: true }, 'USER']]) {
    const { host } = await mountDialog({ permissions, role });
    assert.equal(host.querySelector('.dialog'), null, JSON.stringify({ permissions, role }));
  }

  const { host, auth } = await mountDialog();
  assert.ok(host.querySelector('.dialog'));
  const before = app.calls.accept;
  host.querySelector('[data-test="consent-accept"]').click();
  await new Promise((resolve) => setTimeout(resolve, 0));
  await app.nextTick();
  assert.equal(app.calls.accept, before + 1);
  assert.equal(auth.legalConsentRequired, false);
  assert.equal(host.querySelector('.dialog'), null);

  // Not agreeing leaves one way out: signing out.
  const second = await mountDialog();
  second.host.querySelector('[data-test="consent-decline"]').click();
  await app.nextTick();
  assert.equal(second.auth.token, null);
  assert.deepEqual(app.pushed.at(-1), { name: 'login' });

  const dialog = readFileSync('src/components/LegalConsentDialog.vue', 'utf8');
  for (const locked of [':show-close="false"', ':close-on-click-modal="false"', ':close-on-press-escape="false"']) {
    assert.ok(dialog.includes(locked), `the dialog must not close by itself: ${locked}`);
  }
});

test('the source link is the upstream project until the server says otherwise, and never an unusable address', async () => {
  const defaultUrl = 'https://github.com/StaR4y/starry-api';
  const sourceHref = async (sourceUrl) =>
    (await mountDialog({ sourceUrl })).host.querySelector('[data-test="source-link"]').getAttribute('href');
  assert.equal(app.DEFAULT_SOURCE_URL, defaultUrl);
  assert.equal(await sourceHref(undefined), defaultUrl);
  for (const unusable of ['', '  ', 'javascript:alert(1)', 'data:text/html,x', 'ftp://example.com/x', 'example.com/x', '//example.com/x',
    'https://user:secret@example.com/x', 'https://example.com/a b', 'https://example.com/"x', 'not a url']) {
    assert.equal(app.resolveSourceUrl(unusable), defaultUrl, unusable);
    assert.equal(await sourceHref(unusable), defaultUrl, unusable);
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

test('the disclaimer in the dialog says the same as both READMEs, the docs index and both languages', async () => {
  app.setLocale('zh-CN');
  const chinese = app.disclaimerText();
  app.setLocale('en-US');
  const englishText = app.disclaimerText();
  assert.match(chinese, /^Starry API 是独立的开源项目/);
  assert.match(englishText, /^Starry API is an independent open-source project\. It is not affiliated with, endorsed by or sponsored by/);
  assert.equal(english[chinese], englishText);
  assert.ok(read('README.md').includes(englishText), 'README.md must carry the English disclaimer verbatim');
  assert.ok(read('README.zh-CN.md').includes(chinese), 'README.zh-CN.md must carry the Chinese disclaimer verbatim');
  const index = read('docs/README.md');
  assert.ok(index.includes(chinese), 'docs/README.md must carry the Chinese disclaimer verbatim');
  assert.ok(index.includes(englishText), 'docs/README.md must carry the English disclaimer verbatim');

  const { host } = await mountDialog();
  assert.equal(host.querySelector('[data-test="disclaimer-text"]').textContent, englishText);
  app.setLocale('zh-CN');
  await app.nextTick();
  assert.equal(host.querySelector('[data-test="disclaimer-text"]').textContent, chinese);
});

test('no page links to the licence, the source or the disclaimer, or shows a copyright line; the layout asks Root once instead', () => {
  const layout = readFileSync('src/layouts/DefaultLayout.vue', 'utf8');
  assert.ok(layout.includes('<LegalConsentDialog />'));
  assert.ok(!/all rights reserved/i.test(layout));
  const router = readFileSync('src/router/index.ts', 'utf8');
  const standalone = router.slice(0, router.indexOf("import('@/layouts/DefaultLayout.vue')"));
  const views = [...new Set([...standalone.matchAll(/import\('@\/(views\/[\w/]+\.vue)'\)/g)].map((match) => match[1]))];
  assert.deepEqual(views.sort(), [
    'views/auth/ChangePasswordView.vue',
    'views/auth/LoginView.vue',
    'views/auth/SmtpVerifyView.vue',
    'views/system/MaintenanceView.vue',
  ]);
  for (const file of ['layouts/DefaultLayout.vue', ...views]) {
    const text = readFileSync(`src/${file}`, 'utf8');
    for (const gone of ['SourceNotice', 'LICENSE_URL', 'THIRD_PARTY_NOTICES_HREF', 'disclaimerText']) {
      assert.ok(!text.includes(gone), `${file} must not show ${gone}`);
    }
    // LGPL-2.1, section 6: a running work that shows copyright notices must show logback's among them, with a reference
    // to its licence. Without the link to the notices, the console shows no copyright line at all.
    assert.ok(!/©|&copy;/.test(text), `${file} must not show a copyright line`);
  }
});

test('turning on plugins needs the whole plugin disclaimer accepted, and the guide carries the same text', () => {
  app.setLocale('zh-CN');
  const chinese = app.pluginDisclaimer();
  app.setLocale('en-US');
  const englishClauses = app.pluginDisclaimer();
  assert.equal(chinese.length, 5);
  const guide = read('docs/PLUGINS.md');
  chinese.forEach((clause, index) => {
    assert.ok(guide.includes(`**${clause.title}。** ${clause.body}`), `docs/PLUGINS.md must carry "${clause.title}" verbatim`);
    assert.equal(english[clause.title], englishClauses[index].title);
    assert.equal(english[clause.body], englishClauses[index].body);
  });
  assert.match(chinese.map((clause) => clause.body).join(''), /第三方|没有沙箱|按现状|关闭开发者模式/);

  // The developer-mode dialog shows it, and "verify and turn on" stays disabled until it is accepted; so does the server.
  const view = readFileSync('src/views/settings/PluginSettingsView.vue', 'utf8');
  assert.ok(view.includes('pluginDisclaimer()'));
  assert.ok(view.includes('data-test="plugin-disclaimer-accept"'));
  assert.match(view, /:disabled="\s*!form\.acceptRisk \|\|/);
  const security = read('service/src/main/java/tech/starryapi/plugin/PluginSecurityService.java');
  assert.match(security, /if \(!request\.acceptRisk\(\)\) return Mono\.error\(error\(HttpStatus\.BAD_REQUEST,\s*"PLUGIN_RISK_ACCEPTANCE_REQUIRED"/);
  assert.ok(security.includes('plugin disclaimer accepted'));
});

test('maintenance mode still serves the third-party notices the dialog links to', () => {
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
