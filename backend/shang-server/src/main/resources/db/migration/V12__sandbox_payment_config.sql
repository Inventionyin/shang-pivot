-- V12: 沙箱支付配置项（对齐原型 settings-trade 表单）
INSERT INTO identity.system_config (id, config_key, config_value, category, is_secret, remark)
SELECT v.id, v.config_key, v.config_value, v.category, v.is_secret, v.remark
FROM (VALUES
    (20::bigint, 'payment.sandbox.enabled',   'true',              'PAYMENT', false, '沙箱渠道开关'),
    (21::bigint, 'payment.sandbox.channel',    'sandbox-mock',      'PAYMENT', false, '渠道标识'),
    (22::bigint, 'payment.sandbox.merchant',   'shang_mock_001',    'PAYMENT', false, '模拟支付商户号'),
    (23::bigint, 'payment.sandbox.delay-ms',   '800',               'PAYMENT', false, '默认成功延时（ms）'),
    (24::bigint, 'payment.sandbox.notify-url', 'https://api.shang.example/payment/sandbox/notify', 'PAYMENT', false, '回调地址')
) AS v(id, config_key, config_value, category, is_secret, remark)
WHERE NOT EXISTS (
    SELECT 1 FROM identity.system_config c WHERE c.id = v.id OR c.config_key = v.config_key
);
