const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { root, services, delay } = require('./runtime.cjs');
const { load, escape } = require('../contracts/lib.cjs');
class Context {
  constructor(runtime) {
    this.runtime = runtime;
    this.contracts = Object.fromEntries(services.map(service => [service, load(path.join(root, `contracts/api/${service}-service.yaml`))]));
    this.result = { sha: runtime.sha, startedAt: new Date().toISOString(), runtime: runtime.mode, checks: [],
      contracts: Object.fromEntries(services.map(s => [s, this.contracts[s].sha256])),
      mocks: { payments: 'existing MockPaymentEngine', ivs: 'explicit local IVS stub', internalHttp: 'real services; no Prism fallback' },
      excludedInfrastructure: ['Kafka: no current producer/consumer usage in the four services; listener/admin auto-start disabled', 'Gateway', 'Frontend', 'deployed Kubernetes', 'Social login and email ownership verification: explicitly excluded from P2'],
      tokenPolicy: { access: 'PT15M', refresh: 'P30D', refreshExpiry: 'absolute from login',
        ordinaryLogout: 'refresh family only', securityRevocation: 'reject subsequent authentication checks' },
      contractCoverage: { matched: 0, excluded: [], unmatched: [] }, deferred: [] };
  }
  check(name, actual, expected = true) {
    assert(!this.result.checks.some(c => c.name === name), 'Duplicate check name: ' + name);
    let passed = true;
    try { assert.deepEqual(actual, expected); } catch { passed = false; }
    this.result.checks.push({ name, passed, actual: this.runtime.sanitize(actual), expected: this.runtime.sanitize(expected) });
    this.save();
    assert(passed, 'Invariant failed: ' + name);
  }
  validate(service, method, requestPath, status, body, contentType) {
    const pathname = new URL(requestPath, 'http://integration.invalid').pathname;
    const contract = this.contracts[service];
    const candidates = Object.keys(contract.doc.paths).sort((a, b) => (a.match(/\{/g) || []).length - (b.match(/\{/g) || []).length);
    const template = candidates.find(p => new RegExp('^' + p.split('/').map(part => part.startsWith('{') ? '[^/]+' : part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('/') + '$').test(pathname));
    const operation = template && contract.doc.paths[template][method.toLowerCase()];
    const raw = operation?.responses[String(status)] || operation?.responses.default;
    if (!raw) {
      const entry = { service, method, path: pathname, status };
      if (pathname.startsWith('/actuator/') || pathname.startsWith('/v1/dev/')) {
        this.result.contractCoverage.excluded.push({ ...entry, reason: 'management or explicitly enabled test control is outside public OpenAPI' });
        return;
      }
      this.result.contractCoverage.unmatched.push(entry);
      throw new Error(`No response contract for ${service} ${method} ${pathname} ${status}`);
    }
    const response = raw.$ref ? contract.resolve(raw.$ref) : raw;
    if (status === 204) assert.equal(body, null, '204 response must be empty');
    else if (response.content?.['application/json']) {
      assert(contentType.includes('application/json'), 'JSON contract requires JSON response Content-Type');
      const pointer = (raw.$ref || `#/paths/${escape(template)}/${method.toLowerCase()}/responses/${status}`) + '/content/application~1json/schema';
      const valid = contract.validator(pointer);
      if (!valid(body)) throw new Error(`Contract mismatch ${service} ${method} ${template} ${status}: ${JSON.stringify(valid.errors)}`);
    } else if (response.content?.['image/png']) assert(contentType.startsWith('image/'), 'Image contract requires image response');
    else assert(status === 204, 'Unsupported response media contract');
    this.result.contractCoverage.matched++;
  }
  async request(name, service, method, requestPath, options = {}) {
    assert(!this.result.checks.some(c => c.name === name), 'Duplicate request check: ' + name);
    const headers = { 'X-Request-Id': this.runtime.id + '-' + name, ...options.headers };
    if (options.token) headers.Authorization = 'Bearer ' + options.token;
    let body;
    if (Object.hasOwn(options, 'body')) {
      body = options.body instanceof FormData ? options.body : JSON.stringify(options.body);
      if (!(options.body instanceof FormData)) headers['Content-Type'] = 'application/json';
    }
    const expected = options.status ?? 200;
    const record = { name, service, method, path: requestPath, expected, passed: false };
    this.result.checks.push(record);
    try {
      const begin = Date.now();
      const response = await fetch((options.management ? this.runtime.health[service] : this.runtime.urls[service]) + requestPath,
        { method, headers, body, signal: AbortSignal.timeout(15000) });
      record.status = response.status; record.elapsedMs = Date.now() - begin;
      const contentType = response.headers.get('content-type') || '';
      const bytes = Buffer.from(await response.arrayBuffer());
      const parsed = !bytes.length ? null : contentType.includes('json') ? JSON.parse(bytes.toString()) :
        contentType.startsWith('image/') ? { binaryBytes: bytes.length, sha256: crypto.createHash('sha256').update(bytes).digest('hex') } : bytes.toString();
      if (parsed?.data?.accessToken) this.runtime.remember(parsed.data.accessToken, parsed.data.refreshToken);
      record.body = this.runtime.sanitize(parsed);
      assert(Array.isArray(expected) ? expected.includes(response.status) : response.status === expected, `${name}: unexpected HTTP status ${response.status}`);
      this.validate(service, method, requestPath, response.status, parsed, contentType);
      record.passed = true;
      console.log(`${name}: ${response.status}`);
      return parsed;
    } catch (error) {
      record.error = this.runtime.sanitize(error.message);
      throw error;
    } finally { this.save(); }
  }
  async poll(name, service, requestPath, token, matches, timeout = 5000) {
    const until = Date.now() + timeout; let attempt = 0;
    while (Date.now() < until) {
      const body = await this.request(`${name}-${++attempt}`, service, 'GET', requestPath, { token });
      if (matches(body)) return body;
      await delay(100);
    }
    this.check(name + '-completed', false);
  }
  save() {
    const r = this.result;
    r.executed = r.checks.length; r.failed = r.checks.filter(c => !c.passed).length; r.skipped = 0;
    r.passed = r.executed > 0 && r.failed === 0 && !r.error;
    r.p2Complete = r.passed && r.scope === 'full' && r.deferred.length === 0;
    fs.writeFileSync(path.join(this.runtime.output, 'summary.json'), JSON.stringify(this.runtime.sanitize(r), null, 2) + '\n');
  }
}
module.exports = { Context };
