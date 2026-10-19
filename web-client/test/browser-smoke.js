import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createClientServer } from '../server.js';

const SCRIPT_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const ONE_PIXEL_JPEG = '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAH/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAEFAqf/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAEDAQE/ASP/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAECAQE/ASP/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAY/Aqf/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAE/IV//2gAMAwEAAgADAAAAEP/EABQRAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQMBAT8QH//EABQRAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQIBAT8QH//EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAT8QH//Z';

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function listen(server) {
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  return server.address().port;
}

function connectCdp(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const pending = new Map();
    const events = [];
    let nextId = 0;
    const api = {
      events,
      send(method, params = {}, sessionId) {
        return new Promise((resolveCommand, rejectCommand) => {
          const id = ++nextId;
          pending.set(id, { resolve: resolveCommand, reject: rejectCommand });
          ws.send(JSON.stringify({ id, method, params, sessionId }));
        });
      },
      close() {
        ws.close();
      },
    };
    ws.onmessage = (event) => {
      const message = JSON.parse(event.data);
      if (message.id) {
        const command = pending.get(message.id);
        if (!command) return;
        pending.delete(message.id);
        if (message.error) command.reject(new Error(message.error.message));
        else command.resolve(message.result);
      } else {
        events.push(message);
      }
    };
    ws.onerror = () => reject(new Error('CDP WebSocket error'));
    ws.onopen = () => resolve(api);
  });
}

async function evaluate(cdp, sessionId, expression) {
  const result = await cdp.send('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  }, sessionId);
  if (result.exceptionDetails) throw new Error('页面 JavaScript 执行失败');
  return result.result.value;
}

async function waitFor(cdp, sessionId, expression, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await evaluate(cdp, sessionId, expression)) return;
    await delay(100);
  }
  throw new Error(`等待页面条件超时: ${expression}`);
}

async function main() {
  const profile = await mkdtemp(path.join(os.tmpdir(), 'efacego-chrome-'));
  const outputDirectory = path.join(SCRIPT_DIRECTORY, '..', 'test-output');
  await mkdir(outputDirectory, { recursive: true });
  const jpegPath = path.join(profile, 'smoke.jpg');
  await writeFile(jpegPath, Buffer.from(ONE_PIXEL_JPEG, 'base64'));

  const server = createClientServer();
  const port = await listen(server);
  let chrome;
  let cdp;
  try {
    chrome = spawn(CHROME, [
      '--headless=new',
      '--disable-gpu',
      '--no-first-run',
      '--no-default-browser-check',
      '--remote-debugging-port=0',
      `--user-data-dir=${profile}`,
      'about:blank',
    ], { stdio: ['ignore', 'ignore', 'pipe'] });

    const browserWs = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Chrome 未输出调试地址')), 15000);
      let stderr = '';
      chrome.stderr.on('data', (chunk) => {
        stderr += chunk.toString('utf8');
        const match = stderr.match(/DevTools listening on (ws:\/\/[^\s]+)/);
        if (match) {
          clearTimeout(timeout);
          resolve(match[1]);
        }
      });
      chrome.once('exit', (code) => reject(new Error(`Chrome 提前退出: ${code}`)));
    });

    cdp = await connectCdp(browserWs);
    const { targetInfos } = await cdp.send('Target.getTargets');
    const page = targetInfos.find((target) => target.type === 'page');
    const { sessionId } = await cdp.send('Target.attachToTarget', {
      targetId: page.targetId,
      flatten: true,
    });
    await cdp.send('Page.enable', {}, sessionId);
    await cdp.send('Runtime.enable', {}, sessionId);
    await cdp.send('Log.enable', {}, sessionId);
    await cdp.send('Page.navigate', { url: `http://127.0.0.1:${port}` }, sessionId);

    await waitFor(cdp, sessionId,
      "document.readyState === 'complete' && document.querySelector('h1')?.textContent.includes('eFaceGo')");
    const title = await evaluate(cdp, sessionId, 'document.title');

    await evaluate(cdp, sessionId, `(() => {
      const select = document.querySelector('#time-mode');
      select.value = 'custom';
      select.dispatchEvent(new Event('change', { bubbles: true }));
      return true;
    })()`);
    const customMode = await evaluate(cdp, sessionId, `(() => ({
      disabled: document.querySelector('#custom-time').disabled,
      hasValue: document.querySelector('#custom-time').value.length >= 16,
      hint: document.querySelector('#time-hint').textContent
    }))()`);

    const { root } = await cdp.send('DOM.getDocument', { depth: 1 }, sessionId);
    const { nodeId } = await cdp.send('DOM.querySelector', {
      nodeId: root.nodeId,
      selector: '#photo',
    }, sessionId);
    await cdp.send('DOM.setFileInputFiles', { nodeId, files: [jpegPath] }, sessionId);
    await waitFor(cdp, sessionId,
      "document.querySelector('#photo-name').textContent.includes('smoke.jpg') && !document.querySelector('.photo-preview-wrap').hidden");
    const photoName = await evaluate(cdp, sessionId,
      "document.querySelector('#photo-name').textContent");

    const { data } = await cdp.send('Page.captureScreenshot', {
      format: 'png',
      captureBeyondViewport: true,
    }, sessionId);
    const screenshot = path.join(outputDirectory, 'browser-smoke.png');
    await writeFile(screenshot, Buffer.from(data, 'base64'));

    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width: 390,
      height: 844,
      deviceScaleFactor: 1,
      mobile: true,
    }, sessionId);
    await delay(200);
    const mobileHasNoHorizontalOverflow = await evaluate(cdp, sessionId,
      'document.documentElement.scrollWidth <= window.innerWidth');
    const mobileCapture = await cdp.send('Page.captureScreenshot', {
      format: 'png',
      captureBeyondViewport: true,
    }, sessionId);
    const mobileScreenshot = path.join(outputDirectory, 'browser-smoke-mobile.png');
    await writeFile(mobileScreenshot, Buffer.from(mobileCapture.data, 'base64'));

    const errors = cdp.events.filter((event) =>
      event.method === 'Runtime.exceptionThrown'
      || (event.method === 'Log.entryAdded' && event.params.entry.level === 'error'));
    console.log(JSON.stringify({
      ok: errors.length === 0 && mobileHasNoHorizontalOverflow,
      title,
      customMode,
      photoName,
      screenshot,
      mobileScreenshot,
      mobileHasNoHorizontalOverflow,
      consoleErrors: errors.length,
      errorDetails: errors.map((event) => ({ method: event.method, params: event.params })),
      upstreamAttendanceRequests: 0,
    }, null, 2));
    if (errors.length) throw new Error(`页面出现 ${errors.length} 个错误`);
    if (!mobileHasNoHorizontalOverflow) throw new Error('手机宽度下出现横向溢出');
  } finally {
    if (cdp) {
      try {
        await cdp.send('Browser.close');
      } catch {
        // The browser may close its debugging socket before replying.
      }
      cdp.close();
    }
    if (chrome && chrome.exitCode === null) chrome.kill();
    await new Promise((resolve) => server.close(resolve));
    await delay(500);
    await rm(profile, {
      recursive: true,
      force: true,
      maxRetries: 8,
      retryDelay: 250,
    });
  }
}

await main();
