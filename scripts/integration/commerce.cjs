async function commerce(ctx) {
  const r = ctx.runtime; const req = ctx.request.bind(ctx); const p = ctx.products; const s = ctx.sales;
  const stock = id => JSON.parse(r.sql('commerce', `SELECT json_build_object('available',available,'reserved',reserved) FROM sales_stock WHERE sales_info_id=${id}`));
  const snapshot = () => r.sql('commerce', "SELECT json_build_object('orders',(SELECT count(*) FROM orders),'payments',(SELECT count(*) FROM payment_attempt),'groups',(SELECT json_agg(t ORDER BY id) FROM (SELECT id,status,total_amount,payment_id FROM payment_group) t),'cart',(SELECT count(*) FROM cart_item),'stock',(SELECT json_agg(t ORDER BY sales_info_id) FROM (SELECT sales_info_id,available,reserved FROM sales_stock) t))");
  ctx.commerceSnapshot = snapshot;
  const buyer = { buyerName: 'Fixture buyer', buyerPhone: '010-0000-0000', expectedTotalAmount: 10000 };
  ctx.buyer = buyer;
  await req('cart-anonymous', 'commerce', 'GET', '/v1/cart/items', { status: 401 });
  const beforeCart = stock(s[0]);
  const item = await req('cart-add-primary', 'commerce', 'POST', '/v1/cart/items', { token: ctx.a, body: { productId: p[0], quantity: 2 }, status: 201 });
  const other = await req('cart-add-secondary', 'commerce', 'POST', '/v1/cart/items', { token: ctx.a, body: { productId: p[1], quantity: 1 }, status: 201 });
  ctx.check('cart-does-not-reserve-stock', stock(s[0]), beforeCart);
  await req('cart-duplicate-product', 'commerce', 'POST', '/v1/cart/items', { token: ctx.a, body: { productId: p[0], quantity: 1 }, status: 409 });
  await req('cart-patch-quantity', 'commerce', 'PATCH', `/v1/cart/items/${other.id}`, { token: ctx.a, body: { quantity: 3 } });
  for (const [name, token] of [['b', ctx.b], ['seller', ctx.seller]]) {
    await req(`cart-other-${name}-patch`, 'commerce', 'PATCH', `/v1/cart/items/${item.id}`, { token, body: { quantity: 1 }, status: 404 });
    await req(`cart-other-${name}-delete`, 'commerce', 'DELETE', `/v1/cart/items/${item.id}`, { token, status: 404 });
    await req(`cart-other-${name}-order`, 'commerce', 'POST', `/v1/cart/items/${item.id}/orders`, { token, body: buyer, headers: { 'X-Idempotency-Key': 'foreign-' + name }, status: 404 });
  }
  await req('cart-order-requires-key', 'commerce', 'POST', `/v1/cart/items/${item.id}/orders`, { token: ctx.a, body: buyer, status: 400 });
  const beforeMismatch = snapshot();
  await req('cart-price-mismatch', 'commerce', 'POST', `/v1/cart/items/${item.id}/orders`, { token: ctx.a, body: buyer, headers: { 'X-Idempotency-Key': 'mismatch' }, status: 409 });
  ctx.check('cart-price-mismatch-no-write', snapshot(), beforeMismatch);
  const selectedBody = { ...buyer, expectedTotalAmount: 20000 };
  const selected = await req('cart-selected-order', 'commerce', 'POST', `/v1/cart/items/${item.id}/orders`, { token: ctx.a, body: selectedBody, headers: { 'X-Idempotency-Key': 'selected-order' }, status: 201 });
  const remaining = await req('cart-remaining-item', 'commerce', 'GET', '/v1/cart/items', { token: ctx.a });
  ctx.check('cart-kept-until-payment-success', remaining.map(i => i.id).sort(), [item.id, other.id].sort());
  const replay = await req('cart-order-replay-after-removal', 'commerce', 'POST', `/v1/cart/items/${item.id}/orders`, { token: ctx.a, body: selectedBody, headers: { 'X-Idempotency-Key': 'selected-order' } });
  ctx.check('cart-replay-same-order', replay.orderNumber, selected.orderNumber);
  await req('cart-order-changed-replay', 'commerce', 'POST', `/v1/cart/items/${item.id}/orders`, { token: ctx.a, body: { ...selectedBody, buyerName: 'Changed' }, headers: { 'X-Idempotency-Key': 'selected-order' }, status: 409 });
  const bItem = await req('cart-b-add', 'commerce', 'POST', '/v1/cart/items', { token: ctx.b, body: { productId: p[0], quantity: 1 }, status: 201 });
  const bOrder = await req('cart-b-same-key', 'commerce', 'POST', `/v1/cart/items/${bItem.id}/orders`, { token: ctx.b, body: buyer, headers: { 'X-Idempotency-Key': 'selected-order' }, status: 201 });
  ctx.check('same-key-different-members-isolated', bOrder.orderNumber !== selected.orderNumber);
  await req('cart-b-cancel-before-stock-race', 'commerce', 'POST', `/v1/orders/${bOrder.orderNumber}/cancel`, { token: ctx.b, status: 204 });
  await req('checkout-member', 'commerce', 'GET', `/v1/orders/checkout?productId=${p[0]}&quantity=1`, { token: ctx.a });
  await req('legacy-password-cannot-authorize', 'commerce', 'GET', `/v1/orders/${selected.orderNumber}`, { headers: { 'X-Order-Password': 'old-password' }, status: 401 });
  for (const [name, token] of [['b', ctx.b], ['seller', ctx.seller]]) {
    await req(`order-other-${name}`, 'commerce', 'GET', `/v1/orders/${selected.orderNumber}`, { token, headers: { 'X-Order-Password': 'old-password' }, status: 404 });
    await req(`cancel-other-${name}`, 'commerce', 'POST', `/v1/orders/${selected.orderNumber}/cancel`, { token, status: 404 });
    await req(`payment-other-${name}`, 'commerce', 'POST', `/v1/orders/${selected.orderNumber}/payments`, { token, status: 404 });
  }
  const paymentBefore = snapshot();
  await req('seller-cannot-set-user-payment-scenario', 'commerce', 'PUT', `/v1/dev/payment-scenarios/${selected.orderNumber}`,
    { token: ctx.seller, body: { scenario: 'INSTANT_FAIL' }, status: 404 });
  for (const [name, body] of [['scenario', { scenario: 'INSTANT_FAIL' }], ['nonempty', { ignored: true }]])
    await req(`payment-reject-${name}`, 'commerce', 'POST', `/v1/orders/${selected.orderNumber}/payments`, { token: ctx.a, body, status: 400 });
  ctx.check('invalid-payment-body-no-write', snapshot(), paymentBefore);
  const paid = await req('payment-empty-object', 'commerce', 'POST', `/v1/orders/${selected.orderNumber}/payments`, { token: ctx.a, body: {} });
  await ctx.poll('payment-success', 'commerce', `/v1/orders/${selected.orderNumber}/payments/${paid.paymentId}`, ctx.a, b => b.status === 'SUCCESS');
  const final = await req('paid-order', 'commerce', 'GET', `/v1/orders/${selected.orderNumber}`, { token: ctx.a });
  ctx.check('order-paid-state', final.status, 'PAID');
  const paidCart = await req('cart-after-payment-success', 'commerce', 'GET', '/v1/cart/items', { token: ctx.a });
  ctx.check('cart-removes-only-selected', paidCart.map(i => i.id), [other.id]);
  await req('cart-delete-own', 'commerce', 'DELETE', `/v1/cart/items/${other.id}`, { token: ctx.a, status: 204 });
  const afterRemoval = await req('cart-order-replay-after-success-removal', 'commerce', 'POST', `/v1/cart/items/${item.id}/orders`, { token: ctx.a, body: selectedBody, headers: { 'X-Idempotency-Key': 'selected-order' } });
  ctx.check('cart-replay-after-success-same-order', afterRemoval.orderNumber, selected.orderNumber);
  for (const [name, token] of [['b', ctx.b], ['seller', ctx.seller]]) await req(`payment-query-other-${name}`, 'commerce', 'GET', `/v1/orders/${selected.orderNumber}/payments/${paid.paymentId}`, { token, status: 404 });
  const history = await req('member-order-history', 'commerce', 'GET', '/v1/orders?page=0&size=20', { token: ctx.a });
  ctx.check('history-excludes-other-member', history.items.every(o => o.orderNumber !== bOrder.orderNumber));
  ctx.direct = async (name, options = {}) => req(name, 'commerce', 'POST', '/v1/orders', { token: options.token || ctx.a, status: 201,
    headers: { 'X-Idempotency-Key': name, 'X-Member-Id': ctx.memberB }, body: { productId: p[1], quantity: 1, ...buyer, memberId: ctx.memberB, ...options.body } });
  const cancel = await ctx.direct('direct-order-owned-by-jwt');
  ctx.check('body-header-cannot-replace-member', r.sql('commerce', `SELECT member_id FROM orders WHERE order_number='${cancel.orderNumber}'`), ctx.memberA);
  const reservedBeforeCancel = stock(s[1]);
  await req('cancel-own-order', 'commerce', 'POST', `/v1/orders/${cancel.orderNumber}/cancel`, { token: ctx.a, status: 204 });
  const canceled = await req('cancel-result', 'commerce', 'GET', `/v1/orders/${cancel.orderNumber}`, { token: ctx.a });
  ctx.check('cancelled-state', canceled.status, 'CANCELLED');
  ctx.check('cancel-restores-once', stock(s[1]), { available: reservedBeforeCancel.available + 1, reserved: reservedBeforeCancel.reserved - 1 });
  const afterCancel = stock(s[1]);
  await req('cancel-replay', 'commerce', 'POST', `/v1/orders/${cancel.orderNumber}/cancel`, { token: ctx.a, status: 204 });
  ctx.check('cancel-replay-no-restock', stock(s[1]), afterCancel);
  for (const [name, paymentBody] of [['absent', undefined], ['json-null', null], ['delayed-success', {}], ['delayed-fail', {}]]) {
    const payer = name.startsWith('delayed') ? ctx.seller : ctx.a;
    const order = await ctx.direct('direct-' + name, { token: payer });
    if (name.startsWith('delayed')) await req('mock-select-' + name, 'commerce', 'PUT', `/v1/dev/payment-scenarios/${order.orderNumber}`,
      { token: ctx.seller, body: { scenario: name === 'delayed-success' ? 'DELAYED_SUCCESS' : 'DELAYED_FAIL' }, status: 204 });
    const pay = await req('payment-body-' + name, 'commerce', 'POST', `/v1/orders/${order.orderNumber}/payments`,
      { token: payer, ...(paymentBody === undefined ? {} : { body: paymentBody }) });
    const expected = name === 'delayed-fail' ? 'FAILED' : 'SUCCESS';
    await ctx.poll('settled-' + name, 'commerce', `/v1/orders/${order.orderNumber}/payments/${pay.paymentId}`, payer, b => b.status === expected);
    await req('payment-wrong-order-' + name, 'commerce', 'GET', `/v1/orders/${cancel.orderNumber}/payments/${pay.paymentId}`, { token: ctx.a, status: 404 });
  }
  await paymentGroups(ctx, req, stock, snapshot);
  const empty = await req('sold-out-cart-add', 'commerce', 'POST', '/v1/cart/items', { token: ctx.a, body: { productId: p[3], quantity: 1 }, status: 201 });
  const emptyBefore = snapshot();
  await req('sold-out-cart-order', 'commerce', 'POST', `/v1/cart/items/${empty.id}/orders`, { token: ctx.a, body: buyer, headers: { 'X-Idempotency-Key': 'sold-out' }, status: 409 });
  ctx.check('sold-out-preserves-cart-and-stock', snapshot(), emptyBefore);
  await req('one-last-stock', 'commerce', 'PATCH', `/v1/sales/${s[3]}/stock`, { token: ctx.seller, body: { delta: 1 } });
  const contenders = await Promise.all([ctx.a, ctx.b].map((token, i) => req('concurrent-last-stock-' + i, 'commerce', 'POST', '/v1/orders',
    { token, body: { productId: p[3], quantity: 1, ...buyer }, headers: { 'X-Idempotency-Key': 'concurrent-' + i }, status: [201, 409] })));
  ctx.check('one-concurrent-winner', ctx.result.checks.filter(c => c.name.startsWith('concurrent-last-stock-')).map(c => c.status).sort(), [201, 409]);
  ctx.check('last-stock-never-negative', stock(s[3]), { available: 0, reserved: 1 });
  const winner = contenders.findIndex(response => response.orderNumber);
  await req('last-stock-winner-cleanup', 'commerce', 'POST', `/v1/orders/${contenders[winner].orderNumber}/cancel`, { token: [ctx.a, ctx.b][winner], status: 204 });
}
module.exports = { commerce };

async function paymentGroups(ctx, req, stock, snapshot) {
  const r = ctx.runtime; const p = ctx.products; const s = ctx.sales;
  await req('group-fixture-price', 'commerce', 'PATCH', `/v1/sales/${s[1]}/price`, { token: ctx.seller, body: { price: 5000 } });
  const a = await req('group-cart-a', 'commerce', 'POST', '/v1/cart/items', { token: ctx.a, body: { productId: p[0], quantity: 2 }, status: 201 });
  const b = await req('group-cart-b', 'commerce', 'POST', '/v1/cart/items', { token: ctx.a, body: { productId: p[1], quantity: 1 }, status: 201 });
  const items = [a, b].map(i => ({ itemId: i.id, version: i.version }));
  const before = snapshot();
  const quote = await req('group-checkout', 'commerce', 'POST', '/v1/cart/checkout', { token: ctx.a, body: { items } });
  ctx.check('group-quote-total', quote.totalAmount, 25000);
  ctx.check('group-quote-no-reservation', snapshot(), before);
  await req('group-duplicate-selection', 'commerce', 'POST', '/v1/cart/checkout', { token: ctx.a, body: { items: [items[0], items[0]] }, status: 400 });
  await req('group-foreign-selection', 'commerce', 'POST', '/v1/cart/checkout', { token: ctx.b, body: { items }, status: 404 });
  await req('group-stale-selection', 'commerce', 'POST', '/v1/cart/checkout', { token: ctx.a, body: { items: [{ ...items[0], version: items[0].version + 1 }, items[1]] }, status: 409 });
  const body = { items, ...ctx.buyer, expectedTotalAmount: 25000 };
  await req('group-create-requires-key', 'commerce', 'POST', '/v1/cart/orders', { token: ctx.a, body, status: 400 });
  await req('group-total-mismatch', 'commerce', 'POST', '/v1/cart/orders', { token: ctx.a, body: { ...body, expectedTotalAmount: 1 }, headers: { 'X-Idempotency-Key': 'group-mismatch' }, status: 409 });
  ctx.check('group-invalid-create-no-write', snapshot(), before);
  const group = await req('group-create', 'commerce', 'POST', '/v1/cart/orders', { token: ctx.a, body, headers: { 'X-Idempotency-Key': 'group-create-key' }, status: 201 });
  ctx.check('group-two-orders-one-total', [group.orders.length, group.totalAmount, group.orders.map(o => o.totalAmount).sort((x,y) => x-y)], [2, 25000, [5000,20000]]);
  const base = `/v1/payment-groups/${group.groupNumber}`;
  const replay = await req('group-create-reversed-replay', 'commerce', 'POST', '/v1/cart/orders', { token: ctx.a, body: { ...body, items: [...items].reverse() }, headers: { 'X-Idempotency-Key': 'group-create-key' } });
  ctx.check('group-replay-same-orders', replay.orders.map(o => o.orderNumber), group.orders.map(o => o.orderNumber));
  const active = await req('group-active-query', 'commerce', 'GET', '/v1/payment-groups/active', { token: ctx.a });
  ctx.check('group-active-reference', active.groupNumber, group.groupNumber);
  await req('group-foreign-query', 'commerce', 'GET', base, { token: ctx.b, status: 404 });
  await req('group-blocks-new-single', 'commerce', 'POST', '/v1/orders', { token: ctx.a, body: { productId: p[0], quantity: 1, ...ctx.buyer }, headers: { 'X-Idempotency-Key': 'group-other-single' }, status: 409 });
  await req('group-forbids-partial-cancel', 'commerce', 'POST', `/v1/orders/${group.orders[0].orderNumber}/cancel`, { token: ctx.a, status: 409 });
  await req('group-forbids-partial-payment', 'commerce', 'POST', `/v1/orders/${group.orders[0].orderNumber}/payments`, { token: ctx.a, status: 409 });
  await req('group-payment-requires-key', 'commerce', 'POST', `${base}/payments`, { token: ctx.a, status: 400 });
  await req('group-invalid-payment-body', 'commerce', 'POST', `${base}/payments`, { token: ctx.a, body: { scenario: 'INSTANT_FAIL' }, headers: { 'X-Idempotency-Key': 'group-payment-key' }, status: 400 });
  const changed = await req('group-cart-edit-while-pending', 'commerce', 'PATCH', `/v1/cart/items/${a.id}`, { token: ctx.a, body: { quantity: 3 } });
  const payment = await req('group-payment-start', 'commerce', 'POST', `${base}/payments`, { token: ctx.a, body: {}, headers: { 'X-Idempotency-Key': 'group-payment-key' }, status: 202 });
  const payReplay = await req('group-payment-replay', 'commerce', 'POST', `${base}/payments`, { token: ctx.a, body: {}, headers: { 'X-Idempotency-Key': 'group-payment-key' }, status: 202 });
  ctx.check('group-one-payment-id', payReplay.paymentId, payment.paymentId);
  await req('group-payment-other-key', 'commerce', 'POST', `${base}/payments`, { token: ctx.a, headers: { 'X-Idempotency-Key': 'different-payment-key' }, status: 409 });
  await req('group-payment-foreign-query', 'commerce', 'GET', `${base}/payments/${payment.paymentId}`, { token: ctx.b, status: 404 });
  await ctx.poll('group-payment-settled', 'commerce', `${base}/payments/${payment.paymentId}`, ctx.a, result => result.status === 'SUCCESS');
  const paid = await req('group-paid-query', 'commerce', 'GET', base, { token: ctx.a });
  ctx.check('group-all-orders-paid', [paid.status, paid.orders.map(o => o.status)], ['PAID',['PAID','PAID']]);
  ctx.check('group-durable-approval-once', r.sql('commerce', `SELECT count(*) FROM mock_gateway_result WHERE attempt_id=${payment.paymentId}`), '1');
  const remaining = await req('group-success-cart', 'commerce', 'GET', '/v1/cart/items', { token: ctx.a });
  ctx.check('group-preserves-intervening-cart-edit', remaining.map(i => [i.id,i.quantity,i.version]), [[a.id,3,changed.version]]);
  await req('group-changed-cart-cleanup', 'commerce', 'DELETE', `/v1/cart/items/${a.id}`, { token: ctx.a, status: 204 });
  await req('group-no-active-after-success', 'commerce', 'GET', '/v1/payment-groups/active', { token: ctx.a, status: 204 });
  await req('group-fixture-price-restore', 'commerce', 'PATCH', `/v1/sales/${s[1]}/price`, { token: ctx.seller, body: { price: 10000 } });

  // A seller can also buy. Keep these cart rows across failure, expiry and cancel.
  const sellerItems = [];
  for (let i=0;i<2;i++) sellerItems.push(await req(`group-seller-cart-${i}`, 'commerce', 'POST', '/v1/cart/items', { token: ctx.seller, body: { productId: p[i], quantity: 1 }, status: 201 }));
  const sellerBody = { items: sellerItems.map(i => ({ itemId:i.id,version:i.version })), ...ctx.buyer, expectedTotalAmount:20000 };
  const sellerBefore = [stock(s[0]),stock(s[1])];
  const failed = await req('group-failure-create', 'commerce', 'POST', '/v1/cart/orders', { token: ctx.seller, body:sellerBody, headers:{'X-Idempotency-Key':'group-failure-create'}, status:201 });
  await req('group-failure-scenario', 'commerce', 'PUT', `/v1/dev/payment-scenarios/${failed.orders[0].orderNumber}`, { token:ctx.seller,body:{scenario:'INSTANT_FAIL'},status:204 });
  await req('group-failure-pay', 'commerce', 'POST', `/v1/payment-groups/${failed.groupNumber}/payments`, { token:ctx.seller,headers:{'X-Idempotency-Key':'group-failure-pay'},status:202 });
  const failedResult = await ctx.poll('group-failure-settled', 'commerce', `/v1/payment-groups/${failed.groupNumber}`, ctx.seller, result=>result.status==='FAILED');
  ctx.check('group-failure-all-orders', failedResult.orders.map(o=>o.status), ['FAILED','FAILED']);
  ctx.check('group-failure-restores-all-stock', [stock(s[0]),stock(s[1])], sellerBefore);
  const expires = await req('group-expiry-create', 'commerce', 'POST', '/v1/cart/orders', { token:ctx.seller,body:sellerBody,headers:{'X-Idempotency-Key':'group-expiry-create'},status:201 });
  // Group scheduler owns expiry. Changing only orders.expires_at would not expire it.
  r.sql('commerce', `UPDATE payment_group SET expires_at=now()-interval '1 second' WHERE group_number='${expires.groupNumber}'`);
  r.sql('commerce', `UPDATE orders SET expires_at=now()-interval '1 second' WHERE payment_group_id=(SELECT id FROM payment_group WHERE group_number='${expires.groupNumber}')`);
  await req('group-expiry-rejects-start', 'commerce', 'POST', `/v1/payment-groups/${expires.groupNumber}/payments`, { token:ctx.seller,headers:{'X-Idempotency-Key':'expired-payment'},status:409 });
  const expiredResult = await ctx.poll('group-expiry-settled', 'commerce', `/v1/payment-groups/${expires.groupNumber}`, ctx.seller, result=>result.status==='EXPIRED',75000);
  ctx.check('group-expiry-all-orders', expiredResult.orders.map(o=>o.status), ['EXPIRED','EXPIRED']);
  ctx.check('group-expiry-restores-all-stock', [stock(s[0]),stock(s[1])], sellerBefore);
  const canceled = await req('group-cancel-create', 'commerce', 'POST', '/v1/cart/orders', { token:ctx.seller,body:sellerBody,headers:{'X-Idempotency-Key':'group-cancel-create'},status:201 });
  for(let i=0;i<2;i++) await req(`group-cancel-${i}`, 'commerce', 'POST', `/v1/payment-groups/${canceled.groupNumber}/cancel`, {token:ctx.seller,status:204});
  ctx.check('group-cancel-replay-restores-once', [stock(s[0]),stock(s[1])], sellerBefore);
  const preserved = await req('group-terminal-cart-kept', 'commerce', 'GET', '/v1/cart/items', {token:ctx.seller});
  ctx.check('group-terminal-cart-preserves-all', preserved.map(i=>i.id).sort(),sellerItems.map(i=>i.id).sort());
  for(const item of sellerItems) await req('group-terminal-cart-cleanup-'+item.id,'commerce','DELETE',`/v1/cart/items/${item.id}`,{token:ctx.seller,status:204});
}
