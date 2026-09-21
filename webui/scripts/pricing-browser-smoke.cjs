// Runs against the disposable preview only; restores its original rules on exit.
const { chromium } = require('playwright');
const { readFileSync, mkdirSync, writeFileSync } = require('node:fs');
const assert = require('node:assert/strict');
const base = 'http://127.0.0.1:5175';
const output = '/private/tmp/starry-plugin-preview';
if (process.env.PRICING_PREVIEW_TESTS !== 'true') throw new Error('Requires PRICING_PREVIEW_TESTS=true');
const password = 'pricing-preview-only-password-2026';
const report = { checks: [], screenshots: [], errors: [] };
async function api(route, token, method = 'GET', body, expected = 200) {
  const response = await fetch(base + route, { method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body) });
  const payload = await response.json();
  assert.equal(response.status, expected, method + ' ' + route + ': ' + JSON.stringify(payload.error || {}));
  return payload.data;
}
async function main() {
  mkdirSync(output + '/pricing', { recursive: true });
  let session;
  try { session = await api('/auth/login', null, 'POST', { username: 'admin', password }); }
  catch {
    const original = [...readFileSync(output + '/backend.log', 'utf8').matchAll(/Password: ([a-f0-9]{36})/g)].at(-1)?.[1];
    assert.ok(original, 'Disposable bootstrap credential available');
    const first = await api('/auth/login', null, 'POST', { username: 'admin', password: original });
    await api('/auth/password', first.accessToken, 'POST', { oldPassword: original, newPassword: password });
    session = await api('/auth/login', null, 'POST', { username: 'admin', password });
  }
  const token = session.accessToken;
  const originalRules = (await api('/admin/model-pricing', token)).rules;
  const seed = [
    { ruleId: 'all', modelPattern: '*', priceMultiplier: 1, enabled: true, priority: 100 },
    { ruleId: 'exact', modelPattern: 'gpt-5.5', priceMultiplier: 0.1, enabled: true, priority: 100 },
    { ruleId: 'legacy', modelPattern: 'review-legacy', totalMultiplier: 0.00000123456789, enabled: true, priority: 100 },
    { ruleId: 'itemized', modelPattern: 'review-itemized', inputMultiplier: 0.00000345678901,
      outputMultiplier: 0.00001098765432, cacheReadMultiplier: 0.000000345678901, cacheWriteMultiplier: 0.00000456789012,
      enabled: true, priority: 100 },
  ];
  let browser;
  try {
    await api('/admin/model-pricing', token, 'POST', { rules: seed });
    await api('/admin/model-pricing/preview', null, 'POST', { model: 'gpt-5.5', rules: seed }, 401);
    const result = await api('/admin/model-pricing/preview', token, 'POST', { model: 'starry/gpt-5.5', rules: seed });
    assert.deepEqual(result.matchedRuleIds, ['exact', 'all']);
    assert.equal(result.pricing.inputPricePerMillionTokens, 0.5);
    assert.equal(result.pricing.totalPricePerMillionTokens, 1.75);
    const before = await api('/admin/model-pricing', token);
    await api('/admin/model-pricing/preview', token, 'POST', { model: 'gpt-5.5', rules: [] });
    assert.deepEqual((await api('/admin/model-pricing', token)).rules, before.rules);
    await api('/admin/model-pricing', token, 'POST', { rules: [seed[1], { ...seed[1], ruleId: 'duplicate', modelPattern: 'starry/gpt-5.5' }] }, 400);
    report.checks.push('preview requires authentication and does not write', 'exact beats wildcard with normalized prefix', 'duplicate canonical patterns rejected');
    browser = await chromium.launch({ headless: true, channel: 'chrome' });
    const context = await browser.newContext({ viewport: { width: 1440, height: 1050 }, reducedMotion: 'reduce' });
    await context.addInitScript(session => {
      localStorage.setItem('kp.adminToken', session.accessToken);
      localStorage.setItem('kp.adminUser', JSON.stringify(session.user));
    }, session);
    const page = await context.newPage();
    page.on('pageerror', error => report.errors.push(error.message));
    await page.goto(base + '/#/settings/models');
    await page.getByText('价格倍率', { exact: true }).click();
    const panel = page.locator('.pricing-panel');
    await panel.getByText('2 条旧固定价', { exact: true }).waitFor();
    async function shot(name) {
      await page.mouse.move(0, 0);
      await page.locator('.el-message').last().waitFor({ state: 'hidden', timeout: 8000 }).catch(() => {});
      await page.evaluate(async () => {
        await Promise.all(document.getAnimations().filter(a => a.effect?.getComputedTiming().iterations !== Infinity)
          .map(a => a.finished.catch(() => {})));
      });
      const dialogVisible = await page.getByRole('dialog').isVisible();
      if (!dialogVisible) await page.evaluate(() => window.scrollTo(0, 0));
      await page.screenshot({ path: output + '/pricing/' + name + '.png', fullPage: !dialogVisible });
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), 'No overflow: ' + name);
      report.screenshots.push(name);
    }
    async function save() {
      const response = page.waitForResponse(r => r.url().endsWith('/admin/model-pricing') && r.request().method() === 'POST');
      await panel.getByRole('button', { name: '保存规则', exact: true }).click();
      assert.equal((await response).status(), 200);
      await panel.getByRole('button', { name: '保存规则', exact: true }).waitFor();
    }
    async function preview() {
      const response = page.waitForResponse(r => r.url().endsWith('/admin/model-pricing/preview'));
      await panel.getByRole('button', { name: '预览价格', exact: true }).click();
      assert.equal((await response).status(), 200);
      await panel.locator('.price-table').waitFor();
    }
    await preview();
    assert.match(await panel.locator('.conflict-note').innerText(), /\*/);
    assert.equal(await panel.locator('.price-line').nth(1).locator('strong').innerText(), '1.75');
    await shot('desktop-rules-preview');
    const providerRate = panel.getByRole('spinbutton', { name: 'OpenAI 厂商倍率', exact: true });
    const familyRate = panel.getByRole('spinbutton', { name: 'OpenAI GPT-5 系列倍率', exact: true });
    await providerRate.fill('0.2');
    await providerRate.blur();
    assert.equal(Number(await familyRate.inputValue()), 0.2, 'Unset family inherits provider draft');
    await familyRate.fill('0.3');
    await familyRate.blur();
    await save();
    let grouped = (await api('/admin/model-pricing', token)).rules;
    assert.equal(grouped.find(r => r.scope === 'provider' && r.provider === 'OpenAI').priceMultiplier, 0.2);
    assert.equal(grouped.find(r => r.scope === 'family' && r.family === 'GPT-5').priceMultiplier, 0.3);
    for (const [model, multiplier] of [['gpt-4o', 0.2], ['o3', 0.2], ['starry/gpt-5.4', 0.3], ['gpt-5.5', 0.1]]) {
      const actual = await api('/admin/model-pricing/preview', token, 'POST', { model, rules: grouped });
      assert.equal(actual.multiplier, multiplier, model);
    }
    const selection = panel.locator('.preview-controls .el-select input');
    await selection.fill('gpt-5.4');
    await page.getByRole('option', { name: 'gpt-5.4', exact: true }).click();
    await preview();
    assert.match(await panel.locator('.preview-match').innerText(), /OpenAI · GPT-5/);
    await shot('desktop-provider-family');
    await panel.getByRole('button', { name: 'GPT-5 恢复厂商倍率', exact: true }).click();
    await save();
    assert.equal(Number(await familyRate.inputValue()), 0.2);
    grouped = (await api('/admin/model-pricing', token)).rules;
    assert.ok(!grouped.some(r => r.scope === 'family' && r.family === 'GPT-5'));
    await familyRate.fill('0.3');
    await familyRate.blur();
    await save();
    report.checks.push('provider and family multipliers save as typed scopes', 'reasoning models inherit vendor; exact exceptions remain', 'family reset restores provider inheritance');
    await panel.getByRole('button', { name: '新增规则', exact: true }).click();
    let dialog = page.getByRole('dialog', { name: '新增价格规则' });
    await dialog.locator('.el-select input').fill('gpt5');
    await dialog.locator('.el-select input').press('Enter');
    await dialog.getByText('未找到精确模型 ID：gpt5', { exact: true }).waitFor();
    report.checks.push('bare shorthand gets an explicit exact-ID warning');
    await dialog.locator('.el-select input').fill('gpt-5.4');
    await page.getByRole('option', { name: 'gpt-5.4', exact: true }).click();
    await dialog.getByRole('spinbutton').first().fill('0.25');
    await dialog.getByRole('button', { name: '确定', exact: true }).click();
    assert.equal(await panel.locator('.price-table').count(), 0, 'Editing invalidates preview');
    await save();
    const saved = (await api('/admin/model-pricing', token)).rules;
    assert.equal(saved.find(r => r.ruleId === 'legacy').totalMultiplier, seed[2].totalMultiplier);
    for (const field of ['inputMultiplier', 'outputMultiplier', 'cacheReadMultiplier', 'cacheWriteMultiplier']) {
      assert.equal(saved.find(r => r.ruleId === 'itemized')[field], seed[3][field]);
    }
    report.checks.push('UI creates and saves a multiplier without changing legacy total/itemized rates');
    await panel.getByRole('button', { name: '编辑 review-legacy', exact: true }).click();
    dialog = page.getByRole('dialog', { name: '编辑价格规则' });
    await dialog.getByRole('spinbutton').fill('50');
    await dialog.getByRole('button', { name: '确定', exact: true }).click();
    await save();
    assert.equal((await api('/admin/model-pricing', token)).rules.find(r => r.ruleId === 'legacy').totalMultiplier, seed[2].totalMultiplier);
    await panel.getByRole('button', { name: '编辑 review-legacy', exact: true }).click();
    await dialog.getByRole('button', { name: '改为倍率', exact: true }).click();
    await page.locator('.el-message-box').getByRole('button', { name: '改为倍率', exact: true }).click();
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    assert.equal(await panel.getByRole('button', { name: '保存规则', exact: true }).isDisabled(), true);
    report.checks.push('legacy priority edits preserve exact rates', 'cancelling conversion preserves original rule');
    await panel.getByRole('button', { name: '编辑 review-legacy', exact: true }).click();
    await dialog.getByRole('button', { name: '改为倍率', exact: true }).click();
    await page.locator('.el-message-box').getByRole('button', { name: '改为倍率', exact: true }).click();
    await dialog.getByRole('spinbutton').first().fill('0.5');
    await dialog.getByRole('button', { name: '确定', exact: true }).click();
    await save();
    const converted = (await api('/admin/model-pricing', token)).rules.find(r => r.ruleId === 'legacy');
    assert.equal(converted.priceMultiplier, 0.5);
    assert.ok(converted.totalMultiplier == null);
    report.checks.push('explicit legacy conversion replaces fixed rates only after save');
    const modelSelect = panel.locator('.preview-controls .el-select input');
    await modelSelect.fill('review-legacy');
    await modelSelect.press('Enter');
    await preview();
    await panel.getByText('未找到可核实的官方价格，暂不可计费', { exact: true }).waitFor();
    report.checks.push('unknown price shown as unavailable');
    await modelSelect.fill('gpt-5.5');
    await page.getByRole('option', { name: 'gpt-5.5', exact: true }).click();
    await preview();
    for (const width of [390, 768]) {
      await page.setViewportSize({ width, height: 900 });
      await shot(width + '-rules-preview');
      await panel.getByRole('button', { name: '编辑 gpt-5.5', exact: true }).click();
      await page.getByRole('dialog', { name: '编辑价格规则' }).waitFor();
      await shot(width + '-edit-dialog');
      await page.getByRole('dialog', { name: '编辑价格规则' }).getByRole('button', { name: '取消', exact: true }).click();
      await page.getByRole('dialog', { name: '编辑价格规则' }).waitFor({ state: 'hidden' });
    }
    await page.setViewportSize({ width: 1440, height: 1050 });
    await panel.locator('.el-switch').filter({ has: page.getByRole('switch', { name: '启用 gpt-5.5', exact: true }) }).click();
    await panel.getByText('官方价格数据源', { exact: true }).click();
    await page.route('**/admin/model-pricing/refresh', route => route.fulfill({
      status: 503, json: { success: false, error: { code: 'MODEL_PRICE_REFRESH_FAILED', message: 'Preview injected sync failure' } },
    }));
    await panel.getByRole('button', { name: '立即同步', exact: true }).click();
    await page.getByText('同步失败，已保留当前规则草稿和上次成功价格', { exact: true }).waitFor();
    assert.equal(await panel.getByRole('switch', { name: '启用 gpt-5.5', exact: true }).getAttribute('aria-checked'), 'false');
    assert.equal(await panel.getByRole('button', { name: '保存规则', exact: true }).isEnabled(), true);
    report.checks.push('failed source sync preserves unsaved rules', 'desktop/mobile layouts have no page overflow');
    await page.goto(base + '/#/models');
    await page.getByPlaceholder('搜索名称、ID、系列或 endpoint').fill('gpt-5.5');
    const modelCard = page.locator('.model-card').filter({ has: page.getByRole('button', { name: 'gpt-5.5', exact: true }) });
    await modelCard.locator('.meta-chip--price').waitFor();
    assert.equal((await modelCard.locator('.meta-chip--price').innerText()).trim(), '1.75 / MTok');
    assert.match(await modelCard.locator('.meta-chip--price').getAttribute('title'), /50%/);
    report.checks.push('model catalog defaults to total reference price with itemized tooltip');
    assert.deepEqual(report.errors, []);
    console.log(JSON.stringify(report));
  } finally {
    if (browser) await browser.close();
    await api('/admin/model-pricing', token, 'POST', { rules: originalRules });
    writeFileSync(output + '/pricing/report.json', JSON.stringify(report, null, 2));
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
