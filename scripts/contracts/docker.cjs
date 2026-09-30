const { spawnSync } = require('node:child_process');
const crypto = require('node:crypto');
const running = new Set();
function docker(args) {
  const result = spawnSync('docker', args, { encoding: 'utf8', timeout: 120000 });
  if (result.status !== 0) throw new Error(result.error?.message || result.stderr || 'Docker failed');
  return result.stdout.trim();
}
function stop(id) { if (running.delete(id)) docker(['rm', '-f', id]); }
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => {
  for (const id of running) { try { stop(id); } catch (error) { console.error(error.message); } }
  process.exit(signal === 'SIGINT' ? 130 : 143);
});
function start(args, containerPort, hostPort = '') {
  const name = 'sl-contract-' + process.pid + '-' + crypto.randomBytes(4).toString('hex');
  let id;
  try {
    id = docker(['run', '-d', '--name', name,
      '-p', `127.0.0.1:${hostPort}:${containerPort}`, ...args]);
  } catch (error) {
    // Docker can create the container before failing to bind an occupied port.
    try { docker(['rm', '-f', name]); } catch { /* Preserve the startup error. */ }
    throw error;
  }
  running.add(id);
  try {
    const port = docker(['port', id, `${containerPort}/tcp`]).split(':').at(-1);
    return { id, url: `http://127.0.0.1:${port}` };
  } catch (error) { stop(id); throw error; }
}
async function ready(url) {
  for (let attempt = 0; attempt < 120; attempt++) {
    try { return await fetch(url, { signal: AbortSignal.timeout(1000) }); } catch {}
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  throw new Error(`Startup timed out: ${url}`);
}
module.exports = { docker, start, stop, ready };
