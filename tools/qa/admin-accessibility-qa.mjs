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
    blockingViolations.push({
      page: "/admin/stations/page",
      id: "admin-table-scroll-keyboard",
      impact: "serious",
      nodes: 0,
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
  await captureAxTree(page, outputDir, report);
  await keyboardTableCheck(page, baseUrl, report);
  await textScalePass(page, baseUrl, outputDir, report, ADMIN_TEXT_SCALE_PAGES);
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

  // Chromium 키보드 스크롤은 smooth 애니메이션이라 press 직후 scrollLeft가 아직 0일 수 있다. 정착 대기.
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("ArrowRight");
  await page.waitForTimeout(300);
  const afterRight = await page.evaluate(() => {
    const element = document.querySelector(".admin-table-scroll");
    return element ? element.scrollLeft : null;
  });
  await page.keyboard.press("ArrowLeft");
  await page.waitForTimeout(300);
  const afterLeft = await page.evaluate(() => {
    const element = document.querySelector(".admin-table-scroll");
    return element ? element.scrollLeft : null;
  });

  const startScrollLeft = focusState ? focusState.startScrollLeft : null;
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
    maxScrollLeft: focusState ? focusState.maxScrollLeft : null,
    scrolledRight: afterRight != null && startScrollLeft != null && afterRight > startScrollLeft,
    scrolledBackLeft: afterLeft != null && afterRight != null && afterLeft < afterRight,
    outlineStyle: focusState ? focusState.outlineStyle : null,
    outlineWidth: focusState ? focusState.outlineWidth : null,
    outlineVisible,
  });
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
