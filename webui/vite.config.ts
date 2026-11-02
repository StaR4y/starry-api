import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, URL } from 'node:url';
import { defineConfig, loadEnv, type Plugin } from 'vite';
import vue from '@vitejs/plugin-vue';
import AutoImport from 'unplugin-auto-import/vite';
import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import ElementPlus from 'unplugin-element-plus/vite';
import viteCompression from 'vite-plugin-compression';

/** The license, copying and notice files an npm package ships (LICENSE, LICENSE.md, LICENSE-MIT, COPYING, NOTICE ...). */
const LICENSE_FILE = /^(licen[sc]e|copying|notice)([-_.].*)?$/i;
const NOTICES_FILE = 'THIRD-PARTY-NOTICES.txt';

/**
 * Packages that declare the MIT license but ship no license file. The MIT license has to travel with every copy, so the
 * build writes the standard text with the copyright holder given here (the author field of the package, or for Element
 * Plus the line its sibling package @element-plus/icons-vue prints). An entry is used only while the package still
 * declares MIT; a bundled package that has neither a license file nor an entry stops the build. Check the holder against
 * the upstream license file when you add an entry or update the package.
 */
export const SUPPLIED_MIT_LICENSES: Record<string, string> = {
  '@vue/devtools-api': 'Guillaume Chau',
  'element-plus': '2020-PRESENT Element Plus (https://github.com/element-plus)',
  'lodash-unified': 'Jack Works',
};

export function mitLicenseText(copyright: string): string {
  return [
    'MIT License',
    '',
    `Copyright (c) ${copyright}`,
    '',
    'Permission is hereby granted, free of charge, to any person obtaining a copy',
    'of this software and associated documentation files (the "Software"), to deal',
    'in the Software without restriction, including without limitation the rights',
    'to use, copy, modify, merge, publish, distribute, sublicense, and/or sell',
    'copies of the Software, and to permit persons to whom the Software is',
    'furnished to do so, subject to the following conditions:',
    '',
    'The above copyright notice and this permission notice shall be included in all',
    'copies or substantial portions of the Software.',
    '',
    'THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR',
    'IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,',
    'FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE',
    'AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER',
    'LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,',
    'OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE',
    'SOFTWARE.',
  ].join('\n');
}

/**
 * The copyright notice of logback, the logging library of the service. This project uses it under the LGPL-2.1 (section 3
 * of NOTICE), and section 6 of that license asks that a work which displays copyright notices while it runs includes the
 * library's copyright notice among them, together with a reference to the license. The console is where the running service
 * displays a copyright line, so the notices file it links to ends with the section written by serverSideNotice(). These are
 * the first two lines of licenses/logback-NOTICE.txt; tests/thirdPartyNotices.test.mjs checks that they still are.
 */
export const LOGBACK_COPYRIGHT = [
  'Logback: the reliable, generic, fast and flexible logging framework.',
  'Copyright (C) 1999-2026, QOS.ch. All rights reserved.',
];

export interface PackageNotice {
  name: string;
  version: string;
  license: string;
  homepage: string;
  files: { name: string; text: string }[];
}

/** The package directories under node_modules that contributed a module to the bundle. */
export function bundledPackageDirectories(moduleIds: Iterable<string>): string[] {
  const marker = '/node_modules/';
  const directories = new Set<string>();
  for (const raw of moduleIds) {
    // Plugins decorate ids ("\0" prefix, "?commonjs-exports" and similar suffixes); what is left is the real file.
    const id = raw.replace(/^\0/, '').split('?')[0].replace(/\\/g, '/');
    const at = id.lastIndexOf(marker);
    if (at < 0) continue;
    const parts = id.slice(at + marker.length).split('/');
    const depth = parts[0].startsWith('@') ? 2 : 1;
    if (parts.length <= depth) continue;
    directories.add(id.slice(0, at + marker.length) + parts.slice(0, depth).join('/'));
  }
  return [...directories].sort();
}

function licenseField(manifest: Record<string, unknown>): string {
  const single = manifest.license;
  if (typeof single === 'string') return single;
  if (single && typeof (single as { type?: unknown }).type === 'string') return (single as { type: string }).type;
  const several = manifest.licenses;
  if (Array.isArray(several)) {
    return several
      .map((item) => (typeof item === 'string' ? item : String((item as { type?: unknown }).type ?? '')))
      .filter(Boolean)
      .join(' OR ');
  }
  return '';
}

/**
 * Reads what a package says about its own license: the license field and every license file in its root. A package
 * that ships no license file gets the text of SUPPLIED_MIT_LICENSES, and one that is not in it stops the build.
 */
export function readPackageNotice(directory: string, suppliedMit: Record<string, string> = SUPPLIED_MIT_LICENSES): PackageNotice {
  if (!existsSync(path.join(directory, 'package.json'))) {
    throw new Error(`${directory} is bundled into the console but is not an npm package (no package.json)`);
  }
  const manifest = JSON.parse(readFileSync(path.join(directory, 'package.json'), 'utf8')) as Record<string, unknown>;
  const files = readdirSync(directory)
    .filter((name) => LICENSE_FILE.test(name) && statSync(path.join(directory, name)).isFile())
    .sort()
    .map((name) => ({ name, text: readFileSync(path.join(directory, name), 'utf8').replace(/\r\n/g, '\n').trim() }))
    .filter((file) => file.text);
  const notice: PackageNotice = {
    name: String(manifest.name ?? path.basename(directory)),
    version: String(manifest.version ?? ''),
    license: licenseField(manifest),
    homepage: typeof manifest.homepage === 'string' ? manifest.homepage : '',
    files,
  };
  if (!notice.files.length) {
    const copyright = suppliedMit[notice.name];
    if (copyright === undefined) {
      throw new Error(
        `${notice.name}@${notice.version} is bundled into the console but ships no license text (${directory}); ` +
          'add its license to SUPPLIED_MIT_LICENSES in vite.config.ts or drop the package',
      );
    }
    if (notice.license !== 'MIT') {
      throw new Error(
        `${notice.name}@${notice.version} declares the license "${notice.license}", but SUPPLIED_MIT_LICENSES has the MIT text for it; check the license`,
      );
    }
    notice.files.push({
      name: 'LICENSE (written by the Starry API build, because the package ships none)',
      text: mitLicenseText(copyright),
    });
  }
  return notice;
}

/** The section for logback that closes the notices file; see LOGBACK_COPYRIGHT. */
export function serverSideNotice(rule: string): string[] {
  return [
    '',
    rule,
    'logback-classic and logback-core (the logging library of the Starry API service; not part of the console)',
    'License: LGPL-2.1-only (logback is offered under EPL-2.0 or LGPL-2.1-only; Starry API uses it under LGPL-2.1-only)',
    'Homepage: https://logback.qos.ch/',
    '',
    '--- Copyright notice of logback ---',
    '',
    ...LOGBACK_COPYRIGHT,
    '',
    '--- The license of logback in Starry API ---',
    '',
    'Starry API uses logback-classic and logback-core, unmodified, under the GNU Lesser General Public License, version 2.1',
    'only (LGPL-2.1-only), and the Library and its use are covered by that License. The text of the License is at',
    'https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html, and a copy comes with every release of Starry API: the file',
    'licenses/LGPL-2.1.txt of the deployment bundle and META-INF/licenses/LGPL-2.1.txt of the executable jar, next to the',
    'notice of logback itself (logback-NOTICE.txt). Each logback library is its own jar under BOOT-INF/lib of the executable',
    'jar, so you can replace it with a modified version, and its source code is in the third-party-sources/ directory that',
    'is published with the release. Section 3 of the NOTICE file and JAVA-THIRD-PARTY-NOTICES.txt give the versions.',
  ];
}

export function renderThirdPartyNotices(notices: PackageNotice[]): string {
  const rule = '='.repeat(78);
  // The same release is often installed in several places (a nested node_modules); it is listed once.
  const distinct = new Map(notices.map((notice) => [JSON.stringify(notice), notice]));
  const sorted = [...distinct.values()].sort((a, b) => `${a.name}@${a.version}`.localeCompare(`${b.name}@${b.version}`));
  const lines = [
    'THIRD-PARTY NOTICES',
    '',
    'The Starry API console (web UI) bundles the open-source packages listed below. Each entry gives the',
    'license the package declares and the license and notice files it ships (for the few packages that ship',
    'none, the standard license text with the copyright holder). The last entry is the notice for logback, the',
    'logging library of the service, which Starry API uses under LGPL-2.1-only. Starry API itself is licensed under',
    'AGPL-3.0-only; see the LICENSE and NOTICE files of the distribution.',
    '',
    'This file is generated by the production build (thirdPartyNotices in webui/vite.config.ts) from the',
    'packages that end up in the bundle. Do not edit it by hand.',
    '',
    `${sorted.length} packages`,
  ];
  for (const notice of sorted) {
    lines.push('', rule, `${notice.name} ${notice.version}`.trim(), `License: ${notice.license || 'see the license text below'}`);
    if (notice.homepage) lines.push(`Homepage: ${notice.homepage}`);
    for (const file of notice.files) lines.push('', `--- ${file.name} ---`, '', file.text);
  }
  lines.push(...serverSideNotice(rule));
  return `${lines.join('\n')}\n`;
}

/**
 * Writes THIRD-PARTY-NOTICES.txt next to the console. Minified bundles lose the license comments of what they contain,
 * and MIT, BSD and Apache-2.0 packages must keep theirs wherever they are copied, so the notice is rebuilt from the
 * packages that actually went into the bundle. A bundled package without license text fails the build.
 */
export function thirdPartyNotices(): Plugin {
  return {
    name: 'starry:third-party-notices',
    apply: 'build',
    generateBundle(_options, bundle) {
      const ids = new Set<string>();
      for (const output of Object.values(bundle)) {
        if (output.type === 'chunk') Object.keys(output.modules).forEach((id) => ids.add(id));
      }
      this.emitFile({
        type: 'asset',
        fileName: NOTICES_FILE,
        source: renderThirdPartyNotices(bundledPackageDirectories(ids).map((directory) => readPackageNotice(directory))),
      });
    },
  };
}

/**
 * Vite 配置。
 *
 * 通过 VITE_API_BASE 控制开发环境调用的后端地址，
 * 默认指向本地 Spring Boot 服务（http://127.0.0.1:8080）。
 * 部署到生产时建议同源部署，避免跨域。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = env.VITE_API_BASE || 'http://127.0.0.1:8080';

  return {
    base: './',
    plugins: [
      vue(),
      // 把打进控制台的第三方包的许可证与声明写到 dist/THIRD-PARTY-NOTICES.txt（压缩后的 JS 不保留它们）
      thirdPartyNotices(),
      // 自动按需导入 Element Plus 样式
      ElementPlus({}),
      // Gzip 静态资源压缩
      viteCompression({
        verbose: true,
        disable: false,
        threshold: 1024,
        algorithm: 'gzip',
        ext: '.gz',
      }),
      // Brotli 静态资源压缩
      viteCompression({
        verbose: true,
        disable: false,
        threshold: 1024,
        algorithm: 'brotliCompress',
        ext: '.br',
      }),
      // Element Plus 按需自动注册组件与组合式 API
      AutoImport({
        imports: ['vue', 'vue-router', 'pinia'],
        resolvers: [ElementPlusResolver()],
        dts: 'auto-imports.d.ts',
      }),
      Components({
        resolvers: [ElementPlusResolver()],
        dts: 'components.d.ts',
      }),
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      host: '127.0.0.1',
      port: 5173,
      proxy: {
        // 仅代理后端管理与代理 API 路径，避免拦截前端路由
        '^/(auth|admin|system|payment|tickets|assistant|news|documentation|v1|health|models|chat|responses|messages|actuator|assets/model-icons|assets/branding|plugin-assets)':
          {
            target,
            changeOrigin: true,
          },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
      target: 'es2022',
      chunkSizeWarningLimit: 1024,
      rollupOptions: {
        output: {
          // 优化 Chunk 拆包策略，将第三方库与核心代码分离，提升浏览器缓存利用率
          manualChunks(id) {
            if (id.includes('node_modules')) {
              if (/node_modules\/(markdown-it|highlight.js|dompurify|linkify-it|uc.micro|mdurl|entities|argparse)\//.test(id)) {
                return 'vendor-documentation';
              }
              if (id.includes('element-plus')) {
                return 'vendor-element-plus';
              }
              if (
                id.includes('vue') ||
                id.includes('pinia') ||
                id.includes('vue-router') ||
                id.includes('axios')
              ) {
                return 'vendor-core';
              }
              return 'vendor-libs';
            }
          },
        },
      },
    },
  };
});
