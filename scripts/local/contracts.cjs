const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { root, files, load } = require('../contracts/lib.cjs');
const { start, stop, ready } = require('../contracts/docker.cjs');
const images = require('../contracts/images.json');
function mock(service, port) {
  const file = files().find(file => path.basename(file) === service + '-service.yaml');
  if (!file) throw new Error(`Unknown contract: ${service}`);
  return start(['-v', `${path.dirname(file)}:/contracts:ro`, images.prism,
    'mock', '--multiprocess', 'false', '-h', '0.0.0.0', '/contracts/' + path.basename(file)], 4010, port);
}
function docs(port) {
  fs.mkdirSync(path.join(root, 'build/contracts'), { recursive: true });
  const gitSha = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
  const dest = fs.mkdtempSync(path.join(root, 'build/contracts/swagger-'));
  const inputs = files().map(file => {
    const { doc, sha256 } = load(file);
    fs.copyFileSync(file, path.join(dest, path.basename(file)));
    return { file: path.basename(file), title: doc.info.title, sha256 };
  });
  const config = { ...require('../../contracts/docs/swagger-config.json'), urls: inputs.map(input => ({
    name: `${input.title} (working tree @ ${gitSha.slice(0, 8)})`, url: '/contracts/' + input.file })) };
  fs.writeFileSync(path.join(dest, 'config.json'), JSON.stringify(config));
  fs.writeFileSync(path.join(dest, 'provenance.json'), JSON.stringify({ gitSha, snapshot: 'working-tree', inputs }));
  return start(['-v', `${dest}:/usr/share/nginx/html/contracts:ro`,
    '-e', 'CONFIG_URL=/contracts/config.json', images.swagger], 8080, port);
}
async function main() {
  const [mode, service, portArg] = process.argv.slice(2);
  if (!['docs', 'mock'].includes(mode)) throw new Error('Usage: contracts.cjs docs [port] | mock <service> [port]');
  const port = Number((mode === 'docs' ? service : portArg) || (mode === 'docs' ? 18090 : 4010));
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Port must be 1024..65535');
  fs.mkdirSync(path.join(root, 'build/contracts'), { recursive: true });
  const server = mode === 'docs' ? docs(port) : mock(service, port);
  try {
    await ready(server.url);
    console.log(`${mode}: ${server.url}; container=${server.id}; Ctrl-C stops only this container`);
    setInterval(() => {}, 60000);
  } catch (error) { stop(server.id); throw error; }
}
module.exports = { mock, docs };
if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
