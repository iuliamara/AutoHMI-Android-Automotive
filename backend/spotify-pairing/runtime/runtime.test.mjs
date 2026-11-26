import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { Miniflare, Log, LogLevel, convertV4MiniflareOptions } from 'miniflare';
import { nonce } from '../src/worker.mjs';
import { build } from 'esbuild';

test('real local Workers runtime supports SQLite transactions, alarms and single-use delivery', { timeout: 60_000 }, async () => {
  const origin = 'https://relay.test.invalid'; const clientId = '0123456789abcdef0123456789abcdef';
  const bundle = await build({ entryPoints: [fileURLToPath(new URL('../src/entry.mjs', import.meta.url))], bundle: true, format: 'esm', write: false });
  const options = convertV4MiniflareOptions({ modules: true, script: bundle.outputFiles[0].text,
    compatibilityDate: '2026-11-23', log: new Log(LogLevel.NONE),
    bindings: { PUBLIC_ORIGIN: origin, SPOTIFY_CLIENT_ID: clientId },
    durableObjects: { PAIRINGS: { className: 'PairingSession', useSQLite: true } },
    ratelimits: { REQUEST_LIMITER: { namespace_id: '1001', simple: { limit: 120, period: 60 } },
      CREATE_LIMITER: { namespace_id: '1002', simple: { limit: 5, period: 60 } } },
    cf: false, logRequests: false,
    outboundService: () => new Response('External network disabled', { status: 503 }),
  });
  const mf = new Miniflare({ ...options, telemetry: { enabled: false } });
  const secret = nonce();
  const api = (path, body) => mf.dispatchFetch(origin + path, { method: 'POST', headers: { Authorization: 'Bearer ' + secret, 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
  try {
    const creation = await api('/v1/pairings', { clientId, stateNonce: nonce(), challenge: nonce() });
    assert.equal(creation.status, 201, 'session creation must work against actual Durable Object storage');
    const session = await creation.json();
    const landing = await mf.dispatchFetch(session.qrUrl); const cookie = landing.headers.get('Set-Cookie').split(';')[0];
    const begin = await mf.dispatchFetch(session.qrUrl + '/begin', { method: 'POST', headers: { Origin: origin, Cookie: cookie }, redirect: 'manual' });
    assert.equal(begin.status, 303);
    const authorization = new URL(begin.headers.get('Location'));
    assert.equal(authorization.origin, 'https://accounts.spotify.com');
    const callback = await mf.dispatchFetch(session.redirectUri + '?' + new URLSearchParams({ state: session.state, code: 'fixture-local-code' }), { headers: { Cookie: cookie }, redirect: 'manual' });
    assert.equal(callback.headers.get('Location'), '/pair/complete');
    const deliveries = await Promise.all([api(`/v1/pairings/${session.id}/redeem`), api(`/v1/pairings/${session.id}/redeem`)]);
    assert.deepEqual(deliveries.map(r => r.status).sort(), [200, 410]);
    const winner = deliveries.find(r => r.status === 200);
    assert.equal((await winner.json()).code, 'fixture-local-code');
    assert.equal((await api(`/v1/pairings/${session.id}/redeem`)).status, 410);
  } finally { await mf.dispose(); }
});
