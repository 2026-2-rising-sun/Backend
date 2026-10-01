// Test-only transport: select a Prism example, then optionally hold all response headers.
// Never log headers/body/credentials; only timing and HTTP status evidence is exposed.
const http = require('node:http');
const crypto = require('node:crypto');
const upstream = new URL(process.env.UPSTREAM_URL);
const controlToken = process.env.PROXY_CONTROL_TOKEN;
if (upstream.protocol !== 'http:' || !controlToken || controlToken.length < 32) throw new Error('Invalid test proxy configuration');
let config = { prefer: 'code=200, example=testNormal', delayMs: 0, generation: 0 };
let events = [];
const json = (res, status, body) => {
  res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body));
};
function authorized(req) {
  const actual = Buffer.from(req.headers['x-proxy-control'] || ''); const expected = Buffer.from(controlToken);
  return actual.length === expected.length && crypto.timingSafeEqual(actual, expected);
}
const server = http.createServer(async (req, res) => {
  if (req.url === '/__health' && req.method === 'GET') return json(res, 200, { ready: true });
  if (req.url.startsWith('/__')) {
    if (!authorized(req)) return json(res, 401, { error: 'control authentication required' });
    if (req.url === '/__stats' && req.method === 'GET') return json(res, 200, { generation: config.generation, events });
    if (req.url === '/__control' && req.method === 'POST') {
      try {
        let text = '';
        for await (const chunk of req) { text += chunk; if (text.length > 4096) throw new Error('control payload too large'); }
        const value = JSON.parse(text);
        if (!/^code=(200|404|503), example=[A-Za-z]+$/.test(value.prefer)
          || !Number.isInteger(value.delayMs) || value.delayMs < 0 || value.delayMs > 10000) throw new Error('invalid control');
        config = { prefer: value.prefer, delayMs: value.delayMs, generation: config.generation + 1 };
        events = [];
        return json(res, 200, { generation: config.generation });
      } catch { return json(res, 400, { error: 'invalid control request' }); }
    }
    return json(res, 404, { error: 'unknown control path' });
  }
  if (req.method !== 'GET') return json(res, 405, { error: 'test proxy only forwards GET' });
  const current = { ...config }; const started = Date.now(); let timer;
  const event = { generation: current.generation, startedAt: new Date(started).toISOString(), method: req.method,
    path: req.url, prefer: current.prefer, delayMs: current.delayMs, upstreamStatus: null,
    upstreamBodyCompleteMs: null, headersSentMs: null, clientClosedBeforeHeaders: false, clientClosedMs: null };
  events.push(event); if (events.length > 256) events.shift();
  res.on('close', () => {
    event.clientClosedMs = Date.now() - started;
    event.clientClosedBeforeHeaders = !res.headersSent;
    if (timer) clearTimeout(timer);
  });
  const headers = { ...req.headers, host: upstream.host, prefer: current.prefer };
  delete headers['x-proxy-control']; delete headers.connection;
  const outgoing = http.request(new URL(req.url, upstream), { method: 'GET', headers }, response => {
    event.upstreamStatus = response.statusCode;
    const chunks = []; let length = 0;
    response.on('data', chunk => {
      length += chunk.length;
      if (length > 1024 * 1024) { outgoing.destroy(new Error('fixture body exceeds limit')); return; }
      chunks.push(chunk);
    });
    response.on('end', () => {
      event.upstreamBodyCompleteMs = Date.now() - started;
      const bytes = Buffer.concat(chunks);
      const send = () => {
        if (res.destroyed) return;
        const resultHeaders = { ...response.headers, 'content-length': String(bytes.length) };
        delete resultHeaders.connection; delete resultHeaders['transfer-encoding']; delete resultHeaders['keep-alive'];
        event.headersSentMs = Date.now() - started;
        res.writeHead(response.statusCode, resultHeaders); res.end(bytes);
      };
      if (!res.destroyed) { if (current.delayMs) timer = setTimeout(send, current.delayMs); else send(); }
    });
    response.on('error', () => { if (!res.destroyed) json(res, 502, { error: 'fixture upstream response failed' }); });
  });
  outgoing.setTimeout(8000, () => outgoing.destroy(new Error('fixture upstream timeout')));
  outgoing.on('error', () => { event.upstreamFailed = true; if (!res.destroyed) json(res, 502, { error: 'fixture upstream failed' }); });
  outgoing.end();
});
server.listen(Number(process.env.PORT || 8080), '0.0.0.0');
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => server.close(() => process.exit(0)));
