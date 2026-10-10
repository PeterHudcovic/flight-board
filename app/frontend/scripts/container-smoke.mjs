import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';

const frontend = fileURLToPath(new URL('../', import.meta.url));
const suffix = randomUUID().slice(0, 8);
const network = 'flight-board-frontend-test-' + suffix;
const image = 'flight-board-frontend:issue-17';
const containers = [];
let networkCreated = false;
const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
async function request(port, path) {
  return fetch('http://127.0.0.1:' + port + path, { signal: AbortSignal.timeout(5000) });
}
async function waitFor(check, label) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    try { if (await check()) return; } catch { /* Allow containers and Docker DNS to start. */ }
    await new Promise(resolve => setTimeout(resolve, 300));
  }
  throw Error('Timed out: ' + label);
}
function run(name, extra = []) {
  docker('run', '--detach', '--name', name, '--label', 'com.flightboard.test=frontend',
    '--read-only', '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
    '--network', network, '--publish', '127.0.0.1::8080', ...extra, image);
  containers.push(name);
  const binding = JSON.parse(docker('inspect', name))[0].NetworkSettings.Ports['8080/tcp'][0];
  return Number(binding.HostPort);
}
try {
  docker('network', 'create', network); networkCreated = true;
  const standalone = 'flight-board-frontend-static-' + suffix;
  const standalonePort = run(standalone);
  await waitFor(async () => (await request(standalonePort, '/healthz')).status === 200, 'static nginx health');
  assert.equal(docker('exec', standalone, 'id', '-u'), '101');
  for (const path of ['/', '/board', '/index.html']) {
    const page = await request(standalonePort, path);
    assert.equal(page.status, 200); assert.match(await page.text(), /<div id="root">/);
    assert.equal(page.headers.get('cache-control'), 'no-store');
  }
  const api = await request(standalonePort, '/api/departures');
  assert.equal(api.status, 503); assert.equal(api.headers.get('cache-control'), 'no-store');
  assert.equal((await api.json()).code, 'API_UNAVAILABLE');
  assert.equal((await request(standalonePort, '/healthz')).status, 200);
  const index = await (await request(standalonePort, '/')).text();
  const asset = index.match(/src="([^"]+\.js)"/)?.[1]; assert.ok(asset);
  const bundle = await request(standalonePort, asset); assert.equal(bundle.status, 200);
  assert.match(bundle.headers.get('cache-control'), /immutable/);
  const license = await request(standalonePort, '/barlow-condensed-OFL.txt');
  assert.equal(license.status, 200); assert.match(await license.text(), /SIL OPEN FONT LICENSE/);
  console.log('Static container: non-root/read-only, SPA, font license, assets, API isolation and independent health passed.');

  const local = 'flight-board-frontend-local-' + suffix;
  const localPort = run(local, ['--mount', 'type=bind,source=' + resolve(frontend, 'nginx/local.conf') + ',target=/etc/nginx/conf.d/default.conf,readonly']);
  await waitFor(async () => (await request(localPort, '/healthz')).status === 200, 'local health without backend');
  const unavailable = await request(localPort, '/api/departures?probe=1');
  assert.equal(unavailable.status, 502); assert.equal(unavailable.headers.get('cache-control'), 'no-store');
  assert.equal((await request(localPort, '/healthz')).status, 200);
  const backend = 'flight-board-frontend-source-' + suffix;
  docker('run', '--detach', '--name', backend, '--label', 'com.flightboard.test=frontend', '--network', network, '--network-alias', 'backend',
    '--mount', 'type=bind,source=' + resolve(frontend, 'scripts/http-stub.mjs') + ',target=/stub.mjs,readonly',
    'node:24-alpine', 'node', '/stub.mjs');
  containers.push(backend);
  await waitFor(async () => (await request(localPort, '/api/departures?probe=1')).status === 200, 'local proxy recovery after backend starts');
  const proxied = await request(localPort, '/api/departures?probe=1');
  assert.equal(proxied.headers.get('cache-control'), 'no-store');
  assert.equal((await proxied.json()).runId, 'synthetic-container-run');
  assert.equal((await (await request(localPort, '/api/status')).json()).version, 'synthetic-container-sha');
  assert.equal((await request(localPort, '/api/unknown')).status, 404);
  assert.equal((await request(localPort, '/healthz')).status, 200);
  console.log('Local container: health with missing backend, DNS recovery, query/path forwarding and no-store passed.');
} finally {
  for (const name of containers.reverse()) {
    try {
      assert.equal(JSON.parse(docker('inspect', name))[0].Config.Labels['com.flightboard.test'], 'frontend');
      docker('rm', '--force', name);
    } catch (error) { console.error('Could not clean test container ' + name + ': ' + error.message); }
  }
  if (networkCreated) { try { docker('network', 'rm', network); } catch (error) { console.error(error.message); } }
}
