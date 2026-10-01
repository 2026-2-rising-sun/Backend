const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const { isDeepStrictEqual } = require('node:util');
const YAML = require('yaml');
const { root, files, report, escape } = require('./lib.cjs');

// Object-key order/comments are irrelevant; array order and every parsed value remain significant.
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  }
  return value;
}
const hash = value => crypto.createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
function parse(source, file) {
  const doc = YAML.parseDocument(source, { uniqueKeys: true });
  if (doc.errors.length) throw new Error(`${file}: ${doc.errors.map(error => error.message).join('; ')}`);
  return doc.toJS();
}
function pointers(before, after, pointer = '') {
  if (isDeepStrictEqual(before, after)) return [];
  if (before === null || after === null || typeof before !== 'object' || typeof after !== 'object'
      || Array.isArray(before) || Array.isArray(after)) return [pointer || '/'];
  return [...new Set([...Object.keys(before), ...Object.keys(after)])].sort().flatMap(key =>
    pointers(before[key], after[key], `${pointer}/${escape(key)}`));
}
function snapshot(documents) {
  return Object.fromEntries(Object.keys(documents).sort().map(file => [file, hash(documents[file])]));
}
function compare(before, after, reviews) {
  const baseFiles = snapshot(before);
  const headFiles = snapshot(after);
  const baseSnapshotSha256 = hash(baseFiles);
  const headSnapshotSha256 = hash(headFiles);
  const changes = [...new Set([...Object.keys(before), ...Object.keys(after)])].sort()
    .filter(file => baseFiles[file] !== headFiles[file]).map(file => ({
      file, kind: !(file in before) ? 'added' : !(file in after) ? 'removed' : 'changed',
      baseSha256: baseFiles[file] || null, headSha256: headFiles[file] || null,
      pointers: pointers(before[file], after[file])
    }));
  const nonempty = value => typeof value === 'string' && value.trim().length > 0;
  const review = reviews.find(item => item.baseSnapshotSha256 === baseSnapshotSha256
    && item.headSnapshotSha256 === headSnapshotSha256
    && nonempty(item.reason) && nonempty(item.independentReview?.reviewer)
    && nonempty(item.independentReview?.evidence) && nonempty(item.independentReview?.reviewedAt));
  return { passed: changes.length === 0 || !!review, baseSnapshotSha256, headSnapshotSha256,
    baseFiles, headFiles, changes, acknowledgment: review || null,
    scope: 'Conservative structural change review record; not compatibility proof or GitHub approval verification.' };
}
function git(...args) {
  return execFileSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
}
function main(args) {
  if (args.length !== 2 || args[0] !== '--base' || !args[1]) {
    throw new Error('Usage: node scripts/contracts/base-diff.cjs --base <commit-or-tree>');
  }
  const baseRef = args[1];
  // Resolve once without shell interpolation. An empty tree object is valid as a baseline.
  const baseTree = git('rev-parse', '--verify', '--end-of-options', `${baseRef}^{tree}`).trim();
  const before = {};
  for (const file of git('ls-tree', '-r', '--name-only', '-z', baseTree, '--', 'contracts/api').split('\0')) {
    if (/^contracts\/api\/[^/]+\.yaml$/.test(file)) before[file] = parse(git('show', `${baseTree}:${file}`), file);
  }
  const after = {};
  for (const file of files()) {
    const relative = path.relative(root, file).split(path.sep).join('/');
    after[relative] = parse(fs.readFileSync(file, 'utf8'), relative);
  }
  const reviewPath = path.join(root, 'contracts/docs/change-review.json');
  const record = fs.existsSync(reviewPath) ? JSON.parse(fs.readFileSync(reviewPath, 'utf8')) : { formatVersion: 1, reviews: [] };
  if (record.formatVersion !== 1 || !Array.isArray(record.reviews)) throw new Error('Invalid change-review.json format');
  const result = { baseRef, baseTree, headCommit: git('rev-parse', 'HEAD').trim(),
    headSource: 'checked-out files', ...compare(before, after, record.reviews) };
  report('base-diff', result);
  console.log(JSON.stringify(result, null, 2));
  if (!result.passed) process.exitCode = 1;
}
if (require.main === module) {
  try { main(process.argv.slice(2)); }
  catch (error) { report('base-diff', { passed: false, error: error.message }); console.error(error.message); process.exitCode = 1; }
}
module.exports = { compare, parse };
