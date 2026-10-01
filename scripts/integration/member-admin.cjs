const crypto = require('node:crypto');
const zlib = require('node:zlib');
const { services } = require('./runtime.cjs');
function png() {
  const chunk = (type, data) => {
    const payload = Buffer.concat([Buffer.from(type), data]); let crc = 0xffffffff;
    for (const b of payload) { crc ^= b; for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0); }
    const size = Buffer.alloc(4); size.writeUInt32BE(data.length); const checksum = Buffer.alloc(4); checksum.writeUInt32BE((crc ^ 0xffffffff) >>> 0);
    return Buffer.concat([size, payload, checksum]);
  };
  const header = Buffer.alloc(13); header.writeUInt32BE(2); header.writeUInt32BE(2, 4); header[8] = 8; header[9] = 2;
  return Buffer.concat([Buffer.from('89504e470d0a1a0a', 'hex'), chunk('IHDR', header),
    chunk('IDAT', zlib.deflateSync(Buffer.from([0, 32, 128, 192, 32, 128, 192, 0, 32, 128, 192, 32, 128, 192]))), chunk('IEND', Buffer.alloc(0))]);
}
async function memberAdmin(ctx) {
  const r = ctx.runtime; const req = ctx.request.bind(ctx);
  for (const service of services) {
    for (const group of ['readiness', 'liveness']) {
      const body = await req(`${service}-${group}`, service, 'GET', `/actuator/health/${group}`, { management: true });
      ctx.check(`${service}-${group}-minimal`, body, { status: 'UP' });
      await req(`${service}-${group}-absent-on-api`, service, 'GET', `/actuator/health/${group}`, { status: 404 });
    }
    await req(`${service}-other-actuator-denied`, service, 'GET', '/actuator/env', { management: true, status: 401 });
  }
  const password = crypto.randomBytes(24).toString('base64url'); r.remember(password);
  const signup = { email: 'member-a@example.test', password, displayName: 'Member A' };
  await req('signup-cannot-assign-role', 'member', 'POST', '/v1/auth/signup', { body: { ...signup, roles: ['ADMIN'] }, status: 400 });
  await req('signup-cannot-assign-id', 'member', 'POST', '/v1/auth/signup', { body: { ...signup, memberId: crypto.randomUUID() }, status: 400 });
  ctx.check('invalid-signup-no-member', r.sql('member', 'SELECT count(*) FROM members'), '0');
  const a = (await req('signup-a', 'member', 'POST', '/v1/auth/signup', { body: signup, status: 201 })).data;
  const b = (await req('signup-b', 'member', 'POST', '/v1/auth/signup', { body: { ...signup, email: 'member-b@example.test', displayName: 'Member B' }, status: 201 })).data;
  ctx.check('signup-user-role', a.roles, ['USER']);
  await req('signup-normalized-duplicate', 'member', 'POST', '/v1/auth/signup', { body: { ...signup, email: '  MEMBER-A@EXAMPLE.TEST  ' }, status: 409 });
  await req('login-wrong-password', 'member', 'POST', '/v1/auth/login', { body: { email: signup.email, password: 'wrong-password' }, status: 401 });
  const login = async (name, email) => (await req('login-' + name, 'member', 'POST', '/v1/auth/login', { body: { email, password } })).data.accessToken;
  ctx.a = await login('a', a.email); ctx.b = await login('b', b.email); ctx.memberA = a.memberId; ctx.memberB = b.memberId;
  r.bootstrapAdmin('admin@example.test', password);
  ctx.admin = await login('admin', 'admin@example.test');
  ctx.check('admin-created-by-cli', r.sql('member', "SELECT count(*) FROM members WHERE role='ADMIN'"), '1');
  const claims = JSON.parse(Buffer.from(ctx.a.split('.')[1], 'base64url').toString());
  ctx.check('member-issued-token-sub', claims.sub, a.memberId);
  ctx.check('member-issued-token-signature', crypto.verify('RSA-SHA256', Buffer.from(ctx.a.split('.').slice(0, 2).join('.')),
    crypto.createPublicKey(r.key), Buffer.from(ctx.a.split('.')[2], 'base64url')));
  const profile = (await req('profile-a', 'member', 'GET', '/v1/members/me', { token: ctx.a })).data;
  ctx.check('profile-owned-by-token', profile.memberId, a.memberId);
  await req('profile-cannot-assign-role', 'member', 'PATCH', '/v1/members/me', { token: ctx.a, body: { displayName: 'A', roles: ['ADMIN'] }, status: 400 });
  await req('profile-update', 'member', 'PATCH', '/v1/members/me', { token: ctx.a, body: { displayName: 'Member A updated' } });
  const mutations = { issuer: { iss: 'untrusted' }, audience: { aud: ['other'] }, expired: { exp: Math.floor(Date.now() / 1000) - 600 },
    subject: { sub: 'not-a-uuid' }, missingJti: { jti: undefined } };
  for (const [name, changes] of Object.entries(mutations)) await req('jwt-' + name, 'member', 'GET', '/v1/members/me', { token: r.sign({ ...claims, ...changes }), status: 401 });
  await req('jwt-wrong-algorithm', 'member', 'GET', '/v1/members/me', { token: r.sign(claims, 'HS256'), status: 401 });
  const tampered = ctx.a.slice(0, ctx.a.lastIndexOf('.') + 1) + 'AAAA';
  await req('jwt-tampered', 'member', 'GET', '/v1/members/me', { token: tampered, status: 401 });
  await req('forged-identity-headers', 'commerce', 'GET', '/v1/cart/items', { headers: { 'X-Member-Id': a.memberId, 'X-Roles': 'ADMIN' }, status: 401 });
  for (const [service, endpoint] of [['shopping', '/v1/admin/products'], ['commerce', '/v1/sales'], ['live', '/v1/admin/broadcasts']]) {
    await req(`${service}-admin-anonymous`, service, 'POST', endpoint, { body: {}, status: 401 });
    await req(`${service}-admin-user`, service, 'POST', endpoint, { token: ctx.a, body: {}, status: 403 });
    await req(`${service}-forged-admin-role`, service, 'POST', endpoint, { token: ctx.a, headers: { 'X-Roles': 'ADMIN' }, body: {}, status: 403 });
  }
  ctx.check('denied-product-writes', r.sql('shopping', 'SELECT count(*) FROM product'), '0');
  ctx.check('denied-sale-writes', r.sql('commerce', 'SELECT count(*) FROM sales_info'), '0');
  ctx.check('denied-broadcast-writes', r.sql('live', 'SELECT count(*) FROM broadcast'), '0');
  const upload = new FormData(); upload.set('file', new Blob([png()], { type: 'image/png' }), 'fixture.png');
  const image = (await req('image-upload-admin', 'shopping', 'POST', '/v1/admin/product-images', { token: ctx.admin, body: upload, status: 201 })).data;
  const fetchedImage = await req('image-public', 'shopping', 'GET', `/v1/product-images/${image.imageId}`);
  ctx.check('image-content-preserved', fetchedImage.sha256, crypto.createHash('sha256').update(png()).digest('hex'));
  ctx.products = []; ctx.sales = [];
  for (let i = 0; i < 4; i++) {
    const product = (await req(`product-create-${i}`, 'shopping', 'POST', '/v1/admin/products', { token: ctx.admin, status: 201,
      headers: { 'X-Idempotency-Key': 'product-' + i }, body: { name: `Integration product ${i}`, description: 'HTTP fixture', mainImageId: image.imageId } })).data;
    ctx.products.push(product.productId);
    const sale = await req(`sale-create-${i}`, 'commerce', 'POST', '/v1/sales', { token: ctx.admin, status: 201,
      body: { productId: product.productId, price: 10000, initialStock: i === 3 ? 0 : 30 } });
    ctx.sales.push(sale.id);
    if (i === 3) {
      await req('empty-ready-cannot-start-sale', 'commerce', 'PATCH', `/v1/sales/${sale.id}/status`, { token: ctx.admin, body: { status: 'ON_SALE' }, status: 409 });
      await req('empty-sale-private', 'commerce', 'PATCH', `/v1/sales/${sale.id}/status`, { token: ctx.admin, body: { status: 'PRIVATE' } });
    }
    await req(`sale-on-${i}`, 'commerce', 'PATCH', `/v1/sales/${sale.id}/status`, { token: ctx.admin, body: { status: 'ON_SALE' } });
  }
  const detail = (await req('public-product-real-commerce', 'shopping', 'GET', `/v1/products/${ctx.products[0]}`)).data;
  ctx.check('public-product-real-price', detail.price, 10000);
  await req('public-products', 'shopping', 'GET', '/v1/products');
  await req('admin-product-list', 'shopping', 'GET', '/v1/admin/products', { token: ctx.admin });
  await req('public-product-missing', 'shopping', 'GET', '/v1/products/99999999', { status: 404 });
}
module.exports = { memberAdmin };
