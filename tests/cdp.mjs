import fs from 'node:fs';

export async function connectFrame() {
  const pages = await (await fetch('http://127.0.0.1:9222/json')).json();
  const page = pages.find((p) => p.url === 'https://frame.local/index.html');
  if (!page) throw new Error('Start a debug build of Still and forward its WebView to port 9222.');
  const socket = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.onopen = resolve;
    socket.onerror = reject;
  });
  let sequence = 0;
  const pending = new Map(),
    errors = [];
  socket.onmessage = (event) => {
    const data = JSON.parse(event.data);
    if (data.method === 'Runtime.exceptionThrown') errors.push(data.params.exceptionDetails.text);
    const request = pending.get(data.id);
    if (!request) return;
    pending.delete(data.id);
    clearTimeout(request.timer);
    if (data.error) request.reject(new Error(JSON.stringify(data.error)));
    else request.resolve(data.result);
  };
  function send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++sequence;
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error('CDP timeout: ' + method));
      }, 20000);
      pending.set(id, { resolve, reject, timer });
      socket.send(JSON.stringify({ id, method, params }));
    });
  }
  async function evaluate(expression) {
    const response = await send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    if (response.exceptionDetails) throw new Error(JSON.stringify(response.exceptionDetails));
    return response.result.value;
  }
  async function until(expression, timeout = 30000) {
    const end = Date.now() + timeout;
    do {
      const result = await evaluate(expression);
      if (result) return result;
      await new Promise((resolve) => setTimeout(resolve, 100));
    } while (Date.now() < end);
    throw new Error('Timed out: ' + expression);
  }
  await send('Runtime.enable');
  return {
    send,
    evaluate,
    until,
    errors,
    close: () => socket.close(),
    screenshot: async (path) => {
      const value = await send('Page.captureScreenshot', { format: 'png' });
      fs.writeFileSync(path, Buffer.from(value.data, 'base64'));
    },
  };
}
