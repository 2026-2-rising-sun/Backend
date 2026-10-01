#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { Runtime, command } = require('./runtime.cjs');
const { Context } = require('./context.cjs');
const { memberAdmin } = require('./member-admin.cjs');
const { commerce } = require('./commerce.cjs');
const { liveFailures } = require('./live-failures.cjs');
const { mockFailures } = require('./mock-failures.cjs');

async function run() {
  const runtime = new Runtime(); let context;
  const interrupted = signal => {
    console.error(`Integration interrupted by ${signal}; cleaning only this run's resources`);
    runtime.cleanup().finally(() => process.exit(130));
  };
  process.once('SIGINT', interrupted); process.once('SIGTERM', interrupted);
  try {
    if (command('git', ['status', '--porcelain', '--untracked-files=normal']))
      throw new Error('Integration requires a clean checkout including untracked source files');
    context = new Context(runtime);
    context.result.deferred = [
      'Refresh rotation/reuse/logout/withdrawal require the agreed revocation policy and implementation',
      'Social login implementation and provider fixtures are not yet available'
    ];
    context.save();
    const usePrebuilt = process.argv.includes('--use-prebuilt') || process.env.CI === 'true';
    await runtime.build(usePrebuilt);
    context.result.jars = Object.fromEntries(Object.entries(runtime.jars).map(([service, file]) =>
      [service, crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')]));
    console.log(`Starting isolated ${runtime.mode} runtime at ${runtime.sha}`);
    await runtime.setup();
    await memberAdmin(context);
    await commerce(context);
    await mockFailures(context);
    await liveFailures(context);
    context.result.implementedFlowsPassed = true;
  } catch (error) {
    if (context) context.result.error = runtime.sanitize(error.message);
    console.error(runtime.sanitize(error.stack || error.message));
    process.exitCode = 1;
  } finally {
    try { await runtime.cleanup(); if (context) context.result.cleanupPassed = true; }
    catch (error) { if (context) context.result.error = runtime.sanitize(error.message); process.exitCode = 1; }
    if (context) {
      context.result.completedAt = new Date().toISOString(); context.save();
      console.log(`Executed ${context.result.executed}; failed ${context.result.failed}; deferred ${context.result.deferred.length}`);
      if (!context.result.passed) process.exitCode = 1;
    }
    process.removeListener('SIGINT', interrupted); process.removeListener('SIGTERM', interrupted);
  }
}
run().catch(error => { console.error(error.message); process.exitCode = 1; });
