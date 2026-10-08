const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const YAML = require('../contracts/node_modules/yaml');
const images = require('../contracts/images.json');
const { root, command } = require('./runtime.cjs');
const DELAY_MS = 5000;
const hash = text => crypto.createHash('sha256').update(text).digest('hex');

// Compare rows, not merely counts: a failed request must not update an existing cart/order/stock row.
function snapshot(r) {
  return r.sql('commerce', `SELECT json_build_object(
    'cart',(SELECT json_agg(t ORDER BY id) FROM (SELECT id,member_id,product_id,quantity,version FROM cart_item) t),
    'orders',(SELECT json_agg(t ORDER BY id) FROM (SELECT id,member_id,sales_info_id,quantity,unit_price,total_amount,status,version,source_cart_item_id,source_cart_item_version,payment_group_id FROM orders) t),
    'groups',(SELECT json_agg(t ORDER BY id) FROM (SELECT id,member_id,request_key,fingerprint,total_amount,status,expires_at,payment_id,version FROM payment_group) t),
    'stock',(SELECT json_agg(t ORDER BY sales_info_id) FROM (SELECT sales_info_id,available,reserved FROM sales_stock) t),
    'sales',(SELECT json_agg(t ORDER BY id) FROM (SELECT id,price,status,version FROM sales_info) t),
    'payments',(SELECT json_agg(t ORDER BY id) FROM (SELECT id,order_id,status,version,payment_group_id,request_key FROM payment_attempt) t),
    'approvals',(SELECT json_agg(t ORDER BY attempt_id) FROM (SELECT attempt_id,outcome,authorized_at FROM mock_gateway_result) t))`);
}
function hostUrl(name, port) {
  const inspect = JSON.parse(command('docker', ['inspect', name]))[0];
  return 'http://127.0.0.1:' + inspect.NetworkSettings.Ports[port + '/tcp'][0].HostPort;
}
async function startPair(ctx, provider, normal) {
  const r = ctx.runtime; const contract = ctx.contracts[provider];
  const doc = structuredClone(contract.doc);
  const operationPath = provider === 'commerce' ? '/v1/sales' : '/v1/internal/products/{productId}';
  const operation = doc.paths[operationPath].get;
  operation.responses['200'].content['application/json'].examples = { testNormal: { value: normal } };
  delete operation.responses['200'].content['application/json'].example;
  // A transport 503 is deliberately not added to the published provider contract.
  operation.responses['503'] = { description: 'TEST OVERLAY ONLY: upstream process/proxy unavailable', content: {
    'application/json': { schema: { $ref: '#/components/schemas/ErrorEnvelope' }, examples: {
      transportUnavailable: { value: { success: false, data: null, error: {
        code: 'SERVICE_UNAVAILABLE', message: 'Test upstream process unavailable', requestId: 'test-transport' } } } } } } };
  const overlayText = YAML.stringify(doc);
  const overlay = r.secretFile(`mock-${provider}.yaml`, overlayText);
  const token = crypto.randomBytes(32).toString('base64url'); r.remember(token);
  const prism = r.id + '-mock-' + provider; const proxy = prism + '-proxy';
  const envFile = r.envFile(`mock-${provider}-proxy`, { UPSTREAM_URL: `http://${prism}:4010`, PROXY_CONTROL_TOKEN: token, PORT: '8080' });
  const pair = { prism, proxy, overlay, envFile, token };
  ctx.result.mocks.dependencyFailures.overlays.push({ provider, operationPath, publishedSha256: contract.sha256,
    overlaySha256: hash(overlayText), scope: 'Temporary 200 fixture and unpublished transport 503 response; no provider implementation claim' });
  try {
    r.docker(prism, ['-d', '--network', r.id, '-p', '127.0.0.1::4010', '-v', `${overlay}:/contract.yaml:ro`,
      images.prism, 'mock', '--multiprocess', 'false', '-h', '0.0.0.0', '/contract.yaml']);
    await r.wait(async () => { try { await fetch(hostUrl(prism, 4010), { signal: AbortSignal.timeout(1000) }); return true; } catch { return false; } }, 'Prism ' + provider, 30000);
    // The pinned Prism image already contains Node; no additional runtime image is needed.
    r.docker(proxy, ['-d', '--network', r.id, '-p', '127.0.0.1::8080', '--entrypoint', 'node', '--env-file', envFile,
      '-v', `${path.join(root, 'scripts/integration/mock-proxy.cjs')}:/mock-proxy.cjs:ro`, images.prism, '/mock-proxy.cjs']);
    pair.url = hostUrl(proxy, 8080);
    pair.consumerUrl = r.mode === 'docker' ? `http://${proxy}:8080` : pair.url;
    await r.wait(async () => { try { return (await fetch(pair.url + '/__health', { signal: AbortSignal.timeout(1000) })).status === 200; } catch { return false; } }, 'test proxy', 30000);
    return pair;
  } catch (error) { await disposePair(r, pair); throw error; }
}
async function disposePair(r, pair) {
  for (const name of [pair.proxy, pair.prism]) {
    if (!r.owned(name)) continue;
    const log = command('docker', ['logs', name]);
    fs.writeFileSync(path.join(r.output, name + '.log'), r.sanitize(log));
    command('docker', ['rm', '-f', name]); r.containers.delete(name);
  }
  for (const file of [pair.overlay, pair.envFile]) fs.rmSync(file, { force: true });
}
async function control(pair, prefer, delayMs = 0) {
  const response = await fetch(pair.url + '/__control', { method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Proxy-Control': pair.token },
    body: JSON.stringify({ prefer, delayMs }), signal: AbortSignal.timeout(3000) });
  assert.equal(response.status, 200, 'Test proxy control failed');
}
async function stats(pair) {
  const response = await fetch(pair.url + '/__stats', { headers: { 'X-Proxy-Control': pair.token }, signal: AbortSignal.timeout(3000) });
  assert.equal(response.status, 200, 'Test proxy evidence unavailable');
  return (await response.json()).events;
}
async function evidence(ctx, pair, name, status, delayed = false) {
  let events;
  await ctx.runtime.wait(async () => {
    events = await stats(pair);
    return events.length > 0 && events.every(e => delayed ? e.clientClosedBeforeHeaders : e.headersSentMs !== null);
  }, name + ' network evidence', 5000);
  ctx.check(name + '-upstream', events.every(e => e.upstreamStatus === status && e.upstreamBodyCompleteMs !== null
    && !e.upstreamFailed && (delayed ? e.headersSentMs === null && e.clientClosedBeforeHeaders
      && e.clientClosedMs >= 500 && e.clientClosedMs < e.upstreamBodyCompleteMs + DELAY_MS : e.headersSentMs !== null)));
  if (delayed) {
    const elapsed = ctx.result.checks.find(check => check.name === name).elapsedMs;
    ctx.check(name + '-elapsed', elapsed >= 1000 && elapsed < DELAY_MS * 2);
  }
  ctx.result.mocks.dependencyFailures.requests.push({ name, kind: delayed ? 'real read timeout before response headers' : 'Prism selected HTTP response', events });
  ctx.save();
}
async function mockFailures(ctx) {
  const r = ctx.runtime; const req = ctx.request.bind(ctx); const p = ctx.products;
  assert.equal(typeof r.reconfigure, 'function', 'Runtime reconfigure API is required');
  ctx.result.mocks.internalHttp = 'Real services by default; explicit scoped Prism/transport failure cases below';
  ctx.result.mocks.dependencyFailures = { overlays: [], requests: [], restoredRealUpstreams: false,
    circuitIsolation: 'Client owner is recreated before each failure type; proxy records actual requests',
    readTimeoutMs: 1000, delayedResponseMs: DELAY_MS };
  const sourceSales = await req('mock-source-sales', 'commerce', 'GET', `/v1/sales?productIds=${p[0]}`,
    { headers: { 'X-Service-Token': r.credentials.SHOPPING_COMMERCE_SERVICE_TOKEN } });
  const originalShopping = { SHOPPING_SALES_CLIENT_BASE_URL: r.environments.shopping.SHOPPING_SALES_CLIENT_BASE_URL };
  const salesPair = await startPair(ctx, 'commerce', sourceSales);
  try {
    await r.reconfigure('shopping', { ...originalShopping, SHOPPING_SALES_CLIENT_BASE_URL: salesPair.consumerUrl });
    await control(salesPair, 'code=200, example=testNormal');
    await req('shopping-prism-positive', 'shopping', 'GET', `/v1/products/${p[0]}`);
    await evidence(ctx, salesPair, 'shopping-prism-positive', 200);
    for (const delayed of [false, true]) {
      // Fresh RestClient/Retry/CircuitBreaker state prevents an OPEN circuit masquerading as a timeout.
      await r.reconfigure('shopping', { ...originalShopping, SHOPPING_SALES_CLIENT_BASE_URL: salesPair.consumerUrl });
      const name = delayed ? 'shopping-proxy-timeout' : 'shopping-prism-503';
      await control(salesPair, delayed ? 'code=200, example=testNormal' : 'code=503, example=transportUnavailable', delayed ? DELAY_MS : 0);
      const before = snapshot(r);
      const response = await req(name, 'shopping', 'GET', `/v1/products/${p[0]}`, { status: 503 });
      ctx.check(name + '-error-code', response.error.code, 'SALES_INFO_UNAVAILABLE');
      await evidence(ctx, salesPair, name, delayed ? 200 : 503, delayed);
      ctx.check(name + '-no-write', snapshot(r), before);
    }
  } finally {
    try { await r.reconfigure('shopping', originalShopping); } finally { await disposePair(r, salesPair); }
  }
  const sourceProduct = await req('mock-source-product', 'shopping', 'GET', `/v1/internal/products/${p[2]}`,
    { headers: { 'X-Service-Token': r.credentials.COMMERCE_SHOPPING_SERVICE_TOKEN } });
  const item = await req('mock-cart-fixture', 'commerce', 'POST', '/v1/cart/items',
    { token: ctx.a, body: { productId: p[2], quantity: 1 }, status: 201 });
  const originalCommerce = { COMMERCE_SHOPPING_CLIENT_BASE_URL: r.environments.commerce.COMMERCE_SHOPPING_CLIENT_BASE_URL };
  let productPair;
  try {
    productPair = await startPair(ctx, 'shopping', sourceProduct);
    const useProxy = () => r.reconfigure('commerce', { ...originalCommerce, COMMERCE_SHOPPING_CLIENT_BASE_URL: productPair.consumerUrl });
    await useProxy(); await control(productPair, 'code=200, example=testNormal');
    const positiveBefore = snapshot(r);
    await req('commerce-prism-positive', 'commerce', 'GET', `/v1/orders/checkout?productId=${p[2]}&quantity=1`, { token: ctx.a });
    await evidence(ctx, productPair, 'commerce-prism-positive', 200);
    ctx.check('commerce-prism-positive-no-reservation', snapshot(r), positiveBefore);
    for (const scenario of [
      { prefix: 'commerce-prism-404', code: 404, prefer: 'code=404, example=notFound' },
      { prefix: 'commerce-prism-503', code: 503, prefer: 'code=503, example=transportUnavailable' },
      { prefix: 'commerce-proxy-timeout', code: 503, prefer: 'code=200, example=testNormal', delayed: true }
    ]) {
      await useProxy();
      const calls = [
        { suffix: 'cart-order', path: `/v1/cart/items/${item.id}/orders`, body: ctx.buyer },
        { suffix: 'direct-order', path: '/v1/orders', body: { productId: p[2], quantity: 1, ...ctx.buyer } },
        { suffix: 'group-checkout', path: '/v1/cart/checkout', body: { items: [{ itemId: item.id, version: item.version }] } },
        { suffix: 'group-order', path: '/v1/cart/orders', body: { items: [{ itemId: item.id, version: item.version }], ...ctx.buyer } },
        ...(!scenario.delayed ? [{ suffix: 'cart-add', path: '/v1/cart/items', body: { productId: p[0], quantity: 1 } }] : [])
      ];
      for (const call of calls) {
        const name = scenario.prefix + '-' + call.suffix;
        await control(productPair, scenario.prefer, scenario.delayed ? DELAY_MS : 0);
        const before = snapshot(r);
        const response = await req(name, 'commerce', 'POST', call.path, { token: ctx.a, body: call.body,
          headers: { 'X-Idempotency-Key': name }, status: scenario.code });
        ctx.check(name + '-error-code', response.error.code, scenario.code === 404 ? 'NOT_FOUND' : 'SHOPPING_UNAVAILABLE');
        await evidence(ctx, productPair, name, scenario.delayed ? 200 : scenario.code, scenario.delayed);
        ctx.check(name + '-no-write', snapshot(r), before);
      }
    }
  } finally {
    try { await r.reconfigure('commerce', originalCommerce); }
    finally {
      if (productPair) await disposePair(r, productPair);
      if (r.sql('commerce', `SELECT count(*) FROM cart_item WHERE id=${item.id}`) === '1')
        await req('mock-cart-fixture-cleanup', 'commerce', 'DELETE', `/v1/cart/items/${item.id}`, { token: ctx.a, status: 204 });
    }
  }
  ctx.result.mocks.dependencyFailures.restoredRealUpstreams = true;
  ctx.check('mock-real-upstreams-restored', [r.environments.shopping.SHOPPING_SALES_CLIENT_BASE_URL,
    r.environments.commerce.COMMERCE_SHOPPING_CLIENT_BASE_URL],
  [originalShopping.SHOPPING_SALES_CLIENT_BASE_URL, originalCommerce.COMMERCE_SHOPPING_CLIENT_BASE_URL]);
}
const mockFailureChecks = [
  'shopping-prism-positive', 'shopping-prism-positive-upstream', 'commerce-prism-positive', 'commerce-prism-positive-upstream',
  'commerce-prism-positive-no-reservation', 'mock-real-upstreams-restored',
  ...['shopping-prism-503', 'shopping-proxy-timeout',
    ...['commerce-prism-404', 'commerce-prism-503'].flatMap(prefix => ['cart-order', 'direct-order', 'group-checkout', 'group-order', 'cart-add'].map(s => prefix + '-' + s)),
    ...['cart-order', 'direct-order', 'group-checkout', 'group-order'].map(s => 'commerce-proxy-timeout-' + s)]
    .flatMap(name => [name, name + '-error-code', name + '-upstream', name + '-no-write', ...(name.includes('timeout') ? [name + '-elapsed'] : [])])
];
module.exports = { mockFailures, mockFailureChecks };
