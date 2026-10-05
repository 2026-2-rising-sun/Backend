const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

// Execute the exact inline workflow script with an in-memory GitHub API.
const workflow = fs.readFileSync(path.join(__dirname, '../workflows/labeler.yml'), 'utf8');
const block = workflow.match(/^          script: \|\n((?:            .*\n?)+)/m);
assert.ok(block, 'inline size label script must exist');
const source = block[1].split('\n').map(line => line.slice(12)).join('\n');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const run = new AsyncFunction('github', 'context', 'core', source);

async function labelFiles(files, initialLabels = [], pageSize = 100) {
  const labels = new Set(initialLabels);
  const mutations = [];
  const requests = [];
  const listFiles = () => {};
  const listLabelsOnIssue = () => {};
  const github = {
    rest: {
      pulls: { listFiles },
      issues: {
        listLabelsOnIssue,
        removeLabel: async args => {
          mutations.push(['remove', args.name]);
          labels.delete(args.name);
        },
        addLabels: async args => {
          mutations.push(['add', ...args.labels]);
          args.labels.forEach(name => labels.add(name));
        },
      },
    },
    paginate: async (method, args) => {
      assert.equal(args.owner, 'example');
      assert.equal(args.repo, 'Backend');
      assert.equal(args.per_page, 100);
      requests.push(method);
      if (method === listFiles) {
        assert.equal(args.pull_number, 143);
        const pages = [];
        for (let start = 0; start < files.length; start += pageSize) {
          pages.push(files.slice(start, start + pageSize));
        }
        return pages.flat();
      }
      assert.equal(method, listLabelsOnIssue);
      assert.equal(args.issue_number, 143);
      return [...labels].map(name => ({ name }));
    },
  };
  const messages = [];
  await run(github, {
    repo: { owner: 'example', repo: 'Backend' },
    payload: { pull_request: { number: 143 } },
  }, { info: message => messages.push(message) });
  assert.deepEqual(requests, [listFiles, listLabelsOnIssue]);
  return { labels: [...labels], mutations, messages };
}

function file(filename, additions, deletions = 0, extra = {}) {
  return { filename, additions, deletions, status: 'modified', ...extra };
}

for (const [lines, size] of [
  [0, 'XS'], [49, 'XS'], [50, 'S'], [199, 'S'], [200, 'M'],
  [499, 'M'], [500, 'L'], [999, 'L'], [1000, 'XL'], [2000, 'XL'],
]) {
  test(`${lines} implementation additions + deletions -> ${size}`, async () => {
    const additions = Math.floor(lines / 2);
    const result = await labelFiles([file('services/live-service/Main.java', additions, lines - additions)]);
    assert.deepEqual(result.labels, [`size/${size}`]);
  });
}

test('prose, API documents, exports, locks and migration SQL do not inflate size', async () => {
  const excluded = [
    'README.md', 'docs/guide.mdx', 'docs/guide.rst', 'docs/guide.adoc',
    'README-notes.txt', 'services/live-service/README.txt', 'docs/nested/notes.txt',
    'contracts/docs/nested/notes.txt', 'gradle.lock', 'scripts/contracts/package-lock.json',
    'gradle/wrapper/gradle-wrapper.jar', 'contracts/api/live-service.yaml',
    'contracts/api/nested/member.yml', 'contracts/api/member.json',
    'contracts/docs/change-review.json', 'contracts/exports/nested/apidog.json',
    'contracts/exports/bundle.yaml', 'contracts/exports/bundle.yml',
    'services/member-service/src/main/resources/db/migration/V1__members.sql',
  ];
  const result = await labelFiles(excluded.map(name => file(name, 2000, 2000)));
  assert.deepEqual(result.labels, ['size/XS']);
});

test('runtime YAML, executable SQL, tests and event contracts remain counted', async () => {
  const included = [
    'services/live-service/src/main/resources/application.yml',
    '.github/workflows/ci.yml', 'scripts/contracts/redocly.yaml',
    'contracts/scenarios/required-coverage.json', 'contracts/docs/swagger-config.json',
    'contracts/events/src/main/java/OrderPlacedEvent.java',
    'services/commerce-service/src/test/java/MigrationPostgresTest.java',
    'services/live-service/src/test/resources/setup.sql',
  ];
  for (const name of included) {
    const result = await labelFiles([file(name, 300, 200)]);
    assert.deepEqual(result.labels, ['size/L'], name);
  }
});

test('101st file is counted through pagination', async () => {
  const files = Array.from({ length: 100 }, (_, index) => file(`docs/${index}.md`, 2000));
  files.push(file('services/shopping-service/Main.java', 700, 300));
  const result = await labelFiles(files);
  assert.deepEqual(result.labels, ['size/XL']);
});

test('size updates preserve all unrelated labels and remove stale size labels', async () => {
  const result = await labelFiles([file('Main.java', 500)], [
    'service:shopping', 'review:needed', 'manually-added', 'size/XL', 'size/S',
  ]);
  assert.deepEqual(result.labels, ['service:shopping', 'review:needed', 'manually-added', 'size/L']);
  assert.deepEqual(result.mutations, [['remove', 'size/XL'], ['remove', 'size/S'], ['add', 'size/L']]);
});

test('matching size label is retained without redundant writes', async () => {
  const result = await labelFiles([file('Main.java', 500)], ['service:live', 'size/L']);
  assert.deepEqual(result.labels, ['service:live', 'size/L']);
  assert.deepEqual(result.mutations, []);
});

test('renamed implementation remains counted when the destination is prose', async () => {
  const result = await labelFiles([file('docs/retired.md', 0, 500, {
    status: 'renamed', previous_filename: 'services/live-service/Retired.java',
  })]);
  assert.deepEqual(result.labels, ['size/L']);
});

test('prose-only renames remain excluded while prose moved into implementation counts', async () => {
  const result = await labelFiles([
    file('docs/renamed.md', 2000, 2000, { status: 'renamed', previous_filename: 'README.md' }),
    file('services/live-service/New.java', 200, 0, { status: 'renamed', previous_filename: 'docs/snippet.md' }),
  ]);
  assert.deepEqual(result.labels, ['size/M']);
});

test('deleted implementation counts deletions while deleted migrations are excluded', async () => {
  const result = await labelFiles([
    file('services/live-service/Retired.java', 0, 500, { status: 'removed' }),
    file('services/live-service/src/main/resources/db/migration/V1__old.sql', 0, 2000, { status: 'removed' }),
  ]);
  assert.deepEqual(result.labels, ['size/L']);
});
