const assert = require('node:assert/strict');
const { command, delay } = require('./runtime.cjs');
const { commerceRequest, positiveId, couponId, stock } = require('./p3-fixtures.cjs');
const pid = r => r.mode === 'host' ? r.children.get('commerce').pid : JSON.parse(command('docker', ['inspect', r.id + '-commerce']))[0].State.Pid;
async function paymentRecoveryProof(ctx) {
  const r=ctx.runtime, req=commerceRequest(ctx,'p3-pay-');
  const evidence=[];
  async function create(name, scenario='INSTANT_SUCCESS', token=ctx.a) {
    const now=Date.now();
    const coupon=await req(name+'-coupon','POST','/v1/seller/coupons',{token:ctx.seller,status:201,body:{name,fixedDiscount:1000,issuanceLimit:1,
      startsAt:new Date(now-60000).toISOString(),endsAt:new Date(now+3600000).toISOString(),expiresAt:new Date(now+7200000).toISOString(),productIds:[positiveId(ctx.products[0])]}});
    couponId(coupon.id);await req(name+'-claim','POST',`/v1/coupons/${coupon.id}/claims`,{token,status:201});
    const before=stock(ctx);
    const item=await req(name+'-cart','POST','/v1/cart/items',{token,status:201,body:{productId:ctx.products[0],quantity:1}});
    const group=await req(name+'-group','POST','/v1/cart/orders',{token,status:201,headers:{'X-Idempotency-Key':name+'-group'},
      body:{items:[{itemId:positiveId(item.id),version:item.version}],buyerName:'Payment recovery',buyerPhone:'01012345678',expectedTotalAmount:10000,couponId:coupon.id}});
    assert(/^[A-Za-z0-9-]{1,64}$/.test(group.groupNumber));
    const base='/v1/payment-groups/'+group.groupNumber;
    if(scenario!=='INSTANT_SUCCESS')await req(name+'-scenario','PUT',`/v1/dev/payment-scenarios/${group.orders[0].orderNumber}`,{token,status:204,body:{scenario}});
    return {name,coupon,item,group,base,before,token};
  }
  const provider = p => Number(r.sql('commerce',`SELECT count(*) FROM mock_gateway_result WHERE attempt_id=${positiveId(p.payment.paymentId)}`));
  const couponState = p => r.sql('commerce',`SELECT status FROM member_coupon WHERE coupon_id='${couponId(p.coupon.id)}'`);
  async function held(p,label) {
    const group=await req(label+'-group-read','GET',p.base,{token:p.token});
    ctx.check(label+'-group-confirming',[group.status,...group.orders.map(o=>o.status)],['PAYMENT_CONFIRMING','PAYMENT_CONFIRMING']);
    ctx.check(label+'-coupon-reserved',couponState(p),'RESERVED');
    ctx.check(label+'-stock-reserved',stock(ctx),{available:p.before.available-1,reserved:p.before.reserved+1});
    ctx.check(label+'-cart-kept',r.sql('commerce',`SELECT count(*) FROM cart_item WHERE id=${positiveId(p.item.id)}`),'1');
  }
  for(const phase of ['before-result','after-result','during-apply']) {
    const p=await create('p3-pay-'+phase);
    const table=phase==='before-result'?'mock_gateway_result':phase==='after-result'?'orders':'payment_attempt';
    const event=phase==='before-result'?'INSERT':'UPDATE';
    const guard=phase==='before-result'?'':phase==='after-result'?"WHEN (NEW.status='PAID' AND OLD.status='PAYMENT_CONFIRMING')":"WHEN (NEW.status='SUCCESS' AND OLD.status IN ('PROCESSING','UNKNOWN'))";
    r.sql('commerce',`CREATE FUNCTION p3_payment_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(10); RETURN NEW; END $$;
      CREATE TRIGGER p3_payment_pause BEFORE ${event} ON ${table} FOR EACH ROW ${guard} EXECUTE FUNCTION p3_payment_pause();`);
    p.payment=await req(phase+'-pay','POST',p.base+'/payments',{status:202,headers:{'X-Idempotency-Key':p.name+'-pay'}});
    positiveId(p.payment.paymentId);
    const sleeping=()=>r.sql('commerce',"SELECT count(*) FROM pg_stat_activity WHERE datname='commerce' AND wait_event='PgSleep' AND state='active'")==='1';
    await r.wait(sleeping,phase+' payment crash boundary',5000);
    const observed=Date.now();
    ctx.check('p3-payment-'+phase+'-pre-crash-result',provider(p),phase==='before-result'?0:1);
    assert(sleeping(),'must still be inside paused transaction');
    const crash=await r.crash('commerce');
    const observedToCrashMs=Date.now()-observed;assert(observedToCrashMs<5000,'crash must precede pause exit');
    ctx.check('p3-payment-'+phase+'-real-sigkill',crash.signal,'SIGKILL');
    r.sql('commerce',`DROP TRIGGER p3_payment_pause ON ${table}; DROP FUNCTION p3_payment_pause();`);
    // No lease expiry or provider outcome is forged; natural expiry after restart owns recovery.
    // Commerce is stopped, so verify preserved state through committed database rows.
    ctx.check('p3-payment-'+phase+'-rollback-coupon',couponState(p),'RESERVED');
    ctx.check('p3-payment-'+phase+'-rollback-stock',stock(ctx),{available:p.before.available-1,reserved:p.before.reserved+1});
    ctx.check('p3-payment-'+phase+'-rollback-order',r.sql('commerce',`SELECT status FROM orders WHERE payment_group_id=(SELECT id FROM payment_group WHERE group_number='${p.group.groupNumber}')`),'PAYMENT_CONFIRMING');
    await r.restart('commerce');const newProcess=pid(r);
    ctx.check('p3-payment-'+phase+'-new-process',newProcess!==crash.processId);
    await held(p,'p3-payment-'+phase+'-after-restart');
    const until=Date.now()+90000;let payment;
    for(let i=0;Date.now()<until;i++) {
      payment=await req(phase+'-recovery-'+i,'GET',`${p.base}/payments/${p.payment.paymentId}`);
      if(payment.status==='SUCCESS')break;
      await delay(1000);
    }
    ctx.check('p3-payment-'+phase+'-converged',payment?.status,'SUCCESS');
    ctx.check('p3-payment-'+phase+'-one-approval',provider(p),1);
    ctx.check('p3-payment-'+phase+'-coupon-used',couponState(p),'USED');
    ctx.check('p3-payment-'+phase+'-stock-once',stock(ctx),{available:p.before.available-1,reserved:p.before.reserved});
    const retryCount=Number(r.sql('commerce',`SELECT retry_count FROM payment_attempt WHERE id=${p.payment.paymentId}`));
    ctx.check('p3-payment-'+phase+'-correct-quota',retryCount,phase==='before-result'?1:0);
    const replay=await req(phase+'-replay','POST',p.base+'/payments',{headers:{'X-Idempotency-Key':p.name+'-pay'}});
    ctx.check('p3-payment-'+phase+'-same-id',replay.paymentId,p.payment.paymentId);
    ctx.check('p3-payment-'+phase+'-replay-no-approval',provider(p),1);
    evidence.push({phase,...crash,newProcess,observedToCrashMs,paymentId:p.payment.paymentId,providerResults:1,retryCount});
  }
  // A lost response to an authoritative rejection must hold resources until result-only lookup.
  const rejected=await create('p3-pay-lost-failure','FAILURE_RESPONSE_LOST',ctx.seller);
  r.sql('commerce',`CREATE FUNCTION p3_payment_hold() RETURNS trigger LANGUAGE plpgsql AS $$
    BEGIN IF NEW.status='UNKNOWN' AND NEW.retry_count=0 THEN NEW.scheduled_resolve_at=clock_timestamp()+INTERVAL '5 minutes'; END IF; RETURN NEW; END $$;
    CREATE TRIGGER p3_payment_hold BEFORE UPDATE ON payment_attempt FOR EACH ROW EXECUTE FUNCTION p3_payment_hold();`);
  rejected.payment=await req('lost-failure-pay','POST',rejected.base+'/payments',{token:rejected.token,status:202,headers:{'X-Idempotency-Key':rejected.name+'-pay'}});
  await r.wait(()=>r.sql('commerce',`SELECT status||'|'||(lease_token IS NULL) FROM payment_attempt WHERE id=${positiveId(rejected.payment.paymentId)}`)==='UNKNOWN|true','rejection response lost',10000);
  await held(rejected,'p3-payment-rejected-unknown');
  ctx.check('p3-payment-rejected-ledger-known',r.sql('commerce',`SELECT outcome FROM mock_gateway_result WHERE attempt_id=${rejected.payment.paymentId}`),'FAILED');
  r.sql('commerce',`DROP TRIGGER p3_payment_hold ON payment_attempt; DROP FUNCTION p3_payment_hold();UPDATE payment_attempt SET scheduled_resolve_at=clock_timestamp() WHERE id=${rejected.payment.paymentId};`);
  await ctx.poll('p3-payment-rejected-resolved','commerce',`${rejected.base}/payments/${rejected.payment.paymentId}`,rejected.token,b=>b.status==='FAILED');
  ctx.check('p3-payment-rejected-coupon-released',couponState(rejected),'AVAILABLE');
  ctx.check('p3-payment-rejected-stock-restored',stock(ctx),rejected.before);
  ctx.check('p3-payment-rejected-query-no-quota',r.sql('commerce',`SELECT retry_count FROM payment_attempt WHERE id=${rejected.payment.paymentId}`),'0');

  ctx.check('p3-payment-rejected-cart-preserved',r.sql('commerce',`SELECT count(*) FROM cart_item WHERE id=${positiveId(rejected.item.id)}`),'1');
  await req('rejected-cart-cleanup','DELETE',`/v1/cart/items/${rejected.item.id}`,{token:rejected.token,status:204});

  const p=await create('p3-pay-exhaust');
  r.sql('commerce',`CREATE SEQUENCE p3_payment_invocations;
    CREATE FUNCTION p3_payment_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM nextval('p3_payment_invocations'); RAISE EXCEPTION 'Owned Mock payment write unavailable'; END $$;
    CREATE TRIGGER p3_payment_fault BEFORE INSERT ON mock_gateway_result FOR EACH ROW EXECUTE FUNCTION p3_payment_fault();`);
  p.payment=await req('exhaust-pay','POST',p.base+'/payments',{status:202,headers:{'X-Idempotency-Key':p.name+'-pay'}});
  const attempts=()=>Number(r.sql('commerce',"SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM p3_payment_invocations"));
  await r.wait(()=>attempts()===4 && r.sql('commerce',`SELECT status||'|'||retry_count||'|'||(lease_token IS NULL) FROM payment_attempt WHERE id=${positiveId(p.payment.paymentId)}`)==='UNKNOWN|3|true','natural initial plus three payment retries',20000);
  await held(p,'p3-payment-exhausted');
  const initial=await req('exhausted-read','GET',`${p.base}/payments/${p.payment.paymentId}`);
  const originalDue=Date.parse(initial.scheduledResolveAt);assert(Number.isFinite(originalDue));
  r.sql('commerce','DROP TRIGGER p3_payment_fault ON mock_gateway_result; DROP FUNCTION p3_payment_fault();');
  const crash=await r.crash('commerce');await r.restart('commerce');const newProcess=pid(r);
  ctx.check('p3-payment-exhausted-real-restart',newProcess!==crash.processId);
  const until=Date.now()+90000;let queryCompleted=false;
  while(Date.now()<until) {
    queryCompleted=r.sql('commerce',`SELECT CASE WHEN scheduled_resolve_at>=to_timestamp(${originalDue/1000})+INTERVAL '55 seconds' AND lease_token IS NULL THEN 1 ELSE 0 END FROM payment_attempt WHERE id=${p.payment.paymentId}`)==='1';
    if(queryCompleted)break;await delay(1000);
  }
  ctx.check('p3-payment-exhausted-natural-query-completed',queryCompleted);
  const final=await req('exhausted-final','GET',`${p.base}/payments/${p.payment.paymentId}`);
  ctx.check('p3-payment-exhausted-unknown',final.status,'UNKNOWN');
  ctx.check('p3-payment-exhausted-four-invocations',attempts(),4);
  ctx.check('p3-payment-exhausted-no-new-approval',provider(p),0);
  ctx.check('p3-payment-exhausted-count-preserved',r.sql('commerce',`SELECT retry_count FROM payment_attempt WHERE id=${p.payment.paymentId}`),'3');
  await held(p,'p3-payment-after-natural-query');
  ctx.result.paymentRecoveryProof={crashes:evidence,exhaustion:{oldProcess:crash.processId,newProcess,originalDue,finalDue:Date.parse(final.scheduledResolveAt),executionInvocations:attempts(),providerResults:provider(p),retryCount:3,status:final.status},faultScope:'Owned Mock provider database trigger; actual SIGKILL/new process; no production provider proof'};
  ctx.result.paymentRecoveryProofPassed=true;
  ctx.save();
}
module.exports={paymentRecoveryProof};
