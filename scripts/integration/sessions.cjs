const crypto = require('node:crypto');

async function sessions(ctx) {
  const r = ctx.runtime; const req = ctx.request.bind(ctx);
  const password = crypto.randomBytes(24).toString('base64url'); r.remember(password);
  const claims = token => JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString());
  const login = async (name, email) => (await req(name, 'member', 'POST', '/v1/auth/login',
    { body: { email, password } })).data;
  const register = async name => {
    const email = `${name}@sessions.example.test`;
    const member = (await req(name + '-signup', 'member', 'POST', '/v1/auth/signup',
      { body: { email, password, displayName: name }, status: 201 })).data;
    return { ...member, email, pair: await login(name + '-login', email) };
  };
  const refresh = async (name, token, status = 200) => req(name, 'member', 'POST', '/v1/auth/refresh',
    { body: { refreshToken: token }, status });
  const rejectEverywhere = async (name, token) => {
    for (const [service, endpoint] of [['member', '/v1/members/me'], ['commerce', '/v1/cart/items'],
      ['shopping', '/v1/admin/products'], ['live', '/v1/admin/broadcasts']]) {
      await req(`${name}-${service}`, service, 'GET', endpoint, { token, status: 401 });
    }
  };

  const ordinary = await register('ordinary-logout');
  const first = ordinary.pair; const firstClaims = claims(first.accessToken);
  const sid = firstClaims.sid;
  ctx.check('session-sid-is-family', sid, first.refreshToken.split('.')[0]);
  ctx.check('session-access-ttl-15-minutes', firstClaims.exp - firstClaims.iat, 900);
  ctx.check('session-refresh-absolute-30-days', r.sql('member',
    `SELECT extract(epoch FROM (expires_at-created_at))::bigint FROM refresh_families WHERE id='${sid}'`), '2592000');
  const expiresBefore = r.sql('member', `SELECT expires_at FROM refresh_families WHERE id='${sid}'`);
  const second = await login('ordinary-second-device', ordinary.email);
  const rotated = (await refresh('refresh-rotation', first.refreshToken)).data;
  ctx.check('refresh-rotates-secret', rotated.refreshToken !== first.refreshToken);
  ctx.check('refresh-keeps-session-id', claims(rotated.accessToken).sid, sid);
  ctx.check('refresh-keeps-absolute-expiry', r.sql('member', `SELECT expires_at FROM refresh_families WHERE id='${sid}'`), expiresBefore);
  ctx.check('refresh-records-consumed-hash', r.sql('member',
    `SELECT count(*) FROM refresh_tokens WHERE family_id='${sid}' AND used_at IS NOT NULL`), '1');
  await req('ordinary-logout', 'member', 'POST', '/v1/auth/logout', { body: { refreshToken: rotated.refreshToken }, status: 204 });
  await req('ordinary-logout-idempotent', 'member', 'POST', '/v1/auth/logout', { body: { refreshToken: rotated.refreshToken }, status: 204 });
  await refresh('ordinary-logout-blocks-refresh', rotated.refreshToken, 401);
  await req('ordinary-logout-keeps-existing-access', 'commerce', 'GET', '/v1/cart/items', { token: rotated.accessToken });
  await req('ordinary-logout-keeps-old-access', 'member', 'GET', '/v1/members/me', { token: first.accessToken });
  await refresh('ordinary-logout-other-device-refresh', second.refreshToken);

  const reuse = await register('refresh-reuse');
  const forged = reuse.pair.refreshToken.split('.')[0] + '.' + crypto.randomBytes(32).toString('base64url');
  r.remember(forged);
  await refresh('forged-refresh-rejected', forged, 401);
  await req('forged-refresh-does-not-revoke-victim', 'commerce', 'GET', '/v1/cart/items', { token: reuse.pair.accessToken });
  const reuseRotated = (await refresh('reuse-first-rotation', reuse.pair.refreshToken)).data;
  await refresh('consumed-refresh-reuse-rejected', reuse.pair.refreshToken, 401);
  await refresh('reuse-revokes-descendant-refresh', reuseRotated.refreshToken, 401);
  await rejectEverywhere('reuse-revokes-access', reuseRotated.accessToken);
  await req('reuse-revokes-original-access', 'commerce', 'GET', '/v1/cart/items', { token: reuse.pair.accessToken, status: 401 });

  const race = await register('refresh-race');
  const attempts = await Promise.all([0, 1].map(i => refresh(`concurrent-refresh-${i}`, race.pair.refreshToken, [200, 401])));
  const statuses = ctx.result.checks.filter(c => /^concurrent-refresh-[01]$/.test(c.name)).map(c => c.status).sort();
  ctx.check('concurrent-refresh-single-winner', statuses, [200, 401]);
  const winner = attempts.find(body => body?.data?.accessToken).data;
  await req('concurrent-reuse-revokes-winner-access', 'commerce', 'GET', '/v1/cart/items', { token: winner.accessToken, status: 401 });
  await refresh('concurrent-reuse-revokes-winner-refresh', winner.refreshToken, 401);

  const foreign = await register('foreign-member');
  await req('seller-cannot-revoke-another-member', 'member', 'POST',
    `/v1/admin/members/${foreign.memberId}/sessions/revoke`, { token: ctx.seller, status: 403 });
  const forceEmail = 'forced-logout@sessions.example.test';
  r.bootstrapSeller(forceEmail, password);
  const forceId = r.sql('member', `SELECT id FROM members WHERE email='${forceEmail}'`);
  const force = { memberId: forceId, email: forceEmail, pair: await login('forced-logout-login', forceEmail) };
  const forceOther = await login('forced-other-device', force.email);
  const revokePath = `/v1/admin/members/${force.memberId}/sessions/revoke`;
  await req('force-logout-anonymous-denied', 'member', 'POST', revokePath, { status: 401 });
  await req('force-logout-user-denied', 'member', 'POST', revokePath, { token: foreign.pair.accessToken, status: 403 });
  await req('force-logout-seller', 'member', 'POST', revokePath, { token: force.pair.accessToken, status: 204 });
  await rejectEverywhere('force-revokes-existing-access', force.pair.accessToken);
  await req('force-revokes-other-device-access', 'commerce', 'GET', '/v1/cart/items', { token: forceOther.accessToken, status: 401 });
  await refresh('force-revokes-refresh', force.pair.refreshToken, 401);
  await refresh('force-revokes-other-refresh', forceOther.refreshToken, 401);
  const relogin = await login('force-allows-new-login', force.email);
  await req('force-new-session-usable', 'commerce', 'GET', '/v1/cart/items', { token: relogin.accessToken });

  const sessionBody = { memberId: force.memberId, sessionId: claims(relogin.accessToken).sid };
  const internal = '/v1/internal/auth/sessions/check';
  for (const caller of ['SHOPPING', 'COMMERCE', 'LIVE']) {
    const body = await req(`member-session-caller-${caller.toLowerCase()}`, 'member', 'POST', internal,
      { headers: { 'X-Service-Token': r.credentials[caller + '_MEMBER_SERVICE_TOKEN'] }, body: sessionBody });
    ctx.check(`member-session-active-${caller.toLowerCase()}`, body.data, { active: true });
  }
  await req('member-session-caller-missing', 'member', 'POST', internal, { body: sessionBody, status: 401 });
  await req('member-session-user-cannot-introspect', 'member', 'POST', internal, { body: sessionBody, token: relogin.accessToken, status: 401 });
  await req('member-session-wrong-direction', 'member', 'POST', internal,
    { headers: { 'X-Service-Token': r.credentials.COMMERCE_SHOPPING_SERVICE_TOKEN }, body: sessionBody, status: 401 });
  const headers = { 'X-Service-Token': r.credentials.COMMERCE_MEMBER_SERVICE_TOKEN };
  const mismatch = await req('member-session-owner-mismatch', 'member', 'POST', internal,
    { headers, body: { ...sessionBody, memberId: ctx.memberA } });
  ctx.check('member-session-owner-mismatch-inactive', mismatch.data, { active: false });
  await req('member-session-malformed-id', 'member', 'POST', internal,
    { headers, body: { ...sessionBody, sessionId: 'invalid' }, status: 400 });

  const expired = await register('refresh-expiry');
  const expirySid = claims(expired.pair.accessToken).sid;
  // Only this harness's owned DB is modified, preserving the real issued access token.
  r.sql('member', `UPDATE refresh_families SET created_at=now()-interval '31 days', expires_at=now()-interval '1 second' WHERE id='${expirySid}'`);
  await refresh('absolute-refresh-expiry-rejected', expired.pair.refreshToken, 401);
  await req('refresh-expiry-keeps-unexpired-access', 'commerce', 'GET', '/v1/cart/items', { token: expired.pair.accessToken });

  const withdrawn = await register('withdrawal');
  await req('withdrawal-wrong-password', 'member', 'DELETE', '/v1/members/me',
    { token: withdrawn.pair.accessToken, body: { password: 'not-the-password' }, status: 401 });
  await req('withdrawal-failure-preserves-session', 'member', 'GET', '/v1/members/me', { token: withdrawn.pair.accessToken });
  await req('withdrawal-confirmed', 'member', 'DELETE', '/v1/members/me',
    { token: withdrawn.pair.accessToken, body: { password }, status: 204 });
  await rejectEverywhere('withdrawal-revokes-access', withdrawn.pair.accessToken);
  await refresh('withdrawal-revokes-refresh', withdrawn.pair.refreshToken, 401);
  await req('withdrawal-blocks-login', 'member', 'POST', '/v1/auth/login',
    { body: { email: withdrawn.email, password }, status: 401 });

  const before = ctx.commerceSnapshot();
  await r.stop('member');
  try {
    for (const [service, endpoint, token] of [['commerce', '/v1/cart/items', relogin.accessToken],
      ['shopping', '/v1/admin/products', ctx.seller], ['live', '/v1/admin/broadcasts', ctx.seller]]) {
      const body = await req(`member-down-${service}-fails-closed`, service, 'GET', endpoint, { token, status: 503 });
      ctx.check(`member-down-${service}-error-code`, body.error.code, 'SERVICE_UNAVAILABLE');
    }
    await req('member-down-order-cannot-write', 'commerce', 'POST', '/v1/orders',
      { token: ctx.a, body: { ...ctx.buyer, productId: ctx.products[0], quantity: 1 },
        headers: { 'X-Idempotency-Key': 'member-down-order' }, status: 503 });
    ctx.check('member-down-preserves-commerce-data', ctx.commerceSnapshot(), before);
    await req('member-down-public-product', 'shopping', 'GET', `/v1/products/${ctx.products[0]}`);
    await req('member-down-public-broadcast', 'live', 'GET', '/v1/broadcasts');
    await req('member-down-service-caller-independent', 'shopping', 'GET', `/v1/internal/products/${ctx.products[0]}`,
      { headers: { 'X-Service-Token': r.credentials.COMMERCE_SHOPPING_SERVICE_TOKEN } });
  } finally { await r.restart('member'); }
  await req('member-restart-restores-active-session', 'commerce', 'GET', '/v1/cart/items', { token: relogin.accessToken });
  await req('member-restart-keeps-security-revocation', 'commerce', 'GET', '/v1/cart/items', { token: force.pair.accessToken, status: 401 });
}

module.exports = { sessions };
