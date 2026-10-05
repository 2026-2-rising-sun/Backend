const test = require('node:test');
const assert = require('node:assert/strict');
const { compare, parse } = require('./base-diff.cjs');

test('comments and object key order are equal; array ordering still requires review', () => {
  const a = { 'a.yaml': parse('x: [USER, SELLER]\ny: true # comment', 'a') };
  const b = { 'a.yaml': parse('y: true\nx: [USER, SELLER]', 'b') };
  assert.equal(compare(a, b, []).passed, true);
  b['a.yaml'].x.reverse();
  assert.equal(compare(a, b, []).passed, false);
});
test('file addition, deletion and permission changes fail without an exact review record', () => {
  const before = { 'old.yaml': { paths: {} }, 'api.yaml': { security: [] } };
  const after = { 'new.yaml': { paths: {} }, 'api.yaml': { security: [{ BearerAuth: [] }] } };
  const diff = compare(before, after, []);
  assert.equal(diff.passed, false);
  assert.deepEqual(diff.changes.map(item => item.kind).sort(), ['added', 'changed', 'removed']);
  const review = { baseSnapshotSha256: diff.baseSnapshotSha256, headSnapshotSha256: diff.headSnapshotSha256,
    reason: 'Test fixture for intentional authentication change', independentReview: {
      reviewer: 'test reviewer', evidence: 'test-only review record', reviewedAt: '2026-10-01' } };
  assert.equal(compare(before, after, [review]).passed, true);
  assert.equal(compare(after, before, [review]).passed, false);
  assert.equal(compare({}, after, [review]).passed, false);
  assert.equal(compare(before, { ...after, 'extra.yaml': {} }, [review]).passed, false);
  assert.equal(compare(before, after, [{ ...review, independentReview: {} }]).passed, false);
});
test('empty baseline additions require review and duplicate YAML keys fail closed', () => {
  assert.equal(compare({}, {}, []).passed, true);
  assert.equal(compare({}, { 'member.yaml': {} }, []).passed, false);
  assert.throws(() => parse('security: []\nsecurity: []', 'duplicate'), /unique/i);
});
