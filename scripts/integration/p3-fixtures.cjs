const assert = require('node:assert/strict');
const positiveId = n => { assert(Number.isSafeInteger(n) && n > 0); return n; };
const couponId = s => { assert(/^[0-9a-f-]{36}$/.test(s)); return s; };
function commerceRequest(ctx, prefix) {
  return async (name, method, url, options = {}) => {
    const body = await ctx.request(prefix + name, 'commerce', method, url, { token: ctx.a, ...options });
    return body?.data ?? body;
  };
}
function stock(ctx) {
  return JSON.parse(ctx.runtime.sql('commerce', `SELECT json_build_object('available',available,'reserved',reserved) FROM sales_stock WHERE sales_info_id=${positiveId(ctx.sales[0])}`));
}
async function purchase(ctx, req, name, options = {}) {
  const selections = options.selections ?? [{ productId: positiveId(ctx.products[0]), quantity: options.quantity ?? 1 }];
  const items = [];
  for (let i = 0; i < selections.length; i++) items.push(await req(name + '-cart-' + i, 'POST', '/v1/cart/items', { status: 201,
    body: { productId: positiveId(selections[i].productId), quantity: positiveId(selections[i].quantity) } }));
  const item = items[0];
  const group = await req(name + '-group', 'POST', '/v1/cart/orders', { status: 201, headers: { 'X-Idempotency-Key': name + '-group' },
    body: { items: items.map(i => ({ itemId: positiveId(i.id), version: i.version })), buyerName: 'P3 fixture', buyerPhone: '01012345678',
      expectedTotalAmount: 10000 * selections.reduce((sum, s) => sum + s.quantity, 0), ...(options.couponId ? { couponId: couponId(options.couponId) } : {}) } });
  assert(/^[A-Za-z0-9-]{1,64}$/.test(group.groupNumber));
  const base = '/v1/payment-groups/' + group.groupNumber;
  if (options.beforePay) await options.beforePay();
  const payment = await req(name + '-pay', 'POST', base + '/payments', { status: group.payableAmount === 0 ? 200 : 202,
    headers: { 'X-Idempotency-Key': name + '-pay' } });
  await ctx.poll(name + '-paid', 'commerce', `${base}/payments/${positiveId(payment.paymentId)}`, ctx.a, b => b.status === 'SUCCESS');
  return { item, items, group, base, payment };
}
async function refund(ctx, req, name, bought, options = {}) {
  const requested = await req(name + '-request', 'POST', bought.base + '/refunds', { status: 201,
    headers: { 'Idempotency-Key': name }, ...options });
  const done = await ctx.poll(name + '-done', 'commerce', `${bought.base}/refunds/${positiveId(requested.id)}`, ctx.a, b => b.data.status === 'SUCCESS');
  return done.data;
}
module.exports = { positiveId, couponId, commerceRequest, stock, purchase, refund };
