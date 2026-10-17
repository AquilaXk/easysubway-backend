import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { FACILITY_MODES } from './journey-load-contract.mjs';
import { MODE_OUTPUT_FILES, ROOT_OUTPUT_FILES, SERVER_MAIN_CLASS, clearPreviousRun, modeDirectory, parseArguments, serverCommand, waitForPortFile } from './journey-load-run.mjs';

const withDirectory = async (run) => {
  const directory = mkdtempSync(join(tmpdir(), 'journey-load-run-'));
  try { return await run(directory); } finally { rmSync(directory, { recursive: true, force: true }); }
};

test('arguments require an absolute output directory and reject anything unknown', () => {
  const bothModes = [FACILITY_MODES.DEFAULT, FACILITY_MODES.REQUIRED];
  assert.deepEqual(parseArguments(['--output-dir', '/tmp/out']), { outputDir: '/tmp/out', backendDir: 'backend', modes: bothModes });
  assert.deepEqual(parseArguments(['--output-dir', '/tmp/out', '--backend-dir', 'other']), { outputDir: '/tmp/out', backendDir: 'other', modes: bothModes });
  assert.deepEqual(parseArguments(['--output-dir', '/tmp/out', '--modes', 'required']).modes, [FACILITY_MODES.REQUIRED]);
  assert.deepEqual(parseArguments(['--output-dir', '/tmp/out', '--modes', 'required,default']).modes, [FACILITY_MODES.REQUIRED, FACILITY_MODES.DEFAULT]);
  for (const modes of ['', 'sideways', 'default,default', 'default,']) assert.throws(() => parseArguments(['--output-dir', '/tmp/out', '--modes', modes]), /--modes/, modes);
  assert.throws(() => parseArguments([]), /--output-dir/);
  assert.throws(() => parseArguments(['--output-dir', 'relative/out']), /absolute/);
  assert.throws(() => parseArguments(['--output-dir', '/tmp/out', '--base-url', 'https://example.com']), /unknown option/);
  assert.throws(() => parseArguments(['--output-dir']), /named options/);
});

test('the server command runs the scenario server with a fixed heap and the port file', () => {
  const runtime = { javaExecutable: '/jdk/bin/java', classpath: ['/a.jar', '/b/classes'] };
  const { command, args } = serverCommand(runtime, '/out/server.port', FACILITY_MODES.DEFAULT);
  assert.equal(command, '/jdk/bin/java');
  assert.deepEqual(args.slice(0, 3), ['-Xms1g', '-Xmx1g', '-Dfile.encoding=UTF-8']);
  assert.deepEqual(args.slice(3, 5), ['-cp', ['/a.jar', '/b/classes'].join(process.platform === 'win32' ? ';' : ':')]);
  assert.deepEqual(args.slice(5), [SERVER_MAIN_CLASS, '--port-file', '/out/server.port', '--facility-status-required', 'false']);
  assert.deepEqual(serverCommand(runtime, '/out/server.port', FACILITY_MODES.REQUIRED).args.slice(-2), ['--facility-status-required', 'true']);
});

test('the port file wait returns the port once the server wrote it', () => withDirectory(async (directory) => {
  const portFile = join(directory, 'server.port');
  setTimeout(() => writeFileSync(portFile, '45123'), 30);
  assert.equal(await waitForPortFile(portFile, { isServerAlive: () => true, timeoutMs: 2000, pollMs: 5 }), 45123);
}));

test('the port file wait fails fast when the server died, on a malformed port, and on timeout', () => withDirectory(async (directory) => {
  const portFile = join(directory, 'server.port');
  await assert.rejects(waitForPortFile(portFile, { isServerAlive: () => false, timeoutMs: 2000, pollMs: 5 }), /exited before/);
  writeFileSync(portFile, 'not a port');
  await assert.rejects(waitForPortFile(portFile, { isServerAlive: () => true, timeoutMs: 2000, pollMs: 5 }), /invalid port/);
  writeFileSync(portFile, '70000');
  await assert.rejects(waitForPortFile(portFile, { isServerAlive: () => true, timeoutMs: 2000, pollMs: 5 }), /invalid port/);
  await assert.rejects(waitForPortFile(join(directory, 'never'), { isServerAlive: () => true, timeoutMs: 40, pollMs: 5 }), /did not write/);
}));

test('a new run clears every previous run output, in the root and in each configuration directory', () => withDirectory((directory) => {
  const stale = [...ROOT_OUTPUT_FILES.map((name) => join(directory, name))];
  for (const mode of Object.values(FACILITY_MODES)) {
    mkdirSync(modeDirectory(directory, mode));
    stale.push(...MODE_OUTPUT_FILES.map((name) => join(modeDirectory(directory, mode), name)));
  }
  for (const path of stale) writeFileSync(path, 'stale');
  writeFileSync(join(directory, 'unrelated.txt'), 'keep');
  clearPreviousRun(directory);
  for (const path of stale) assert.equal(existsSync(path), false, path);
  assert.equal(existsSync(join(directory, 'unrelated.txt')), true);
  assert.doesNotThrow(() => clearPreviousRun(directory), 'clearing an already clean directory is fine');
  assert.ok(MODE_OUTPUT_FILES.includes('server.port') && MODE_OUTPUT_FILES.includes('k6-distilled.json'));
}));

test('each configuration gets its own output directory', () => {
  assert.notEqual(modeDirectory('/out', FACILITY_MODES.DEFAULT), modeDirectory('/out', FACILITY_MODES.REQUIRED));
  assert.ok(modeDirectory('/out', FACILITY_MODES.DEFAULT).startsWith('/out/'));
});
