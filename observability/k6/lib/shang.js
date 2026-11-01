/**
 * 尚品汇压测共享库。
 *
 * 与元气购的三个关键差异（照抄那边的脚本会全错）：
 *   1. 成功业务码是 0，不是 200（Result.ok → {"code":0,...}）
 *   2. 两套入口：C 端 /api/**、管理端 /admin/**，登录接口与令牌不通用
 *   3. 商品 ID 字段叫 spuId，详情里的 SKU ID 叫 skuId、库存叫 availableStock
 *
 * 环境变量：
 *   BASE_URL          C 端接口根地址，默认 http://127.0.0.1:8086/api
 *   ADMIN_BASE_URL    管理端接口根地址，默认 http://127.0.0.1:8086/admin
 *   PHONE / CODE      C 端压测账号（演示环境验证码固定 123456）
 *   ACCOUNT / PASSWORD 管理端账号
 *   ENABLE_AUTH       false 时跳过所有需要 token 的请求
 *   VUS / DURATION    固定并发模式
 *   STAGES            阶梯加压，格式 30s:10,1m:30,3m:50
 *   THINK_TIME        迭代间思考时间（秒），默认 1
 *   THRESHOLD_P95_MS / THRESHOLD_P99_MS / THRESHOLD_FAIL_RATE / THRESHOLD_CHECK_RATE
 */
import http from 'k6/http';
import { check, fail } from 'k6';

const TREND_STATS = ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'];

export function getBaseUrl() {
  return (__ENV.BASE_URL || 'http://127.0.0.1:8086/api').replace(/\/+$/, '');
}

export function getAdminBaseUrl() {
  return (__ENV.ADMIN_BASE_URL || 'http://127.0.0.1:8086/admin').replace(/\/+$/, '');
}

export function getAccount() {
  return __ENV.ACCOUNT || '';
}

export function getPassword() {
  return __ENV.PASSWORD || '';
}

export function getPhone() {
  return __ENV.PHONE || '';
}

export function getSmsCode() {
  return __ENV.CODE || '123456';
}

export function authEnabled() {
  return (__ENV.ENABLE_AUTH || 'true').toLowerCase() !== 'false';
}

export function buildOptions(defaultVus, defaultDuration) {
  const thresholds = {
    http_req_failed: [`rate<${Number(__ENV.THRESHOLD_FAIL_RATE || 0.01)}`],
    checks: [`rate>${Number(__ENV.THRESHOLD_CHECK_RATE || 0.99)}`],
    http_req_duration: [
      `p(95)<${Number(__ENV.THRESHOLD_P95_MS || 800)}`,
      `p(99)<${Number(__ENV.THRESHOLD_P99_MS || 1500)}`,
    ],
  };

  const stages = parseStages(__ENV.STAGES || '');
  if (stages.length > 0) {
    return { thresholds, stages, summaryTrendStats: TREND_STATS };
  }

  return {
    thresholds,
    vus: Number(__ENV.VUS || defaultVus),
    duration: __ENV.DURATION || defaultDuration,
    summaryTrendStats: TREND_STATS,
  };
}

export function jsonHeaders(token) {
  const headers = {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  };

  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  return { headers };
}

export const authHeaders = jsonHeaders;

export function parseJson(response) {
  try {
    return response.json();
  } catch (error) {
    return null;
  }
}

/** 业务成功判定：尚品汇信封 {"code":0,"msg":"success","data":...} */
export function isBusinessSuccess(body) {
  if (!body || typeof body !== 'object') {
    return false;
  }

  if (body.code !== undefined) {
    return Number(body.code) === 0;
  }

  return body.data !== undefined;
}

export function expectOk(response, label) {
  const body = parseJson(response);
  const ok = check(response, {
    [`${label} HTTP 200`]: (res) => res.status === 200,
    [`${label} 业务成功`]: () => isBusinessSuccess(body),
  });

  return { ok, body };
}

/** 管理端登录：POST /admin/auth/login {username,password} → data.accessToken */
export function adminLogin(adminBaseUrl, username, password) {
  if (!username || !password) {
    return { token: '', reason: '未提供 ACCOUNT / PASSWORD' };
  }

  const response = http.post(
    `${adminBaseUrl}/auth/login`,
    JSON.stringify({ username, password }),
    jsonHeaders(),
  );
  const body = parseJson(response);

  if (response.status !== 200) {
    return { token: '', reason: `登录 HTTP ${response.status}` };
  }

  const token = body && body.data && body.data.accessToken ? body.data.accessToken : '';
  if (!token) {
    return { token: '', reason: body ? `code=${body.code} msg=${body.msg}` : '响应非 JSON' };
  }

  return { token, reason: '' };
}

/** C 端登录：POST /api/auth/login {phone,code,clientType} → data.accessToken */
export function customerLogin(baseUrl, phone, code) {
  if (!phone) {
    return { token: '', reason: '未提供 PHONE' };
  }

  const response = http.post(
    `${baseUrl}/auth/login`,
    JSON.stringify({ phone, code: code || '123456', clientType: __ENV.CLIENT_TYPE || 'web' }),
    jsonHeaders(),
  );
  const body = parseJson(response);

  if (response.status !== 200) {
    return { token: '', reason: `登录 HTTP ${response.status}` };
  }

  const token = body && body.data && body.data.accessToken ? body.data.accessToken : '';
  if (!token) {
    return { token: '', reason: body ? `code=${body.code} msg=${body.msg}` : '响应非 JSON' };
  }

  return { token, reason: '' };
}

/** 登录后探测受保护接口，区分"能签发 token"和"token 真能通过鉴权"。 */
export function verifyTokenUsable(baseUrl, token, path) {
  if (!token) {
    return { usable: false, detail: '无 token' };
  }

  const response = http.get(`${baseUrl}${path}`, authHeaders(token));
  const body = parseJson(response);

  if (response.status === 200 && isBusinessSuccess(body)) {
    return { usable: true, detail: '令牌鉴权通过' };
  }

  const message = body && body.msg ? body.msg : `HTTP ${response.status}`;
  return { usable: false, detail: message };
}

export function customerLoginOrFail(baseUrl, phone, code) {
  const result = customerLogin(baseUrl, phone, code);
  if (!result.token) {
    fail(`C 端登录失败：${result.reason}。账号见 docs/测试资料/领域词典.md 的种子会员（验证码固定 123456）。`);
  }

  const verdict = verifyTokenUsable(baseUrl, result.token, '/me');
  if (!verdict.usable) {
    fail(`登录返回了 token，但受保护接口拒绝（${verdict.detail}）。写链路无法压测。`);
  }

  return result.token;
}

export function adminLoginOrFail(adminBaseUrl, username, password) {
  const result = adminLogin(adminBaseUrl, username, password);
  if (!result.token) {
    fail(`管理端登录失败：${result.reason}。默认超管 admin / Admin@123。`);
  }

  const verdict = verifyTokenUsable(adminBaseUrl, result.token, '/auth/me');
  if (!verdict.usable) {
    fail(`管理端令牌被拒（${verdict.detail}）。`);
  }

  return result.token;
}

/**
 * 拉取商品 ID 池（列表记录的字段是 spuId）。
 *
 * ⚠️ ID 一律保持**字符串**：尚品汇主键是 19 位雪花号，Number 类型超过
 *    Number.MAX_SAFE_INTEGER（9007199254740991）会丢精度——实测踩过：
 *    地址 id "2107309261480067073" 经 Number() 后变成另一个数，下单报"收货地址无效"。
 *    后端入参是 Long.valueOf(x.toString())，字符串正是它接受的形态。
 */
export function loadSpuIds(baseUrl, token, size) {
  const response = http.get(`${baseUrl}/products?page=1&size=${size || 40}`, jsonHeaders(token));
  const body = parseJson(response);

  if (!body || !body.data || !Array.isArray(body.data.records)) {
    return [];
  }

  return body.data.records
    .map((item) => item && item.spuId)
    .filter((id) => id !== undefined && id !== null)
    .map((id) => String(id));
}

/** 把接口返回的 ID 统一成字符串（雪花号必须按字符串传，见 loadSpuIds 注释）。 */
export function asId(value) {
  return value === undefined || value === null ? '' : String(value);
}

/** 拉取会员收货地址（下单必填）。 */
export function loadAddresses(baseUrl, token) {
  const response = http.get(`${baseUrl}/addresses`, authHeaders(token));
  const body = parseJson(response);
  return body && Array.isArray(body.data) ? body.data : [];
}

/**
 * 构建 (spuId, skuId) 池：加购/下单都必须带 skuId
 * （尚品汇商品带规格，CartService 在缺 skuId 时会拒绝）。
 */
export function loadSkuPool(baseUrl, token, spuIds, limit) {
  const pool = [];
  const scanList = spuIds.slice(0, limit || 20);

  for (const spuId of scanList) {
    const response = http.get(`${baseUrl}/products/${spuId}`, jsonHeaders(token));
    const body = parseJson(response);
    const skus = body && body.data && Array.isArray(body.data.skus) ? body.data.skus : [];
    for (const sku of skus) {
      if (Number(sku.availableStock) > 0) {
        pool.push({ spuId: asId(spuId), skuId: asId(sku.skuId) });
      }
    }
  }

  return pool;
}

export function pickRandom(values) {
  if (!values || values.length === 0) {
    return null;
  }

  return values[Math.floor(Math.random() * values.length)];
}

export function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

export function thinkTime() {
  return Number(__ENV.THINK_TIME || 1);
}

/** 下单幂等键：同一 key 重复提交会被后端拒绝，每次迭代必须换新的。 */
export function idempotentKey(prefix) {
  return `${prefix || 'k6'}-${__VU}-${__ITER}-${Date.now()}`;
}

function parseStages(rawStages) {
  if (!rawStages) {
    return [];
  }

  return rawStages
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
    .map((item) => {
      const parts = item.split(':').map((part) => part.trim());
      if (parts.length !== 2) {
        fail(`STAGES 格式错误：${item}。应形如 30s:10,1m:30,3m:50`);
      }

      return {
        duration: parts[0],
        target: Number(parts[1]),
      };
    });
}
