// node --test .github/scripts/issue-branch.test.js
const test = require('node:test');
const assert = require('node:assert/strict');
const { slugify, readEnglishTitle, branchNameFor, createIssueBranch, ENGLISH_TITLE_HEADING } = require('./issue-branch.js');

const formBody = (englishTitle) => [
  `### ${ENGLISH_TITLE_HEADING}`,
  '',
  englishTitle,
  '',
  '### 작업 내용',
  '',
  '초대 링크 대신 QR',
].join('\n');

test('slugify keeps only lowercase ascii words joined by dashes', () => {
  assert.equal(slugify('Replace invite link with QR code!'), 'replace-invite-link-with-qr-code');
  assert.equal(slugify('  V-06 driving screen  '), 'v-06-driving-screen');
  assert.equal(slugify('QR 초대 연결'), 'qr');
  assert.equal(slugify('주행 화면'), '');
});

test('slugify cuts long titles at a word boundary', () => {
  const slug = slugify('make the driving screen responsive for three way split screen layouts on pleos');
  assert.ok(slug.length <= 50, slug);
  assert.ok(!slug.endsWith('-'));
  assert.equal(slug, 'make-the-driving-screen-responsive-for-three-way');
});

test('english title comes from the issue form field', () => {
  assert.equal(readEnglishTitle(formBody('Add QR invite')), 'Add QR invite');
  assert.equal(readEnglishTitle(formBody('_No response_')), '');
  assert.equal(readEnglishTitle(formBody('Add QR invite').replace(/\n/g, '\r\n')), 'Add QR invite');
  assert.equal(readEnglishTitle(null), '');
});

test('branch name is issue number plus english title', () => {
  assert.equal(branchNameFor({ number: 12, title: 'QR 초대', body: formBody('Add QR invite') }), '12-add-qr-invite');
  // 양식 없이 연 이슈: 제목의 영문만, 그것도 없으면 issue
  assert.equal(branchNameFor({ number: 13, title: 'Fix V-06 layout', body: '' }), '13-fix-v-06-layout');
  assert.equal(branchNameFor({ number: 14, title: '폰트 변경', body: null }), '14-issue');
});

function fakeGithub({ permission = 'write', existingBranches = [] } = {}) {
  const calls = [];
  const notFound = () => Object.assign(new Error('Not Found'), { status: 404 });
  return {
    calls,
    rest: {
      repos: {
        getCollaboratorPermissionLevel: async () => ({ data: { permission } }),
        getBranch: async ({ branch }) => ({ data: { commit: { sha: `sha-of-${branch}` } } }),
      },
      git: {
        getRef: async ({ ref }) => {
          if (existingBranches.includes(ref.replace('heads/', ''))) return { data: {} };
          throw notFound();
        },
        createRef: async (args) => { calls.push(['createRef', args]); },
      },
      issues: {
        createComment: async (args) => { calls.push(['createComment', args]); },
      },
    },
  };
}

const context = {
  repo: { owner: 'hyundai-full-accel', repo: 'paircar-vehicle' },
  payload: {
    repository: { default_branch: 'main' },
    issue: { number: 7, title: 'QR 초대', body: formBody('QR invite'), user: { login: 'teammate' } },
  },
};
const core = { info: () => {} };

test('creates the branch from the default branch and comments on the issue', async () => {
  const github = fakeGithub();
  await createIssueBranch({ github, context, core });
  assert.deepEqual(github.calls[0], ['createRef', {
    owner: 'hyundai-full-accel', repo: 'paircar-vehicle', ref: 'refs/heads/7-qr-invite', sha: 'sha-of-main',
  }]);
  assert.equal(github.calls[1][0], 'createComment');
  assert.match(github.calls[1][1].body, /git switch 7-qr-invite/);
});

test('skips issues from people without write access', async () => {
  const github = fakeGithub({ permission: 'read' });
  await createIssueBranch({ github, context, core });
  assert.equal(github.calls.length, 0);
});

test('does not recreate an existing branch', async () => {
  const github = fakeGithub({ existingBranches: ['7-qr-invite'] });
  await createIssueBranch({ github, context, core });
  assert.equal(github.calls.length, 0);
});
