#!/usr/bin/env node
// Foreground, disposable manual-review environment. No production or shared development resources.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { Runtime, root, command } = require('../integration/runtime.cjs');

async function main(args = process.argv.slice(2)) {
  assert(args.every(arg => ['--use-prebuilt', '--verify-setup'].includes(arg)), 'Unknown manual-review option');
  const base = path.join(root, 'build/manual-review');
  fs.mkdirSync(base, { recursive: true, mode: 0o700 });
  const directory = fs.mkdtempSync(path.join(base, 'run-'));
  fs.chmodSync(directory, 0o700);
  const runtime = new Runtime({ output: path.join(directory, 'logs'), localTestAccounts: true });
  const envFile = path.join(directory, 'env.sh');
  const jsonFile = path.join(directory, 'env.json');
  let cleanupPromise, keepAlive;
  const cleanup = () => cleanupPromise ||= (async () => {
    clearInterval(keepAlive);
    try {
      // Manual outage checks may leave a process paused; restore only owned resources before cleanup.
      for (const child of runtime.children.values()) if (child.exitCode === null && child.signalCode === null) child.kill('SIGCONT');
      for (const name of runtime.containers) if (runtime.owned(name)
        && JSON.parse(command('docker', ['inspect', name]))[0].State.Paused) command('docker', ['unpause', name]);
    } finally {
      try { await runtime.cleanup(); }
      finally { for (const file of [envFile, jsonFile]) fs.rmSync(file, { force: true }); }
    }
  })();
  const interrupt = () => { cleanup().then(() => process.exit(130), () => process.exit(1)); };
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  try {
    await runtime.build(args.includes('--use-prebuilt'));
    await runtime.setup();
    const env = { REVIEW_ID: runtime.id, POSTGRES_CONTAINER: runtime.pg };
    for (const service of ['member', 'shopping', 'commerce', 'live']) {
      env[service.toUpperCase() + '_URL'] = runtime.urls[service];
      env[service.toUpperCase() + '_HEALTH_URL'] = runtime.health[service];
      if (runtime.mode === 'docker') env[service.toUpperCase() + '_CONTAINER'] = runtime.id + '-' + service;
      else env[service.toUpperCase() + '_PID'] = String(runtime.children.get(service).pid);
    }
    async function post(operation, body, expected) {
      const response = await fetch(runtime.urls.member + '/v1/auth/' + operation, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
        signal: AbortSignal.timeout(10000)
      });
      assert.equal(response.status, expected, `Manual fixture ${operation} returned unexpected HTTP status`);
      const value = await response.json(); assert.equal(value.success, true);
      return value.data;
    }
    for (const [label, role] of [['USER_A', 'USER'], ['USER_B', 'USER'], ['SELLER', 'SELLER']]) {
      const fixed = label !== 'USER_B';
      const login = role === 'SELLER' ? 'seller' : 'user';
      const email = fixed ? login + '@local.test' : `${runtime.id}-user-b@example.test`;
      const password = fixed ? login : 'manual-' + crypto.randomBytes(18).toString('base64url');
      if (!fixed) {
        runtime.remember(password);
        await post('signup', { email, password, displayName: label }, 201);
      }
      const tokens = await post('login', { email, password }, 200);
      assert(tokens.accessToken && tokens.refreshToken, 'Manual login did not issue token pair');
      runtime.remember(tokens.accessToken, tokens.refreshToken);
      Object.assign(env, { [label + '_EMAIL']: email, [label + '_PASSWORD']: password,
        [label + '_ACCESS_TOKEN']: tokens.accessToken, [label + '_REFRESH_TOKEN']: tokens.refreshToken });
      const me = await fetch(runtime.urls.member + '/v1/members/me', {
        headers: { Authorization: 'Bearer ' + tokens.accessToken }, signal: AbortSignal.timeout(10000)
      });
      assert.equal(me.status, 200);
      const profile = (await me.json()).data;
      assert.deepEqual(profile.roles, [role]);
      env[label + '_ID'] = profile.memberId;
    }
    const quote = value => "'" + String(value).replace(/'/g, "'\\''") + "'";
    fs.writeFileSync(envFile, Object.entries(env).map(([key, value]) => `export ${key}=${quote(value)}`).join('\n') + '\n', { mode: 0o600, flag: 'wx' });
    fs.writeFileSync(jsonFile, JSON.stringify(env, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
    fs.writeFileSync(path.join(runtime.output, 'setup.json'), JSON.stringify({ sha: runtime.sha, runtime: runtime.mode,
      urls: runtime.urls, health: runtime.health, accounts: ['USER_A', 'USER_B', 'SELLER'],
      mocks: ['existing MockPaymentEngine', 'explicit local IVS stub'], automatedFlowVerification: false }, null, 2) + '\n');
    console.log(`Manual review ready at ${runtime.sha} (${runtime.mode}).`);
    console.log(`Shell environment (contains credentials; 0600): ${envFile}`);
    console.log(`Generic key/value JSON (not a tool-specific import format; 0600): ${jsonFile}`);
    console.log('Keep this process open. Ctrl-C removes only this run’s services, databases, keys and environment files.');
    if (!args.includes('--verify-setup')) {
      // Detached Docker services do not keep the parent Node event loop alive.
      keepAlive = setInterval(() => {}, 60000);
      await new Promise(() => {});
    }
  } finally {
    await cleanup();
    process.removeListener('SIGINT', interrupt); process.removeListener('SIGTERM', interrupt);
  }
}
module.exports = { main };
if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
