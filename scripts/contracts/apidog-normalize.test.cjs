const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const Ajv = require('ajv/dist/2020').default;
const { normalize, createArtifact, differences } = require('./apidog-normalize.cjs');
const clone = structuredClone;
function fixture() {
  return { openapi: '3.1.0', info: { title: 'Fixture', version: '1' }, security: [],
    tags: [{ name: 'Products', description: 'Approved tag' }], components: {
      securitySchemes: { Caller: { type: 'apiKey', in: 'header', name: 'X-Service-Token' } },
      schemas: { Product: { type: ['object', 'null'], properties: { id: { type: 'integer', minimum: 1 } }, required: ['id'] } },
      parameters: { Id: { name: 'id', in: 'path', required: true, schema: { type: 'integer', minimum: 1 } } },
      responses: { Missing: { description: 'Missing' } } }, paths: { '/products/{id}': { get: {
        operationId: 'Shopping_product', servers: [{ url: 'http://localhost:8082' }],
        security: [{ Caller: [] }], 'x-allowed-callers': ['commerce'], 'x-apidog-folder': 'Shopping',
        parameters: [{ $ref: '#/components/parameters/Id' }], responses: {
          200: { description: 'Success', content: { 'application/json': {
            schema: { $ref: '#/components/schemas/Product' }, example: { id: 1 } } } },
          404: { $ref: '#/components/responses/Missing' } } } } } };
}
const operation = doc => doc.paths['/products/{id}'].get;
const media = doc => operation(doc).responses[200].content['application/json'];
function run(a, b, removals = []) {
  const ajv = new Ajv({ strict: false }); ajv.addSchema(b, 'https://test.invalid/spec');
  return normalize(a, b, pointer => ajv.compile({ $ref: 'https://test.invalid/spec' + pointer }), removals);
}
function exported(a) {
  const b = clone(a), op = operation(b);
  delete op.servers; op.deprecated = false; op['x-apidog-folder'] = '루트/Shopping';
  op['x-apidog-status'] = 'released'; op['x-run-in-apidog'] = 'https://app.apidog.com/web/project/123/apis/api-456-run';
  op.security[0]['x-apidog'] = { required: true, use: {} };
  op.parameters = [{ ...clone(a.components.parameters.Id), example: '', description: '' }];
  op.responses[404].description = 'Missing'; op.responses[404]['x-apidog-ordering'] = 1;
  media(b).examples = { 성공: { value: media(b).example, summary: '성공' } }; delete media(b).example;
  b.components.schemas.Product['x-apidog-orders'] = ['id']; b.components.schemas.Product['x-apidog-ignore-properties'] = [];
  delete b.tags[0].description; b.servers = []; b.webhooks = {};
  return b;
}
test('known UI transformations restore exact structure, including references and servers', () => {
  const a = fixture(), b = exported(a), original = clone(b), result = run(a, b);
  assert.equal(result.passed, true); assert.deepEqual(result.normalized, a);
  assert.deepEqual(b, original); assert.deepEqual(result.inventoryBefore, result.inventoryAfter);
  assert.ok(result.changes.some(c => c.rule === 'remove-invalid-added-empty-example'));
});
const meaningful = {
  'changed explicit servers': b => { operation(b).servers = [{ url: 'https://different.test' }]; },
  'explicit empty servers': b => { operation(b).servers = []; },
  'nullable removed': b => { b.components.schemas.Product.type = 'object'; },
  'required removed': b => { b.components.schemas.Product.required = []; },
  'constraint relaxed': b => { b.components.schemas.Product.properties.id.minimum = 0; },
  'schema property added': b => { b.components.schemas.Product.properties.extra = { type: 'string' }; },
  'authentication removed': b => { operation(b).security = []; },
  'scheme changed': b => { b.components.securitySchemes.Caller.name = 'X-Other'; },
  'caller widened': b => { operation(b)['x-allowed-callers'].push('unknown'); },
  'unknown extension added': b => { operation(b)['x-sensitive-policy'] = 'public'; },
  'example value changed': b => { media(b).examples.성공.value.id = 2; },
  'nonempty example added': b => { operation(b).parameters[0].example = 42; },
  'parameter type changed': b => { operation(b).parameters[0].schema.type = 'number'; },
  'operation renamed': b => { operation(b).operationId = 'Changed'; },
  'response removed': b => { delete operation(b).responses[404]; },
};
for (const [name, change] of Object.entries(meaningful)) test(name + ' remains a failing diff', () => {
  const a = fixture(), b = exported(a); change(b); const result = run(a, b);
  assert.equal(result.passed, false); assert.ok(result.differences.length);
});
test('example payloads and similarly named schema properties are opaque to metadata filtering', () => {
  const a = fixture(); a.components.schemas.Product.properties['x-apidog-orders'] = { type: 'string' };
  media(a).example = { id: 1, 'x-apidog-orders': 'original', example: { servers: [] } };
  const b = exported(a); media(b).examples.성공.value['x-apidog-orders'] = 'changed';
  const result = run(a, b);
  assert.equal(result.passed, false);
  assert.equal(result.normalized.components.schemas.Product.properties['x-apidog-orders'].type, 'string');
  assert.equal(media(result.normalized).examples.성공.value['x-apidog-orders'], 'changed');
});
test('valid added empty examples require exact reviewed pointer and value', () => {
  const a = fixture(); a.components.parameters.Id.schema = { type: 'string' };
  const b = exported(a), pending = run(a, b);
  assert.equal(pending.passed, false); assert.equal(pending.pendingEmptyExamples.length, 1);
  assert.equal(run(a, b, pending.pendingEmptyExamples).passed, true);
  assert.throws(() => run(a, b, [{ ...pending.pendingEmptyExamples[0], value: 'different' }]), /exact eligible/);
  operation(b).parameters[0].example = 'legitimate new example';
  assert.throws(() => run(a, b, pending.pendingEmptyExamples), /exact eligible/);
});
test('unknown vendor metadata and nonempty ignored-properties are preserved', () => {
  const a = fixture(), b = exported(a);
  b.components.schemas.Product['x-apidog-ignore-properties'] = ['id'];
  b.components.schemas.Product['x-other-format'] = { enabled: true };
  assert.equal(run(a, b).passed, false);
});
test('schema properties named __proto__ are preserved as own fields', () => {
  const a = fixture(), b = exported(a);
  b.components.schemas.Product.properties = JSON.parse('{"id":{"type":"integer","minimum":1},"__proto__":{"type":"string"}}');
  const result = run(a, b);
  assert.equal(result.passed, false);
  assert.equal(Object.hasOwn(result.normalized.components.schemas.Product.properties, '__proto__'), true);
});
test('added and removed operations or duplicate operationIds cannot pass', () => {
  const a = fixture(), b = exported(a);
  b.paths['/extra'] = { get: { ...clone(operation(b)), operationId: 'Extra' } };
  assert.equal(run(a, b).passed, false);
  b.paths['/extra'].get.operationId = operation(b).operationId;
  assert.throws(() => run(a, b), /unique operationId/);
  delete b.paths['/extra']; delete b.paths['/products/{id}']; assert.equal(run(a, b).passed, false);
});
test('CLI artifact reviews bind both input byte hashes; raw files and existing output stay intact', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'apidog-normalize-test-'));
  try {
    const a = fixture(); a.components.parameters.Id.schema = { type: 'string' };
    const b = exported(a); const approved = path.join(dir, 'approved.json'), raw = path.join(dir, 'raw.json');
    const reviewFile = path.join(dir, 'review.json'), output = path.join(dir, 'output.json');
    fs.writeFileSync(approved, JSON.stringify(a)); fs.writeFileSync(raw, JSON.stringify(b));
    const sha = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
    const before = sha(raw); const review = { approvedSha256: sha(approved), exportedSha256: before,
      reviewer: 'test reviewer', reason: 'test fixture only', evidence: 'independent test review',
      removals: run(a, b).pendingEmptyExamples };
    fs.writeFileSync(reviewFile, JSON.stringify(review));
    const result = createArtifact(approved, raw, output, reviewFile);
    assert.equal(result.passed, true); assert.equal(sha(raw), before);
    assert.throws(() => createArtifact(approved, raw, raw, reviewFile), /already exists/);
    assert.throws(() => createArtifact(approved, raw, output, reviewFile), /already exists/);
    fs.appendFileSync(raw, '\n');
    assert.throws(() => createArtifact(approved, raw, path.join(dir, 'new.json'), reviewFile), /exact input hashes/);
    assert.equal(fs.existsSync(path.join(dir, 'new.json')), false);
    assert.ok(differences(a, { ...a, security: [{ Caller: [] }] }).length);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});
