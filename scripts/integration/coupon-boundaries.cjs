const { commerceRequest, couponId, positiveId, stock, purchase, refund } = require('./p3-fixtures.cjs');
async function couponBoundaries(ctx) {
  const req = commerceRequest(ctx, 'p3-cb-');
  const start = Date.now() + 86400000;
  const settings = { name: 'Coupon boundaries', fixedDiscount: 1000, issuanceLimit: 10000,
    startsAt: new Date(start).toISOString(), endsAt: new Date(start + 720 * 3600000).toISOString(),
    expiresAt: new Date(start + 721 * 3600000).toISOString(), productIds: [positiveId(ctx.products[0])] };
  await req('too-many', 'POST', '/v1/seller/coupons', { token: ctx.seller, status: 400, body: { ...settings, issuanceLimit: 10001 } });
  await req('too-long', 'POST', '/v1/seller/coupons', { token: ctx.seller, status: 400,
    body: { ...settings, endsAt: new Date(start + 720 * 3600000 + 1).toISOString() } });
  const max = await req('max-create', 'POST', '/v1/seller/coupons', { token: ctx.seller, status: 201, body: settings });
  ctx.check('p3-coupon-boundary-max', [max.issuanceLimit, Date.parse(max.endsAt) - Date.parse(max.startsAt)], [10000, 720 * 3600000]);
  await req('not-started-claim', 'POST', `/v1/coupons/${couponId(max.id)}/claims`, { status: 409 });
  const updated = await req('before-start-edit', 'PATCH', `/v1/seller/coupons/${max.id}`, { token: ctx.seller,
    body: { version: max.version, settings: { ...settings, fixedDiscount: 2000 } } });
  ctx.check('p3-coupon-boundary-version', updated.version, max.version + 1);
  await req('stale-edit', 'PATCH', `/v1/seller/coupons/${max.id}`, { token: ctx.seller, status: 409,
    body: { version: max.version, settings } });
  const active = { ...settings, issuanceLimit: 1, startsAt: new Date(Date.now() - 60000).toISOString(),
    endsAt: new Date(Date.now() + 3600000).toISOString(), expiresAt: new Date(Date.now() + 7200000).toISOString() };
  const last = await req('last-create', 'POST', '/v1/seller/coupons', { token: ctx.seller, status: 201, body: active });
  await req('started-edit', 'PATCH', `/v1/seller/coupons/${couponId(last.id)}`, { token: ctx.seller, status: 409,
    body: { version: last.version, settings: active } });
  await req('foreign-target', 'POST', '/v1/seller/coupons', { token: ctx.seller, status: 403,
    body: { ...active, productIds: [ctx.p3OtherProduct] } });
  await Promise.all([ctx.a, ctx.b].map((token, i) => req('last-claim-' + i, 'POST', `/v1/coupons/${last.id}/claims`, { token, status: [201, 409] })));
  const statuses = [0, 1].map(i => ctx.result.checks.find(c => c.name === 'p3-cb-last-claim-' + i).status).sort();
  ctx.check('p3-coupon-boundary-last-one', statuses, [201, 409]);
  ctx.check('p3-coupon-boundary-last-persisted', ctx.runtime.sql('commerce', `SELECT count(*) FROM member_coupon WHERE coupon_id='${last.id}'`), '1');

  const createActive = name => req(name + '-create', 'POST', '/v1/seller/coupons', { token: ctx.seller, status: 201, body: { ...active, name } });
  const held = await createActive('Held past event');
  await req('held-claim', 'POST', `/v1/coupons/${couponId(held.id)}/claims`, { status: 201 });
  // Owned fixture clock fields are adjusted; no monetary result or stock is forged.
  ctx.runtime.sql('commerce', `UPDATE coupon_definition SET ends_at=clock_timestamp()-INTERVAL '20 seconds' WHERE id='${held.id}'`);
  await req('event-ended-new-claim', 'POST', `/v1/coupons/${held.id}/claims`, { token: ctx.b, status: 409 });
  const list = await req('held-list-read', 'GET', '/v1/me/coupons');
  ctx.check('p3-coupon-boundary-held-valid', list.find(c => c.couponId === held.id).status, 'AVAILABLE');
  await req('held-preview', 'GET', `/v1/products/${ctx.products[0]}/order-preview?quantity=1&couponId=${held.id}`);
  const before = stock(ctx);
  const bought = await purchase(ctx, req, 'p3-expiry-reservation', { couponId: held.id, beforePay: async () => {
    ctx.runtime.sql('commerce', `UPDATE coupon_definition SET expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id='${held.id}'`);
  } });
  const after = await req('reserved-expired-list-read', 'GET', '/v1/me/coupons');
  ctx.check('p3-coupon-boundary-reserved-expiry-paid', after.find(c => c.couponId === held.id).status, 'USED');
  const done = await refund(ctx, req, 'p3-expiry-refund', bought);
  ctx.check('p3-coupon-boundary-expiry-refund-amount', done.refundAmount, 9000);
  ctx.check('p3-coupon-boundary-expiry-stock', stock(ctx), before);
  const expired = await createActive('Expired without reservation');
  await req('expired-claim', 'POST', `/v1/coupons/${couponId(expired.id)}/claims`, { status: 201 });
  ctx.runtime.sql('commerce', `UPDATE coupon_definition SET ends_at=clock_timestamp()-INTERVAL '20 seconds',expires_at=clock_timestamp()-INTERVAL '1 second' WHERE id='${expired.id}'`);
  const expiredList = await req('expired-list-read', 'GET', '/v1/me/coupons');
  ctx.check('p3-coupon-boundary-expired-state', expiredList.find(c => c.couponId === expired.id).status, 'EXPIRED');
  await req('expired-preview', 'GET', `/v1/products/${ctx.products[0]}/order-preview?quantity=1&couponId=${expired.id}`, { status: 409 });
  ctx.result.couponBoundariesPassed = true;
}
module.exports = { couponBoundaries };
