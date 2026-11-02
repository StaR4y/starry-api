import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';
import { build as bundle } from 'esbuild';
import { build as viteBuild } from 'vite';

// vite.config.ts only needs its helpers here, so the build tool and its plugins are replaced by empty stand-ins.
const standIns = {
  name: 'stand-ins',
  setup(b) {
    b.onResolve({ filter: /^(vite|@vitejs\/.+|unplugin-.+|vite-plugin-.+)$/ }, (args) => ({ path: args.path, namespace: 'stand-in' }));
    b.onLoad({ filter: /.*/, namespace: 'stand-in' }, () => ({
      contents: 'export default () => ({}); export const defineConfig = (c) => c; export const loadEnv = () => ({}); export const ElementPlusResolver = () => ({});',
      loader: 'js',
    }));
  },
};
const compiled = await bundle({
  entryPoints: ['vite.config.ts'],
  bundle: true,
  write: false,
  format: 'esm',
  platform: 'node',
  plugins: [standIns],
});
const { LOGBACK_COPYRIGHT, SUPPLIED_MIT_LICENSES, bundledPackageDirectories, mitLicenseText, readPackageNotice, renderThirdPartyNotices } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled.outputFiles[0].text).toString('base64')}`
);

test('only packages under node_modules count, whatever the plugins did to the module id', () => {
  const directories = bundledPackageDirectories([
    '/repo/webui/node_modules/vue/dist/vue.runtime.esm-bundler.js',
    '\0/repo/webui/node_modules/axios/lib/axios.js?commonjs-exports',
    '/repo/webui/node_modules/@element-plus/icons-vue/dist/es/index.mjs?v=1',
    '/repo/webui/node_modules/element-plus/node_modules/dayjs/esm/index.js',
    'C:\\repo\\webui\\node_modules\\pinia\\dist\\pinia.mjs',
    '/repo/webui/node_modules/vue',
    '/repo/webui/node_modules/@scope',
    '/repo/webui/src/main.ts',
    '\0plugin-vue:export-helper',
  ]);

  assert.deepEqual(directories, [
    '/repo/webui/node_modules/@element-plus/icons-vue',
    '/repo/webui/node_modules/axios',
    '/repo/webui/node_modules/element-plus/node_modules/dayjs',
    '/repo/webui/node_modules/vue',
    'C:/repo/webui/node_modules/pinia',
  ]);
});

test('a package contributes its declared license and the license files it ships', () => {
  const notice = readPackageNotice(path.resolve('node_modules/highlight.js'));

  assert.equal(notice.name, 'highlight.js');
  assert.equal(notice.license, 'BSD-3-Clause');
  assert.deepEqual(notice.files.map((file) => file.name), ['LICENSE']);
  assert.match(notice.files[0].text, /Copyright \(c\) 2006, Ivan Sagalaev/);
  assert.equal(readPackageNotice(path.resolve('node_modules/dompurify')).license, '(MPL-2.0 OR Apache-2.0)');
});

test('a bundled directory that is not an npm package stops the build', () => {
  assert.throws(() => readPackageNotice(path.resolve('src')), /not an npm package/);
});

test('the notices list each release once, sorted, with the license text of each', () => {
  const entry = (name, version, license, text) => ({ name, version, license, homepage: '', files: [{ name: 'LICENSE', text }] });
  const text = renderThirdPartyNotices([
    entry('zeta', '1.0.0', 'MIT', 'Zeta license text'),
    entry('alpha', '2.0.0', 'ISC', 'Alpha license text'),
    entry('zeta', '1.0.0', 'MIT', 'Zeta license text'),
  ]);

  assert.match(text, /^THIRD-PARTY NOTICES\n/);
  assert.match(text, /\n2 packages\n/);
  assert.ok(text.indexOf('alpha 2.0.0') < text.indexOf('zeta 1.0.0'));
  assert.equal(text.split('Zeta license text').length - 1, 1);
  assert.match(text, /alpha 2\.0\.0\nLicense: ISC\n\n--- LICENSE ---\n\nAlpha license text/);
  assert.match(text, /AGPL-3\.0-only/);
});

// LGPL-2.1, section 6: a work that displays copyright notices while it runs includes the copyright notice of the Library
// among them and points the user to the License. The console displays one, and this file is what its footer links to.
test('the notices end with the logback notice that the LGPL-2.1 asks a running work to display', () => {
  const text = renderThirdPartyNotices([{ name: 'alpha', version: '2.0.0', license: 'ISC', homepage: '', files: [{ name: 'LICENSE', text: 'Alpha license text' }] }]);
  const section = text.slice(text.indexOf('\nlogback-classic and logback-core'));

  assert.ok(text.indexOf('\nalpha 2.0.0') < text.indexOf('\nlogback-classic and logback-core'), 'logback comes after the packages of the console');
  assert.match(section, /\nLicense: LGPL-2\.1-only \(logback is offered under EPL-2\.0 or LGPL-2\.1-only; Starry API uses it under LGPL-2\.1-only\)\n/);
  assert.ok(section.includes(LOGBACK_COPYRIGHT.join('\n')), 'the copyright notice of logback must be reproduced');
  assert.ok(section.includes('https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html'), 'the notice must point to the text of the License');
  assert.ok(section.includes('licenses/LGPL-2.1.txt') && section.includes('META-INF/licenses/LGPL-2.1.txt'));
  assert.match(text, /\n1 packages\n/, 'logback is not counted as a package of the console');
});

test('the copyright lines the notices reproduce are the ones in licenses/logback-NOTICE.txt', () => {
  const notice = readFileSync(path.resolve('..', 'licenses', 'logback-NOTICE.txt'), 'utf8');

  assert.ok(notice.startsWith(`${LOGBACK_COPYRIGHT.join('\n')}\n`), 'update LOGBACK_COPYRIGHT in vite.config.ts when licenses/logback-NOTICE.txt changes');
});

// A package that is bundled but ships no license file: the table supplies the MIT text, otherwise the build stops.
const fixtures = path.resolve('..', 'build', 'third-party-notices-test-packages');
function fixturePackage(name, license) {
  const directory = path.join(fixtures, name.replace('/', '__'));
  mkdirSync(directory, { recursive: true });
  writeFileSync(path.join(directory, 'package.json'), JSON.stringify({ name, version: '1.2.3', license }));
  return directory;
}
rmSync(fixtures, { recursive: true, force: true });

test('a package without a license file gets the MIT text with its copyright holder from the table', () => {
  const notice = readPackageNotice(fixturePackage('element-plus', 'MIT'));

  assert.equal(notice.files.length, 1);
  assert.match(notice.files[0].name, /written by the Starry API build/);
  assert.ok(notice.files[0].text.startsWith('MIT License\n\nCopyright (c) 2020-PRESENT Element Plus'));
  assert.ok(notice.files[0].text.includes('Permission is hereby granted, free of charge'));
  assert.ok(notice.files[0].text.includes('The above copyright notice and this permission notice shall be included'));
  assert.equal(mitLicenseText('Jane Doe').split('\n')[2], 'Copyright (c) Jane Doe');
  assert.deepEqual(Object.keys(SUPPLIED_MIT_LICENSES).sort(), ['@vue/devtools-api', 'element-plus', 'lodash-unified']);
});

test('a bundled package with no license file and no entry in the table stops the build', () => {
  assert.throws(() => readPackageNotice(fixturePackage('some-unlisted-library', 'MIT')), /ships no license text.*SUPPLIED_MIT_LICENSES/);
  // A package that states a license but has no text is not enough either: the license field alone does not satisfy MIT.
  assert.throws(() => readPackageNotice(fixturePackage('another-library', 'BSD-3-Clause')), /ships no license text/);
  assert.throws(() => readPackageNotice(fixturePackage('no-license-at-all', '')), /ships no license text/);
});

test('an entry in the table stops applying when the package changes its license', () => {
  assert.throws(() => readPackageNotice(fixturePackage('lodash-unified', 'ISC')), /declares the license "ISC"/);
  assert.throws(() => readPackageNotice(fixturePackage('@vue/devtools-api', '')), /declares the license ""/);
});

test('the table covers the three installed packages that ship no license file', () => {
  for (const [name, holder] of [['element-plus', 'Element Plus'], ['lodash-unified', 'Jack Works'], ['@vue/devtools-api', 'Guillaume Chau']]) {
    const notice = readPackageNotice(path.resolve('node_modules', name));
    assert.equal(notice.license, 'MIT', name);
    assert.ok(notice.files[0].text.includes(`Copyright (c) `) && notice.files[0].text.includes(holder), name);
  }
});

test('the production build writes the notices of what it bundled next to the console', async () => {
  const outDir = path.resolve('..', 'build', 'third-party-notices-test');
  await viteBuild({ configFile: path.resolve('vite.config.ts'), logLevel: 'silent', build: { outDir, emptyOutDir: true, reportCompressedSize: false } });
  const text = readFileSync(path.join(outDir, 'THIRD-PARTY-NOTICES.txt'), 'utf8');

  // The packages whose license requires the notice to travel with a copy, which the minified bundle does not keep.
  for (const expected of ['vue ', 'element-plus ', 'dompurify ', 'highlight.js ', 'normalize-wheel-es ', 'axios ', 'pinia ']) {
    assert.ok(text.includes(`\n${expected}`), `${expected.trim()} must be listed`);
  }
  assert.ok(text.includes('Copyright (c) 2006, Ivan Sagalaev'), 'the highlight.js copyright line must be reproduced');
  assert.ok(text.includes('Copyright (c) 2015, Facebook, Inc.'), 'the normalize-wheel-es copyright line must be reproduced');
  // Element Plus, lodash-unified and @vue/devtools-api ship no license file; their MIT text comes from the build.
  assert.ok(text.includes('Copyright (c) 2020-PRESENT Element Plus'), 'the Element Plus copyright line must be reproduced');
  assert.ok(text.includes('Copyright (c) Jack Works'), 'the lodash-unified copyright line must be reproduced');
  assert.ok(text.includes('Copyright (c) Guillaume Chau'), 'the @vue/devtools-api copyright line must be reproduced');
  assert.ok(!text.includes('ships no separate license file'), 'no package may be listed without its license text');
  assert.ok(text.includes(LOGBACK_COPYRIGHT[1]), 'the logback copyright line must be in the file the footer links to');
  assert.ok(text.includes('LGPL-2.1-only') && text.includes('https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html'));
  for (const block of text.split('='.repeat(78)).slice(1)) {
    assert.match(block, /\n--- .+ ---\n\n\S/, `every package needs license text: ${block.slice(0, 80)}`);
  }
  assert.ok(!text.includes('\ntypescript '), 'development-only packages must not be listed');
  assert.ok(!text.includes('\neslint '), 'development-only packages must not be listed');
  assert.match(text, /\n\d+ packages\n/);
});
