const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');

// Reads one SSE response. Events are collected as they arrive; `closed` resolves when the server ends the stream.
async function openStream(url) {
  const controller = new AbortController();
  const response = await fetch(url, { headers: { Accept: 'text/event-stream' }, signal: controller.signal });
  const events = []; const waiters = []; let done = false;
  const wake = () => waiters.splice(0).forEach(resolve => resolve());
  const closed = (async () => {
    if (response.status !== 200) return;
    const decoder = new TextDecoder(); let buffer = '';
    try {
      for await (const chunk of response.body) {
        buffer += decoder.decode(chunk, { stream: true });
        let end;
        while ((end = buffer.indexOf('\n\n')) >= 0) {
          const event = {};
          for (const line of buffer.slice(0, end).split('\n')) {
            const colon = line.indexOf(':');
            if (colon > 0) event[line.slice(0, colon)] = line.slice(colon + 1).trimStart();
          }
          buffer = buffer.slice(end + 2);
          if (event.event) { events.push({ name: event.event, id: event.id, data: JSON.parse(event.data) }); wake(); }
        }
      }
    } catch { /* aborted by this test */ }
  })().finally(() => { done = true; wake(); });
  const next = async (matches, timeout = 10000) => {
    const until = Date.now() + timeout; let seen = 0;
    while (true) {
      for (; seen < events.length; seen++) if (matches(events[seen])) return events[seen];
      const remaining = until - Date.now();
      if (done || remaining <= 0) return null;
      await Promise.race([new Promise(resolve => waiters.push(resolve)), new Promise(resolve => setTimeout(resolve, remaining))]);
    }
  };
  const ended = async (timeout = 10000) => Promise.race([closed.then(() => true), new Promise(resolve => setTimeout(() => resolve(false), timeout))]);
  return { status: response.status, contentType: response.headers.get('content-type') || '', next, ended, close: () => controller.abort() };
}

// Instance A is `live`, instance B is `live2`. Writes go to A and the viewer is connected to B.
async function liveRealtime(ctx) {
  const req = ctx.request.bind(ctx); const r = ctx.runtime;
  const create = { title: 'Realtime broadcast', scheduledAt: new Date(Date.now() + 60000).toISOString(),
    channelArn: 'arn:aws:ivs:ap-northeast-2:000000000000:channel/realtime-fixture', playbackUrl: 'https://stub.live-video.net/realtime-fixture.m3u8' };
  const broadcast = (await req('realtime-broadcast-create', 'live', 'POST', '/v1/admin/broadcasts', { token: ctx.seller, body: create,
    headers: { 'Idempotency-Key': 'realtime-broadcast' }, status: 201 })).data;
  const link = (await req('realtime-broadcast-link', 'live', 'POST', `/v1/admin/broadcasts/${broadcast.id}/products`,
    { token: ctx.seller, body: { productId: ctx.products[0], expectedVersion: broadcast.version }, status: 201 })).data;
  const base = `/v1/broadcasts/${broadcast.id}`;
  const firstKey = randomUUID();
  let firstLike;
  await req('realtime-sse-before-start-409', 'live2', 'GET', base + '/events', { headers: { Accept: 'text/event-stream' }, status: 409 });
  await req('realtime-broadcast-start', 'live', 'POST', `/v1/admin/broadcasts/${broadcast.id}/start?expectedVersion=${link.broadcastVersion}`, { token: ctx.seller });

  const stream = await openStream(r.urls.live2 + base + '/events');
  try {
    ctx.check('realtime-sse-b-connected', [stream.status, stream.contentType.startsWith('text/event-stream')], [200, true]);
    const ready = await stream.next(event => event.name === 'stream.ready');
    ctx.check('realtime-sse-b-ready', ready?.data, { broadcastId: broadcast.id });

    const chat = (await req('realtime-chat-write-a', 'live', 'POST', base + '/chats', { token: ctx.a, body: { content: '두 인스턴스 채팅' }, status: 201 })).data;
    const delivered = await stream.next(event => event.name === 'chat.created');
    ctx.check('realtime-chat-a-reaches-b', [delivered?.id, delivered?.data], [String(chat.messageId), chat]);
    const history = (await req('realtime-chat-history-b', 'live2', 'GET', base + '/chats')).data;
    ctx.check('realtime-chat-history-b-has-message', history.map(message => message.messageId), [chat.messageId]);
    await req('realtime-chat-anonymous-401', 'live2', 'POST', base + '/chats', { body: { content: 'x' }, status: 401 });

    const mine = (await req('realtime-like-mine-initial', 'live', 'GET', base + '/likes/mine', { token: ctx.a })).data;
    ctx.check('realtime-like-mine-initial-state', mine, { broadcastId: broadcast.id, liked: false, stateVersion: 0, total: 0, version: 0 });
    const intent = (token, liked, key = randomUUID()) => ({ token, body: { liked }, headers: { 'Idempotency-Key': key } });
    await req('realtime-like-anonymous-401', 'live2', 'PUT', base + '/likes/mine', { ...intent(undefined, true), status: 401 });
    firstLike = (await req('realtime-like-a-1', 'live', 'PUT', base + '/likes/mine', intent(ctx.a, true, firstKey))).data;
    await req('realtime-like-b-2', 'live2', 'PUT', base + '/likes/mine', intent(ctx.b, true));
    const noop = (await req('realtime-like-a-noop', 'live', 'PUT', base + '/likes/mine', intent(ctx.a, true))).data;
    const replay = (await req('realtime-like-a-replay-b', 'live2', 'PUT', base + '/likes/mine', intent(ctx.a, true, firstKey))).data;
    ctx.check('realtime-like-replay-original-response', replay, firstLike);
    await req('realtime-like-key-conflict-409', 'live2', 'PUT', base + '/likes/mine', { ...intent(ctx.a, false, firstKey), status: 409 });
    const totalA = (await req('realtime-likes-total-a', 'live', 'GET', base + '/likes')).data;
    const totalB = (await req('realtime-likes-total-b', 'live2', 'GET', base + '/likes')).data;
    ctx.check('realtime-likes-same-total-noop', [noop.total, noop.stateVersion, noop.version, totalA, totalB],
      [2, 1, 2, { broadcastId: broadcast.id, total: 2, version: 2 }, { broadcastId: broadcast.id, total: 2, version: 2 }]);
    const likes = await stream.next(event => event.name === 'likes.updated' && event.data.total === 2 && event.data.version === 2);
    ctx.check('realtime-likes-updated-reaches-b', likes?.data, { broadcastId: broadcast.id, total: 2, version: 2 });
    const cancelled = (await req('realtime-like-a-cancel', 'live', 'PUT', base + '/likes/mine', intent(ctx.a, false))).data;
    ctx.check('realtime-like-cancel-personal-and-aggregate', cancelled,
      { broadcastId: broadcast.id, liked: false, stateVersion: 2, total: 1, version: 3 });
    const decrease = await stream.next(event => event.name === 'likes.updated' && event.data.total === 1 && event.data.version === 3);
    ctx.check('realtime-likes-decreasing-sse-reaches-b', decrease?.data, { broadcastId: broadcast.id, total: 1, version: 3 });

    const ended = (await req('realtime-broadcast-end-a', 'live', 'POST', `/v1/admin/broadcasts/${broadcast.id}/end`, { token: ctx.seller })).data;
    const notice = await stream.next(event => event.name === 'broadcast.ended');
    ctx.check('realtime-end-a-reaches-b', [notice?.data.broadcastId, new Date(notice?.data.endedAt).getTime()], [broadcast.id, new Date(ended.endedAt).getTime()]);
    ctx.check('realtime-end-a-closes-b', await stream.ended());
  } finally { stream.close(); }

  await req('realtime-sse-after-end-409', 'live2', 'GET', base + '/events', { headers: { Accept: 'text/event-stream' }, status: 409 });
  await req('realtime-chat-after-end-409', 'live2', 'POST', base + '/chats', { token: ctx.a, body: { content: '종료 후' }, status: 409 });
  await req('realtime-like-after-end-409', 'live2', 'PUT', base + '/likes/mine', { token: ctx.a, body: { liked: true }, headers: { 'Idempotency-Key': randomUUID() }, status: 409 });
  const endedReplay = (await req('realtime-like-replay-after-end', 'live2', 'PUT', base + '/likes/mine', { token: ctx.a, body: { liked: true }, headers: { 'Idempotency-Key': firstKey } })).data;
  ctx.check('realtime-like-ended-replay-preserved', endedReplay, firstLike);
  const after = (await req('realtime-likes-total-after-end', 'live2', 'GET', base + '/likes')).data;
  assert.equal(Number.isSafeInteger(broadcast.id), true);
  ctx.check('realtime-likes-kept-and-stored-after-end', [after.total, after.version,
    r.sql('live', `SELECT total FROM broadcast_like_aggregate WHERE broadcast_id = ${broadcast.id}`),
    r.sql('live', `SELECT version FROM broadcast_like_aggregate WHERE broadcast_id = ${broadcast.id}`),
    r.sql('live', `SELECT COUNT(*) FROM broadcast_member_like WHERE broadcast_id = ${broadcast.id} AND liked = true`),
    r.sql('live', `SELECT COUNT(*) FROM broadcast_like_request WHERE broadcast_id = ${broadcast.id}`)], [1, 3, '1', '3', '1', '4']);
}
module.exports = { liveRealtime };
