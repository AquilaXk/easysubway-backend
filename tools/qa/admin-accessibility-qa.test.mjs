import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const source = readFileSync(new URL("./admin-accessibility-qa.mjs", import.meta.url), "utf8");

// #2272 V6-00: inventory 항목을 source에서 파싱한다. 모듈을 import하면 playwright 런타임 의존이
// unit test로 새므로(설치 없이도 실행돼야 함) 기존 text 기반 계약 방식을 유지한다.
function parseSurfaceInventory(exportName) {
  const blockMatch = source.match(new RegExp(`export const ${exportName} = \\[([\\s\\S]*?)\\];`));
  assert.ok(blockMatch, `${exportName} 블록을 찾지 못했다`);
  const entryRegex =
    /\{\s*url:\s*"([^"]+)",\s*name:\s*"([^"]+)",\s*archetype:\s*"([^"]+)",\s*ownerSubIssue:\s*"([^"]+)",\s*permission:\s*"([^"]+)",\s*noJsPath:\s*"([^"]+)"\s*\}/g;
  return [...blockMatch[1].matchAll(entryRegex)].map(
    ([, url, name, archetype, ownerSubIssue, permission, noJsPath]) => ({
      url,
      name,
      archetype,
      ownerSubIssue,
      permission,
      noJsPath,
    }),
  );
}

test("admin accessibility QA script covers Phase 3 required routes and viewports", () => {
  for (const expected of [
    "/admin/dashboard/page",
    "/admin/reports/page",
    "/admin/stations/station-sangnoksu/page",
    "/admin/datapack/pipeline/page",
    "/admin/audits/page",
    "/operator/accessibility-report/page",
  ]) {
    assert.match(source, new RegExp(expected.replaceAll("/", "\\/")));
  }
  for (const expected of ["desktop-1280", "tablet-1024", "mobile-768", "desktop-1440", "mobile-390"]) {
    assert.match(source, new RegExp(expected));
  }
});

test("admin accessibility QA script captures text 200 percent reflow evidence on 1440 and 390", () => {
  assert.match(source, /TEXT_SCALE_FACTOR = 2/);
  assert.match(source, /TEXT_SCALE_VIEWPORTS = \["desktop-1440", "mobile-390"\]/);
  for (const expected of [
    "/admin/dashboard/page",
    "/admin/stations/page",
    "/admin/stations/station-sangnoksu/page",
    "/admin/datapack/pipeline/page",
    "/operator/accessibility-report/page",
  ]) {
    assert.match(source, new RegExp(expected.replaceAll("/", "\\/")));
  }
  assert.match(source, /report\.textScale\.push/);
  assert.match(source, /noHorizontalScroll: doc\.scrollWidth <= doc\.clientWidth/);
  assert.match(source, /clippedContainers/);
  // #1988: pass가 실제로 호출되는지, clipping 집계가 summary에 보존되는지 계약으로 고정한다.
  assert.match(source, /await textScalePass\(page, baseUrl, outputDir, report, ADMIN_TEXT_SCALE_PAGES\)/);
  assert.match(source, /await textScalePass\(operatorPage, baseUrl, outputDir, report, OPERATOR_TEXT_SCALE_PAGES\)/);
  assert.match(source, /textScaleClippedContainers: report\.textScale\.reduce/);
});

test("admin accessibility QA script captures login NONE and RETRY_WARNING public parity", () => {
  assert.match(source, /runLoginStatePass/);
  assert.match(source, /qa-nonexistent-/);
  assert.match(source, /login-\$\{surface\.key\}-none\.png/);
  assert.match(source, /login-\$\{surface\.key\}-retry-warning\.png/);
  assert.match(source, /RETRY_WARNING_COPY/);
  assert.match(source, /report\.loginParity = \{/);
  assert.match(source, /warningCopyParity/);
  // #1988: 실패 로그인은 [role="alert"] 가시화 대기로 판정하고, copy 불일치는 non-blocking 플래그로 기록한다.
  assert.match(source, /waitFor\(\{ state: "visible", timeout: 10000 \}\)/);
  assert.match(source, /retryWarningRendered/);
  assert.match(source, /login parity 계산 불가/);
  // #1988: pass가 실제로 호출되고, 양쪽 동일 실패가 parity를 green으로 통과시키지 않도록
  // 각 surface 기대 상태 충족을 parity에 포함하는 계약을 고정한다.
  assert.match(source, /await runLoginStatePass\(browser, baseUrl, outputDir, report\)/);
  assert.match(source, /function loginSurfaceMeetsExpectedState\(entry\)/);
  assert.match(source, /entry\.noneStatus >= 200/);
  assert.match(source, /entry\.noneAlerts === 0/);
  assert.match(source, /entry\.alertVisible === true/);
  assert.match(source, /entry\.retryWarningRendered === true/);
  assert.match(source, /const adminExpectedStateOk = loginSurfaceMeetsExpectedState\(admin\)/);
  assert.match(source, /const operatorExpectedStateOk = loginSurfaceMeetsExpectedState\(operator\)/);
  assert.match(source, /&& adminExpectedStateOk\s*\n\s*&& operatorExpectedStateOk/);
});

// #2277 V6-05: workspace disclosure가 현재 위치를 담은 영역만 기본 펼침하는지 keyboard smoke가 검사한다.
test("admin accessibility QA script verifies workspace disclosure defaults to current only", () => {
  assert.match(source, /check: "nav-workspace-disclosure"/);
  assert.match(source, /\.admin-nav-workspace-toggle\[aria-expanded="false"\]/);
  assert.match(source, /workspaceDisclosure\.expanded === 1/);
  assert.match(source, /workspaceDisclosure\.collapsed === workspaceDisclosure\.total - 1/);
  assert.match(source, /workspace disclosure did not default to only the current workspace expanded/);
});

// #2277 리뷰: 현재 위치가 없는 페이지가 전 영역 펼침으로 폴백하는지 QA 하네스가 실제로 검증하는 계약.
test("admin accessibility QA script verifies no-current page falls back to all workspaces expanded", () => {
  assert.match(source, /check: "nav-workspace-no-current-disclosure"/);
  assert.match(source, /\/admin\/search/);
  assert.match(source, /is-no-current/);
  assert.match(source, /disclosure\.expanded === disclosure\.total/);
  assert.match(source, /disclosure\.visiblePrograms === disclosure\.total/);
  assert.match(source, /no-current page did not fall back to all workspaces expanded/);
  assert.match(source, /await noCurrentWorkspaceDisclosure\(page, baseUrl, report\)/);
});

test("admin accessibility QA script verifies admin-table-scroll keyboard and focus outline", () => {
  assert.match(source, /keyboardTableCheck/);
  assert.match(source, /admin-table-scroll/);
  assert.match(source, /scrolledRight/);
  assert.match(source, /scrolledBackLeft/);
  assert.match(source, /outlineVisible/);
  assert.match(source, /ArrowRight/);
  assert.match(source, /ArrowLeft/);
  // #1988: pass가 실제로 호출되고, 검사 실패가 blocking 위반으로 표면화되는 계약을 고정한다.
  assert.match(source, /await keyboardTableCheck\(page, baseUrl, report\)/);
  assert.match(source, /entry\.check === "admin-table-scroll-keyboard"/);
  assert.match(source, /id: "admin-table-scroll-keyboard"/);
  assert.match(source, /id: "login-public-state-expectation"/);
});

// #2278 V6-06: 목록 툴바 시트 계약을 QA 하네스가 검사하는지 source로 고정한다.
test("admin accessibility QA script verifies list toolbar sheet body overflow and focus restore", () => {
  assert.match(source, /async function listToolbarSheetCheck\(page, baseUrl, report\)/);
  assert.match(source, /await listToolbarSheetCheck\(page, baseUrl, report\)/);
  assert.match(source, /check: "list-toolbar-sheet"/);
  // body가 가로 overflow를 소유하지 않는지(§9) 측정한다.
  assert.match(source, /bodyOverflowX0/);
  assert.match(source, /document\.body\.scrollWidth <= document\.body\.clientWidth \+ 1/);
  // 시트 outside close 입력 미유실·Esc 시트 실제 닫힘(is-open 제거)·포커스 복원을 이중 단언으로 검사한다.
  assert.match(source, /inputPreserved/);
  assert.match(source, /sheetClosed/);
  assert.match(source, /focusRestored/);
  assert.match(source, /admin-toolbar-filter-trigger/);
  // direct control 수를 시트 밖 요소만 세어 기록한다.
  assert.match(source, /directControls/);
  // 계약 실패가 blocking 위반으로 표면화되는지 고정한다.
  assert.match(source, /id: "list-toolbar-sheet"/);
  assert.match(source, /listToolbar\.bodyOverflowX0 === false/);
  assert.match(source, /listToolbar\.sheetClosed === false/);
});

// V6-07 #2279: 마스터 목록 상태 신호(sticky 식별자·비색 상태 아이콘+텍스트·헤더 scope) 계약을 source로 고정한다.
test("admin accessibility QA script verifies master-list sticky identifier and non-color status signal", () => {
  assert.match(source, /async function masterListStatusSignalCheck\(page, baseUrl, report\)/);
  assert.match(source, /await masterListStatusSignalCheck\(page, baseUrl, report\)/);
  assert.match(source, /check: "master-list-status-signal"/);
  // 첫 식별자 열 sticky 고정을 실제 computed style로 판정한다.
  assert.match(source, /getComputedStyle\(firstCell\)\.position === "sticky"/);
  // 상태가 색 단독이 아니라 아이콘(비색 신호)+텍스트를 함께 갖는지 검사한다.
  assert.match(source, /cell\.querySelector\("svg\.admin-icon"\)/);
  assert.match(source, /statusHasIcon/);
  assert.match(source, /statusHasText/);
  // 표 헤더 scope 연결을 센다.
  assert.match(source, /thead th\[scope=/);
  // 계약 실패가 blocking 위반으로 표면화되는지 고정한다.
  assert.match(source, /id: "master-list-status-signal"/);
  assert.match(source, /statusSignal\.stickyIdentifier === false/);
  assert.match(source, /statusSignal\.statusHasIcon === false/);
});

// V6-08 #2280: 신고 대기열 action·photo 경계(승인 primary·반려 danger·사진 fail closed) 계약을 source로 고정한다.
test("admin accessibility QA script verifies report queue action hierarchy and photo fail-closed", () => {
  assert.match(source, /async function reportQueueActionSignalCheck\(page, baseUrl, report\)/);
  assert.match(source, /await reportQueueActionSignalCheck\(page, baseUrl, report\)/);
  assert.match(source, /check: "report-queue-action-signal"/);
  // 일괄 승인 primary·반려 danger 위계를 실제 DOM class로 판정한다.
  assert.match(source, /button\[name=\\"decision\\"\]\[value=\\"ACCEPT\\"\]/);
  assert.match(source, /button\[name=\\"decision\\"\]\[value=\\"REJECT\\"\]/);
  assert.match(source, /classList\.contains\("primary"\)/);
  assert.match(source, /classList\.contains\("danger"\)/);
  // 사진 fail closed: raw object key 미노출(속성값 leak 포함 innerHTML 스캔) + 썸네일은 permission-gated
  // 원본 endpoint만 참조한다.
  assert.match(source, /document\.body\.innerHTML \|\| ""\)\.includes\("facility-reports\/"\)/);
  assert.match(source, /noRawPhotoKey/);
  assert.match(source, /photoScoped/);
  // 썸네일 0개 무검증 PASS 위장을 방지하는 photoScopedVerified 신호를 고정한다.
  assert.match(source, /const photoScopedVerified = thumbs\.length > 0;/);
  assert.match(source, /photoScopedVerified\s*\n\s*&&\s*thumbs\.every/);
  // 계약 실패가 blocking 위반으로 표면화되는지 고정한다.
  assert.match(source, /id: "report-queue-action-signal"/);
  assert.match(source, /actionSignal\.approvePrimary === false/);
  assert.match(source, /actionSignal\.rejectDanger === false/);
  assert.match(source, /actionSignal\.photoScoped === false/);
  assert.match(source, /actionSignal\.photoScopedVerified === false/);
});

test("admin accessibility QA script records manual-only screen reader and contrast work", () => {
  assert.match(source, /VoiceOver reading flow/);
  assert.match(source, /high-contrast visual inspection/);
  assert.match(source, /200 percent browser zoom visual inspection/);
});

test("admin accessibility QA script fails on serious and critical axe violations", () => {
  assert.match(source, /criticalAxeViolations/);
  assert.match(source, /seriousAxeViolations/);
  assert.match(source, /impact === "critical"/);
  assert.match(source, /impact === "serious"/);
  assert.match(source, /throw new Error\(`blocking axe violations/);
});

// #2272 V6-00: 화면 inventory·permission·no-JS·archetype·owner sub-issue 고정.
test("admin/operator QA surface inventory pins archetype/owner/permission/no-JS with 0 missing fields", () => {
  const REQUIRED_FIELDS = ["url", "name", "archetype", "ownerSubIssue", "permission", "noJsPath"];
  const OWNER_SUB_ISSUES = new Set(["V6-07", "V6-08", "V6-09", "V6-10"]);
  const adminSurfaces = parseSurfaceInventory("ADMIN_SURFACE_INVENTORY");
  const operatorSurfaces = parseSurfaceInventory("OPERATOR_SURFACE_INVENTORY");
  const surfaces = [...adminSurfaces, ...operatorSurfaces];

  const missing = [];
  for (const surface of surfaces) {
    for (const field of REQUIRED_FIELDS) {
      if (typeof surface[field] !== "string" || surface[field].length === 0) {
        missing.push(`${surface.url ?? "?"}:${field}`);
      }
    }
    if (!OWNER_SUB_ISSUES.has(surface.ownerSubIssue)) {
      missing.push(`${surface.url}:ownerSubIssue=${surface.ownerSubIssue}`);
    }
    // no-JS pass가 실제 방문하는 경로는 surface url과 같아야 한다(placeholder 경로 금지).
    if (surface.noJsPath !== surface.url) {
      missing.push(`${surface.url}:noJsPath!=url`);
    }
  }
  // owner·permission·archetype·no-JS path 중 하나라도 없으면 V6-01 착수를 차단한다(#2272 §8).
  assert.deepEqual(missing, [], `surface inventory missing fields: ${missing.join(", ")}`);

  assert.equal(adminSurfaces.length, 13);
  assert.equal(operatorSurfaces.length, 5);

  // 파생된 ADMIN_PAGES/OPERATOR_PAGES가 inventory url/name에서 나오는지 source로 고정한다.
  assert.match(source, /export const ADMIN_PAGES = ADMIN_SURFACE_INVENTORY\.map\(\(surface\) => \[surface\.url, surface\.name\]\);/);
  assert.match(source, /export const OPERATOR_PAGES = OPERATOR_SURFACE_INVENTORY\.map\(\(surface\) => \[surface\.url, surface\.name\]\);/);

  // 모든 operator surface owner는 V6-10(나머지 admin·operator·auth/feedback 이관)이다.
  for (const surface of operatorSurfaces) {
    assert.equal(surface.ownerSubIssue, "V6-10", `${surface.url} operator owner must be V6-10`);
    assert.equal(surface.permission, "ROLE_OPERATOR_ADMIN");
  }
});

test("admin accessibility QA script fails non-success page responses", () => {
  assert.match(source, /response\.status\(\)/);
  assert.match(source, /status < 200 \|\| status >= 300/);
  assert.match(source, /returned HTTP \$\{status\}/);
  assert.match(source, /did not return a page response/);
});
