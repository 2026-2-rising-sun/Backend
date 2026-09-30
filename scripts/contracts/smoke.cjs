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
        const media = response.content['application/json'];
        const selected = media.examples[scenario.example];
        const expected = selected.$ref ? resolve(selected.$ref).value : selected.value;
        const pointer = raw.$ref || `#/paths/${escape(scenario.contractPath)}/${method}/responses/${scenario.code}`;
        const validate = validator(pointer + '/content/application~1json/schema');
        const prefer = `code=${scenario.code}, example=${scenario.example}`;
        const actual = await fetch(server.url + scenario.path, { method, headers: { ...scenario.headers, Prefer: prefer }, signal: AbortSignal.timeout(5000) });
        const body = await actual.json();
        assert.equal(actual.status, scenario.code);
        assert.deepEqual(body, expected);
        assert.ok(validate(body), JSON.stringify(validate.errors));
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
