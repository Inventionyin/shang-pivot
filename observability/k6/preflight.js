/**
 * 压测前预检：探明目标环境"哪些接口真的能压"。
 *
 * 用法（C 端 + 管理端）：
 *   k6 run -e BASE_URL=http://111.170.33.2:60084/api \
 *          -e ADMIN_BASE_URL=http://111.170.33.2:60085/admin \
 *          -e PHONE=13800000001 -e ACCOUNT=admin -e PASSWORD=Admin@123 k6/preflight.js
 *
 * 如需在写链路不可用时返回非零退出码，追加 -e REQUIRE_WRITE_PATH=true
 */
import http from 'k6/http';
import { fail } from 'k6';
import {
  adminLogin,
  authHeaders,
  customerLogin,
  getAccount,
  getAdminBaseUrl,
  getBaseUrl,
  getPassword,
  getPhone,
  isBusinessSuccess,
  jsonHeaders,
  loadAddresses,
  loadSkuPool,
  loadSpuIds,
  parseJson,
  verifyTokenUsable,
} from './lib/shang.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {},
};

export default function () {
  const baseUrl = getBaseUrl();
  const adminBaseUrl = getAdminBaseUrl();
  const phone = getPhone();
  const findings = [];

  console.log(`\n===== 尚品汇压测预检 =====`);
  console.log(`C 端:   ${baseUrl}`);
  console.log(`管理端: ${adminBaseUrl}`);
  console.log(`账号:   PHONE=${phone || '(未提供)'} ACCOUNT=${getAccount() || '(未提供)'}\n`);

  // [1] 公开读链路
  const homeResponse = http.get(`${baseUrl}/home`, jsonHeaders());
  const homeBody = parseJson(homeResponse);
  findings.push([
    '公开读',
    'GET /api/home',
    homeResponse.status === 200 && isBusinessSuccess(homeBody),
    `HTTP ${homeResponse.status}${homeBody && homeBody.data ? ` keys=${Object.keys(homeBody.data).join(',')}` : ''}`,
  ]);

  const listResponse = http.get(`${baseUrl}/products?page=1&size=10`, jsonHeaders());
  const listBody = parseJson(listResponse);
  const listOk = listResponse.status === 200 && isBusinessSuccess(listBody);
  findings.push([
    '公开读',
    'GET /api/products',
    listOk,
    `HTTP ${listResponse.status} 共 ${listBody && listBody.data ? listBody.data.total : '?'} 条`,
  ]);

  // [2] 商品详情（续接的 SKU 池供写链路用）
  const spuIds = loadSpuIds(baseUrl, '', 40);
  let detailOk = false;
  if (spuIds.length > 0) {
    const detailResponse = http.get(`${baseUrl}/products/${spuIds[0]}`, jsonHeaders());
    detailOk = detailResponse.status === 200 && isBusinessSuccess(parseJson(detailResponse));
  }
  findings.push(['公开读', 'GET /api/products/{spuId}', detailOk, `商品池 ${spuIds.length} 条`]);

  // [3] 搜索（q 为关键词参数，不是 keyword）
  const keyword = __ENV.KEYWORD || '帐篷';
  const searchResponse = http.get(
    `${baseUrl}/search?q=${encodeURIComponent(keyword)}&page=1&size=10`,
    jsonHeaders(),
  );
  const searchBody = parseJson(searchResponse);
  findings.push([
    '公开读',
    `GET /api/search?q=${keyword}`,
    searchResponse.status === 200 && isBusinessSuccess(searchBody),
    `命中 ${searchBody && searchBody.data ? searchBody.data.total : '?'} 条`,
  ]);

  // [4] 类目
  const catResponse = http.get(`${baseUrl}/categories`, jsonHeaders());
  findings.push([
    '公开读',
    'GET /api/categories',
    catResponse.status === 200 && isBusinessSuccess(parseJson(catResponse)),
    `HTTP ${catResponse.status}`,
  ]);

  // [5] 鉴权边界：无 token 访问受保护接口必须被拒
  const guardResponse = http.get(`${baseUrl}/cart`, jsonHeaders());
  const guardBody = parseJson(guardResponse);
  const guardOk = guardResponse.status !== 200 || !isBusinessSuccess(guardBody);
  findings.push([
    '鉴权边界',
    'GET /api/cart 无 token 应被拒',
    guardOk,
    `HTTP ${guardResponse.status} code=${guardBody ? guardBody.code : '?'}`,
  ]);

  // [6] C 端登录与令牌可用性
  const login = customerLogin(baseUrl, phone, __ENV.CODE || '123456');
  findings.push(['登录', 'POST /api/auth/login（手机号+验证码）', Boolean(login.token), login.reason || '返回 accessToken']);

  const verdict = verifyTokenUsable(baseUrl, login.token, '/me');
  findings.push(['鉴权读', 'GET /api/me 携带 token', verdict.usable, verdict.detail]);

  let addressOk = false;
  let addressDetail = '依赖登录，未执行';
  let skuPool = [];
  if (verdict.usable) {
    const addresses = loadAddresses(baseUrl, login.token);
    addressOk = addresses.length > 0;
    addressDetail = `地址 ${addresses.length} 条${addresses.length === 0 ? '（写链路需要至少 1 条收货地址）' : ''}`;

    skuPool = loadSkuPool(baseUrl, login.token, spuIds, Number(__ENV.SKIP_SCAN || 10));
  }
  findings.push(['受保护读', 'GET /api/addresses', addressOk, addressDetail]);
  findings.push(['写链路素材', '可售 SKU 池', skuPool.length > 0, `${skuPool.length} 个（扫前 ${Number(__ENV.SKIP_SCAN || 10)} 个商品）`]);

  let cartOk = false;
  let cartDetail = '依赖登录，未执行';
  if (verdict.usable) {
    const cartResponse = http.get(`${baseUrl}/cart`, authHeaders(login.token));
    cartOk = cartResponse.status === 200 && isBusinessSuccess(parseJson(cartResponse));
    cartDetail = `HTTP ${cartResponse.status}`;
  }
  findings.push(['受保护读', 'GET /api/cart', cartOk, cartDetail]);

  let ordersOk = false;
  let ordersDetail = '依赖登录，未执行';
  if (verdict.usable) {
    const ordersResponse = http.get(`${baseUrl}/orders?page=1&size=5`, authHeaders(login.token));
    ordersOk = ordersResponse.status === 200 && isBusinessSuccess(parseJson(ordersResponse));
    ordersDetail = `HTTP ${ordersResponse.status}`;
  }
  findings.push(['受保护读', 'GET /api/orders', ordersOk, ordersDetail]);

  // [7] 管理端登录与受保护读
  const adminResult = adminLogin(adminBaseUrl, getAccount(), getPassword());
  findings.push(['登录', 'POST /admin/auth/login', Boolean(adminResult.token), adminResult.reason || '返回 accessToken']);

  let adminReadOk = false;
  let adminReadDetail = '依赖管理端登录，未执行';
  if (adminResult.token) {
    const dashboardResponse = http.get(`${adminBaseUrl}/dashboard/stats`, authHeaders(adminResult.token));
    adminReadOk = dashboardResponse.status === 200 && isBusinessSuccess(parseJson(dashboardResponse));
    adminReadDetail = `HTTP ${dashboardResponse.status}`;
  }
  findings.push(['管理端读', 'GET /admin/dashboard/stats', adminReadOk, adminReadDetail]);

  // 汇总
  console.log('---- 探测结果 ----');
  for (const [layer, name, ok, detail] of findings) {
    console.log(`${ok ? 'PASS' : 'FAIL'}  [${layer}] ${name}  <- ${detail}`);
  }

  const writePathReady = verdict.usable && addressOk && skuPool.length > 0;
  console.log('\n---- 结论 ----');
  console.log(`写链路（登录→加购→下单→支付）：${writePathReady ? '可压测' : '不可压测'}`);
  if (!writePathReady) {
    console.log(`原因：token=${verdict.usable ? 'OK' : verdict.detail}；地址=${addressOk ? 'OK' : '缺'}；SKU 池=${skuPool.length}`);
  }
  console.log('可压测范围（无论如何）：公开读链路（首页/列表/详情/搜索/类目）+ 登录接口');
  console.log('');

  if ((__ENV.REQUIRE_WRITE_PATH || 'false').toLowerCase() === 'true' && !writePathReady) {
    fail('写链路预检未通过，详见上面的探测结果。');
  }
}
