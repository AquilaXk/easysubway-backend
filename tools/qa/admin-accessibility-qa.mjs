#!/usr/bin/env node
import AxeBuilder from "@axe-core/playwright";
import { chromium } from "playwright-core";
import { mkdir, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";

export const VIEWPORTS = [
  { name: "desktop-1280", width: 1280, height: 900 },
  { name: "tablet-1024", width: 1024, height: 900 },
  { name: "mobile-768", width: 768, height: 900 },
  { name: "desktop-1440", width: 1440, height: 900 },
  { name: "mobile-390", width: 390, height: 844 },
  // #2283 V6-11: 최소 지원 폭. WCAG 1.4.10 reflow(1280px @ 400% = 320 CSS px)의 목표 폭이자
  // 좁은 화면 회귀를 압박하는 명시 viewport. 기존 index 참조(VIEWPORTS[0..2])를 보존하려 끝에 덧붙인다.
  { name: "mobile-320", width: 320, height: 640 },
];

// #2272 V6-00: QA surface inventory. 각 admin/operator surface에 archetype, owner sub-issue
// (V6-07~V6-10), permission, no-JS path를 데이터로 고정한다. 항목이 하나라도 누락되면
// admin-accessibility-qa.test.mjs의 "0 missing" 계약과 repository-contract.test.mjs의 catalog
// 대응 계약이 실패한다. route·permission·no-JS 계약의 정본은 AdminProgram.visibleTo()와 API
// catalog GET page route이며 이 표는 그것을 인용할 뿐 변경하지 않는다. noJsPath는 no-JS pass가
// 실제로 방문하는 경로(= url)를 고정한다.
export const ADMIN_SURFACE_INVENTORY = [
  { url: "/admin/dashboard/page", name: "dashboard", archetype: "overview-dashboard", ownerSubIssue: "V6-09", permission: "ADMIN_VIEW", noJsPath: "/admin/dashboard/page" },
  { url: "/admin/reports/page", name: "reports", archetype: "queue-review", ownerSubIssue: "V6-08", permission: "REPORT_REVIEW", noJsPath: "/admin/reports/page" },
  { url: "/admin/stations/page", name: "stations", archetype: "master-list", ownerSubIssue: "V6-07", permission: "ADMIN_VIEW", noJsPath: "/admin/stations/page" },
  { url: "/admin/stations/station-sangnoksu/page", name: "station-hub", archetype: "detail-hub", ownerSubIssue: "V6-07", permission: "ADMIN_VIEW", noJsPath: "/admin/stations/station-sangnoksu/page" },
  { url: "/admin/facilities/page", name: "facilities", archetype: "master-list", ownerSubIssue: "V6-07", permission: "ADMIN_VIEW", noJsPath: "/admin/facilities/page" },
  { url: "/admin/data-collections/page", name: "collections", archetype: "operations-list", ownerSubIssue: "V6-10", permission: "DATA_OPERATE", noJsPath: "/admin/data-collections/page" },
  { url: "/admin/batches/page", name: "batches", archetype: "operations-list", ownerSubIssue: "V6-10", permission: "DATA_OPERATE", noJsPath: "/admin/batches/page" },
  { url: "/admin/incidents/page", name: "incidents", archetype: "operations-list", ownerSubIssue: "V6-10", permission: "OPERATIONS_MANAGE", noJsPath: "/admin/incidents/page" },
  { url: "/admin/routes/searches/page", name: "route-searches", archetype: "analytics-list", ownerSubIssue: "V6-10", permission: "ADMIN_VIEW", noJsPath: "/admin/routes/searches/page" },
  { url: "/admin/routes/feedback/page", name: "route-feedback", archetype: "analytics-list", ownerSubIssue: "V6-10", permission: "ADMIN_VIEW", noJsPath: "/admin/routes/feedback/page" },
  { url: "/admin/datapack/pipeline/page", name: "datapack-pipeline", archetype: "datapack-pipeline", ownerSubIssue: "V6-09", permission: "DATAPACK_READ", noJsPath: "/admin/datapack/pipeline/page" },
  { url: "/admin/audits/page", name: "audits", archetype: "audit-log", ownerSubIssue: "V6-10", permission: "AUDIT_READ", noJsPath: "/admin/audits/page" },
  { url: "/admin/audits/privacy/page", name: "privacy-audits", archetype: "audit-log", ownerSubIssue: "V6-10", permission: "PRIVACY_LOG_READ", noJsPath: "/admin/audits/privacy/page" },
];

export const OPERATOR_SURFACE_INVENTORY = [
  { url: "/operator/accessibility-report/page", name: "operator-accessibility", archetype: "operator-report", ownerSubIssue: "V6-10", permission: "ROLE_OPERATOR_ADMIN", noJsPath: "/operator/accessibility-report/page" },
  { url: "/operator/repeated-broken-facilities/page", name: "operator-repeated-broken", archetype: "operator-report", ownerSubIssue: "V6-10", permission: "ROLE_OPERATOR_ADMIN", noJsPath: "/operator/repeated-broken-facilities/page" },
  { url: "/operator/data-collection-failures/page", name: "operator-collection-failures", archetype: "operator-report", ownerSubIssue: "V6-10", permission: "ROLE_OPERATOR_ADMIN", noJsPath: "/operator/data-collection-failures/page" },
  { url: "/operator/route-feedback-report/page", name: "operator-route-feedback", archetype: "operator-report", ownerSubIssue: "V6-10", permission: "ROLE_OPERATOR_ADMIN", noJsPath: "/operator/route-feedback-report/page" },
  { url: "/operator/push-notification-report/page", name: "operator-push", archetype: "operator-report", ownerSubIssue: "V6-10", permission: "ROLE_OPERATOR_ADMIN", noJsPath: "/operator/push-notification-report/page" },
];

export const ADMIN_PAGES = ADMIN_SURFACE_INVENTORY.map((surface) => [surface.url, surface.name]);

export const OPERATOR_PAGES = OPERATOR_SURFACE_INVENTORY.map((surface) => [surface.url, surface.name]);

// #1988: text 200% reflow/clipping evidence는 대표 5화면에서만 수집한다.
export const TEXT_SCALE_FACTOR = 2;
export const TEXT_SCALE_VIEWPORTS = ["desktop-1440", "mobile-390"];
export const ADMIN_TEXT_SCALE_PAGES = [
  ["/admin/dashboard/page", "dashboard"],
  ["/admin/stations/page", "stations"],
  ["/admin/stations/station-sangnoksu/page", "station-hub"],
  ["/admin/datapack/pipeline/page", "datapack-pipeline"],
];
export const OPERATOR_TEXT_SCALE_PAGES = [
  ["/operator/accessibility-report/page", "operator-accessibility"],
];

// #2283 V6-11: WCAG 1.4.10 reflow. 1280px 화면을 400% 확대하면 유효 폭이 320 CSS px가 되므로
// 320px viewport를 reflow의 목표 폭으로 삼아 대표 화면이 2차원(가로) 스크롤 없이 재배치되는지 검사한다.
// (문서 스크롤 폭 = 클라이언트 폭 계약. 가로 overflow는 wrapper .admin-table-scroll만 소유한다 — #2071.)
export const REFLOW_VIEWPORT = "mobile-320";
export const REFLOW_PAGES = [
  ["/admin/dashboard/page", "dashboard"],
  ["/admin/stations/page", "stations"],
  ["/admin/reports/page", "reports"],
  ["/admin/datapack/pipeline/page", "datapack-pipeline"],
];

// #1988: admin·operator 로그인 공개 상태 parity 캡처 대상.
export const LOGIN_SURFACES = [
  { key: "admin", loginPath: "/admin/login" },
  { key: "operator", loginPath: "/operator/login" },
];
export const RETRY_WARNING_COPY = "아이디 또는 비밀번호를 확인하고 다시 시도해 주세요.";

export const MANUAL_REQUIRED = [
  "VoiceOver reading flow for dashboard, reports, station hub, datapack pipeline, and audits",
  "OS/browser high-contrast visual inspection",
  "200 percent browser zoom visual inspection",
];

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const baseUrl = (options.baseUrl ?? process.env.ADMIN_QA_BASE_URL ?? "http://localhost:8080").replace(/\/$/, "");
  const outputDir = path.resolve(options.output ?? process.env.ADMIN_QA_OUTPUT_DIR ?? "artifacts/admin-qa");
  const adminUser = requiredEnv("ADMIN_QA_ADMIN_USER");
  const adminPassword = requiredEnv("ADMIN_QA_ADMIN_PASSWORD");
  const operatorUser = requiredEnv("ADMIN_QA_OPERATOR_USER");
  const operatorPassword = requiredEnv("ADMIN_QA_OPERATOR_PASSWORD");
  const browser = await chromium.launch({
    executablePath: chromePath(),
    headless: process.env.ADMIN_QA_HEADLESS !== "false",
    // #2283 V6-11: CI ubuntu 러너의 시스템 Chrome은 sandbox 없이 실행해야 launch가 성립한다.
    // route map 테스트의 ROUTE_MAP_CHROME_NO_SANDBOX 관례와 동일하게 env로만 켜고 로컬 기본값은 sandbox 유지.
    args: process.env.ADMIN_QA_CHROME_NO_SANDBOX === "1" ? ["--no-sandbox"] : [],
  });
  const report = {
    baseUrl,
    generatedAt: new Date().toISOString(),
    viewports: VIEWPORTS,
    adminPages: ADMIN_PAGES.map(([url, name]) => ({ url, name })),
    operatorPages: OPERATOR_PAGES.map(([url, name]) => ({ url, name })),
    manualRequired: MANUAL_REQUIRED,
    axe: [],
    screenshots: [],
    keyboard: [],
    noJs: [],
    charts: [],
    axTree: [],
    textScale: [],
    reflow: [],
    loginStates: [],
    loginParity: null,
  };

  const reportPath = path.join(outputDir, "admin-accessibility-qa-report.json");
  await mkdir(outputDir, { recursive: true });
  // #1988: 어느 pass가 예외를 던져도 수집분을 보존하도록 report 쓰기를 finally로 감싼다.
  // 예외 자체는 finally에서 삼키지 않고 그대로 전파돼 exit code로 실패를 표면화한다.
  try {
    try {
      await runJsPass(browser, baseUrl, outputDir, adminUser, adminPassword, operatorUser, operatorPassword, report);
      await runLoginStatePass(browser, baseUrl, outputDir, report);
      await runNoJsPass(browser, baseUrl, outputDir, adminUser, adminPassword, operatorUser, operatorPassword, report);
    } finally {
      await browser.close();
    }
  } finally {
    finalizeReport(report);
    await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`);
  }
  if (report.blockingViolations.length > 0) {
    throw new Error(`blocking axe violations: ${JSON.stringify(report.blockingViolations)}`);
  }
  console.log(`admin accessibility QA ok: ${reportPath}`);
}

function finalizeReport(report) {
  const blockingViolations = report.axe.flatMap((entry) =>
    entry.violations
      .filter((violation) => violation.impact === "critical" || violation.impact === "serious")
      .map((violation) => ({
        page: entry.page,
        id: violation.id,
        impact: violation.impact,
        nodes: violation.nodes.length,
      })),
  );
  // #1988: table keyboard 검사가 전부 false여도 evidence만 남기고 실행은 성공하던 허위 green을 막는다.
  // axe blocking과 같은 패턴으로 위반 항목에 편입해 exit code로 실패를 표면화한다(report는 이미 기록됨).
  const tableKeyboard = report.keyboard.find((entry) => entry.check === "admin-table-scroll-keyboard");
  if (tableKeyboard
    && !(tableKeyboard.tabFocusable
      && tableKeyboard.scrolledRight
      && tableKeyboard.scrolledBackLeft
      && tableKeyboard.outlineVisible)) {
    // #2349: nodes:0만 남기면 4개 불리언 중 무엇이 false였는지 로그에 안 남아 flake 진단이 불가능했다.
    // 실패한 불리언과 실측 scrollLeft 값을 위반 객체에 실어 다음 간헐 실패 때 원인이 로그로 남게 한다.
    blockingViolations.push({
      page: "/admin/stations/page",
      id: "admin-table-scroll-keyboard",
      impact: "serious",
      nodes: 0,
      tabFocusable: tableKeyboard.tabFocusable,
      scrolledRight: tableKeyboard.scrolledRight,
      scrolledBackLeft: tableKeyboard.scrolledBackLeft,
      outlineVisible: tableKeyboard.outlineVisible,
      maxScrollLeft: tableKeyboard.maxScrollLeft,
      startScrollLeft: tableKeyboard.startScrollLeft,
      afterRight: tableKeyboard.afterRight,
      afterLeft: tableKeyboard.afterLeft,
    });
  }
  // #1988: 로그인 공개 상태 기대치 미충족(허위 parity의 근원)도 위반으로 편입한다.
  if (report.loginParity
    && (report.loginParity.adminExpectedStateOk === false
      || report.loginParity.operatorExpectedStateOk === false)) {
    blockingViolations.push({
      page: "login-parity",
      id: "login-public-state-expectation",
      impact: "serious",
      nodes: 0,
    });
  }
  // #2278 V6-06: 목록 툴바 시트 계약을 위반으로 편입한다. body가 가로 overflow를 소유하거나(§9),
  // 툴바가 있는데 outside close가 입력을 버리거나, Esc(window 스코프, #2301 리뷰)가 시트를 실제로
  // 닫지 못하거나(is-open 잔존) 포커스를 복원하지 못하면 실패를 표면화한다.
  const listToolbar = report.keyboard.find((entry) => entry.check === "list-toolbar-sheet");
  if (listToolbar
    && (listToolbar.bodyOverflowX0 === false
      || (listToolbar.present === true
        && listToolbar.sheetPresent === true
        && (listToolbar.inputPreserved === false
          || listToolbar.sheetClosed === false
          || listToolbar.focusRestored === false)))) {
    blockingViolations.push({
      page: "/admin/reports/page",
      id: "list-toolbar-sheet",
      impact: "serious",
      nodes: 0,
    });
  }
  // V6-07 #2279 / #2313: 마스터 목록 상태 신호 계약을 위반으로 편입한다. 첫 식별자 열이 sticky가 아니거나,
  // 상태 셀(.admin-status)에 비색 신호인 상태 텍스트가 병기되지 않거나(색 단독 ● 점만 존재), 헤더 scope
  // 연결이 없으면 실패를 표면화한다.
  const statusSignal = report.keyboard.find((entry) => entry.check === "master-list-status-signal");
  if (statusSignal
    && (statusSignal.stickyIdentifier === false
      || statusSignal.statusHasText === false
      || statusSignal.scopedHeaders === 0)) {
    blockingViolations.push({
      page: "/admin/stations/page",
      id: "master-list-status-signal",
      impact: "serious",
      nodes: 0,
    });
  }
  // V6-08 #2280: 신고 대기열 action·photo 경계 계약을 위반으로 편입한다. 일괄 승인이 primary가 아니거나,
  // 반려가 danger가 아니거나, 사진 셀이 raw object key를 노출하거나 썸네일이 permission-gated endpoint를
  // 벗어나거나, 사진 셀 검증 자체가 수행되지 않았으면(썸네일 0개, 무검증 PASS 위장 방지) 실패를 표면화한다
  // (§7 action 체계, §9 photo matrix).
  const actionSignal = report.keyboard.find((entry) => entry.check === "report-queue-action-signal");
  if (actionSignal
    && (actionSignal.bulkbarPresent === false
      || actionSignal.approvePrimary === false
      || actionSignal.rejectDanger === false
      || actionSignal.noRawPhotoKey === false
      || actionSignal.photoScoped === false
      || actionSignal.photoScopedVerified === false)) {
    blockingViolations.push({
      page: "/admin/reports/page",
      id: "report-queue-action-signal",
      impact: "serious",
      nodes: 0,
    });
  }
  // #2281 V6-09: 대시보드 KPI 상태 계층 계약을 위반으로 편입한다. headline 카드가 3개를 넘거나,
  // 기간 표기 caption이 없거나, 총 카드가 3개 초과인데 나머지를 담는 native details가 없거나 DETAILS가
  // 아니면(keyboard·no-JS 접근 불가) 실패를 표면화한다.
  // #2306 리뷰: (1) check entry 자체가 없으면(pass가 돌지 않았거나 신호 미기록) false-green이므로
  // 위반으로 편입한다. (2) 누계 총량 카드가 headline에 있거나(index 기반 격하 회귀), headline 카드가
  // 지표 정체성(data-metric-key)을 노출하지 않으면(정체성 검증 불가) 위반으로 편입한다.
  const kpiHierarchy = report.keyboard.find((entry) => entry.check === "dashboard-kpi-hierarchy");
  if (!kpiHierarchy) {
    blockingViolations.push({
      page: "/admin/dashboard/page",
      id: "dashboard-kpi-hierarchy-missing",
      impact: "serious",
      nodes: 0,
    });
  } else if (kpiHierarchy.panelPresent === false
      || kpiHierarchy.headlineCards > 3
      || kpiHierarchy.captionPresent === false
      || kpiHierarchy.headlineMetricKeysPresent === false
      || kpiHierarchy.cumulativeInHeadline === true
      || (kpiHierarchy.totalCards > 3
        && (kpiHierarchy.disclosureCards === 0 || kpiHierarchy.disclosureIsDetails !== true))) {
    blockingViolations.push({
      page: "/admin/dashboard/page",
      id: "dashboard-kpi-hierarchy",
      impact: "serious",
      nodes: 0,
    });
  }
  // #2283 V6-11: 400% reflow(320px 목표 폭)에서 문서 수준 가로 스크롤이 생긴 대표 화면을 위반으로 편입한다.
  for (const entry of report.reflow) {
    if (!entry.noHorizontalScroll) {
      blockingViolations.push({
        page: entry.url,
        id: "reflow-400-horizontal-scroll",
        impact: "serious",
        nodes: 0,
      });
    }
  }
  const criticalViolations = blockingViolations.filter((violation) => violation.impact === "critical");
  const seriousViolations = blockingViolations.filter((violation) => violation.impact === "serious");
  report.summary = {
    criticalAxeViolations: criticalViolations.length,
    seriousAxeViolations: seriousViolations.length,
    screenshots: report.screenshots.length,
    noJsPages: report.noJs.length,
    chartChecks: report.charts.length,
    keyboardChecks: report.keyboard.length,
    textScaleChecks: report.textScale.length,
    textScaleReflowFailures: report.textScale.filter((entry) => !entry.noHorizontalScroll).length,
    // #1988: 수평 스크롤만 세면 overflow:hidden/clip으로 잘린 컨테이너 증거가 사라진다.
    // 각 entry의 clippedContainers를 합산해 clipping 규모를 summary에 보존한다.
    textScaleClippedContainers: report.textScale.reduce((sum, entry) => sum + (entry.clippedContainers || 0), 0),
    reflowChecks: report.reflow.length,
    reflowHorizontalScrollFailures: report.reflow.filter((entry) => !entry.noHorizontalScroll).length,
    loginStateCaptures: report.loginStates.length,
    loginParityOk: report.loginParity ? report.loginParity.parity : null,
  };
  report.blockingViolations = blockingViolations;
}

async function runJsPass(browser, baseUrl, outputDir, adminUser, adminPassword, operatorUser, operatorPassword, report) {
  const context = await browser.newContext({ viewport: VIEWPORTS[0] });
  const page = await context.newPage();
  await login(page, baseUrl, "/admin/login", adminUser, adminPassword);
  for (const viewport of VIEWPORTS) {
    await page.setViewportSize(viewport);
    for (const [url, name] of ADMIN_PAGES) {
      await auditPage(page, baseUrl, outputDir, url, `${name}-${viewport.name}`, report);
    }
  }
  await keyboardSmoke(page, baseUrl, report);
  await dashboardKpiHierarchyCheck(page, baseUrl, report);
  await captureAxTree(page, outputDir, report);
  await noCurrentWorkspaceDisclosure(page, baseUrl, report);
  await keyboardTableCheck(page, baseUrl, report);
  await masterListStatusSignalCheck(page, baseUrl, report);
  await reportQueueActionSignalCheck(page, baseUrl, report);
  await listToolbarSheetCheck(page, baseUrl, report);
  await textScalePass(page, baseUrl, outputDir, report, ADMIN_TEXT_SCALE_PAGES);
  await reflowPass(page, baseUrl, outputDir, report, REFLOW_PAGES);
  await context.close();

  const operatorContext = await browser.newContext({ viewport: VIEWPORTS[0] });
  const operatorPage = await operatorContext.newPage();
  await login(operatorPage, baseUrl, "/operator/login", operatorUser, operatorPassword);
  for (const viewport of [VIEWPORTS[0], VIEWPORTS[2]]) {
    await operatorPage.setViewportSize(viewport);
    for (const [url, name] of OPERATOR_PAGES) {
      await auditPage(operatorPage, baseUrl, outputDir, url, `${name}-${viewport.name}`, report);
    }
  }
  await textScalePass(operatorPage, baseUrl, outputDir, report, OPERATOR_TEXT_SCALE_PAGES);
  await operatorContext.close();
}

async function runNoJsPass(browser, baseUrl, outputDir, adminUser, adminPassword, operatorUser, operatorPassword, report) {
  const context = await browser.newContext({ javaScriptEnabled: false, viewport: VIEWPORTS[0] });
  const page = await context.newPage();
  await login(page, baseUrl, "/admin/login", adminUser, adminPassword);
  for (const [url, name] of ADMIN_PAGES) {
    const response = await page.goto(`${baseUrl}${url}`, { waitUntil: "domcontentloaded" });
    await assertOk(page, url, response);
    const screenshot = path.join(outputDir, `no-js-${name}.png`);
    await page.screenshot({ path: screenshot, fullPage: true });
    report.noJs.push({ url, screenshot });
  }
  await context.close();

  const operatorContext = await browser.newContext({ javaScriptEnabled: false, viewport: VIEWPORTS[0] });
  const operatorPage = await operatorContext.newPage();
  await login(operatorPage, baseUrl, "/operator/login", operatorUser, operatorPassword);
  for (const [url, name] of OPERATOR_PAGES) {
    const response = await operatorPage.goto(`${baseUrl}${url}`, { waitUntil: "domcontentloaded" });
    await assertOk(operatorPage, url, response);
    const screenshot = path.join(outputDir, `no-js-${name}.png`);
    await operatorPage.screenshot({ path: screenshot, fullPage: true });
    report.noJs.push({ url, screenshot });
  }
  await operatorContext.close();
}

async function auditPage(page, baseUrl, outputDir, url, name, report) {
  const response = await page.goto(`${baseUrl}${url}`, { waitUntil: "networkidle" });
  await assertOk(page, url, response);
  const screenshot = path.join(outputDir, `${name}.png`);
  await page.screenshot({ path: screenshot, fullPage: true });
  report.screenshots.push({ url, screenshot });
  const axe = await new AxeBuilder({ page }).analyze();
  report.axe.push({ page: url, violations: axe.violations });
  const chartChecks = await page.evaluate(() => Array.from(document.querySelectorAll("canvas")).map((canvas) => {
    var tableId = canvas.getAttribute("data-operator-chart-table");
    var fallbackTable = tableId
      ? Boolean(document.getElementById(tableId))
      : Boolean(canvas.closest("section") && canvas.closest("section").querySelector("table"));
    return {
      id: canvas.id || canvas.getAttribute("aria-label") || "canvas",
      role: canvas.getAttribute("role"),
      ariaLabel: canvas.getAttribute("aria-label"),
      fallbackTable,
    };
  }));
  for (const check of chartChecks) {
    if (check.role !== "img" || !check.ariaLabel || !check.fallbackTable) {
      throw new Error(`${url} chart accessibility failure: ${JSON.stringify(check)}`);
    }
    report.charts.push({ url, ...check });
  }
}

// #1988: text-only 200%.
// admin-v3 CSS는 전부 px 기반 font-size라 CDP Page.setFontSizes(기본 폰트 크기 최소값)로는
// 명시적 px 텍스트가 스케일되지 않는다. 동등한 text-only 방식으로 모든 요소의 computed
// font-size를 factor배로 인라인 override해(레이아웃 box는 그대로 유지) reflow/clipping을 압박한다.
async function textScalePass(page, baseUrl, outputDir, report, pages) {
  const viewports = TEXT_SCALE_VIEWPORTS.map((name) => VIEWPORTS.find((viewport) => viewport.name === name));
  for (const viewport of viewports) {
    await page.setViewportSize(viewport);
    for (const [url, name] of pages) {
      const response = await page.goto(`${baseUrl}${url}`, { waitUntil: "networkidle" });
      await assertOk(page, url, response);
      const metrics = await page.evaluate((factor) => {
        const elements = Array.from(document.querySelectorAll("*"));
        const originals = elements.map((element) => parseFloat(getComputedStyle(element).fontSize) || 0);
        elements.forEach((element, index) => {
          if (originals[index] > 0) {
            element.style.fontSize = `${originals[index] * factor}px`;
          }
        });
        const doc = document.documentElement;
        const clippedContainers = elements.filter((element) => {
          const style = getComputedStyle(element);
          const hiddenX = style.overflowX === "hidden" || style.overflowX === "clip";
          const hiddenY = style.overflowY === "hidden" || style.overflowY === "clip";
          const overflowsX = hiddenX && element.scrollWidth > element.clientWidth + 1;
          const overflowsY = hiddenY && element.scrollHeight > element.clientHeight + 1;
          return overflowsX || overflowsY;
        }).length;
        return {
          scrollWidth: doc.scrollWidth,
          clientWidth: doc.clientWidth,
          noHorizontalScroll: doc.scrollWidth <= doc.clientWidth + 1,
          clippedContainers,
        };
      }, TEXT_SCALE_FACTOR);
      const screenshot = path.join(outputDir, `text-scale-200-${name}-${viewport.name}.png`);
      await page.screenshot({ path: screenshot, fullPage: true });
      report.textScale.push({
        url,
        name,
        viewport: viewport.name,
        factor: TEXT_SCALE_FACTOR,
        method: "inline font-size ×2 override — 실제 브라우저 텍스트 확대와 다를 수 있는 근사",
        screenshot,
        ...metrics,
      });
    }
  }
}

// #2283 V6-11: WCAG 1.4.10 reflow. 400% 확대 목표 폭(320 CSS px) viewport에서 대표 화면이
// 문서 수준 가로 스크롤 없이 재배치되는지 검사한다. noHorizontalScroll이 하나라도 false면
// finalizeReport가 위반으로 편입해 exit code로 실패를 표면화한다(§9 body overflow·§7 400% reflow).
async function reflowPass(page, baseUrl, outputDir, report, pages) {
  const viewport = VIEWPORTS.find((entry) => entry.name === REFLOW_VIEWPORT);
  await page.setViewportSize(viewport);
  for (const [url, name] of pages) {
    const response = await page.goto(`${baseUrl}${url}`, { waitUntil: "networkidle" });
    await assertOk(page, url, response);
    const metrics = await page.evaluate(() => {
      const doc = document.documentElement;
      return {
        scrollWidth: doc.scrollWidth,
        clientWidth: doc.clientWidth,
        bodyScrollWidth: document.body.scrollWidth,
        bodyClientWidth: document.body.clientWidth,
        noHorizontalScroll: doc.scrollWidth <= doc.clientWidth + 1
          && document.body.scrollWidth <= document.body.clientWidth + 1,
      };
    });
    const screenshot = path.join(outputDir, `reflow-400-${name}-${viewport.name}.png`);
    await page.screenshot({ path: screenshot, fullPage: true });
    report.reflow.push({
      url,
      name,
      viewport: viewport.name,
      zoom: "400%",
      method: "1280px @ 400% zoom = 320 CSS px 목표 폭을 320 viewport로 근사(WCAG 1.4.10 reflow)",
      screenshot,
      ...metrics,
    });
  }
}

// #1988: 각 surface가 기대 공개 상태(NONE 2xx·alert 없음, RETRY_WARNING alert 가시·copy 일치)를
// 충족하는지 판정한다. parity 비교가 "동일하게 실패"를 green으로 통과시키는 것을 막는다.
function loginSurfaceMeetsExpectedState(entry) {
  return entry.noneStatus >= 200
    && entry.noneStatus < 300
    && entry.noneAlerts === 0
    && entry.alertVisible === true
    && entry.retryWarningRendered === true;
}

// #1988: 로그인 공개 상태(NONE·RETRY_WARNING)를 admin·operator 각각 캡처하고 parity를 검사한다.
// 실제 계정 잠금을 유발하지 않도록 존재하지 않는 사용자명으로 실패를 만든다.
async function runLoginStatePass(browser, baseUrl, outputDir, report) {
  const captured = {};
  for (const surface of LOGIN_SURFACES) {
    const context = await browser.newContext({ viewport: VIEWPORTS[0] });
    const page = await context.newPage();
    try {
      const noneResponse = await page.goto(`${baseUrl}${surface.loginPath}`, { waitUntil: "domcontentloaded" });
      const noneStatus = noneResponse ? noneResponse.status() : 0;
      const noneAlerts = await page.locator("[role=\"alert\"]").count();
      const noneShot = path.join(outputDir, `login-${surface.key}-none.png`);
      await page.screenshot({ path: noneShot, fullPage: true });

      await page.fill("input[name=\"username\"]", `qa-nonexistent-${Date.now()}`);
      await page.fill("input[name=\"password\"]", "qa-invalid-credential");
      await page.click("button[type=\"submit\"]");
      // #1988: 실패 로그인 판정은 POST 응답 가로채기 대신 [role="alert"] 가시화 대기로 한다.
      // 타임아웃(10s)이면 run을 중단하지 않고 alertVisible=false로 non-blocking 기록한다.
      let alertVisible = false;
      try {
        await page.locator("[role=\"alert\"]").first().waitFor({ state: "visible", timeout: 10000 });
        alertVisible = true;
      } catch {
        alertVisible = false;
      }
      const warningAlerts = await page.locator("[role=\"alert\"]").count();
      const warningCopy = warningAlerts > 0
        ? (await page.locator("[role=\"alert\"]").first().innerText()).trim()
        : null;
      const warningShot = path.join(outputDir, `login-${surface.key}-retry-warning.png`);
      await page.screenshot({ path: warningShot, fullPage: true });

      // #1988: RETRY_WARNING copy 일치 여부는 throw 대신 non-blocking 플래그로 기록한다.
      const retryWarningRendered = Boolean(warningCopy && warningCopy.includes(RETRY_WARNING_COPY));
      const entry = {
        surface: surface.key,
        loginPath: surface.loginPath,
        noneStatus,
        noneAlerts,
        noneScreenshot: noneShot,
        alertVisible,
        warningAlerts,
        warningCopy,
        retryWarningRendered,
        warningScreenshot: warningShot,
      };
      captured[surface.key] = entry;
      report.loginStates.push(entry);
    } finally {
      await context.close();
    }
  }

  const admin = captured.admin;
  const operator = captured.operator;
  // #1988: 한쪽 surface 캡처가 누락되면 parity 계산을 건너뛰고 사유를 기록한다.
  if (!admin || !operator) {
    const missing = !admin ? "admin" : "operator";
    report.loginParity = {
      parity: false,
      reason: `login parity 계산 불가: ${missing} surface 캡처 누락`,
    };
    return;
  }
  const noneStatusParity = admin.noneStatus === operator.noneStatus;
  const warningCopyParity = admin.warningCopy === operator.warningCopy;
  const retryWarningParity = admin.retryWarningRendered === operator.retryWarningRendered;
  const alertVisibleParity = admin.alertVisible === operator.alertVisible;
  const alertStructureParity = admin.warningAlerts === operator.warningAlerts
    && admin.noneAlerts === operator.noneAlerts;
  // #1988: 양쪽 surface가 동일하게 실패해도(alert 없음·copy null) 동등성 비교가 모두 참이라
  // parity=true가 되는 허위 green을 막기 위해, 각 surface가 기대 공개 상태를 충족하는지도 함께 요구한다.
  const adminExpectedStateOk = loginSurfaceMeetsExpectedState(admin);
  const operatorExpectedStateOk = loginSurfaceMeetsExpectedState(operator);
  report.loginParity = {
    parity: noneStatusParity
      && warningCopyParity
      && retryWarningParity
      && alertVisibleParity
      && alertStructureParity
      && adminExpectedStateOk
      && operatorExpectedStateOk,
    noneStatusParity,
    warningCopyParity,
    retryWarningParity,
    alertVisibleParity,
    alertStructureParity,
    adminExpectedStateOk,
    operatorExpectedStateOk,
    adminRetryWarningRendered: admin.retryWarningRendered,
    operatorRetryWarningRendered: operator.retryWarningRendered,
    adminWarningCopy: admin.warningCopy,
    operatorWarningCopy: operator.warningCopy,
  };
}

async function keyboardSmoke(page, baseUrl, report) {
  // #1988: 게이트 측정 상태였던 mobile-768(768×900)로 명시 고정해 호출 순서 의존을 제거한다.
  await page.setViewportSize(VIEWPORTS.find((viewport) => viewport.name === "mobile-768"));
  const response = await page.goto(`${baseUrl}/admin/dashboard/page`, { waitUntil: "networkidle" });
  await assertOk(page, "/admin/dashboard/page", response);
  await page.keyboard.press(process.platform === "darwin" ? "Meta+K" : "Control+K");
  await page.waitForSelector(".command-palette-overlay[style*=\"display: none\"]", { state: "detached", timeout: 1000 }).catch(() => {});
  const paletteOpen = await page.locator(".command-palette-overlay").isVisible();
  const activePlaceholder = await page.evaluate(() => document.activeElement && document.activeElement.getAttribute("placeholder"));
  report.keyboard.push({ check: "command-palette", paletteOpen, activePlaceholder });
  if (!paletteOpen || activePlaceholder !== "메뉴·역 검색") {
    throw new Error("command palette did not move focus to search input");
  }
  await page.keyboard.press("Escape");
  await page.locator(".admin-alert-bell").click();
  const alertExpanded = await page.locator(".admin-alert-bell").getAttribute("aria-expanded");
  report.keyboard.push({ check: "alert-center-toggle", ariaExpanded: alertExpanded });
  if (alertExpanded !== "true") {
    throw new Error("alert center did not expose aria-expanded=true");
  }

  // #2277: workspace disclosure는 현재 위치를 담은 영역만 기본 펼침하고 나머지는 접는다.
  // Alpine이 x-bind로 aria-expanded를 실제 상태로 덮어쓸 때까지(비현재 영역이 접힐 때까지) 기다린 뒤
  // 현재(펼침) 1개 + 나머지(접힘)로 갈렸는지 판정한다. no-JS 정적 상태(모두 true)는 이 대기로 배제된다.
  await page.waitForSelector('.admin-nav-workspace-toggle[aria-expanded="false"]', { timeout: 2000 }).catch(() => {});
  const workspaceDisclosure = await page.evaluate(() => {
    const toggles = Array.from(document.querySelectorAll(".admin-nav-workspace-toggle"));
    return {
      total: toggles.length,
      expanded: toggles.filter((toggle) => toggle.getAttribute("aria-expanded") === "true").length,
      collapsed: toggles.filter((toggle) => toggle.getAttribute("aria-expanded") === "false").length,
    };
  });
  report.keyboard.push({ check: "nav-workspace-disclosure", ...workspaceDisclosure });
  if (!(workspaceDisclosure.total >= 2
    && workspaceDisclosure.expanded === 1
    && workspaceDisclosure.collapsed === workspaceDisclosure.total - 1)) {
    throw new Error(`workspace disclosure did not default to only the current workspace expanded: ${JSON.stringify(workspaceDisclosure)}`);
  }
}

// #2277 리뷰: 현재 위치가 없는 페이지(sidebar('')로 렌더되는 검색·알림·오류)는 is-current 영역이
// 하나도 없어 JS가 전 영역을 접어 program 링크가 사라지는 회귀가 있었다. 서버가 .admin-nav-scroll에
// is-no-current를 붙이고 navWorkspace init이 전 영역 펼침으로 폴백하는지 실제 브라우저로 검증한다.
// /admin/search를 대표로 방문해 모든 영역이 펼쳐지고(aria-expanded=true) program 목록이 실제로 보이는지 확인한다.
async function noCurrentWorkspaceDisclosure(page, baseUrl, report) {
  await page.setViewportSize(VIEWPORTS.find((viewport) => viewport.name === "desktop-1280"));
  const response = await page.goto(`${baseUrl}/admin/search`, { waitUntil: "networkidle" });
  await assertOk(page, "/admin/search", response);
  // Alpine이 x-show를 최초 평가(has-js-ready)한 뒤 판정한다 — 폴백이 실제로 program 목록을 펼쳤는지 본다.
  await page.waitForSelector("body.has-js-ready", { timeout: 2000 }).catch(() => {});
  const disclosure = await page.evaluate(() => {
    const scroll = document.querySelector(".admin-nav-scroll");
    const toggles = Array.from(document.querySelectorAll(".admin-nav-workspace-toggle"));
    const programs = Array.from(document.querySelectorAll(".admin-nav-workspace-programs"));
    return {
      isNoCurrent: Boolean(scroll && scroll.classList.contains("is-no-current")),
      total: toggles.length,
      expanded: toggles.filter((toggle) => toggle.getAttribute("aria-expanded") === "true").length,
      visiblePrograms: programs.filter((element) => element.getClientRects().length > 0).length,
    };
  });
  report.keyboard.push({ check: "nav-workspace-no-current-disclosure", ...disclosure });
  if (!(disclosure.isNoCurrent
    && disclosure.total >= 2
    && disclosure.expanded === disclosure.total
    && disclosure.visiblePrograms === disclosure.total)) {
    throw new Error(`no-current page did not fall back to all workspaces expanded: ${JSON.stringify(disclosure)}`);
  }
}

// #1988: admin-table-scroll wrapper의 키보드 접근성.
// 좁은 viewport로 표를 가로 overflow시킨 뒤 Tab focus·ArrowRight/ArrowLeft scrollLeft 변화·
// focus-visible outline을 검사한다.
async function keyboardTableCheck(page, baseUrl, report) {
  await page.setViewportSize(VIEWPORTS.find((viewport) => viewport.name === "mobile-390"));
  const response = await page.goto(`${baseUrl}/admin/stations/page`, { waitUntil: "networkidle" });
  await assertOk(page, "/admin/stations/page", response);

  let tabFocusable = false;
  for (let index = 0; index < 60 && !tabFocusable; index += 1) {
    await page.keyboard.press("Tab");
    tabFocusable = await page.evaluate(() =>
      Boolean(document.activeElement && document.activeElement.classList.contains("admin-table-scroll")));
  }

  const focusState = await page.evaluate(() => {
    const element = document.querySelector(".admin-table-scroll");
    if (!element) {
      return null;
    }
    const style = getComputedStyle(element);
    const focused = document.activeElement === element;
    return {
      maxScrollLeft: element.scrollWidth - element.clientWidth,
      startScrollLeft: element.scrollLeft,
      focused,
      outlineStyle: focused ? style.outlineStyle : null,
      outlineWidth: focused ? style.outlineWidth : null,
    };
  });

  // Chromium 키보드 스크롤은 smooth 애니메이션이라 press 직후 scrollLeft가 아직 0일 수 있고,
  // CI 러너가 느리면 고정 대기(300ms)로는 정착 전에 측정해 scrolledRight가 false로 뒤집힌다(#2349 flake).
  // 고정 대기 대신 scrollLeft 변화를 상한까지 poll하고, 안 움직이면 Arrow 키를 몇 회 더 눌러 재시도한다.
  // 검사 계약(키보드로 실제 스크롤돼야 통과)은 그대로다 — 최종 afterRight/afterLeft가 실제 변해야 참이 된다.
  const SCROLL_SETTLE_TIMEOUT_MS = 4000;
  const SCROLL_MAX_ATTEMPTS = 4;
  const maxScrollLeft = focusState ? focusState.maxScrollLeft : null;
  const startScrollLeft = focusState ? focusState.startScrollLeft : null;

  const readScrollLeft = () =>
    page.evaluate(() => {
      const element = document.querySelector(".admin-table-scroll");
      return element ? element.scrollLeft : null;
    });

  let afterRight = startScrollLeft;
  let afterLeft = startScrollLeft;
  // maxScrollLeft가 0이면 스크롤 여지가 없어(정상적으로는 발생하지 않음) 눌러도 움직이지 않는다.
  // 기존 의미(스크롤 불가 → scrolledRight/scrolledBackLeft false → 위반)를 유지하되, 부질없는
  // 상한 대기 반복을 피하려고 스크롤 여지가 있을 때만 키 입력·정착 대기를 수행한다.
  if (maxScrollLeft != null && maxScrollLeft > 0) {
    for (let attempt = 0;
      attempt < SCROLL_MAX_ATTEMPTS
        && !(afterRight != null && startScrollLeft != null && afterRight > startScrollLeft);
      attempt += 1) {
      await page.keyboard.press("ArrowRight");
      try {
        await page.waitForFunction(
          (baseline) => {
            const element = document.querySelector(".admin-table-scroll");
            return Boolean(element) && element.scrollLeft > baseline;
          },
          startScrollLeft ?? 0,
          { timeout: SCROLL_SETTLE_TIMEOUT_MS, polling: 50 },
        );
      } catch (error) {
        if (error.name !== "TimeoutError") {
          throw error;
        }
        // 정착 타임아웃: 다음 attempt에서 ArrowRight를 한 번 더 눌러 재시도한다.
      }
      afterRight = await readScrollLeft();
    }

    afterLeft = afterRight;
    // ArrowRight 실패(스크롤 불가) 시 ArrowLeft 루프 스킵, 판정 결과 불변 유지
    if (afterRight != null && startScrollLeft != null && afterRight > startScrollLeft) {
      for (let attempt = 0;
        attempt < SCROLL_MAX_ATTEMPTS
          && !(afterLeft != null && afterRight != null && afterLeft < afterRight);
        attempt += 1) {
        await page.keyboard.press("ArrowLeft");
        try {
          await page.waitForFunction(
            (ceiling) => {
              const element = document.querySelector(".admin-table-scroll");
              return Boolean(element) && element.scrollLeft < ceiling;
            },
            afterRight ?? 0,
            { timeout: SCROLL_SETTLE_TIMEOUT_MS, polling: 50 },
          );
        } catch (error) {
          if (error.name !== "TimeoutError") {
            throw error;
          }
          // 정착 타임아웃: 다음 attempt에서 ArrowLeft를 한 번 더 눌러 재시도한다.
        }
        afterLeft = await readScrollLeft();
      }
    }
  }

  const outlineVisible = Boolean(
    focusState
    && focusState.outlineStyle
    && focusState.outlineStyle !== "none"
    && focusState.outlineWidth
    && parseFloat(focusState.outlineWidth) > 0,
  );
  report.keyboard.push({
    check: "admin-table-scroll-keyboard",
    tabFocusable,
    maxScrollLeft,
    startScrollLeft,
    afterRight,
    afterLeft,
    scrolledRight: afterRight != null && startScrollLeft != null && afterRight > startScrollLeft,
    scrolledBackLeft: afterLeft != null && afterRight != null && afterLeft < afterRight,
    outlineStyle: focusState ? focusState.outlineStyle : null,
    outlineWidth: focusState ? focusState.outlineWidth : null,
    outlineVisible,
  });
}

// V6-07 #2279 / #2313: 마스터 목록 상태 신호 계약. 이관된 master-list(역 목록)를 mobile-390에서 열어
// (1) 첫 식별자 열이 sticky로 고정되는지, (2) 상태가 색 단독이 아닌지 — #2313에서 상태 표현을
// .admin-status(● 점 + 상태 텍스트)로 단일화했으므로, 색으로만 구분되는 ● 점 옆에 상태 텍스트가
// 항상 병기되는지로 판정한다(WCAG 1.4.1 색 단독 금지: 비색 신호 = 상태 텍스트), (3) 표 헤더가
// scope로 연결되는지 검사한다. 하나라도 어기면 finalizeReport가 위반으로 편입해 exit code로 실패를
// 표면화한다(§9 식별자·주의·품질 즉시 접근, badge accessible name).
async function masterListStatusSignalCheck(page, baseUrl, report) {
  await page.setViewportSize(VIEWPORTS.find((viewport) => viewport.name === "mobile-390"));
  const response = await page.goto(`${baseUrl}/admin/stations/page`, { waitUntil: "networkidle" });
  await assertOk(page, "/admin/stations/page", response);

  const signal = await page.evaluate(() => {
    const firstCell = document.querySelector(".admin-table-scroll tbody td:first-child");
    const stickyIdentifier = firstCell ? getComputedStyle(firstCell).position === "sticky" : false;
    const statusCells = Array.from(document.querySelectorAll(".admin-table-scroll .admin-status"));
    // 색 단독 금지의 유일한 비색 신호는 상태 텍스트다. ● 점은 ::before 색상 신호이므로 textContent에
    // 잡히지 않는다 — 모든 상태 셀이 비어있지 않은 텍스트 라벨을 병기해야 통과한다.
    const statusHasText = statusCells.length > 0
      && statusCells.every((cell) => (cell.textContent || "").trim().length > 0);
    const scopedHeaders = document.querySelectorAll(".admin-table-scroll thead th[scope=\"col\"]").length;
    return { stickyIdentifier, statusCells: statusCells.length, statusHasText, scopedHeaders };
  });

  report.keyboard.push({ check: "master-list-status-signal", ...signal });
}

// V6-08 #2280: 신고 대기열 action·photo 경계 계약. 이관된 queue-review(신고 목록)를 mobile-390에서 열어
// (1) 일괄 승인 버튼이 primary, 반려 버튼이 danger로 액션 위계를 명시하는지, (2) 사진 셀이 fail closed인지
// — raw object key(facility-reports/)를 노출하지 않고 썸네일은 permission-gated endpoint만 참조하는지
// 검사한다. 하나라도 어기면 finalizeReport가 위반으로 편입해 exit code로 실패를 표면화한다(§7 action 체계·§9 photo matrix).
async function reportQueueActionSignalCheck(page, baseUrl, report) {
  await page.setViewportSize(VIEWPORTS.find((viewport) => viewport.name === "mobile-390"));
  const response = await page.goto(`${baseUrl}/admin/reports/page`, { waitUntil: "networkidle" });
  await assertOk(page, "/admin/reports/page", response);

  const signal = await page.evaluate(() => {
    const bulkbar = document.querySelector(".bulk-actionbar");
    const approve = document.querySelector(".bulk-actionbar button[name=\"decision\"][value=\"ACCEPT\"]");
    const reject = document.querySelector(".bulk-actionbar button[name=\"decision\"][value=\"REJECT\"]");
    const approvePrimary = approve ? approve.classList.contains("primary") : false;
    const rejectDanger = reject ? reject.classList.contains("danger") : false;
    // 사진 fail closed: 어떤 셀도 raw object key를 노출하지 않고(속성값 leak 포함 innerHTML 스캔),
    // 썸네일은 permission-gated 원본 endpoint만 가리킨다.
    const noRawPhotoKey = !(document.body.innerHTML || "").includes("facility-reports/");
    const thumbs = Array.from(document.querySelectorAll(".report-thumb"));
    // 썸네일이 0개면 every()가 무검증인데도 true를 반환해 PASS로 위장한다 — photoScopedVerified로 실제
    // 검증 여부를 분리하고, photoScoped 자체도 미검증 시 fail closed로 false를 반환한다.
    const photoScopedVerified = thumbs.length > 0;
    const photoScoped = photoScopedVerified
      && thumbs.every((anchor) => (anchor.getAttribute("href") || "").includes("/photo/original"));
    return {
      bulkbarPresent: Boolean(bulkbar),
      approvePrimary,
      rejectDanger,
      noRawPhotoKey,
      photoScoped,
      photoScopedVerified,
      thumbs: thumbs.length,
    };
  });

  report.keyboard.push({ check: "report-queue-action-signal", ...signal });
}

// #2278 V6-06: 목록 툴바 시트 계약. compact viewport에서 (1) body가 가로 overflow를 소유하지 않는지(§9),
// (2) 툴바가 있으면 direct control 수·시트 outside close 입력 미유실·Esc 포커스 복원을 검사한다.
// 화면 이관(V6-07~10) 전에는 툴바가 아직 없어 present:false로 no-op이며 body overflow만 기록한다.
// wrapper(.admin-table-scroll)만 가로 overflow를 소유한다는 #2071 계약과 결이 같다.
async function listToolbarSheetCheck(page, baseUrl, report) {
  await page.setViewportSize(VIEWPORTS.find((viewport) => viewport.name === "mobile-390"));
  const response = await page.goto(`${baseUrl}/admin/reports/page`, { waitUntil: "networkidle" });
  await assertOk(page, "/admin/reports/page", response);
  await page.waitForSelector("body.has-js-ready", { timeout: 2000 }).catch(() => {});

  const base = await page.evaluate(() => {
    const doc = document.documentElement;
    const bodyOverflowX0 = document.body.scrollWidth <= document.body.clientWidth + 1
      && doc.scrollWidth <= doc.clientWidth + 1;
    const toolbar = document.querySelector(".admin-list-toolbar");
    if (!toolbar) {
      return { present: false, bodyOverflowX0 };
    }
    const directControls = Array.from(
      toolbar.querySelectorAll("input:not([type=\"hidden\"]), button, select, a, textarea"),
    ).filter((element) => !element.closest(".admin-toolbar-sheet")).length;
    return { present: true, bodyOverflowX0, directControls };
  });

  if (!base.present) {
    report.keyboard.push({ check: "list-toolbar-sheet", ...base });
    return;
  }

  // 시트 outside close가 입력을 버리지 않는지: 시트 입력에 값을 넣고 트리거로 열었다 바깥을 눌러 닫은 뒤
  // 값이 보존되는지 확인한다. Esc는 트리거로 포커스를 복원해야 한다(focus restore).
  const sheet = await page.evaluate(() => {
    const trigger = document.querySelector(".admin-toolbar-filter-trigger");
    const sheetInput = document.querySelector(".admin-toolbar-filter-sheet input, .admin-toolbar-filter-sheet select");
    if (!trigger || !sheetInput) {
      return { sheetPresent: false };
    }
    if (sheetInput.tagName.toLowerCase() === "input" && sheetInput.type !== "checkbox") {
      sheetInput.value = "보존-확인";
    }
    return { sheetPresent: true, savedValue: sheetInput.value ?? null };
  });

  let inputPreserved = null;
  let sheetClosed = null;
  let focusRestored = null;
  if (sheet.sheetPresent) {
    await page.click(".admin-toolbar-filter-trigger");
    await page.click("h1");
    await page.waitForTimeout(100);
    inputPreserved = await page.evaluate((expected) => {
      const sheetInput = document.querySelector(".admin-toolbar-filter-sheet input, .admin-toolbar-filter-sheet select");
      return sheetInput ? sheetInput.value === expected : false;
    }, sheet.savedValue);
    await page.click(".admin-toolbar-filter-trigger");
    // window 스코프 Esc(#2301 리뷰)는 시트 내부 포커스와 무관하게 발화하므로, 시트 내부 컨트롤에
    // 포커스를 둔 채로 Esc를 눌러도 닫히는지까지 확인한다.
    await page.evaluate(() => {
      const sheetInput = document.querySelector(".admin-toolbar-filter-sheet input, .admin-toolbar-filter-sheet select");
      sheetInput?.focus();
    });
    await page.keyboard.press("Escape");
    await page.waitForTimeout(100);
    const afterEscape = await page.evaluate(() => ({
      sheetClosed: !document.querySelector(".admin-toolbar-filter-sheet")?.classList.contains("is-open"),
      focusRestored: Boolean(document.activeElement
        && document.activeElement.classList.contains("admin-toolbar-filter-trigger")),
    }));
    sheetClosed = afterEscape.sheetClosed;
    focusRestored = afterEscape.focusRestored;
  }

  report.keyboard.push({
    check: "list-toolbar-sheet",
    ...base,
    sheetPresent: sheet.sheetPresent,
    inputPreserved,
    sheetClosed,
    focusRestored,
  });
}

// #2281 V6-09: 통합 대시보드 KPI 상태 계층 계약. 대표 KPI 3개만 headline으로 노출하고 나머지는
// disclosure로 격하해 urgent state를 먼저 식별하게 한다. headline 카드가 3개를 넘거나(우선순위 붕괴),
// 기간 표기 caption(값=현재·스파크라인=최근 7일·델타=전일)이 없거나, 총 카드가 3개 초과인데 나머지를
// 담는 native details(.dashboard-more)가 없거나 DETAILS 요소가 아니면(keyboard·no-JS 접근 불가)
// finalizeReport가 위반으로 편입해 exit code로 실패를 표면화한다(§7 대표 KPI·§9 urgent state 식별).
async function dashboardKpiHierarchyCheck(page, baseUrl, report) {
  await page.setViewportSize(VIEWPORTS.find((viewport) => viewport.name === "mobile-390"));
  const response = await page.goto(`${baseUrl}/admin/dashboard/page`, { waitUntil: "networkidle" });
  await assertOk(page, "/admin/dashboard/page", response);

  const signal = await page.evaluate(() => {
    // #2306 리뷰: 누계 총량 지표(AdminMetricKeys.PUSH_FAILED)는 index가 아니라 지표 의미로 격하한다.
    // Java 상수값을 그대로 미러링한다(계약 값).
    const CUMULATIVE_METRIC_KEY = "push.failed";
    const metricKeysOf = (root) =>
      root ? Array.from(root.querySelectorAll(".dashboard-card")).map((card) => card.getAttribute("data-metric-key")) : [];
    const panel = document.querySelector(".dashboard-metric-panel");
    const headlineGrid = panel ? panel.querySelector(":scope > .dashboard-cards") : null;
    const headlineCards = headlineGrid ? headlineGrid.querySelectorAll(".dashboard-card").length : 0;
    const more = panel ? panel.querySelector(".dashboard-more") : null;
    const disclosureCards = more ? more.querySelectorAll(".dashboard-card").length : 0;
    const totalCards = panel ? panel.querySelectorAll(".dashboard-card").length : 0;
    const headlineMetricKeys = metricKeysOf(headlineGrid);
    const disclosureMetricKeys = metricKeysOf(more);
    // headline 카드 정체성: 모든 headline 카드가 실제 metric key를 노출해야 한다(속성 부재 시 false-green 방지).
    const headlineMetricKeysPresent = headlineCards > 0
      && headlineMetricKeys.every((key) => typeof key === "string" && key.length > 0);
    // 누계 카드는 headline에 없어야 하고, 존재한다면 disclosure에 있어야 한다.
    const cumulativeInHeadline = headlineMetricKeys.includes(CUMULATIVE_METRIC_KEY);
    const cumulativeDemoted = disclosureMetricKeys.includes(CUMULATIVE_METRIC_KEY);
    const captionPresent = Boolean(
      panel
        && panel.querySelector(".section-hint")
        && /현재 값/.test(panel.querySelector(".section-hint").textContent || ""),
    );
    return {
      panelPresent: Boolean(panel),
      headlineCards,
      disclosureCards,
      totalCards,
      headlineMetricKeys,
      disclosureMetricKeys,
      headlineMetricKeysPresent,
      cumulativeInHeadline,
      cumulativeDemoted,
      captionPresent,
      disclosureIsDetails: more ? more.tagName === "DETAILS" : null,
    };
  });

  report.keyboard.push({ check: "dashboard-kpi-hierarchy", ...signal });
}

async function captureAxTree(page, outputDir, report) {
  // #1988: AX tree 캡처도 게이트 측정 상태 mobile-768(768×900)로 고정한다.
  await page.setViewportSize(VIEWPORTS.find((viewport) => viewport.name === "mobile-768"));
  const session = await page.context().newCDPSession(page);
  const tree = await session.send("Accessibility.getFullAXTree");
  const axPath = path.join(outputDir, "dashboard-ax-tree.json");
  await writeFile(axPath, `${JSON.stringify(tree, null, 2)}\n`);
  report.axTree.push({ page: "/admin/dashboard/page", artifact: axPath, nodes: tree.nodes.length });
}

async function login(page, baseUrl, loginPath, username, password) {
  await page.goto(`${baseUrl}${loginPath}`, { waitUntil: "domcontentloaded" });
  await page.fill("input[name=\"username\"]", username);
  await page.fill("input[name=\"password\"]", password);
  await Promise.all([
    page.waitForURL((url) => url.pathname !== loginPath, { waitUntil: "domcontentloaded" }),
    page.click("button[type=\"submit\"]"),
  ]);
  await page.waitForLoadState("networkidle");
}

async function assertOk(page, url, response) {
  if (!response) {
    throw new Error(`${url} did not return a page response`);
  }
  const status = response.status();
  if (status < 200 || status >= 300) {
    throw new Error(`${url} returned HTTP ${status}`);
  }
  const title = await page.title();
  if (page.url().includes("/login") || title.includes("로그인")) {
    throw new Error(`${url} redirected to login`);
  }
}

function chromePath() {
  if (process.env.CHROME_PATH) {
    return process.env.CHROME_PATH;
  }
  const candidates = [
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium-browser",
    "/usr/bin/chromium",
  ];
  const found = candidates.find((candidate) => existsSync(candidate));
  if (!found) {
    throw new Error("CHROME_PATH is required when Chrome is not in a standard location");
  }
  return found;
}

function requiredEnv(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} is required`);
  }
  return value;
}

function parseArgs(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 1) {
    if (args[index] === "--base-url") {
      options.baseUrl = args[index + 1];
      index += 1;
    } else if (args[index] === "--output") {
      options.output = args[index + 1];
      index += 1;
    }
  }
  return options;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((error) => {
    console.error(error.stack ?? error.message);
    process.exitCode = 1;
  });
}
