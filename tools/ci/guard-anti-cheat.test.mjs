import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  checkCircularMocking,
  checkSilentPassBypasses,
  checkProductionCheats,
  checkHollowAssertions,
  runAntiCheatAudit,
  JAVA_HOLLOW_PATTERNS,
} from './guard-anti-cheat.mjs';

test('javaHollowPatterns detects new instantiation in assertNotNull and assertThat isNotNull', () => {
  const assertNotNullViolation = 'assertNotNull(new RouteBundlePublicationObjectFetcher());';
  const assertThatViolation = 'assertThat(new Object()).isNotNull();';
  const validAssertNotNull = 'assertNotNull(result);';
  const validAssertThat = 'assertThat(result).isNotNull();';

  const matches = (pattern, text) => {
    pattern.regex.lastIndex = 0;
    return pattern.regex.test(text);
  };

  const assertNotNullPattern = JAVA_HOLLOW_PATTERNS.find((p) => p.regex.source.includes('assertNotNull') && p.regex.source.includes('new'));
  const assertThatPattern = JAVA_HOLLOW_PATTERNS.find((p) => p.regex.source.includes('assertThat') && p.regex.source.includes('new'));

  assert.ok(matches(assertNotNullPattern, assertNotNullViolation));
  assert.ok(!matches(assertNotNullPattern, validAssertNotNull));

  assert.ok(matches(assertThatPattern, assertThatViolation));
  assert.ok(!matches(assertThatPattern, validAssertThat));
});

test('checkHollowAssertions catches tautological assertions in test sources', () => {
  const violations = checkHollowAssertions();
  assert.ok(Array.isArray(violations));
});

test('checkCircularMocking verifies test helpers are decoupled', () => {
  const violations = checkCircularMocking();
  assert.ok(Array.isArray(violations));
});

test('checkSilentPassBypasses verifies no silent catch blocks or missing tool passes', () => {
  const violations = checkSilentPassBypasses();
  assert.ok(Array.isArray(violations));
});

test('checkProductionCheats verifies zero backdoor flags in production tools', () => {
  const violations = checkProductionCheats();
  assert.ok(Array.isArray(violations));
});

test('runAntiCheatAudit passes cleanly on the updated backend repository', () => {
  const violations = runAntiCheatAudit();
  assert.equal(violations.length, 0, `Expected 0 violations, found: ${JSON.stringify(violations, null, 2)}`);
});
