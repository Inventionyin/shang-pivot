/**
 * 写链路压测：C 端登录 → 加购 → 查购物车 → 下单 → 沙箱支付。
 *
 * ⚠️ 并发约束：**一个账号配一个 VU**。
 *   下单会清空购物车已选中的条目，同一账号跑多个 VU 时两个 VU 会互相清空购物车，
 *   表现为下单返回业务失败。用 PHONES=a1,a2,... 提供账号列表，脚本按 __VU 顺序分配。
 *
 * ⚠️ 下单必须带 skuId（商品都带规格），且**每次下单会真实扣 1 件库存**。
 *   setup 会扫描商品详情构建 (spuId, skuId) 池；可用 SKIP_SCAN 限定扫描深度。
 *   压测后如需恢复库存，按 docs/测试资料 里的做法用后台调库存或直接改库。
 *
 * ⚠️ 沙箱支付：POST /api/payment/sandbox {orderId} 会把订单置为已支付；这是演示环境的
 *   模拟支付接口，不产生真实交易。
 *
 * 用法（一账号一 VU）：
 *   k6 run -e BASE_URL=http://127.0.0.1:8086/api \
 *          -e PHONES=13800000001,13800000002,13800000003 \
 *          -e VUS=3 -e DURATION=1m k6/checkout-smoke.js
 */
import http from 'k6/http';
import { sleep } from 'k6';
import {
  asId,
  authHeaders,
  expectOk,
  getBaseUrl,
  idempotentKey,
  isBusinessSuccess,
  jsonHeaders,
  loadAddresses,
  loadSkuPool,
  loadSpuIds,
  customerLoginOrFail,
  parseJson,
  pickRandom,
  thinkTime,
} from './lib/shang.js';

export const options = {
  vus: Number(__ENV.VUS || 1),
  duration: __ENV.DURATION || '1m',
  thresholds: {
    http_req_failed: [`rate<${Number(__ENV.THRESHOLD_FAIL_RATE || 0.01)}`],
    checks: [`rate>${Number(__ENV.THRESHOLD_CHECK_RATE || 0.99)}`],
    http_req_duration: [
      `p(95)<${Number(__ENV.THRESHOLD_P95_MS || 1500)}`,
      `p(99)<${Number(__ENV.THRESHOLD_P99_MS || 3000)}`,
    ],
  },
};

export function setup() {
  const baseUrl = getBaseUrl();
  const phones = (__ENV.PHONES || __ENV.PHONE || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
  if (phones.length === 0) {
    throw new Error('缺少 C 端账号：-e PHONES=13800000001,13800000002（一个账号对应一个 VU）');
  }

  const code = __ENV.CODE || '123456';

  // 1. 每个账号登录并取默认收货地址（下单必填，token 按账号隔离）
  const users = [];
  for (const phone of phones) {
    const token = customerLoginOrFail(baseUrl, phone, code);
    const addresses = loadAddresses(baseUrl, token);
    if (addresses.length === 0) {
      throw new Error(`压测账号 ${phone} 没有收货地址，无法下单。请先在会员中心添加地址。`);
    }
    users.push({ phone, token, addressId: asId(addresses[0].id) });
  }

  // 2. 构建 (spuId, skuId) 池
  const spuIds = loadSpuIds(baseUrl, users[0].token, 40);
  const skuPool = loadSkuPool(baseUrl, users[0].token, spuIds, Number(__ENV.SKIP_SCAN || 20));
  if (skuPool.length === 0) {
    throw new Error(
      `扫描 ${spuIds.length} 个商品后没有可售 SKU。请确认演示数据里商品已上架且有库存（Flyway 种子）。`,
    );
  }

  console.log(
    `[checkout-smoke] 账号 ${users.length} 个，可用 SKU ${skuPool.length} 个，VUS=${options.vus}；` +
      'VUS 超过账号数时会复用账号并可能触发购物车/库存竞争',
  );

  return { baseUrl, users, skuPool };
}

export default function (data) {
  // 一账号一 VU：__VU 从 1 开始，超出账号数时取模复用（会在日志里提示）
  const user = data.users[(__VU - 1) % data.users.length];
  const picked = pickRandom(data.skuPool);

  const addResponse = http.post(
    `${data.baseUrl}/cart/items`,
    JSON.stringify({ skuId: picked.skuId, quantity: 1 }),
    authHeaders(user.token),
  );
  expectOk(addResponse, 'add cart item');

  const cartResponse = http.get(`${data.baseUrl}/cart`, authHeaders(user.token));
  expectOk(cartResponse, 'get cart');

  const checkoutResponse = http.post(
    `${data.baseUrl}/orders`,
    JSON.stringify({
      addressId: user.addressId,
      skuId: picked.skuId,
      quantity: 1,
      idempotentKey: idempotentKey('k6-checkout'),
    }),
    authHeaders(user.token),
  );
  const checkoutResult = expectOk(checkoutResponse, 'submit order');

  const orderId =
    checkoutResult.body && checkoutResult.body.data
      ? checkoutResult.body.data.id || checkoutResult.body.data.orderId
      : null;

  if (orderId) {
    const payResponse = http.post(
      `${data.baseUrl}/payment/sandbox`,
      JSON.stringify({ orderId: asId(orderId) }),
      authHeaders(user.token),
    );
    expectOk(payResponse, 'sandbox pay');
  } else {
    const failed = parseJson(checkoutResponse);
    console.log(
      `[checkout-smoke] 账号=${user.phone} 下单失败 code=${failed ? failed.code : '?'} msg=${failed ? failed.msg : '非 JSON 响应'}`,
    );
  }

  sleep(thinkTime());
}
