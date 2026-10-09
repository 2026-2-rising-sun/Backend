const assert = require('node:assert/strict');
const crypto = require('node:crypto');

async function couponRefundFlow(ctx) {
  const r = ctx.runtime;
  const req = async (name, method, url, options = {}) => {
    const response = await ctx.request('p3-' + name, 'commerce', method, url, { token: ctx.a, ...options });
    return response?.data ?? response;
  };
  const id = n => { assert(Number.isSafeInteger(n) && n > 0); return n; };
  const uuid = s => { assert(/^[0-9a-f-]{36}$/.test(s)); return s; };
  const password = crypto.randomBytes(24).toString('base64url'); r.remember(password);
  r.bootstrapSeller('seller-b@example.test', password);
  const sellerB = (await ctx.request('p3-login-seller-b', 'member', 'POST', '/v1/auth/login',
    { body: { email: 'seller-b@example.test', password } })).data.accessToken;
  const product = (await ctx.request('p3-owned-product-details', 'shopping', 'GET', `/v1/admin/products/${id(ctx.products[0])}`,
    { token: ctx.seller })).data;
  const other = (await ctx.request('p3-product-seller-b', 'shopping', 'POST', '/v1/admin/products', { token: sellerB, status: 201,
    headers: { 'X-Idempotency-Key': 'p3-seller-b-product' },
    body: { name: 'Other seller product', description: 'Non-target coupon fixture', mainImageId: id(product.mainImageId) } })).data;
  const saleB = await req('sale-seller-b', 'POST', '/v1/sales', { token: sellerB, status: 201,
    body: { productId: id(other.productId), price: 5000, initialStock: 30 } });
  await req('sale-seller-b-on', 'PATCH', `/v1/sales/${id(saleB.id)}/status`, { token: sellerB, body: { status: 'ON_SALE' } });
  const settings = { name: 'Mixed refund coupon', fixedDiscount: 1000, issuanceLimit: 10000,
    startsAt: new Date(Date.now() - 60000).toISOString(), endsAt: new Date(Date.now() + 3600000).toISOString(),
    expiresAt: new Date(Date.now() + 7200000).toISOString(), productIds: ctx.products.slice(0, 2) };
  const coupon = await req('coupon-create', 'POST', '/v1/seller/coupons', { token: ctx.seller, body: settings, status: 201 });
  uuid(coupon.id);
  await req('coupon-b-cannot-manage', 'GET', `/v1/seller/coupons/${coupon.id}`, { token: sellerB, status: 403 });
  const claim = await req('coupon-claim', 'POST', `/v1/coupons/${coupon.id}/claims`, { status: 201 });
  const again = await req('coupon-claim-replay', 'POST', `/v1/coupons/${coupon.id}/claims`);
  ctx.check('p3-claim-idempotent', again.id, claim.id);
  const couponStatus = async name => (await req(name, 'GET', '/v1/me/coupons')).find(c => c.couponId === coupon.id)?.status;
  const sales = [ctx.sales[0], ctx.sales[1], saleB.id].map(id);
  const stock = () => sales.map(s => JSON.parse(r.sql('commerce', `SELECT json_build_object('available',available,'reserved',reserved) FROM sales_stock WHERE sales_info_id=${s}`)));
  const before = stock();
  const products = [ctx.products[0], ctx.products[1], other.productId].map(id);
  const items = [];
  for (let i = 0; i < products.length; i++) items.push(await req('cart-' + i, 'POST', '/v1/cart/items',
    { body: { productId: products[i], quantity: i === 0 ? 2 : 1 }, status: 201 }));
  const selection = items.map(item => ({ itemId: id(item.id), version: item.version }));
  const quote = await req('checkout', 'POST', '/v1/cart/checkout', { body: { items: selection, couponId: coupon.id } });
  ctx.check('p3-discount-targets-only', quote.items.map(i => i.discountAmount), [667, 333, 0]);
  ctx.check('p3-quote-total', [quote.totalAmount, quote.discountAmount, quote.payableAmount], [35000, 1000, 34000]);
  ctx.check('p3-preview-no-stock-reservation', stock(), before);
  ctx.check('p3-preview-no-coupon-reservation', await couponStatus('coupon-after-preview'), 'AVAILABLE');
  const body = { items: selection, couponId: coupon.id, expectedTotalAmount: 35000, buyerName: 'Coupon refund buyer', buyerPhone: '01012345678' };
  const group = await req('group-create', 'POST', '/v1/cart/orders', { status: 201, body, headers: { 'X-Idempotency-Key': 'p3-group' } });
  assert(/^[A-Za-z0-9-]{1,64}$/.test(group.groupNumber));
  const base = '/v1/payment-groups/' + group.groupNumber;
  ctx.check('p3-group-allocation-snapshot', group.orders.map(o => [o.discountAmount, o.payableAmount]), [[667, 19333], [333, 9667], [0, 5000]]);
  ctx.check('p3-group-coupon-reserved', await couponStatus('coupon-reserved'), 'RESERVED');
  const replay = await req('group-replay', 'POST', '/v1/cart/orders', { body, headers: { 'X-Idempotency-Key': 'p3-group' } });
  ctx.check('p3-group-replay-same-id', replay.groupNumber, group.groupNumber);
  const payment = await req('pay', 'POST', base + '/payments', { status: 202, headers: { 'X-Idempotency-Key': 'p3-pay' } });
  await ctx.poll('p3-paid', 'commerce', `${base}/payments/${id(payment.paymentId)}`, ctx.a, b => b.status === 'SUCCESS');
  ctx.check('p3-coupon-used', await couponStatus('coupon-used-read'), 'USED');
  const sold = before.map((s, i) => ({ available: s.available - (i === 0 ? 2 : 1), reserved: s.reserved }));
  ctx.check('p3-paid-stock', stock(), sold);
  ctx.check('p3-paid-cart-cleaned', r.sql('commerce', `SELECT count(*) FROM cart_item WHERE id IN (${items.map(i => id(i.id)).join(',')})`), '0');
  await req('group-other-member', 'GET', base, { token: ctx.b, status: 404 });
  await req('refund-other-member', 'POST', base + '/refunds', { token: ctx.b, status: 404, headers: { 'Idempotency-Key': 'other-refund' } });
  const firstBody = { cartItemIds: [items[0].id] };
  const first = await req('refund-selected', 'POST', base + '/refunds', { status: 201, body: firstBody, headers: { 'Idempotency-Key': 'p3-first-refund' } });
  ctx.check('p3-refund-whole-order-quantity', first.targets.map(t => [t.cartItemId, t.quantity, t.refundAmount]), [[items[0].id, 2, 19333]]);
  const firstDone = (await ctx.poll('p3-first-refund-done', 'commerce', `${base}/refunds/${id(first.id)}`, ctx.a, b => b.data.status === 'SUCCESS')).data;
  ctx.check('p3-first-cumulative', firstDone.cumulativeRefundAmount, 19333);
  const partial = await req('partial-group', 'GET', base);
  ctx.check('p3-partial-state', [partial.status, ...partial.orders.map(o => o.status)], ['PAID', 'REFUNDED', 'PAID', 'PAID']);
  ctx.check('p3-partial-stock', stock(), [before[0], sold[1], sold[2]]);
  ctx.check('p3-partial-coupon-not-restored', await couponStatus('coupon-partial-refund'), 'USED');
  const sellerAFirst = await req('seller-own-selected', 'GET', `/v1/seller/refunds/${first.id}`, { token: ctx.seller });
  ctx.check('p3-seller-selected-own-amount', [sellerAFirst.refundAmount, sellerAFirst.targets.length], [19333, 1]);
  await req('seller-b-cannot-see-selected', 'GET', `/v1/seller/refunds/${first.id}`, { token: sellerB, status: 404 });
  await req('refund-whole-includes-already-refunded', 'POST', base + '/refunds', { status: 409, headers: { 'Idempotency-Key': 'p3-whole-conflict' } });
  const last = await req('refund-remaining-explicit', 'POST', base + '/refunds', { status: 201,
    body: { cartItemIds: items.slice(1).map(i => i.id) }, headers: { 'Idempotency-Key': 'p3-last-refund' } });
  ctx.check('p3-remaining-only', [last.refundAmount, ...last.targets.map(t => t.cartItemId).sort((a, b) => a - b)], [14667, items[1].id, items[2].id]);
  const done = (await ctx.poll('p3-last-refund-done', 'commerce', `${base}/refunds/${id(last.id)}`, ctx.a, b => b.data.status === 'SUCCESS')).data;
  ctx.check('p3-final-cumulative', done.cumulativeRefundAmount, 34000);
  const whole = await req('whole-group', 'GET', base);
  ctx.check('p3-all-refunded-state', [whole.status, ...whole.orders.map(o => o.status)], ['REFUNDED', 'REFUNDED', 'REFUNDED', 'REFUNDED']);
  ctx.check('p3-whole-stock-restored', stock(), before);
  const sellerLast = await req('seller-a-remaining', 'GET', `/v1/seller/refunds/${last.id}`, { token: ctx.seller });
  const otherLast = await req('seller-b-remaining', 'GET', `/v1/seller/refunds/${last.id}`, { token: sellerB });
  ctx.check('p3-seller-amounts-masked', [sellerLast.refundAmount, sellerLast.cumulativeRefundAmount, sellerLast.targets.length,
    otherLast.refundAmount, otherLast.cumulativeRefundAmount, otherLast.targets.length], [9667, 29000, 1, 5000, 5000, 1]);
  ctx.check('p3-seller-no-group-disclosure', [Object.hasOwn(sellerLast, 'paymentGroupNumber'), Object.hasOwn(otherLast, 'paymentGroupNumber')], [false, false]);
  const old = await req('refund-replay', 'POST', base + '/refunds', { body: firstBody, headers: { 'Idempotency-Key': 'p3-first-refund' } });
  ctx.check('p3-refund-replay-same-id', old.id, first.id);
  ctx.check('p3-replay-no-restock', stock(), before);
  ctx.check('p3-only-two-provider-results', r.sql('commerce', `SELECT count(*) FROM mock_refund_result WHERE refund_request_id IN (${id(first.id)},${id(last.id)})`), '2');
  ctx.check('p3-original-payment-success', (await req('original-payment', 'GET', `${base}/payments/${payment.paymentId}`)).status, 'SUCCESS');
  ctx.check('p3-refund-coupon-used', await couponStatus('coupon-whole-refund'), 'USED');
  const reclaimed = await req('coupon-reclaim', 'POST', `/v1/coupons/${coupon.id}/claims`);
  ctx.check('p3-refund-no-new-coupon', [reclaimed.id, reclaimed.status], [claim.id, 'USED']);
  ctx.check('p3-issuance-not-reset', (await req('coupon-final-definition', 'GET', `/v1/seller/coupons/${coupon.id}`, { token: ctx.seller })).issuedCount, 1);
  const allItem = await req('all-cart', 'POST', '/v1/cart/items', { body: { productId: products[0], quantity: 1 }, status: 201 });
  const allGroup = await req('all-group', 'POST', '/v1/cart/orders', { status: 201, headers: { 'X-Idempotency-Key': 'p3-all-group' },
    body: { items: [{ itemId: allItem.id, version: allItem.version }], buyerName: 'Whole refund', buyerPhone: '01012345678', expectedTotalAmount: 10000 } });
  assert(/^[A-Za-z0-9-]{1,64}$/.test(allGroup.groupNumber));
  const allBase = '/v1/payment-groups/' + allGroup.groupNumber;
  const allPayment = await req('all-pay', 'POST', allBase + '/payments', { status: 202, headers: { 'X-Idempotency-Key': 'p3-all-pay' } });
  await ctx.poll('p3-all-paid', 'commerce', `${allBase}/payments/${id(allPayment.paymentId)}`, ctx.a, b => b.status === 'SUCCESS');
  const all = await req('all-refund-omitted-body', 'POST', allBase + '/refunds', { status: 201, headers: { 'Idempotency-Key': 'p3-all-refund' } });
  ctx.check('p3-omitted-body-whole-group', [all.refundAmount, ...all.targets.map(t => t.cartItemId)], [10000, allItem.id]);
  await ctx.poll('p3-all-refunded', 'commerce', `${allBase}/refunds/${id(all.id)}`, ctx.a, b => b.data.status === 'SUCCESS');
  ctx.check('p3-whole-omitted-state', (await req('all-final-group', 'GET', allBase)).status, 'REFUNDED');
  ctx.check('p3-whole-omitted-stock', stock(), before);
  ctx.result.couponRefundFlowPassed = true;
}
module.exports = { couponRefundFlow };
