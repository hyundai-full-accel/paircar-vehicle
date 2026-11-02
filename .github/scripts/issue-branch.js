// 이슈가 열리면 "이슈번호-영어제목" 브랜치를 기본 브랜치에서 만든다.
// 영어 제목은 이슈 양식의 "영어 제목 (브랜치 이름)" 칸에서 읽고, 없으면 이슈 제목의 영문/숫자만 쓴다.
// .github/workflows/issue-branch.yml 에서 actions/github-script 로 실행한다.

const ENGLISH_TITLE_HEADING = '영어 제목 (브랜치 이름)';
const MAX_SLUG_LENGTH = 50;
const WRITE_PERMISSIONS = ['admin', 'maintain', 'write'];

/** "Add QR invite!" → "add-qr-invite" (영문 소문자·숫자·하이픈만) */
function slugify(text) {
  const slug = String(text ?? '')
    .normalize('NFKD')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  if (slug.length <= MAX_SLUG_LENGTH) return slug;
  const cut = slug.slice(0, MAX_SLUG_LENGTH);
  const lastDash = cut.lastIndexOf('-');
  return (lastDash > 0 ? cut.slice(0, lastDash) : cut).replace(/-+$/, '');
}

/** 이슈 양식 본문에서 "### 영어 제목 (브랜치 이름)" 아래 첫 줄을 꺼낸다. */
function readEnglishTitle(body) {
  const lines = String(body ?? '').split(/\r?\n/);
  const start = lines.findIndex((line) => line.trim() === `### ${ENGLISH_TITLE_HEADING}`);
  if (start < 0) return '';
  for (const line of lines.slice(start + 1)) {
    const value = line.trim();
    if (value.startsWith('### ')) break;
    if (value && value !== '_No response_') return value;
  }
  return '';
}

function branchNameFor(issue) {
  const slug = slugify(readEnglishTitle(issue.body)) || slugify(issue.title) || 'issue';
  return `${issue.number}-${slug}`;
}

async function hasWriteAccess(github, owner, repo, username) {
  try {
    const { data } = await github.rest.repos.getCollaboratorPermissionLevel({ owner, repo, username });
    return WRITE_PERMISSIONS.includes(data.permission);
  } catch (error) {
    return false;
  }
}

async function branchExists(github, owner, repo, branch) {
  try {
    await github.rest.git.getRef({ owner, repo, ref: `heads/${branch}` });
    return true;
  } catch (error) {
    if (error.status === 404) return false;
    throw error;
  }
}

async function createIssueBranch({ github, context, core }) {
  const { owner, repo } = context.repo;
  const issue = context.payload.issue;

  // 공개 저장소라 누구나 이슈를 열 수 있다. 쓰기 권한이 있는 팀원의 이슈만 브랜치를 만든다.
  if (!(await hasWriteAccess(github, owner, repo, issue.user.login))) {
    core.info(`${issue.user.login} has no write access; skipping branch creation.`);
    return;
  }

  const branch = branchNameFor(issue);
  if (await branchExists(github, owner, repo, branch)) {
    core.info(`Branch ${branch} already exists.`);
    return;
  }

  const baseBranch = context.payload.repository.default_branch;
  const { data: base } = await github.rest.repos.getBranch({ owner, repo, branch: baseBranch });
  await github.rest.git.createRef({ owner, repo, ref: `refs/heads/${branch}`, sha: base.commit.sha });

  await github.rest.issues.createComment({
    owner,
    repo,
    issue_number: issue.number,
    body: [
      `\`${baseBranch}\` 에서 작업 브랜치 \`${branch}\` 를 만들었어요.`,
      '',
      '```bash',
      'git fetch origin',
      `git switch ${branch}`,
      '```',
      '',
      `PR 본문에 \`Closes #${issue.number}\` 를 쓰면 머지할 때 이 이슈가 닫혀요.`,
    ].join('\n'),
  });
  core.info(`Created ${branch} from ${baseBranch}@${base.commit.sha}`);
}

module.exports = { slugify, readEnglishTitle, branchNameFor, createIssueBranch, ENGLISH_TITLE_HEADING };
