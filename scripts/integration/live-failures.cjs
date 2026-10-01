const { command, delay, services } = require('./runtime.cjs');
async function liveFailures(ctx) {
  const req = ctx.request.bind(ctx); const r = ctx.runtime; const p = ctx.products;
  const tokenHeader = key => ({ 'X-Service-Token': r.credentials[key + '_SERVICE_TOKEN'] });
  for (const [name, service, endpoint, caller] of [
    ['commerce-shopping', 'shopping', `/v1/internal/products/${p[0]}`, 'COMMERCE_SHOPPING'],
    ['live-shopping', 'shopping', `/v1/internal/products?ids=${p[0]}&ids=${p[1]}`, 'LIVE_SHOPPING'],
    ['shopping-commerce', 'commerce', `/v1/sales?productIds=${p[0]}`, 'SHOPPING_COMMERCE'],
    ['live-commerce', 'commerce', `/v1/sales?productIds=${p[0]}`, 'LIVE_COMMERCE']]) {
    await req('caller-valid-' + name, service, 'GET', endpoint, { headers: tokenHeader(caller) });
    await req('caller-missing-' + name, service, 'GET', endpoint, { status: 401 });
    await req('caller-admin-jwt-' + name, service, 'GET', endpoint, { token: ctx.admin, status: 401 });
    await req('caller-forged-name-' + name, service, 'GET', endpoint, { headers: { 'X-Service-Caller': 'live', 'X-Service-Token': 'untrusted' }, status: 401 });
  }
  await req('caller-token-cannot-shop-admin', 'shopping', 'POST', '/v1/admin/products', { headers: tokenHeader('COMMERCE_SHOPPING'), body: {}, status: 401 });
  await req('caller-token-cannot-buy', 'commerce', 'GET', '/v1/cart/items', { headers: tokenHeader('SHOPPING_COMMERCE'), status: 401 });
  await req('wrong-target-credential', 'shopping', 'GET', `/v1/internal/products/${p[0]}`, { headers: tokenHeader('SHOPPING_COMMERCE'), status: 401 });
  const create = { title: 'Integration broadcast', scheduledAt: new Date(Date.now() + 60000).toISOString(),
    channelArn: 'arn:aws:ivs:ap-northeast-2:000000000000:channel/local-fixture', playbackUrl: 'https://fixture.invalid/local.m3u8' };
  const broadcast = (await req('broadcast-create', 'live', 'POST', '/v1/admin/broadcasts', { token: ctx.admin, body: create,
    headers: { 'Idempotency-Key': 'broadcast' }, status: 201 })).data;
  let version = broadcast.version; const links = [];
  for (let i = 0; i < 2; i++) {
    const link = (await req('broadcast-link-' + i, 'live', 'POST', `/v1/admin/broadcasts/${broadcast.id}/products`,
      { token: ctx.admin, body: { productId: p[i], expectedVersion: version }, status: 201 })).data;
    links.push(link.linkId); version = link.broadcastVersion;
  }
  const list = (await req('broadcast-products-real-upstream', 'live', 'GET', `/v1/admin/broadcasts/${broadcast.id}/products`, { token: ctx.admin })).data;
  ctx.check('live-real-products-and-sales', list.every(item => !item.missing && item.purchasable) && list.length === 2);
  const reordered = (await req('broadcast-reorder', 'live', 'PUT', `/v1/admin/broadcasts/${broadcast.id}/products/order`,
    { token: ctx.admin, body: { linkIds: [...links].reverse(), expectedVersion: version } })).data;
  version = reordered[0].broadcastVersion;
  await req('broadcast-stale-edit', 'live', 'PATCH', `/v1/admin/broadcasts/${broadcast.id}?version=${broadcast.version}`, { token: ctx.admin, body: { title: 'Stale' }, status: 409 });
  await req('broadcast-start', 'live', 'POST', `/v1/admin/broadcasts/${broadcast.id}/start?expectedVersion=${version}`, { token: ctx.admin });
  await req('broadcast-public-list', 'live', 'GET', '/v1/broadcasts');
  const publicBroadcast = (await req('broadcast-public-detail', 'live', 'GET', `/v1/broadcasts/${broadcast.id}`)).data;
  ctx.check('public-broadcast-hides-channel', !Object.hasOwn(publicBroadcast, 'channelArn'));
  await req('broadcast-public-products', 'live', 'GET', `/v1/broadcasts/${broadcast.id}/products`);
  await req('broadcast-end', 'live', 'POST', `/v1/admin/broadcasts/${broadcast.id}/end`, { token: ctx.admin });
  await req('normal-purchase-after-broadcast-end', 'commerce', 'GET', `/v1/orders/checkout?productId=${p[0]}&quantity=1`, { token: ctx.a });
  const shoppingAccess = r.access('shopping'); const commerceAccess = r.access('commerce');
  ctx.check('live-repeated-ids-reached-shopping', shoppingAccess.split('\n').some(line => line.includes(`ids=${p[0]}&ids=${p[1]}`) && line.includes('broadcast-products-real-upstream')));
  ctx.check('live-bulk-reached-commerce', commerceAccess.split('\n').some(line => line.includes('productIds=') && line.includes('broadcast-products-real-upstream')));
  ctx.result.upstreamEvidence = r.sanitize({ shopping: shoppingAccess.split('\n').filter(line => line.includes('broadcast-products-real-upstream')),
    commerce: commerceAccess.split('\n').filter(line => line.includes('broadcast-products-real-upstream')) });
  await r.stop('commerce');
  try {
    await req('commerce-down-public-503', 'shopping', 'GET', `/v1/products/${p[0]}`, { status: 503 });
    await req('commerce-down-internal-still-real', 'shopping', 'GET', `/v1/internal/products/${p[0]}`, { headers: tokenHeader('LIVE_SHOPPING') });
    const admin = (await req('commerce-down-admin-unknown', 'shopping', 'GET', `/v1/admin/products/${p[0]}`, { token: ctx.admin })).data;
    ctx.check('admin-keeps-product-on-sales-outage', admin.productId, p[0]);
  } finally { await r.restart('commerce'); }
  // Recreate the outbound client's circuit state by restarting its owner, not by substituting a mock.
  await r.stop('shopping'); await r.restart('shopping');
  const outageItem = await req('outage-cart-fixture', 'commerce', 'POST', '/v1/cart/items', { token: ctx.a, body: { productId: p[2], quantity: 1 }, status: 201 });
  const before = ctx.commerceSnapshot();
  await r.stop('shopping');
  try {
    await req('shopping-down-cart-order-503', 'commerce', 'POST', `/v1/cart/items/${outageItem.id}/orders`, { token: ctx.a, body: ctx.buyer,
      headers: { 'X-Idempotency-Key': 'outage-cart-order' }, status: 503 });
    ctx.check('shopping-outage-preserves-cart-order-stock', ctx.commerceSnapshot(), before);
  } finally { await r.restart('shopping'); }
  command('docker', ['pause', r.pg]);
  try {
    for (const service of services) {
      await req(`${service}-db-down-readiness`, service, 'GET', '/actuator/health/readiness', { management: true, status: 503 });
      await req(`${service}-db-down-liveness`, service, 'GET', '/actuator/health/liveness', { management: true });
    }
  } finally {
    command('docker', ['unpause', r.pg]);
    for (const service of services) await r.waitReady(service);
  }
  await delay(100);
}
module.exports = { liveFailures };
