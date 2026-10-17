// 경로 판정 클래스 뮤테이션(PIT) 추세의 결과 모델 (backend #523): mutations.xml 해석, 점수 집계, 결과 JSON 검증.
// 직전 run의 artifact는 신뢰할 수 없는 입력이므로 비교와 요약이 읽는 필드는 모두 여기서 형식까지 검증한다.
import { validateContext } from './journey-benchmark-result.mjs';

export const fail = (message) => { throw new Error(`journey mutation trend: ${message}`); };

export const SCHEMA_VERSION = 1;
// PIT DetectionStatus. 변이가 탐지됐는지(테스트가 실패했는지)를 가르는 상태만 점수에 들어간다.
const STATUS_FIELD = {
  KILLED: 'killed', TIMED_OUT: 'timedOut', MEMORY_ERROR: 'memoryError',
  SURVIVED: 'survived', NO_COVERAGE: 'noCoverage', NON_VIABLE: 'nonViable',
};
// 끝까지 실행되지 못한 mutant가 있으면 점수는 추정치가 된다. 추정으로 덮지 않고 측정 실패로 드러낸다.
const UNFINISHED_STATUSES = ['STARTED', 'NOT_STARTED', 'RUN_ERROR'];
const DETECTED_FIELDS = ['killed', 'timedOut', 'memoryError'];
const VIABLE_FIELDS = [...DETECTED_FIELDS, 'survived', 'noCoverage'];
const COUNT_FIELDS = [...VIABLE_FIELDS, 'nonViable'];
// 테스트가 잡지 못한 mutant. 목록으로 남겨 테스트 공백을 분석할 수 있게 한다.
const MISSED_STATUSES = ['SURVIVED', 'NO_COVERAGE'];

const DOCUMENT = /^<\?xml[^>]*\?>\s*<mutations(?:\s[^>]*)?>([\s\S]*)<\/mutations>\s*$/;
const MUTATION = /<mutation\s([^>]*)>([\s\S]*?)<\/mutation>/g;
const XML_ENTITIES = { lt: '<', gt: '>', quot: '"', apos: "'", amp: '&' };
const CLASS_NAME = /^[A-Za-z_][A-Za-z0-9_$.]{0,199}$/;
const CLASS_PATTERN = /^[A-Za-z_][A-Za-z0-9_$.*]{0,199}$/;
const METHOD_NAME = /^[A-Za-z0-9_$<>]{1,100}$/;
// PIT mutator 클래스의 마지막 이름. RemoveConditionalMutator만 `_<EQUAL|ORDER>_<IF|ELSE>` 꼬리가 붙는다(PIT 1.30.0 RemoveConditionalMutator.getGloballyUniqueId). summary에 그대로 쓰이므로 이 형식 밖은 거부한다.
const MUTATOR_NAME = /^[A-Za-z0-9]{1,80}(?:_(?:EQUAL|ORDER)_(?:IF|ELSE))?$/;
const VERSION = /^\d{1,3}\.\d{1,3}\.\d{1,3}$/;
const SHA256 = /^[a-f0-9]{64}$/;
const DESCRIPTION_LIMIT = 300;
const SCORE_DECIMALS = 100;

const isRecord = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const record = (value, name) => { if (!isRecord(value)) fail(`${name} must be an object`); return value; };
const matches = (value, pattern, name) => { if (typeof value !== 'string' || !pattern.test(value)) fail(`${name} is invalid`); return value; };
const count = (value, name) => { if (!Number.isInteger(value) || value < 0) fail(`${name} must be a non-negative integer`); return value; };
const positiveInteger = (value, name) => { if (!Number.isInteger(value) || value <= 0) fail(`${name} must be a positive integer`); return value; };
const unescapeXml = (text) => text.replace(/&(lt|gt|quot|apos|amp);/g, (_, name) => XML_ENTITIES[name]);

function element(body, tag) {
  const found = new RegExp(`<${tag}>([^<]*)</${tag}>`).exec(body);
  if (found === null) fail(`mutation is missing ${tag}`);
  return unescapeXml(found[1]);
}

function parseMutation([, attributes, body]) {
  const status = /\bstatus=(['"])([A-Z_]+)\1/.exec(attributes)?.[2];
  if (status === undefined) fail('mutation is missing status');
  if (!(status in STATUS_FIELD) && !UNFINISHED_STATUSES.includes(status)) fail(`unknown mutation status ${status}`);
  const line = Number(element(body, 'lineNumber'));
  if (!Number.isInteger(line) || line < 0) fail('mutation lineNumber is not a number');
  return {
    status,
    mutatedClass: element(body, 'mutatedClass'),
    method: element(body, 'mutatedMethod'),
    line,
    mutator: element(body, 'mutator').split('.').at(-1),
    description: element(body, 'description'),
  };
}

/** PIT mutations.xml을 mutant 목록으로 읽는다. 끝까지 쓰이지 않았거나 끝나지 못한 mutant가 있으면 실패한다. */
export function parseMutations(xml) {
  const document = DOCUMENT.exec(xml);
  if (document === null) fail('input is not a complete mutations document');
  const mutants = [...document[1].matchAll(MUTATION)].map(parseMutation);
  if (document[1].replace(MUTATION, '').trim() !== '') fail('mutations document has content outside mutation elements');
  if (mutants.length === 0) fail('PIT generated no mutations');
  const unfinished = mutants.filter(({ status }) => UNFINISHED_STATUSES.includes(status));
  if (unfinished.length > 0) fail(`PIT did not finish ${unfinished.length} mutant(s): ${[...new Set(unfinished.map(({ status }) => status))].join(', ')}`);
  return mutants;
}

const emptyCounts = () => Object.fromEntries(COUNT_FIELDS.map((field) => [field, 0]));
const viableTotal = (counts) => VIABLE_FIELDS.reduce((sum, field) => sum + counts[field], 0);
const detectedTotal = (counts) => DETECTED_FIELDS.reduce((sum, field) => sum + counts[field], 0);

/** 탐지된 mutant / 실행 가능한 mutant 전체(%). 생존과 미커버 코드가 모두 점수를 낮춘다. 컴파일되지 않은 mutant는 뺀다. */
export function scoreOf(counts) {
  const total = viableTotal(counts);
  if (total === 0) fail('no viable mutant to score');
  return Math.round((detectedTotal(counts) / total) * 100 * SCORE_DECIMALS) / SCORE_DECIMALS;
}

const outerClass = (mutatedClass) => mutatedClass.split('$')[0];
const bySurvivor = (left, right) => left.className.localeCompare(right.className) || left.line - right.line || left.mutator.localeCompare(right.mutator) || left.method.localeCompare(right.method);

/** 전체·클래스별 개수와 점수, 잡지 못한 mutant 목록. 중첩 클래스는 바깥 클래스로 합친다. */
export function summarizeMutants(mutants) {
  const counts = emptyCounts();
  const perClass = new Map();
  for (const { status, mutatedClass } of mutants) {
    const field = STATUS_FIELD[status];
    counts[field] += 1;
    const key = outerClass(mutatedClass);
    if (!perClass.has(key)) perClass.set(key, emptyCounts());
    perClass.get(key)[field] += 1;
  }
  // 실행 가능한 mutant가 없는 클래스는 점수가 정의되지 않아 클래스 표에서 뺀다(전체 개수에는 남는다).
  const classes = Object.fromEntries([...perClass].filter(([, classCounts]) => viableTotal(classCounts) > 0)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([name, classCounts]) => [name, { ...classCounts, score: scoreOf(classCounts) }]));
  const survivors = mutants.filter(({ status }) => MISSED_STATUSES.includes(status))
    .map(({ status, mutatedClass, method, line, mutator, description }) => ({ className: mutatedClass, method, line, mutator, description, status }))
    .sort(bySurvivor);
  return { counts, classes, survivors };
}

export function validateRun(run) {
  record(run, 'run');
  matches(run.pitestVersion, VERSION, 'run.pitestVersion');
  matches(run.junit5PluginVersion, VERSION, 'run.junit5PluginVersion');
  if (!Array.isArray(run.targetClasses) || run.targetClasses.length === 0) fail('run.targetClasses must not be empty');
  run.targetClasses.forEach((pattern) => matches(pattern, CLASS_PATTERN, 'run.targetClasses entry'));
  positiveInteger(run.threads, 'run.threads');
  matches(run.configHash, SHA256, 'run.configHash');
  count(run.elapsedSeconds, 'run.elapsedSeconds');
  return run;
}

function validateCounts(value, name) {
  record(value, name);
  for (const field of COUNT_FIELDS) count(value[field], `${name}.${field}`);
  const score = viableTotal(value) === 0 ? null : scoreOf(value);
  if (value.score !== score) fail(`${name}.score does not match its counts`);
  return value;
}

function validateSurvivor(survivor) {
  record(survivor, 'survivor');
  matches(survivor.className, CLASS_NAME, 'survivor.className');
  matches(survivor.method, METHOD_NAME, 'survivor.method');
  count(survivor.line, 'survivor.line');
  matches(survivor.mutator, MUTATOR_NAME, 'survivor.mutator');
  if (typeof survivor.description !== 'string' || survivor.description.length > DESCRIPTION_LIMIT) fail('survivor.description is invalid');
  if (!MISSED_STATUSES.includes(survivor.status)) fail('survivor.status is invalid');
}

export function validateMutationResult(value) {
  if (!isRecord(value) || value.schemaVersion !== SCHEMA_VERSION) fail(`result is not a schemaVersion ${SCHEMA_VERSION} result`);
  validateContext(value.context);
  validateRun(value.run);
  validateCounts(value.totals, 'totals');
  record(value.classes, 'classes');
  if (Object.keys(value.classes).length === 0) fail('classes must not be empty');
  for (const [name, counts] of Object.entries(value.classes)) validateCounts(matches(name, CLASS_NAME, 'class name') && counts, `classes.${name}`);
  if (!Array.isArray(value.survivors)) fail('survivors must be a list');
  value.survivors.forEach(validateSurvivor);
  return value;
}

export function buildMutationResult({ mutants, run, context }) {
  if (mutants.length === 0) fail('PIT generated no mutations');
  validateContext(context);
  validateRun(run);
  const { counts, classes, survivors } = summarizeMutants(mutants);
  return validateMutationResult({ schemaVersion: SCHEMA_VERSION, context, run, totals: { ...counts, score: scoreOf(counts) }, classes, survivors });
}

export function parseMutationResult(text, name) {
  let value;
  try { value = JSON.parse(text); } catch (error) { fail(`${name} is not valid JSON: ${error.message}`); }
  return validateMutationResult(value);
}
