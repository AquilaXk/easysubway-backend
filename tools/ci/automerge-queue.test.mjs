import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

const workflowUrl = new URL(
  '../../.github/workflows/automerge-queue.yml',
  import.meta.url,
);
const ciWorkflowUrl = new URL('../../.github/workflows/ci.yml', import.meta.url);
const producerWorkflowUrl = new URL(
  '../../.github/workflows/release-artifacts.yml',
  import.meta.url,
);

const readWorkflow = () => readFile(workflowUrl, 'utf8');

// `run: |` 블록의 본문은 10칸 들여쓰기다. 셸 블록을 그대로 실행하려면 벗겨야 한다.
const dedent = (block) => block.replace(/^ {10}/gm, '');

const stubbedBash = (lines) => {
  const dir = mkdtempSync(join(tmpdir(), 'automerge-queue-'));
  const log = join(dir, 'gh.log');
  const result = spawnSync(
    'bash',
    ['-c', [`GH_LOG=${JSON.stringify(log)}`, ': > "$GH_LOG"', ...lines].join('\n')],
    { encoding: 'utf8' },
  );
  return {
    status: result.status,
    stdout: result.stdout ?? '',
    stderr: result.stderr ?? '',
    calls: existsSync(log) ? readFileSync(log, 'utf8') : '',
  };
};

test('코디네이터는 PAT 없이 GITHUB_TOKEN으로만 동작한다', async () => {
  const workflow = await readWorkflow();

  for (const contract of [
    'GH_TOKEN: ${{ github.token }}',
    'pull_request_target:',
    'workflow_run:',
    'workflow_dispatch:',
    'schedule:',
    'cron: "*/10 * * * *"',
    'permissions: {}',
    'actions: write',
    'checks: read',
    'statuses: read',
    'contents: write',
    'pull-requests: write',
  ]) {
    assert.ok(workflow.includes(contract), `missing contract: ${contract}`);
  }

  // PAT 의존은 이 저장소의 큐를 통째로 정지시킨 원인이다. 어떤 형태로도 남기지 않는다.
  assert.doesNotMatch(workflow, /AUTOMERGE_PAT/);
  assert.doesNotMatch(workflow, /secrets\./);
  // 관리자 우회 병합과 squash 이외의 병합 방식은 사용하지 않는다.
  assert.doesNotMatch(workflow, /--admin|gh pr merge.+--merge|gh pr merge.+--rebase/);
  // 라벨 트리거는 base 저장소 권한으로 도는 pull_request_target이어야 한다.
  assert.ok(workflow.includes("github.event_name != 'pull_request_target'"));
  assert.ok(!workflow.includes('  pull_request:\n'));
});

test('큐는 FIFO 한 건만 처리하고 미해결 thread는 fail closed다', async () => {
  const workflow = await readWorkflow();

  for (const contract of [
    '--base main --state open --label automerge',
    '--limit 1000',
    "sort_by(.createdAt)[0].number // empty",
    '.isDraft == false',
    'reviewThreads(first: 100)',
    'hasNextPage',
    'pageInfo.hasNextPage == false',
    'all(.data.repository.pullRequest.reviewThreads.nodes[]; .isResolved)',
    'gh pr merge --squash --auto',
    '--match-head-commit "${head}"',
  ]) {
    assert.ok(workflow.includes(contract), `missing contract: ${contract}`);
  }
});

test('required context는 ruleset 전수 조회로만 판정한다', async () => {
  const workflow = await readWorkflow();

  for (const contract of [
    '/rules/branches/main',
    'required_status_checks',
    'integration_id',
    "jq -e 'length > 0' <<<\"${required}\"",
  ]) {
    assert.ok(workflow.includes(contract), `missing contract: ${contract}`);
  }

  // 하드코딩 폴백은 ruleset 변경·조회 실패를 통과시킨다. 조회 실패는 fail closed여야 한다.
  assert.doesNotMatch(workflow, /required_checks='\[/);
  assert.doesNotMatch(workflow, /Backend CI","Dependency/);
});

test('classic commit status는 전 페이지를 모아 판정한다', async () => {
  const workflow = await readWorkflow();

  const statusRequest = workflow.match(/statuses="\$\(gh api ([\s\S]*?)"\)"/)?.[1];
  assert.ok(statusRequest, 'classic status request must stay testable');
  for (const flag of [
    '--paginate',
    '--slurp',
    '/commits/${head}/statuses?per_page=100',
  ]) {
    assert.ok(statusRequest.includes(flag), `status request missing: ${flag}`);
  }
  // `/status`는 조합 결과를 단일 객체로 주고 페이지네이션되지 않는다. `/statuses`여야 한다.
  assert.doesNotMatch(statusRequest, /\/commits\/\$\{head\}\/status\?/);
  assert.ok(workflow.includes('($statuses | flatten) as $status_records'));
});

test('리뷰 게이트는 전 커밋의 활성 상태와 current head 긍정 리뷰를 함께 요구한다', async () => {
  const workflow = await readWorkflow();

  const reviewProgram = workflow.match(
    /# review-state-filter-begin\n\s+jq -e --arg head "\$\{head\}" '\n([\s\S]*?)\n\s+' <<<"\$\{reviews\}" >\/dev\/null/,
  )?.[1];
  assert.ok(reviewProgram, 'review state jq program must stay testable');

  const fallbackBody =
    '**Actionable comments posted: 0**\n<!-- Review source: Codex CLI fallback; canonical visible structure: PR #1926 Review 4676157515 -->';
  const review = (id, state, submittedAt, body = '', overrides = {}) => ({
    id,
    state,
    submitted_at: submittedAt,
    commit_id: 'head',
    author_association: 'OWNER',
    body,
    user: { login: 'reviewer' },
    ...overrides,
  });
  const runReviewFilter = (reviews) =>
    spawnSync('jq', ['-e', '--arg', 'head', 'head', reviewProgram], {
      input: JSON.stringify([reviews]),
    }).status;

  // 기본 판정.
  assert.equal(
    runReviewFilter([
      review(1, 'CHANGES_REQUESTED', '2026-08-01T00:00:00Z'),
      review(2, 'APPROVED', '2026-08-01T00:01:00Z'),
    ]),
    0,
  );
  assert.notEqual(
    runReviewFilter([
      review(1, 'CHANGES_REQUESTED', '2026-08-01T00:00:00Z'),
      review(2, 'COMMENTED', '2026-08-01T00:01:00Z'),
    ]),
    0,
  );
  assert.notEqual(
    runReviewFilter([review(1, 'COMMENTED', '2026-08-01T00:00:00Z')]),
    0,
  );
  // 폴백 리뷰는 규약 양식의 제목줄과 provenance marker를 모두 가져야 한다.
  assert.equal(
    runReviewFilter([review(1, 'COMMENTED', '2026-08-01T00:00:00Z', fallbackBody)]),
    0,
  );
  // 신뢰되지 않는 author_association은 어떤 본문으로도 게이트를 통과하지 못한다.
  assert.notEqual(
    runReviewFilter([
      review(1, 'COMMENTED', '2026-08-01T00:00:00Z', fallbackBody, {
        author_association: 'NONE',
      }),
    ]),
    0,
  );

  // ① 이전 head에 남은 활성 change request는 head가 바뀌어도 게이트에서 사라지지 않는다.
  assert.notEqual(
    runReviewFilter([
      review(1, 'CHANGES_REQUESTED', '2026-08-01T00:00:00Z', '', {
        commit_id: 'previous-head',
        user: { login: 'reviewer-one' },
      }),
      review(2, 'APPROVED', '2026-08-01T00:01:00Z', '', {
        user: { login: 'reviewer-two' },
      }),
    ]),
    0,
  );
  // 폴백 리뷰가 current head에 있어도 다른 리뷰어의 이전 head change request는 막는다.
  assert.notEqual(
    runReviewFilter([
      review(1, 'CHANGES_REQUESTED', '2026-08-01T00:00:00Z', '', {
        commit_id: 'previous-head',
        user: { login: 'reviewer-one' },
      }),
      review(2, 'COMMENTED', '2026-08-01T00:01:00Z', fallbackBody, {
        user: { login: 'reviewer-two' },
      }),
    ]),
    0,
  );
  // 같은 리뷰어가 current head에서 승인하면 이전 change request는 해소된다.
  assert.equal(
    runReviewFilter([
      review(1, 'CHANGES_REQUESTED', '2026-08-01T00:00:00Z', '', {
        commit_id: 'previous-head',
      }),
      review(2, 'APPROVED', '2026-08-01T00:01:00Z'),
    ]),
    0,
  );
  // 긍정 리뷰는 여전히 current head를 요구한다.
  assert.notEqual(
    runReviewFilter([
      review(1, 'APPROVED', '2026-08-01T00:00:00Z', '', {
        commit_id: 'previous-head',
      }),
    ]),
    0,
  );
  assert.notEqual(
    runReviewFilter([
      review(1, 'COMMENTED', '2026-08-01T00:00:00Z', fallbackBody, {
        commit_id: 'previous-head',
      }),
    ]),
    0,
  );

  // ② dismiss된 change request는 활성이 아니므로 큐를 막지 않는다.
  assert.equal(
    runReviewFilter([
      review(1, 'DISMISSED', '2026-08-01T00:00:00Z', '', {
        commit_id: 'previous-head',
        user: { login: 'reviewer-one' },
      }),
      review(2, 'APPROVED', '2026-08-01T00:01:00Z', '', {
        user: { login: 'reviewer-two' },
      }),
    ]),
    0,
  );
  // ③ dismiss_stale_reviews로 무효화된 이전 head 승인도 큐를 막지 않는다.
  assert.equal(
    runReviewFilter([
      review(1, 'APPROVED', '2026-08-01T00:00:00Z', '', {
        commit_id: 'previous-head',
        user: { login: 'reviewer-one' },
      }),
      review(2, 'DISMISSED', '2026-08-01T00:01:00Z', '', {
        commit_id: 'previous-head',
        user: { login: 'reviewer-one' },
      }),
      review(3, 'APPROVED', '2026-08-01T00:02:00Z', '', {
        user: { login: 'reviewer-two' },
      }),
    ]),
    0,
  );
  // dismissed가 섞여 있어도 다른 리뷰어의 활성 change request는 그대로 막는다.
  assert.notEqual(
    runReviewFilter([
      review(1, 'DISMISSED', '2026-08-01T00:00:00Z', '', {
        commit_id: 'previous-head',
        user: { login: 'reviewer-one' },
      }),
      review(2, 'CHANGES_REQUESTED', '2026-08-01T00:01:00Z', '', {
        commit_id: 'previous-head',
        user: { login: 'reviewer-two' },
      }),
      review(3, 'APPROVED', '2026-08-01T00:02:00Z', '', {
        user: { login: 'reviewer-three' },
      }),
    ]),
    0,
  );
  // dismiss 이후 같은 리뷰어가 다시 남긴 change request는 정상 반영된다.
  assert.notEqual(
    runReviewFilter([
      review(1, 'DISMISSED', '2026-08-01T00:00:00Z', '', {
        commit_id: 'previous-head',
        user: { login: 'reviewer-one' },
      }),
      review(2, 'CHANGES_REQUESTED', '2026-08-01T00:01:00Z', '', {
        commit_id: 'previous-head',
        user: { login: 'reviewer-one' },
      }),
      review(3, 'APPROVED', '2026-08-01T00:02:00Z', '', {
        user: { login: 'reviewer-two' },
      }),
    ]),
    0,
  );
  // dismissed 리뷰만 남으면 활성 리뷰가 없으므로 fail closed로 막는다.
  assert.notEqual(
    runReviewFilter([
      review(1, 'DISMISSED', '2026-08-01T00:00:00Z', '', {
        commit_id: 'previous-head',
      }),
    ]),
    0,
  );
});

test('required context 판정은 뒤 페이지 status까지 본다', async () => {
  const workflow = await readWorkflow();

  const checkProgram = workflow.match(
    /# required-context-filter-begin\n\s+jq -e [^']+'\n([\s\S]*?)\n\s+' <<<"\$\{checks\}" >\/dev\/null/,
  )?.[1];
  assert.ok(checkProgram, 'required context jq program must stay testable');

  // statusPages는 `gh api --paginate --slurp` 결과와 같은 페이지 배열이다.
  const runCheckFilter = (
    checkRuns,
    statusPages = [],
    requiredCheck = { context: 'Backend CI', integration_id: null },
  ) =>
    spawnSync(
      'jq',
      [
        '-e',
        '--argjson',
        'required_check',
        JSON.stringify(requiredCheck),
        '--argjson',
        'statuses',
        JSON.stringify(statusPages),
        checkProgram,
      ],
      { input: JSON.stringify([{ check_runs: checkRuns }]) },
    ).status;

  assert.notEqual(
    runCheckFilter([
      { id: 1, name: 'Backend CI', conclusion: 'success', started_at: '2026-08-01T00:00:00Z' },
      { id: 2, name: 'Backend CI', conclusion: 'failure', started_at: '2026-08-01T00:01:00Z' },
    ]),
    0,
  );
  assert.equal(
    runCheckFilter([
      { id: 1, name: 'Backend CI', conclusion: 'failure', started_at: '2026-08-01T00:00:00Z' },
      { id: 2, name: 'Backend CI', conclusion: 'success', started_at: '2026-08-01T00:01:00Z' },
    ]),
    0,
  );
  // ④ required context가 두 번째 status 페이지에 있어도 찾아낸다.
  assert.equal(
    runCheckFilter(
      [],
      [
        [{ id: 1, context: 'Other CI', state: 'success', updated_at: '2026-08-01T00:00:00Z' }],
        [{ id: 2, context: 'Backend CI', state: 'success', updated_at: '2026-08-01T00:01:00Z' }],
      ],
    ),
    0,
  );
  // 뒤 페이지의 최신 실패가 앞 페이지의 성공을 덮는다.
  assert.notEqual(
    runCheckFilter(
      [],
      [
        [{ id: 1, context: 'Backend CI', state: 'success', updated_at: '2026-08-01T00:00:00Z' }],
        [{ id: 2, context: 'Backend CI', state: 'failure', updated_at: '2026-08-01T00:01:00Z' }],
      ],
    ),
    0,
  );
  // integration_id가 지정된 required context는 다른 앱의 동명 check로 충족되지 않는다.
  assert.notEqual(
    runCheckFilter(
      [{ id: 1, name: 'Backend CI', conclusion: 'success', started_at: '2026-08-01T00:00:00Z', app: { id: 7 } }],
      [[{ id: 2, context: 'Backend CI', state: 'success', updated_at: '2026-08-01T00:01:00Z' }]],
      { context: 'Backend CI', integration_id: 42 },
    ),
    0,
  );
  assert.equal(
    runCheckFilter(
      [{ id: 1, name: 'Backend CI', conclusion: 'success', started_at: '2026-08-01T00:00:00Z', app: { id: 42 } }],
      [],
      { context: 'Backend CI', integration_id: 42 },
    ),
    0,
  );
});

test('merge-state 분기는 상태별로 병합·물러남·실패를 구분한다', async () => {
  const workflow = await readWorkflow();

  const dispatchBlock = workflow.match(
    /# merge-state-dispatch-begin\n([\s\S]*?)\n\s+# merge-state-dispatch-end/,
  )?.[1];
  assert.ok(dispatchBlock, 'merge state dispatch must stay testable');

  // gh 호출을 기록만 하는 스텁으로 대체해 상태별 분기 결과를 실측한다.
  const runDispatch = (mergeState, { headRepo = 'o/r', newHead = 'updated-head' } = {}) => {
    const result = stubbedBash([
      'set -euo pipefail',
      'gh() {',
      `  printf '%s\\n' "gh $*" >> "$GH_LOG"`,
      '  case "$*" in',
      `    *"pr view"*headRefOid*) printf '%s\\n' ${JSON.stringify(newHead)} ;;`,
      '  esac',
      '}',
      'sleep() { :; }',
      'pr=26',
      'repo=o/r',
      'head=old-head',
      `head_repo=${JSON.stringify(headRepo)}`,
      'head_ref=feature',
      `merge_state=${JSON.stringify(mergeState)}`,
      dedent(dispatchBlock),
    ]);
    return {
      status: result.status,
      merged: result.calls.includes('gh pr merge'),
      updatedBranch: result.calls.includes('update-branch'),
      dispatchedCi: result.calls.includes('workflow run ci.yml'),
    };
  };

  // ⑥ 병합 가능 상태. UNSTABLE은 "필수가 아닌 check가 green이 아님"일 뿐이고 required
  // context는 앞에서 ruleset 기준으로 이미 검증했으므로 병합을 진행한다.
  for (const mergeState of ['CLEAN', 'HAS_HOOKS', 'UNSTABLE']) {
    assert.deepEqual(
      runDispatch(mergeState),
      { status: 0, merged: true, updatedBranch: false, dispatchedCi: false },
      `${mergeState} must proceed to merge`,
    );
  }
  // base 갱신이 필요한 상태는 update-branch 후 CI를 명시 dispatch한다.
  assert.deepEqual(runDispatch('BEHIND'), {
    status: 0,
    merged: false,
    updatedBranch: true,
    dispatchedCi: true,
  });
  // ⑨ update-branch는 비동기라 bounded wait 안에 head가 안 바뀔 수 있다. 계약 위반이
  // 아니라 대기 상태이므로 stale ref로 CI를 쏘지 않고 실패하지도 않는다.
  assert.deepEqual(runDispatch('BEHIND', { newHead: 'old-head' }), {
    status: 0,
    merged: false,
    updatedBranch: true,
    dispatchedCi: false,
  });
  // fork head에 base 저장소 CI를 dispatch하지 않는다.
  const forkBehind = runDispatch('BEHIND', { headRepo: 'fork/r' });
  assert.notEqual(forkBehind.status, 0);
  assert.equal(forkBehind.updatedBranch, false);
  // ⑦ 전이·대기 상태에서 exit 1을 내면 그 실패 check가 PR을 UNSTABLE로 만들어 다음
  // 실행을 같은 자리에서 죽인다. 조용히 물러나 다음 트리거에서 재시도한다.
  for (const mergeState of ['BLOCKED', 'UNKNOWN']) {
    assert.deepEqual(
      runDispatch(mergeState),
      { status: 0, merged: false, updatedBranch: false, dispatchedCi: false },
      `${mergeState} must back off without failing the run`,
    );
  }
  // ⑧ 충돌은 사람이 해소해야 하므로 계약 위반으로 실패시킨다.
  const dirty = runDispatch('DIRTY');
  assert.notEqual(dirty.status, 0);
  assert.equal(dirty.merged, false);
  // 알 수 없는 상태에서 조용히 물러나면 큐가 원인 없이 멈추므로 실패시킨다.
  const unknownEnum = runDispatch('SOME_NEW_STATE');
  assert.notEqual(unknownEnum.status, 0);
  assert.equal(unknownEnum.merged, false);
});

test('게이트는 병합 분기보다 앞서고 producer dispatch는 큐보다 앞선다', async () => {
  const workflow = await readWorkflow();

  // `set -e` 아래에서 게이트는 `jq -e` 실패 시 즉시 종료되므로, 계약 위반은 병합
  // 분기에 닿기 전에 exit 1로 끝난다.
  assert.ok(workflow.includes('set -euo pipefail'));
  const producerAt = workflow.indexOf('# producer-dispatch-end');
  const reviewGateAt = workflow.indexOf('# review-state-filter-end');
  const contextGateAt = workflow.indexOf('# required-context-filter-end');
  const dispatchAt = workflow.indexOf('# merge-state-dispatch-begin');
  assert.ok(producerAt > 0, 'producer dispatch marker must exist');
  // ⑩ producer dispatch → 리뷰 게이트 → required context 게이트 → 병합 분기 순서.
  // producer가 큐보다 앞서야 큐가 막힌 동안에도 배포 체인이 끊기지 않는다.
  assert.ok(reviewGateAt > producerAt, 'producer dispatch must precede the queue gates');
  assert.ok(contextGateAt > reviewGateAt, 'review gate must precede the required context gate');
  assert.ok(dispatchAt > contextGateAt, 'gates must precede the merge dispatch');
});

test('image producer는 main head 기준으로 정확히 한 번 dispatch된다', async () => {
  const workflow = await readWorkflow();

  const producerBlock = workflow.match(
    /# producer-dispatch-begin\n([\s\S]*?)\n\s+# producer-dispatch-end/,
  )?.[1];
  assert.ok(producerBlock, 'producer dispatch block must stay testable');
  // 중복·누락을 동시에 막는 판정 키는 workflow runs API의 head_sha다.
  assert.ok(producerBlock.includes('head_sha=${main_sha}'));
  assert.ok(producerBlock.includes('select(.event != "pull_request")'));
  assert.ok(producerBlock.includes('gh workflow run "${producer}"'));

  const runProducer = ({
    existingRuns = 0,
    appearsAfterDispatch = true,
    mainSha = 'a'.repeat(40),
  } = {}) => {
    const dir = mkdtempSync(join(tmpdir(), 'producer-dispatch-'));
    const dispatched = join(dir, 'dispatched');
    const result = stubbedBash([
      'set -euo pipefail',
      `DISPATCHED=${JSON.stringify(dispatched)}`,
      'gh() {',
      `  printf '%s\\n' "gh $*" >> "$GH_LOG"`,
      '  case "$*" in',
      `    *"commits/main"*) printf '%s\\n' ${JSON.stringify(mainSha)} ;;`,
      '    *"runs?head_sha="*)',
      `      if [ -f "$DISPATCHED" ] && [ ${appearsAfterDispatch ? '1' : '0'} -eq 1 ]; then`,
      "        printf '1\\n'",
      '      else',
      `        printf '%s\\n' ${JSON.stringify(String(existingRuns))}`,
      '      fi ;;',
      '    "workflow run"*) : > "$DISPATCHED" ;;',
      '  esac',
      '}',
      'sleep() { :; }',
      'repo=o/r',
      'producer=release-artifacts.yml',
      dedent(producerBlock),
    ]);
    return {
      status: result.status,
      stdout: result.stdout,
      stderr: result.stderr,
      dispatched: result.calls.includes('gh workflow run release-artifacts.yml'),
    };
  };

  // main head에 producer 실행이 없으면 dispatch한다 (누락 방지).
  const missing = runProducer({ existingRuns: 0 });
  assert.equal(missing.status, 0);
  assert.equal(missing.dispatched, true);

  // 이미 실행이 있으면 dispatch하지 않는다 (중복 방지). push 병합·수동 실행·직전
  // 코디네이터 dispatch가 모두 같은 head_sha로 잡힌다.
  const present = runProducer({ existingRuns: 2 });
  assert.equal(present.status, 0);
  assert.equal(present.dispatched, false);

  // dispatch 후 run 목록 반영이 늦어도 큐를 막지 않는다. 다음 트리거가 같은 키로
  // 다시 판정하고, 같은 내용의 재빌드는 push-by-digest라 무해하다.
  const lagging = runProducer({ existingRuns: 0, appearsAfterDispatch: false });
  assert.equal(lagging.status, 0);
  assert.equal(lagging.dispatched, true);
  assert.match(lagging.stdout, /::warning::/);

  // main head revision 조회가 깨지면 잘못된 ref로 dispatch하지 않고 실패한다.
  const broken = runProducer({ mainSha: 'null' });
  assert.notEqual(broken.status, 0);
  assert.equal(broken.dispatched, false);
});

// `run: |` 본문은 YAML block scalar다. 안쪽 줄 하나가 블록 들여쓰기 아래로 내려가면
// 블록은 거기서 끝나고 나머지 스크립트가 YAML 구조로 새어 나간다. 문자열 포함 검사만
// 하는 계약 테스트는 그 파손을 그대로 통과시키므로 구조 자체를 계약으로 고정한다.
const runBlocks = (workflow) => {
  const lines = workflow.split('\n');
  const blocks = [];
  for (let index = 0; index < lines.length; index += 1) {
    const opener = /^(\s*)run: \|\s*$/.exec(lines[index]);
    if (!opener) continue;
    const keyIndent = opener[1].length;
    let cursor = index + 1;
    while (cursor < lines.length && lines[cursor].trim() === '') cursor += 1;
    const blockIndent = /^\s*/.exec(lines[cursor] ?? '')[0].length;
    const body = [];
    let terminator = null;
    for (; cursor < lines.length; cursor += 1) {
      const line = lines[cursor];
      if (line.trim() === '') {
        body.push('');
        continue;
      }
      if (/^\s*/.exec(line)[0].length < blockIndent) {
        terminator = { line, number: cursor + 1 };
        break;
      }
      body.push(line.slice(blockIndent));
    }
    blocks.push({ keyIndent, blockIndent, body: body.join('\n'), terminator, openedAt: index + 1 });
  }
  return blocks;
};

test('워크플로 run 블록은 YAML block scalar 들여쓰기를 지킨다', async () => {
  const files = [
    ['automerge-queue.yml', await readWorkflow()],
    ['ci.yml', await readFile(ciWorkflowUrl, 'utf8')],
    ['release-artifacts.yml', await readFile(producerWorkflowUrl, 'utf8')],
  ];

  for (const [name, workflow] of files) {
    const blocks = runBlocks(workflow);
    assert.ok(blocks.length > 0, `${name}: run 블록 추출이 비었다`);

    for (const block of blocks) {
      assert.ok(
        block.blockIndent > block.keyIndent,
        `${name}:${block.openedAt}: block scalar 본문이 run 키보다 깊게 들여쓰기되어야 한다`,
      );
      // 블록을 끝내는 줄은 반드시 더 얕은 레벨의 정상 YAML 키여야 한다. 스크립트 본문이
      // 흘러넘친 줄이면 여기서 걸린다.
      if (block.terminator) {
        assert.match(
          block.terminator.line,
          /^ *(- )?[A-Za-z_][A-Za-z0-9_.-]*:(\s|$)/,
          `${name}:${block.terminator.number}: block scalar 밖으로 새어 나온 줄 — ${JSON.stringify(block.terminator.line)}`,
        );
      }
      // 구조가 살아 있어도 내용이 잘리면 셸이 깨진다. 두 겹으로 잡는다.
      const syntax = spawnSync('bash', ['-n'], { input: block.body, encoding: 'utf8' });
      assert.equal(
        syntax.status,
        0,
        `${name}:${block.openedAt}: run 블록이 bash 문법 검사에 실패했다 — ${syntax.stderr}`,
      );
    }
  }
});

test('여러 줄 셸 문자열은 한 줄 안에서 닫힌다', async () => {
  // mobile에서 코멘트 본문을 여러 줄로 쓴 `--body "` 가 block scalar를 깨뜨렸다.
  // 여러 줄이 필요하면 printf '%s\n' 로 조립한다.
  const workflow = await readWorkflow();
  assert.doesNotMatch(workflow, /--body "[^"\n]*$/m);
});

test('CI는 실제 YAML 파서로 워크플로를 검사한다', async () => {
  const ciWorkflow = await readFile(ciWorkflowUrl, 'utf8');
  assert.ok(ciWorkflow.includes('rhysd/actionlint@sha256:'));
  assert.match(ciWorkflow, /docker run --rm[\s\S]{0,200}rhysd\/actionlint@sha256:[a-f0-9]{64}/);
});

test('배포 체인 워크플로는 명시 dispatch에서도 성립한다', async () => {
  const ciWorkflow = await readFile(ciWorkflowUrl, 'utf8');
  const producerWorkflow = await readFile(producerWorkflowUrl, 'utf8');

  // job key는 2칸, job 속성은 4칸 들여쓰기다. 4칸 이상 줄만 건너뛰므로 탐색이 다음
  // job으로 넘어가지 않는다.
  const jobCondition = (workflow, job) =>
    workflow.match(new RegExp(`\\n {2}${job}:\\n(?: {4,}[^\\n]*\\n)*? {4}if: ([^\\n]*)`))?.[1];

  // GITHUB_TOKEN dispatch로 CI를 다시 돌릴 때 required context 두 개가 모두 나와야
  // update-branch 이후 PR이 병합 가능해진다.
  assert.ok(ciWorkflow.includes('  workflow_dispatch:'));
  const osvCondition = jobCondition(ciWorkflow, 'dependency-vulnerability-scan');
  assert.ok(osvCondition, 'osv job condition must stay testable');
  assert.ok(osvCondition.includes("github.event_name == 'pull_request'"));
  assert.ok(osvCondition.includes("github.event_name == 'workflow_dispatch'"));

  // GITHUB_TOKEN 병합은 push 이벤트를 만들지 않으므로 producer는 dispatch로도
  // 이미지 job까지 실행돼야 한다.
  assert.ok(producerWorkflow.includes('  workflow_dispatch:'));
  const releaseCondition = jobCondition(producerWorkflow, 'backend-release');
  assert.ok(releaseCondition, 'producer release job condition must stay testable');
  assert.ok(releaseCondition.includes("github.event_name == 'push'"));
  assert.ok(releaseCondition.includes("github.event_name == 'workflow_dispatch'"));
  assert.ok(releaseCondition.includes("github.ref == 'refs/heads/main'"));

  // dispatch가 이미지를 발행하게 된 이상 preflight까지 중복 실행할 이유가 없다.
  const preflightCondition = jobCondition(producerWorkflow, 'image-preflight');
  assert.ok(preflightCondition, 'producer preflight job condition must stay testable');
  assert.ok(preflightCondition.includes("github.event_name == 'pull_request'"));
});
