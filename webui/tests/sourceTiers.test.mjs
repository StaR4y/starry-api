import assert from 'node:assert/strict';
import path from 'node:path';
import { test } from 'node:test';
import { build } from 'esbuild';
import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'https://test.example' });
globalThis.window = dom.window;
globalThis.document = dom.window.document;
globalThis.localStorage = dom.window.localStorage;
Object.defineProperty(globalThis, 'navigator', { value: dom.window.navigator, configurable: true });
// Bundle the draft model in memory (it imports the i18n module through the `@` alias).
const compiled = await build({
  stdin: {
    contents: `export * from './src/views/settings/components/source-tiers/sourceTiersMeta'; export * from './src/views/settings/components/source-tiers/tierStatusMeta'; export * from './src/views/settings/billingReviewTiers'; export * from './src/views/logs/logSource'; export * from './src/views/api-keys/tierPreference'; export * from './src/views/models/modelTiers'; export { createSetupScriptBlock, createCcSwitchLinks } from './src/platforms/clientSetup'; export { tierReference, TIER_HEADER } from './src/platforms/setupShared'; export { setLocale } from './src/i18n';`,
    resolveDir: process.cwd(),
    loader: 'ts',
  },
  bundle: true,
  write: false,
  format: 'esm',
  platform: 'node',
  alias: { '@': path.resolve('src') },
});
const meta = await import(`data:text/javascript;base64,${Buffer.from(compiled.outputFiles[0].text).toString('base64')}`);
const {
  draftOf, replaceRequest, draftSignature, tierProblems, validateDraft, unsupportedMessage, enableNote, unifyNote, tierPresets, placedCounts,
  pinChoices, exclusiveRelayIds, DEFAULT_REFUSAL_CAP,
  layerSegments, layerTotal, degradedPercent, degradedTone, notServingReason, placementsByAccount, tierFilterOptions,
  matchesTierFilter, placedLabel, placedHint, placementVendorCount, batchPlan, UNPIN, NO_TIER_FILTER, MAX_BATCH_MEMBERS,
  vendorOfProvider, previewTiers, reviewTier, reviewTierLabel, suggestedCredits, perMillion, reservedMinusEstimate,
  hasSourceTiers, sourceTierLabel, priceFactorLabel,
  AUTO_PREFERENCE, preferenceValue, preferenceChoices, priceText,
  tierSuffix, modelTierChips, createSetupScriptBlock, createCcSwitchLinks, tierReference, TIER_HEADER,
} = meta;
meta.setLocale('zh-CN');

function tier(tierId, level, extra = {}) {
  return {
    tierId, level, name: tierId.toUpperCase(), priceFactor: 1, memberCategories: [],
    userSelectable: true, enabled: true, description: null, ...extra,
  };
}

function nativeRow(accountId, tierId) {
  return {
    accountId, tierId, protocol: null, exclusive: null, vision: null, jsonSchema: null, priorityTier: null,
    cacheControlPassthrough: null, upstreamStream: null, reasoningMode: null, maxDurationSeconds: null,
    usageCacheReadInPrompt: null, usageCacheWriteInPrompt: null, models: null,
  };
}

const relay = {
  accountId: 'relay-1', tierId: 'official', protocol: 'OPENAI_CHAT', exclusive: true, vision: false,
  jsonSchema: false, priorityTier: false, cacheControlPassthrough: false, upstreamStream: true,
  reasoningMode: 'NONE', maxDurationSeconds: 540, usageCacheReadInPrompt: true, usageCacheWriteInPrompt: false,
  models: [{
    modelId: 'claude-sonnet-4.5', upstreamModel: 'relay-sonnet', maxContextTokens: 200000, imageTokenCap: 8192,
    enabled: true, verifiedAt: '2026-10-20T08:00:00Z', verified: true,
  }],
};

function vendor(extra = {}) {
  return {
    vendor: 'anthropic', label: 'Anthropic', configured: true, version: 7, enabled: false, unifyEndpoints: false,
    publicListing: true, stickySessions: false, refusalCap: 24, crossTierReservedSlot: false,
    tiers: [
      tier('kiro', 5, { name: '  Kiro  ', priceFactor: 0.6, memberCategories: ['KIRO', 'CLAUDE_OAUTH'], userSelectable: false }),
      tier('official', 2, { memberCategories: ['OFFICIAL_API_KEY'], description: 'first' }),
    ],
    members: [nativeRow('acc-1', 'kiro'), nativeRow('acc-2', 'official'), relay],
    gates: [{ sourceProtocol: 'OPENAI_CHAT', entryEndpoint: 'MESSAGES', state: 'OFF', allowKeyIds: [] }],
    warnings: [],
    ...extra,
  };
}

test('the draft is ordered by level and the request renumbers the levels from 1', () => {
  const draft = draftOf(vendor());
  assert.deepEqual(draft.tiers.map((row) => row.tierId), ['official', 'kiro']);
  const request = replaceRequest(7, draft);
  assert.equal(request.expectedVersion, 7);
  assert.deepEqual(request.tiers.map((row) => [row.tierId, row.level]), [['official', 1], ['kiro', 2]]);
  assert.equal(request.tiers[1].name, 'Kiro');
  assert.deepEqual(request.tiers[1].memberCategories, ['CLAUDE_OAUTH', 'KIRO']);
  assert.equal(request.tiers[1].userSelectable, false);
  assert.equal(request.tiers[0].description, 'first');
  assert.deepEqual(
    [request.enabled, request.publicListing, request.stickySessions, request.refusalCap, request.crossTierReservedSlot],
    [false, true, false, 24, false],
  );
});

test('a whole-vendor replace keeps the relay members and the gates the page does not edit', () => {
  const request = replaceRequest(7, draftOf(vendor()));
  assert.deepEqual(request.members.map((member) => member.accountId), ['acc-1', 'acc-2', 'relay-1']);
  assert.deepEqual(request.members[2], relay);
  assert.deepEqual(request.members[0], nativeRow('acc-1', 'kiro'));
  assert.deepEqual(request.gates, vendor().gates);
});

test('the shape the server really sends, with null fields left out, reads and writes back the same', () => {
  // application.yml sets default-property-inclusion: non_null
  const stripNulls = (value) => JSON.parse(JSON.stringify(value, (key, item) => (item === null ? undefined : item)));
  const sent = stripNulls(vendor({
    tiers: [tier('official', 1), tier('kiro', 2, { description: null })],
    members: [nativeRow('acc-1', 'kiro'), relay],
  }));
  assert.deepEqual(sent.members[0], { accountId: 'acc-1', tierId: 'kiro' });
  assert.equal('description' in sent.tiers[1], false);
  const draft = draftOf(sent);
  assert.deepEqual(draft.pins.map((pin) => pin.accountId), ['acc-1']);
  assert.equal(draft.relayMembers.length, 1);
  assert.equal(draft.tiers[1].description, null);
  const request = replaceRequest(sent.version, draft);
  assert.deepEqual(request.members[0], nativeRow('acc-1', 'kiro'));
  assert.deepEqual(request.members[1], relay);
  assert.equal(request.tiers[1].description, null);
  assert.equal(draftSignature(draftOf(sent)), draftSignature(draft));
});

test('a native pin follows its tier through a rename and a reorder, and goes with a removed tier', () => {
  const draft = draftOf(vendor());
  const [official, kiro] = draft.tiers;
  draft.tiers = [{ ...kiro, tierId: 'kiro-2' }, official];
  const renamed = replaceRequest(7, draft);
  assert.deepEqual(
    renamed.members.filter((member) => !member.protocol).map((member) => [member.accountId, member.tierId]),
    [['acc-1', 'kiro-2'], ['acc-2', 'official']],
  );
  draft.tiers = [official];
  assert.deepEqual(
    replaceRequest(7, draft).members.filter((member) => !member.protocol).map((member) => member.accountId),
    ['acc-2'],
  );
});

test('the signature ignores row keys and changes with any edit', () => {
  const base = draftSignature(draftOf(vendor()));
  assert.equal(draftSignature(draftOf(vendor())), base);
  const edited = draftOf(vendor());
  edited.tiers[0] = { ...edited.tiers[0], priceFactor: 1.5 };
  assert.notEqual(draftSignature(edited), base);
  const reordered = draftOf(vendor());
  reordered.tiers = meta.moveItem(reordered.tiers, 0, 1);
  assert.notEqual(draftSignature(reordered), base);
  const pinned = draftOf(vendor());
  pinned.pins = [];
  assert.notEqual(draftSignature(pinned), base);
});

test('a vendor that was never configured gives the defaults and an empty draft', () => {
  const draft = draftOf(vendor({
    configured: false, version: 0, enabled: false, publicListing: false, stickySessions: true, refusalCap: 16,
    crossTierReservedSlot: true, tiers: [], members: [], gates: [],
  }));
  assert.deepEqual(draft.tiers, []);
  const request = replaceRequest(0, draft);
  assert.deepEqual(
    [request.expectedVersion, request.stickySessions, request.refusalCap, request.crossTierReservedSlot, request.members, request.gates],
    [0, true, 16, true, [], []],
  );
});

function problemsOf(draft) {
  return Object.values(tierProblems(draft.tiers));
}

test('the tier rules match the server: identifiers, reserved words, names, factors', () => {
  const base = draftOf(vendor());
  assert.equal(validateDraft(base), null);
  const withId = (tierId) => ({ ...base, tiers: [{ ...base.tiers[0], tierId }, base.tiers[1]] });
  for (const bad of ['', 'Upper', '-lead', 'a b', 'x'.repeat(33), 'under_score']) {
    assert.ok(problemsOf(withId(bad))[0]?.id, `identifier ${JSON.stringify(bad)} is refused`);
  }
  for (const reserved of ['auto', 'l2', '12', '1']) {
    assert.ok(problemsOf(withId(reserved))[0]?.id, `identifier ${reserved} is reserved`);
  }
  for (const good of ['relay', 'a', '0x', 'kiro-2', 'l2x', 'x'.repeat(32)]) {
    assert.equal(problemsOf(withId(good)).length, 0, `identifier ${good} is fine`);
  }
  assert.ok(problemsOf(withId('kiro'))[0]?.id, 'a duplicate identifier is refused');

  const withName = (name) => ({ ...base, tiers: [{ ...base.tiers[0], name }, base.tiers[1]] });
  assert.ok(problemsOf(withName('   '))[0]?.name);
  assert.ok(problemsOf(withName('a'.repeat(33)))[0]?.name);
  assert.equal(problemsOf(withName('a'.repeat(32))).length, 0);
  assert.equal(problemsOf(withName('号'.repeat(32))).length, 0);
  assert.equal(problemsOf(withName('😀'.repeat(32))).length, 0, 'a name counts code points, as the server does');
  assert.ok(problemsOf(withName('a\u0007b'))[0]?.name);
  assert.ok(problemsOf(withName('Kiro'))[0]?.name, 'a name that another tier has (after trimming) is refused');

  const withFactor = (priceFactor) => ({ ...base, tiers: [{ ...base.tiers[0], priceFactor }, base.tiers[1]] });
  for (const bad of [0, -1, 1000.5, Number.NaN, null, 0.0000004]) {
    assert.ok(problemsOf(withFactor(bad))[0]?.factor, `factor ${bad} is refused`);
  }
  for (const good of [0.000001, 0.6, 1, 1000]) {
    assert.equal(problemsOf(withFactor(good)).length, 0, `factor ${good} is fine`);
  }
});

test('validateDraft reports the first problem of the whole draft', () => {
  const base = draftOf(vendor());
  const firstTier = (change) => ({ ...base, tiers: [{ ...base.tiers[0], ...change }, base.tiers[1]] });
  assert.match(validateDraft(firstTier({ name: '' })), /^第 1 级/);
  assert.ok(validateDraft({ ...base, tiers: [...base.tiers, ...base.tiers, ...base.tiers, ...base.tiers, ...base.tiers] }));
  // The same category in two tiers
  assert.ok(validateDraft({ ...base, tiers: [{ ...base.tiers[0], memberCategories: ['KIRO'] }, base.tiers[1]] }));
  for (const refusalCap of [3, 65, 16.5, Number.NaN]) {
    assert.ok(validateDraft({ ...base, refusalCap }), `refusal cap ${refusalCap} is refused`);
  }
  for (const refusalCap of [4, 16, 64]) {
    assert.equal(validateDraft({ ...base, refusalCap }), null);
  }
  // Enabling needs an enabled tier
  const allOff = { ...base, tiers: base.tiers.map((row) => ({ ...row, enabled: false })) };
  assert.equal(validateDraft(allOff), null);
  assert.ok(validateDraft({ ...allOff, enabled: true }));
  assert.equal(validateDraft({ ...base, enabled: true }), null);
  assert.equal(validateDraft({ ...base, tiers: [], enabled: false }), null);
});

test('a new tier takes the first free number and never repeats an identifier or a name', () => {
  const draft = draftOf(vendor());
  const first = meta.newTier(draft.tiers);
  assert.equal(first.tierId, 'pool-3');
  assert.equal(first.priceFactor, 1);
  assert.deepEqual(first.memberCategories, []);
  assert.equal(validateDraft({ ...draft, tiers: [...draft.tiers, first] }), null);
  const taken = [...draft.tiers, first, { ...first, key: 'x', tierId: 'pool-4', name: '号池 4' }];
  assert.equal(meta.newTier(taken).tierId, 'pool-5');
});

test('the lowest enabled tier is the last enabled one', () => {
  const draft = draftOf(vendor());
  assert.equal(meta.lowestEnabledKey(draft.tiers), draft.tiers[1].key);
  const lastOff = [draft.tiers[0], { ...draft.tiers[1], enabled: false }];
  assert.equal(meta.lowestEnabledKey(lastOff), draft.tiers[0].key);
  assert.equal(meta.lowestEnabledKey(lastOff.map((row) => ({ ...row, enabled: false }))), null);
  assert.equal(meta.lowestEnabledKey([]), null);
});

test('moveItem returns the same list for a move that does nothing', () => {
  const list = ['a', 'b', 'c'];
  assert.deepEqual(meta.moveItem(list, 0, 2), ['b', 'c', 'a']);
  assert.deepEqual(meta.moveItem(list, 2, 0), ['c', 'a', 'b']);
  assert.equal(meta.moveItem(list, 1, 1), list);
  assert.equal(meta.moveItem(list, -1, 1), list);
  assert.equal(meta.moveItem(list, 1, 3), list);
});

test('the factor is shown without trailing zeros and rounded to the six decimals the database keeps', () => {
  assert.equal(meta.roundFactor(0.1 + 0.2), 0.3);
  assert.equal(meta.roundFactor(0.1234567), 0.123457);
  assert.equal(meta.formatFactor(0.6), '0.6');
  assert.equal(meta.formatFactor(1), '1');
  assert.equal(meta.factorExample(0.6), '6');
  assert.equal(meta.factorExample(0.35), '3.5');
  assert.equal(meta.factorExample(1.234567), '12.3457');
  assert.equal(meta.suffixExample('anthropic', 'kiro'), 'claude-sonnet-4.5@kiro');
  assert.equal(meta.suffixExample('some-vendor', 'x'), 'model@x');
});

test('the runtime-unsupported detail names what this release cannot run', () => {
  assert.equal(unsupportedMessage(null), null);
  assert.equal(unsupportedMessage([]), null);
  assert.equal(unsupportedMessage('enabled'), null);
  const message = unsupportedMessage(['enabled', 'pairGates', 'somethingNew']);
  assert.ok(message.includes('somethingNew'));
  assert.ok(message.includes('启用分级') && message.includes('跨协议门控'));
});

test('switching a vendor on warns that every node must run a version with source tiers first', () => {
  // A version that can run tiers: the warning is for a vendor that is still off, in a rolling upgrade.
  const warning = enableNote(true, false, false);
  assert.ok(warning.includes('所有节点') && warning.includes('标准价'));
  assert.equal(enableNote(true, false, true), warning, 'still shown while the switch is on but not saved');
  assert.equal(enableNote(true, true, true), null, 'a vendor that already runs tiers has been through that');
  // A version that cannot run them says why instead.
  assert.ok(enableNote(false, false, false).includes('暂时不能启用'));
  assert.ok(enableNote(false, true, true).includes('需要关闭后才能保存'));
  assert.notEqual(enableNote(false, false, false), warning);
});

test('the all-endpoints switch says why it cannot be turned on, when it has no effect and when other nodes must be upgraded first', () => {
  // A version that cannot run the switch says why instead.
  assert.ok(unifyNote(false, false, false, true).includes('暂时不能打开'));
  assert.ok(unifyNote(false, true, true, true).includes('需要关闭后才能保存'));
  // A version that can: a vendor that does not use tiers has nothing for the switch to act on.
  const idle = unifyNote(true, true, true, false);
  assert.ok(idle.includes('启用'), 'the switch is on in the draft but the vendor is off');
  assert.equal(unifyNote(true, true, false, false), null, 'a switch that is off has nothing to say');
  // Rolling upgrade: nodes that predate the switch keep Chat and Responses on Kiro alone until it is saved on.
  const upgrade = unifyNote(true, false, false, true);
  assert.ok(upgrade.includes('所有节点') && upgrade.includes('Kiro'));
  assert.equal(unifyNote(true, false, true, true), upgrade, 'still shown while the switch is on but not saved');
  assert.equal(unifyNote(true, true, true, true), null, 'a vendor that already runs it has been through that');
  assert.notEqual(idle, upgrade);
});

test('the all-endpoints switch is off in the draft of a vendor nobody configured, and no preset turns it on', () => {
  const fresh = draftOf({
    ...vendor({ tiers: [], members: [], gates: [] }), configured: false, version: 0, unifyEndpoints: false,
  });
  assert.equal(fresh.unifyEndpoints, false);
  assert.equal(replaceRequest(0, fresh).unifyEndpoints, false);
  for (const preset of tierPresets('anthropic')) {
    const tiers = preset.build();
    const applied = { ...fresh, tiers };
    assert.equal(applied.unifyEndpoints, false);
    assert.equal(replaceRequest(0, applied).unifyEndpoints, false);
  }
  // The switch is saved as the draft has it, so that turning it on is one decision made on one page.
  assert.equal(replaceRequest(7, { ...draftOf(vendor()), unifyEndpoints: true }).unifyEndpoints, true);
});

test('presets are drafts that pass validation and never set a price', () => {
  assert.deepEqual(tierPresets('google'), []);
  assert.deepEqual(tierPresets('deepseek'), []);
  for (const vendorId of ['anthropic', 'openai']) {
    const presets = tierPresets(vendorId);
    assert.ok(presets.length > 0);
    for (const preset of presets) {
      const tiers = preset.build();
      assert.ok(tiers.length >= 2);
      assert.ok(tiers.every((row) => row.priceFactor === 1 && row.enabled));
      assert.equal(new Set(tiers.map((row) => row.key)).size, tiers.length);
      assert.equal(validateDraft({ ...draftOf(vendor({ tiers: [], members: [], gates: [], enabled: false })), tiers }), null);
      assert.notEqual(preset.build()[0].key, tiers[0].key, 'every build has fresh row keys');
    }
  }
  const anthropic = tierPresets('anthropic')[0].build();
  assert.deepEqual(anthropic.map((row) => [row.tierId, row.memberCategories]), [['official', ['OFFICIAL_API_KEY']], ['kiro', ['KIRO', 'CLAUDE_OAUTH']]]);
  const openai = tierPresets('openai')[0].build();
  assert.deepEqual(openai.map((row) => [row.tierId, row.memberCategories]), [['official', ['OFFICIAL_API_KEY']], ['codex', ['CODEX']]]);
});

test('a tier shows the accounts the saved configuration puts in it, and none when it is not saved yet', () => {
  const loaded = vendor({ tierAccounts: { official: 3 } });
  const draft = draftOf(loaded);
  const [official, kiro] = draft.tiers;

  // kiro is saved but holds nobody (the server leaves a tier without accounts out), so it reads 0.
  assert.deepEqual(placedCounts(loaded, draft), { [official.key]: 3, [kiro.key]: 0 });

  // A tier added in the draft, or one whose identifier was just edited, has no saved counterpart to count.
  kiro.tierId = 'kiro-2';
  draft.tiers.push({ ...official, key: 'tier-row-new', tierId: 'brand-new' });
  assert.deepEqual(placedCounts(loaded, draft), { [official.key]: 3 });

  // A server that sends no counts at all reads as every saved tier holding nobody.
  assert.deepEqual(Object.values(placedCounts(vendor(), draftOf(vendor()))), [0, 0]);
});

test('the refusal cap default is the one the server stores', () => {
  assert.equal(DEFAULT_REFUSAL_CAP, 16);
  assert.equal(draftOf(vendor({ configured: false, version: 0, refusalCap: 16, tiers: [], members: [], gates: [] })).refusalCap, DEFAULT_REFUSAL_CAP);
});

test('only accounts without a member row can be pinned, found by e-mail or account id', () => {
  const accounts = [
    { accountId: 'acc-1', email: 'one@example.com' },
    { accountId: 'acc-2', email: 'Two@Example.com' },
    { accountId: 'relay-1', email: 'relay@example.com' },
    { accountId: 'acc-4', email: null },
  ];
  const pins = [{ accountId: 'acc-1', tierKey: 'k' }];

  assert.deepEqual(pinChoices(accounts, pins, ['relay-1'], '').map((a) => a.accountId), ['acc-2', 'acc-4']);
  // A relay already has its row: pinning it again would send two rows for one account and the server refuses.
  assert.deepEqual(pinChoices(accounts, [], ['relay-1'], '').map((a) => a.accountId), ['acc-1', 'acc-2', 'acc-4']);
  assert.deepEqual(pinChoices(accounts, pins, ['relay-1'], '  TWO@exa ').map((a) => a.accountId), ['acc-2']);
  assert.deepEqual(pinChoices(accounts, pins, ['relay-1'], 'ACC-4').map((a) => a.accountId), ['acc-4']);
  assert.deepEqual(pinChoices(accounts, pins, ['relay-1'], 'relay').map((a) => a.accountId), []);
});

test('an exclusive relay of another vendor is no native member anywhere, so it cannot be pinned', () => {
  const sharedRelay = { ...relay, accountId: 'shared-relay', exclusive: false };
  const unspecified = { ...relay, accountId: 'default-relay', exclusive: null };
  const anthropic = vendor({ members: [nativeRow('pinned-kiro', 'kiro'), relay, sharedRelay, unspecified] });
  const zhipu = vendor({ vendor: 'zhipu', members: [nativeRow('glm-1', 'direct')] });

  // Exclusive is the default; only a relay that also serves untiered traffic (exclusive: false) is a native member too.
  assert.deepEqual(exclusiveRelayIds([anthropic, zhipu]).sort(), ['default-relay', 'relay-1']);
  assert.deepEqual(exclusiveRelayIds([zhipu]), []);
  assert.deepEqual(exclusiveRelayIds([]), []);

  const accounts = [{ accountId: 'relay-1', email: 'r@example.com' }, { accountId: 'glm-1', email: 'g@example.com' }, { accountId: 'glm-2', email: 'h@example.com' }];
  const taken = [...exclusiveRelayIds([anthropic])];
  assert.deepEqual(pinChoices(accounts, [{ accountId: 'glm-1', tierKey: 'k' }], taken, '').map((a) => a.accountId), ['glm-2']);
});

test('setting accounts to a tier skips the exclusive relays of other vendors, but not when taking the pinning away', () => {
  const zhipu = vendor({ vendor: 'zhipu', version: 3, tiers: [tier('direct', 1)], members: [nativeRow('stale-pin', 'direct')] });
  const elsewhere = new Set(['relay-1', 'stale-pin']);

  const pinning = batchPlan(zhipu, ['glm-1', 'relay-1', 'stale-pin'], 'direct', elsewhere);
  assert.deepEqual(pinning.pin, ['glm-1']);
  assert.deepEqual(pinning.skippedElsewhere, ['relay-1', 'stale-pin']);
  assert.deepEqual(pinning.skippedRelay, []);
  assert.deepEqual(pinning.requests.map((request) => request.members.map((member) => member.accountId)), [['glm-1']]);

  // A pin that was saved before the relay was registered can be removed.
  const unpinning = batchPlan(zhipu, ['glm-1', 'relay-1', 'stale-pin'], UNPIN, elsewhere);
  assert.deepEqual(unpinning.unpin, ['stale-pin']);
  assert.deepEqual(unpinning.unchanged, ['glm-1', 'relay-1']);
  assert.deepEqual(unpinning.skippedElsewhere, []);
  assert.deepEqual(unpinning.requests, [{ expectedVersion: 3, removeAccountIds: ['stale-pin'] }]);

  // Without the set, as before.
  assert.deepEqual(batchPlan(zhipu, ['relay-1'], 'direct').pin, ['relay-1']);
  assert.deepEqual(batchPlan(zhipu, ['relay-1'], 'direct').skippedElsewhere, []);
});

/* ---------- status panel, account list, pricing preview, billing review, request log (P2-07) ---------- */

test('the layer bar has a segment for every layer that holds accounts, in the order of the layers', () => {
  const segments = layerSegments({ disabled: 1, unavailable: 0, resting: 1, busy: 0, ready: 2 });
  assert.deepEqual(segments.map((segment) => [segment.key, segment.count]), [['ready', 2], ['resting', 1], ['disabled', 1]]);
  assert.deepEqual(segments.map((segment) => segment.percent), [50, 25, 25]);
  assert.deepEqual(layerSegments({ disabled: 0, unavailable: 0, resting: 0, busy: 0, ready: 0 }), []);
  assert.equal(layerTotal({ disabled: 1, unavailable: 2, resting: 3, busy: 4, ready: 5 }), 15);
});

test('the downgrade rate is a share of the gateway-chosen requests, with one decimal, and unknown without them', () => {
  assert.equal(degradedPercent({ autoServed: 0, degraded: 0 }), null);
  assert.equal(degradedPercent({ autoServed: 100, degraded: 30 }), 30);
  assert.equal(degradedPercent({ autoServed: 3, degraded: 1 }), 33.3);
  assert.equal(degradedTone(null), 'info');
  assert.equal(degradedTone(0), 'success');
  assert.equal(degradedTone(12.5), 'warning');
  assert.equal(degradedTone(50), 'danger');
});

test('a vendor that is not serving says why: the release first, then the switch, then the tiers', () => {
  const serving = { vendor: 'anthropic', label: 'Anthropic', enabled: true, serving: true, autoServed: 0, degraded: 0, tiers: [] };
  assert.equal(notServingReason(serving, true), null);
  assert.match(notServingReason({ ...serving, serving: false }, false), /版本尚不支持/);
  assert.match(notServingReason({ ...serving, serving: false, enabled: false }, true), /还没有启用分级/);
  assert.match(notServingReason({ ...serving, serving: false }, true), /没有已启用的号池/);
});

const placements = {
  configVersion: 3,
  accounts: [
    { accountId: 'a1', tiers: [{ vendor: 'anthropic', vendorLabel: 'Anthropic', tierId: 'official', level: 1, name: '官方', rule: 'CATEGORY', relay: false, active: true }] },
    { accountId: 'a2', tiers: [
      { vendor: 'anthropic', vendorLabel: 'Anthropic', tierId: 'kiro', level: 2, name: 'Kiro', rule: 'MEMBER_ROW', relay: false, active: true },
      { vendor: 'openai', vendorLabel: 'OpenAI', tierId: 'codex', level: 2, name: 'Codex', rule: 'LOWEST_TIER', relay: false, active: false },
    ] },
  ],
};

test('the account list reads each account\'s tiers, filters by one, and names the vendor only when there are several', () => {
  const index = placementsByAccount(placements);
  assert.equal(index.get('a1')[0].tierId, 'official');
  assert.equal(index.get('missing'), undefined);
  assert.equal(placementsByAccount(null).size, 0);
  assert.equal(placementVendorCount(placements), 2);
  assert.equal(placementVendorCount({ configVersion: 1, accounts: [placements.accounts[0]] }), 1);
  assert.equal(placedLabel(placements.accounts[1].tiers[0], false), 'L2 Kiro');
  assert.equal(placedLabel(placements.accounts[1].tiers[0], true), 'Anthropic · L2 Kiro');
  assert.deepEqual(tierFilterOptions(placements), [
    { value: 'anthropic:official', label: 'Anthropic · L1 官方' },
    { value: 'anthropic:kiro', label: 'Anthropic · L2 Kiro' },
    { value: 'openai:codex', label: 'OpenAI · L2 Codex' },
  ]);
  assert.deepEqual(tierFilterOptions({ configVersion: 1, accounts: [placements.accounts[0]] }), [{ value: 'anthropic:official', label: 'L1 官方' }]);
  assert.equal(matchesTierFilter(index.get('a2'), ''), true);
  assert.equal(matchesTierFilter(index.get('a2'), 'openai:codex'), true);
  assert.equal(matchesTierFilter(index.get('a1'), 'openai:codex'), false);
  assert.equal(matchesTierFilter(undefined, NO_TIER_FILTER), true);
  assert.equal(matchesTierFilter(index.get('a1'), NO_TIER_FILTER), false);
});

test('a placement says why the account is there and whether the tier serves', () => {
  const [byCategory] = placements.accounts[0].tiers;
  const [pinned, lowest] = placements.accounts[1].tiers;
  assert.match(placedHint(byCategory), /按账号类别归入「官方」/);
  assert.match(placedHint(pinned), /手动指定到「Kiro」/);
  assert.doesNotMatch(placedHint(pinned), /现在没有按这个号池服务/);
  assert.match(placedHint(lowest), /最低一级「Codex」/);
  assert.match(placedHint(lowest), /现在没有按这个号池服务/);
  assert.match(placedHint({ ...pinned, relay: true }), /跨协议中转/);
});

function memberVendor(extra = {}) {
  return vendor({
    version: 7,
    members: [nativeRow('pinned-kiro', 'kiro'), nativeRow('pinned-official', 'official'), relay],
    ...extra,
  });
}

test('setting accounts to a tier writes only the ones that change and skips relays', () => {
  const plan = batchPlan(memberVendor(), ['new-1', 'pinned-kiro', 'pinned-official', 'relay-1', 'new-1'], 'official');
  assert.deepEqual(plan.pin, ['new-1', 'pinned-kiro']);
  assert.deepEqual(plan.unchanged, ['pinned-official']);
  assert.deepEqual(plan.skippedRelay, ['relay-1']);
  assert.deepEqual(plan.unpin, []);
  assert.equal(plan.requests.length, 1);
  assert.equal(plan.requests[0].expectedVersion, 7);
  assert.deepEqual(plan.requests[0].members.map((member) => [member.accountId, member.tierId, member.protocol]), [
    ['new-1', 'official', null], ['pinned-kiro', 'official', null],
  ]);
  assert.equal(plan.requests[0].removeAccountIds, undefined);
});

test('removing the pinning removes only accounts that have a pin, and does nothing when none has', () => {
  const plan = batchPlan(memberVendor(), ['pinned-kiro', 'never-pinned', 'relay-1'], UNPIN);
  assert.deepEqual(plan.unpin, ['pinned-kiro']);
  assert.deepEqual(plan.unchanged, ['never-pinned']);
  assert.deepEqual(plan.skippedRelay, ['relay-1']);
  assert.deepEqual(plan.requests, [{ expectedVersion: 7, removeAccountIds: ['pinned-kiro'] }]);
  assert.deepEqual(batchPlan(memberVendor(), ['never-pinned'], UNPIN).requests, []);
  assert.deepEqual(batchPlan(memberVendor(), ['pinned-kiro'], 'kiro').requests, []);
});

test('a batch larger than the server accepts is split, each request one version after the last', () => {
  const ids = Array.from({ length: MAX_BATCH_MEMBERS * 2 + 5 }, (_, index) => `acc-${index}`);
  const plan = batchPlan(memberVendor({ version: 4 }), ids, 'official');
  assert.deepEqual(plan.requests.map((request) => [request.expectedVersion, request.members.length]), [
    [4, MAX_BATCH_MEMBERS], [5, MAX_BATCH_MEMBERS], [6, 5],
  ]);
  assert.equal(new Set(plan.requests.flatMap((request) => request.members.map((member) => member.accountId))).size, ids.length);
  const unpin = batchPlan(vendor({ version: 9, members: ids.map((id) => nativeRow(id, 'kiro')) }), ids, UNPIN);
  assert.deepEqual(unpin.requests.map((request) => [request.expectedVersion, request.removeAccountIds.length]), [
    [9, MAX_BATCH_MEMBERS], [10, MAX_BATCH_MEMBERS], [11, 5],
  ]);
});

test('the price preview prices the tiers of an enabled vendor, best first, and nothing otherwise', () => {
  assert.equal(vendorOfProvider('Anthropic'), 'anthropic');
  assert.equal(vendorOfProvider('Claude'), 'anthropic');
  assert.equal(vendorOfProvider('Gemini'), 'google');
  assert.equal(vendorOfProvider('Google'), 'google');
  assert.equal(vendorOfProvider('OpenAI'), 'openai');
  assert.equal(vendorOfProvider('Open Router'), 'open-router');
  assert.equal(vendorOfProvider(undefined), '');
  const enabled = vendor({ enabled: true, tiers: [tier('kiro', 2, { priceFactor: 0.6 }), tier('official', 1), tier('off', 3, { enabled: false })] });
  assert.deepEqual(previewTiers(enabled, true).map((row) => [row.tierId, row.level, row.factor]), [['official', 1, 1], ['kiro', 2, 0.6]]);
  assert.deepEqual(previewTiers(enabled, false), []);
  assert.deepEqual(previewTiers({ ...enabled, enabled: false }, true), []);
  assert.deepEqual(previewTiers(undefined, true), []);
});

const reviewRow = {
  request_hash: 'h', request_id: 'r', user_id: 'u', billing_source: 'BALANCE', reserved_credits: 0.05, actual_credits: null,
  reason: 'x', created_at: '2026-10-25T00:00:00Z',
};
const kiroTier = {
  tierRef: 'anthropic:kiro', level: 2, name: 'kiro', factor: '0.6', estimate: '0.03', at: '2026-10-25T01:02:03Z',
  quote: { input: '0.0000018', output: '0.000009', cacheRead: '0.00000018', cacheWrite: '0.00000225', fallback: '0.0000018' },
};

test('a review of a tiered request is settled by the tier that served it, not by the reservation', () => {
  assert.equal(reviewTier(reviewRow), null);
  assert.equal(suggestedCredits(reviewRow), '0.05');
  const dispatched = { ...reviewRow, last_dispatched: kiroTier };
  assert.equal(reviewTier(dispatched), kiroTier);
  assert.equal(suggestedCredits(dispatched), '0.03');
  const served = { ...dispatched, served: { ...kiroTier, estimate: '0.02' } };
  assert.equal(reviewTier(served).estimate, '0.02');
  assert.equal(suggestedCredits(served), '0.02');
  assert.equal(suggestedCredits({ ...served, actual_credits: 0.0234 }), '0.0234');
  assert.equal(reviewTierLabel(kiroTier), 'L2 kiro');
  assert.equal(reviewTierLabel({ tierRef: 'anthropic:kiro' }), 'anthropic:kiro');
  assert.equal(reservedMinusEstimate(dispatched, kiroTier), 0.02);
  assert.equal(reservedMinusEstimate(dispatched, { ...kiroTier, estimate: '0.05' }), null);
  assert.equal(reservedMinusEstimate(dispatched, { tierRef: 'x' }), null);
});

test('a quote per token is shown per million tokens without float noise', () => {
  assert.equal(perMillion('0.0000018'), '1.8');
  assert.equal(perMillion('0.000009'), '9');
  assert.equal(perMillion('0.00000225'), '2.25');
  assert.equal(perMillion(undefined), '—');
  assert.equal(perMillion('n/a'), 'n/a');
});

test('the request log gets a source column only when some row was served from tiers', () => {
  const plain = { requestId: 'a', path: '/v1/chat/completions', success: true };
  const served = { ...plain, sourceTierRef: 'anthropic:kiro', sourceTierLevel: 2, sourceTierName: 'kiro', priceFactor: 0.6 };
  assert.equal(hasSourceTiers([plain]), false);
  assert.equal(hasSourceTiers([plain, served]), true);
  assert.equal(sourceTierLabel(plain), '');
  assert.equal(sourceTierLabel(served), 'L2 kiro');
  assert.equal(sourceTierLabel({ ...plain, sourceTierLevel: 1, sourceTierName: '官方' }), 'L1 官方');
  assert.equal(priceFactorLabel(served), '×0.6');
  assert.equal(priceFactorLabel({ ...served, priceFactor: 1.000000 }), '×1');
  assert.equal(priceFactorLabel(plain), '');
});

/* ---------- API Key 的号源偏好 ---------- */

const choiceOfficial = { id: 'official', level: 1, name: '官方', priceFactor: 1 };
const choiceKiro = {
  id: 'kiro', level: 2, name: 'kiro反向代理', priceFactor: 0.6,
  pricing: { billingMode: 'input_output', inputPricePerMillionTokens: 1.8, outputPricePerMillionTokens: 9 },
};

test('a key without a preference, or with auto in any case, is automatic; anything else is read in lower case', () => {
  assert.equal(AUTO_PREFERENCE, 'auto');
  for (const stored of [undefined, null, '', '  ', 'auto', 'AUTO', ' Auto ']) {
    assert.equal(preferenceValue(stored), 'auto', JSON.stringify(stored));
  }
  assert.equal(preferenceValue('Kiro+'), 'kiro+');
  assert.equal(preferenceValue(' L2 '), 'l2');
});

test('the form offers automatic first and, for each pool, the pool alone and the pool with those below it', () => {
  const choices = preferenceChoices([choiceKiro, choiceOfficial], 'claude-sonnet-4.5', 'auto');

  assert.deepEqual(choices.map((choice) => choice.value), ['auto', 'official', 'official+', 'kiro', 'kiro+']);
  assert.equal(choices[0].label, '自动');
  assert.equal(choices[1].label, '官方（1 级，系数 ×1）');
  assert.equal(choices[2].label, '官方 及以下');
  assert.equal(choices[3].label, 'kiro反向代理（2 级，系数 ×0.6）');
  assert.equal(choices[4].label, 'kiro反向代理 及以下');
});

test('a pool shows its factor, and its price when the server could work one out', () => {
  const choices = preferenceChoices([choiceOfficial, choiceKiro], 'claude-sonnet-4.5', 'auto');

  assert.equal(choices[1].hint, '标准价 × 1');
  assert.equal(choices[3].hint, '标准价 × 0.6；以 claude-sonnet-4.5 计：输入 1.8、输出 9（每百万 token）');
  // Without a reference model the price has nothing to be read against.
  assert.equal(preferenceChoices([choiceKiro], null, 'auto')[1].hint, '标准价 × 0.6');
});

test('a price is read from the input and output, else from the total, else not at all', () => {
  assert.equal(priceText(choiceKiro.pricing), '输入 1.8、输出 9（每百万 token）');
  assert.equal(priceText({ billingMode: 'total', totalPricePerMillionTokens: 4.5 }), '4.5（每百万 token）');
  assert.equal(priceText({ billingMode: 'unavailable' }), null);
  assert.equal(priceText(undefined), null);
  assert.equal(priceText({ billingMode: 'input_output', inputPricePerMillionTokens: 0.1 + 0.2, outputPricePerMillionTokens: 1 }), '输入 0.3、输出 1（每百万 token）');
});

test('a price is a number of balance units with no currency sign, and reads like the price of the same pool on the model page', () => {
  // 3 / 15 dollars under a billing multiplier of 7.2: the balance is not dollars, so the form must not say it is.
  const multiplied = { billingMode: 'input_output', inputPricePerMillionTokens: 21.6, outputPricePerMillionTokens: 108 };
  const pool = { ...choiceKiro, priceFactor: 1, pricing: multiplied };
  try {
    for (const language of ['zh-CN', 'en-US']) {
      meta.setLocale(language);
      assert.doesNotMatch(priceText(multiplied), /[$¥￥]/, language);
      assert.doesNotMatch(priceText({ billingMode: 'total', totalPricePerMillionTokens: 64.8 }), /[$¥￥]/, language);
      assert.doesNotMatch(preferenceChoices([pool], 'claude-sonnet-4.5', 'auto')[1].hint, /[$¥￥]/, language);
    }
    meta.setLocale('en-US');
    assert.equal(priceText(multiplied), 'Input 21.6, output 108 (per million tokens)');
    meta.setLocale('zh-CN');
    assert.equal(priceText(multiplied), '输入 21.6、输出 108（每百万 token）');
    // The numbers are the ones the model page shows for the pool, formatted the same way (with grouping, no rounding noise).
    assert.equal(modelTierChips('claude-sonnet-4.5', [{ id: 'kiro', level: 2, name: 'kiro', priceFactor: 1, selectable: true, pricing: multiplied }])[0].price, '64.8 / MTok');
    assert.equal(priceText({ billingMode: 'input_output', inputPricePerMillionTokens: 1234.5, outputPricePerMillionTokens: 12345.678901234 }), '输入 1,234.5、输出 12,345.678901（每百万 token）');
  } finally {
    meta.setLocale('zh-CN');
  }
});

test('a preference that is not among the pools stays as an option of its own so that saving does not change it', () => {
  const kept = preferenceChoices([choiceOfficial], null, 'kiro+');
  assert.deepEqual(kept.map((choice) => choice.value), ['auto', 'official', 'official+', 'kiro+']);
  assert.equal(kept.at(-1).label, 'kiro+');
  // A value the pools do offer is not added a second time, whatever case it was stored in.
  assert.equal(preferenceChoices([choiceOfficial], null, 'Official+').length, 3);
  assert.equal(preferenceChoices([], null, 'auto').length, 1);
});

test('the option that keeps a preference says it is not listed, not that requests ignore it', () => {
  // A user of a vendor that keeps its pool names private sees this too, and requests are still served by the pool.
  const hint = preferenceChoices([], null, 'kiro').at(-1).hint;
  assert.match(hint, /没有列出/);
  assert.match(hint, /仍按它服务/);
  assert.doesNotMatch(hint, /请求会忽略/);
});

test('a preference written as a level is named after the pool of that level and says it was chosen by level', () => {
  const pools = [choiceOfficial, choiceKiro];
  for (const stored of ['l2', 'L2', ' 2 ']) {
    const kept = preferenceChoices(pools, 'claude-sonnet-4.5', stored).at(-1);
    assert.equal(kept.value, preferenceValue(stored), stored);
    assert.equal(kept.label, 'kiro反向代理（2 级）', stored);
    assert.match(kept.hint, /按级别选择/, stored);
    assert.match(kept.hint, /由 2 级的号池服务/, stored);
    assert.doesNotMatch(kept.hint, /没有列出/, stored);
  }
  for (const stored of ['l1+', '1+']) {
    const kept = preferenceChoices(pools, null, stored).at(-1);
    assert.equal(kept.value, stored);
    assert.equal(kept.label, '官方 及以下（1 级起）', stored);
    assert.match(kept.hint, /按级别选择/, stored);
    assert.match(kept.hint, /1 级的号池和它以下的号池/, stored);
    assert.doesNotMatch(kept.hint, /没有列出/, stored);
  }
  // Saving keeps what it was: the option is the value as it is stored, once.
  assert.equal(preferenceChoices(pools, null, '2+').filter((choice) => choice.value === '2+').length, 1);
});

test('every spelling of a level the backend stores is recognised, up to the deepest level a pool can have', () => {
  // The backend writes a level without zeros (L02 is saved as l2, 002+ as 2+), so these are the only forms there are.
  const deepest = { id: 'spare', level: 9, name: '备用', priceFactor: 0.3 };
  const pools = [choiceOfficial, deepest];
  for (const stored of ['l9', '9', 'l9+', '9+', 'l1', '1+']) {
    const kept = preferenceChoices(pools, null, stored).at(-1);
    assert.equal(kept.value, stored);
    assert.notEqual(kept.label, stored, stored);
    assert.match(kept.hint, /按级别选择/, stored);
    assert.doesNotMatch(kept.hint, /没有列出/, stored);
  }
});

test('a level that no listed pool has, and any other reference, is still said not to be listed', () => {
  for (const stored of ['l3', '3', '3+', 'l9', 'l0', '10', 'kiro++']) {
    const kept = preferenceChoices([choiceOfficial, choiceKiro], null, stored).at(-1);
    assert.equal(kept.value, stored);
    assert.equal(kept.label, stored);
    assert.match(kept.hint, /没有列出/, stored);
  }
  // A vendor that keeps its pool names private lists none, so a level cannot be put to a name either.
  const hidden = preferenceChoices([], null, 'l2').at(-1);
  assert.equal(hidden.label, 'l2');
  assert.match(hidden.hint, /没有列出/);
});

test('the held level reads in English too', () => {
  meta.setLocale('en-US');
  try {
    const pools = [choiceOfficial, choiceKiro];
    const exact = preferenceChoices(pools, null, 'l2').at(-1);
    assert.equal(exact.label, 'kiro反向代理 (tier 2)');
    assert.match(exact.hint, /chosen by level/);
    assert.match(exact.hint, /level 2/);
    assert.doesNotMatch(exact.hint, /not listed/);
    const orBelow = preferenceChoices(pools, null, '1+').at(-1);
    assert.equal(orBelow.label, '官方 and below (from tier 1)');
    assert.match(orBelow.hint, /and the tiers below it/);
    assert.match(preferenceChoices(pools, null, 'l7').at(-1).hint, /not listed/);
  } finally {
    meta.setLocale('zh-CN');
  }
});

test('a key form of a deployment without pools offers only automatic', () => {
  assert.deepEqual(preferenceChoices([], undefined, undefined).map((choice) => choice.value), ['auto']);
});

// ---- the tier in the client snippets (design 6.5) ----

const setup = { apiOrigin: 'https://example.com', apiBaseUrl: 'https://example.com/v1', apiKey: 'sk-test-user-key', apiPort: '443' };
const scriptOf = (client, platform, tier) => createSetupScriptBlock(client, platform, { ...setup, tier }).script;
const everyScript = (tier) => ['codex', 'claude', 'opencode'].flatMap((client) => ['unix', 'cmd', 'powershell'].map((platform) => scriptOf(client, platform, tier)));
const linksOf = (tier) => createCcSwitchLinks({ ...setup, tier });
const linkParams = (link) => new URL(link.url).searchParams;

test('a tier is a pool id, a level or a number, with + for the pool and those below it, in lower case', () => {
  assert.equal(TIER_HEADER, 'X-Starry-Tier');
  assert.equal(tierReference('kiro'), 'kiro');
  assert.equal(tierReference(' Kiro+ '), 'kiro+');
  assert.equal(tierReference('L2'), 'l2');
  assert.equal(tierReference('2+'), '2+');
  assert.equal(tierReference('a'.repeat(32)), 'a'.repeat(32));
});

test('automatic, nothing and anything the server would not take as a tier write nothing', () => {
  for (const tier of [undefined, null, '', '  ', 'auto', 'AUTO', 'auto+', '+', '-kiro', 'a'.repeat(33), 'kiro pool', 'kiro"', "kiro'; rm -rf ~", 'kiro\nevil', '模型', 'kiro++', '@kiro']) {
    assert.equal(tierReference(tier), null, JSON.stringify(tier));
    assert.deepEqual(everyScript(tier), everyScript(undefined), JSON.stringify(tier));
    assert.deepEqual(linksOf(tier).map((link) => link.url), linksOf(undefined).map((link) => link.url), JSON.stringify(tier));
  }
});

test('Claude Code gets the tier as the custom header its environment variable carries, in each shell\'s own syntax', () => {
  const unix = scriptOf('claude', 'unix', 'kiro').split('\n');
  assert.ok(unix.includes("export ANTHROPIC_CUSTOM_HEADERS='X-Starry-Tier: kiro'"));
  // It sits among the other variables and changes none of them.
  assert.deepEqual(unix.filter((line) => !line.includes('CUSTOM_HEADERS')), scriptOf('claude', 'unix', undefined).split('\n'));
  assert.ok(scriptOf('claude', 'cmd', 'kiro+').split('\n').includes('set "ANTHROPIC_CUSTOM_HEADERS=X-Starry-Tier: kiro+"'));
  assert.ok(scriptOf('claude', 'powershell', 'L2').split('\n').includes("$env:ANTHROPIC_CUSTOM_HEADERS = 'X-Starry-Tier: l2'"));
});

test('Codex gets the tier as a header of its provider, after the settings it already had, and its auth file is untouched', () => {
  const lines = scriptOf('codex', 'unix', 'Kiro+').split('\n');
  const at = lines.indexOf('supports_websockets = false');
  assert.equal(lines[at + 1], 'http_headers = { "X-Starry-Tier" = "kiro+" }');
  assert.equal(lines.filter((line) => line.includes('X-Starry-Tier')).length, 1);
  const files = createSetupScriptBlock('codex', 'unix', { ...setup, tier: 'kiro' }).files;
  const plain = createSetupScriptBlock('codex', 'unix', setup).files;
  assert.equal(files[1].content, plain[1].content);
  assert.equal(files[0].content.split('\n').filter((line) => line !== 'http_headers = { "X-Starry-Tier" = "kiro" }').join('\n'), plain[0].content);
});

test('OpenCode gets the tier as a header of its provider options', () => {
  const config = JSON.parse(scriptOf('opencode', 'unix', 'kiro'));
  assert.deepEqual(config.provider.starry.options, { baseURL: 'https://example.com/v1', apiKey: 'sk-test-user-key', headers: { 'X-Starry-Tier': 'kiro' } });
  const plain = JSON.parse(scriptOf('opencode', 'unix', undefined));
  delete config.provider.starry.options.headers;
  assert.deepEqual(config, plain);
});

test('the CC Switch link of Claude Code puts the tier after every model name, the Codex link carries the header in its config, the OpenCode link nothing', () => {
  const [claude, codex, opencode] = linksOf('kiro+');
  const [plainClaude, plainCodex, plainOpencode] = linksOf(undefined);
  const params = linkParams(claude);
  assert.deepEqual(['model', 'haikuModel', 'sonnetModel', 'opusModel'].map((name) => params.get(name)), [
    'claude-opus-4.8@kiro+', 'claude-haiku-4.5@kiro+', 'claude-opus-4.8@kiro+', 'claude-opus-4.8@kiro+',
  ]);
  assert.equal(params.get('apiKey'), linkParams(plainClaude).get('apiKey'));
  assert.equal(params.get('endpoint'), linkParams(plainClaude).get('endpoint'));

  const config = JSON.parse(Buffer.from(linkParams(codex).get('config'), 'base64').toString('utf8'));
  assert.ok(config.config.split('\n').includes('http_headers = { "X-Starry-Tier" = "kiro+" }'));
  assert.deepEqual(config.auth, JSON.parse(Buffer.from(linkParams(plainCodex).get('config'), 'base64').toString('utf8')).auth);

  assert.equal(opencode.url, plainOpencode.url);
});

test('the snippets and links are the same in every language', () => {
  meta.setLocale('zh-CN');
  const chinese = JSON.stringify([everyScript('kiro+'), linksOf('kiro+').map((link) => link.url)]);
  meta.setLocale('en-US');
  assert.equal(JSON.stringify([everyScript('kiro+'), linksOf('kiro+').map((link) => link.url)]), chinese);
  meta.setLocale('zh-CN');
});

// ---- the tiers of a model on the model page ----

const listedTiers = [
  { id: 'kiro', level: 2, name: 'kiro反向代理', priceFactor: 0.6, selectable: true, pricing: { billingMode: 'input_output', inputPricePerMillionTokens: 1.8, outputPricePerMillionTokens: 9 } },
  { id: 'official', level: 1, name: '官方', priceFactor: 1, selectable: true, pricing: { billingMode: 'input_output', inputPricePerMillionTokens: 3, outputPricePerMillionTokens: 15 } },
  { id: 'internal', level: 3, name: '内部', priceFactor: 0.250000, selectable: false, pricing: null },
];

test('a model is asked for from a pool with its id, an @ and the pool id', () => {
  assert.equal(tierSuffix('claude-sonnet-4.5', 'kiro'), 'claude-sonnet-4.5@kiro');
});

test('the model page lists the pools best first, with the factor, the price and what to copy', () => {
  const chips = modelTierChips('claude-sonnet-4.5', listedTiers);

  assert.deepEqual(chips.map((chip) => chip.id), ['official', 'kiro', 'internal']);
  assert.deepEqual(chips.map((chip) => chip.factor), ['×1', '×0.6', '×0.25']);
  assert.deepEqual(chips.map((chip) => chip.suffix), ['claude-sonnet-4.5@official', 'claude-sonnet-4.5@kiro', null]);
  assert.equal(chips[0].price, '9 / MTok');
  assert.equal(chips[1].price, '5.4 / MTok');
  assert.equal(chips[2].price, null);
  assert.equal(chips[1].title, '2 级。复制 claude-sonnet-4.5@kiro：请求只由这个号池服务');
  assert.equal(chips[2].title, '3 级。请求不能指定这个号池');
  // The list the server sent is not reordered.
  assert.deepEqual(listedTiers.map((tier) => tier.id), ['kiro', 'official', 'internal']);
});

test('a model without pools, or whose vendor keeps its pool names private, shows none', () => {
  assert.deepEqual(modelTierChips('gpt-5.5', undefined), []);
  assert.deepEqual(modelTierChips('gpt-5.5', null), []);
  assert.deepEqual(modelTierChips('gpt-5.5', []), []);
});
