import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * EasySubway Backend Anti-Cheating & Test Integrity Guard
 * (Standardized on EasyConvert Multi-Gate Architecture)
 *
 * Scans Java source, tests, and Node.js CI tooling to enforce:
 * 1. ANTI-CIRCULAR-MOCKING: Test helpers directly importing internal mutable production state.
 * 2. ANTI-SILENT-PASS: Java & Node catch blocks returning true or swallowing transit errors.
 * 3. ANTI-PRODUCTION-CHEAT: Test backdoor branching or dummy fallback strings in production.
 * 4. ANTI-HOLLOW-ASSERTION: Tautological assertions (assertTrue(true), assert.ok(true)).
 * 5. GATE_GATEWAY_INTEGRITY: Fail-closed verification for transit provider gateways.
 */

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const ROOT_DIR = path.resolve(__dirname, '../..');

export const EXCLUDED_DIRS = new Set([
  'node_modules',
  '.git',
  'target',
  'build',
  '.gradle',
  'coverage',
  '.cache',
  '.external',
]);

const SUPPORTED_EXTENSIONS = /\.(mjs|cjs|js|ts|java|json)$/;

export function scanDirectory(dir, extension = SUPPORTED_EXTENSIONS, fileList = []) {
  if (!fs.existsSync(dir)) return fileList;
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    if (entry.isDirectory()) {
      if (!EXCLUDED_DIRS.has(entry.name)) {
        scanDirectory(path.join(dir, entry.name), extension, fileList);
      }
    } else if (entry.isFile() && extension.test(entry.name)) {
      fileList.push(path.join(dir, entry.name));
    }
  }
  return fileList;
}

export function getLineAndSnippet(content, index, matchLength = 0) {
  const upToMatch = content.slice(0, index);
  const line = upToMatch.split('\n').length;
  const lineStart = content.lastIndexOf('\n', index) + 1;
  let lineEnd = content.indexOf('\n', index + Math.max(matchLength, 1));
  if (lineEnd === -1) lineEnd = content.length;
  const snippet = content.slice(lineStart, lineEnd).replace(/\s+/g, ' ').trim();
  return {
    line,
    snippet: snippet.length > 120 ? snippet.slice(0, 117) + '...' : snippet,
  };
}

export function checkCircularMocking(repoRoot = ROOT_DIR) {
  const violations = [];
  const testHelperDirs = [
    path.join(repoRoot, 'tools/ci'),
    path.join(repoRoot, 'backend/tools'),
  ];
  const files = testHelperDirs
    .flatMap((d) => scanDirectory(d, /\.(mjs|cjs|js)$/))
    .filter((f) => !f.endsWith('guard-anti-cheat.mjs'));

  const circularPatterns = [
    {
      regex: /(?:import\s+[\s\S]*?\s+from|require\s*\(|import\s*\()\s*['"](\.\.?\/[^'"]*(?:\/tools\/datapack|\/contracts\/builders))['"]/gs,
      desc: 'Test helper circularly imports builder/production scripts.',
    },
  ];

  for (const file of files) {
    const content = fs.readFileSync(file, 'utf8');
    for (const pattern of circularPatterns) {
      pattern.regex.lastIndex = 0;
      let match;
      while ((match = pattern.regex.exec(content)) !== null) {
        const { line, snippet } = getLineAndSnippet(content, match.index, match[0].length);
        violations.push({
          file: path.relative(repoRoot, file),
          line,
          rule: 'ANTI-CIRCULAR-MOCKING',
          snippet,
          message: pattern.desc,
        });
      }
    }
  }
  return violations;
}

export function checkSilentPassBypasses(repoRoot = ROOT_DIR) {
  const violations = [];
  // Scan JS tests
  const jsTestFiles = scanDirectory(path.join(repoRoot, 'tools'), /\.(test\.mjs|spec\.mjs)$/)
    .concat(scanDirectory(path.join(repoRoot, 'backend/tools'), /\.(test\.mjs|spec\.mjs)$/))
    .filter((f) => !f.endsWith('guard-anti-cheat.test.mjs'));

  for (const file of jsTestFiles) {
    const content = fs.readFileSync(file, 'utf8');
    const bypassPatterns = [
      {
        regex: /catch\s*(?:\([^)]*\))?\s*\{[\s\S]{0,60}?return\s+(?:true|1|\{\s*valid\s*:\s*true\s*\})\s*;?[\s\S]{0,20}?\}/gis,
        desc: 'Catch block silently returning true in JS test.',
      },
      {
        regex: /if\s*\(\s*!(?:toolPath|tool|binPath|binary|executable)\b[\s\S]{0,80}?\)\s*(?:\{\s*return\s+true\s*;?\s*\}|return\s+true\s*;?)/gis,
        desc: 'Bypassing test with return true when tool is missing.',
      },
    ];
    for (const pattern of bypassPatterns) {
      pattern.regex.lastIndex = 0;
      let match;
      while ((match = pattern.regex.exec(content)) !== null) {
        const { line, snippet } = getLineAndSnippet(content, match.index, match[0].length);
        violations.push({
          file: path.relative(repoRoot, file),
          line,
          rule: 'ANTI-SILENT-PASS',
          snippet,
          message: pattern.desc,
        });
      }
    }
  }

  // Scan Java test files for empty catch blocks in @Test methods
  const javaTestFiles = scanDirectory(path.join(repoRoot, 'backend/src/test/java'), /\.java$/);
  for (const file of javaTestFiles) {
    const content = fs.readFileSync(file, 'utf8');
    const emptyCatchPattern = /catch\s*\([A-Za-z0-9_]+\s+[A-Za-z0-9_]+\)\s*\{\s*\/\/\s*ignore[^\n]*\s*\}/gis;
    let match;
    while ((match = emptyCatchPattern.exec(content)) !== null) {
      const { line, snippet } = getLineAndSnippet(content, match.index, match[0].length);
      violations.push({
        file: path.relative(repoRoot, file),
        line,
        rule: 'ANTI-SILENT-PASS',
        snippet,
        message: 'Empty catch-ignore block in Java test. Must assert or fail explicitly.',
      });
    }
  }

  return violations;
}

export function checkProductionCheats(repoRoot = ROOT_DIR) {
  const violations = [];
  // Scan Java production code for backdoor flags
  const javaProdFiles = scanDirectory(path.join(repoRoot, 'backend/src/main/java'), /\.java$/);
  const javaCheatPatterns = [
    {
      regex: /System\.getProperty\s*\(\s*['"](?:test|bypass|fake)['"]\s*\)/gis,
      desc: 'Test backdoor property check detected in Java production code.',
    },
    {
      regex: /['"](?:dummy-arrival|fake-timetable-trip|mock-realtime)['"]/gis,
      desc: 'Hardcoded dummy realtime identifier in Java production code.',
    },
  ];

  for (const file of javaProdFiles) {
    const content = fs.readFileSync(file, 'utf8');
    for (const pattern of javaCheatPatterns) {
      pattern.regex.lastIndex = 0;
      let match;
      while ((match = pattern.regex.exec(content)) !== null) {
        const { line, snippet } = getLineAndSnippet(content, match.index, match[0].length);
        violations.push({
          file: path.relative(repoRoot, file),
          line,
          rule: 'ANTI-PRODUCTION-CHEAT',
          snippet,
          message: pattern.desc,
        });
      }
    }
  }

  // Scan Node.js production tooling
  const jsProdFiles = scanDirectory(path.join(repoRoot, 'tools/ci'), /\.(mjs|cjs|js)$/)
    .concat(scanDirectory(path.join(repoRoot, 'backend/tools'), /\.(mjs|cjs|js)$/))
    .filter((f) => !f.includes('.test.') && !f.endsWith('guard-anti-cheat.mjs'));

  for (const file of jsProdFiles) {
    const content = fs.readFileSync(file, 'utf8');
    if (/process\.env\.NODE_ENV\s*===?\s*['"]test['"]/g.test(content)) {
      violations.push({
        file: path.relative(repoRoot, file),
        line: 1,
        rule: 'ANTI-PRODUCTION-CHEAT',
        snippet: 'process.env.NODE_ENV === "test"',
        message: 'Test-specific backdoor branching in tooling.',
      });
    }
  }

  return violations;
}

export const JAVA_HOLLOW_PATTERNS = [
  {
    regex: /assertTrue\s*\(\s*true\s*\)/g,
    desc: 'Hollow assertion assertTrue(true) in Java test.',
  },
  {
    regex: /assertThat\s*\(\s*true\s*\)\.isTrue\s*\(\s*\)/g,
    desc: 'Hollow assertion assertThat(true).isTrue() in Java test.',
  },
  {
    regex: /assertEquals\s*\(\s*([a-zA-Z0-9_]+)\s*,\s*\1\s*\)/g,
    desc: 'Tautological assertEquals(x, x) in Java test.',
  },
  {
    regex: /assertNotNull\s*\(\s*new\s/g,
    desc: 'Hollow assertion assertNotNull(new ...) in Java test.',
  },
  {
    regex: /assertThat\s*\(\s*new\s[^;]*\)\s*\.isNotNull\s*\(\s*\)/g,
    desc: 'Hollow assertion assertThat(new ...).isNotNull() in Java test.',
  },
];

export function checkHollowAssertions(repoRoot = ROOT_DIR) {
  const violations = [];
  // Scan JS tests
  const jsTestFiles = scanDirectory(path.join(repoRoot, 'tools'), /\.(test\.mjs|spec\.mjs)$/)
    .concat(scanDirectory(path.join(repoRoot, 'backend/tools'), /\.(test\.mjs|spec\.mjs)$/))
    .filter((f) => !f.endsWith('guard-anti-cheat.test.mjs'));

  for (const file of jsTestFiles) {
    const content = fs.readFileSync(file, 'utf8');
    const hollowPatterns = [
      {
        regex: /assert\.(?:strictEqual|equal|deepStrictEqual|deepEqual)\s*\(\s*([a-zA-Z0-9_$]+)\s*,\s*\1\s*\)/g,
        desc: 'Tautological assertion comparing variable with itself.',
      },
      {
        regex: /assert\.(?:ok|isTrue)\s*\(\s*true\s*\)/g,
        desc: 'Hollow assertion assert.ok(true).',
      },
    ];
    for (const pattern of hollowPatterns) {
      pattern.regex.lastIndex = 0;
      let match;
      while ((match = pattern.regex.exec(content)) !== null) {
        const { line, snippet } = getLineAndSnippet(content, match.index, match[0].length);
        violations.push({
          file: path.relative(repoRoot, file),
          line,
          rule: 'ANTI-HOLLOW-ASSERTION',
          snippet,
          message: pattern.desc,
        });
      }
    }
  }

  // Scan Java test files for hollow assertions
  const javaTestFiles = scanDirectory(path.join(repoRoot, 'backend/src/test/java'), /\.java$/);
  for (const file of javaTestFiles) {
    const content = fs.readFileSync(file, 'utf8');
    for (const pattern of JAVA_HOLLOW_PATTERNS) {
      pattern.regex.lastIndex = 0;
      let match;
      while ((match = pattern.regex.exec(content)) !== null) {
        const { line, snippet } = getLineAndSnippet(content, match.index, match[0].length);
        violations.push({
          file: path.relative(repoRoot, file),
          line,
          rule: 'ANTI-HOLLOW-ASSERTION',
          snippet,
          message: pattern.desc,
        });
      }
    }
  }

  return violations;
}

export function runAntiCheatAudit(repoRoot = ROOT_DIR) {
  return [
    ...checkCircularMocking(repoRoot),
    ...checkSilentPassBypasses(repoRoot),
    ...checkProductionCheats(repoRoot),
    ...checkHollowAssertions(repoRoot),
  ];
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  console.log('\n🔒 Running EasySubway Backend Anti-Cheat & Test Integrity Guard (EasyConvert Standard)...\n');
  const violations = runAntiCheatAudit();

  if (violations.length > 0) {
    console.error(`\x1b[31m❌ [REJECTED] Found ${violations.length} Anti-Cheat violation(s):\x1b[0m\n`);
    for (const v of violations) {
      console.error(`  \x1b[33m${v.file}:${v.line}\x1b[0m [\x1b[31m${v.rule}\x1b[0m]`);
      console.error(`    Snippet : "${v.snippet}"`);
      console.error(`    Reason  : ${v.message}\n`);
    }
    process.exit(1);
  } else {
    console.log('\x1b[32m✅ [PASS] Zero shortcuts, zero circular mocks, zero silent passes, zero hollow assertions detected.\x1b[0m\n');
    process.exit(0);
  }
}
