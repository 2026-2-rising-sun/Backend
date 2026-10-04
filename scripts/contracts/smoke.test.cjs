const test = require('node:test');
const assert = require('node:assert/strict');
const { main, validateCoverage, selectScenarios } = require('./smoke.cjs');
const scenarios = require('../../contracts/scenarios/prism.json');

test('focused Prism selection keeps all cases for the selected provider and still validates global coverage', () => {
  for (const service of ['member', 'shopping', 'commerce', 'live']) {
    assert.deepEqual(selectScenarios(scenarios, [service]), scenarios.filter(item => item.service === service));
  }
  for (const selection of [[], ['unknown'], ['commerce', 'commerce'], 'commerce'])
    assert.throws(() => selectScenarios(scenarios, selection), /invalid service selection/);
  assert.throws(() => selectScenarios(scenarios.filter(item => item.service !== 'member'), ['commerce']), /member needs a success scenario/);
});

test('empty scenario input fails the actual smoke entry before starting Docker', async () => {
  await assert.rejects(main([]), /Prism coverage: scenarios must be nonempty/);
});
test('removing any contract service fails instead of reporting partial smoke success', async () => {
  for (const service of ['member', 'shopping', 'commerce', 'live']) {
    await assert.rejects(main(scenarios.filter(item => item.service !== service)), new RegExp(`Prism coverage: ${service} needs a success scenario`));
  }
});
test('success and required failure categories cannot silently disappear', async () => {
  assert.doesNotThrow(() => validateCoverage(scenarios));
  await assert.rejects(main(scenarios.filter(item => item.service !== 'commerce' || item.code !== 503)), /commerce missing error 503/);
  await assert.rejects(main(scenarios.filter(item => item.service !== 'member' || item.code >= 400)), /member needs a success scenario/);
});

test('session rotation, logout and inactive status cases cannot hide behind another response with the same code', async () => {
  for (const name of ['session-refresh-200', 'session-logout-204', 'session-check-inactive', 'session-authority-unavailable-commerce']) {
    await assert.rejects(main(scenarios.filter(item => item.name !== name)), new RegExp(`missing required scenario ${name}`));
  }
  await assert.rejects(main([...scenarios, scenarios.find(item => item.name === 'session-refresh-200')]), /duplicate named scenario/);
});
