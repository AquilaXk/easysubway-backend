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
