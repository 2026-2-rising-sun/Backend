async function commerce(ctx) {
  const r = ctx.runtime; const req = ctx.request.bind(ctx); const p = ctx.products; const s = ctx.sales;
  const stock = id => JSON.parse(r.sql('commerce', `SELECT json_build_object('available',available,'reserved',reserved) FROM sales_stock WHERE sales_info_id=${id}`));
  const snapshot = () => r.sql('commerce', "SELECT json_build_object('orders',(SELECT count(*) FROM orders),'payments',(SELECT count(*) FROM payment_attempt),'cart',(SELECT count(*) FROM cart_item),'stock',(SELECT json_agg(t ORDER BY sales_info_id) FROM (SELECT sales_info_id,available,reserved FROM sales_stock) t))");
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
  for (const [name, token] of [['b', ctx.b], ['admin', ctx.admin]]) {
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
  ctx.check('cart-removes-only-selected', remaining.map(i => i.id), [other.id]);
  const replay = await req('cart-order-replay-after-removal', 'commerce', 'POST', `/v1/cart/items/${item.id}/orders`, { token: ctx.a, body: selectedBody, headers: { 'X-Idempotency-Key': 'selected-order' } });
  ctx.check('cart-replay-same-order', replay.orderNumber, selected.orderNumber);
  await req('cart-order-changed-replay', 'commerce', 'POST', `/v1/cart/items/${item.id}/orders`, { token: ctx.a, body: { ...selectedBody, buyerName: 'Changed' }, headers: { 'X-Idempotency-Key': 'selected-order' }, status: 409 });
  const bItem = await req('cart-b-add', 'commerce', 'POST', '/v1/cart/items', { token: ctx.b, body: { productId: p[0], quantity: 1 }, status: 201 });
  const bOrder = await req('cart-b-same-key', 'commerce', 'POST', `/v1/cart/items/${bItem.id}/orders`, { token: ctx.b, body: buyer, headers: { 'X-Idempotency-Key': 'selected-order' }, status: 201 });
  ctx.check('same-key-different-members-isolated', bOrder.orderNumber !== selected.orderNumber);
  await req('cart-delete-own', 'commerce', 'DELETE', `/v1/cart/items/${other.id}`, { token: ctx.a, status: 204 });
  await req('checkout-member', 'commerce', 'GET', `/v1/orders/checkout?productId=${p[0]}&quantity=1`, { token: ctx.a });
  await req('legacy-password-cannot-authorize', 'commerce', 'GET', `/v1/orders/${selected.orderNumber}`, { headers: { 'X-Order-Password': 'old-password' }, status: 401 });
  for (const [name, token] of [['b', ctx.b], ['admin', ctx.admin]]) {
    await req(`order-other-${name}`, 'commerce', 'GET', `/v1/orders/${selected.orderNumber}`, { token, headers: { 'X-Order-Password': 'old-password' }, status: 404 });
    await req(`cancel-other-${name}`, 'commerce', 'POST', `/v1/orders/${selected.orderNumber}/cancel`, { token, status: 404 });
    await req(`payment-other-${name}`, 'commerce', 'POST', `/v1/orders/${selected.orderNumber}/payments`, { token, status: 404 });
  }
  const paymentBefore = snapshot();
  await req('admin-cannot-set-user-payment-scenario', 'commerce', 'PUT', `/v1/dev/payment-scenarios/${selected.orderNumber}`,
    { token: ctx.admin, body: { scenario: 'INSTANT_FAIL' }, status: 404 });
  for (const [name, body] of [['scenario', { scenario: 'INSTANT_FAIL' }], ['nonempty', { ignored: true }]])
    await req(`payment-reject-${name}`, 'commerce', 'POST', `/v1/orders/${selected.orderNumber}/payments`, { token: ctx.a, body, status: 400 });
  ctx.check('invalid-payment-body-no-write', snapshot(), paymentBefore);
  const paid = await req('payment-empty-object', 'commerce', 'POST', `/v1/orders/${selected.orderNumber}/payments`, { token: ctx.a, body: {} });
  await ctx.poll('payment-success', 'commerce', `/v1/orders/${selected.orderNumber}/payments/${paid.paymentId}`, ctx.a, b => b.status === 'SUCCESS');
  const final = await req('paid-order', 'commerce', 'GET', `/v1/orders/${selected.orderNumber}`, { token: ctx.a });
  ctx.check('order-paid-state', final.status, 'PAID');
  for (const [name, token] of [['b', ctx.b], ['admin', ctx.admin]]) await req(`payment-query-other-${name}`, 'commerce', 'GET', `/v1/orders/${selected.orderNumber}/payments/${paid.paymentId}`, { token, status: 404 });
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
  await req('cancel-replay', 'commerce', 'POST', `/v1/orders/${cancel.orderNumber}/cancel`, { token: ctx.a, status: 409 });
  ctx.check('cancel-replay-no-restock', stock(s[1]), afterCancel);
  for (const [name, paymentBody] of [['absent', undefined], ['json-null', null], ['delayed-success', {}], ['delayed-fail', {}]]) {
    const payer = name.startsWith('delayed') ? ctx.admin : ctx.a;
    const order = await ctx.direct('direct-' + name, { token: payer });
    if (name.startsWith('delayed')) await req('mock-select-' + name, 'commerce', 'PUT', `/v1/dev/payment-scenarios/${order.orderNumber}`,
      { token: ctx.admin, body: { scenario: name === 'delayed-success' ? 'DELAYED_SUCCESS' : 'DELAYED_FAIL' }, status: 204 });
    const pay = await req('payment-body-' + name, 'commerce', 'POST', `/v1/orders/${order.orderNumber}/payments`,
      { token: payer, ...(paymentBody === undefined ? {} : { body: paymentBody }) });
    const expected = name === 'delayed-fail' ? 'FAILED' : 'SUCCESS';
    await ctx.poll('settled-' + name, 'commerce', `/v1/orders/${order.orderNumber}/payments/${pay.paymentId}`, payer, b => b.status === expected);
    await req('payment-wrong-order-' + name, 'commerce', 'GET', `/v1/orders/${cancel.orderNumber}/payments/${pay.paymentId}`, { token: ctx.a, status: 404 });
  }
  const empty = await req('sold-out-cart-add', 'commerce', 'POST', '/v1/cart/items', { token: ctx.a, body: { productId: p[3], quantity: 1 }, status: 201 });
  const emptyBefore = snapshot();
  await req('sold-out-cart-order', 'commerce', 'POST', `/v1/cart/items/${empty.id}/orders`, { token: ctx.a, body: buyer, headers: { 'X-Idempotency-Key': 'sold-out' }, status: 409 });
  ctx.check('sold-out-preserves-cart-and-stock', snapshot(), emptyBefore);
  await req('one-last-stock', 'commerce', 'PATCH', `/v1/sales/${s[3]}/stock`, { token: ctx.admin, body: { delta: 1 } });
  await Promise.all([ctx.a, ctx.b].map((token, i) => req('concurrent-last-stock-' + i, 'commerce', 'POST', '/v1/orders',
    { token, body: { productId: p[3], quantity: 1, ...buyer }, headers: { 'X-Idempotency-Key': 'concurrent-' + i }, status: [201, 409] })));
  ctx.check('one-concurrent-winner', ctx.result.checks.filter(c => c.name.startsWith('concurrent-last-stock-')).map(c => c.status).sort(), [201, 409]);
  ctx.check('last-stock-never-negative', stock(s[3]), { available: 0, reserved: 1 });
}
module.exports = { commerce };
