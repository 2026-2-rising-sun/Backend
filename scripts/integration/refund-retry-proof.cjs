const assert = require('node:assert/strict');
const { command, delay } = require('./runtime.cjs');
const { commerceRequest, positiveId, stock, purchase } = require('./p3-fixtures.cjs');
function fault(runtime, name) {
  assert(['late', 'exhaust'].includes(name));
  const prefix = 'p3_' + name;
  runtime.sql('commerce', `CREATE SEQUENCE ${prefix}_attempts;
    CREATE FUNCTION ${prefix}_fault() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN PERFORM nextval('${prefix}_attempts'); RAISE EXCEPTION 'Owned proof provider write unavailable'; END $$;
    CREATE TRIGGER ${prefix}_fault BEFORE INSERT ON mock_refund_result FOR EACH ROW EXECUTE FUNCTION ${prefix}_fault();`);
  return {
    attempts: () => Number(runtime.sql('commerce', `SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM ${prefix}_attempts`)),
    remove: () => runtime.sql('commerce', `DROP TRIGGER ${prefix}_fault ON mock_refund_result; DROP FUNCTION ${prefix}_fault();`)
  };
}
async function refundRetryProof(ctx) {
  const r = ctx.runtime;
  const req = commerceRequest(ctx, 'p3-rr-');
  const results = id => r.sql('commerce', `SELECT count(*) FROM mock_refund_result WHERE refund_request_id=${positiveId(id)}`);
  const beforeLate = stock(ctx);
  const late = await purchase(ctx, req, 'p3-rr-late');
  const lateFault = fault(r, 'late');
  // Gate only the first retry's due time to make the owned clock-fixture change deterministic.
  r.sql('commerce', `CREATE FUNCTION p3_hold_initial() RETURNS trigger LANGUAGE plpgsql AS $$
    BEGIN IF NEW.status='UNKNOWN' AND NEW.retry_count=0 THEN NEW.next_action_at=clock_timestamp()+INTERVAL '5 minutes'; END IF; RETURN NEW; END $$;
    CREATE TRIGGER p3_hold_initial BEFORE UPDATE ON refund_request FOR EACH ROW EXECUTE FUNCTION p3_hold_initial();`);
  const lateRequest = await req('late-request', 'POST', late.base + '/refunds', { status: 201, headers: { 'Idempotency-Key': 'p3-late-recovery' } });
  positiveId(lateRequest.id);
  await r.wait(() => r.sql('commerce', `SELECT status||'|'||retry_count||'|'||(lease_token IS NULL) FROM refund_request WHERE id=${lateRequest.id}`) === 'UNKNOWN|0|true', 'first UNKNOWN gate', 10000);
  ctx.check('p3-refund-retry-late-one-failed-invocation', lateFault.attempts(), 1);
  r.sql('commerce', `UPDATE payment_attempt SET resolved_at=clock_timestamp()-INTERVAL '8 days' WHERE id=${positiveId(late.payment.paymentId)};
    UPDATE refund_request SET requested_at=clock_timestamp()-INTERVAL '8 days' WHERE id=${lateRequest.id};`);
  const replay = await req('late-replay', 'POST', late.base + '/refunds', { headers: { 'Idempotency-Key': 'p3-late-recovery' } });
  ctx.check('p3-refund-retry-late-existing-id', replay.id, lateRequest.id);
  await req('late-new-key-rejected', 'POST', late.base + '/refunds', { status: 409, headers: { 'Idempotency-Key': 'p3-late-new' } });
  lateFault.remove();
  r.sql('commerce', `DROP TRIGGER p3_hold_initial ON refund_request; DROP FUNCTION p3_hold_initial();
    UPDATE refund_request SET next_action_at=clock_timestamp() WHERE id=${lateRequest.id};`);
  const recovered = (await ctx.poll('p3-rr-late-recovered', 'commerce', `${late.base}/refunds/${lateRequest.id}`, ctx.a, b => b.data.status === 'SUCCESS')).data;
  ctx.check('p3-refund-retry-late-single-result', results(recovered.id), '1');
  ctx.check('p3-refund-retry-late-stock-restored', stock(ctx), beforeLate);
  ctx.check('p3-refund-retry-late-amount', recovered.refundAmount, 10000);

  const exhausted = await purchase(ctx, req, 'p3-rr-exhaust');
  const sold = stock(ctx);
  const unavailable = fault(r, 'exhaust');
  const pending = await req('exhaust-request', 'POST', exhausted.base + '/refunds', { status: 201, headers: { 'Idempotency-Key': 'p3-exhaust' } });
  positiveId(pending.id);
  await r.wait(() => unavailable.attempts() === 4 && r.sql('commerce', `SELECT status||'|'||retry_count||'|'||(lease_token IS NULL) FROM refund_request WHERE id=${pending.id}`) === 'UNKNOWN|3|true', 'initial plus three natural retries', 20000);
  const initial = await req('exhausted-read', 'GET', `${exhausted.base}/refunds/${pending.id}`);
  ctx.check('p3-refund-retry-exhausted-metadata', [initial.status, initial.recovery.retryCount, initial.recovery.retryExhausted], ['UNKNOWN', 3, true]);
  const originalDue = Date.parse(initial.recovery.nextActionAt); assert(Number.isFinite(originalDue));
  ctx.check('p3-refund-retry-four-invocations', unavailable.attempts(), 4);
  unavailable.remove();
  const pid = () => r.mode === 'host' ? r.children.get('commerce').pid : JSON.parse(command('docker', ['inspect', r.id + '-commerce']))[0].State.Pid;
  const oldProcess = pid();
  await r.stop('commerce');
  await r.wait(() => r.mode === 'host'
    ? r.children.get('commerce').exitCode !== null || r.children.get('commerce').signalCode !== null
    : !JSON.parse(command('docker', ['inspect', r.id + '-commerce']))[0].State.Running, 'old Commerce process exited', 5000);
  await r.restart('commerce');
  const newProcess = pid();
  ctx.check('p3-refund-retry-actual-restart', oldProcess !== newProcess);
  const until = Date.now() + 90000;
  let final; let queryCompleted = false;
  for (let i = 0; Date.now() < until; i++) {
    final = await req('query-only-' + i, 'GET', `${exhausted.base}/refunds/${pending.id}`);
    assert.equal(final.status, 'UNKNOWN'); assert.equal(final.recovery.retryCount, 3);
    const advanced = r.sql('commerce', `SELECT CASE WHEN next_action_at>=to_timestamp(${originalDue / 1000})+INTERVAL '55 seconds' AND lease_token IS NULL THEN 1 ELSE 0 END FROM refund_request WHERE id=${pending.id}`) === '1';
    if (advanced) { queryCompleted = true; break; }
    await delay(1000);
  }
  ctx.check('p3-refund-retry-query-completed', queryCompleted);
  final = await req('query-only-final-read', 'GET', `${exhausted.base}/refunds/${pending.id}`);
  ctx.check('p3-refund-retry-natural-query-interval', Date.parse(final?.recovery?.nextActionAt) - originalDue >= 55000);
  ctx.check('p3-refund-retry-no-new-execution', unavailable.attempts(), 4);
  ctx.check('p3-refund-retry-no-result-after-exhaustion', results(pending.id), '0');
  ctx.check('p3-refund-retry-no-stock-restoration', stock(ctx), sold);
  ctx.check('p3-refund-retry-paid-group-preserved', (await req('exhausted-group-read', 'GET', exhausted.base)).status, 'PAID');
  ctx.result.refundRetryProof = { originalDue, finalDue: Date.parse(final.recovery.nextActionAt), oldProcess, newProcess,
    retryCount: final.recovery.retryCount, status: final.status, executionInvocations: unavailable.attempts(), providerResults: Number(results(pending.id)),
    faultScope: 'Owned PostgreSQL Mock result insertion failure; not production provider transport',
    lateClockScope: 'Owned persisted clock fixture with a first-retry gate; existing request resumes after eight simulated days' };
  ctx.result.refundRetryProofPassed = true;
}
module.exports = { refundRetryProof };
