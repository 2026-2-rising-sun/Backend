const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const net = require('node:net');
const crypto = require('node:crypto');
const { spawn, spawnSync } = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../..');
const services = ['member', 'shopping', 'commerce', 'live'];
// live2 is a second live-service process on the same database and Redis, used to prove cross-instance delivery.
const replicas = { live2: 'live' };
const instances = [...services, ...Object.keys(replicas)];
const base = instance => replicas[instance] || instance;
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function command(file, args, options = {}) {
  const result = spawnSync(file, args, { cwd: root, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024, ...options });
  if (result.status !== 0) throw new Error(`${file} command failed (${result.status}); inspect sanitized runtime logs`);
  return (result.stdout || '').trim();
}
async function freePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
class Runtime {
  constructor(options = {}) {
    this.id = 'sl-p2-flow-' + crypto.randomBytes(6).toString('hex');
    this.localTestAccounts = options.localTestAccounts === true;
    this.sha = command('git', ['rev-parse', 'HEAD']);
    this.output = options.output ?? path.join(root, 'build/integration');
    this.containers = new Set(); this.children = new Map(); this.secrets = new Set();
    this.urls = {}; this.health = {}; this.environments = {}; this.jars = {}; this.ports = {};
    const java = spawnSync('java', ['-version'], { encoding: 'utf8' });
    const host21 = /version "21[.\"]/.test((java.stderr || '') + (java.stdout || ''));
    this.mode = process.env.SHOPPINGLIVE_INTEGRATION_RUNTIME || (host21 ? 'host' : 'docker');
    assert(['host', 'docker'].includes(this.mode), 'Runtime must be host or docker');
    assert(this.mode !== 'host' || host21, 'Host runtime requires JDK 21');
    this.private = fs.mkdtempSync(path.join(os.tmpdir(), this.id + '-'));
    fs.chmodSync(this.private, 0o700);
    this.jvm = ['-Xmx192m', '-XX:MaxMetaspaceSize=192m', '-XX:ActiveProcessorCount=2'];
    this.credentials = Object.fromEntries(['SHOPPING_COMMERCE', 'COMMERCE_SHOPPING', 'LIVE_SHOPPING', 'LIVE_COMMERCE',
      'SHOPPING_MEMBER', 'COMMERCE_MEMBER', 'LIVE_MEMBER']
      .map(pair => [pair + '_SERVICE_TOKEN', crypto.randomBytes(32).toString('base64url')]));
    this.databasePassword = crypto.randomBytes(24).toString('base64url');
    this.remember(this.databasePassword, ...Object.values(this.credentials));
    const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
    this.key = privateKey; this.kid = this.id;
    this.secretFile('member-private.pem', privateKey.export({ type: 'pkcs8', format: 'pem' }));
    this.secretFile('member-public.jwks', JSON.stringify({ keys: [{ ...publicKey.export({ format: 'jwk' }),
      kid: this.kid, use: 'sig', alg: 'RS256' }] }));
    fs.mkdirSync(this.output, { recursive: true });
  }
  remember(...values) { for (const value of values) if (value) this.secrets.add(value); }
  sanitize(value) {
    if (value === undefined) return undefined;
    if (typeof value !== 'string') return JSON.parse(this.sanitize(JSON.stringify(value, (key, item) =>
      /^(accessToken|refreshToken|password|privateKey|authorization|cookie)$/i.test(key) ? '[REDACTED]' : item)));
    let text = value.replace(/-----BEGIN PRIVATE KEY-----[\s\S]*?-----END PRIVATE KEY-----/g, '[PRIVATE KEY REDACTED]')
      .replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, '[JWT REDACTED]');
    for (const secret of [...this.secrets].sort((a, b) => b.length - a.length)) text = text.split(secret).join('[REDACTED]');
    return text;
  }
  secretFile(name, value) {
    const file = path.join(this.private, name);
    fs.writeFileSync(file, value, { mode: 0o600 });
    return file;
  }
  envFile(name, env) {
    assert(Object.values(env).every(v => !String(v).includes('\n')), 'Environment values must be single-line');
    return this.secretFile(name + '.env', Object.entries(env).map(([k, v]) => `${k}=${v}`).join('\n') + '\n');
  }
  owned(name) {
    const inspect = spawnSync('docker', ['inspect', '--format', '{{index .Config.Labels "shoppinglive.integration"}}', name], { encoding: 'utf8' });
    if (inspect.status !== 0) return false;
    assert.equal(inspect.stdout.trim(), this.id, 'Refusing to touch a container owned by another run');
    return true;
  }
  docker(name, args) {
    this.containers.add(name);
    return command('docker', ['run', '--name', name, '--label', `shoppinglive.integration=${this.id}`, ...args]);
  }
  async build(usePrebuilt) {
    if (usePrebuilt) {
      assert.equal(fs.readFileSync(path.join(root, 'build/integration-jars.sha'), 'utf8').trim(), this.sha,
        'Prebuilt jars must carry the verified checkout SHA');
    } else {
      const args = ['--no-daemon', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=384m',
        ...services.map(s => `:services:${s}-service:bootJar`)];
      const file = this.mode === 'host' ? './gradlew' : 'docker';
      if (this.mode === 'docker') this.containers.add(this.id + '-build');
      const buildArgs = this.mode === 'host' ? args : ['run', '--rm', '--name', this.id + '-build',
        '--label', `shoppinglive.integration=${this.id}`, '-v', `${root}:/workspace`,
        '-v', 'sl-p2-flow-gradle:/root/.gradle', '-w', '/workspace', 'eclipse-temurin:21-jdk', './gradlew', ...args];
      await new Promise((resolve, reject) => {
        const child = spawn(file, buildArgs, { cwd: root, stdio: 'inherit' });
        this.buildChild = child;
        child.once('error', reject).once('exit', code => code === 0 ? resolve() : reject(new Error(`bootJar failed (${code})`)));
      });
    }
    for (const service of services) {
      const directory = path.join(root, 'services', service + '-service', 'build/libs');
      const files = fs.readdirSync(directory).filter(name => name.endsWith('.jar') && !name.endsWith('-plain.jar'));
      assert.equal(files.length, 1, `${service} requires exactly one bootJar`);
      this.jars[service] = path.join(directory, files[0]);
    }
    for (const replica of Object.keys(replicas)) this.jars[replica] = this.jars[base(replica)];
  }
  async setup() {
    await this.setupPostgres();
    await this.setupRedis();
    for (const service of instances) this.ports[service] = { api: await freePort(), management: await freePort() };
    for (const service of services) await this.start(service);
    for (const service of services) await this.waitReady(service);
    // Replicas start after their base instance has finished the Flyway migration.
    for (const replica of Object.keys(replicas)) { await this.start(replica); await this.waitReady(replica); }
  }
  async setupPostgres() {
    command('docker', ['network', 'create', '--label', `shoppinglive.integration=${this.id}`, this.id]);
    this.networkCreated = true;
    this.pg = this.id + '-postgres';
    const pgEnv = this.envFile('postgres', { POSTGRES_USER: 'integration', POSTGRES_PASSWORD: this.databasePassword, POSTGRES_DB: 'postgres' });
    this.docker(this.pg, ['-d', '--network', this.id, '--network-alias', 'task-postgres',
      '--tmpfs', '/var/lib/postgresql/data:rw', '-p', '127.0.0.1::5432', '--env-file', pgEnv, 'postgres:16-alpine']);
    this.pgPort = JSON.parse(command('docker', ['inspect', this.pg]))[0].NetworkSettings.Ports['5432/tcp'][0].HostPort;
    // The entrypoint's initialization server only accepts Unix sockets. Wait for the final TCP server.
    const tcp = ['exec', this.pg, 'sh', '-c', 'export PGPASSWORD="$POSTGRES_PASSWORD"; exec "$@"', 'sh'];
    await this.wait(async () => {
      if (spawnSync('docker', [...tcp, 'pg_isready', '-h', '127.0.0.1', '-U', 'integration', '-d', 'postgres'], { stdio: 'ignore' }).status !== 0) return false;
      const sql = spawnSync('docker', [...tcp, 'psql', '-h', '127.0.0.1', '-U', 'integration', '-d', 'postgres', '-Atqc', 'SELECT 1'], { encoding: 'utf8' });
      return sql.status === 0 && sql.stdout.trim() === '1';
    }, 'PostgreSQL TCP query readiness');
    for (const service of services)
      command('docker', [...tcp, 'createdb', '-h', '127.0.0.1', '-U', 'integration', service]);
  }
  async setupRedis() {
    this.redis = this.id + '-redis';
    this.docker(this.redis, ['-d', '--network', this.id, '--network-alias', 'task-redis', '-p', '127.0.0.1::6379', 'redis:7-alpine']);
    this.redisPort = JSON.parse(command('docker', ['inspect', this.redis]))[0].NetworkSettings.Ports['6379/tcp'][0].HostPort;
    await this.wait(async () => {
      const ping = spawnSync('docker', ['exec', this.redis, 'redis-cli', 'ping'], { encoding: 'utf8' });
      return ping.status === 0 && ping.stdout.trim() === 'PONG';
    }, 'Redis readiness');
  }
  databaseUrl(service) {
    return `jdbc:postgresql://${this.mode === 'docker' ? 'task-postgres:5432' : '127.0.0.1:' + this.pgPort}/${service}?connectTimeout=2&socketTimeout=2`;
  }
  javaOptions(service, env, extra = [], oneShot = false) {
    const hostBase = Object.fromEntries(['PATH', 'JAVA_HOME', 'HOME', 'TMPDIR', 'LANG']
      .filter(key => process.env[key]).map(key => [key, process.env[key]]));
    if (this.mode === 'host') return ['java', [...this.jvm, '-jar', this.jars[service], ...extra], { ...hostBase, ...env }];
    const name = this.id + '-' + service + (oneShot ? '-seller' : '');
    const mounts = ['-v', `${this.jars[service]}:/app.jar:ro`, '-v', `${this.private}/member-public.jwks:/run/integration/member-public.jwks:ro`];
    if (service === 'shopping') {
      fs.mkdirSync(path.join(this.private, 'images'), { recursive: true, mode: 0o700 });
      mounts.push('-v', `${this.private}/images:/tmp/images`);
    }
    if (service === 'member') mounts.push('-v', `${this.private}/member-private.pem:/run/integration/member-private.pem:ro`);
    const args = ['--network', this.id, '--network-alias', service, '--env-file', this.envFile(service + (oneShot ? '-seller' : ''), env), ...mounts];
    if (oneShot) args.push('--rm');
    else args.push('-d', '-p', `127.0.0.1:${this.ports[service].api}:8080`, '-p', `127.0.0.1:${this.ports[service].management}:9090`);
    args.push('eclipse-temurin:21-jdk', 'java', ...this.jvm, '-jar', '/app.jar', ...extra);
    return [name, args];
  }
  async start(service, overrides = {}) {
    const prefix = this.mode === 'docker' ? '/run/integration' : this.private;
    const upstream = name => this.mode === 'docker' ? `http://${name}:8080` : `http://127.0.0.1:${this.ports[name].api}`;
    const env = { SPRING_PROFILES_ACTIVE: 'local', SERVER_PORT: String(this.mode === 'docker' ? 8080 : this.ports[service].api),
      MANAGEMENT_SERVER_PORT: String(this.mode === 'docker' ? 9090 : this.ports[service].management),
      SPRING_DATASOURCE_URL: this.databaseUrl(base(service)), SPRING_DATASOURCE_USERNAME: 'integration', SPRING_DATASOURCE_PASSWORD: this.databasePassword,
      SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT: '2000', SPRING_DATASOURCE_HIKARI_VALIDATION_TIMEOUT: '1000',
      MEMBER_LOCAL_TEST_ACCOUNTS_ENABLED: String(this.localTestAccounts), SPRING_KAFKA_BOOTSTRAP_SERVERS: '127.0.0.1:1', SPRING_KAFKA_ADMIN_AUTO_CREATE: 'false', SPRING_KAFKA_LISTENER_AUTO_STARTUP: 'false',
      MEMBER_JWT_PUBLIC_KEY_SET_LOCATION: `file:${prefix}/member-public.jwks`,
      SHOPPING_SALES_CLIENT_BASE_URL: upstream('commerce'), COMMERCE_SHOPPING_CLIENT_BASE_URL: upstream('shopping'),
      LIVE_PRODUCTS_MODE: 'http', LIVE_PRODUCTS_SHOPPING_URL: upstream('shopping'), LIVE_PRODUCTS_COMMERCE_URL: upstream('commerce'),
      LIVE_IVS_MODE: 'stub', LIVE_IVS_STUB_READY: 'true', SHOPPING_IMAGE_DIR: this.mode === 'docker' ? '/tmp/images' : path.join(this.private, 'images'),
      SHOPPING_IMAGE_PUBLIC_BASE_URL: upstream('shopping'), COMMERCE_DEV_PAYMENT_SCENARIO_ENABLED: 'true',
      SERVER_TOMCAT_ACCESSLOG_ENABLED: 'true', SERVER_TOMCAT_ACCESSLOG_DIRECTORY: this.mode === 'docker' ? '/tmp/access' : path.join(this.private, service + '-access'),
      SERVER_TOMCAT_ACCESSLOG_PATTERN: '%m %U%q %s %{X-Request-Id}i', SERVER_TOMCAT_ACCESSLOG_BUFFERED: 'false' };
    const needed = { member: ['SHOPPING_MEMBER', 'COMMERCE_MEMBER', 'LIVE_MEMBER'],
      shopping: ['SHOPPING_COMMERCE', 'COMMERCE_SHOPPING', 'LIVE_SHOPPING', 'SHOPPING_MEMBER'],
      commerce: ['SHOPPING_COMMERCE', 'COMMERCE_SHOPPING', 'LIVE_COMMERCE', 'COMMERCE_MEMBER'],
      live: ['LIVE_SHOPPING', 'LIVE_COMMERCE', 'LIVE_MEMBER'] };
    if (base(service) === 'live') Object.assign(env, { SPRING_DATA_REDIS_HOST: this.mode === 'docker' ? 'task-redis' : '127.0.0.1',
      SPRING_DATA_REDIS_PORT: String(this.mode === 'docker' ? 6379 : this.redisPort) });
    for (const pair of needed[base(service)]) env[pair + '_SERVICE_TOKEN'] = this.credentials[pair + '_SERVICE_TOKEN'];
    if (service !== 'member') env.MEMBER_SESSION_BASE_URL = upstream('member');
    if (service === 'member') Object.assign(env, { MEMBER_JWT_PRIVATE_KEY_LOCATION: `file:${prefix}/member-private.pem`,
      MEMBER_JWT_KEY_ID: this.kid, MEMBER_ACCESS_TOKEN_TTL: 'PT15M', MEMBER_REFRESH_TOKEN_TTL: 'P30D' });
    Object.assign(env, overrides);
    this.environments[service] = env;
    const [file, args, hostEnv] = this.javaOptions(service, env);
    if (this.mode === 'docker') this.docker(file, args);
    else {
      const fd = fs.openSync(path.join(this.private, service + '.log'), 'a', 0o600);
      const child = spawn(file, args, { cwd: root, env: hostEnv, stdio: ['ignore', fd, fd] });
      fs.closeSync(fd); this.children.set(service, child);
    }
    this.urls[service] = `http://127.0.0.1:${this.ports[service].api}`;
    this.health[service] = `http://127.0.0.1:${this.ports[service].management}`;
  }
  async wait(predicate, label, timeout = 120000) {
    const until = Date.now() + timeout;
    while (Date.now() < until) { if (await predicate()) return; await delay(250); }
    throw new Error(`Timed out waiting for ${label}`);
  }
  waitReady(service) {
    return this.wait(async () => { try { return (await fetch(this.health[service] + '/actuator/health/readiness',
      { signal: AbortSignal.timeout(2000) })).status === 200; } catch { return false; } }, service);
  }
  bootstrapSeller(email, password) {
    this.remember(password);
    const env = { ...this.environments.member, MEMBER_BOOTSTRAP_DB_URL: this.databaseUrl('member'), MEMBER_BOOTSTRAP_DB_SCHEMA: 'public',
      MEMBER_BOOTSTRAP_DB_USER: 'integration', MEMBER_BOOTSTRAP_DB_PASSWORD: this.databasePassword,
      MEMBER_BOOTSTRAP_SELLER_EMAIL: email, MEMBER_BOOTSTRAP_SELLER_PASSWORD: password, MEMBER_BOOTSTRAP_SELLER_DISPLAY_NAME: 'Integration Seller' };
    const [file, args, hostEnv] = this.javaOptions('member', env, ['--bootstrap-seller'], true);
    if (this.mode === 'docker') this.docker(file, args);
    else command(file, args, { env: hostEnv });
  }
  sql(service, query) {
    assert(services.includes(service)); assert(this.owned(this.pg));
    return command('docker', ['exec', this.pg, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'integration', '-d', service, '-At', '-c', query]);
  }
  access(service) {
    if (this.mode === 'docker') {
      const name = this.id + '-' + service; assert(this.owned(name));
      return command('docker', ['exec', name, 'sh', '-c', 'cat /tmp/access/*']);
    }
    const directory = path.join(this.private, service + '-access');
    return fs.readdirSync(directory).map(name => fs.readFileSync(path.join(directory, name), 'utf8')).join('\n');
  }
  sign(claims, algorithm = 'RS256') {
    const header = Buffer.from(JSON.stringify({ alg: algorithm, kid: this.kid, typ: 'JWT' })).toString('base64url');
    const payload = Buffer.from(JSON.stringify(claims)).toString('base64url');
    const input = header + '.' + payload;
    const token = input + '.' + (algorithm === 'RS256' ? crypto.sign('RSA-SHA256', Buffer.from(input), this.key).toString('base64url') : 'invalid');
    this.remember(token); return token;
  }
  async stop(service) {
    if (this.mode === 'docker') {
      const name = this.id + '-' + service;
      if (this.owned(name)) command('docker', ['stop', '--time', '5', name]);
    } else {
      const child = this.children.get(service);
      if (!child || child.exitCode !== null) return;
      child.kill('SIGTERM');
      await Promise.race([new Promise(resolve => child.once('exit', resolve)), delay(7000)]);
      if (child.exitCode === null) child.kill('SIGKILL');
    }
  }
  async restart(service) {
    if (this.mode === 'docker') { assert(this.owned(this.id + '-' + service)); command('docker', ['start', this.id + '-' + service]); }
    else await this.start(service);
    await this.waitReady(service);
  }
  async reconfigure(service, overrides) {
    assert(services.includes(service));
    const upstreamKeys = ['SHOPPING_SALES_CLIENT_BASE_URL', 'COMMERCE_SHOPPING_CLIENT_BASE_URL',
      'LIVE_PRODUCTS_SHOPPING_URL', 'LIVE_PRODUCTS_COMMERCE_URL'];
    assert(Object.keys(overrides).every(key => upstreamKeys.includes(key)), 'Only test upstream URLs may be reconfigured');
    await this.stop(service);
    if (this.mode === 'docker') {
      const name = this.id + '-' + service;
      if (this.owned(name)) {
        const logs = spawnSync('docker', ['logs', name], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
        fs.appendFileSync(path.join(this.private, service + '.previous.log'), (logs.stdout || '') + (logs.stderr || ''), { mode: 0o600 });
        command('docker', ['rm', name]);
      }
    }
    await this.start(service, overrides);
    await this.waitReady(service);
  }
  async cleanup() {
    if (!this.cleanupPromise) this.cleanupPromise = this.cleanOwnedResources();
    return this.cleanupPromise;
  }
  async cleanOwnedResources() {
    const errors = [];
    if (this.buildChild && this.buildChild.exitCode === null && this.buildChild.signalCode === null) {
      this.buildChild.kill('SIGTERM');
      await Promise.race([new Promise(resolve => this.buildChild.once('exit', resolve)), delay(5000)]);
      if (this.buildChild.exitCode === null && this.buildChild.signalCode === null) this.buildChild.kill('SIGKILL');
    }
    for (const service of instances) {
      try {
        let log = '';
        if (this.mode === 'docker' && this.owned(this.id + '-' + service)) {
          const logs = spawnSync('docker', ['logs', this.id + '-' + service], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
          log = (logs.stdout || '') + (logs.stderr || '');
        } else if (this.mode === 'host' && fs.existsSync(path.join(this.private, service + '.log'))) log = fs.readFileSync(path.join(this.private, service + '.log'), 'utf8');
        const previous = path.join(this.private, service + '.previous.log');
        if (fs.existsSync(previous)) log = fs.readFileSync(previous, 'utf8') + log;
        fs.writeFileSync(path.join(this.output, service + '.log'), this.sanitize(log));
        await this.stop(service);
      } catch (error) { errors.push(this.sanitize(error.message)); }
    }
    try {
      if (this.pg && this.owned(this.pg)) {
        const log = spawnSync('docker', ['logs', this.pg], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
        fs.writeFileSync(path.join(this.output, 'postgres.log'), this.sanitize((log.stdout || '') + (log.stderr || '')));
      }
    } catch (error) { errors.push(this.sanitize(error.message)); }
    for (const name of this.containers) {
      try { if (this.owned(name)) command('docker', ['rm', '-f', name]); } catch (error) { errors.push(error.message); }
    }
    if (this.networkCreated) {
      try {
        const network = JSON.parse(command('docker', ['network', 'inspect', this.id]))[0];
        assert.equal(network.Labels['shoppinglive.integration'], this.id);
        command('docker', ['network', 'rm', this.id]);
      } catch (error) { errors.push(error.message); }
    }
    fs.rmSync(this.private, { recursive: true, force: true });
    assert.equal(errors.length, 0, 'Owned runtime cleanup failed: ' + errors.join('; '));
  }
}
module.exports = { Runtime, root, services, instances, delay, command };
