const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const { spawnSync } = require('node:child_process');
const { load, root } = require('./lib.cjs');
const { buildBundle, createArtifact } = require('./apidog-bundle.cjs');
const methods = ['get', 'post', 'put', 'patch', 'delete', 'options', 'head', 'trace'];
function fixture(name, route = '/v1/items') {
  return { name, doc: { openapi: '3.1.0', info: { title: name }, servers: [{ url: 'http://' + name.toLowerCase() + ':8080' }, { url: 'http://localhost:8081' }],
    security: [{ Bearer: [] }], tags: [{ name: 'Items' }], components: {
      securitySchemes: { Bearer: { type: 'http', scheme: 'bearer' }, Caller: { type: 'apiKey', in: 'header', name: 'X-Service-Token' } },
      schemas: { Item: { type: 'object' } }, responses: { Found: { description: 'ok', content: { 'application/json': { schema: { $ref: '#/components/schemas/Item' } } } } },
      parameters: { Id: { in: 'query', name: 'id', schema: { type: 'string' } } },
      headers: { Trace: { schema: { type: 'string' } } }, requestBodies: { Body: { content: { 'application/json': { schema: { $ref: '#/components/schemas/Item' } } } } },
      examples: { Literal: { value: { $ref: 'literal-data', operationId: 'do-not-prefix', security: [] } } },
      links: { Link: { operationId: 'list' } }, callbacks: { Done: { '{$request.query.callback}': { post: { operationId: 'callback', responses: { '204': { description: 'done' } } } } } },
      pathItems: { Reusable: { get: { operationId: 'reusable', responses: { '200': { $ref: '#/components/responses/Found' } } } } } },
    paths: { [route]: { parameters: [{ $ref: '#/components/parameters/Id' }], get: { operationId: 'list', tags: ['Items'], responses: { '200': { $ref: '#/components/responses/Found' } } },
      post: { operationId: 'create', security: [{ Caller: [] }], responses: { '204': { description: 'done' } } },
      delete: { operationId: 'public', security: [], responses: { '204': { description: 'done' } } } } } } };
}
test('namespaces every component kind and reference without changing payload examples or authentication', () => {
  const a = fixture('Member'), b = fixture('Shopping', '/v1/products');
  const original = structuredClone(a);
  const { bundle } = buildBundle([a, b]);
  assert.deepEqual(a, original);
  for (const [kind, values] of Object.entries(a.doc.components)) for (const key of Object.keys(values)) assert(Object.hasOwn(bundle.components[kind], 'Member_' + key));
  assert.equal(bundle.components.responses.Member_Found.content['application/json'].schema.$ref, '#/components/schemas/Member_Item');
  assert.deepEqual(bundle.components.examples.Member_Literal.value, a.doc.components.examples.Literal.value);
  assert.deepEqual(bundle.paths['/v1/items'].get.security, [{ Member_Bearer: [] }]);
  assert.deepEqual(bundle.paths['/v1/items'].post.security, [{ Member_Caller: [] }]);
  assert.deepEqual(bundle.paths['/v1/items'].delete.security, []);
  assert.deepEqual(bundle.paths['/v1/items'].get.servers, a.doc.servers);
  assert.deepEqual(bundle.paths['/v1/items'].get.tags, ['Member: Items']);
  assert.equal(bundle.paths['/v1/items'].get['x-apidog-folder'], 'Member');
  assert.equal(bundle.components.links.Member_Link.operationId, 'Member_list');
  assert.deepEqual(bundle.components.pathItems.Member_Reusable.get.security, [{ Member_Bearer: [] }]);
});
test('materializes path inheritance and operation overrides when distinct methods share a path', () => {
  const a = fixture('Member'), b = fixture('Commerce');
  a.doc.paths['/v1/items'] = { servers: [{ url: 'http://path-server' }], parameters: [{ $ref: '#/components/parameters/Id' }], get: a.doc.paths['/v1/items'].get };
  b.doc.paths['/v1/items'] = { post: b.doc.paths['/v1/items'].post };
  a.doc.paths['/v1/items'].get.parameters = [{ in: 'query', name: 'id', schema: { type: 'integer' } }];
  b.doc.paths['/v1/items'].post.servers = [{ url: 'http://operation-server' }];
  const { bundle } = buildBundle([a, b]);
  assert.deepEqual(bundle.paths['/v1/items'].get.servers, [{ url: 'http://path-server' }]);
  assert.deepEqual(bundle.paths['/v1/items'].post.servers, [{ url: 'http://operation-server' }]);
  assert.equal(bundle.paths['/v1/items'].get.parameters.length, 1);
  assert.equal(bundle.paths['/v1/items'].get.parameters[0].schema.type, 'integer');
});
test('rejects duplicate paths/methods and broken or external references', () => {
  assert.throws(() => buildBundle([fixture('Member'), fixture('Commerce')]), /Duplicate operation/);
  const bad = fixture('Member'); bad.doc.components.schemas.Item = { $ref: '#/components/schemas/Missing' };
  assert.throws(() => buildBundle([bad]), /Unresolved reference/);
  bad.doc.components.schemas.Item = { $ref: 'external.yaml#/Schema' };
  assert.throws(() => buildBundle([bad]), /local JSON-pointer/);
});
test('four committed YAMLs produce 67 endpoint operations with preserved effective security and servers', () => {
  const inputs = ['member', 'commerce', 'shopping', 'live'].map(s => ({ name: s[0].toUpperCase() + s.slice(1), ...load(path.join(root, 'contracts/api', s + '-service.yaml')) }));
  const { bundle, counts } = buildBundle(inputs);
  assert.deepEqual(counts, { Member: 9, Commerce: 27, Shopping: 12, Live: 19 });
  for (const source of inputs) for (const [route, item] of Object.entries(source.doc.paths)) for (const method of methods) if (item[method]) {
    const actual = bundle.paths[route][method];
    assert.deepEqual(actual.servers, item[method].servers ?? item.servers ?? source.doc.servers);
    assert.deepEqual(actual.security, (item[method].security ?? source.doc.security ?? []).map(r => Object.fromEntries(Object.entries(r).map(([k, v]) => [source.name + '_' + k, v]))));
    assert.equal(actual.operationId, source.name + '_' + item[method].operationId);
    assert.equal(actual['x-apidog-folder'], source.name);
  }
});
test('writes a hash manifest and existing compare detects a lost imported operation', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'apidog-bundle-test-'));
  const report = path.join(root, 'build/contracts/apidog-diff.json');
  const previousReport = fs.existsSync(report) ? fs.readFileSync(report) : null;
  try {
    const file = path.join(dir, 'bundle.json'); const result = createArtifact(root, file);
    assert.equal(result.operations, 67); assert.match(result.sourceSha, /^[a-f0-9]{40}$/);
    assert.equal(result.bundleSha256, load(file).sha256);
    assert.equal(spawnSync(process.execPath, [path.join(__dirname, 'compare.cjs'), file, file]).status, 0);
    const removed = JSON.parse(fs.readFileSync(file, 'utf8')); delete removed.paths['/v1/auth/login'].post;
    const exported = path.join(dir, 'export.json'); fs.writeFileSync(exported, JSON.stringify(removed));
    assert.equal(spawnSync(process.execPath, [path.join(__dirname, 'compare.cjs'), file, exported]).status, 1);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
    if (previousReport) fs.writeFileSync(report, previousReport); else fs.rmSync(report, { force: true });
  }
});
