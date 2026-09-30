const { files, load, escape, report } = require('./lib.cjs');
const inputs = process.argv.slice(2).length ? process.argv.slice(2) : files();
const results = [];
if (!inputs.length) throw new Error('No contract files found');
for (const file of inputs) {
  const result = { file, passed: false, schemas: 0, references: 0, examples: 0 };
  try {
    const { doc, resolve, validator, sha256 } = load(file);
    result.sha256 = sha256;
    const check = (pointer, value, label) => {
      const validate = validator(pointer);
      if (!validate(value)) throw new Error(`${label}: ${JSON.stringify(validate.errors)}`);
      result.examples++;
    };
    function walk(node, pointer) {
      if (!node || typeof node !== 'object') return;
      if (node.$ref) { resolve(node.$ref); result.references++; }
      if (Object.hasOwn(node, 'schema')) {
        validator(pointer + '/schema');
        if (Object.hasOwn(node, 'example')) check(pointer + '/schema', node.example, pointer + '/example');
        for (const [name, raw] of Object.entries(node.examples || {})) {
          const example = raw.$ref ? resolve(raw.$ref) : raw;
          if (!Object.hasOwn(example, 'value')) throw new Error(`Example value required: ${pointer}/${name}`);
          check(pointer + '/schema', example.value, pointer + '/examples/' + name);
        }
      }
      if (pointer.startsWith('#/components/schemas/') && Array.isArray(node.examples)) {
        for (const value of node.examples) check(pointer, value, pointer + '/examples');
      }
      for (const [key, value] of Object.entries(node)) walk(value, pointer + '/' + escape(key));
    }
    for (const name of Object.keys(doc.components?.schemas || {})) {
      validator('#/components/schemas/' + escape(name));
      result.schemas++;
    }
    walk(doc, '#');
    result.passed = true;
  } catch (error) { result.error = error.message; }
  results.push(result);
}
const summary = { passed: results.every(result => result.passed), results };
report('examples', summary);
console.log(JSON.stringify(summary, null, 2));
process.exitCode = summary.passed ? 0 : 1;
