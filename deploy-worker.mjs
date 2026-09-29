// deploy-worker.mjs — one-shot Brebde installer via the Cloudflare API.
// Usage: CLOUDFLARE_API_TOKEN=xxx node deploy-worker.mjs [worker-code-path]
// Creates: KV namespace + Worker (brebde) + cron trigger, binds everything,
// enables the workers.dev subdomain, prints the panel URL. Fully idempotent.
import { readFileSync } from 'node:fs';

const TOKEN = process.env.CLOUDFLARE_API_TOKEN;
const WORKER_NAME = process.env.BREBDE_NAME || 'brebde';
const CODE_PATH = process.argv[2] || 'catclient.worker.js';
if (!TOKEN) { console.error('CLOUDFLARE_API_TOKEN required'); process.exit(1); }

const API = 'https://api.cloudflare.com/client/v4';
const headers = { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' };
async function cf(method, path, body, isJson = true) {
  const res = await fetch(API + path, {
    method,
    headers: isJson ? headers : { Authorization: `Bearer ${TOKEN}` },
    body: body === undefined ? undefined : (isJson ? JSON.stringify(body) : body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.success === false) {
    throw new Error(`${method} ${path}: HTTP ${res.status} — ${JSON.stringify(data.errors || data).slice(0, 300)}`);
  }
  return data.result;
}

// 1. account
const accounts = await cf('GET', '/accounts');
const account = accounts[0];
console.log('account:', account.id, account.name);

// 2. KV namespace (create or reuse)
const nss = await cf('GET', `/accounts/${account.id}/storage/kv/namespaces`);
let ns = nss.find((n) => n.title === WORKER_NAME + '-kv');
if (!ns) {
  ns = await cf('POST', `/accounts/${account.id}/storage/kv/namespaces`, { title: WORKER_NAME + '-kv' });
  console.log('KV created:', ns.id);
} else {
  console.log('KV reused:', ns.id);
}

// 3. worker script (module syntax, multipart metadata with bindings + cron)
const code = readFileSync(CODE_PATH, 'utf8');
const metadata = {
  main_module: 'worker.js',
  compatibility_date: '2026-09-01',
  bindings: [
    { type: 'kv_namespace', name: 'CAT_KV', namespace_id: ns.id },
    { type: 'plain_text', name: 'OPEN_PANEL', text: 'true' },
    { type: 'plain_text', name: 'OPEN_SUB', text: 'true' },
  ],
  keep_bindings: true,
};
const form = new FormData();
form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
form.append('worker.js', new Blob([code], { type: 'application/javascript+module' }), 'worker.js');
const putRes = await fetch(`${API}/accounts/${account.id}/workers/scripts/${WORKER_NAME}`, {
  method: 'PUT',
  headers: { Authorization: `Bearer ${TOKEN}` },
  body: form,
});
const putData = await putRes.json().catch(() => ({}));
if (!putRes.ok || putData.success === false) throw new Error('script upload: ' + JSON.stringify(putData.errors || putData).slice(0, 300));
console.log('worker uploaded');

// 4. cron trigger (every 6h)
await cf('PUT', `/accounts/${account.id}/workers/scripts/${WORKER_NAME}/schedules`, [
  [{ cron: '0 */6 * * *' }],
]);
console.log('cron set: 0 */6 * * *');

// 5. enable workers.dev
try {
  const sub = await cf('GET', `/accounts/${account.id}/workers/subdomain`);
  await cf('POST', `/accounts/${account.id}/workers/scripts/${WORKER_NAME}/subdomain`, { enabled: true, previews_enabled: false });
  console.log(`panel: https://${WORKER_NAME}.${sub.result.subdomain}.workers.dev`);
} catch (e) {
  console.log('workers.dev enable: ' + e.message + ' (check dashboard)');
}
console.log('DONE');
