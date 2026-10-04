const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
function execute(file, values, status = 200, body = {}) {
  const environment = { get: key => values[key], set: (key, value) => { values[key] = value; }, unset: key => { delete values[key]; } };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, file), 'utf8'), {
    pm: { environment, response: { code: status, json: () => body } }
  });
}
test('short credentials map to unchanged email login; production cannot use test login', () => {
  for (const login of ['user', 'seller']) {
    const values = { test_mode: 'local', login_name: login, access_token: 'stale' };
    execute('login-before.js', values);
    assert.equal(values.login_email, login + '@local.test'); assert.equal(values.login_password, login);
    assert.equal(values.access_token, undefined);
  }
  const values = { test_mode: 'production', login_name: 'seller', access_token: 'stale' };
  assert.throws(() => execute('login-before.js', values)); assert.equal(values.access_token, undefined);
});
test('login/refresh replaces tokens; failed or malformed response cannot retain credentials', () => {
  const values = { access_token: 'old', refresh_token: 'old' };
  execute('tokens-after.js', values, 200, { success: true, data: { accessToken: 'new-access', refreshToken: 'new-refresh' } });
  assert.equal(values.access_token, 'new-access'); assert.equal(values.refresh_token, 'new-refresh');
  execute('tokens-after.js', values, 401); assert.equal(values.access_token, undefined);
  values.access_token = 'stale'; assert.throws(() => execute('tokens-after.js', values, 200, { success: true, data: {} }));
  assert.equal(values.access_token, undefined);
});
test('profile saves only authenticated role and logout clears both token slots', () => {
  const values = { access_token: 'access', refresh_token: 'refresh' };
  execute('profile-after.js', values, 200, { success: true, data: { memberId: 'member', roles: ['SELLER'] } });
  assert.equal(values.member_role, 'SELLER');
  assert.throws(() => execute('profile-after.js', values, 200, { success: true, data: { memberId: 'member', roles: ['ADMIN'] } }));
  assert.equal(values.member_role, undefined);
  execute('logout-after.js', values, 503); assert.equal(values.access_token, undefined); assert.equal(values.refresh_token, undefined);
});
