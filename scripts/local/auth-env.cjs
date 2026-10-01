const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');
const { root } = require('../contracts/lib.cjs');

const publicKeys = ['MEMBER_JWT_PUBLIC_KEY_SET_LOCATION'];
const serviceKeys = {
  member: ['MEMBER_JWT_PRIVATE_KEY_LOCATION', 'MEMBER_JWT_KEY_ID', 'MEMBER_ACCESS_TOKEN_TTL', 'MEMBER_REFRESH_TOKEN_TTL'],
  shopping: ['COMMERCE_SHOPPING_SERVICE_TOKEN', 'LIVE_SHOPPING_SERVICE_TOKEN', 'SHOPPING_COMMERCE_SERVICE_TOKEN'],
  commerce: ['COMMERCE_SHOPPING_SERVICE_TOKEN', 'SHOPPING_COMMERCE_SERVICE_TOKEN', 'LIVE_COMMERCE_SERVICE_TOKEN'],
  live: ['LIVE_SHOPPING_SERVICE_TOKEN', 'LIVE_COMMERCE_SERVICE_TOKEN']
};
function duration(value, name) {
  const match = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(value || '');
  const seconds = match && Number(match[1] || 0) * 86400 + Number(match?.[2] || 0) * 3600
    + Number(match?.[3] || 0) * 60 + Number(match?.[4] || 0);
  if (!match || !Number.isSafeInteger(seconds) || seconds < 1) {
    throw new Error(`${name} must be an explicit positive ISO-8601 duration in whole seconds (no default)`);
  }
  return value;
}
function create(args) {
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    if (!['--access-ttl', '--refresh-ttl', '--out'].includes(args[i]) || !args[i + 1]) throw new Error('Invalid create option');
    if (args[i] in options) throw new Error('Duplicate create option');
    options[args[i]] = args[i + 1];
  }
  const accessTtl = duration(options['--access-ttl'], '--access-ttl');
  const refreshTtl = duration(options['--refresh-ttl'], '--refresh-ttl');
  const parent = path.join(root, 'build/local');
  fs.mkdirSync(parent, { recursive: true });
  const dest = path.resolve(options['--out'] || path.join(parent, `auth-${crypto.randomUUID()}`));
  // Never overwrite existing credentials or rotate a running environment implicitly.
  fs.mkdirSync(dest, { mode: 0o700 });
  const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const kid = `local-${crypto.randomUUID()}`;
  const privatePath = path.join(dest, 'member-private.pem');
  const publicPath = path.join(dest, 'member-public.jwks');
  fs.writeFileSync(privatePath, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600, flag: 'wx' });
  fs.writeFileSync(publicPath, JSON.stringify({ keys: [{ ...publicKey.export({ format: 'jwk' }), kid, alg: 'RS256', use: 'sig' }] }), { mode: 0o600, flag: 'wx' });
  const env = { MEMBER_JWT_PUBLIC_KEY_SET_LOCATION: `file:${publicPath}`,
    MEMBER_JWT_PRIVATE_KEY_LOCATION: `file:${privatePath}`, MEMBER_JWT_KEY_ID: kid,
    MEMBER_ACCESS_TOKEN_TTL: accessTtl, MEMBER_REFRESH_TOKEN_TTL: refreshTtl };
  for (const key of new Set(Object.values(serviceKeys).flat().filter(key => key.endsWith('_SERVICE_TOKEN')))) {
    env[key] = crypto.randomBytes(32).toString('base64url');
  }
  const envPath = path.join(dest, 'env.json');
  fs.writeFileSync(envPath, JSON.stringify({ purpose: 'isolated local development only', env }, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  console.log(`Local credentials written: ${envPath}`);
  console.log('No account or token was issued. Use the real Member signup/login flow; do not commit these files.');
}
function execute(args) {
  const [service, option, envPath, separator, command, ...commandArgs] = args;
  if (!serviceKeys[service] || option !== '--env' || !envPath || separator !== '--' || !command) {
    throw new Error('Usage: auth-env.cjs exec <member|shopping|commerce|live> --env <env.json> -- <command> [args]');
  }
  const record = JSON.parse(fs.readFileSync(path.resolve(envPath), 'utf8'));
  if (record.purpose !== 'isolated local development only') throw new Error('Expected generated local credentials');
  const env = { ...process.env };
  for (const name of new Set([...publicKeys, ...Object.values(serviceKeys).flat()])) delete env[name];
  for (const name of [...publicKeys, ...serviceKeys[service]]) {
    if (typeof record.env?.[name] !== 'string' || !record.env[name]) throw new Error(`Missing ${name}`);
    env[name] = record.env[name];
  }
  // Pass credentials through the environment, never shell interpolation or command-line arguments.
  const result = spawnSync(command, commandArgs, { env, stdio: 'inherit', shell: false });
  if (result.error) throw result.error;
  process.exitCode = result.status ?? 1;
}
try {
  const [mode, ...args] = process.argv.slice(2);
  if (mode === 'create') create(args);
  else if (mode === 'exec') execute(args);
  else throw new Error('Usage: auth-env.cjs create --access-ttl <duration> --refresh-ttl <duration> [--out <new-directory>] | exec ...');
} catch (error) { console.error(error.message); process.exitCode = 1; }
