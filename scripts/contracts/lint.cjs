const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { root, files, report } = require('./lib.cjs');
const images = require('./images.json');
const inputs = files().map(file => path.relative(root, file));
if (!inputs.length) throw new Error('No contract files found');
const result = spawnSync('docker', ['run', '--rm', '-e', 'REDOCLY_TELEMETRY=off',
  '-v', `${root}:/spec:ro`, '-w', '/spec', images.redocly, 'lint', ...inputs,
  '--config', 'scripts/contracts/redocly.yaml', '--format=json'], { encoding: 'utf8' });
fs.mkdirSync(path.join(root, 'build/contracts'), { recursive: true });
fs.writeFileSync(path.join(root, 'build/contracts/lint.log'), (result.stdout || '') + (result.stderr || ''));
process.stderr.write(result.stderr || '');
process.stdout.write(result.stdout || '');
report('lint', { passed: result.status === 0, exitCode: result.status, image: images.redocly,
  error: result.error?.message, files: inputs });
process.exitCode = result.status === 0 ? 0 : 1;
