#!/usr/bin/env node
const assert = require('node:assert/strict');
const path = require('node:path');
const crypto = require('node:crypto');
const fs = require('node:fs');
const { Runtime, root, command } = require('./runtime.cjs');
const { Context } = require('./context.cjs');
const { memberSeller } = require('./member-seller.cjs');
const { couponRefundFlow } = require('./coupon-refund-flow.cjs');

async function prove() {
  const runtime = new Runtime({ output: path.join(root, 'build/coupon-refund-proof') });
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
    context.result.deferred = ['Boundary/failure/concurrency scenarios are the second V1 commit; payment automatic recovery policy H2 is unanswered; production provider is excluded'];
    context.save();
    await runtime.build(process.argv.includes('--use-prebuilt') || process.env.CI === 'true');
    context.result.jars = Object.fromEntries(Object.entries(runtime.jars).map(([service, file]) =>
      [service, crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')]));
    await runtime.setup();
    await memberSeller(context);
    await couponRefundFlow(context);
  } catch (error) {
    if (context) context.result.error = runtime.sanitize(error.message);
    console.error(runtime.sanitize(error.stack || error.message));
    process.exitCode = 1;
  } finally {
    try { await runtime.cleanup(); if (context) context.result.cleanupPassed = true; }
    catch (error) { if (context) context.result.error = runtime.sanitize(error.message); process.exitCode = 1; }
    if (context) {
      context.result.completedAt = new Date().toISOString(); context.save();
      if (!context.result.passed || !context.result.couponRefundFlowPassed || !context.result.cleanupPassed) process.exitCode = 1;
      console.log(`Coupon/refund flow: ${context.result.executed} checks; ${context.result.failed} failures`);
    }
    process.removeListener('SIGINT', interrupted); process.removeListener('SIGTERM', interrupted);
  }
}

prove().catch(error => { console.error(error.message); process.exitCode = 1; });
