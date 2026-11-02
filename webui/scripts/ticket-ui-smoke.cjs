// UI-only regression: all business API calls are intercepted; no tickets or email are sent.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const { mkdirSync } = require('node:fs');
const os = require('node:os');
const { join } = require('node:path');
const base = process.env.TICKET_UI_BASE || 'http://127.0.0.1:5175';
const output = process.env.TICKET_UI_OUTPUT || join(os.tmpdir(), 'starry-ticket-ui', 'pages');
assert.ok(['localhost', '127.0.0.1'].includes(new URL(base).hostname));
const stamp = '2026-09-21T02:30:00Z';
const ticketId = '61ae8aca-e6b4-4d8f-a08d-04bb3f26eb90';
const originalTitle = 'API 请求返回 429，希望确认账号限流状态';
const envelope = data => ({ success: true, data, timestamp: stamp });
const attachment = { id: 'attachment-1', filename: 'request-log.txt', sizeBytes: 25 };
const errors = [];
const checks = [];

async function main() {
  mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  try {
    for (const chrome of ['classic', 'editorial']) {
      for (const role of ['USER', 'ADMIN']) {
        const user = { userId: 'preview-user', username: 'linyu', displayName: '林雨', role, firstLogin: false, balanceMicros: 0 };
        const state = {
          ticket: { id: ticketId, userId: user.userId, username: 'linyu', title: originalTitle, category: 'API', status: 'WAITING_USER', version: 2, messageCount: 2, createdAt: stamp, updatedAt: stamp },
          messages: [
            { id: 1, actorName: 'linyu', admin: false, kind: 'MESSAGE', body: '调用 Responses API 时返回 429。\n请求时间：09:30\n已附上脱敏日志，请帮忙确认限流情况。', status: 'OPEN', createdAt: stamp, attachments: [attachment] },
            { id: 2, actorName: 'Support', admin: true, kind: 'MESSAGE', body: '已经收到反馈，正在核对该时间段的请求记录。\n请补充请求 ID，便于进一步定位。', status: 'WAITING_USER', createdAt: stamp, attachments: [] },
          ],
          failCreate: false, failRead: false, conflict: false, hasMore: false, uploads: [],
        };
        const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
        await context.addInitScript(value => {
          localStorage.setItem('kp.adminToken', 'ui-test-token');
          localStorage.setItem('kp.adminUser', JSON.stringify(value));
        }, user);
        const page = await context.newPage();
        page.setDefaultTimeout(10000);
        page.on('pageerror', error => errors.push(error.message));
        const ok = (route, data) => route.fulfill({ json: envelope(data) });
        const fail = (route, status) => route.fulfill({ status, json: { success: false, error: { code: 'UI_TEST', message: '测试请求失败，草稿应保留' } } });
        function append(kind, body, admin, status, attachments = []) {
          state.ticket.status = status;
          state.ticket.version++;
          state.ticket.messageCount++;
          state.messages.push({ id: state.ticket.messageCount, actorName: admin ? 'Support' : 'linyu', admin, kind, body, status, createdAt: stamp, attachments });
        }
        await context.route('**/*', async route => {
          const request = route.request();
          const url = new URL(request.url());
          const path = url.pathname;
          try {
            if (path === '/system/appearance') return ok(route, { chrome });
            if (path === '/system/plugin-widgets' || path === '/system/plugin-settings') return ok(route, []);
            if (path.startsWith('/system/')) return ok(route, { enabled: false, status: 'operational' });
            if (path === '/auth/me') return ok(route, user);
            if (path.startsWith('/tickets')) {
              if (request.method() === 'POST') {
                const form = await new Response(request.postDataBuffer(), { headers: { 'Content-Type': request.headers()['content-type'] } }).formData();
                const input = JSON.parse(await form.get('request').text());
                state.uploads = await Promise.all(form.getAll('files').map(async file => ({ name: file.name, content: await file.text() })));
                if (path === '/tickets') {
                  if (state.failCreate) return fail(route, 500);
                  state.ticket = { ...state.ticket, title: input.title, category: input.category, status: 'OPEN', version: 1, messageCount: 1 };
                  state.messages = [{ id: 1, actorName: 'linyu', admin: false, kind: 'MESSAGE', body: input.body, status: 'OPEN', createdAt: stamp, attachments: state.uploads.length ? [attachment] : [] }];
                } else {
                  assert.equal(input.version, state.ticket.version);
                  if (state.conflict) {
                    state.conflict = false;
                    append('STATUS', '', true, 'CLOSED');
                    return fail(route, 409);
                  }
                  append('MESSAGE', input.body, role === 'ADMIN', role === 'ADMIN' ? input.status : 'OPEN', state.uploads.length ? [attachment] : []);
                }
                return ok(route, state.ticket);
              }
              if (request.method() === 'PATCH') {
                const input = request.postDataJSON();
                assert.equal(input.version, state.ticket.version);
                append('STATUS', '', role === 'ADMIN', input.status);
                return ok(route, state.ticket);
              }
              if (path.includes('/attachments/')) return route.fulfill({ body: 'request-id=ui-test', headers: { 'Content-Type': 'application/octet-stream' } });
              if (state.failRead) return fail(route, 500);
              if (path === '/tickets') {
                const filter = url.searchParams.get('status');
                const search = url.searchParams.get('search');
                const items = (!filter || filter === state.ticket.status) && (!search || state.ticket.title.includes(search)) ? [state.ticket] : [];
                return ok(route, { items, total: items.length, page: 1, pageSize: 20, counts: { [state.ticket.status]: 1 } });
              }
              const older = Number(url.searchParams.get('before')) > 0;
              return ok(route, { ticket: state.ticket, messages: older ? [{ ...state.messages[0], id: -1, body: '更早的沟通记录' }] : state.messages, hasMore: !older && state.hasMore, nextBefore: older ? 0 : 1 });
            }
            if (request.method() !== 'GET') throw new Error('Unexpected real mutation: ' + path);
            return route.continue();
          } catch (error) { errors.push(error.message); return route.abort(); }
        });
        async function shot(name) {
          await page.addStyleTag({ content: '*, *::before, *::after { animation: none !important; transition: none !important; }' });
          await page.mouse.move(0, 0);
          assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true, name + ' overflow');
          await page.screenshot({ path: output + '/' + chrome + '-' + role.toLowerCase() + '-' + name + '.png', fullPage: true, style: '.el-message, .el-popper[role=tooltip] { visibility: hidden !important; }' });
        }
        await page.goto(base + '/#/tickets');
        await page.locator('.ticket-row').waitFor();
        await shot('list');
        await page.getByRole('button', { name: '新建工单', exact: true }).click();
        await page.waitForURL('**/#/tickets/new');
        await page.getByLabel('工单标题', { exact: true }).fill('模型调用失败，补充请求日志');
        await page.getByLabel('问题描述', { exact: true }).fill('请求 ID: req_ui_test\n<svg onload=alert(1)> 应按文本显示');
        await page.locator('input[type=file]').setInputFiles({ name: 'request-log.txt', mimeType: 'text/plain', buffer: Buffer.from('request-id=ui-test') });
        await shot('create');
        await page.setViewportSize({ width: 390, height: 844 });
        await shot('create-390');
        await page.setViewportSize({ width: 1440, height: 1000 });
        await page.getByRole('button', { name: '返回工单列表', exact: true }).click();
        await page.getByRole('button', { name: '继续编辑', exact: true }).click();
        assert.ok(page.url().endsWith('/tickets/new'));
        state.failCreate = true;
        await page.getByRole('button', { name: '提交工单', exact: true }).click();
        await page.getByText('测试请求失败，草稿应保留', { exact: true }).last().waitFor();
        assert.equal(await page.getByLabel('工单标题', { exact: true }).inputValue(), '模型调用失败，补充请求日志');
        assert.equal(await page.locator('.composer__file').count(), 1);
        state.failCreate = false;
        await page.getByRole('button', { name: '提交工单', exact: true }).click();
        await page.waitForURL('**/#/tickets/' + ticketId);
        await page.locator('.ticket-detail__aside').waitFor();
        assert.deepEqual(state.uploads, [{ name: 'request-log.txt', content: 'request-id=ui-test' }]);
        assert.equal(await page.locator('.ticket-message__body svg').count(), 0);
        append('MESSAGE', '已收到日志，请补充请求发生的时间。', true, 'WAITING_USER');
        await page.getByRole('button', { name: '刷新工单', exact: true }).click();
        await page.getByText('已收到日志，请补充请求发生的时间。', { exact: true }).waitFor();
        await shot('detail');
        for (const width of [1024, 390]) {
          await page.setViewportSize({ width, height: width === 390 ? 844 : 900 });
          await shot('detail-' + width);
          assert.ok(await page.locator('.layout__sider').evaluate(el => innerWidth > 720 || el.clientHeight < 250), 'mobile navigation must not fill the viewport');
        }
        await page.setViewportSize({ width: 1440, height: 1000 });
        await page.getByRole('button', { name: role === 'ADMIN' ? '回复工单' : '补充回复', exact: true }).click();
        assert.equal(await page.getByLabel('回复内容', { exact: true }).evaluate(el => el === document.activeElement), true);
        await page.getByLabel('回复内容', { exact: true }).fill('补充信息：请求发生在 09:30。');
        await page.locator('input[type=file]').setInputFiles({ name: 'request-log.txt', mimeType: 'text/plain', buffer: Buffer.from('reply attachment') });
        state.failRead = true;
        await page.getByRole('button', { name: '刷新工单', exact: true }).click();
        await page.locator('.ticket-refresh-error').waitFor();
        assert.equal(await page.getByLabel('回复内容', { exact: true }).inputValue(), '补充信息：请求发生在 09:30。');
        state.failRead = false;
        state.conflict = true;
        await page.getByRole('button', { name: '发送回复', exact: true }).click();
        await page.getByRole('button', { name: '重新打开工单', exact: true }).waitFor();
        await page.getByRole('button', { name: '重新打开工单', exact: true }).click();
        await page.getByLabel('回复内容', { exact: true }).waitFor();
        assert.equal(await page.getByLabel('回复内容', { exact: true }).inputValue(), '补充信息：请求发生在 09:30。');
        assert.equal(await page.locator('.composer__file').count(), 1);
        await page.getByRole('button', { name: '发送回复', exact: true }).click();
        await page.getByText('回复已发送', { exact: true }).waitFor();
        assert.equal(await page.getByLabel('回复内容', { exact: true }).inputValue(), '');
        assert.equal(state.ticket.status, role === 'ADMIN' ? 'WAITING_USER' : 'OPEN');
        state.hasMore = true;
        await page.getByRole('button', { name: '刷新工单', exact: true }).click();
        await page.getByRole('button', { name: '查看更早的记录', exact: true }).click();
        await page.getByText('更早的沟通记录', { exact: true }).waitFor();
        if (role === 'USER') {
          await page.getByRole('button', { name: '关闭工单', exact: true }).click();
          await page.getByRole('dialog').getByRole('button', { name: '关闭工单', exact: true }).click();
          await page.getByRole('button', { name: '重新打开工单', exact: true }).waitFor();
          assert.equal(state.ticket.status, 'CLOSED');
          await page.getByRole('button', { name: '重新打开工单', exact: true }).click();
          await page.getByLabel('回复内容', { exact: true }).waitFor();
        } else {
          await page.locator('.ticket-detail__state .el-select').click();
          await page.getByRole('option', { name: '已解决', exact: true }).click();
          await page.getByRole('button', { name: '重新打开工单', exact: true }).waitFor();
          assert.equal(state.ticket.status, 'RESOLVED');
          await page.getByRole('button', { name: '重新打开工单', exact: true }).click();
          await page.getByLabel('回复内容', { exact: true }).waitFor();
        }
        const downloadEvent = page.waitForEvent('download');
        await page.getByRole('button', { name: '下载 request-log.txt', exact: true }).first().click();
        assert.equal((await downloadEvent).suggestedFilename(), 'request-log.txt');
        await page.getByRole('button', { name: '返回工单列表', exact: true }).click();
        await page.waitForURL('**/#/tickets');
        await page.locator('.ticket-row').click();
        await page.waitForURL('**/#/tickets/' + ticketId);
        await page.reload();
        await page.locator('.ticket-detail__aside').waitFor();
        await page.getByLabel('回复内容', { exact: true }).fill('未提交的草稿');
        await page.getByRole('button', { name: '返回工单列表', exact: true }).click();
        await page.getByRole('button', { name: '离开', exact: true }).click();
        await page.waitForURL('**/#/tickets');
        await page.getByLabel('搜索工单', { exact: true }).fill('不存在的标题');
        await page.getByLabel('搜索工单', { exact: true }).press('Enter');
        await page.getByText('没有匹配的工单', { exact: true }).waitFor();
        checks.push(chrome + '/' + role + ': list, create, full detail, draft guards, failure recovery, multipart attachments, conflict/reopen, reply, history, download, return, direct-link reload, search');
        await context.close();
      }
    }
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ checks, pageErrors: errors, screenshots: output }, null, 2));
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
