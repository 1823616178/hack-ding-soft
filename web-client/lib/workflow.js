import {
  buildAddCardRequest,
  buildCheckLocationRequest,
  buildLoginRequest,
  decodePhoto,
  formatCoordinate,
  formatLocalDateTime,
  normalizeBaseUrl,
  requestPreview,
  resolveTimes,
} from './protocol.js';
import { executeUpstream } from './upstream.js';
import { postMobileRecord } from './tcp-client.js';
import { buildPostMobileRecord, tcpRequestPreview } from './tcp-protocol.js';
import { resolveSm2Config } from './sm2-config.js';

export class WorkflowError extends Error {
  constructor(code, message, { status = 422, details } = {}) {
    super(message);
    this.name = 'WorkflowError';
    this.code = code;
    this.status = status;
    this.details = details;
  }
}

function responseMessage(json, fallback) {
  return json?.reason || json?.message || json?.msg || fallback;
}

async function send(request, executor) {
  const upstream = await executor(request);
  let json = null;
  try {
    json = JSON.parse(upstream.text);
  } catch {
    // The caller decides whether a non-JSON response is valid for its endpoint.
  }

  return {
    request: requestPreview(request),
    response: {
      status: upstream.status,
      statusMessage: upstream.statusMessage,
      contentType: upstream.headers['content-type'] || null,
      contentEncoding: upstream.headers['content-encoding'] || null,
      compressed: upstream.compressed,
      json,
      text: upstream.text,
    },
  };
}

function requireJson(exchange, endpoint) {
  if (exchange.response.json === null) {
    throw new WorkflowError('UPSTREAM_INVALID_JSON', `${endpoint} 返回的不是有效 JSON`, {
      status: 502,
      details: { exchange },
    });
  }
  return exchange.response.json;
}

function isSuccessFlag(value) {
  return value === true || (typeof value === 'string' && value.toLowerCase() === 'true');
}

function requireHttp2xx(exchange, endpoint) {
  const status = exchange.response.status;
  if (status < 200 || status >= 300) {
    throw new WorkflowError('UPSTREAM_HTTP_STATUS', `${endpoint} 返回 HTTP ${status}`, {
      status: 502,
      details: { exchange },
    });
  }
}

export async function runLogin(input, executor = executeUpstream) {
  const exchange = await send(buildLoginRequest(input), executor);
  const json = requireJson(exchange, 'login.do');

  if (!isSuccessFlag(json.success)) {
    throw new WorkflowError('LOGIN_FAILED', responseMessage(json, '登录失败'), {
      details: { exchange },
    });
  }

  const result = json.result;
  if (!result || typeof result !== 'object' || !String(result.token ?? '')) {
    throw new WorkflowError('LOGIN_RESULT_INVALID', '登录成功响应中缺少 result.token', {
      status: 502,
      details: { exchange },
    });
  }

  const rawTcpPort = Number(result.recordTcpPort ?? -1);
  const recordTcpPort = Number.isFinite(rawTcpPort) && rawTcpPort > 0 ? rawTcpPort : -1;
  const rawMap = Number(result.mapGaode ?? 0);

  return {
    token: String(result.token),
    employId: String(result.employId ?? ''),
    mapGaode: Number.isFinite(rawMap) ? rawMap : 0,
    recordTcpPort,
    transport: recordTcpPort === -1 ? 'http' : 'tcp',
    exchange,
  };
}

export async function runCheckLocation(input, executor = executeUpstream) {
  const times = resolveTimes(input);
  const exchange = await send(buildCheckLocationRequest({
    ...input,
    time: times.checkLocationTime,
  }), executor);
  const json = requireJson(exchange, 'checkLocation.do');

  if (!isSuccessFlag(json.success) || Number(json.code) !== 0) {
    throw new WorkflowError('LOCATION_REJECTED', responseMessage(json, '服务端未通过定位校验'), {
      details: { exchange },
    });
  }

  return { exchange, json };
}

export async function runAddCard(input, executor = executeUpstream) {
  const times = resolveTimes(input);
  const exchange = await send(buildAddCardRequest({
    ...input,
    time: times.addCardTime,
  }), executor);
  requireHttp2xx(exchange, 'addCard.do');
  const json = requireJson(exchange, 'addCard.do');

  if (Number(json.code) !== 0) {
    throw new WorkflowError('ADD_CARD_REJECTED', responseMessage(json, '上传考勤信息失败'), {
      details: { exchange },
    });
  }

  return {
    punchTime: String(json.result ?? ''),
    exchange,
    json,
  };
}

function validatePunchInput(input) {
  normalizeBaseUrl(input.baseUrl);
  if (!String(input.userName ?? '')) {
    throw new WorkflowError('INVALID_INPUT', '请填写用户名', { status: 400 });
  }
  if (!String(input.pwd ?? '')) {
    throw new WorkflowError('INVALID_INPUT', '请填写密码', { status: 400 });
  }
  if (input.authorized !== true) {
    throw new WorkflowError('AUTHORIZATION_REQUIRED', '请确认仅在授权测试环境使用', { status: 400 });
  }

  // Validate every local input before the first network request.
  decodePhoto(input.photoBase64);
  resolveTimes(input);
  formatCoordinate(input.lat, 'lat');
  formatCoordinate(input.lng, 'lng');
  if (!/^\d+$/.test(String(input.currSysVer ?? ''))) {
    throw new WorkflowError('INVALID_INPUT', 'currSysVer 必须是 Android SDK 整数版本', { status: 400 });
  }
  if (input.usedMap !== 0 && input.usedMap !== 1 && input.usedMap !== '0' && input.usedMap !== '1') {
    throw new WorkflowError('INVALID_INPUT', 'usedMap 只能是 0 或 1', { status: 400 });
  }
}

export async function runPunch(input, executor = executeUpstream, tcpExecutor = postMobileRecord) {
  validatePunchInput(input);
  const login = await runLogin(input, executor);
  const checkLocation = await runCheckLocation({ ...input, token: login.token }, executor);

  const responseResult = checkLocation.json?.result;
  const checkMap = typeof responseResult === 'object' && responseResult !== null
    ? Number(responseResult.mapGaode)
    : Number.NaN;
  const topLevelMap = Number(checkLocation.json?.mapGaode);
  const serverSpecifiedMap = Number.isFinite(checkMap)
    ? checkMap
    : Number.isFinite(topLevelMap)
      ? topLevelMap
      : login.mapGaode;

  if (login.transport === 'tcp') {
    const sm2 = await resolveSm2Config(input);
    const record = buildPostMobileRecord({
      ...input,
      employId: login.employId,
      time: resolveTimes(input).checkLocationTime,
      serverSpecifiedMap,
    });
    const host = new URL(normalizeBaseUrl(input.baseUrl)).hostname;
    const tcpResult = await tcpExecutor({
      host,
      port: login.recordTcpPort,
      record,
      sm2,
    });
    return {
      ok: true,
      transport: 'tcp',
      punchTime: formatLocalDateTime(),
      serverSpecifiedMap,
      login,
      checkLocation,
      tcp: {
        request: tcpRequestPreview(host, login.recordTcpPort, record),
        response: {
          status: 200,
          statusMessage: 'TCP success',
          json: tcpResult.response,
          text: '',
        },
      },
    };
  }

  const addCard = await runAddCard({
    ...input,
    token: login.token,
    serverSpecifiedMap,
  }, executor);

  return {
    ok: true,
    transport: 'http',
    punchTime: addCard.punchTime,
    serverSpecifiedMap,
    login,
    checkLocation,
    addCard,
  };
}
