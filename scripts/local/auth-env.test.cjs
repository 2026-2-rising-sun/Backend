const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');
const command = path.join(__dirname, 'auth-env.cjs');
const run = (args, env = process.env) => spawnSync(process.execPath, [command, ...args], { env, encoding: 'utf8' });

test('local keys match; missing/invalid TTL and credential overwrites fail', () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'p2-auth-env-test-'));
  const dest = path.join(temp, 'keys');
  try {
    for (const ttl of [undefined, 'P1DT', 'PT', 'P0D']) {
      const args = ttl ? ['--access-ttl', ttl, '--refresh-ttl', 'P30D'] : [];
      assert.equal(run(['create', ...args, '--out', dest]).status, 1);
      assert.equal(fs.existsSync(dest), false);
    }
    const args = ['create', '--access-ttl', 'PT15M', '--refresh-ttl', 'P30D', '--out', dest];
    assert.equal(run(args).status, 0);
    const envPath = path.join(dest, 'env.json');
    const saved = fs.readFileSync(envPath, 'utf8');
    const { env } = JSON.parse(saved);
    const jwk = JSON.parse(fs.readFileSync(env.MEMBER_JWT_PUBLIC_KEY_SET_LOCATION.slice(5))).keys[0];
    const pem = fs.readFileSync(env.MEMBER_JWT_PRIVATE_KEY_LOCATION.slice(5));
    const payload = Buffer.from('local verifier test');
    assert.ok(crypto.verify('sha256', payload, crypto.createPublicKey({ key: jwk, format: 'jwk' }), crypto.sign('sha256', payload, pem)));
    assert.equal(jwk.kid, env.MEMBER_JWT_KEY_ID);
    assert.equal(fs.statSync(envPath).mode & 0o777, 0o600);
    assert.equal(fs.statSync(dest).mode & 0o777, 0o700);
    const tokens = Object.entries(env).filter(([key]) => key.endsWith('_SERVICE_TOKEN')).map(([, value]) => value);
    assert.equal(new Set(tokens).size, 7);
    assert.equal(env.MEMBER_SESSION_BASE_URL, 'http://localhost:8081');
    assert.equal(run(args).status, 1);
    assert.equal(fs.readFileSync(envPath, 'utf8'), saved);
    // Even pre-existing parent secrets must not reach a different service child.
    const child = run(['exec', 'commerce', '--env', envPath, '--', process.execPath, '-e', `
      const a=require('node:assert/strict');
      for (const key of ['MEMBER_JWT_PRIVATE_KEY_LOCATION','MEMBER_JWT_KEY_ID','MEMBER_ACCESS_TOKEN_TTL','MEMBER_REFRESH_TOKEN_TTL','LIVE_SHOPPING_SERVICE_TOKEN','SHOPPING_MEMBER_SERVICE_TOKEN','LIVE_MEMBER_SERVICE_TOKEN']) a.equal(process.env[key],undefined);
      for (const key of ['MEMBER_JWT_PUBLIC_KEY_SET_LOCATION','MEMBER_SESSION_BASE_URL','COMMERCE_MEMBER_SERVICE_TOKEN','COMMERCE_SHOPPING_SERVICE_TOKEN','SHOPPING_COMMERCE_SERVICE_TOKEN','LIVE_COMMERCE_SERVICE_TOKEN']) a.ok(process.env[key]);
    `], { ...process.env, MEMBER_JWT_PRIVATE_KEY_LOCATION: 'ambient-private-secret', LIVE_SHOPPING_SERVICE_TOKEN: 'ambient-other-service-secret', SHOPPING_MEMBER_SERVICE_TOKEN: 'ambient-member-caller-secret' });
    assert.equal(child.status, 0, child.stderr);
    const member = run(['exec', 'member', '--env', envPath, '--', process.execPath, '-e', `
      const a=require('node:assert/strict');
      for (const key of ['MEMBER_SESSION_BASE_URL','SHOPPING_COMMERCE_SERVICE_TOKEN','COMMERCE_SHOPPING_SERVICE_TOKEN','LIVE_SHOPPING_SERVICE_TOKEN','LIVE_COMMERCE_SERVICE_TOKEN']) a.equal(process.env[key],undefined);
      for (const key of ['MEMBER_JWT_PRIVATE_KEY_LOCATION','SHOPPING_MEMBER_SERVICE_TOKEN','COMMERCE_MEMBER_SERVICE_TOKEN','LIVE_MEMBER_SERVICE_TOKEN']) a.ok(process.env[key]);
    `], { ...process.env, MEMBER_SESSION_BASE_URL: 'http://untrusted.invalid', SHOPPING_COMMERCE_SERVICE_TOKEN: 'ambient-other-target-secret' });
    assert.equal(member.status, 0, member.stderr);
  } finally { fs.rmSync(temp, { recursive: true, force: true }); }
});

test('malformed credential JSON never appears in diagnostic output', () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'p2-auth-env-invalid-'));
  try {
    const file = path.join(temp, 'env.json');
    fs.writeFileSync(file, '{"secret":"sensitive-config-fragment",BROKEN}', { mode: 0o600 });
    const result = run(['exec', 'commerce', '--env', file, '--', process.execPath, '-e', 'process.exit(99)']);
    assert.equal(result.status, 1);
    assert.equal(result.stderr.trim(), 'Cannot read or parse local credential configuration');
    assert.equal(result.stdout, '');
  } finally { fs.rmSync(temp, { recursive: true, force: true }); }
});

test('Member authority URL is explicit and never accepts embedded credentials', () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'p2-auth-env-url-'));
  try {
    const dest = path.join(temp, 'keys');
    for (const url of ['file:///tmp/auth', 'http://name:secret@localhost:8081', 'http://localhost:8081/internal']) {
      const result = run(['create', '--access-ttl', 'PT15M', '--refresh-ttl', 'P30D', '--member-url', url, '--out', dest]);
      assert.equal(result.status, 1); assert.equal(fs.existsSync(dest), false);
      assert.equal(result.stderr.includes('name:secret'), false);
    }
    assert.equal(run(['create', '--access-ttl', 'PT15M', '--refresh-ttl', 'P30D', '--member-url', 'http://member:8080', '--out', dest]).status, 0);
    assert.equal(JSON.parse(fs.readFileSync(path.join(dest, 'env.json'))).env.MEMBER_SESSION_BASE_URL, 'http://member:8080');
  } finally { fs.rmSync(temp, { recursive: true, force: true }); }
});
