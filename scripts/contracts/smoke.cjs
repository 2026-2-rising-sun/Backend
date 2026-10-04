const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { root, files, load, escape, report } = require('./lib.cjs');
const { mock } = require('../local/contracts.cjs');
const { docker, stop, ready } = require('./docker.cjs');
const defaultScenarios = require('../../contracts/scenarios/prism.json');
const coverage = require('../../contracts/scenarios/required-coverage.json');
function validateCoverage(scenarios) {
  assert.ok(Array.isArray(scenarios) && scenarios.length > 0, 'Prism coverage: scenarios must be nonempty');
  const services = files().map(file => path.basename(file, '-service.yaml'));
  for (const service of Object.keys(coverage)) assert.ok(services.includes(service), `Prism coverage: missing contract for ${service}`);
  for (const scenario of scenarios) assert.ok(services.includes(scenario.service), `Prism coverage: unknown service ${scenario.service}`);
  for (const service of services) {
    const required = coverage[service];
    assert.ok(required, `Prism coverage: missing policy for ${service}`);
    assert.ok(Array.isArray(required.errors) && required.errors.length > 0
      && required.errors.every(code => Number.isInteger(code) && code >= 400 && code < 600),
    `Prism coverage: ${service} requires explicit failure codes`);
    const codes = scenarios.filter(item => item.service === service).map(item => item.code);
    assert.ok(codes.some(code => code >= 200 && code < 300), `Prism coverage: ${service} needs a success scenario`);
    for (const code of required.errors) assert.ok(codes.includes(code), `Prism coverage: ${service} missing error ${code}`);
    const names = scenarios.filter(item => item.service === service && item.name).map(item => item.name);
    assert.equal(names.length, new Set(names).size, `Prism coverage: ${service} duplicate named scenario`);
    for (const name of required.scenarios || [])
      assert.ok(names.includes(name), `Prism coverage: ${service} missing required scenario ${name}`);
  }
}
function selectScenarios(scenarios, selected = files().map(file => path.basename(file, '-service.yaml'))) {
  validateCoverage(scenarios);
  const available = files().map(file => path.basename(file, '-service.yaml'));
  assert.ok(Array.isArray(selected) && selected.length > 0 && new Set(selected).size === selected.length
    && selected.every(service => available.includes(service)), 'Prism coverage: invalid service selection');
  return scenarios.filter(item => selected.includes(item.service));
}
async function main(scenarios = defaultScenarios) {
  // Fail before starting Docker when a deleted/empty scenario file would otherwise look successful.
  scenarios = selectScenarios(scenarios, process.env.CONTRACT_SERVICES ? JSON.parse(process.env.CONTRACT_SERVICES) : undefined);
  const results = [];
  for (const service of new Set(scenarios.map(item => item.service))) {
    const { doc, resolve, validator, sha256 } = load(path.join(root, 'contracts/api', service + '-service.yaml'));
    const server = mock(service);
    try {
      await ready(server.url);
      for (const scenario of scenarios.filter(item => item.service === service)) {
        const method = (scenario.method || 'GET').toLowerCase();
        const operation = doc.paths[scenario.contractPath][method];
        const raw = operation.responses[scenario.code];
        const response = raw.$ref ? resolve(raw.$ref) : raw;
        const media = response.content?.['application/json'];
        const selected = scenario.example ? media?.examples?.[scenario.example] : undefined;
        if (scenario.example) assert.ok(selected, `Unknown example: ${scenario.example}`);
        const expected = selected ? (selected.$ref ? resolve(selected.$ref).value : selected.value) : media?.example;
        if (media) assert.notEqual(expected, undefined, 'Exact response example is required');
        const pointer = raw.$ref || `#/paths/${escape(scenario.contractPath)}/${method}/responses/${scenario.code}`;
        const validate = media && validator(pointer + '/content/application~1json/schema');
        const prefer = `code=${scenario.code}` + (scenario.example ? `, example=${scenario.example}` : '');
        const headers = { ...(scenario.body === undefined ? {} : { 'Content-Type': 'application/json' }), ...scenario.headers, Prefer: prefer };
        const actual = await fetch(server.url + scenario.path, { method: method.toUpperCase(), headers,
          body: scenario.body === undefined ? undefined : JSON.stringify(scenario.body), signal: AbortSignal.timeout(5000) });
        assert.equal(actual.status, scenario.code, `${service} ${method} ${scenario.path} example=${scenario.example}`);
        const text = await actual.text();
        const body = media ? JSON.parse(text) : undefined;
        if (media) {
          assert.deepEqual(body, expected);
          assert.ok(validate(body), JSON.stringify(validate.errors));
        } else {
          assert.equal(scenario.code, 204, 'Only explicitly empty 204 is supported without JSON');
          assert.equal(text, '');
        }
        results.push({ ...scenario, prefer, sha256, status: actual.status, body, passed: true });
      }
    } finally {
      fs.mkdirSync(path.join(root, 'build/contracts'), { recursive: true });
      try { fs.writeFileSync(path.join(root, `build/contracts/prism-${service}.log`), docker(['logs', server.id])); }
      finally { stop(server.id); }
    }
  }
  report('prism', { passed: true, services: [...new Set(scenarios.map(item => item.service))], results });
  console.log(`Prism: ${results.length} exact example responses passed`);
}
module.exports = { main, validateCoverage, selectScenarios };
if (require.main === module) main().catch(error => { report('prism', { passed: false, error: error.message }); console.error(error); process.exitCode = 1; });
