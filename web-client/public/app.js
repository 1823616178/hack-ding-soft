const elements = {
  form: document.querySelector('#client-form'),
  baseUrl: document.querySelector('#base-url'),
  userName: document.querySelector('#user-name'),
  pwd: document.querySelector('#password'),
  timeMode: document.querySelector('#time-mode'),
  customTime: document.querySelector('#custom-time'),
  timeHint: document.querySelector('#time-hint'),
  gps: document.querySelector('#gps'),
  lat: document.querySelector('#lat'),
  lng: document.querySelector('#lng'),
  usedMap: document.querySelector('#used-map'),
  currSysVer: document.querySelector('#curr-sys-ver'),
  photo: document.querySelector('#photo'),
  photoName: document.querySelector('#photo-name'),
  photoPreview: document.querySelector('#photo-preview'),
  authorized: document.querySelector('#authorized'),
  loginButton: document.querySelector('#login-button'),
  punchButton: document.querySelector('#punch-button'),
  status: document.querySelector('#status'),
  results: document.querySelector('#results'),
};

let photoBase64 = '';
let busy = false;

function setStatus(message, kind = 'info') {
  elements.status.textContent = message;
  elements.status.className = `status ${kind}`;
  elements.status.hidden = false;
}

function setBusy(value, message = '') {
  busy = value;
  elements.loginButton.disabled = value;
  elements.punchButton.disabled = value;
  if (value) setStatus(message || '正在请求，请稍候…', 'info');
}

function customTimeValue() {
  const value = elements.customTime.value;
  if (!value) return '';
  const [date, time] = value.split('T');
  return `${date} ${time.length === 5 ? `${time}:00` : time}`;
}

function payload({ includePhoto = false } = {}) {
  const value = {
    baseUrl: elements.baseUrl.value.trim(),
    userName: elements.userName.value,
    pwd: elements.pwd.value,
    timeMode: elements.timeMode.value,
    customTime: customTimeValue(),
    gps: elements.gps.value,
    lat: elements.lat.value,
    lng: elements.lng.value,
    usedMap: elements.usedMap.value,
    currSysVer: elements.currSysVer.value,
    authorized: elements.authorized.checked,
  };
  if (includePhoto) value.photoBase64 = photoBase64;
  return value;
}

async function callApi(path, body) {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  try {
    return await response.json();
  } catch {
    throw new Error(`本地服务返回 HTTP ${response.status}，但响应不是 JSON`);
  }
}

function masked(value, key = '') {
  if (/^(token|pwd|cloudTalkPassword)$/i.test(key)) return '<redacted>';
  if (Array.isArray(value)) return value.map((item) => masked(item));
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([name, item]) => [name, masked(item, name)]));
  }
  return value;
}

function pretty(value) {
  return JSON.stringify(masked(value), null, 2);
}

function addExchange(title, exchange) {
  if (!exchange) return;
  const details = document.createElement('details');
  details.className = 'exchange';
  const summary = document.createElement('summary');
  summary.textContent = title;
  details.append(summary);

  const body = document.createElement('div');
  body.className = 'exchange-body';
  const sections = [
    ['请求', `${exchange.request.method} ${exchange.request.url}`],
    ['请求头', pretty(exchange.request.headers)],
    ['表单（敏感值已隐藏）', exchange.request.body],
    ['响应状态', `${exchange.response.status} ${exchange.response.statusMessage || ''}`],
    ['响应 JSON', pretty(exchange.response.json ?? exchange.response.text)],
  ];
  for (const [heading, text] of sections) {
    const h4 = document.createElement('h4');
    h4.textContent = heading;
    const pre = document.createElement('pre');
    pre.textContent = text;
    body.append(h4, pre);
  }
  details.append(body);
  elements.results.append(details);
}

function render(data, operation) {
  elements.results.replaceChildren();
  if (data.ok && operation === 'punch') {
    setStatus(`打卡接口调用成功，服务端时间：${data.punchTime || '(空)'}`, 'success');
    addExchange('1. login.do', data.login?.exchange);
    addExchange('2. checkLocation.do', data.checkLocation?.exchange);
    addExchange(data.transport === 'tcp' ? '3. TCP PostMobileRecord' : '3. addCard.do', data.tcp || data.addCard?.exchange);
    return;
  }
  if (data.code === 'TCP_REQUIRED') {
    setStatus(data.message, 'warning');
    addExchange('1. login.do', data.login?.exchange);
    addExchange('2. checkLocation.do', data.checkLocation?.exchange);
    return;
  }
  if (data.ok && operation === 'login') {
    setStatus(`登录成功；本次接口通道：${data.transport === 'http' ? 'HTTP addCard.do' : `TCP 端口 ${data.recordTcpPort}`}`, 'success');
    addExchange('login.do', data.exchange);
    return;
  }

  setStatus(data.message || '请求失败', 'error');
  addExchange('失败请求', data.details?.exchange || data.exchange);
}

async function run(operation) {
  if (busy) return;
  if (operation === 'punch' && !photoBase64) {
    setStatus('请先选择 JPEG 照片', 'error');
    return;
  }
  if (operation === 'punch' && !elements.authorized.checked) {
    setStatus('请先确认授权测试环境', 'error');
    return;
  }

  setBusy(true, operation === 'punch' ? '正在按原接口链路提交…' : '正在调用 login.do…');
  try {
    const data = await callApi(
      operation === 'punch' ? '/api/punch' : '/api/login',
      payload({ includePhoto: operation === 'punch' }),
    );
    render(data, operation);
  } catch (error) {
    elements.results.replaceChildren();
    setStatus(error.message, 'error');
  } finally {
    setBusy(false);
  }
}

elements.timeMode.addEventListener('change', () => {
  const custom = elements.timeMode.value === 'custom';
  elements.customTime.disabled = !custom;
  elements.customTime.required = custom;
  elements.timeHint.textContent = custom
    ? 'checkLocation.time 和 addCard.time 都使用自定义值'
    : 'checkLocation 用当前时间；addCard.time 留空';
  if (custom && !elements.customTime.value) {
    const now = new Date();
    now.setMilliseconds(0);
    const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000);
    elements.customTime.value = local.toISOString().slice(0, 19);
  }
});

elements.photo.addEventListener('change', async () => {
  const file = elements.photo.files?.[0];
  photoBase64 = '';
  elements.photoPreview.parentElement.hidden = true;
  if (!file) {
    elements.photoName.textContent = '未选择文件';
    return;
  }
  if (file.size > 12 * 1024 * 1024) {
    elements.photo.value = '';
    elements.photoName.textContent = '未选择文件';
    setStatus('JPEG 照片不能超过 12 MiB', 'error');
    return;
  }

  try {
    const dataUrl = await new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = () => reject(new Error('无法读取照片'));
      reader.readAsDataURL(file);
    });
    photoBase64 = String(dataUrl).split(',', 2)[1] || '';
    elements.photoName.textContent = `${file.name} · ${(file.size / 1024).toFixed(1)} KiB`;
    elements.photoPreview.src = dataUrl;
    elements.photoPreview.parentElement.hidden = false;
    setStatus('照片已在本地读取，尚未上传', 'info');
  } catch (error) {
    setStatus(error.message, 'error');
  }
});

elements.loginButton.addEventListener('click', () => run('login'));
elements.punchButton.addEventListener('click', () => run('punch'));
elements.form.addEventListener('submit', (event) => event.preventDefault());
