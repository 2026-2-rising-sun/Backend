const assert = require('node:assert/strict');

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

    await req('realtime-like-a-1', 'live', 'POST', base + '/likes', { token: ctx.a });
    await req('realtime-like-b-2', 'live2', 'POST', base + '/likes', { token: ctx.b });
    const third = (await req('realtime-like-a-3', 'live', 'POST', base + '/likes', { token: ctx.a })).data;
    const totalA = (await req('realtime-likes-total-a', 'live', 'GET', base + '/likes')).data;
    const totalB = (await req('realtime-likes-total-b', 'live2', 'GET', base + '/likes')).data;
    ctx.check('realtime-likes-same-total', [third.total, totalA.total, totalB.total], [3, 3, 3]);
    const likes = await stream.next(event => event.name === 'likes.updated' && event.data.total === 3);
    ctx.check('realtime-likes-updated-reaches-b', likes?.data, { broadcastId: broadcast.id, total: 3 });

    const ended = (await req('realtime-broadcast-end-a', 'live', 'POST', `/v1/admin/broadcasts/${broadcast.id}/end`, { token: ctx.seller })).data;
    const notice = await stream.next(event => event.name === 'broadcast.ended');
    ctx.check('realtime-end-a-reaches-b', [notice?.data.broadcastId, new Date(notice?.data.endedAt).getTime()], [broadcast.id, new Date(ended.endedAt).getTime()]);
    ctx.check('realtime-end-a-closes-b', await stream.ended());
  } finally { stream.close(); }

  await req('realtime-sse-after-end-409', 'live2', 'GET', base + '/events', { headers: { Accept: 'text/event-stream' }, status: 409 });
  await req('realtime-chat-after-end-409', 'live2', 'POST', base + '/chats', { token: ctx.a, body: { content: '종료 후' }, status: 409 });
  await req('realtime-like-after-end-409', 'live2', 'POST', base + '/likes', { token: ctx.a, status: 409 });
  const after = (await req('realtime-likes-total-after-end', 'live2', 'GET', base + '/likes')).data;
  assert.equal(Number.isSafeInteger(broadcast.id), true);
  ctx.check('realtime-likes-kept-and-stored-after-end', [after.total,
    r.sql('live', `SELECT total FROM broadcast_like_snapshot WHERE broadcast_id = ${broadcast.id}`)], [3, '3']);
}
module.exports = { liveRealtime };
