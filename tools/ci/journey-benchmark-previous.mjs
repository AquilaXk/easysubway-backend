// 직전 성공 run의 추세 artifact를 gh로 내려받아 기준 후보로 모은다 (backend #516).
// 다른 추세 job(뮤테이션, #523)도 artifact 이름 접두사와 결과 파서만 바꿔 같은 흐름을 쓴다.
// 실패는 원인별로 나눠 기록한다: 보존 기간 만료(예상된 일), 성공 run인데 artifact 없음·조회/다운로드 오류·손상된 파일(이상 신호).
import { spawnSync } from 'node:child_process';
import { lstatSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseResult } from './journey-benchmark-result.mjs';
import { truncate } from './journey-benchmark-format.mjs';

// workflow의 artifact 이름 `journey-engine-benchmark-<sha>`의 접두사. workflow 구조 테스트가 둘의 일치를 확인한다.
export const ARTIFACT_PREFIX = 'journey-engine-benchmark-';
// 기준으로 쓸 직전 성공 run의 최대 개수. 이상치 한두 개가 중앙값을 끌지 못하면서 최근 변화는 반영되는 크기다.
export const BASELINE_RUN_LIMIT = 5;
// 보존 만료·오류로 못 쓰는 run을 건너뛰고도 기준을 채울 수 있도록 훑어보는 후보 수. 다운로드 실패가 기준 크기를 줄이지 않게 한다.
export const BASELINE_CANDIDATE_LIMIT = 10;
// 추세 결과는 수십 KB다. 이보다 큰 파일은 읽지 않는다.
export const MAX_RESULT_BYTES = 1_048_576;
const GH_MESSAGE_LIMIT = 200;

export function runGh(args) {
  const run = spawnSync('gh', args, { encoding: 'utf8', maxBuffer: 16 * 1_048_576 });
  return { status: run.status ?? 1, stdout: run.stdout ?? '', stderr: run.stderr ?? run.error?.message ?? '' };
}

const oneLine = (text) => truncate(text.replace(/\s+/g, ' ').trim(), GH_MESSAGE_LIMIT);
const skip = (runId, kind, message) => ({ runId, kind, message });

/** 이름 접두사가 맞는 artifact 중 만료되지 않은 것의 이름. 없으면 만료인지 아예 없는지로 나눈다. */
function findArtifact(gh, repo, runId, artifactPrefix) {
  const listed = gh(['api', `repos/${repo}/actions/runs/${runId}/artifacts?per_page=100`, '--jq', `[.artifacts[] | select(.name | startswith("${artifactPrefix}")) | {name, expired}]`]);
  if (listed.status !== 0) return { skipped: skip(runId, 'error', `artifact 목록 조회에 실패했습니다: ${oneLine(listed.stderr)}`) };
  let artifacts;
  try { artifacts = JSON.parse(listed.stdout); } catch { return { skipped: skip(runId, 'error', 'artifact 목록 응답을 해석하지 못했습니다.') }; }
  if (artifacts.length === 0) return { skipped: skip(runId, 'missing-artifact', `성공한 run인데 ${artifactPrefix}* artifact가 없습니다.`) };
  const usable = artifacts.find(({ expired }) => !expired);
  return usable ? { name: usable.name } : { skipped: skip(runId, 'expired', '보존 기간이 지나 artifact가 만료되었습니다.') };
}

/** 내려받은 result.json을 일반 파일로만 읽고 크기를 제한한 뒤 형식까지 검증한다. */
function readDownloaded(path, runId, parse) {
  let stat;
  try { stat = lstatSync(path); } catch { return { skipped: skip(runId, 'unreadable', 'artifact에 result.json이 없습니다.') }; }
  if (!stat.isFile() || stat.size > MAX_RESULT_BYTES) return { skipped: skip(runId, 'unreadable', 'result.json이 일반 파일이 아니거나 크기 제한을 넘었습니다.') };
  try { parse(readFileSync(path, 'utf8'), 'previous result'); } catch (error) { return { skipped: skip(runId, 'unreadable', oneLine(`result.json을 읽지 못했습니다: ${error.message}`)) }; }
  return { path };
}

function fetchOne({ gh, repo, directory, artifactPrefix, parse }, runId) {
  const found = findArtifact(gh, repo, runId, artifactPrefix);
  if (found.skipped) return found;
  const target = join(directory, runId);
  const downloaded = gh(['run', 'download', runId, '--repo', repo, '--name', found.name, '--dir', target]);
  if (downloaded.status !== 0) return { skipped: skip(runId, 'error', `artifact 다운로드에 실패했습니다: ${oneLine(downloaded.stderr)}` ) };
  return readDownloaded(join(target, 'result.json'), runId, parse);
}

/**
 * 같은 workflow의 main 성공 run을 최신순으로 훑어 기준 결과를 최대 BASELINE_RUN_LIMIT개 모은다.
 * 결과: { results: 경로[], skipped: [{runId, kind, message}], reason: 기준이 없을 때의 사유 | null, warning: 목록 조회 자체의 실패 여부 }
 */
export function fetchPreviousResults({ gh = runGh, repo, workflow, directory, artifactPrefix = ARTIFACT_PREFIX, parse = parseResult }) {
  const listed = gh(['run', 'list', '--repo', repo, '--workflow', workflow, '--branch', 'main', '--status', 'success', '--limit', String(BASELINE_CANDIDATE_LIMIT), '--json', 'databaseId', '--jq', '.[].databaseId']);
  if (listed.status !== 0) return { results: [], skipped: [], reason: `run 목록을 조회하지 못했습니다: ${oneLine(listed.stderr)}`, warning: true };
  const runIds = listed.stdout.split('\n').filter((line) => /^\d+$/.test(line));
  if (runIds.length === 0) return { results: [], skipped: [], reason: '직전 성공 run이 없습니다(첫 실행).', warning: false };
  const results = [];
  const skipped = [];
  for (const runId of runIds) {
    if (results.length === BASELINE_RUN_LIMIT) break;
    const fetched = fetchOne({ gh, repo, directory, artifactPrefix, parse }, runId);
    if (fetched.path) results.push(fetched.path); else skipped.push(fetched.skipped);
  }
  const reason = results.length === 0 ? `사용할 수 있는 직전 결과가 없습니다(성공 run ${runIds.length}개 중 ${skipped.length}개 제외).` : null;
  return { results, skipped, reason, warning: false };
}
