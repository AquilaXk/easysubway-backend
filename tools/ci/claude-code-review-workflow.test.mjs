import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import test from "node:test";

// Claude Code 공식 /code-review를 PR discovery 리뷰로 실행하는 workflow 계약 (#390, hub AquilaXk/easysubway#3006 이식).
const workflow = readFileSync(new URL("../../.github/workflows/claude-code-review.yml", import.meta.url), "utf8");

// 들여쓰기 0칸 최상위 키(on:, permissions:, jobs: ...) 사이의 블록을 잘라낸다.
const topLevelBlock = (key) => {
  const match = workflow.match(new RegExp(`^${key}:[^\\n]*\\n((?:(?:[ \\t][^\\n]*)?\\n)*)`, "m"));
  assert.ok(match, `${key}: 최상위 블록이 필요하다`);
  return match[1].replace(/\n+$/, "\n");
};
const stepBlock = (name) => {
  const start = workflow.indexOf(`- name: ${name}\n`);
  assert.ok(start >= 0, `${name} step이 필요하다`);
  const next = workflow.indexOf("\n      - name: ", start + 1);
  return workflow.slice(start, next === -1 ? undefined : next);
};

test("트리거는 PR opened·ready_for_review와 PR 번호 수동 재실행뿐이다", () => {
  const on = topLevelBlock("on");
  assert.match(on, /^ {2}pull_request:\n {4}types:\n {6}- opened\n {6}- ready_for_review\n/m);
  assert.match(on, /^ {2}workflow_dispatch:\n {4}inputs:\n {6}pr_number:\n(?: {8}[^\n]*\n)* {8}required: true\n(?: {8}[^\n]*\n)* {8}type: number\n/m);
  assert.doesNotMatch(on, /synchronize|reopened|labeled|pull_request_target|push:|schedule:|issue_comment|pull_request_review/);
});

test("Draft·fork·봇이 연 PR은 job-level if로 명시적으로 건너뛰고 수동 재실행은 영향받지 않는다", () => {
  const jobIf = workflow.match(/^ {4}if: (?:>-?\n)?([\s\S]*?)^ {4}runs-on:/m)?.[1];
  assert.ok(jobIf, "review job에 job-level if 조건이 필요하다");
  assert.equal(
    jobIf.replace(/\s+/g, " ").trim(),
    "(github.event_name == 'pull_request' && github.event.pull_request.draft == false && "
      + "github.event.pull_request.head.repo.full_name == github.repository && "
      + "github.event.pull_request.user.type != 'Bot') || github.event_name == 'workflow_dispatch'",
  );
  // skip된 job은 claude[bot] Review를 만들지 않으므로 게이트 통과가 아니다(automerge-queue.test.mjs의 marker·Review 부재 → 거부).
});

test("수동 재실행도 open·non-draft·same-repo PR만 받고 아니면 실패한다", () => {
  const resolve = stepBlock("Resolve pull request");
  assert.match(resolve, /PR_NUMBER: \$\{\{ github\.event\.pull_request\.number \|\| inputs\.pr_number \}\}/);
  assert.match(resolve, /gh api "repos\/\$\{REPO\}\/pulls\/\$\{PR_NUMBER\}"/);
  assert.match(resolve, /"\$\{state\}" != "open"[^\n]*\n[^\n]*exit 1/);
  assert.match(resolve, /"\$\{draft\}" != "false"[^\n]*\n[^\n]*exit 1/);
  assert.match(resolve, /"\$\{head_repo\}" != "\$\{REPO\}"[^\n]*\n[^\n]*exit 1/);
  assert.match(resolve, /head_sha=\$\{head_sha\}/);
  assert.match(resolve, /started_at=/);
  const checkout = stepBlock("Checkout pull request head");
  assert.match(checkout, /actions\/checkout@[0-9a-f]{40}/);
  assert.match(checkout, /ref: \$\{\{ steps\.pr\.outputs\.head_sha \}\}/);
});

test("인증은 CLAUDE_CODE_OAUTH_TOKEN만 쓰고 API 키·커스텀 github_token을 쓰지 않는다", () => {
  const review = stepBlock("Run Claude Code review");
  assert.match(review, /uses: anthropics\/claude-code-action@[0-9a-f]{40} # v1\.0\.\d+\n/, "40자 커밋 SHA 고정 + 버전 주석");
  assert.doesNotMatch(workflow, /claude-code-action@v\d/, "움직이는 tag 참조 금지");
  assert.match(review, /claude_code_oauth_token: \$\{\{ secrets\.CLAUDE_CODE_OAUTH_TOKEN \}\}/);
  assert.doesNotMatch(workflow, /anthropic_api_key|ANTHROPIC_API_KEY/);
  assert.doesNotMatch(review, /github_token:/, "커스텀 토큰은 claude[bot]이 아닌 신원으로 게시하게 만든다");
  assert.deepEqual([...new Set(workflow.match(/secrets\.[A-Z0-9_]+/g))], ["secrets.CLAUDE_CODE_OAUTH_TOKEN"]);
  assert.doesNotMatch(review, /track_progress: *["']?true/, "tag mode 전환 금지(agent mode prompt 유지)");
});

test("공식 /code-review를 high effort로 실행하고 ultra는 쓰지 않는다", () => {
  const review = stepBlock("Run Claude Code review");
  assert.match(review, /prompt: \/code-review high \$\{\{ steps\.pr\.outputs\.number \}\}\n/);
  assert.doesNotMatch(workflow, /ultra/i);
});

test("Claude 도구 권한은 PR 조회와 단일 COMMENT Review 게시로 제한된다", () => {
  const review = stepBlock("Run Claude Code review");
  const allowed = review.match(/--allowedTools "([^"]+)"/)?.[1];
  assert.ok(allowed, "--allowedTools가 필요하다");
  assert.deepEqual(allowed.split(","), [
    "Bash(gh pr view *)",
    "Bash(gh pr diff *)",
    "Edit(/claude-code-review.json)",
    "Bash(gh api repos/${{ github.repository }}/pulls/${{ steps.pr.outputs.number }}/reviews --method POST --input claude-code-review.json)",
  ]);
  assert.match(review, /--append-system-prompt 'EasySubway backend PR discovery 리뷰 규칙 \(Issue #390\)\./);
  assert.match(review, /"event": "COMMENT"/);
  assert.match(review, /"commit_id": "\$\{\{ steps\.pr\.outputs\.head_sha \}\}"/);
  assert.match(review, /🔴 Important/);
  assert.match(review, /🟡 Nit/);
  assert.match(review, /🟣 Pre-existing/);
  assert.match(review, /한국어/);
  assert.match(review, /작성자\(사람, 에이전트, 자동화\)나 변경 크기와 관계없이[^\n]*건너뛰지 않는다/, "PR 작성자·크기 기반 skip 금지");
  for (const priority of [/Fallback 금지/, /서버 공인 라우팅/, /접근성/, /실패하는 테스트/, /continue-on-error/, /documentation-fragment\.json/, /Flyway/, /H2와 PostgreSQL/, /시크릿/, /내부 절대경로/]) {
    assert.match(review, priority);
  }
  assert.doesNotMatch(review, /--approve|--request-changes|Bash\(gh \*\)|Bash\(gh:\*\)|Bash\(\*\)|Bash\(gh api \*\)|Bash\(git push/);
});

test("최소 권한·PR별 concurrency·timeout을 두고 실패를 성공으로 덮지 않는다", () => {
  assert.equal(topLevelBlock("permissions"), "  contents: read\n");
  const jobPermissions = workflow.match(/^ {4}permissions:\n((?: {6}[^\n]*\n)+)/m)?.[1];
  assert.equal(jobPermissions, "      contents: read\n      pull-requests: read\n      id-token: write\n");
  assert.doesNotMatch(workflow, /write-all|contents: write|pull-requests: write|issues: write/);
  assert.match(topLevelBlock("concurrency"), /group: claude-code-review-\$\{\{ github\.event\.pull_request\.number \|\| inputs\.pr_number \}\}\n/);
  const timeout = Number(workflow.match(/timeout-minutes: (\d+)/)?.[1]);
  assert.ok(timeout > 0 && timeout <= 60, `timeout-minutes는 1~60이어야 한다: ${timeout}`);
  assert.doesNotMatch(workflow, /^\s*continue-on-error\s*:/m);
  assert.doesNotMatch(workflow, /\|\| true|\|\| echo|\|\| exit 0/);
});

test("실행 뒤 current head의 claude[bot] COMMENT Review가 정확히 하나인지 검증하고 아니면 실패한다", () => {
  // action 내부 단계가 전부 skipped여도 job이 pass로 끝나는 가짜 통과를 막는 별도 검증 step (easyconvert #238 실측).
  const verify = stepBlock("Verify Claude review object");
  assert.ok(workflow.indexOf("- name: Run Claude Code review") < workflow.indexOf("- name: Verify Claude review object"));
  assert.doesNotMatch(verify, /^ {8}if:/m, "검증 step에는 step-level if를 두지 않는다");
  assert.match(verify, /HEAD_SHA: \$\{\{ steps\.pr\.outputs\.head_sha \}\}/);
  assert.match(verify, /gh api --paginate --slurp "repos\/\$\{REPO\}\/pulls\/\$\{PR_NUMBER\}\/reviews"/);
  assert.match(verify, /\.user\.login == "claude\[bot\]"/);
  assert.match(verify, /\.user\.id == 209825114/);
  assert.match(verify, /\.user\.type == "Bot"/);
  assert.match(verify, /\.commit_id == \$head/);
  assert.match(verify, /if \[ "\$\{verdict\}" != "true" \]; then[\s\S]*?exit 1/);
  const expression = verify.match(/verdict=\$\(jq -r --arg since "\$\{STARTED_AT\}" --arg head "\$\{HEAD_SHA\}" '([\s\S]*?)' <<<"\$\{reviews\}"\)/)?.[1];
  assert.ok(expression, "verify step must keep an inline verdict jq expression");

  const HEAD = "a".repeat(40);
  const SINCE = "2026-09-29T01:00:00Z";
  const CLAUDE = { login: "claude[bot]", id: 209825114, type: "Bot" };
  const reviewAt = (id, submittedAt, overrides = {}) => ({
    id,
    state: "COMMENTED",
    commit_id: HEAD,
    submitted_at: submittedAt,
    author_association: "NONE",
    user: CLAUDE,
    body: "🔴 0 · 🟡 1 · 🟣 0\n요약",
    ...overrides,
  });
  const verdict = (pages) => execFileSync("jq", ["-r", "--arg", "since", SINCE, "--arg", "head", HEAD, expression], {
    input: JSON.stringify(pages),
    encoding: "utf8",
  }).trim();

  assert.equal(verdict([[reviewAt(1, "2026-09-29T01:05:00Z")]]), "true", "이번 실행의 단일 요약 Review");
  assert.equal(verdict([[reviewAt(1, "2026-09-29T00:05:00Z"), reviewAt(2, "2026-09-29T01:05:00Z")]]), "true", "이전 실행 Review는 세지 않는다");
  assert.equal(verdict([]), "false", "Review 없음");
  assert.equal(verdict([[reviewAt(1, "2026-09-29T00:05:00Z")]]), "false", "이번 실행 이전 Review만 있음");
  assert.equal(verdict([[reviewAt(1, "2026-09-29T01:05:00Z"), reviewAt(2, "2026-09-29T01:06:00Z")]]), "false", "Review 중복 게시");
  assert.equal(verdict([[reviewAt(1, "2026-09-29T01:05:00Z", { commit_id: "b".repeat(40) })]]), "false", "다른 head");
  assert.equal(verdict([[reviewAt(1, "2026-09-29T01:05:00Z", { body: "" })]]), "false", "빈 본문(inline wrapper만)");
  assert.equal(verdict([[reviewAt(1, "2026-09-29T01:05:00Z", { state: "APPROVED" })]]), "false", "APPROVE 게시");
  assert.equal(verdict([[reviewAt(1, "2026-09-29T01:05:00Z", { user: { ...CLAUDE, id: 1 } })]]), "false", "위조 신원");
});

test("🔴·🟡 finding은 inline thread로만 인정되고 본문에만 둔 blocking finding은 job을 실패시킨다", () => {
  // 병합 차단은 미해결 inline thread에 의존하므로, 본문 개수보다 inline 코멘트가 적으면 thread gate 우회다 (hub PR #3007 F2).
  const verify = stepBlock("Verify Claude review object");
  assert.match(verify, /gh api --paginate --slurp "repos\/\$\{REPO\}\/pulls\/\$\{PR_NUMBER\}\/reviews\/\$\{review_id\}\/comments"/);
  assert.match(verify, /if \[ "\$\{coverage\}" != "true" \]; then[\s\S]*?exit 1/);
  const expression = verify.match(/coverage=\$\(jq -r --argjson inline "\$\{inline_count\}" '([\s\S]*?)' <<<"\$\{review\}"\)/)?.[1];
  assert.ok(expression, "verify step must keep an inline coverage jq expression");
  const coverage = (body, inline) => execFileSync("jq", ["-r", "--argjson", "inline", String(inline), expression], {
    input: JSON.stringify({ body }),
    encoding: "utf8",
  }).trim();

  assert.equal(coverage("🔴 1 · 🟡 0 · 🟣 0\n요약", 0), "false", "본문에만 둔 Important");
  assert.equal(coverage("🔴 1 · 🟡 0 · 🟣 0\n요약", 1), "true", "Important 1건 inline");
  assert.equal(coverage("🔴 0 · 🟡 2 · 🟣 1\n요약", 1), "false", "Nit 1건 누락");
  assert.equal(coverage("🔴 0 · 🟡 2 · 🟣 1\n요약", 2), "true", "Pre-existing은 본문 허용");
  assert.equal(coverage("🔴 0 · 🟡 0 · 🟣 0\nfinding 없음", 0), "true", "finding 없음");
  assert.equal(coverage("요약만 있고 개수 줄 없음", 3), "false", "개수 줄 누락");
  assert.equal(coverage("", 0), "false", "빈 본문");
});

test("문서 파편 규칙은 fragment resources 목록 기준이고 README·workflow를 직접 지목하지 않는다", () => {
  // 등록 파일은 contracts/documentation/documentation-fragment.json resources가 정본이다.
  // 파일군을 프롬프트에 직접 나열하면 목록이 바뀔 때 오탐·누락 finding이 된다 (hub PR #3007 F1).
  const review = stepBlock("Run Claude Code review");
  assert.match(review, /contracts\/documentation\/documentation-fragment\.json의 resources에 등록된 파일/);
  assert.doesNotMatch(review, /SecurityConfig|README, workflow/);
});

test("#390 계약 테스트 두 파일은 Backend CI의 tools/ci Node 계약 테스트 glob으로 실행된다", () => {
  const ci = readFileSync(new URL("../../.github/workflows/ci.yml", import.meta.url), "utf8");
  const step = ci.match(/\n( {6})- name: Test backend-owned Node contracts\n((?:\1 {2}[^\n]*\n)+)/)?.[2];
  assert.ok(step, "Backend CI의 Test backend-owned Node contracts step이 필요하다");
  assert.doesNotMatch(step, /^ {8}(?:if|continue-on-error):/m, "계약 테스트 step은 조건부·실패 허용이 아니어야 한다");
  const commands = step.split("\n").map((line) => line.trim());
  assert.ok(commands.includes("node --test tools/ci/*.test.mjs"), "tools/ci/*.test.mjs glob이 실행 목록에 있어야 한다");
  for (const file of ["automerge-queue.test.mjs", "claude-code-review-workflow.test.mjs"]) {
    assert.ok(existsSync(new URL(`./${file}`, import.meta.url)), `tools/ci/${file}이 glob 대상 위치에 있어야 한다`);
  }
});
