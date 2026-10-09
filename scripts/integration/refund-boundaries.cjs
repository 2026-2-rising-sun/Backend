const { commerceRequest, positiveId, couponId, stock, purchase, refund } = require('./p3-fixtures.cjs');
async function refundBoundaries(ctx) {
  const req = commerceRequest(ctx, 'p3-rb-');
  const groupRows = bought => ctx.runtime.sql('commerce', `SELECT count(*) FROM refund_request WHERE payment_group_id=(SELECT id FROM payment_group WHERE group_number='${bought.group.groupNumber}')`);
  const providerRows = requestId => ctx.runtime.sql('commerce', `SELECT count(*) FROM mock_refund_result WHERE refund_request_id=${positiveId(requestId)}`);
  const quantityGroup = await purchase(ctx, req, 'p3-rb-input', { quantity: 2 });
  const invalid = [null, {}, { cartItemIds: [] }, { cartItemIds: null }, { cartItemIds: [quantityGroup.item.id], quantity: 1 }, { cartItemIds: [quantityGroup.item.id, quantityGroup.item.id] }];
  for (let i = 0; i < invalid.length; i++) await req('invalid-' + i, 'POST', quantityGroup.base + '/refunds', { status: 400,
    headers: { 'Idempotency-Key': 'p3-invalid-' + i }, body: invalid[i] });
  await req('outside-item', 'POST', quantityGroup.base + '/refunds', { status: 404,
    headers: { 'Idempotency-Key': 'p3-outside-item' }, body: { cartItemIds: [99999999] } });
  ctx.check('p3-refund-boundary-invalid-no-intake', groupRows(quantityGroup), '0');
  const sameBody = { cartItemIds: [quantityGroup.item.id] };
  const same = await Promise.all([0, 1].map(i => req('same-key-' + i, 'POST', quantityGroup.base + '/refunds',
    { status: [200, 201], headers: { 'Idempotency-Key': 'p3-rb-same-key' }, body: sameBody })));
  ctx.check('p3-refund-boundary-same-key-id', same[0].id, same[1].id);
  ctx.check('p3-refund-boundary-same-key-created-once', [0, 1].map(i => ctx.result.checks.find(c => c.name === 'p3-rb-same-key-' + i).status).sort(), [200, 201]);
  await req('same-key-different-body', 'POST', quantityGroup.base + '/refunds', { status: 409,
    headers: { 'Idempotency-Key': 'p3-rb-same-key' }, body: { cartItemIds: [99999999] } });
  const sameDone = (await ctx.poll('p3-rb-same-done', 'commerce', `${quantityGroup.base}/refunds/${positiveId(same[0].id)}`, ctx.a, b => b.data.status === 'SUCCESS')).data;
  ctx.check('p3-refund-boundary-full-quantity', [sameDone.refundAmount, sameDone.targets[0].quantity], [20000, 2]);
  ctx.check('p3-refund-boundary-same-one-result', providerRows(sameDone.id), '1');

  const otherKeys = await purchase(ctx, req, 'p3-rb-other-keys');
  const duplicates = await Promise.all([0, 1].map(i => req('other-keys-' + i, 'POST', otherKeys.base + '/refunds', { status: [201, 409],
    headers: { 'Idempotency-Key': 'p3-rb-other-keys-' + i }, body: { cartItemIds: [otherKeys.item.id] } })));
  ctx.check('p3-refund-boundary-other-keys-conflict', [0, 1].map(i => ctx.result.checks.find(c => c.name === 'p3-rb-other-keys-' + i).status).sort(), [201, 409]);
  const duplicateWinner = duplicates.find(r => r.id);
  await ctx.poll('p3-rb-other-keys-done', 'commerce', `${otherKeys.base}/refunds/${positiveId(duplicateWinner.id)}`, ctx.a, b => b.data.status === 'SUCCESS');
  ctx.check('p3-refund-boundary-other-keys-one-intake', groupRows(otherKeys), '1');
  ctx.check('p3-refund-boundary-other-keys-one-provider', providerRows(duplicateWinner.id), '1');

  const race = await purchase(ctx, req, 'p3-rb-race', { selections: ctx.products.slice(0, 2).map(productId => ({ productId, quantity: 1 })) });
  const raced = await Promise.all([0, 1].map(i => req('race-' + i, 'POST', race.base + '/refunds', { status: [201, 409],
    headers: { 'Idempotency-Key': 'p3-rb-race-' + i }, ...(i ? { body: { cartItemIds: [race.items[0].id] } } : {}) })));
  ctx.check('p3-refund-boundary-overlap-race', [0, 1].map(i => ctx.result.checks.find(c => c.name === 'p3-rb-race-' + i).status).sort(), [201, 409]);
  const winner = raced.find(r => r.id);
  await ctx.poll('p3-rb-race-done', 'commerce', `${race.base}/refunds/${positiveId(winner.id)}`, ctx.a, b => b.data.status === 'SUCCESS');
  const remaining = race.items.filter(item => !winner.targets.some(target => target.cartItemId === item.id));
  if (remaining.length) await refund(ctx, req, 'p3-rb-race-rest', race, { body: { cartItemIds: remaining.map(item => item.id) } });
  ctx.check('p3-refund-boundary-race-cap', ctx.runtime.sql('commerce', `SELECT sum(refund_amount) FROM refund_request WHERE payment_group_id=(SELECT id FROM payment_group WHERE group_number='${race.group.groupNumber}') AND status='SUCCESS'`), '20000');
  ctx.check('p3-refund-boundary-race-final-state', (await req('race-final-group', 'GET', race.base)).status, 'REFUNDED');

  for (const [name, hours, minutes, accepted] of [['before-window', 168, -1, true], ['at-window', 168, 0, false], ['after-window', 168, 1, false]]) {
    const bought = await purchase(ctx, req, 'p3-rb-' + name);
    // Test-owned persisted time: at-window arrives at or after the boundary; exact equality uses the existing Clock-based PG tests.
    ctx.runtime.sql('commerce', `UPDATE payment_attempt SET resolved_at=clock_timestamp()-INTERVAL '${hours} hours'-INTERVAL '${minutes} minutes' WHERE id=${positiveId(bought.payment.paymentId)}`);
    if (accepted) await refund(ctx, req, 'p3-rb-' + name + '-refund', bought);
    else {
      await req(name + '-refund', 'POST', bought.base + '/refunds', { status: 409, headers: { 'Idempotency-Key': 'p3-rb-' + name } });
      ctx.check('p3-refund-boundary-' + name + '-no-intake', groupRows(bought), '0');
    }
  }
  const now = Date.now();
  const zeroCoupon = await req('zero-coupon', 'POST', '/v1/seller/coupons', { token: ctx.seller, status: 201,
    body: { name: 'Zero charge', fixedDiscount: 10000, issuanceLimit: 10, startsAt: new Date(now - 60000).toISOString(),
      endsAt: new Date(now + 3600000).toISOString(), expiresAt: new Date(now + 7200000).toISOString(), productIds: [ctx.products[0]] } });
  await req('zero-claim', 'POST', `/v1/coupons/${couponId(zeroCoupon.id)}/claims`, { status: 201 });
  const before = stock(ctx);
  const zero = await purchase(ctx, req, 'p3-rb-zero', { couponId: zeroCoupon.id });
  ctx.check('p3-refund-boundary-zero-paid', zero.payment.status, 'SUCCESS');
  ctx.check('p3-refund-boundary-zero-no-payment-provider', ctx.runtime.sql('commerce', `SELECT count(*) FROM mock_gateway_result WHERE attempt_id=${positiveId(zero.payment.paymentId)}`), '0');
  const zeroRefund = await refund(ctx, req, 'p3-rb-zero-refund', zero);
  ctx.check('p3-refund-boundary-zero-amount', [zeroRefund.refundAmount, zeroRefund.cumulativeRefundAmount], [0, 0]);
  ctx.check('p3-refund-boundary-zero-no-refund-provider', providerRows(zeroRefund.id), '0');
  ctx.check('p3-refund-boundary-zero-stock', stock(ctx), before);
  const coupons = await req('zero-coupons-read', 'GET', '/v1/me/coupons');
  ctx.check('p3-refund-boundary-zero-coupon-used', coupons.find(c => c.couponId === zeroCoupon.id).status, 'USED');
  ctx.result.refundBoundaryClock = 'Owned persisted time adjustments for HTTP before/at-or-after/after 168h; exact equality covered separately by existing Clock-based PostgreSQL tests';
  ctx.result.refundBoundariesPassed = true;
}
module.exports = { refundBoundaries };
