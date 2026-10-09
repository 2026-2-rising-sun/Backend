#!/usr/bin/env node
const assert = require('node:assert/strict');
const path = require('node:path');
const crypto = require('node:crypto');
const fs = require('node:fs');
const { Runtime, root, command } = require('./runtime.cjs');
const { Context } = require('./context.cjs');
const { memberSeller } = require('./member-seller.cjs');
const { couponRefundFlow } = require('./coupon-refund-flow.cjs');
const { couponBoundaries } = require('./coupon-boundaries.cjs');
const { refundBoundaries } = require('./refund-boundaries.cjs');
const { refundRetryProof } = require('./refund-retry-proof.cjs');
const { paymentRecoveryProof } = require('./payment-recovery-proof.cjs');

async function prove() {
  const runtime = new Runtime({ output: path.join(root, 'build/coupon-refund-proof') });
  const originalStart = runtime.start.bind(runtime);
  runtime.start = (service, overrides = {}) => originalStart(service, service === 'commerce' ? {
    ...overrides, SPRING_DATASOURCE_URL: runtime.databaseUrl('commerce').replace(/socketTimeout=\d+/, 'socketTimeout=60')
  } : overrides);
  let context;
  const interrupted = signal => {
    console.error(`Coupon/refund proof interrupted by ${signal}; cleaning this run's resources`);
    runtime.cleanup().finally(() => process.exit(130));
  };
  process.once('SIGINT', interrupted); process.once('SIGTERM', interrupted);
  try {
    assert.equal(command('git', ['status', '--porcelain', '--untracked-files=normal']), '', 'Proof requires a clean checkout');
    context = new Context(runtime);
    context.result.scope = 'P3 coupon and selected/remaining refund: real HTTP/PostgreSQL; Mock monetary provider';
    context.result.deferred = ['production provider and frontend are excluded'];
    context.result.paymentPolicy = 'initial plus three retries; Full Jitter 0–1/0–2/0–4s; exhausted UNKNOWN with one-minute query only indefinitely';
    context.save();
    await runtime.build(process.argv.includes('--use-prebuilt') || process.env.CI === 'true');
    context.result.jars = Object.fromEntries(Object.entries(runtime.jars).map(([service, file]) =>
      [service, crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')]));
    await runtime.setup();
    await memberSeller(context);
    await couponRefundFlow(context);
    await couponBoundaries(context);
    await refundBoundaries(context);
    await refundRetryProof(context);
    await paymentRecoveryProof(context);
  } catch (error) {
    if (context) context.result.error = runtime.sanitize(error.message);
    console.error(runtime.sanitize(error.stack || error.message));
    process.exitCode = 1;
  } finally {
    try { await runtime.cleanup(); if (context) context.result.cleanupPassed = true; }
    catch (error) { if (context) context.result.error = runtime.sanitize(error.message); process.exitCode = 1; }
    if (context) {
      context.result.completedAt = new Date().toISOString(); context.save();
      if (!context.result.passed || !context.result.couponRefundFlowPassed || !context.result.couponBoundariesPassed || !context.result.refundBoundariesPassed || !context.result.refundRetryProofPassed || !context.result.paymentRecoveryProofPassed || !context.result.cleanupPassed) process.exitCode = 1;
      console.log(`Coupon/refund flow: ${context.result.executed} checks; ${context.result.failed} failures`);
    }
    process.removeListener('SIGINT', interrupted); process.removeListener('SIGTERM', interrupted);
  }
}

prove().catch(error => { console.error(error.message); process.exitCode = 1; });
