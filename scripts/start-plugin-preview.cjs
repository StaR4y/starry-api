// Disposable local preview only. Does not replace services on 8080/5173.
const { spawn } = require('node:child_process');
const { mkdirSync, openSync, closeSync, writeFileSync } = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const net = require('node:net');
const repo = path.resolve(__dirname, '..');
const output = path.join(os.tmpdir(), 'starry-plugin-preview');
if (process.env.PLUGIN_PREVIEW_TESTS !== 'true'
    || process.env.POSTGRES_DATABASE !== 'starry_api'
    || process.env.POSTGRES_HOST !== '127.0.0.1' || process.env.POSTGRES_PORT !== '55438') {
  throw new Error('Requires explicit disposable plugin preview database on 127.0.0.1:55438');
}
async function free(port) {
  await new Promise((resolve, reject) => {
    const server = net.createServer(); server.once('error', reject);
    server.listen(port, '127.0.0.1', () => server.close(resolve));
  });
}
function launch(command, args, env, log, cwd = repo) {
  const fd = openSync(path.join(output, log), 'a', 0o600);
  const child = spawn(command, args, { cwd, env, detached: true, stdio: ['ignore', fd, fd] });
  closeSync(fd); child.unref(); return child.pid;
}
(async () => {
  await free(8082); await free(5175);
  mkdirSync(output, { recursive: true, mode: 0o700 });
  const env = { ...process.env, SERVER_ADDRESS: '127.0.0.1', SERVER_PORT: '8082',
    STARRY_MODEL_PRICING_REMOTE_REFRESH_ENABLED: 'false', STARRY_MODEL_RANKING_REMOTE_REFRESH_ENABLED: 'false',
    STARRY_MODEL_PRICING_DIR: `${output}/prices`, STARRY_MODEL_RANKING_DIR: `${output}/ranking`,
    STARRY_DATA_MANAGEMENT_SECURITY_FILE: `${output}/security.json`, STARRY_PLUGIN_DIR: `${output}/plugins`,
    STARRY_ADMIN_TOKEN: '', STARRY_PLUGINS_DISABLED: 'false', STARRY_WEBUI_URL: 'http://127.0.0.1:5175' };
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';
  const backend = launch(java, ['-Xms64m', '-Xmx256m', '-jar', 'service/build/libs/service-0.1.0-SNAPSHOT.jar'], env, 'backend.log');
  const web = launch(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '5175', '--strictPort'],
    { ...process.env, VITE_API_BASE: 'http://127.0.0.1:8082' }, 'web.log', path.join(repo, 'webui'));
  writeFileSync(path.join(output, 'processes.json'), JSON.stringify({ backend, web }), { mode: 0o600 });
  for (let i = 0; i < 100; i++) {
    try {
      const response = await fetch('http://127.0.0.1:8082/system/status');
      if (response.ok) { console.log('Plugin preview ready: http://127.0.0.1:5175/#/settings/plugins'); return; }
    } catch { /* Wait for startup. */ }
    await new Promise(resolve => setTimeout(resolve, 300));
  }
  throw new Error(`Preview startup failed. Inspect ${output}/backend.log`);
})().catch(error => { console.error(error.message); process.exitCode = 1; });
