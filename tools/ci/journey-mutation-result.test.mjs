import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { context } from './journey-benchmark-test-fixtures.mjs';
import { buildMutationResult, parseMutationResult, parseMutations, scoreOf, summarizeMutants, validateRun } from './journey-mutation-result.mjs';
import { DISPOSITION, OVERLAY, RUN, mutantXml, mutantsFor, mutationsXml, result } from './journey-mutation-test-fixtures.mjs';

const ALTERNATIVES = 'com.easysubway.journey.application.JourneyAlternatives';
const METHOD_PATTERN = /^[A-Za-z0-9_$<>]+$/;
const parse = (mutants) => parseMutations(mutationsXml(mutants));

test('a mutation entry is read into class, method, line, mutator and status, with XML escapes resolved', () => {
  const [mutant] = parse([{ status: 'SURVIVED', mutatedClass: `${OVERLAY}$Capture`, method: '&lt;init&gt;', line: 42, description: 'negated conditional &amp; "quoted"' }]);
  assert.deepEqual(mutant, {
    status: 'SURVIVED', mutatedClass: `${OVERLAY}$Capture`, method: '<init>', line: 42,
    mutator: 'ConditionalsBoundaryMutator', description: 'negated conditional & "quoted"',
  });
});

test('the real PIT output shape parses: the partial attribute, an empty killingTest and a single-quoted attribute list', () => {
  const xml = `<?xml version="1.0" encoding="UTF-8"?>\n<mutations partial="true">\n${mutantXml({ status: 'SURVIVED' })}\n${mutantXml()}\n</mutations>\n`;
  assert.deepEqual(parseMutations(xml).map(({ status }) => status), ['SURVIVED', 'KILLED']);
});

test('output that is not a complete mutations document is rejected instead of being scored', () => {
  const complete = mutationsXml([{}]);
  assert.throws(() => parseMutations(complete.replace('</mutations>\n', '')), /not a complete mutations document/);
  assert.throws(() => parseMutations(''), /not a complete mutations document/);
  assert.throws(() => parseMutations('<html></html>'), /not a complete mutations document/);
  assert.throws(() => parseMutations(mutationsXml([])), /no mutations/);
  assert.throws(() => parseMutations(complete.replace('<lineNumber>10</lineNumber>', '')), /lineNumber/);
  assert.throws(() => parseMutations(complete.replace('<lineNumber>10</lineNumber>', '<lineNumber>ten</lineNumber>')), /lineNumber/);
  assert.throws(() => parseMutations(complete.replace('status=\'KILLED\'', 'status=\'ODD\'')), /unknown mutation status ODD/);
});

test('mutants PIT never finished or could not run make the measurement untrustworthy', () => {
  for (const status of ['STARTED', 'NOT_STARTED', 'RUN_ERROR']) {
    assert.throws(() => parse([{}, { status }]), new RegExp(`${status}`), status);
  }
});

test('the score is detected mutants over every viable mutant, so survivors and uncovered code both lower it', () => {
  const mutants = parse([
    ...mutantsFor(OVERLAY, { killed: 3, survived: 1, noCoverage: 1 }),
    { status: 'TIMED_OUT', mutatedClass: OVERLAY, line: 300 },
    { status: 'MEMORY_ERROR', mutatedClass: OVERLAY, line: 301 },
    { status: 'NON_VIABLE', mutatedClass: OVERLAY, line: 302 },
  ]);
  const { counts } = summarizeMutants(mutants);
  assert.deepEqual(counts, { killed: 3, timedOut: 1, memoryError: 1, survived: 1, noCoverage: 1, nonViable: 1 });
  assert.equal(scoreOf(counts), 71.43); // 5 / 7
  assert.equal(scoreOf({ killed: 4, timedOut: 0, memoryError: 0, survived: 0, noCoverage: 0, nonViable: 9 }), 100);
  assert.throws(() => scoreOf({ killed: 0, timedOut: 0, memoryError: 0, survived: 0, noCoverage: 0, nonViable: 2 }), /no viable mutant/);
});

test('nested classes roll up into their outer class and the survivor list is complete and ordered', () => {
  const { classes, survivors } = summarizeMutants(parse([
    { mutatedClass: DISPOSITION, line: 1 },
    { mutatedClass: `${DISPOSITION}$PublicFailure`, line: 2, status: 'SURVIVED', method: '&lt;init&gt;', mutator: 'org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator', description: 'negated conditional' },
    { mutatedClass: OVERLAY, line: 90, status: 'SURVIVED' },
    { mutatedClass: OVERLAY, line: 12, status: 'NO_COVERAGE' },
    { mutatedClass: OVERLAY, line: 12, status: 'KILLED' },
  ]));
  assert.deepEqual(Object.keys(classes), [DISPOSITION, OVERLAY]);
  assert.equal(classes[DISPOSITION].killed, 1);
  assert.equal(classes[DISPOSITION].survived, 1);
  assert.equal(classes[DISPOSITION].score, 50);
  assert.deepEqual(survivors.map(({ className, line, status }) => `${className.split('.').at(-1)}:${line}:${status}`), ['JourneyExecutionDisposition$PublicFailure:2:SURVIVED', 'JourneyFacilityBlockOverlay:12:NO_COVERAGE', 'JourneyFacilityBlockOverlay:90:SURVIVED']);
  assert.equal(survivors[0].mutator, 'NegateConditionalsMutator');
});

test('the run description from the Gradle task is validated field by field', () => {
  assert.deepEqual(validateRun(RUN), RUN);
  const broken = {
    'pitest version': { pitestVersion: '1.30' },
    'plugin version': { junit5PluginVersion: 'latest' },
    'empty scope': { targetClasses: [] },
    'markdown in scope': { targetClasses: ['a.B`*'] },
    'thread count': { threads: 0 },
    'config hash': { configHash: 'abc' },
    'missing config hash': { configHash: undefined },
    'elapsed time': { elapsedSeconds: -1 },
  };
  for (const [name, change] of Object.entries(broken)) assert.throws(() => validateRun({ ...RUN, ...change }), /run\./, name);
  assert.throws(() => validateRun(null), /run must be an object/);
});

test('a result carries context, run, totals, classes and survivors, and survives a JSON round trip', () => {
  const built = result();
  assert.equal(built.schemaVersion, 1);
  assert.equal(built.totals.killed, 12);
  assert.equal(built.totals.survived, 2);
  assert.equal(built.totals.score, 85.71);
  assert.deepEqual(built.run, RUN);
  assert.equal(built.survivors.length, 2);
  assert.deepEqual(parseMutationResult(JSON.stringify(built), 'result'), built);
});

test('a result that does not match the schema is rejected whichever field is damaged', () => {
  const damage = {
    'schema version': (value) => { value.schemaVersion = 2; },
    'missing context': (value) => { delete value.context; },
    'bad commit': (value) => { value.context.commitSha = 'abc'; },
    'missing run': (value) => { delete value.run; },
    'negative count': (value) => { value.totals.killed = -1; },
    'fractional count': (value) => { value.classes[OVERLAY].survived = 1.5; },
    'score out of range': (value) => { value.totals.score = 101; },
    'class name with markdown': (value) => { value.classes['a.B`|'] = value.classes[OVERLAY]; },
    'survivor without a line': (value) => { delete value.survivors[0].line; },
    'survivor status': (value) => { value.survivors[0].status = 'KILLED'; },
    'survivors not a list': (value) => { value.survivors = {}; },
    'no classes': (value) => { value.classes = {}; },
  };
  for (const [name, change] of Object.entries(damage)) {
    const value = structuredClone(result());
    change(value);
    assert.throws(() => parseMutationResult(JSON.stringify(value), 'previous result'), Error, name);
  }
  assert.throws(() => parseMutationResult('{', 'previous result'), /previous result is not valid JSON/);
});

test('building a result needs a valid context and at least one viable mutant', () => {
  const mutants = parse([{}]);
  assert.throws(() => buildMutationResult({ mutants, run: RUN, context: context({ commitSha: 'short' }) }), /commitSha/);
  assert.throws(() => buildMutationResult({ mutants: [], run: RUN, context: context() }), /no mutations/);
});

test('a mutations.xml written by PIT 1.30.0 for the real target classes parses to the counts and survivors it holds', () => {
  const real = parseMutations(readFileSync(new URL('./fixtures/pit-mutations.xml', import.meta.url), 'utf8'));
  const { counts, classes, survivors } = summarizeMutants(real);
  assert.deepEqual(counts, { killed: 3, timedOut: 0, memoryError: 0, survived: 5, noCoverage: 0, nonViable: 0 });
  assert.deepEqual(Object.keys(classes), [`${ALTERNATIVES}`, OVERLAY]);
  assert.deepEqual(survivors.map(({ className, line }) => `${className.split('.').at(-1)}:${line}`), ['JourneyAlternatives:120', 'JourneyFacilityBlockOverlay:89', 'JourneyFacilityBlockOverlay:91', 'JourneyFacilityBlockOverlay:92', 'JourneyFacilityBlockOverlay:93']);
  assert.ok(survivors.every(({ method }) => METHOD_PATTERN.test(method)));
});
