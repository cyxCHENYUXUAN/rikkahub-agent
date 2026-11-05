import { createHash } from 'node:crypto';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const windows = process.platform === 'win32';
const ignored = new Set(['target', 'node_modules', '.git', 'test']);
let active;
let stopping = false;

// Use only fixed Maven arguments; no source filenames are passed to a shell.
function launch(args) {
  const child = spawn(windows ? 'mvn.cmd' : 'mvn', ['-B', ...args], {
    cwd: root,
    stdio: 'inherit',
    shell: windows,
    windowsHide: true,
    env: { ...process.env, RIKKAHUB_SECURITY_DEVELOPMENT_CORS: process.env.RIKKAHUB_SECURITY_DEVELOPMENT_CORS ?? 'true' },
    detached: !windows,
  });
  const done = new Promise((resolve) => {
    child.once('error', (error) => {
      console.error(`[dev] Cannot start Maven: ${error.message}`);
      resolve(1);
    });
    child.once('exit', (code) => resolve(code ?? 1));
  });
  active = { child, done };
  return active;
}

async function stopActive() {
  const current = active;
  if (!current) return;
  if (current.child.exitCode === null && current.child.signalCode === null && current.child.pid) {
    if (windows) {
      // Stop only the process tree started by this launcher.
      await new Promise((resolve, reject) => {
        const killer = spawn('taskkill.exe', ['/PID', String(current.child.pid), '/T', '/F'], {
          windowsHide: true,
          stdio: 'ignore',
        });
        killer.once('error', reject);
        killer.once('exit', resolve);
      });
    } else {
      try {
        process.kill(-current.child.pid, 'SIGTERM');
      } catch (error) {
        if (error.code !== 'ESRCH') throw error;
      }
      const exited = await Promise.race([current.done.then(() => true), delay(5000).then(() => false)]);
      if (!exited) process.kill(-current.child.pid, 'SIGKILL');
    }
  }
  await current.done;
  if (active === current) active = undefined;
}

async function fingerprint() {
  const hash = createHash('sha256');
  async function visit(relative) {
    const absolute = path.join(root, relative);
    try {
      const entries = await readdir(absolute, { withFileTypes: true });
      entries.sort((a, b) => a.name.localeCompare(b.name));
      for (const entry of entries) {
        if (ignored.has(entry.name) || entry.isSymbolicLink()) continue;
        await visit(path.join(relative, entry.name));
      }
    } catch (error) {
      if (error.code === 'ENOTDIR') {
        try {
          hash.update(relative).update(await readFile(absolute));
        } catch (readError) {
          if (readError.code !== 'ENOENT') throw readError;
        }
      } else if (error.code !== 'ENOENT') {
        throw error;
      }
    }
  }
  for (const entry of ['pom.xml', 'backend/pom.xml', 'backend/src/main', 'agent-core/pom.xml', 'agent-core/src/main', 'plugins', 'config']) {
    await visit(entry);
  }
  return hash.digest('hex');
}

async function shutdown() {
  if (stopping) return;
  stopping = true;
  await stopActive();
  process.exit(0);
}
process.on('SIGINT', () => shutdown().catch(fail));
process.on('SIGTERM', () => shutdown().catch(fail));

function fail(error) {
  console.error(`[dev] ${error.message}`);
  process.exitCode = 1;
  stopActive().finally(() => process.exit(1));
}

async function main() {
  console.log('[dev] Watching Java modules and resources. Press Ctrl+C to stop.');
  let previous = await fingerprint();
  let rebuild = true;
  while (!stopping) {
    if (rebuild) {
      rebuild = false;
      console.log('[dev] Building Java modules...');
      const build = launch(['clean', 'install', '-DskipTests']);
      const code = await build.done;
      if (stopping) return;
      if (code === 0) {
        console.log('[dev] Starting backend...');
        const server = launch(['-pl', 'backend', 'spring-boot:run']);
        server.done.then((exitCode) => {
          if (active === server && !stopping) {
            console.log(`[dev] Backend exited (${exitCode}). Fix the problem and save a watched file to retry.`);
          }
        });
      } else {
        active = undefined;
        console.error('[dev] Build failed. Waiting for the next edit to retry.');
      }
    }
    await delay(1000);
    const next = await fingerprint();
    if (next !== previous) {
      // Combine editor save bursts before rebuilding.
      await delay(500);
      previous = await fingerprint();
      console.log('[dev] Change detected; rebuilding and restarting...');
      await stopActive();
      rebuild = true;
    }
  }
}

main().catch(fail);
