// Runs only against the explicitly initialized disposable preview. SMTP stays on loopback.
const { chromium } = require('playwright');
const net = require('node:net');
const { readFileSync, mkdirSync, writeFileSync } = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const base = 'http://127.0.0.1:5175';
const output = '/private/tmp/starry-plugin-preview';
if (process.env.PLUGIN_PREVIEW_TESTS !== 'true' || !process.env.PLUGIN_PREVIEW_PASSWORD) {
  throw new Error('Requires PLUGIN_PREVIEW_TESTS=true and a disposable preview password');
}
const password = process.env.PLUGIN_PREVIEW_PASSWORD;
const pluginId = 'whale-widget';
const messages = [];
const smtp = net.createServer(socket => {
  socket.setEncoding('utf8'); socket.write('220 localhost plugin test SMTP\r\n');
  let input = ''; let data = false; let message = '';
  socket.on('data', chunk => {
    input += chunk;
    while (input.includes('\r\n')) {
      const index = input.indexOf('\r\n'); const line = input.slice(0, index); input = input.slice(index + 2);
      if (data) {
        if (line === '.') { messages.push(message); message = ''; data = false; socket.write('250 accepted\r\n'); }
        else message += line + '\r\n';
      } else if (/^EHLO|^HELO/i.test(line)) socket.write('250-localhost\r\n250 8BITMIME\r\n');
      else if (/^DATA/i.test(line)) { data = true; socket.write('354 end with dot\r\n'); }
      else if (/^QUIT/i.test(line)) { socket.end('221 bye\r\n'); }
      else socket.write('250 ok\r\n');
    }
  });
  socket.on('error', () => {});
});
function lastCode() {
  for (const message of messages.toReversed()) {
    const index = message.indexOf('\r\n\r\n');
    const headers = message.slice(0, index); let body = message.slice(index + 4);
    if (/Content-Transfer-Encoding:\s*base64/i.test(headers)) body = Buffer.from(body.replace(/\s/g, ''), 'base64').toString('utf8');
    else if (/Content-Transfer-Encoding:\s*quoted-printable/i.test(headers)) {
      body = Buffer.from(body.replace(/=\r\n/g, '').replace(/=([A-F0-9]{2})/gi, (_, hex) => String.fromCharCode(parseInt(hex, 16))), 'latin1').toString('utf8');
    }
    const code = body.match(/六位验证码[：:]\s*(\d{6})/);
    if (code) return code[1];
  }
  return undefined;
}
async function api(route, token, method = 'GET', body, expected = 200) {
  const response = await fetch(base + route, { method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body) });
  const payload = await response.json();
  assert.equal(response.status, expected, `${method} ${route}: ${payload.error?.code || response.status}`);
  return payload.data;
}
const report = { checks: [], screenshots: [], errors: [] };
async function main() {
  mkdirSync(path.join(output, 'screenshots'), { recursive: true });
  await new Promise((resolve, reject) => { smtp.once('error', reject); smtp.listen(2527, '127.0.0.1', resolve); });
  let session;
  const login = () => api('/auth/login', null, 'POST', { username: 'admin', password });
  try { session = await login(); }
  catch {
    const log = readFileSync(path.join(output, 'backend.log'), 'utf8');
    const original = [...log.matchAll(/Password: ([a-f0-9]{36})/g)].at(-1)?.[1];
    assert.ok(original, 'Disposable bootstrap credential must be available');
    const first = await api('/auth/login', null, 'POST', { username: 'admin', password: original });
    await api('/auth/password', first.accessToken, 'POST', { oldPassword: original, newPassword: password });
    session = await login();
  }
  const token = session.accessToken;
  await api(`/admin/users/${session.user.userId}`, token, 'PATCH', { email: 'plugin-root@example.test' });
  await api('/admin/users/registration-settings', token, 'PATCH', { smtpHost: '127.0.0.1', smtpPort: 2527,
    smtpUsername: '', smtpPassword: '', smtpFromEmail: 'noreply@example.test', smtpSecurity: 'none',
    smtpUseTls: false, smtpVerifyEnabled: false });
  await api('/admin/plugins/developer-mode', token, 'DELETE');
  const previousCode = (await api('/admin/plugins/audit', token)).find(event => event.action === 'CODE_DELIVERY' && event.outcome === 'SENT');
  const cooldown = previousCode ? Date.parse(previousCode.at) + 61_000 - Date.now() : 0;
  if (cooldown > 0) await new Promise(resolve => setTimeout(resolve, cooldown));
  const anonymous = await fetch(base + '/admin/plugins'); assert.equal(anonymous.status, 401);
  await api(`/admin/plugins/${pluginId}/actions/start`, token, 'POST', {}, 403);
  report.checks.push('anonymous rejected', 'disabled mode blocks execution');
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
  await ctx.addInitScript(session => { localStorage.setItem('kp.adminToken', session.accessToken); localStorage.setItem('kp.adminUser', JSON.stringify(session.user)); }, session);
  const page = await ctx.newPage(); page.on('pageerror', error => report.errors.push(error.message));
  async function shot(name) {
    await page.mouse.move(0, 0);
    await page.locator('.el-message').last().waitFor({ state: 'hidden', timeout: 8000 }).catch(() => {});
    await page.evaluate(async () => {
      await Promise.all(document.getAnimations().filter(a => a.effect?.getComputedTiming().iterations !== Infinity)
        .map(a => a.finished.catch(() => {})));
    });
    await page.screenshot({ path: path.join(output, 'screenshots', name + '.png'), fullPage: true }); report.screenshots.push(name);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, `No page overflow: ${name}`);
  }
  try {
    await page.goto(base + '/#/settings/plugins');
    await page.locator('.mode-control .el-switch').waitFor();
    await shot('desktop-disabled');
    await page.locator('.mode-control .el-switch').click();
    await page.getByRole('dialog').waitFor(); await shot('desktop-verification');
    await page.locator('input[type=password]').fill(password);
    await page.getByRole('button', { name: '发送验证码', exact: true }).click();
    for (let i = 0; i < 100 && !lastCode(); i++) await new Promise(resolve => setTimeout(resolve, 100));
    assert.ok(lastCode(), 'A real SMTP six-digit challenge was delivered');
    await page.getByPlaceholder('六位验证码').fill(lastCode());
    await page.getByRole('dialog').locator('.el-checkbox').click();
    const enabled = page.waitForResponse(response => response.url().endsWith('/admin/plugins/developer-mode') && response.request().method() === 'POST');
    await page.getByRole('button', { name: '验证并开启' }).click(); assert.equal((await enabled).status(), 200);
    await page.getByRole('dialog').waitFor({ state: 'hidden' });
    assert.equal((await api('/admin/plugins', token)).developerMode, true);
    report.checks.push('real root password and SMTP code enabled mode through UI');
    const installed = await api('/admin/plugins', token);
    if (installed.plugins.some(plugin => plugin.id === 'starry-demo')) {
      await api('/admin/plugins/starry-demo/actions/remove', token, 'POST', {});
      await page.reload(); await page.locator('input[type=file]').waitFor({ state: 'attached' });
    }
    const jar = path.resolve(__dirname, '../../../whale-whidget-plugin/build/libs/starry-whale-widget-plugin.jar');
    await page.locator('input[type=file]').setInputFiles(jar);
    await page.getByRole('button', { name: '上传', exact: true }).click();
    await page.getByText(pluginId, { exact: true }).waitFor();
    let state = await api('/admin/plugins', token); assert.equal(state.plugins[0].state, 'STOPPED');
    await page.getByRole('button', { name: '启动', exact: true }).click();
    await page.getByText('运行中', { exact: true }).waitFor(); await shot('desktop-running');
    await page.getByRole('button', { name: '配置', exact: true }).click();
    await page.getByRole('textbox', { name: '插件 JSON 配置' }).fill('{"preview":true}');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await page.getByRole('dialog').waitFor({ state: 'hidden' });
    await api(`/admin/plugins/${pluginId}/actions/reload`, token, 'POST', {});
    state = await api('/admin/plugins', token);
    assert.equal(state.restartRequired, false); assert.deepEqual(state.restartReasons, []);
    report.checks.push('healthy hot reload does not require a host restart');
    await page.getByRole('button', { name: '调试', exact: true }).click();
    await page.getByRole('button', { name: '执行', exact: true }).click();
    await page.getByText(/执行结果/).waitFor(); await shot('desktop-debug');
    const command = async name => JSON.parse((await api(`/admin/plugins/${pluginId}/debug`, token, 'POST', { command: name, json: '{}' })).json);
    assert.equal((await command('health')).sdkVersion, '1.1');
    assert.equal((await command('health')).status, 'ok');
    const capabilities = await command('capabilities');
    const script = await fetch(base + capabilities.script);
    assert.equal(script.status, 200); assert.ok((await script.text()).length > 0);
    assert.equal(JSON.parse(await api(`/admin/plugins/${pluginId}/config`, token)).preview, true);
    report.checks.push('SDK 1.1 health and widget assets after hot reload');
    report.checks.push('real JAR installed without execution', 'start, configuration, reload and debug');
    await page.getByRole('dialog').locator('.el-dialog__headerbtn').click();
    await page.getByRole('tab', { name: '运行日志' }).click();
    await page.getByRole('log').waitFor(); await shot('desktop-logs');
    await page.getByRole('tab', { name: '操作审计' }).click();
    await page.getByRole('cell', { name: 'SUCCEEDED', exact: true }).first().waitFor(); await shot('desktop-audit');
    for (const width of [390, 768]) {
      await page.setViewportSize({ width, height: 844 });
      await page.getByRole('tab', { name: '已安装插件' }).click(); await shot(`${width}-plugins`);
      await page.getByRole('tab', { name: '运行日志' }).click(); await page.getByRole('log').waitFor(); await shot(`${width}-logs`);
    }
    const diagnosticState = { ...state, restartRequired: true,
      plugins: state.plugins.map(plugin => ({ ...plugin, state: 'RESTART_REQUIRED', autoStart: false })),
      restartReasons: [{ pluginId, operation: 'CLEANUP', code: 'TASKS_STILL_RUNNING',
        detail: '托管任务在清理等待期内未退出', at: new Date().toISOString() }] };
    await page.route('**/admin/plugins', route => route.fulfill({ json: { success: true, data: diagnosticState } }));
    await page.route('**/system/plugin-widgets', route => route.fulfill({ json: { success: true, data: [] } }));
    try {
      for (const width of [1440, 390]) {
        await page.setViewportSize({ width, height: 1000 });
        await page.reload();
        await page.getByText('需重启', { exact: true }).waitFor();
        assert.equal(await page.getByRole('button', { name: '重载', exact: true }).isDisabled(), true);
        assert.equal(await page.getByRole('button', { name: '调试', exact: true }).isDisabled(), true);
        assert.equal(await page.getByRole('button', { name: '重试清理', exact: true }).isEnabled(), true);
        await shot(`${width}-restart-diagnostic-fixture`);
      }
      report.checks.push('mocked restart diagnostic layout and blocked controls on desktop/mobile');
    } finally {
      await page.unroute('**/admin/plugins');
      await page.unroute('**/system/plugin-widgets');
    }
    await api('/admin/plugins/developer-mode', token, 'DELETE');
    state = await api('/admin/plugins', token);
    assert.equal(state.developerMode, false); assert.equal(state.plugins[0].state, 'STOPPED');
    assert.equal(state.restartRequired, false); assert.deepEqual(state.restartReasons, []);
    await api(`/admin/plugins/${pluginId}/actions/start`, token, 'POST', {}, 403);
    await page.reload(); await page.locator('.mode-control .el-switch').waitFor();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('.mode-control .el-switch').click();
    await page.getByRole('dialog').waitFor(); await shot('mobile-verification');
    report.checks.push('disable stops loaded plugin and blocks further execution', 'desktop/tablet/mobile layouts');
    assert.deepEqual(report.errors, []);
  } finally {
    await api('/admin/plugins/developer-mode', token, 'DELETE').catch(() => {});
    await browser.close(); await new Promise(resolve => smtp.close(resolve));
    writeFileSync(path.join(output, 'browser-report.json'), JSON.stringify(report, null, 2));
  }
  console.log(JSON.stringify(report));
}
main().catch(error => { console.error(error); smtp.close(); process.exitCode = 1; });
