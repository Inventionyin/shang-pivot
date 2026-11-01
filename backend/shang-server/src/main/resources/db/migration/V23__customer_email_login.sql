-- ============================================================
-- V23: C 端邮箱注册/登录 —— identity.customer 增加 email 列与唯一索引
-- 依据 docs/01-requirements/PRD-V2.0-购物网站.md §8.1 增量（邮箱验证码登录，163 SMTP 发码）
-- ============================================================

ALTER TABLE identity.customer ADD COLUMN IF NOT EXISTS email VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uk_customer_email
    ON identity.customer (email) WHERE email IS NOT NULL AND email <> '' AND deleted = FALSE;

COMMENT ON COLUMN identity.customer.email IS '邮箱（邮箱验证码登录；与手机号二者其一）';
