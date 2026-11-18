#!/usr/bin/env node
// 경로 검색 부하·장애 시나리오 실행기 (backend #520).
//
// 1) Gradle로 테스트 클래스를 컴파일하고 서버 실행 정보(java, classpath)를 받는다.
// 2) 시나리오 서버(JourneyLoadServer)를 직접 띄우고 포트를 기다린다.
// 3) k6로 단계별 부하·장애 시나리오를 돌린다.
// 4) 서버를 종료하고 k6 요약을 판정한다(journey-load-verdict.mjs).
// 대상은 이 실행기가 띄운 로컬 서버뿐이다. 외부 주소를 받는 인자는 없다.
import { spawn, spawnSync } from 'node:child_process';
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, rmSync } from 'node:fs';
import { delimiter, isAbsolute, join } from 'node:path';
import { isMainModule } from '../lib/is-main-module.mjs';
import { FACILITY_MODES, K6_OUTPUT_FILES } from './journey-load-contract.mjs';
import { main as judge } from './journey-load-verdict.mjs';

export const SERVER_MAIN_CLASS = 'com.easysubway.journey.adapter.in.web.JourneyLoadServer';
const SERVER_JVM_OPTIONS = ['-Xms1g', '-Xmx1g', '-Dfile.encoding=UTF-8'];
const SERVER_START_TIMEOUT_MS = 120_000;
const SERVER_STOP_TIMEOUT_MS = 30_000;
const PORT_POLL_MS = 100;
const MAX_PORT = 65_535;
const INIT_SCRIPT = '../tools/ci/gradle/journey-load.init.gradle';
const K6_SCRIPT = new URL('./journey-load.k6.js', import.meta.url).pathname;

/** 서버 구성(facility mode)마다 서버·k6·판정을 따로 돌리므로 산출물은 구성별 디렉터리에 둔다. */
const MODE_FILES = Object.freeze({ port: 'server.port', serverLog: 'server.log', verdict: 'verdict.json', summary: 'summary.md', ...K6_OUTPUT_FILES });
const ROOT_FILES = Object.freeze({ runtime: 'runtime.json' });
export const MODE_OUTPUT_FILES = Object.freeze(Object.values(MODE_FILES));
export const ROOT_OUTPUT_FILES = Object.freeze(Object.values(ROOT_FILES));
export const modeDirectory = (outputDir, mode) => join(outputDir, mode);

const fail = (message) => { throw new Error(`journey load run: ${message}`); };

function parseModes(value) {
  const modes = value.split(',');
  const valid = modes.every((mode) => Object.values(FACILITY_MODES).includes(mode)) && new Set(modes).size === modes.length;
  if (!valid) fail(`--modes must be a comma-separated list of distinct ${Object.values(FACILITY_MODES).join(', ')}`);
  return modes;
}

export function parseArguments(argv) {
  const parsed = { backendDir: 'backend', modes: Object.values(FACILITY_MODES) };
  const names = { 'output-dir': 'outputDir', 'backend-dir': 'backendDir', modes: 'modes' };
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index], value = argv[index + 1];
    if (!/^--[a-z][a-z-]*$/.test(key ?? '') || value === undefined) fail('named options required');
    if (!Object.hasOwn(names, key.slice(2))) fail(`unknown option ${key}`);
    parsed[names[key.slice(2)]] = key === '--modes' ? parseModes(value) : value;
  }
  if (!parsed.outputDir) fail('--output-dir is required');
  if (!isAbsolute(parsed.outputDir)) fail('--output-dir must be absolute');
  return parsed;
}

export function serverCommand({ javaExecutable, classpath }, portFile, facilityMode) {
  const facilityStatusRequired = String(facilityMode === FACILITY_MODES.REQUIRED);
  return {
    command: javaExecutable,
    args: [...SERVER_JVM_OPTIONS, '-cp', classpath.join(delimiter), SERVER_MAIN_CLASS, '--port-file', portFile, '--facility-status-required', facilityStatusRequired],
  };
}

const sleep = (millis) => new Promise((resolve) => { setTimeout(resolve, millis); });

/** 서버는 포트 파일을 임시 파일에 쓴 뒤 옮기므로 파일이 보이면 내용은 완성된 값이다. */
export async function waitForPortFile(portFile, { isServerAlive, timeoutMs, pollMs }) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (existsSync(portFile)) {
      const text = readFileSync(portFile, 'utf8').trim();
      const port = Number(text);
      if (!/^[1-9][0-9]{0,4}$/.test(text) || port > MAX_PORT) fail(`the server wrote an invalid port ${JSON.stringify(text)}`);
      return port;
    }
    if (!isServerAlive()) fail('the server exited before it wrote its port');
    await sleep(pollMs);
  }
  return fail(`the server did not write its port within ${timeoutMs} ms`);
}

/** 이전 실행의 포트·요약이 남아 있으면 새 서버가 뜨기 전에 옛 포트를 읽거나 옛 요약을 판정할 수 있어 시작 전에 지운다. */
export function clearPreviousRun(outputDir) {
  for (const name of ROOT_OUTPUT_FILES) rmSync(join(outputDir, name), { force: true });
  for (const mode of Object.values(FACILITY_MODES)) {
    for (const name of MODE_OUTPUT_FILES) rmSync(join(modeDirectory(outputDir, mode), name), { force: true });
  }
}

function readRuntime(backendDir, runtimeFile) {
  const gradle = spawnSync('./gradlew', ['--init-script', INIT_SCRIPT, 'journeyLoadRuntime', `-PjourneyLoadRuntimeOutput=${runtimeFile}`, '--no-daemon'], { cwd: backendDir, stdio: 'inherit' });
  if (gradle.status !== 0) fail('gradle journeyLoadRuntime failed');
  return JSON.parse(readFileSync(runtimeFile, 'utf8'));
}

function startServer(runtime, portFile, logFile, facilityMode) {
  const log = openSync(logFile, 'w');
  const { command, args } = serverCommand(runtime, portFile, facilityMode);
  const child = spawn(command, args, { stdio: ['ignore', log, log] });
  closeSync(log);
  const state = { alive: true, exited: new Promise((resolve) => { child.once('exit', () => { state.alive = false; resolve(); }); }) };
  return { child, state };
}

async function stopServer({ child, state }) {
  if (!state.alive) return;
  child.kill('SIGTERM');
  const stopped = await Promise.race([state.exited.then(() => true), sleep(SERVER_STOP_TIMEOUT_MS).then(() => false)]);
  if (!stopped) child.kill('SIGKILL');
}

function runK6(port, outputDir, facilityMode) {
  const result = spawnSync('k6', ['run', '--quiet', K6_SCRIPT], {
    stdio: 'inherit',
    env: { ...process.env, JOURNEY_LOAD_BASE_URL: `http://127.0.0.1:${port}`, JOURNEY_LOAD_OUTPUT_DIR: outputDir, JOURNEY_LOAD_FACILITY_MODE: facilityMode },
  });
  if (result.error) fail(`k6 could not start: ${result.error.message}`);
  if (result.status !== 0) fail(`k6 exited with ${result.status}`);
}

/** 한 구성의 서버를 띄워 k6를 돌리고 서버를 내린 뒤 판정한다. 판정의 종료 코드(0 통과, 1 위반)를 돌려준다. */
async function runMode(runtime, directory, facilityMode, env) {
  mkdirSync(directory, { recursive: true });
  const portFile = join(directory, MODE_FILES.port);
  const server = startServer(runtime, portFile, join(directory, MODE_FILES.serverLog), facilityMode);
  const stopOnSignal = () => { server.child.kill('SIGTERM'); };
  process.once('SIGINT', stopOnSignal);
  process.once('SIGTERM', stopOnSignal);
  try {
    const port = await waitForPortFile(portFile, { isServerAlive: () => server.state.alive, timeoutMs: SERVER_START_TIMEOUT_MS, pollMs: PORT_POLL_MS });
    runK6(port, directory, facilityMode);
  } finally {
    await stopServer(server);
    process.removeListener('SIGINT', stopOnSignal);
    process.removeListener('SIGTERM', stopOnSignal);
  }
  const summary = env.GITHUB_STEP_SUMMARY ?? join(directory, MODE_FILES.summary);
  return judge(['--distilled', join(directory, MODE_FILES.distilled), '--output', join(directory, MODE_FILES.verdict), '--summary', summary], env);
}

/** 한 구성이 실패해도 나머지 구성을 끝까지 돌려 산출물을 모두 남긴다. 하나라도 위반이면 1이다. */
export async function run({ outputDir, backendDir, modes }, env = process.env) {
  mkdirSync(outputDir, { recursive: true });
  clearPreviousRun(outputDir);
  const runtime = readRuntime(backendDir, join(outputDir, ROOT_FILES.runtime));
  const codes = [];
  for (const mode of modes) codes.push(await runMode(runtime, modeDirectory(outputDir, mode), mode, env));
  return Math.max(...codes);
}

if (isMainModule(import.meta.url)) {
  try {
    process.exitCode = await run(parseArguments(process.argv.slice(2)));
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 2;
  }
}
