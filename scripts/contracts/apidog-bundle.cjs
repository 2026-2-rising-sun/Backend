const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const { isDeepStrictEqual } = require('node:util');
const { load, root, escape } = require('./lib.cjs');
const services = ['member', 'commerce', 'shopping', 'live'];
const methods = new Set(['get', 'post', 'put', 'patch', 'delete', 'options', 'head', 'trace']);
const sha256 = value => crypto.createHash('sha256').update(value).digest('hex');
const unescape = value => value.replaceAll('~1', '/').replaceAll('~0', '~');
function resolve(doc, ref) {
  if (!ref.startsWith('#/')) throw new Error('Bundle requires local JSON-pointer references: ' + ref);
  return ref.slice(2).split('/').reduce((value, part) => {
    if (!value || !Object.hasOwn(value, unescape(part))) throw new Error('Unresolved reference: ' + ref);
    return value[unescape(part)];
  }, doc);
}
function buildBundle(sources) {
  const bundle = { openapi: '3.1.0', info: { title: 'Shopping-Live API', version: '1.0.0',
    description: sources.map(s => `## ${s.name}\n${s.doc.info.description || ''}`).join('\n\n'), license: { name: 'UNLICENSED' } },
    security: [], tags: [], paths: {}, components: {} };
  const ids = new Set(); const counts = {};
  for (const { name, doc } of sources) {
    if (!/^[A-Za-z][A-Za-z0-9]*$/.test(name) || Object.hasOwn(counts, name)) throw new Error('Invalid/duplicate service name');
    if (doc.openapi !== '3.1.0') throw new Error(name + ': expected OpenAPI 3.1.0');
    if (doc.webhooks && Object.keys(doc.webhooks).length) throw new Error('Webhook bundling is not supported');
    counts[name] = 0;
    const prefix = value => name + '_' + value;
    const tag = value => name + ': ' + value;
    function reference(ref) {
      resolve(doc, ref);
      const parts = ref.slice(2).split('/');
      if (parts[0] === 'components') {
        parts[2] = escape(prefix(unescape(parts[2])));
        return '#/' + parts.join('/');
      }
      if (parts[0] === 'paths') return ref; // Endpoint paths are intentionally unchanged.
      throw new Error('Unsupported reference root: ' + ref);
    }
    function security(requirements) {
      return requirements.map(requirement => Object.fromEntries(Object.entries(requirement).map(([key, scopes]) => {
        if (!Object.hasOwn(doc.components?.securitySchemes || {}, key)) throw new Error('Unknown security scheme: ' + key);
        return [prefix(key), structuredClone(scopes)];
      })));
    }
    function rewrite(value, trail = [], inheritedServers = doc.servers) {
      if (Array.isArray(value)) return value.map((item, i) => rewrite(item, [...trail, String(i)], inheritedServers));
      if (!value || typeof value !== 'object') return value;
      const servers = value.servers ?? inheritedServers;
      return Object.fromEntries(Object.entries(value).map(([key, child]) => {
        // Examples are payloads, including literal fields named $ref/security/operationId.
        if (key === 'example' || (key === 'examples' && Array.isArray(child))
          || (key === 'value' && trail.at(-2) === 'examples')) return [key, structuredClone(child)];
        if (key === '$ref' || key === '$dynamicRef' || key === 'operationRef') return [key, reference(child)];
        if (key === 'operationId' && typeof child === 'string') return [key, prefix(child)];
        if (key === 'security' && Array.isArray(child)) return [key, security(child)];
        if (key === 'tags' && Array.isArray(child) && child.every(t => typeof t === 'string')) return [key, child.map(tag)];
        if (key === 'mapping' && trail.at(-1) === 'discriminator') return [key, Object.fromEntries(Object.entries(child)
          .map(([label, target]) => [label, reference(target.startsWith('#/') ? target : '#/components/schemas/' + escape(target))]))];
        const result = rewrite(child, [...trail, key], servers);
        // Callback and reusable path-item operations also retain source inheritance.
        if (methods.has(key) && child && typeof child === 'object' && child.responses) {
          result.security = security(child.security ?? doc.security ?? []);
          result.servers = structuredClone(child.servers ?? servers ?? []);
          result['x-apidog-folder'] = name;
        }
        return [key, result];
      }));
    }
    for (const definition of doc.tags || []) bundle.tags.push({ ...structuredClone(definition), name: tag(definition.name) });
    for (const [kind, definitions] of Object.entries(doc.components || {})) {
      bundle.components[kind] ??= {};
      for (const [key, definition] of Object.entries(definitions)) bundle.components[kind][prefix(key)] = rewrite(definition, ['components', kind, key]);
    }
    for (const [url, rawItem] of Object.entries(doc.paths || {})) {
      if (rawItem.$ref) throw new Error('Path-item references must be materialized before bundling: ' + url);
      const item = rewrite(rawItem, ['paths', url]);
      const target = bundle.paths[url] ??= {};
      for (const [key, value] of Object.entries(item)) {
        if (key === 'parameters' || key === 'servers') continue;
        if (!methods.has(key)) {
          if (Object.hasOwn(target, key) && !isDeepStrictEqual(target[key], value)) throw new Error('Conflicting path metadata: ' + url + '/' + key);
          target[key] = value; continue;
        }
        if (Object.hasOwn(target, key)) throw new Error('Duplicate operation: ' + key.toUpperCase() + ' ' + url);
        const original = rawItem[key];
        const params = new Map();
        for (const parameter of [...(rawItem.parameters || []), ...(original.parameters || [])]) {
          const effective = parameter.$ref ? resolve(doc, parameter.$ref) : parameter;
          params.set(effective.in + ':' + effective.name, rewrite(parameter));
        }
        if (params.size) value.parameters = [...params.values()];
        value.security = security(original.security ?? doc.security ?? []);
        value.servers = structuredClone(original.servers ?? rawItem.servers ?? doc.servers ?? []);
        if (!value.servers.length) throw new Error('Operation requires an explicit service server: ' + name + ' ' + url);
        value['x-apidog-folder'] = name; // https://docs.apidog.com/x-apidog-folder-1981658m0
        if (!value.operationId || ids.has(value.operationId)) throw new Error('Missing/duplicate operationId: ' + value.operationId);
        ids.add(value.operationId); counts[name]++; target[key] = value;
      }
    }
  }
  // Validate rewritten references, skipping example payload values just as the rewriter does.
  function validate(value, trail = []) {
    if (!value || typeof value !== 'object') return;
    for (const [key, child] of Object.entries(value)) {
      if (key === 'example' || (key === 'examples' && Array.isArray(child)) || (key === 'value' && trail.at(-2) === 'examples')) continue;
      if (key === '$ref' || key === '$dynamicRef' || key === 'operationRef') resolve(bundle, child);
      else validate(child, [...trail, key]);
    }
  }
  validate(bundle);
  return { bundle, counts };
}
function createArtifact(sourceRoot = root, output = path.join(root, 'build/contracts/apidog-shopping-live.json')) {
  const git = (...args) => execFileSync('git', args, { cwd: sourceRoot, encoding: 'utf8' }).trim();
  const sourceSha = git('rev-parse', 'HEAD');
  const inputs = services.map(service => {
    const relative = `contracts/api/${service}-service.yaml`;
    const file = path.join(sourceRoot, relative); const bytes = fs.readFileSync(file);
    const committed = execFileSync('git', ['show', `${sourceSha}:${relative}`], { cwd: sourceRoot });
    if (!bytes.equals(committed)) throw new Error('Source YAML must match the recorded Git SHA: ' + relative);
    return { name: service[0].toUpperCase() + service.slice(1), file: relative, ...load(file) };
  });
  const { bundle, counts } = buildBundle(inputs);
  const text = JSON.stringify(bundle, null, 2) + '\n';
  const manifest = { sourceSha, sourceRoot, operations: Object.values(counts).reduce((a, b) => a + b, 0), counts,
    sources: inputs.map(s => ({ service: s.name, file: s.file, sha256: s.sha256 })), bundleSha256: sha256(text),
    compareCommand: `node scripts/contracts/compare.cjs ${output} <Apidog-export.yaml-or-json>`,
    comparison: 'Conservative structural diff, including metadata. Import/export success is a separate external verification.' };
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, text);
  const manifestFile = output.replace(/\.json$/, '') + '.manifest.json';
  fs.writeFileSync(manifestFile, JSON.stringify(manifest, null, 2) + '\n');
  return { output, manifestFile, ...manifest };
}
if (require.main === module) {
  const args = process.argv.slice(2); let sourceRoot = root, output;
  for (let i = 0; i < args.length; i += 2) {
    if (!args[i + 1]) throw new Error('Expected a value after ' + args[i]);
    if (args[i] === '--source-root') sourceRoot = path.resolve(args[i + 1]);
    else if (args[i] === '--output') output = path.resolve(args[i + 1]);
    else throw new Error('Unknown argument: ' + args[i]);
  }
  console.log(JSON.stringify(createArtifact(sourceRoot, output), null, 2));
}
module.exports = { buildBundle, createArtifact };
