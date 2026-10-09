#!/usr/bin/env node
const assert = require('node:assert/strict');
const path = require('node:path');
const crypto = require('node:crypto');
const fs = require('node:fs');
const { Runtime, root, command, delay } = require('./runtime.cjs');
const { Context } = require('./context.cjs');
const { memberSeller } = require('./member-seller.cjs');
const pauseSeconds = 10;
const socketTimeoutSeconds = 60;

async function prove() {
  const runtime = new Runtime({ output: path.join(root, 'build/refund-recovery-proof') });
  const originalStart = runtime.start.bind(runtime);
  runtime.start = (service, overrides = {}) => originalStart(service, service === 'commerce' ? {
    ...overrides, COMMERCE_REFUNDS_API_ENABLED: 'true', COMMERCE_REFUNDS_EXECUTION_ENABLED: 'true',
    SPRING_DATASOURCE_URL: runtime.databaseUrl('commerce').replace(/socketTimeout=\d+/, `socketTimeout=${socketTimeoutSeconds}`)
  } : overrides);
  let context;
  const interrupted = signal => {
    console.error(`Refund proof interrupted by ${signal}; cleaning this run's resources`);
    runtime.cleanup().finally(() => process.exit(130));
  };
  process.once('SIGINT', interrupted); process.once('SIGTERM', interrupted);
  try {
    assert.equal(command('git', ['status', '--porcelain', '--untracked-files=normal']), '', 'Proof requires a clean checkout');
    context = new Context(runtime);
    context.result.scope = 'Refund recovery only: real HTTP/PostgreSQL and three actual Commerce process crashes; Mock monetary provider';
    context.result.deferred = ['Full coupon/refund policy and boundary flow remains V1 #164; actual production payment provider is excluded'];
    context.result.processCrashes = [];
    context.result.interruptionBoundary = { pauseSeconds, socketTimeoutSeconds };
    context.save();
    await runtime.build(process.argv.includes('--use-prebuilt') || process.env.CI === 'true');
    context.result.jars = Object.fromEntries(Object.entries(runtime.jars).map(([service, file]) =>
      [service, crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')]));
    await runtime.setup();
    assert(runtime.environments.commerce.SPRING_DATASOURCE_URL.includes(`socketTimeout=${socketTimeoutSeconds}`));
    await memberSeller(context);
    for (const phase of ['before-result', 'after-result', 'during-apply']) await provePhase(context, phase);
    context.check('three-actual-process-crashes', context.result.processCrashes.length, 3);
    context.result.recoveryProofPassed = true;
  } catch (error) {
    if (context) context.result.error = runtime.sanitize(error.message);
    console.error(runtime.sanitize(error.stack || error.message));
    process.exitCode = 1;
  } finally {
    try { await runtime.cleanup(); if (context) context.result.cleanupPassed = true; }
    catch (error) { if (context) context.result.error = runtime.sanitize(error.message); process.exitCode = 1; }
    if (context) {
      context.result.completedAt = new Date().toISOString(); context.save();
      if (!context.result.passed || !context.result.recoveryProofPassed || !context.result.cleanupPassed) process.exitCode = 1;
      console.log(`Refund process proof: ${context.result.executed} checks; ${context.result.failed} failures; ${context.result.processCrashes.length} crashes`);
    }
    process.removeListener('SIGINT', interrupted); process.removeListener('SIGTERM', interrupted);
  }
}

async function provePhase(ctx, phase) {
  const r = ctx.runtime;
  const req = async (name, method, url, options = {}) => {
    const response = await ctx.request(`refund-proof-${phase}-${name}`, 'commerce', method, url,
      { token: ctx.a, ...options });
    return response?.data ?? response;
  };
  const number = value => { assert(Number.isSafeInteger(value) && value > 0); return value; };
  const productId = number(ctx.products[0]), salesId = number(ctx.sales[0]);
  const stock = () => JSON.parse(r.sql('commerce', `SELECT json_build_object('available',available,'reserved',reserved) FROM sales_stock WHERE sales_info_id=${salesId}`));
  const before = stock();
  const item = await req('cart', 'POST', '/v1/cart/items', { body: { productId, quantity: 1 }, status: 201 });
  const group = await req('group', 'POST', '/v1/cart/orders', { status: 201,
    headers: { 'X-Idempotency-Key': `proof-group-${phase}` },
    body: { items: [{ itemId: item.id, version: item.version }], buyerName: 'Refund proof', buyerPhone: '01012345678', expectedTotalAmount: 10000 } });
  assert(/^[A-Za-z0-9-]{1,64}$/.test(group.groupNumber));
  const base = `/v1/payment-groups/${group.groupNumber}`;
  const payment = await req('pay', 'POST', `${base}/payments`, { status: 202, headers: { 'X-Idempotency-Key': `proof-pay-${phase}` } });
  await ctx.poll(`refund-proof-${phase}-paid`, 'commerce', `${base}/payments/${payment.paymentId}`, ctx.a,
    body => body.status === 'SUCCESS');
  const sold = stock();
  ctx.check(`refund-proof-${phase}-stock-sold`, sold, { available: before.available - 1, reserved: before.reserved });

  const table = phase === 'before-result' ? 'mock_refund_result' : phase === 'after-result' ? 'orders' : 'refund_request';
  const event = phase === 'before-result' ? 'INSERT' : 'UPDATE';
  const guard = phase === 'before-result' ? '' : phase === 'after-result'
    ? "WHEN (NEW.status='REFUNDED' AND OLD.status='PAID')" : "WHEN (NEW.status='SUCCESS' AND OLD.status IN ('PROCESSING','UNKNOWN'))";
  // This database is owned by this proof run. The pause prevents crossing the chosen crash boundary.
  r.sql('commerce', `CREATE FUNCTION r4b_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(${pauseSeconds}); RETURN NEW; END $$;
    CREATE TRIGGER r4b_pause BEFORE ${event} ON ${table} FOR EACH ROW ${guard} EXECUTE FUNCTION r4b_pause();`);
  const refund = await req('refund', 'POST', `${base}/refunds`, { status: 201, headers: { 'Idempotency-Key': `proof-refund-${phase}` } });
  const id = number(refund.id);
  const sleeping = () => r.sql('commerce', "SELECT COUNT(*) FROM pg_stat_activity WHERE datname='commerce' AND wait_event='PgSleep' AND state='active'") === '1';
  await r.wait(sleeping, `${phase} database crash boundary`, 5000);
  const observedAt = Date.now();
  const providerCount = () => Number(r.sql('commerce', `SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=${id}`));
  ctx.check(`refund-proof-${phase}-committed-provider-result-before-crash`, providerCount(), phase === 'before-result' ? 0 : 1);
  assert(sleeping(), `${phase}: paused transaction must still be at the crash boundary`);
  const crash = await r.crash('commerce');
  const observedToCrashMs = Date.now() - observedAt;
  assert(observedToCrashMs < 5000, `${phase}: crash must precede the end of the database pause`);
  ctx.check(`refund-proof-${phase}-process-really-killed`, crash.signal, 'SIGKILL');
  // The paused PostgreSQL transaction sees the closed process socket after returning from pg_sleep;
  // DROP waits for that transaction to abort naturally. No forged provider outcome is inserted.
  r.sql('commerce', `DROP TRIGGER r4b_pause ON ${table}; DROP FUNCTION r4b_pause();`);
  ctx.check(`refund-proof-${phase}-rollback-stock`, stock(), sold);
  ctx.check(`refund-proof-${phase}-rollback-order`, r.sql('commerce', `SELECT status FROM orders WHERE payment_group_id=(SELECT id FROM payment_group WHERE group_number='${group.groupNumber}')`), 'PAID');
  await r.restart('commerce');
  const newProcess = r.mode === 'host' ? r.children.get('commerce').pid : JSON.parse(command('docker', ['inspect', r.id + '-commerce']))[0].State.Pid;
  ctx.check(`refund-proof-${phase}-new-process`, newProcess !== crash.processId);
  const until = Date.now() + 90000;
  let completed;
  for (let attempt = 1; Date.now() < until; attempt++) {
    completed = await req(`recovery-${attempt}`, 'GET', `${base}/refunds/${id}`);
    if (completed.status === 'SUCCESS') break;
    await delay(1000);
  }
  ctx.check(`refund-proof-${phase}-converged`, completed?.status, 'SUCCESS');
  ctx.check(`refund-proof-${phase}-single-provider-result`, providerCount(), 1);
  ctx.check(`refund-proof-${phase}-single-stock-restoration`, stock(), before);
  ctx.check(`refund-proof-${phase}-original-payment-preserved`, r.sql('commerce', `SELECT status FROM payment_attempt WHERE id=${number(payment.paymentId)}`), 'SUCCESS');
  const replay = await req('replay', 'POST', `${base}/refunds`, { headers: { 'Idempotency-Key': `proof-refund-${phase}` } });
  ctx.check(`refund-proof-${phase}-same-request`, replay.id, id);
  ctx.check(`refund-proof-${phase}-replay-no-restock`, stock(), before);
  ctx.check(`refund-proof-${phase}-replay-no-new-provider-result`, providerCount(), 1);
  contextEvidence(ctx, { phase, crash, observedToCrashMs, newProcess, refundId: id, providerResults: providerCount(), status: completed.status });
}

function contextEvidence(ctx, evidence) { ctx.result.processCrashes.push(evidence); ctx.save(); }
prove().catch(error => { console.error(error.message); process.exitCode = 1; });
