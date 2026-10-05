const test = require('node:test');
const assert = require('node:assert/strict');
const { main, validateCoverage } = require('./smoke.cjs');
const path = require('node:path');
const { load } = require('./lib.cjs');
const scenarios = require('../../contracts/scenarios/prism.json');

test('chat input schema accepts whitespace around the 200-code-point HTTP boundary', () => {
  const { validator } = load(path.join(__dirname, '../../contracts/api/live-service.yaml'));
  const validate = validator('#/components/schemas/ChatInput');
  assert.equal(validate({ content: ' ' + '😀'.repeat(200) + ' ' }), true, JSON.stringify(validate.errors));
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
