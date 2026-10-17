// Project-local debug helper. The release APK disables WebView debugging.
// Usage: node scripts/frame_debug.mjs 'document.title' [screenshot.png]
import fs from 'node:fs';
const pages = await (await fetch('http://127.0.0.1:9222/json')).json();
const page = pages.find((p) => p.url === 'https://frame.local/index.html');
if (!page) throw new Error('No Still WebView is connected');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  ws.onopen = resolve;
  ws.onerror = reject;
});
let seq = 0;
const pending = new Map();
ws.onmessage = (event) => {
  const data = JSON.parse(event.data);
  if (pending.has(data.id)) {
    const p = pending.get(data.id);
    pending.delete(data.id);
    clearTimeout(p.timer);
    data.error ? p.reject(new Error(JSON.stringify(data.error))) : p.resolve(data.result);
  }
};
function send(method, params) {
  return new Promise((resolve, reject) => {
    const id = ++seq,
      timer = setTimeout(() => reject(new Error('CDP timeout')), 20000);
    pending.set(id, { resolve, reject, timer });
    ws.send(JSON.stringify({ id, method, params }));
  });
}
try {
  const result = await send('Runtime.evaluate', {
    expression: process.argv[2] || 'document.title',
    returnByValue: true,
    awaitPromise: true,
  });
  if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
  if (result.result.value !== undefined) console.log(JSON.stringify(result.result.value, null, 2));
  if (process.argv[3]) {
    const shot = await send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(process.argv[3], Buffer.from(shot.data, 'base64'));
  }
} finally {
  ws.close();
}
