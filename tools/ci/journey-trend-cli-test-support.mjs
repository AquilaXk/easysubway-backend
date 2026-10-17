// 추세 CLI 테스트(벤치마크·뮤테이션)가 같이 쓰는 임시 디렉터리, CLI 실행기, 가짜 gh (backend #516, #523).
import { spawnSync } from 'node:child_process';
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { SHA } from './journey-benchmark-test-fixtures.mjs';

export const BASE_ENV = {
  PATH: process.env.PATH, GITHUB_SHA: SHA, GITHUB_REF_NAME: 'main', GITHUB_EVENT_NAME: 'schedule', GITHUB_RUN_ID: '101', GITHUB_RUN_ATTEMPT: '1',
  GITHUB_SERVER_URL: 'https://github.com', GITHUB_REPOSITORY: 'AquilaXk/easysubway-backend', RUNNER_NAME: 'GitHub Actions 7', RUNNER_OS: 'Linux', RUNNER_ARCH: 'X64',
};

export const withDirectory = (prefix, run) => {
  const directory = mkdtempSync(join(tmpdir(), prefix));
  try { return run(directory); } finally { rmSync(directory, { recursive: true, force: true }); }
};

export const writeJson = (directory, name, value) => {
  const path = join(directory, name);
  writeFileSync(path, typeof value === 'string' ? value : JSON.stringify(value));
  return path;
};

/** 도구 경로를 묶은 CLI 실행기: (args, env) => spawnSync 결과. */
export const cliFor = (tool) => (args, env = BASE_ENV) => spawnSync(process.execPath, [tool, ...args], { env, encoding: 'utf8' });

export const manifestIn = (directory, results, extra = {}) => writeJson(directory, 'manifest.json', { results, skipped: [], reason: null, ...extra });

/** PATH 앞에 두는 가짜 gh. 목록은 FAKE_RUN_IDS(FAKE_LIST_FAILURE를 주면 목록 조회가 그 메시지로 실패), 내려받는 결과는 FAKE_RESULT, 아티팩트 응답은 FAKE_ARTIFACTS다. */
export function fakeGhEnvironment(directory, { runIds, resultPath, artifactName }) {
  const bin = join(directory, 'bin');
  mkdirSync(bin);
  const script = join(bin, 'gh');
  writeFileSync(script, `#!/bin/sh
case "$1" in
  run)
    if [ "$2" = "list" ]; then
      if [ -n "$FAKE_LIST_FAILURE" ]; then echo "$FAKE_LIST_FAILURE" >&2; exit 1; fi
      printf '%s\\n' $FAKE_RUN_IDS; exit 0
    fi
    while [ $# -gt 0 ]; do if [ "$1" = "--dir" ]; then dir="$2"; fi; shift; done
    mkdir -p "$dir" && cp "$FAKE_RESULT" "$dir/result.json" ;;
  api) printf '%s' "$FAKE_ARTIFACTS" ;;
esac
`);
  chmodSync(script, 0o755);
  const artifacts = JSON.stringify([{ name: artifactName, expired: false }]);
  return { ...BASE_ENV, PATH: `${bin}:${process.env.PATH}`, FAKE_RUN_IDS: runIds.join(' '), FAKE_RESULT: resultPath, FAKE_ARTIFACTS: artifacts };
}
