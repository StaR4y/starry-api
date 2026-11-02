// Only the disposable loopback preview is touched. No external email is sent.
const { chromium } = require('playwright');
const net = require('node:net');
const { mkdirSync, writeFileSync } = require('node:fs');
const assert = require('node:assert/strict');
const os = require('node:os');
const path = require('node:path');
const base = 'http://127.0.0.1:5175';
const output = path.join(os.tmpdir(), 'starry-ticket-preview');
if (process.env.TICKET_PREVIEW_TESTS !== 'true' || !process.env.TICKET_PREVIEW_PASSWORD) {
  throw new Error('Requires TICKET_PREVIEW_TESTS=true and a disposable preview password');
}
const report = { checks: [], screenshots: [], errors: [] };
const messages = [];
let failMail = false;
const smtp = net.createServer(socket => {
  socket.setEncoding('utf8'); socket.write('220 localhost ticket test SMTP\r\n');
  let input = ''; let data = false; let message = '';
  socket.on('error', () => {});
  socket.on('data', chunk => {
    input += chunk;
    while (input.includes('\r\n')) {
      const index = input.indexOf('\r\n'); const line = input.slice(0, index); input = input.slice(index + 2);
      if (data) {
        if (line === '.') { messages.push(message); message = ''; data = false; socket.write('250 accepted\r\n'); }
        else message += line + '\r\n';
      } else if (/^EHLO|^HELO/i.test(line)) socket.write('250-localhost\r\n250 8BITMIME\r\n');
      else if (/^DATA/i.test(line)) {
        if (failMail) socket.write('451 temporary test failure\r\n');
        else { data = true; socket.write('354 end with dot\r\n'); }
      } else if (/^QUIT/i.test(line)) socket.end('221 bye\r\n');
      else socket.write('250 ok\r\n');
    }
  });
});
function decoded(message) {
  const index = message.indexOf('\r\n\r\n'); const headers = message.slice(0, index); let body = message.slice(index + 4);
  if (/Content-Transfer-Encoding:\s*base64/i.test(headers)) body = Buffer.from(body.replace(/\s/g, ''), 'base64').toString('utf8');
  else if (/Content-Transfer-Encoding:\s*quoted-printable/i.test(headers)) body = Buffer.from(body.replace(/=\r\n/g, '').replace(/=([A-F0-9]{2})/gi, (_, hex) => String.fromCharCode(parseInt(hex, 16))), 'latin1').toString('utf8');
  return body.replace(/\r\n/g, '\n');
}
async function api(route, token, method = 'GET', body, expected = 200) {
  const response = await fetch(base + route, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
  const payload = await response.json();
  assert.equal(response.status, expected, `${method} ${route}: ${JSON.stringify(payload.error)}`);
  return payload.data;
}
async function submit(route, token, body, files = [], expected = 200) {
  const form = new FormData(); form.append('request', new Blob([JSON.stringify(body)], { type: 'application/json' }));
  files.forEach(file => form.append('files', new Blob([file.data]), file.name));
  const response = await fetch(base + route, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form });
  const payload = await response.json();
  assert.equal(response.status, expected, `submit ${route}: ${JSON.stringify(payload.error)}`); return payload.data;
}
async function until(check) {
  for (let i = 0; i < 100; i++) { const result = await check(); if (result) return result; await new Promise(resolve => setTimeout(resolve, 150)); }
  throw new Error('Expected asynchronous result did not arrive');
}
async function main() {
  mkdirSync(output, { recursive: true });
  await new Promise((resolve, reject) => { smtp.once('error', reject); smtp.listen(2529, '127.0.0.1', resolve); });
  const root = await api('/auth/login', null, 'POST', { username: 'admin', password: process.env.TICKET_PREVIEW_PASSWORD });
  const adminToken = root.accessToken;
  const original = (await api('/admin/ticket-settings', adminToken)).settings;
  await api('/admin/ticket-settings', adminToken, 'PUT', { ...original, enabled: false });
  await api(`/admin/users/${root.user.userId}`, adminToken, 'PATCH', { email: 'ticket-admin@example.test' });
  await api('/admin/users/registration-settings', adminToken, 'PATCH', { smtpHost: '127.0.0.1', smtpPort: 2529, smtpUsername: '', smtpPassword: '', smtpFromEmail: 'noreply@example.test', smtpSecurity: 'none', smtpUseTls: false, smtpVerifyEnabled: false });
  async function user(suffix) {
    const username = 'ticket-' + Date.now().toString(36) + '-' + suffix;
    const password = 'Ticket-test-only-2026!'; const changed = password + 'new';
    await api('/admin/users', adminToken, 'POST', { username, role: 'USER', email: `${username}@example.test`, password });
    let session = await api('/auth/login', null, 'POST', { username, password });
    if (session.user.firstLogin) {
      await api('/auth/password', session.accessToken, 'POST', { oldPassword: password, newPassword: changed });
      session = await api('/auth/login', null, 'POST', { username, password: changed });
    }
    return session;
  }
  const alice = await user('alice'); const bob = await user('bob');
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  let ticketId;
  const contexts = [];
  async function pageFor(session, viewport = { width: 1440, height: 1000 }) {
    const ctx = await browser.newContext({ viewport, reducedMotion: 'reduce' }); contexts.push(ctx);
    await ctx.addInitScript(session => { localStorage.setItem('kp.adminToken', session.accessToken); localStorage.setItem('kp.adminUser', JSON.stringify(session.user)); }, session);
    const page = await ctx.newPage(); page.on('pageerror', error => report.errors.push(error.message)); return page;
  }
  async function shot(page, name) {
    await page.mouse.move(0, 0);
    await page.locator('.el-message').last().waitFor({ state: 'hidden', timeout: 8000 }).catch(() => {});
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await page.evaluate(async () => { await Promise.all(document.getAnimations().filter(a => a.effect?.getComputedTiming().iterations !== Infinity).map(a => a.finished.catch(() => {}))); });
    await page.screenshot({ path: `${output}/${name}.png`, fullPage: true });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true, `No overflow: ${name}`);
    report.screenshots.push(name);
  }
  try {
    const page = await pageFor(alice);
    await page.goto(base + '/#/tickets');
    await page.getByRole('button', { name: '新建工单', exact: true }).click();
    await page.getByLabel('工单标题', { exact: true }).fill('API 调用失败，已附上请求日志');
    const problem = '使用 Responses API 时返回 429，请帮忙确认账号限流情况。\n请求时间：2026-09-20 09:30\n<svg onload=alert(1)> 保持为文本';
    await page.getByLabel('问题描述', { exact: true }).fill(problem);
    await page.locator('input[type=file]').setInputFiles([
      { name: '请求日志.txt', mimeType: 'text/plain', buffer: Buffer.from('request-id=test-001\nstatus=429') },
      { name: 'response.json', mimeType: 'application/json', buffer: Buffer.from('{"status":429}') },
      { name: 'context.log', mimeType: 'text/plain', buffer: Buffer.from('trace details') },
    ]);
    await shot(page, 'desktop-new-ticket');
    await page.getByRole('button', { name: '提交工单', exact: true }).click();
    await page.waitForURL(/#\/tickets\/[a-f0-9-]+$/);
    ticketId = page.url().split('/').at(-1);
    await page.getByText('请求日志.txt', { exact: true }).waitFor();
    assert.equal(await page.locator('.ticket-message__body svg').count(), 0);
    await shot(page, 'desktop-user-thread');
    report.checks.push('user created ticket with three attachments in browser', 'user text renders without HTML execution');
    let thread = await api(`/tickets/${ticketId}`, alice.accessToken);
    assert.equal(thread.messages[0].attachments.length, 3);
    const file = thread.messages[0].attachments.find(file => file.filename === '请求日志.txt');
    const denied = await fetch(`${base}/tickets/${ticketId}/attachments/${file.id}`, { headers: { Authorization: `Bearer ${bob.accessToken}` } }); assert.equal(denied.status, 404);
    const download = page.waitForEvent('download'); await page.getByRole('button', { name: /请求日志.txt/ }).click(); assert.equal((await download).suggestedFilename(), '请求日志.txt');
    const allowed = await fetch(`${base}/tickets/${ticketId}/attachments/${file.id}`, { headers: { Authorization: `Bearer ${alice.accessToken}` } });
    assert.equal(allowed.headers.get('x-content-type-options'), 'nosniff'); assert.equal(allowed.headers.get('cache-control'), 'no-store');
    assert.equal(await allowed.text(), 'request-id=test-001\nstatus=429');
    await api(`/tickets/${ticketId}`, bob.accessToken, 'GET', undefined, 404);
    await api(`/tickets/${ticketId}/status`, bob.accessToken, 'PATCH', { status: 'CLOSED', version: 1 }, 404);
    await submit(`/tickets/${ticketId}/replies`, bob.accessToken, { body: 'forbidden', version: 1 }, [], 404);
    await api('/admin/ticket-settings', alice.accessToken, 'GET', undefined, 403);
    await api('/tickets', null, 'GET', undefined, 401);
    assert.equal((await api('/tickets', bob.accessToken)).total, 0);
    await submit('/tickets', alice.accessToken, { title: 'oversize', category: 'API', body: 'body' }, [{ name: 'large.log', data: Buffer.alloc(2 * 1024 * 1024 + 1) }], 413);
    await submit('/tickets', alice.accessToken, { title: 'empty', category: 'API', body: ' ' }, [], 400);
    report.checks.push('cross-user list/detail/reply/status/download isolation', 'admin settings authorization', 'anonymous blocked', 'oversized attachment and empty message rejected', 'authenticated attachment download byte integrity');

    const admin = await pageFor(root);
    await admin.goto(base + `/#/tickets/${ticketId}`);
    await admin.getByLabel('回复内容', { exact: true }).fill('已确认是上游限流。请稍后重试，并补充请求 ID。');
    await admin.getByRole('button', { name: '发送回复', exact: true }).click();
    await admin.getByText('已确认是上游限流。请稍后重试，并补充请求 ID。', { exact: true }).waitFor();
    await shot(admin, 'desktop-admin-thread');
    thread = await api(`/tickets/${ticketId}`, alice.accessToken); assert.equal(thread.ticket.status, 'WAITING_USER');
    await submit(`/tickets/${ticketId}/replies`, alice.accessToken, { body: 'stale reply', version: 1 }, [], 409);

    let config = { enabled: true, mode: 'SUMMARY', recipients: ['ticket-admin@example.test'], reminderMinutes: 15, consoleUrl: base };
    await api('/admin/ticket-settings', adminToken, 'PUT', config);
    await submit(`/tickets/${ticketId}/replies`, alice.accessToken, { body: 'PRIVATE-REPLY-ONE 请求ID test-001', version: thread.ticket.version });
    await until(() => messages.find(mail => mail.includes(ticketId)));
    let received = messages.filter(mail => mail.includes(ticketId));
    assert.equal(decoded(received.at(-1)).includes('PRIVATE-REPLY-ONE'), false);
    assert.equal(decoded(received.at(-1)).includes(problem), false);
    report.checks.push('SUMMARY email omits conversation body');
    config = { ...config, mode: 'CONTENT' }; await api('/admin/ticket-settings', adminToken, 'PUT', config);
    thread = await api(`/tickets/${ticketId}`, alice.accessToken);
    const count = received.length;
    await submit(`/tickets/${ticketId}/replies`, alice.accessToken, { body: 'PRIVATE-REPLY-TWO 仍有异常', version: thread.ticket.version });
    await until(() => messages.filter(mail => mail.includes(ticketId)).length > count);
    received = messages.filter(mail => mail.includes(ticketId));
    assert.ok(decoded(received.at(-1)).includes('PRIVATE-REPLY-TWO'));
    assert.ok(decoded(received.at(-1)).includes(problem));
    assert.ok(decoded(received.at(-1)).includes('请求日志.txt'));
    assert.ok(decoded(received.at(-1)).includes(`${base}/#/tickets/${ticketId}`));
    report.checks.push('CONTENT email contains initial problem, replies, attachment names and direct link');

    failMail = true; thread = await api(`/tickets/${ticketId}`, alice.accessToken);
    await submit(`/tickets/${ticketId}/replies`, alice.accessToken, { body: 'SMTP failure must not lose this reply', version: thread.ticket.version });
    await until(async () => (await api('/admin/ticket-settings', adminToken)).failures.some(f => f.ticketId === ticketId));
    await admin.goto(base + '/#/settings/tickets');
    await admin.getByText('SMTP 发送失败', { exact: false }).waitFor();
    await shot(admin, 'desktop-notification-failure');
    failMail = false;
    await admin.getByRole('button', { name: '立即重试', exact: true }).first().click();
    await until(async () => !(await api('/admin/ticket-settings', adminToken)).failures.some(f => f.ticketId === ticketId));
    report.checks.push('real SMTP temporary rejection persisted and manual retry recovered');
    await admin.getByRole('button', { name: '重新加载通知设置', exact: true }).click();
    await shot(admin, 'desktop-notification-settings');

    await page.reload(); await page.getByRole('button', { name: '关闭工单', exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: '关闭工单', exact: true }).click();
    await page.getByRole('button', { name: '重新打开工单', exact: true }).waitFor();
    thread = await api(`/tickets/${ticketId}`, alice.accessToken); assert.equal(thread.ticket.status, 'CLOSED');
    const closedMailCount = messages.filter(mail => mail.includes(ticketId)).length;
    await new Promise(resolve => setTimeout(resolve, 2500));
    assert.equal(messages.filter(mail => mail.includes(ticketId)).length, closedMailCount);
    await page.getByRole('button', { name: '重新打开工单', exact: true }).click();
    await page.getByLabel('回复内容', { exact: true }).waitFor();
    report.checks.push('user closes and reopens ticket; closed tickets stop reminders');
    await page.setViewportSize({ width: 390, height: 844 }); await shot(page, 'mobile-user-thread');
    await page.getByRole('button', { name: '返回工单列表', exact: true }).click(); await page.getByRole('button', { name: /API 调用失败/ }).waitFor(); await shot(page, 'mobile-user-list');
    await page.getByRole('button', { name: '新建工单', exact: true }).click(); await shot(page, 'mobile-new-ticket');
    const mobileAdmin = await pageFor(root, { width: 390, height: 844 });
    await mobileAdmin.goto(base + '/#/settings/tickets');
    await mobileAdmin.getByRole('button', { name: '保存设置', exact: true }).waitFor();
    await shot(mobileAdmin, 'mobile-settings'); await mobileAdmin.close();
    await admin.setViewportSize({ width: 1440, height: 1000 }); await admin.goto(base + '/#/tickets');
    await admin.getByRole('button', { name: /API 调用失败/ }).first().waitFor(); await shot(admin, 'desktop-admin-list');
    report.checks.push('desktop and 390px mobile layouts have no overflow', 'no browser runtime errors');
    assert.deepEqual(report.errors, []);
  } finally {
    await api('/admin/ticket-settings', adminToken, 'PUT', { ...original, enabled: false });
    await browser.close(); smtp.close();
    writeFileSync(`${output}/report.json`, JSON.stringify(report, null, 2));
  }
  console.log(JSON.stringify(report, null, 2));
}
main().catch(error => { console.error(error); smtp.close(); process.exitCode = 1; });
