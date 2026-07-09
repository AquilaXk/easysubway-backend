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
];

export const ADMIN_PAGES = [
  ["/admin/dashboard/page", "dashboard"],
  ["/admin/reports/page", "reports"],
  ["/admin/stations/page", "stations"],
  ["/admin/stations/station-sangnoksu/page", "station-hub"],
  ["/admin/facilities/page", "facilities"],
  ["/admin/data-collections/page", "collections"],
  ["/admin/batches/page", "batches"],
  ["/admin/incidents/page", "incidents"],
  ["/admin/routes/searches/page", "route-searches"],
  ["/admin/routes/feedback/page", "route-feedback"],
  ["/admin/datapack/pipeline/page", "datapack-pipeline"],
  ["/admin/audits/page", "audits"],
  ["/admin/audits/privacy/page", "privacy-audits"],
];

export const OPERATOR_PAGES = [
  ["/operator/accessibility-report/page", "operator-accessibility"],
  ["/operator/repeated-broken-facilities/page", "operator-repeated-broken"],
  ["/operator/data-collection-failures/page", "operator-collection-failures"],
  ["/operator/route-feedback-report/page", "operator-route-feedback"],
  ["/operator/push-notification-report/page", "operator-push"],
];

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
  };

  try {
    await mkdir(outputDir, { recursive: true });
    await runJsPass(browser, baseUrl, outputDir, adminUser, adminPassword, operatorUser, operatorPassword, report);
    await runNoJsPass(browser, baseUrl, outputDir, adminUser, adminPassword, operatorUser, operatorPassword, report);
  } finally {
    await browser.close();
  }

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
  const criticalViolations = blockingViolations.filter((violation) => violation.impact === "critical");
  const seriousViolations = blockingViolations.filter((violation) => violation.impact === "serious");
  report.summary = {
    criticalAxeViolations: criticalViolations.length,
    seriousAxeViolations: seriousViolations.length,
    screenshots: report.screenshots.length,
    noJsPages: report.noJs.length,
    chartChecks: report.charts.length,
    keyboardChecks: report.keyboard.length,
  };
  report.blockingViolations = blockingViolations;
  const reportPath = path.join(outputDir, "admin-accessibility-qa-report.json");
  await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`);
  if (blockingViolations.length > 0) {
    throw new Error(`blocking axe violations: ${JSON.stringify(blockingViolations)}`);
  }
  console.log(`admin accessibility QA ok: ${reportPath}`);
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

async function keyboardSmoke(page, baseUrl, report) {
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

async function captureAxTree(page, outputDir, report) {
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
