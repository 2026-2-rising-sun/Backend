const { isDeepStrictEqual } = require('node:util');
const { load, escape, report } = require('./lib.cjs');
const [approvedFile, exportedFile] = process.argv.slice(2);
if (!approvedFile || !exportedFile) throw new Error('Usage: compare.cjs <Git YAML> <Apidog export YAML>');
const approved = load(approvedFile), exported = load(exportedFile);
const differences = [];
function compare(left, right, pointer) {
  if (isDeepStrictEqual(left, right)) return;
  if (left && right && typeof left === 'object' && typeof right === 'object'
    && !Array.isArray(left) && !Array.isArray(right)) {
    for (const key of [...new Set([...Object.keys(left), ...Object.keys(right)])].sort()) {
      compare(left[key], right[key], pointer + '/' + escape(key));
    }
  } else differences.push(pointer);
}
function operations(doc) {
  return Object.entries(doc.paths).flatMap(([url, item]) => Object.keys(item)
    .filter(method => /^(get|post|put|patch|delete|options|head|trace)$/.test(method))
    .map(method => method.toUpperCase() + ' ' + url)).sort();
}
compare(approved.doc, exported.doc, '#');
const before = operations(approved.doc), after = operations(exported.doc);
const summary = { passed: !differences.length, approvedFile, exportedFile,
  approvedSha256: approved.sha256, exportedSha256: exported.sha256,
  operationsBefore: before.length, operationsAfter: after.length,
  removed: before.filter(item => !after.includes(item)), added: after.filter(item => !before.includes(item)),
  differences };
report('apidog-diff', summary);
console.log(JSON.stringify(summary, null, 2));
process.exitCode = summary.passed ? 0 : 1;
