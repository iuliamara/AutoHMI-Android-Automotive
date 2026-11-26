// No HTTP logging, analytics, third-party page assets, token exchange or client secret.
export const TTL_MS = 300_000;
export const SCOPES = 'user-read-playback-state user-modify-playback-state';
const NONCE = /^[A-Za-z0-9_-]{43}$/;
const STATE = /^[A-Za-z0-9_-]{43}\.[A-Za-z0-9_-]{43}$/;
const CALLBACK = '/oauth/spotify/callback';
const encoder = new TextEncoder();
const securityHeaders = {
  'Cache-Control': 'no-store, max-age=0', Pragma: 'no-cache',
  'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
  'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; form-action 'self' https://accounts.spotify.com; frame-ancestors 'none'; base-uri 'none'",
};
export function json(value, status = 200, extra = {}) {
  return new Response(JSON.stringify(value), { status, headers: { ...securityHeaders, 'Content-Type': 'application/json', ...extra } });
}
function page(title, message, form = '', extra = {}) {
  return new Response('<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<title>AutoHMI · Spotify</title><style>body{font:18px system-ui;background:#0b131c;color:#eef5f8;margin:0;min-height:100vh;display:grid;place-items:center}main{max-width:32rem;padding:2rem}h1{font-size:2rem}p{line-height:1.6;color:#c3d0da}button{background:#71e0c1;color:#08281e;border:0;border-radius:2rem;padding:1rem 1.5rem;font:inherit;font-weight:600}small{display:block;margin-top:2rem;color:#a8bac6}</style>' +
    '<main><h1>' + title + '</h1><p>' + message + '</p>' + form + '<small>AutoHMI never asks for your Spotify password. Consent happens on Spotify.</small></main></html>',
    { headers: { ...securityHeaders, 'Content-Type': 'text/html; charset=utf-8', ...extra } });
}
function redirect(location, extra = {}) {
  return new Response(null, { status: 303, headers: { ...securityHeaders, Location: location, ...extra } });
}
export function nonce() {
  return btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32)))).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
}
export async function digest(value) {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(value))), b => b.toString(16).padStart(2, '0')).join('');
}
function equal(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let difference = 0;
  for (let i = 0; i < a.length; i++) difference |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return difference === 0;
}
function configuration(env, origin) {
  try {
    const canonical = new URL(env.PUBLIC_ORIGIN);
    return canonical.protocol === 'https:' && canonical.origin === origin &&
      canonical.href === canonical.origin + '/' && !canonical.username && !canonical.password &&
      /^[a-fA-F0-9]{32}$/.test(env.SPOTIFY_CLIENT_ID || '') && env.PAIRINGS;
  } catch { return false; }
}
function bearer(request) {
  const value = request.headers.get('Authorization') || '';
  return /^Bearer [A-Za-z0-9_-]{43}$/.test(value) ? value.slice(7) : null;
}
function cookieName(id) { return '__Host-autohmi_' + id; }
function cookie(request, id) {
  const name = cookieName(id) + '=';
  const values = (request.headers.get('Cookie') || '').split(';').map(s => s.trim()).filter(s => s.startsWith(name));
  return values.length === 1 && NONCE.test(values[0].slice(name.length)) ? values[0].slice(name.length) : null;
}
function cookieHeader(id, value, age = 300) {
  return cookieName(id) + '=' + value + '; Secure; HttpOnly; SameSite=Lax; Path=/; Max-Age=' + age;
}
async function body(request) {
  if (!request.headers.get('Content-Type')?.startsWith('application/json')) throw new Error('Invalid request');
  const reader = request.body?.getReader();
  if (!reader) throw new Error('Invalid request');
  let size = 0; const chunks = [];
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      size += next.value.length;
      if (size > 4096) { await reader.cancel(); throw new Error('Invalid request'); }
      chunks.push(next.value);
    }
    const bytes = new Uint8Array(size); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    return JSON.parse(new TextDecoder().decode(bytes));
  } finally { reader.releaseLock(); }
}
function stub(env, id) { return env.PAIRINGS.get(env.PAIRINGS.idFromName(id)); }
async function call(env, id, action, value = {}) {
  return stub(env, id).fetch(new Request('https://pairing.internal/' + action, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value),
  }));
}

export async function handle(request, env) {
  try {
    const url = new URL(request.url);
    if (!configuration(env, url.origin)) return json({ error: 'not_configured' }, 503);
    if (!env.REQUEST_LIMITER || !env.CREATE_LIMITER) return json({ error: 'not_configured' }, 503);
    // Hash the edge-provided address; neither address nor hash enters application logs/storage.
    const key = await digest(request.headers.get('CF-Connecting-IP') || 'unknown');
    if (!(await env.REQUEST_LIMITER.limit({ key })).success) return json({ error: 'rate_limited' }, 429, { 'Retry-After': '60' });
    if (request.method === 'GET' && url.pathname === '/health') return json({ ready: true });
    if (request.method === 'POST' && url.pathname === '/v1/pairings') {
      if (!(await env.CREATE_LIMITER.limit({ key })).success) return json({ error: 'rate_limited' }, 429, { 'Retry-After': '60' });
      const secret = bearer(request);
      if (!secret) return json({ error: 'unauthorized' }, 401);
      const input = await body(request);
      if (Object.keys(input).sort().join(',') !== 'challenge,clientId,stateNonce' ||
          input.clientId !== env.SPOTIFY_CLIENT_ID || !NONCE.test(input.challenge || '') || !NONCE.test(input.stateNonce || '')) {
        return json({ error: 'invalid_request' }, 400);
      }
      const id = nonce();
      const result = await call(env, id, 'init', { id, secretHash: await digest(secret), challenge: input.challenge, state: id + '.' + input.stateNonce });
      if (!result.ok) return json({ error: 'service_unavailable' }, 503);
      const created = await result.json();
      return json({ id, state: id + '.' + input.stateNonce, qrUrl: url.origin + '/pair/' + id,
        redirectUri: url.origin + CALLBACK, expiresAtMs: created.expiresAtMs, pollAfterMs: 3000 }, 201);
    }
    const api = /^\/v1\/pairings\/([A-Za-z0-9_-]{43})\/(status|redeem|cancel)$/.exec(url.pathname);
    if (api && request.method === 'POST') {
      const secret = bearer(request);
      if (!secret) return json({ error: 'unauthorized' }, 401);
      return call(env, api[1], api[2], { secretHash: await digest(secret) });
    }
    const mobile = /^\/pair\/([A-Za-z0-9_-]{43})(\/begin)?$/.exec(url.pathname);
    if (mobile && request.method === 'GET' && !mobile[2]) {
      const status = await call(env, mobile[1], 'available');
      if (!status.ok) return page('QR unavailable', 'This QR has expired or was already used. Generate a new QR in AutoHMI.');
      return page('Connect Spotify to AutoHMI', 'Continue only if you requested this QR on your own AutoHMI screen. You will approve access on Spotify.',
        '<form method="post" action="/pair/' + mobile[1] + '/begin"><button type="submit">Continue to Spotify</button></form>',
        { 'Set-Cookie': cookieHeader(mobile[1], cookie(request, mobile[1]) || nonce()) });
    }
    if (mobile && request.method === 'POST' && mobile[2]) {
      if (request.headers.get('Origin') !== url.origin) return json({ error: 'invalid_origin' }, 403);
      const phone = cookie(request, mobile[1]);
      if (!phone) return page('Cookies required', 'Allow cookies for this pairing page, then scan a newly generated QR.');
      const begun = await call(env, mobile[1], 'begin', { phoneHash: await digest(phone) });
      if (!begun.ok) return page('QR unavailable', 'Another browser may already be using this QR, or it expired. Generate a new QR in AutoHMI.');
      const value = await begun.json();
      const auth = new URL('https://accounts.spotify.com/authorize');
      auth.search = new URLSearchParams({ client_id: env.SPOTIFY_CLIENT_ID, response_type: 'code',
        redirect_uri: url.origin + CALLBACK, scope: SCOPES, state: value.state,
        code_challenge_method: 'S256', code_challenge: value.challenge, show_dialog: 'true' }).toString();
      return redirect(auth.href);
    }
    if (url.pathname === CALLBACK && request.method === 'GET') {
      const state = url.searchParams.get('state') || '';
      if (!STATE.test(state) || ['state', 'code', 'error'].some(k => url.searchParams.getAll(k).length > 1)) return redirect('/pair/problem');
      const id = state.split('.')[0]; const phone = cookie(request, id);
      const code = url.searchParams.get('code'); const error = url.searchParams.get('error');
      if (!phone || (!!code === !!error) || (code && (code.length > 2048 || /[\r\n]/.test(code)))) return redirect('/pair/problem');
      const completed = await call(env, id, 'callback', { state, phoneHash: await digest(phone), code,
        issue: error ? (error === 'access_denied' ? 'declined' : 'authorization_failed') : null });
      // Strip Spotify's code/state query immediately; no data is echoed to HTML or logs.
      return redirect(completed.ok ? '/pair/complete' : '/pair/problem', { 'Set-Cookie': cookieHeader(id, '', 0) });
    }
    if (url.pathname === '/pair/complete' && request.method === 'GET') return page('Return to AutoHMI', 'Your response was received. AutoHMI will finish connecting. You can close this page.');
    if (url.pathname === '/pair/problem' && request.method === 'GET') return page('Pairing could not finish', 'The request expired, was cancelled, or could not be verified. Generate a new QR in AutoHMI.');
    return json({ error: 'not_found' }, 404);
  } catch {
    // Deliberately omit exception/request details: they could contain callback credentials.
    return json({ error: 'service_unavailable' }, 503);
  }
}
export default { fetch: handle };

/** One SQLite-backed object per server-generated public handle. No access/refresh tokens. */
export class PairingSession {
  constructor(ctx, env, now = Date.now) { this.storage = ctx.storage; this.now = now; }
  async fetch(request) {
    const action = new URL(request.url).pathname.slice(1);
    const input = await request.json();
    return this.storage.transaction(async storage => {
      let current = await storage.get('session');
      if (action === 'init') {
        if (current) return json({ error: 'conflict' }, 409);
        const expiresAtMs = this.now() + TTL_MS;
        await storage.put('session', { ...input, expiresAtMs, status: 'waiting', phoneHash: null, code: null });
        // SQLite includes top-level storage calls in this transaction (txn's alarm API varies).
        await this.storage.setAlarm(expiresAtMs);
        return json({ expiresAtMs }, 201);
      }
      if (!current || this.now() >= current.expiresAtMs) {
        await storage.delete('session');
        return json({ status: 'expired' }, 410);
      }
      if (['cancelled', 'redeemed'].includes(current.status)) return json({ status: current.status }, 410);
      if (action === 'available') return current.status === 'waiting' || current.status === 'authorizing' ? json({ available: true }) : json({ status: 'used' }, 410);
      if (action === 'begin') {
        if (!['waiting', 'authorizing'].includes(current.status) || (current.phoneHash && !equal(current.phoneHash, input.phoneHash))) return json({ error: 'used' }, 409);
        current = { ...current, status: 'authorizing', phoneHash: input.phoneHash };
        await storage.put('session', current);
        return json({ state: current.state, challenge: current.challenge });
      }
      if (action === 'callback') {
        if (current.status !== 'authorizing' || !equal(current.state, input.state) || !equal(current.phoneHash, input.phoneHash)) return json({ error: 'invalid_response' }, 400);
        await storage.put('session', { ...current, status: input.issue ? 'denied' : 'ready', code: input.code, issue: input.issue });
        return json({ received: true });
      }
      if (!equal(current.secretHash, input.secretHash)) return json({ error: 'unauthorized' }, 401);
      if (action === 'cancel') {
        await storage.put('session', { status: 'cancelled', expiresAtMs: current.expiresAtMs });
        return json({ status: 'cancelled' });
      }
      if (action === 'status') return json({ status: current.status === 'authorizing' ? 'waiting' : current.status, issue: current.issue || null });
      if (action === 'redeem') {
        if (current.status !== 'ready') return json({ error: 'not_ready' }, 409);
        const value = { code: current.code, state: current.state };
        // Commit deletion before emitting credentials. Replayed/lost deliveries require a new QR.
        await storage.put('session', { status: 'redeemed', expiresAtMs: current.expiresAtMs });
        return json(value);
      }
      return json({ error: 'not_found' }, 404);
    });
  }
  async alarm() { await this.storage.deleteAll(); }
}
