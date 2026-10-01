const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { root, load, escape, report } = require('./lib.cjs');
const { mock } = require('../local/contracts.cjs');
const { docker, stop, ready } = require('./docker.cjs');
const scenarios = require('../../contracts/scenarios/prism.json');
async function main() {
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
  report('prism', { passed: true, results });
  console.log(`Prism: ${results.length} exact example responses passed`);
}
main().catch(error => { report('prism', { passed: false, error: error.message }); console.error(error); process.exitCode = 1; });
