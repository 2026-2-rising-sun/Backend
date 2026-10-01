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
const { sessions } = require('./sessions.cjs');

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
    // Apidog round-trip is a separately reviewed snapshot, not an automated check in this run.
    context.result.externalVerification = {
      apidog: {
        projectId: 1390127,
        reviewedAt: '2026-10-01',
        evidence: 'https://app.notion.com/p/3ec226545d1581978775f3425f4208ea',
        approvedSha256: '0e404c4630d6431cfc7fb6e68b37d9d356e332d7eae6af805deed1c17f98692e',
        exportedSha256: '166947ba2fc498ef1f43fe55028c1b8fdeb960b30c1d4768807a00ae470d95e1',
        scope: '54 operations; equivalent after explicitly reviewed UI normalization; raw export is not lossless'
      }
    };
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
    await sessions(context);
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
