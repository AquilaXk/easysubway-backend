#!/usr/bin/env node
import { createHash, randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { closeSync, fchmodSync, fsyncSync, linkSync, lstatSync, openSync, readFileSync, rmSync, writeSync } from "node:fs";
import { dirname, relative, resolve } from "node:path";
import { JOURNEY_CONTRACT_RESOURCES } from "./journey-contract-resources.mjs";

const root = resolve(import.meta.dirname, "../..");
const build = resolve(root, "backend/build");
const repository = "AquilaXk/easysubway-backend";

try {
  const args = parse(process.argv.slice(2));
  if (!/^[a-f0-9]{40}$/.test(args.sourceSha)) throw new Error("source sha is invalid");
  const gitDirectory = directory(args.gitDirectory, "git directory"); const output = outputPath(args.output);
  if (git(gitDirectory, ["rev-parse", "FETCH_HEAD"]).trim() !== args.sourceSha || git(gitDirectory, ["cat-file", "-t", args.sourceSha]).trim() !== "commit") throw new Error("fetched source identity is invalid");
  const bundled = JOURNEY_CONTRACT_RESOURCES.map(({ id, path, mediaType }) => { const entry = git(gitDirectory, ["ls-tree", args.sourceSha, "--", path]).trim(); const match = /^100644 blob ([a-f0-9]{40})\t/.exec(entry); if (!match) throw new Error(`source tree entry is not a regular blob: ${path}`); const bytes = gitBytes(gitDirectory, ["cat-file", "blob", match[1]]); return { id, path, owner: repository, mediaType, sha256: hash(bytes), contentBase64: bytes.toString("base64") }; });
  writeNew(output, { schemaVersion: 2, bundleVersion: "2.0.0", component: "backend", producerRepository: repository, producerSha: args.sourceSha, resources: bundled });
} catch (error) { process.stderr.write(`build-journey-contract-prepublication-bundle: ${error instanceof Error ? error.message : "invalid input"}\n`); process.exitCode = 1; }

function parse(values) { if (values.length !== 6) throw new Error("git directory, source sha, and output are required"); const found = {}; for (let i = 0; i < values.length; i += 2) { const [key, value] = [values[i], values[i + 1]]; if (!(["--git-directory", "--source-sha", "--output"].includes(key)) || !value || value.startsWith("--") || Object.hasOwn(found, key)) throw new Error("invalid arguments"); found[key] = value; } return { gitDirectory: found["--git-directory"], sourceSha: found["--source-sha"], output: found["--output"] }; }
function directory(path, label) { const metadata = lstatSync(path, { throwIfNoEntry: false }); if (!metadata || metadata.isSymbolicLink() || !metadata.isDirectory()) throw new Error(`${label} must be a non-symlink directory`); return resolve(path); }
function git(directory, args) { return execFileSync("git", ["-C", directory, ...args], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }); }
function gitBytes(directory, args) { return execFileSync("git", ["-C", directory, ...args], { encoding: "buffer", stdio: ["ignore", "pipe", "pipe"] }); }
function outputPath(path) { const output = resolve(path); if (relative(build, output).startsWith("..")) throw new Error("output must be below backend/build"); directory(build, "backend/build"); directory(dirname(output), "output parent"); if (lstatSync(output, { throwIfNoEntry: false })) throw new Error("output must be absent"); return output; }
function writeNew(output, value) { const temp = `${output}.tmp-${randomUUID()}`; let fd; try { fd = openSync(temp, "wx", 0o600); const bytes = Buffer.from(`${JSON.stringify(value)}\n`); for (let offset = 0; offset < bytes.length;) { const written = writeSync(fd, bytes, offset, bytes.length - offset); if (!written) throw new Error("incomplete write"); offset += written; } fchmodSync(fd, 0o600); fsyncSync(fd); closeSync(fd); fd = undefined; linkSync(temp, output); rmSync(temp); } catch (error) { if (fd !== undefined) closeSync(fd); rmSync(temp, { force: true }); throw error; } }
function hash(bytes) { return createHash("sha256").update(bytes).digest("hex"); }
