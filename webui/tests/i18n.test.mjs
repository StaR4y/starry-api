import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';
import { build } from 'esbuild';
import ts from 'typescript';
import { parse } from '@vue/compiler-sfc';
import { parse as parseTemplate, NodeTypes } from '@vue/compiler-dom';
import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'https://test.example' });
globalThis.window = dom.window;
globalThis.document = dom.window.document;
globalThis.localStorage = dom.window.localStorage;
Object.defineProperty(globalThis, 'navigator', { value: dom.window.navigator, configurable: true });
const compiled = await build({ stdin: { contents: `export * from './src/i18n'; export { computed, reactive } from 'vue'; export { createConnectionText, createCcSwitchLinks, createSetupScriptBlock, setupClients } from './src/platforms/clientSetup'; export { documentationTemplates } from './src/views/documentation/templates';`, resolveDir: process.cwd(), loader: 'ts' }, bundle: true, write: false, format: 'esm', platform: 'node' });
const { t, locale, setLocale, resolveLocale, computed, reactive, LOCALE_STORAGE_KEY, createConnectionText, createCcSwitchLinks, createSetupScriptBlock, setupClients, documentationTemplates } = await import(`data:text/javascript;base64,${Buffer.from(compiled.outputFiles[0].text).toString('base64')}`);
const english = JSON.parse(fs.readFileSync('src/i18n/en.json', 'utf8'));

test('browser language, saved preference, fallback, persistence, and cross-tab changes', () => {
  assert.equal(resolveLocale(null, ['zh-TW', 'en-US']), 'zh-CN');
  assert.equal(resolveLocale(null, ['en-GB']), 'en-US');
  assert.equal(resolveLocale('en-US', ['zh-CN']), 'en-US');
  assert.equal(resolveLocale('invalid', ['fr-FR']), 'en-US');
  setLocale('en-US');
  assert.equal(document.documentElement.lang, 'en-US');
  assert.equal(localStorage.getItem(LOCALE_STORAGE_KEY), 'en-US');
  window.dispatchEvent(new dom.window.StorageEvent('storage', { key: LOCALE_STORAGE_KEY, newValue: 'zh-CN' }));
  assert.equal(locale.value, 'zh-CN');
});

test('switching updates computed UI labels without changing entered data', () => {
  setLocale('zh-CN');
  const form = reactive({ name: '用户自己的名称', body: 'Save ${p0} literally', model: 'gpt-5', billingSource: 'CODING_PLAN' });
  const before = JSON.stringify(form);
  const label = computed(() => t('系统设置'));
  assert.equal(label.value, '系统设置');
  setLocale('en-US');
  assert.equal(label.value, 'System settings');
  assert.match(setupClients[0].description, /configuration/);
  assert.equal(JSON.stringify(form), before);
  assert.equal(t('任意用户内容'), '任意用户内容');
  assert.equal(t('已复制 {p0}', { p0: '$& <script> {p1}' }), 'Copied $& <script> {p1}');
  assert.equal(t('toString'), 'toString');
  assert.equal(t('  新文档\n'), '  New document\n');
  setLocale('zh-CN');
  assert.equal(label.value, '系统设置');
});

test('client protocol, credentials, config files, and import links are language independent', () => {
  const context = { apiOrigin: 'https://example.com', apiBaseUrl: 'https://example.com/v1', apiKey: 'sk-test-user-key', apiPort: '443' };
  // With and without a source pool chosen: the pool is written as data (a header, a model suffix), never as a translated text.
  const snapshot = () => JSON.stringify([context, { ...context, tier: 'kiro+' }].map(context => ({ connection: createConnectionText(context), links: createCcSwitchLinks(context).map(l => l.url), configs: ['codex', 'claude', 'opencode'].flatMap(c => ['unix', 'cmd', 'powershell'].map(p => createSetupScriptBlock(c, p, context).script)) })));
  setLocale('zh-CN');
  const chinese = snapshot();
  setLocale('en-US');
  assert.equal(snapshot(), chinese);
});

test('document templates translate when created and retain literal code placeholders', () => {
  setLocale('zh-CN');
  const original = { ...documentationTemplates[2].content };
  setLocale('en-US');
  const englishTemplate = { ...documentationTemplates[2].content };
  assert.equal(englishTemplate.title, 'Client setup');
  assert.ok(englishTemplate.body.includes('{{api_base_url}}'));
  assert.ok(englishTemplate.body.includes('https://ccswitch.io'));
  assert.equal(original.title, '客户端接入');
});

test('every translation preserves all parameter placeholders', () => {
  const placeholders = value => [...value.matchAll(/\{p\d+\}/g)].map(m => m[0]).sort();
  for (const [source, translated] of Object.entries(english)) {
    assert.ok(translated.trim(), `Empty translation: ${source}`);
    assert.deepEqual(placeholders(translated), placeholders(source), source);
  }
});

test('all explicit UI messages have English translations and no raw template text is missed', () => {
  const missing = [];
  function script(source, file) {
    const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
    function visit(node) {
      if (ts.isCallExpression(node) && node.expression.getText(ast) === 't') {
        const key = node.arguments[0];
        if (key && ts.isStringLiteral(key) && /\p{Script=Han}/u.test(key.text) && !Object.hasOwn(english, key.text.trim())) missing.push(`${file}: ${key.text}`);
      }
      ts.forEachChild(node, visit);
    }
    visit(ast);
  }
  function template(source, file) {
    function visit(node) {
      if (node.type === NodeTypes.TEXT && /\p{Script=Han}/u.test(node.content)) missing.push(`${file}: raw text ${node.content}`);
      if (node.type === NodeTypes.INTERPOLATION) script(node.content.content, file);
      for (const p of node.props ?? []) {
        if (p.type === NodeTypes.ATTRIBUTE && p.value && /\p{Script=Han}/u.test(p.value.content)) missing.push(`${file}: raw attribute ${p.value.content}`);
        if (p.type === NodeTypes.DIRECTIVE && p.exp) script(p.exp.content, file);
      }
      node.children?.forEach(visit);
    }
    visit(parseTemplate(source));
  }
  function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const file = path.join(dir, entry.name);
      if (entry.isDirectory()) { walk(file); continue; }
      if (file.includes('/i18n/') || file.endsWith('LanguageSelect.vue')) continue;
      if (file.endsWith('.ts')) script(fs.readFileSync(file, 'utf8'), file);
      if (file.endsWith('.vue')) {
        const { descriptor } = parse(fs.readFileSync(file, 'utf8'));
        if (descriptor.scriptSetup) script(descriptor.scriptSetup.content, file);
        if (descriptor.template) template(descriptor.template.content, file);
      }
    }
  }
  walk('src');
  assert.deepEqual(missing, []);
});
