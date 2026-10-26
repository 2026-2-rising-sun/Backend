const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '../..');

const publicKeys = ['MEMBER_JWT_PUBLIC_KEY_SET_LOCATION'];
const serviceKeys = {
  member: [
    'MEMBER_JWT_PRIVATE_KEY_LOCATION',
    'MEMBER_JWT_KEY_ID',
    'MEMBER_ACCESS_TOKEN_TTL',
    'MEMBER_REFRESH_TOKEN_TTL',
    'SHOPPING_MEMBER_SERVICE_TOKEN',
    'COMMERCE_MEMBER_SERVICE_TOKEN',
    'LIVE_MEMBER_SERVICE_TOKEN',
    'NOTIFICATION_MEMBER_SERVICE_TOKEN',
  ],
  shopping: [
    'MEMBER_SESSION_BASE_URL',
    'SHOPPING_MEMBER_SERVICE_TOKEN',
    'COMMERCE_SHOPPING_SERVICE_TOKEN',
    'LIVE_SHOPPING_SERVICE_TOKEN',
    'SHOPPING_COMMERCE_SERVICE_TOKEN',
  ],
  commerce: [
    'MEMBER_SESSION_BASE_URL',
    'COMMERCE_MEMBER_SERVICE_TOKEN',
    'COMMERCE_SHOPPING_SERVICE_TOKEN',
    'SHOPPING_COMMERCE_SERVICE_TOKEN',
    'LIVE_COMMERCE_SERVICE_TOKEN',
  ],
  notification: [
    'MEMBER_SESSION_BASE_URL',
    'NOTIFICATION_MEMBER_SERVICE_TOKEN',
  ],
  live: [
    'MEMBER_SESSION_BASE_URL',
    'LIVE_MEMBER_SERVICE_TOKEN',
    'LIVE_SHOPPING_SERVICE_TOKEN',
    'LIVE_COMMERCE_SERVICE_TOKEN',
  ],
};
function duration(value, name) {
  const match = /^P(?:(\d+)D)?(?:T(?=\d)(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(value || '');
  const seconds =
    match &&
    Number(match[1] || 0) * 86400 +
      Number(match?.[2] || 0) * 3600 +
      Number(match?.[3] || 0) * 60 +
      Number(match?.[4] || 0);
  if (!match || !Number.isSafeInteger(seconds) || seconds < 1) {
    throw new Error(
      `${name}에 양수의 정수 초로 된 ISO-8601 기간을 명시해야 합니다(기본값 없음)`,
    );
  }
  return value;
}
function create(args) {
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    if (
      !['--access-ttl', '--refresh-ttl', '--member-url', '--out'].includes(args[i]) ||
      !args[i + 1]
    )
      throw new Error('인증정보 생성 옵션이 올바르지 않습니다');
    if (args[i] in options) throw new Error('인증정보 생성 옵션이 중복되었습니다');
    options[args[i]] = args[i + 1];
  }
  const accessTtl = duration(options['--access-ttl'], '--access-ttl');
  const refreshTtl = duration(options['--refresh-ttl'], '--refresh-ttl');
  let memberUrl;
  try {
    memberUrl = new URL(options['--member-url'] || 'http://localhost:8081');
  } catch {
    throw new Error('--member-url은 HTTP(S) 서비스 원본 주소여야 합니다');
  }
  if (
    !['http:', 'https:'].includes(memberUrl.protocol) ||
    !memberUrl.hostname ||
    memberUrl.username ||
    memberUrl.password ||
    memberUrl.search ||
    memberUrl.hash ||
    memberUrl.pathname !== '/'
  )
    throw new Error('--member-url은 인증정보나 경로가 없는 HTTP(S) 서비스 원본 주소여야 합니다');
  const parent = path.join(root, 'build/local');
  fs.mkdirSync(parent, { recursive: true });
  const dest = path.resolve(options['--out'] || path.join(parent, `auth-${crypto.randomUUID()}`));
  // 기존 인증정보를 덮어쓰거나 실행 중인 환경의 인증정보를 암묵적으로 교체하지 않는다.
  fs.mkdirSync(dest, { mode: 0o700 });
  const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const kid = `local-${crypto.randomUUID()}`;
  const privatePath = path.join(dest, 'member-private.pem');
  const publicPath = path.join(dest, 'member-public.jwks');
  fs.writeFileSync(privatePath, privateKey.export({ type: 'pkcs8', format: 'pem' }), {
    mode: 0o600,
    flag: 'wx',
  });
  fs.writeFileSync(
    publicPath,
    JSON.stringify({
      keys: [{ ...publicKey.export({ format: 'jwk' }), kid, alg: 'RS256', use: 'sig' }],
    }),
    { mode: 0o600, flag: 'wx' },
  );
  const env = {
    MEMBER_JWT_PUBLIC_KEY_SET_LOCATION: `file:${publicPath}`,
    MEMBER_JWT_PRIVATE_KEY_LOCATION: `file:${privatePath}`,
    MEMBER_JWT_KEY_ID: kid,
    MEMBER_ACCESS_TOKEN_TTL: accessTtl,
    MEMBER_REFRESH_TOKEN_TTL: refreshTtl,
    MEMBER_SESSION_BASE_URL: memberUrl.origin,
  };
  for (const key of new Set(
    Object.values(serviceKeys)
      .flat()
      .filter((key) => key.endsWith('_SERVICE_TOKEN')),
  )) {
    env[key] = crypto.randomBytes(32).toString('base64url');
  }
  const envPath = path.join(dest, 'env.json');
  fs.writeFileSync(
    envPath,
    JSON.stringify({ purpose: 'isolated local development only', env }, null, 2) + '\n',
    { mode: 0o600, flag: 'wx' },
  );
  console.log(`로컬 인증정보 저장 완료: ${envPath}`);
  console.log(
    '계정이나 토큰은 발급하지 않았습니다. local 프로필은 user@local.test/user와 seller@local.test/seller를 초기화합니다. 회원 서비스의 일반 로그인을 사용하고 이 파일들은 커밋하지 마세요.',
  );
}
function execute(args) {
  const [service, option, envPath, separator, command, ...commandArgs] = args;
  if (!serviceKeys[service] || option !== '--env' || !envPath || separator !== '--' || !command) {
    throw new Error(
      '사용법: auth-env.cjs exec <member|shopping|commerce|live|notification> --env <env.json> -- <command> [args]',
    );
  }
  let record;
  try {
    record = JSON.parse(fs.readFileSync(path.resolve(envPath), 'utf8'));
  } catch {
    throw new Error('로컬 인증정보 설정을 읽거나 해석할 수 없습니다');
  }
  if (record.purpose !== 'isolated local development only')
    throw new Error('생성된 로컬 인증정보가 필요합니다');
  const env = { ...process.env };
  for (const name of new Set([...publicKeys, ...Object.values(serviceKeys).flat()]))
    delete env[name];
  for (const name of [...publicKeys, ...serviceKeys[service]]) {
    if (typeof record.env?.[name] !== 'string' || !record.env[name])
      throw new Error(`${name} 값이 없습니다`);
    env[name] = record.env[name];
  }
  // 인증정보는 환경변수로만 전달하고 셸 치환이나 명령행 인자로 전달하지 않는다.
  const result = spawnSync(command, commandArgs, { env, stdio: 'inherit', shell: false });
  if (result.error) throw result.error;
  process.exitCode = result.status ?? 1;
}
try {
  const [mode, ...args] = process.argv.slice(2);
  if (mode === 'create') create(args);
  else if (mode === 'exec') execute(args);
  else
    throw new Error(
      '사용법: auth-env.cjs create --access-ttl <duration> --refresh-ttl <duration> [--member-url <origin>] [--out <new-directory>] | exec ...',
    );
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
