const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { isDeepStrictEqual: equal } = require('node:util');
const { load, escape } = require('./lib.cjs');
const methods = new Set(['get', 'post', 'put', 'patch', 'delete', 'options', 'head', 'trace']);
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const has = (obj, key) => Object.hasOwn(obj || {}, key);
const object = value => value && typeof value === 'object' && !Array.isArray(value);
function resolve(doc, ref) {
  if (!ref.startsWith('#/')) throw new Error('Only local references can be normalized');
  return ref.slice(2).split('/').reduce((node, part) => {
    const key = part.replaceAll('~1', '/').replaceAll('~0', '~');
    if (!has(node, key)) throw new Error('Unresolved reference: ' + ref);
    return node[key];
  }, doc);
}
function differences(a, b, pointer = '#') {
  if (equal(a, b)) return [];
  if (object(a) && object(b)) return [...new Set([...Object.keys(a), ...Object.keys(b)])].sort()
    .flatMap(key => differences(a[key], b[key], pointer + '/' + escape(key)));
  return [pointer];
}
function inventory(doc) {
  const operations = [];
  for (const [url, item] of Object.entries(doc.paths || {})) for (const [method, op] of Object.entries(item)) {
    if (methods.has(method)) operations.push({ method, url, id: op.operationId });
  }
  return { operations, schemas: Object.keys(doc.components?.schemas || {}).sort(),
    securitySchemes: Object.keys(doc.components?.securitySchemes || {}).sort() };
}
function childContext(ctx, key) {
  const maps = { paths: 'path', schemas: 'schema', responses: 'response', parameters: 'parameter',
    securitySchemes: 'securityScheme', content: 'media', examples: 'example' };
  if (ctx.endsWith('Map')) return maps[ctx.slice(0, -3)] || 'opaque';
  if (ctx === 'path' && methods.has(key)) return 'operation';
  if (ctx === 'root') return ({ paths: 'pathsMap', components: 'components', tags: 'tags' })[key] || 'opaque';
  if (ctx === 'components') return maps[key] ? key + 'Map' : 'opaque';
  if (ctx === 'operation') return ({ parameters: 'parameterArray', responses: 'responsesMap',
    requestBody: 'requestBody', security: 'securityArray' })[key] || 'opaque';
  if (['parameter', 'media', 'response', 'requestBody'].includes(ctx)) {
    return ({ schema: 'schema', content: 'contentMap', examples: 'examplesMap' })[key] || 'opaque';
  }
  if (ctx === 'schema') {
    if (['properties', 'patternProperties', '$defs', 'dependentSchemas'].includes(key)) return 'schemasMap';
    if (['allOf', 'anyOf', 'oneOf', 'prefixItems'].includes(key)) return 'schemaArray';
    if (['items', 'contains', 'not', 'if', 'then', 'else', 'additionalProperties', 'propertyNames'].includes(key)) return 'schema';
  }
  return 'opaque';
}
function normalize(approved, exported, validator, removals = []) {
  const changes = [], pendingEmptyExamples = [], used = new Set();
  const review = new Map(removals.map(item => [item.pointer, item]));
  if (review.size !== removals.length) throw new Error('Duplicate reviewed pointer');
  const approvedIds = inventory(approved).operations.map(op => op.id);
  const exportedIds = inventory(exported).operations.map(op => op.id);
  if ([approvedIds, exportedIds].some(ids => ids.some(id => !id) || new Set(ids).size !== ids.length)) {
    throw new Error('Every operation requires a unique operationId');
  }
  function record(pointer, rule) { changes.push({ pointer, rule }); }
  function walk(source, raw, pointer, ctx, name) {
    if (ctx === 'opaque' || raw === null || typeof raw !== 'object') return structuredClone(raw);
    if (Array.isArray(raw)) {
      if (ctx === 'tags') {
        const tags = raw.map(tag => structuredClone(tag));
        for (const tag of tags) {
          const original = source?.find(t => t.name === tag.name);
          if (original?.description && !has(tag, 'description')) {
            tag.description = original.description; record(pointer, 'restore-tag-description');
          }
        }
        if (source?.length === tags.length && new Set(tags.map(t => t.name)).size === tags.length
          && source.every(t => tags.some(x => x.name === t.name))) {
          const ordered = source.map(t => tags.find(x => x.name === t.name));
          if (!equal(tags, ordered)) record(pointer, 'restore-tag-order');
          return ordered;
        }
        return tags;
      }
      const itemCtx = ({ parameterArray: 'parameter', securityArray: 'securityRequirement', schemaArray: 'schema' })[ctx];
      if (!itemCtx) return structuredClone(raw);
      return raw.map((item, i) => walk(source?.[i], item, pointer + '/' + i, itemCtx));
    }
    if (source?.$ref && !raw.$ref) {
      const effective = { ...resolve(approved, source.$ref), ...source }; delete effective.$ref;
      const candidate = walk(effective, raw, pointer, ctx, name);
      if (equal(candidate, effective)) { record(pointer, 'restore-equivalent-reference'); return structuredClone(source); }
      return candidate;
    }
    const result = Object.fromEntries(Object.entries(raw).map(([key, value]) => [key,
      walk(source?.[key], value, pointer + '/' + escape(key), childContext(ctx, key), key)]));
    const drop = (key, rule) => { delete result[key]; record(pointer + '/' + escape(key), rule); };
    const added = key => !has(source, key) && has(result, key);
    if (ctx === 'operation' && source?.operationId === raw.operationId && !has(raw, 'servers') && has(source, 'servers')) {
      result.servers = structuredClone(source.servers); record(pointer + '/servers', 'restore-missing-operation-servers');
    }
    if (ctx === 'operation') {
      if (added('deprecated') && result.deprecated === false) drop('deprecated', 'default-false');
      if (added('parameters') && equal(result.parameters, [])) drop('parameters', 'empty-parameters');
      if (added('x-apidog-status') && result['x-apidog-status'] === 'released') drop('x-apidog-status', 'ui-status');
      if (added('x-run-in-apidog') && /^https:\/\/app\.apidog\.com\/web\/project\/\d+\/apis\/api-\d+-run$/.test(result['x-run-in-apidog'])) drop('x-run-in-apidog', 'ui-run-link');
      if (source?.['x-apidog-folder'] && result['x-apidog-folder'] === '루트/' + source['x-apidog-folder']) {
        result['x-apidog-folder'] = source['x-apidog-folder']; record(pointer + '/x-apidog-folder', 'ui-root-folder-prefix');
      }
    }
    if (['schema', 'securityScheme'].includes(ctx) && added('x-apidog-folder')
      && ['', '루트'].includes(result['x-apidog-folder'])) drop('x-apidog-folder', 'ui-component-folder');
    if (ctx === 'schema') {
      if (added('x-apidog-orders') && Array.isArray(result['x-apidog-orders'])
        && result['x-apidog-orders'].every(v => typeof v === 'string')) drop('x-apidog-orders', 'ui-property-order');
      if (added('x-apidog-ignore-properties') && equal(result['x-apidog-ignore-properties'], [])) drop('x-apidog-ignore-properties', 'ui-empty-ignore-list');
      if (added('properties') && equal(result.properties, {})) drop('properties', 'empty-properties');
    }
    if (ctx === 'securityRequirement' && added('x-apidog') && object(result['x-apidog'])) drop('x-apidog', 'ui-security-configuration');
    if (['operation', 'parameter'].includes(ctx) && added('description') && result.description === '') drop('description', 'empty-description');
    if (ctx === 'response') {
      if (added('headers') && equal(result.headers, {})) drop('headers', 'empty-headers');
      if (added('x-apidog-ordering') && Number.isInteger(result['x-apidog-ordering'])) drop('x-apidog-ordering', 'ui-response-order');
      if (source?.$ref && source.$ref === raw.$ref && added('description')
        && result.description === resolve(approved, source.$ref).description) drop('description', 'duplicate-reference-description');
    }
    if (ctx === 'root') for (const [key, empty] of [['servers', []], ['webhooks', {}]]) {
      if (added(key) && equal(result[key], empty)) drop(key, 'empty-root-' + key);
    }
    if (ctx === 'parameter' && added('required') && result.required === false) drop('required', 'default-optional-parameter');
    if (ctx === 'requestBody' && source?.required === false && !has(result, 'required')) {
      result.required = false; record(pointer + '/required', 'default-optional-body');
    }
    if (ctx === 'example' && added('summary') && result.summary === name) drop('summary', 'ui-example-summary');
    if (ctx === 'media' && has(source, 'example') && !has(raw, 'example') && !has(source, 'examples')
      && equal(result.examples, { 성공: { value: source.example } })) {
      delete result.examples; result.example = structuredClone(source.example); record(pointer, 'restore-single-example');
    }
    // Payload values remain opaque; only observed added empty placeholders are eligible.
    const uploadFile = ctx === 'schema' && pointer.endsWith('/content/multipart~1form-data/schema/properties/file')
      && raw.type === 'string' && raw.format === 'binary';
    if ((['parameter', 'media'].includes(ctx) && raw.schema || uploadFile) && added('example')) {
      const empty = result.example === '' || equal(result.example, {})
        || (ctx === 'media' && pointer.endsWith('/multipart~1form-data') && equal(result.example, { file: '' }));
      if (empty) {
        const examplePointer = pointer + '/example';
        if (!validator(uploadFile ? pointer : pointer + '/schema')(result.example)) drop('example', 'remove-invalid-added-empty-example');
        else if (review.has(examplePointer) && equal(review.get(examplePointer).value, result.example)) {
          drop('example', 'reviewed-added-empty-example'); used.add(examplePointer);
        } else pendingEmptyExamples.push({ pointer: examplePointer, value: result.example });
      }
    }
    return result;
  }
  const normalized = walk(approved, exported, '#', 'root');
  for (const pointer of review.keys()) if (!used.has(pointer)) throw new Error('Review entry was not an exact eligible transformation: ' + pointer);
  const remaining = differences(approved, normalized);
  return { normalized, changes, pendingEmptyExamples, differences: remaining, passed: !remaining.length,
    inventoryBefore: inventory(approved), inventoryAfter: inventory(normalized) };
}
function createArtifact(approvedFile, exportedFile, output, reviewFile) {
  const approved = load(approvedFile), exported = load(exportedFile);
  let review = { removals: [] };
  if (reviewFile) {
    review = JSON.parse(fs.readFileSync(reviewFile, 'utf8'));
    if (review.approvedSha256 !== approved.sha256 || review.exportedSha256 !== exported.sha256
      || !Array.isArray(review.removals) || !['reviewer', 'reason', 'evidence'].every(k => typeof review[k] === 'string' && review[k].trim())) {
      throw new Error('Review requires exact input hashes, removals, reviewer, reason and evidence');
    }
  }
  const result = normalize(approved.doc, exported.doc, exported.validator, review.removals);
  const text = JSON.stringify(result.normalized, null, 2) + '\n';
  const reportFile = output.replace(/\.json$/, '') + '.normalization.json';
  const report = { approvedFile, exportedFile, approvedSha256: approved.sha256, exportedSha256: exported.sha256,
    normalizedSha256: hash(text), reviewFile: reviewFile || null, reviewSha256: reviewFile ? hash(fs.readFileSync(reviewFile)) : null,
    ...result }; delete report.normalized;
  // Exclusive creation preserves raw exports, baselines and previous review evidence.
  if (fs.existsSync(output) || fs.existsSync(reportFile)) throw new Error('Output/report already exists; choose new output paths');
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, text, { flag: 'wx' });
  fs.writeFileSync(reportFile, JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
  return { output, reportFile, ...report };
}
if (require.main === module) {
  const args = process.argv.slice(2), options = {};
  for (let i = 0; i < args.length; i += 2) {
    if (!['--approved', '--export', '--output', '--review'].includes(args[i]) || !args[i + 1] || options[args[i]]) throw new Error('Usage: apidog-normalize.cjs --approved bundle.json --export raw.json --output new.json [--review review.json]');
    options[args[i]] = path.resolve(args[i + 1]);
  }
  if (!['--approved', '--export', '--output'].every(k => options[k])) throw new Error('Approved, export and output paths are required');
  const result = createArtifact(options['--approved'], options['--export'], options['--output'], options['--review']);
  console.log(JSON.stringify({ output: result.output, reportFile: result.reportFile, passed: result.passed,
    transformations: result.changes.length, differences: result.differences, pendingEmptyExamples: result.pendingEmptyExamples }, null, 2));
  process.exitCode = result.passed ? 0 : 1;
}
module.exports = { normalize, createArtifact, differences };
