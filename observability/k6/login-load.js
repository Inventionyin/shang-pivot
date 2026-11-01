/**
 * 登录接口压测（大促开场登录风暴）。
 *
 * 两条登录路径的瓶颈位置不同，用 LOGIN_TARGET 切换：
 *   customer（默认）  POST /api/auth/login     手机号+验证码，走 Redis 校验 + 令牌签发
 *   admin             POST /admin/auth/login   用户名+密码，走 BCrypt（纯 CPU），单次更贵
 *
 * 到达速率模式（推荐，模仿固定 QPS 的登录风暴）：
 *   k6 run -e LOGIN_TARGET=customer -e BASE_URL=http://111.170.33.2:60084/api \
 *          -e PHONES=13800000001,13800000002,13800000003 -e RATE=20 -e DURATION=2m k6/login-load.js
 *
 * 固定并发模式（不传 RATE）：
 *   k6 run -e VUS=20 -e DURATION=1m ... k6/login-load.js
 *
 * 说明：
 * - 默认只断言"HTTP 200 且返回 accessToken"，用于测登录接口自身吞吐。
 * - 加 -e VERIFY_TOKEN=true 会额外校验 token 能否通过鉴权。
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  getAdminBaseUrl,
  getBaseUrl,
  isBusinessSuccess,
  jsonHeaders,
  parseJson,
  pickRandom,
  thinkTime,
  verifyTokenUsable,
} from './lib/shang.js';

const TARGET = (__ENV.LOGIN_TARGET || 'customer').toLowerCase();
const RATE = Number(__ENV.RATE || 0);
const DURATION = __ENV.DURATION || '2m';
const PRE_ALLOCATED_VUS = Number(__ENV.PRE_ALLOCATED_VUS || 50);
const MAX_VUS = Number(__ENV.MAX_VUS || 300);

const THRESHOLDS = {
  http_req_failed: [`rate<${Number(__ENV.THRESHOLD_FAIL_RATE || 0.01)}`],
  checks: [`rate>${Number(__ENV.THRESHOLD_CHECK_RATE || 0.99)}`],
  http_req_duration: [
    `p(95)<${Number(__ENV.THRESHOLD_P95_MS || 800)}`,
    `p(99)<${Number(__ENV.THRESHOLD_P99_MS || 1500)}`,
  ],
};

export const options = RATE > 0
  ? {
      thresholds: THRESHOLDS,
      scenarios: {
        login_storm: {
          executor: 'constant-arrival-rate',
          rate: RATE,
          timeUnit: '1s',
          duration: DURATION,
          preAllocatedVUs: PRE_ALLOCATED_VUS,
          maxVUs: MAX_VUS,
        },
      },
    }
  : {
      thresholds: THRESHOLDS,
      vus: Number(__ENV.VUS || 20),
      duration: DURATION,
    };

function splitEnv(name) {
  return (__ENV[name] || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

export function setup() {
  if (TARGET === 'admin') {
    const accounts = splitEnv('ACCOUNTS');
    const single = __ENV.ACCOUNT || '';
    const list = accounts.length > 0 ? accounts : (single ? [single] : []);
    if (list.length === 0) {
      throw new Error('缺少管理端账号：-e ACCOUNTS=admin,ops1 或 -e ACCOUNT=admin');
    }
    if (!__ENV.PASSWORD) {
      throw new Error('缺少管理端密码：-e PASSWORD=...');
    }
    console.log(`[login-load] 管理端账号 ${list.length} 个，模式 ${RATE > 0 ? `到达速率 ${RATE}/s` : '固定并发'}`);
    return { target: 'admin', baseUrl: getAdminBaseUrl(), accounts: list, password: __ENV.PASSWORD };
  }

  const phones = splitEnv('PHONES');
  const single = __ENV.PHONE || '';
  const list = phones.length > 0 ? phones : (single ? [single] : []);
  if (list.length === 0) {
    throw new Error('缺少 C 端账号：-e PHONES=13800000001,13800000002 或 -e PHONE=13800000001');
  }
  console.log(`[login-load] C 端账号 ${list.length} 个，模式 ${RATE > 0 ? `到达速率 ${RATE}/s` : '固定并发'}`);
  return { target: 'customer', baseUrl: getBaseUrl(), accounts: list, code: __ENV.CODE || '123456' };
}

export default function (data) {
  const account = pickRandom(data.accounts);
  const isAdmin = data.target === 'admin';

  const payload = isAdmin
    ? { username: account, password: data.password }
    : { phone: account, code: data.code, clientType: __ENV.CLIENT_TYPE || 'web' };

  const path = isAdmin ? '/auth/login' : '/auth/login';
  const response = http.post(`${data.baseUrl}${path}`, JSON.stringify(payload), jsonHeaders());

  const body = parseJson(response);
  const hasToken = Boolean(body && body.data && body.data.accessToken);

  check(response, {
    'login HTTP 200': (res) => res.status === 200,
    'login 业务成功': () => isBusinessSuccess(body),
    'login 返回 accessToken': () => hasToken,
  });

  if ((__ENV.VERIFY_TOKEN || 'false').toLowerCase() === 'true' && hasToken) {
    const verdict = verifyTokenUsable(data.baseUrl, body.data.accessToken, isAdmin ? '/auth/me' : '/me');
    check(verdict, {
      'login token 可通过鉴权': (v) => v.usable,
    });
  }

  sleep(thinkTime());
}
