import test from 'node:test';
import assert from 'node:assert/strict';
import { handle, PairingSession, TTL_MS, SCOPES, nonce } from '../src/worker.mjs';

// Deterministic clock and transactional storage; no Spotify/network/account interaction.
class Storage {
  data = new Map(); queue = Promise.resolve(); alarmAt = null;
  async get(key) { return structuredClone(this.data.get(key)); }
  async put(key, value) { this.data.set(key, structuredClone(value)); }
  async delete(key) { this.data.delete(key); }
  async deleteAll() { this.data.clear(); }
  async setAlarm(value) { this.alarmAt = value; }
  transaction(fn) {
    const result = this.queue.then(() => fn(this));
    this.queue = result.catch(() => {});
    return result;
  }
}
function fixture() {
  let time = 1_800_000_000_000;
  const objects = new Map();
  const env = { PUBLIC_ORIGIN: 'https://relay.test.invalid', SPOTIFY_CLIENT_ID: '0123456789abcdef0123456789abcdef',
    REQUEST_LIMITER: { limit: async () => ({ success: true }) }, CREATE_LIMITER: { limit: async () => ({ success: true }) },
    PAIRINGS: { idFromName: value => value, get(id) {
      if (!objects.has(id)) { const storage = new Storage(); objects.set(id, new PairingSession({ storage }, {}, () => time)); }
      return objects.get(id);
    } } };
  const secret = nonce(); const stateNonce = nonce(); const challenge = nonce();
  const request = (path, { method = 'GET', headers = {}, body } = {}) => handle(new Request(env.PUBLIC_ORIGIN + path, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
  }), env);
  const api = (path, secretValue = secret, body) => request(path, { method: 'POST', body,
    headers: { Authorization: 'Bearer ' + secretValue, 'Content-Type': 'application/json' } });
  async function create() {
    const result = await api('/v1/pairings', secret, { clientId: env.SPOTIFY_CLIENT_ID, challenge, stateNonce });
    assert.equal(result.status, 201); return result.json();
  }
  async function begin(session) {
    const landing = await request('/pair/' + session.id);
    const cookie = landing.headers.get('Set-Cookie').split(';')[0];
    const response = await request('/pair/' + session.id + '/begin', { method: 'POST', headers: { Cookie: cookie, Origin: env.PUBLIC_ORIGIN } });
    assert.equal(response.status, 303);
    return { cookie, authorization: new URL(response.headers.get('Location')) };
  }
  const callback = (session, cookie, { state = session.state, code = 'fixture-authorization-code', error } = {}) =>
    request('/oauth/spotify/callback?' + new URLSearchParams(error ? { state, error } : { state, code }), { headers: { Cookie: cookie } });
  return { env, objects, request, api, secret, stateNonce, challenge, create, begin, callback, advance: delta => { time += delta; } };
}

test('complete PKCE relay delivers code only once to the initiating secret', async () => {
  const f = fixture(); const s = await f.create();
  assert.equal(s.qrUrl, f.env.PUBLIC_ORIGIN + '/pair/' + s.id);
  assert.equal(new URL(s.qrUrl).search, ''); assert.equal(s.state, s.id + '.' + f.stateNonce);
  const { cookie, authorization } = await f.begin(s);
  assert.equal(authorization.origin, 'https://accounts.spotify.com');
  assert.equal(authorization.pathname, '/authorize');
  assert.equal(authorization.searchParams.get('scope'), SCOPES);
  assert.equal(authorization.searchParams.get('code_challenge_method'), 'S256');
  assert.equal(authorization.searchParams.get('code_challenge'), f.challenge);
  assert.equal(authorization.searchParams.get('redirect_uri'), s.redirectUri);
  assert.equal(authorization.searchParams.has('code_verifier'), false);
  const result = await f.callback(s, cookie);
  assert.equal(result.headers.get('Location'), '/pair/complete');
  assert.match(result.headers.get('Set-Cookie'), /Max-Age=0/);
  const status = await f.api('/v1/pairings/' + s.id + '/status');
  assert.equal(await status.text(), '{"status":"ready","issue":null}');
  const delivery = await f.api('/v1/pairings/' + s.id + '/redeem');
  assert.deepEqual(await delivery.json(), { code: 'fixture-authorization-code', state: s.state });
  assert.deepEqual(f.objects.get(s.id).storage.data.get('session'), { status: 'redeemed', expiresAtMs: s.expiresAtMs });
  assert.equal((await f.api('/v1/pairings/' + s.id + '/redeem')).status, 410);
});
test('wrong or missing session secret cannot poll, redeem or cancel', async () => {
  const f = fixture(); const s = await f.create();
  for (const action of ['status', 'redeem', 'cancel']) {
    assert.equal((await f.api(`/v1/pairings/${s.id}/${action}`, nonce())).status, 401);
    assert.equal((await f.request(`/v1/pairings/${s.id}/${action}`, { method: 'POST' })).status, 401);
  }
  assert.equal(f.objects.get(s.id).storage.data.get('session').status, 'waiting');
});
test('OAuth state mismatch and duplicate callback fields do not bind a code', async () => {
  const f = fixture(); const s = await f.create(); const { cookie } = await f.begin(s);
  for (const path of [
    '/oauth/spotify/callback?' + new URLSearchParams({ state: s.id + '.' + nonce(), code: 'fixture-code' }),
    '/oauth/spotify/callback?' + new URLSearchParams({ state: s.state, code: 'fixture-code' }) + '&state=' + s.state,
    '/oauth/spotify/callback?' + new URLSearchParams({ state: s.state, code: 'fixture-code', error: 'access_denied' }),
  ]) assert.equal((await f.request(path, { headers: { Cookie: cookie } })).headers.get('Location'), '/pair/problem');
  assert.equal(f.objects.get(s.id).storage.data.get('session').code, null);
});
test('phone cookie binding prevents a second browser from substituting a response', async () => {
  const f = fixture(); const s = await f.create(); const first = await f.begin(s);
  const landing = await f.request('/pair/' + s.id);
  const other = landing.headers.get('Set-Cookie').split(';')[0];
  assert.notEqual(other, first.cookie);
  assert.equal((await f.callback(s, other)).headers.get('Location'), '/pair/problem');
  assert.equal((await f.callback(s, '')).headers.get('Location'), '/pair/problem');
  assert.equal((await f.callback(s, first.cookie)).headers.get('Location'), '/pair/complete');
});
test('link previews do not reserve a session and cross-site begin fails', async () => {
  const f = fixture(); const s = await f.create();
  await f.request('/pair/' + s.id);
  assert.equal(f.objects.get(s.id).storage.data.get('session').phoneHash, null);
  const result = await f.request('/pair/' + s.id + '/begin', { method: 'POST', headers: { Origin: 'https://attacker.invalid' } });
  assert.equal(result.status, 403);
});
test('expiry clears data after five minutes even if the alarm has not fired', async () => {
  const f = fixture(); const s = await f.create(); const { cookie } = await f.begin(s);
  assert.equal(f.objects.get(s.id).storage.alarmAt, s.expiresAtMs);
  f.advance(TTL_MS);
  assert.equal((await f.callback(s, cookie)).headers.get('Location'), '/pair/problem');
  assert.equal((await f.api('/v1/pairings/' + s.id + '/status')).status, 410);
  assert.equal(f.objects.get(s.id).storage.data.size, 0);
});
test('cancellation erases credentials and rejects later callback and redemption', async () => {
  const f = fixture(); const s = await f.create(); const { cookie } = await f.begin(s);
  assert.equal((await f.api('/v1/pairings/' + s.id + '/cancel')).status, 200);
  assert.deepEqual(f.objects.get(s.id).storage.data.get('session'), { status: 'cancelled', expiresAtMs: s.expiresAtMs });
  assert.equal((await f.callback(s, cookie)).headers.get('Location'), '/pair/problem');
  assert.equal((await f.api('/v1/pairings/' + s.id + '/redeem')).status, 410);
  await f.objects.get(s.id).alarm(); assert.equal(f.objects.get(s.id).storage.data.size, 0);
});
test('concurrent redemption has one winner and callback cannot be replayed', async () => {
  const f = fixture(); const s = await f.create(); const { cookie } = await f.begin(s);
  await f.callback(s, cookie);
  assert.equal((await f.callback(s, cookie)).headers.get('Location'), '/pair/problem');
  const results = await Promise.all([f.api('/v1/pairings/' + s.id + '/redeem'), f.api('/v1/pairings/' + s.id + '/redeem')]);
  assert.deepEqual(results.map(r => r.status).sort(), [200, 410]);
});
test('declining authorization exposes only sanitized status, not Spotify error text', async () => {
  const f = fixture(); const s = await f.create(); const { cookie } = await f.begin(s);
  await f.callback(s, cookie, { error: 'access_denied' });
  const response = await f.api('/v1/pairings/' + s.id + '/status');
  assert.deepEqual(await response.json(), { status: 'denied', issue: 'declined' });
  assert.equal((await f.api('/v1/pairings/' + s.id + '/redeem')).status, 409);
});
test('rate limits, invalid client and unconfigured relay fail closed', async () => {
  const f = fixture();
  assert.equal((await f.api('/v1/pairings', f.secret, { clientId: 'bad', challenge: f.challenge, stateNonce: f.stateNonce })).status, 400);
  f.env.CREATE_LIMITER.limit = async () => ({ success: false });
  const limited = await f.api('/v1/pairings', f.secret, {});
  assert.equal(limited.status, 429); assert.equal(limited.headers.get('Retry-After'), '60');
  f.env.PUBLIC_ORIGIN = '';
  assert.equal((await handle(new Request('https://relay.test.invalid/v1/pairings'), f.env)).status, 503);
});
test('pages and redirects prohibit caching, referrers, embedding and third-party scripts', async () => {
  const f = fixture(); const s = await f.create();
  for (const path of ['/pair/' + s.id, '/pair/complete', '/pair/problem']) {
    const response = await f.request(path); const html = await response.text();
    assert.match(response.headers.get('Cache-Control'), /no-store/);
    assert.equal(response.headers.get('Referrer-Policy'), 'no-referrer');
    assert.match(response.headers.get('Content-Security-Policy'), /default-src 'none'/);
    assert.equal(response.headers.get('Access-Control-Allow-Origin'), null);
    assert.doesNotMatch(html, /<script|<img|code_verifier|fixture-authorization-code|access_token|refresh_token/);
    assert.equal(html.includes(f.secret), false); assert.equal(s.qrUrl.includes(f.secret), false);
  }
});
