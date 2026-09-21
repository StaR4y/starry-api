// All business APIs are mocked. No security keys, plugins, or backups are changed.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const { mkdirSync } = require('node:fs');
const base = process.env.ROOT_ACCESS_UI_BASE || 'http://127.0.0.1:5175';
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(base).hostname));
const output = '/private/tmp/starry-root-access-ui';
const errors = [];
const checks = [];
const response = data => ({ success: true, data });

async function main() {
  mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    for (const scenario of ['root', 'ordinary-admin', 'static-token', 'missing-permissions', 'failed-permissions', 'expired-session']) {
      const user = { userId: scenario === 'static-token' ? 'env-admin' : scenario, username: scenario === 'root' ? 'initial-operator' : 'admin', role: 'ADMIN', enabled: true, firstLogin: false };
      let root = scenario === 'root';
      const requests = [];
      let permissionCalls = 0;
      const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
      await context.addInitScript(user => {
        localStorage.setItem('kp.adminToken', 'test-token');
        // A forged cached flag must never grant access.
        localStorage.setItem('kp.adminUser', JSON.stringify({ ...user, root: true, isRoot: true }));
      }, user);
      const page = await context.newPage();
      page.setDefaultTimeout(10000);
      page.on('pageerror', error => errors.push(error.message));
      await context.route('**/*', async route => {
        const request = route.request();
        const path = new URL(request.url()).pathname;
        const ok = value => route.fulfill({ json: response(value) });
        if (path === '/auth/permissions') {
          permissionCalls++;
          const failure = { 'missing-permissions': 404, 'failed-permissions': 503, 'expired-session': 401 }[scenario];
          return failure ? route.fulfill({ status: failure, json: { success: false, error: { code: 'TEST_PERMISSION_FAILURE', message: 'Permissions unavailable' } } }) : ok({ root });
        }
        if (path === '/auth/me') return ok(user);
        if (path === '/auth/registration-settings') return ok({ registrationEnabled: false });
        if (path === '/system/appearance') return ok({ chrome: 'editorial' });
        if (path === '/system/plugin-widgets' || path === '/system/plugin-settings') return ok([]);
        if (path.startsWith('/system/')) return ok({ enabled: false, maintenance: false, status: 'operational' });
        if (path.startsWith('/admin/data-transfer') || path.startsWith('/admin/plugins')) {
          requests.push(path);
          assert.equal(request.method(), 'GET', 'No protected mutation expected');
          if (path === '/admin/data-transfer/security/status') return ok({ initialized: false, locked: false, failedAttempts: 0, maxFailedAttempts: 5 });
          if (path === '/admin/plugins') return ok({ root: true, developerMode: false, runtimeDisabled: false, restartRequired: false, rootEmail: 'root@example.test', apiVersion: '1', plugins: [], restartReasons: [] });
          return ok([]);
        }
        if (path === '/admin/users/registration-settings') return ok({});
        if (path.startsWith('/admin/usage-summary')) return ok(null);
        if (path.startsWith('/admin/')) return ok([]);
        if (path === '/health') return route.fulfill({ json: { status: 'UP', uptimeMs: 0 } });
        assert.equal(request.method(), 'GET', 'Unexpected mutation: ' + path);
        return route.continue();
      });
      await page.goto(base + '/#/settings/smtp');
      if (scenario === 'expired-session') {
        await page.waitForURL(/#\/login/);
        assert.deepEqual(requests, []);
        assert.equal(await page.evaluate(() => localStorage.getItem('kp.adminToken')), null);
        checks.push('expired session clears authentication and returns to login');
        await context.close();
        continue;
      }
      await page.getByRole('heading', { name: 'SMTP 设置', exact: true }).waitFor();
      assert.equal(await page.getByRole('button', { name: '数据管理', exact: true }).count(), root ? 1 : 0);
      assert.equal(await page.getByRole('button', { name: '插件开发', exact: true }).count(), root ? 1 : 0);
      await page.screenshot({ path: output + '/' + scenario + '.png', fullPage: true, animations: 'disabled' });
      for (const target of ['data-transfer', 'settings/plugins']) {
        await page.goto(base + '/#/' + target);
        if (root) {
          await page.getByRole('heading', { name: target === 'data-transfer' ? '数据管理' : '插件开发', exact: true }).waitFor();
          assert.ok(page.url().endsWith('/' + target));
        } else {
          await page.waitForURL('**/#/dashboard');
          assert.deepEqual(requests, []);
        }
      }
      if (root) {
        assert.ok(requests.includes('/admin/data-transfer/security/status'));
        assert.ok(requests.includes('/admin/plugins'));
        const previousCalls = permissionCalls;
        root = false;
        requests.length = 0;
        await page.goto(base + '/#/settings/smtp');
        await page.getByRole('heading', { name: 'SMTP 设置', exact: true }).waitFor();
        await page.getByRole('button', { name: '插件开发', exact: true }).click();
        await page.waitForURL('**/#/dashboard');
        assert.ok(permissionCalls > previousCalls, 'Root entry must refresh permissions');
        assert.equal(await page.getByRole('button', { name: '数据管理', exact: true }).count(), 0);
        assert.deepEqual(requests, []);
        checks.push('root uses both modules; revoked permission is rechecked before entry');
      } else checks.push(scenario + ': menus hidden, direct links rejected, no protected API reads');
      await context.close();
    }
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ checks, pageErrors: errors, screenshots: output }, null, 2));
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
