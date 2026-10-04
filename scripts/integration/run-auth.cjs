#!/usr/bin/env node
// Focused authentication verification; does not claim the full P2 integration manifest.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { Runtime, root } = require('./runtime.cjs');
const { Context } = require('./context.cjs');
const { memberSeller } = require('./member-seller.cjs');
const { sessions } = require('./sessions.cjs');

async function run() {
  const args = process.argv.slice(2);
  assert(args.every(arg => arg === '--use-prebuilt'), 'Unknown auth verification option');
  const runtime = new Runtime({ output: path.join(root, 'build/auth-verification') });
  const context = new Context(runtime);
  context.result.scope = 'Member, SELLER and session checks only';
  context.result.deferred = [{ reason: 'Other P2 groups are outside this focused authentication run' }];
  const interrupt = () => runtime.cleanup().finally(() => process.exit(130));
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  try {
    await runtime.build(args.includes('--use-prebuilt'));
    await runtime.setup();
    await memberSeller(context);
    context.commerceSnapshot = () => runtime.sql('commerce', "SELECT json_build_object('orders',(SELECT count(*) FROM orders),'payments',(SELECT count(*) FROM payment_attempt),'cart',(SELECT count(*) FROM cart_item),'stock',(SELECT json_agg(t ORDER BY sales_info_id) FROM (SELECT sales_info_id,available,reserved FROM sales_stock) t))");
    await sessions(context);
    const manifest = JSON.parse(fs.readFileSync(path.join(root, 'scripts/integration/required-checks.json'), 'utf8'));
    const names = new Set(context.result.checks.filter(check => check.passed).map(check => check.name));
    for (const group of ['member', 'seller', 'sessions']) {
      assert(manifest.groups[group].every(name => names.has(name)), `Missing required ${group} checks`);
    }
    context.result.focusedFlowsPassed = true;
  } catch (error) {
    context.result.error = runtime.sanitize(error.message); process.exitCode = 1;
    console.error(runtime.sanitize(error.message));
  } finally {
    try { await runtime.cleanup(); context.result.cleanupPassed = true; }
    catch (error) { context.result.error = runtime.sanitize(error.message); process.exitCode = 1; }
    context.result.completedAt = new Date().toISOString(); context.save();
    console.log(JSON.stringify({ executed: context.result.executed, failed: context.result.failed, passed: context.result.passed }));
    process.removeListener('SIGINT', interrupt); process.removeListener('SIGTERM', interrupt);
  }
}
run().catch(error => { console.error(error.message); process.exitCode = 1; });
