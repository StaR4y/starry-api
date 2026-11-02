// Opt-in smoke test against the disposable local preview, never a production URL.
const { chromium } = require('playwright');
const { mkdirSync, writeFileSync } = require('node:fs');
const assert = require('node:assert/strict');
const os = require('node:os');
const { join } = require('node:path');
const base = 'http://127.0.0.1:5174';
const out = process.env.CODING_PLAN_SCREENSHOTS || join(os.tmpdir(), 'starry-coding-preview', 'screenshots');
if (process.env.CODING_PLAN_PREVIEW_TESTS !== 'true' || !process.env.CODING_PLAN_PREVIEW_PASSWORD) {
  throw new Error('Requires CODING_PLAN_PREVIEW_TESTS=true and disposable preview credentials');
}
async function api(path, token, method = 'GET', body, headers = {}) {
  const response = await fetch(base + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const result = await response.json();
  assert.equal(response.ok, true, `${method} ${path}: ${JSON.stringify(result)}`);
  assert.equal(result.success, true, path);
  return result.data;
}
async function login(username) {
  return api('/auth/login', null, 'POST', {
    username,
    password: process.env.CODING_PLAN_PREVIEW_PASSWORD,
  });
}
async function main() {
  mkdirSync(out, { recursive: true });
  const admin = await login('admin');
  const user = await login('coding-preview');
  assert.equal(user.user.userId, 'coding-preview-user');
  const old = await api('/admin/coding-plans', admin.accessToken);
  for (const plan of old.filter((p) => !p.name.startsWith('Coding '))) {
    await api(`/admin/coding-plans/${plan.planId}`, admin.accessToken, 'PATCH', {
      enabled: false,
      sortOrder: 100,
    });
  }
  const plans = [];
  for (const [index, terms] of [
    {
      name: 'Coding Starter',
      description: '日常编码与轻量项目',
      priceCredits: '20',
      quotaCredits: '120',
      allowedProviders: ['OpenAI'],
    },
    {
      name: 'Coding Pro',
      description: '多模型开发与长期项目',
      priceCredits: '50',
      quotaCredits: '400',
      allowedProviders: ['OpenAI', 'Anthropic'],
    },
    {
      name: 'Coding Max',
      description: '高频开发与完整模型访问',
      priceCredits: '100',
      quotaCredits: '1000',
      allowedProviders: [],
    },
  ].entries()) {
    const existing = old.find((p) => p.name === terms.name);
    plans.push(
      existing ||
        (await api('/admin/coding-plans', admin.accessToken, 'POST', {
          ...terms,
          durationDays: 30,
          sortOrder: index,
        })),
    );
  }
  await api(
    `/payment/coding-plans/${plans[1].planId}/purchase`,
    user.accessToken,
    'POST',
    { version: plans[1].version },
    { 'Idempotency-Key': 'preview-initial-plan' },
  );
  const anonymous = await fetch(base + '/payment/coding-plan-subscriptions/summary');
  assert.equal(anonymous.status, 401);
  const forbidden = await fetch(base + '/admin/coding-plans', {
    headers: { Authorization: `Bearer ${user.accessToken}` },
  });
  assert.equal(forbidden.status, 403);
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const errors = [];
  const report = { checks: [], screenshots: [], errors };
  try {
    async function context(session, width, height) {
      const ctx = await browser.newContext({
        viewport: { width, height },
        deviceScaleFactor: 1,
        reducedMotion: 'reduce',
      });
      await ctx.addInitScript(({ accessToken, user }) => {
        localStorage.setItem('kp.adminToken', accessToken);
        localStorage.setItem('kp.adminUser', JSON.stringify(user));
      }, session);
      const page = await ctx.newPage();
      page.on('pageerror', (error) => errors.push(error.message));
      return { ctx, page };
    }
    async function shot(page, name) {
      await page.waitForLoadState('networkidle');
      await page.screenshot({ path: `${out}/${name}.png`, fullPage: true, animations: 'disabled' });
      report.screenshots.push(name);
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth + 1,
      );
      assert.equal(overflow, false, `${name} page overflow`);
    }
    const desktop = await context(user, 1440, 1000);
    const page = desktop.page;
    await page.goto(base + '/#/coding-plans');
    await page.getByRole('heading', { name: '选择套餐' }).waitFor();
    await page.locator('.product-item').first().waitFor();
    await shot(page, 'subscriptions-desktop');
    await page.getByRole('button', { name: '额度明细', exact: true }).first().click();
    await page.locator('.ledger-row').first().waitFor();
    assert.ok((await page.locator('.ledger-row').count()) > 0);
    report.checks.push('Initial ledger drawer loads real database entries');
    await page.getByRole('button', { name: 'Close this dialog' }).click();
    await page
      .locator('.product-item')
      .filter({ hasText: 'Coding Max' })
      .getByRole('button', { name: '开通套餐' })
      .click();
    await page.getByRole('dialog', { name: '开通 Coding Plan' }).waitFor();
    await shot(page, 'checkout-desktop');
    await page.getByRole('button', { name: '取消', exact: true }).click();
    await page.goto(base + '/#/api-keys');
    await page.getByRole('button', { name: '新建 API Key', exact: true }).waitFor();
    for (const [source, label] of [
      ['BALANCE', '余额计费'],
      ['CODING_PLAN', 'Coding Plan'],
    ]) {
      await page.getByRole('button', { name: '新建 API Key', exact: true }).click();
      const dialog = page.getByRole('dialog').filter({ has: page.locator('.api-key-form') });
      await dialog
        .locator('.el-form-item')
        .filter({ hasText: '名称' })
        .getByRole('textbox')
        .fill(`preview-${source}-${Date.now()}`);
      await dialog.locator('.el-radio-button').filter({ hasText: label }).click();
      assert.equal(await dialog.getByRole('radio', { name: label, exact: true }).isChecked(), true);
      await shot(page, `key-create-${source.toLowerCase()}`);
      const response = page.waitForResponse(
        (r) => r.url().endsWith('/admin/api-keys') && r.request().method() === 'POST',
      );
      await dialog.getByRole('button', { name: '创建密钥', exact: true }).click();
      const created = (await (await response).json()).data;
      assert.equal(created.billingSource, source);
      await api(`/admin/api-keys/${created.keyId}`, user.accessToken, 'PATCH', { creditsLimit: 10 });
      const uncapped = await api(`/admin/api-keys/${created.keyId}`, user.accessToken, 'PATCH', { clearCreditsLimit: true });
      assert.ok(uncapped.creditsLimit == null);
      assert.equal(uncapped.billingSource, source);
      await page
        .getByRole('dialog')
        .filter({ hasText: '新 API Key' })
        .waitFor({ state: 'visible', timeout: 3000 })
        .catch(() => {});
      await page.keyboard.press('Escape');
      report.checks.push(`Creates and persists ${source} key from UI`);
    }
    await shot(page, 'keys-desktop');
    const mobile = await context(user, 390, 844);
    await mobile.page.goto(base + '/#/coding-plans');
    await mobile.page.locator('.product-item').first().waitFor();
    await shot(mobile.page, 'subscriptions-mobile');
    await mobile.page.goto(base + '/#/api-keys');
    await mobile.page.getByRole('button', { name: '新建 API Key', exact: true }).click();
    await mobile.page.locator('.el-radio-button').filter({ hasText: 'Coding Plan' }).click();
    await shot(mobile.page, 'key-create-mobile');
    const management = await context(admin, 1440, 1000);
    await management.page.goto(base + '/#/settings/coding-plans');
    await management.page.getByRole('button', { name: '新建套餐', exact: true }).first().waitFor();
    await management.page.locator('.table-product').first().waitFor();
    await shot(management.page, 'management-desktop');
    await management.page.getByRole('button', { name: '新建套餐', exact: true }).first().click();
    await management.page.getByRole('button', { name: '发布套餐', exact: true }).waitFor();
    await shot(management.page, 'publish-drawer');
    await management.page.getByRole('button', { name: '取消', exact: true }).click();
    await management.page.getByRole('tab', { name: '用户订阅' }).click();
    await management.page.getByPlaceholder('搜索用户或套餐').fill('coding-preview');
    await management.page.getByRole('button', { name: '搜索订阅', exact: true }).click();
    await management.page.locator('.plan-admin-table:visible').waitFor();
    await shot(management.page, 'management-subscriptions');
    const mobileAdmin = await context(admin, 390, 844);
    await mobileAdmin.page.goto(base + '/#/settings/coding-plans');
    await mobileAdmin.page.locator('.table-product').first().waitFor();
    await shot(mobileAdmin.page, 'management-mobile');
    await mobileAdmin.page.getByRole('button', { name: '新建套餐', exact: true }).first().click();
    await mobileAdmin.page.getByRole('button', { name: '发布套餐', exact: true }).waitFor();
    await shot(mobileAdmin.page, 'publish-mobile');
    report.checks.push('Clearing a key limit preserves its selected billing source');
    report.checks.push('Real authentication: anonymous 401; user cannot access admin (403)');
    assert.deepEqual(errors, [], 'Browser runtime errors');
    writeFileSync(`${out}/report.json`, JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report, null, 2));
  } finally {
    await browser.close();
  }
}
main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
