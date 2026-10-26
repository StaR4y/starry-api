import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { JSDOM } from 'jsdom';
import { build } from 'esbuild';

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>');
globalThis.window = dom.window;
// Bundle the TypeScript module in memory so this test also works with Node 20.
const compiled = await build({ entryPoints: ['src/utils/documentMarkdown.ts'], bundle: true, write: false, format: 'esm', platform: 'node' });
const { renderDocument, safeDocLink } = await import(`data:text/javascript;base64,${Buffer.from(compiled.outputFiles[0].text).toString('base64')}`);
const render = (body, files = []) => renderDocument(body, files, 'https://api.example.com');
const documentFor = html => new JSDOM(html).window.document;

test('escapes HTML and rejects executable links and inline data images', () => {
  const result = render('<script>alert(1)</script>\n\n[x](javascript:alert(1))\n\n![bad](data:image/svg+xml;base64,PHN2Zz4=)\n\n<iframe src="https://example.com"></iframe>');
  const doc = documentFor(result.html);
  assert.equal(doc.querySelector('script, iframe, [onerror], [onclick]'), null);
  assert.equal(doc.querySelector('a[href^="javascript:"], img'), null);
  assert.equal(safeDocLink('javascript:alert(1)'), false);
  assert.equal(safeDocLink('//evil.example.com'), false);
});

test('preserves exact code for copy and highlights explicit and inferred languages', () => {
  const code = 'const token = "<script>";\n  console.log(token);\n';
  const result = render('```javascript\n' + code + '```\n\n```\n{"enabled": true}\n```');
  assert.equal(result.codes[0], code);
  const doc = documentFor(result.html);
  assert.equal(doc.querySelector('code').textContent, code);
  assert.equal(doc.querySelectorAll('.doc-code__tools').length, 2);
  assert.equal(doc.querySelector('.doc-code__tools').getAttribute('data-code-index'), '0');
  assert.ok(doc.querySelector('.hljs-keyword'));
  assert.equal(doc.querySelector('script'), null);
});

test('unknown code language stays readable and placeholders use the current site', () => {
  const result = render('```unknown\n{{api_base_url}}\n```\n\n{{site_url}}');
  assert.equal(result.codes[0], 'https://api.example.com/v1\n');
  assert.ok(result.html.includes('https://api.example.com'));
});

test('only known attachments resolve, video is native, and external links are isolated', () => {
  const id = '6aa56246-a075-4080-8ba0-91b5b54fa809';
  const attachment = { id, filename: 'sample.mp4', contentType: 'video/mp4', sizeBytes: 12, url: `/documentation/media/${id}?signature=test` };
  const markdown = `![demo](attachment:${id})\n\n[download](attachment:${id})\n\n[official](https://ccswitch.io)\n\n[API Key](#/api-keys)`;
  const doc = documentFor(render(markdown, [attachment]).html);
  assert.equal(doc.querySelector('video').getAttribute('src'), attachment.url);
  assert.ok(doc.querySelector('video[controls][preload="metadata"]'));
  assert.equal(doc.querySelectorAll('a')[1].getAttribute('rel'), 'noopener noreferrer');
  assert.equal(doc.querySelectorAll('a')[2].getAttribute('href'), '#/api-keys');
  const missing = documentFor(render(markdown).html);
  assert.equal(missing.querySelector('video'), null);
  assert.ok(missing.querySelector('.doc-media-missing'));
  const forged = documentFor(render(markdown, [{ ...attachment, url: 'javascript:alert(1)' }]).html);
  assert.equal(forged.querySelector('video'), null);
});

test('heading anchors stay unique and image alt text cannot inject markup', () => {
  const result = render('## Repeated\n\n## Repeated\n\n![" onerror="alert(1)](https://example.com/a.png)');
  assert.deepEqual(result.headings.map(h => h.id), ['doc-heading-1', 'doc-heading-2']);
  assert.equal(documentFor(result.html).querySelector('[onerror]'), null);
});

// The default tutorials that explain source tiers (V30) are Markdown inside a migration, so a mistake in a table or a link
// would only show to a reader. Render every body the migration installs, in both languages.
const tierTutorials = readFileSync(
  new URL('../../service/src/main/resources/db/migration/V30__documentation_source_tiers.sql', import.meta.url),
  'utf8',
);
const tierBodies = [...tierTutorials.matchAll(/\$doc\$([\s\S]*?)\$doc\$/g)].map(match => match[1]);
const siteRoutes = new Set(['#/api-keys', '#/models', '#/payment', '#/coding-plans', '#/welcome', '#/tickets', '#/model-ranking', '#/news', '#/documentation']);

test('the source-tier tutorials render as tables, code and site links without raw HTML', () => {
  assert.equal(tierBodies.length, 12);
  for (const [index, body] of tierBodies.entries()) {
    const result = render(body);
    const doc = documentFor(result.html);
    const where = `body ${index} (${index % 2 === 0 ? 'Chinese' : 'English'})`;
    assert.equal(doc.querySelector('script, iframe, [onerror], [onclick]'), null, where);
    // A table that Markdown cannot parse (a missing separator row, rows of another width) stays a paragraph of pipes.
    for (const paragraph of doc.querySelectorAll('p')) {
      assert.ok(!paragraph.textContent.trimStart().startsWith('|'), `${where}: a table did not render: ${paragraph.textContent.slice(0, 60)}`);
    }
    for (const row of doc.querySelectorAll('table')) {
      const widths = new Set([...row.querySelectorAll('tr')].map(tr => tr.children.length));
      assert.equal(widths.size, 1, `${where}: table rows of different widths`);
    }
    assert.equal(new Set(result.headings.map(heading => heading.id)).size, result.headings.length, where);
    for (const anchor of doc.querySelectorAll('a')) {
      const href = anchor.getAttribute('href');
      assert.ok(href.startsWith('#/documentation/') || siteRoutes.has(href) || href.startsWith('https://'), `${where}: ${href}`);
    }
  }
});

test('the client tutorials keep their code blocks and the tier examples copy as written', () => {
  const copied = tierBodies.flatMap(body => render(body).codes);
  assert.ok(copied.includes("export ANTHROPIC_CUSTOM_HEADERS='X-Starry-Tier: economy'\n"));
  assert.ok(copied.some(code => code.includes('http_headers = { "X-Starry-Tier" = "economy" }')));
  assert.ok(copied.some(code => code.includes('"headers": { "X-Starry-Tier": "economy" }')));
  // A copy button never copies a site placeholder unresolved.
  assert.ok(copied.every(code => !code.includes('{{')));
});
