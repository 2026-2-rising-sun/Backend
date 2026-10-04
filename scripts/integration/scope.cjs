const assert = require('node:assert/strict');
const scopes = Object.freeze({
  full: { commerce: true, mocks: ['shopping', 'commerce'], dependencies: true,
    databases: ['member', 'shopping', 'commerce', 'live'], sessions: true },
  shopping: { commerce: true, mocks: ['shopping'], dependencies: true, databases: ['shopping'], sessions: false },
  commerce: { commerce: true, mocks: ['commerce'], dependencies: true, databases: ['commerce'], sessions: false },
  live: { commerce: false, mocks: [], dependencies: false, databases: ['live'], sessions: false }
});
function selectScope(name = 'full') {
  assert(Object.hasOwn(scopes, name), 'Unknown integration scope: ' + name);
  return structuredClone(scopes[name]);
}
module.exports = { selectScope };
