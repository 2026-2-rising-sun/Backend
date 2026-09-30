const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const YAML = require('yaml');
const Ajv = require('ajv/dist/2020').default;
const addFormats = require('ajv-formats');
const root = path.resolve(__dirname, '../..');
const files = () => fs.readdirSync(path.join(root, 'contracts/api'))
  .filter(name => name.endsWith('.yaml')).sort().map(name => path.join(root, 'contracts/api', name));
const escape = value => value.replaceAll('~', '~0').replaceAll('/', '~1');
function load(file) {
  const bytes = fs.readFileSync(file);
  const parsed = YAML.parseDocument(bytes.toString(), { uniqueKeys: true });
  if (parsed.errors.length) throw new Error(parsed.errors.map(error => error.message).join('\n'));
  const doc = parsed.toJS();
  if (doc.openapi !== '3.1.0') throw new Error(`${file}: expected OpenAPI 3.1.0`);
  const resolve = ref => {
    if (!ref.startsWith('#/')) throw new Error(`Only local references supported: ${ref}`);
    return ref.slice(2).split('/').reduce((value, part) => {
      const key = part.replaceAll('~1', '/').replaceAll('~0', '~');
      if (!value || !Object.hasOwn(value, key)) throw new Error(`Unresolved reference: ${ref}`);
      return value[key];
    }, doc);
  };
  const ajv = new Ajv({ allErrors: true, strict: false });
  addFormats(ajv);
  ajv.addFormat('int64', { type: 'number', validate: Number.isInteger });
  ajv.addFormat('binary', { type: 'string', validate: () => true });
  const id = 'https://contracts.invalid/' + path.basename(file);
  ajv.addSchema(doc, id);
  const validator = pointer => ajv.compile({ $ref: id + pointer });
  return { doc, resolve, validator, sha256: crypto.createHash('sha256').update(bytes).digest('hex') };
}
function report(name, value) {
  const dir = path.join(root, 'build/contracts');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, name + '.json'), JSON.stringify(value, null, 2) + '\n');
}
module.exports = { root, files, load, escape, report };
