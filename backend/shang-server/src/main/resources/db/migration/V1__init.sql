-- Phase 1 init: identity + commerce schemas, tables, seed data
CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS commerce;

-- ===================== identity =====================
CREATE TABLE IF NOT EXISTS identity.admin_user (
    id              BIGINT PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL,
    password        VARCHAR(128) NOT NULL,
    real_name       VARCHAR(64),
    phone           VARCHAR(32),
    email           VARCHAR(128),
    dept_id         BIGINT,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_admin_user_username ON identity.admin_user (username) WHERE deleted = FALSE;

CREATE TABLE IF NOT EXISTS identity.role (
    id              BIGINT PRIMARY KEY,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    data_scope      VARCHAR(16)  NOT NULL DEFAULT 'ALL',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    remark          VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_role_code ON identity.role (code) WHERE deleted = FALSE;

CREATE TABLE IF NOT EXISTS identity.permission (
    id              BIGINT PRIMARY KEY,
    parent_id       BIGINT       NOT NULL DEFAULT 0,
    code            VARCHAR(128) NOT NULL,
    name            VARCHAR(128) NOT NULL,
    type            VARCHAR(16)  NOT NULL,
    path            VARCHAR(255),
    component       VARCHAR(255),
    icon            VARCHAR(64),
    sort            INT          NOT NULL DEFAULT 0,
    visible         BOOLEAN      NOT NULL DEFAULT TRUE,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_permission_code ON identity.permission (code) WHERE deleted = FALSE;

CREATE TABLE IF NOT EXISTS identity.role_permission (
    role_id         BIGINT NOT NULL,
    permission_id   BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE IF NOT EXISTS identity.admin_role (
    admin_user_id   BIGINT NOT NULL,
    role_id         BIGINT NOT NULL,
    PRIMARY KEY (admin_user_id, role_id)
);

CREATE TABLE IF NOT EXISTS identity.dept (
    id              BIGINT PRIMARY KEY,
    parent_id       BIGINT       NOT NULL DEFAULT 0,
    name            VARCHAR(128) NOT NULL,
    sort            INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS identity.operation_log (
    id              BIGINT PRIMARY KEY,
    user_id         BIGINT,
    username        VARCHAR(64),
    module          VARCHAR(64),
    action          VARCHAR(64),
    params          TEXT,
    ip              VARCHAR(64),
    ua              VARCHAR(512),
    result          VARCHAR(32),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS identity.dict (
    id              BIGINT PRIMARY KEY,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    remark          VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_dict_code ON identity.dict (code) WHERE deleted = FALSE;

CREATE TABLE IF NOT EXISTS identity.dict_item (
    id              BIGINT PRIMARY KEY,
    dict_id         BIGINT       NOT NULL,
    label           VARCHAR(128) NOT NULL,
    value           VARCHAR(128) NOT NULL,
    sort            INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS identity.system_config (
    id              BIGINT PRIMARY KEY,
    config_key      VARCHAR(128) NOT NULL,
    config_value    TEXT,
    category        VARCHAR(32)  NOT NULL DEFAULT 'SYS',
    is_secret       BOOLEAN      NOT NULL DEFAULT FALSE,
    remark          VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_system_config_key ON identity.system_config (config_key) WHERE deleted = FALSE;

CREATE TABLE IF NOT EXISTS identity.file_object (
    id              BIGINT PRIMARY KEY,
    bucket          VARCHAR(128) NOT NULL,
    object_key      VARCHAR(512) NOT NULL,
    content_type    VARCHAR(128),
    size_bytes      BIGINT,
    biz_type        VARCHAR(32)  NOT NULL DEFAULT 'OTHER',
    uploader_id     BIGINT,
    visibility      VARCHAR(16)  NOT NULL DEFAULT 'PUBLIC',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_file_object_key ON identity.file_object (object_key) WHERE deleted = FALSE;

-- ===================== commerce =====================
CREATE TABLE IF NOT EXISTS commerce.category (
    id              BIGINT PRIMARY KEY,
    parent_id       BIGINT       NOT NULL DEFAULT 0,
    name            VARCHAR(128) NOT NULL,
    level           INT          NOT NULL DEFAULT 1,
    sort            INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS commerce.brand (
    id              BIGINT PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    logo            VARCHAR(512),
    sort            INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS commerce.attribute (
    id              BIGINT PRIMARY KEY,
    category_id     BIGINT       NOT NULL,
    name            VARCHAR(128) NOT NULL,
    type            VARCHAR(16)  NOT NULL DEFAULT 'SPEC',
    options         JSONB,
    sort            INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS commerce.spu (
    id              BIGINT PRIMARY KEY,
    category_id     BIGINT,
    brand_id        BIGINT,
    name            VARCHAR(255) NOT NULL,
    subtitle        VARCHAR(512),
    pics            JSONB,
    videos          JSONB,
    detail_html     TEXT,
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS commerce.sku (
    id              BIGINT PRIMARY KEY,
    spu_id          BIGINT       NOT NULL,
    title           VARCHAR(255) NOT NULL,
    price           NUMERIC(12,2) NOT NULL DEFAULT 0,
    cost_price      NUMERIC(12,2),
    bar_code        VARCHAR(64),
    specs           JSONB,
    pic             VARCHAR(512),
    status          VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sku_bar_code ON commerce.sku (bar_code) WHERE bar_code IS NOT NULL AND deleted = FALSE;

CREATE TABLE IF NOT EXISTS commerce.warehouse (
    id              BIGINT PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS commerce.stock (
    id              BIGINT PRIMARY KEY,
    sku_id          BIGINT       NOT NULL,
    warehouse_id    BIGINT       NOT NULL,
    physical_stock  INT          NOT NULL DEFAULT 0,
    locked_stock    INT          NOT NULL DEFAULT 0,
    low_threshold   INT          NOT NULL DEFAULT 10,
    version         INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64),
    CONSTRAINT ck_stock_invariant CHECK (physical_stock >= locked_stock AND locked_stock >= 0)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_stock_sku_wh ON commerce.stock (sku_id, warehouse_id) WHERE deleted = FALSE;

CREATE TABLE IF NOT EXISTS commerce.stock_log (
    id              BIGINT PRIMARY KEY,
    sku_id          BIGINT       NOT NULL,
    warehouse_id    BIGINT       NOT NULL,
    change_qty      INT          NOT NULL,
    type            VARCHAR(32)  NOT NULL,
    reason          VARCHAR(255),
    operator_id     BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS commerce."order" (
    id              BIGINT PRIMARY KEY,
    order_no        VARCHAR(64)  NOT NULL,
    customer_id     BIGINT,
    customer_name   VARCHAR(64),
    status          VARCHAR(32)  NOT NULL,
    total_amount    NUMERIC(12,2) NOT NULL DEFAULT 0,
    pay_amount      NUMERIC(12,2) NOT NULL DEFAULT 0,
    freight         NUMERIC(12,2) NOT NULL DEFAULT 0,
    address_snapshot JSONB,
    remark          VARCHAR(512),
    paid_at         TIMESTAMPTZ,
    ship_at         TIMESTAMPTZ,
    received_at     TIMESTAMPTZ,
    close_reason    VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_order_no ON commerce."order" (order_no) WHERE deleted = FALSE;

CREATE TABLE IF NOT EXISTS commerce.order_item (
    id              BIGINT PRIMARY KEY,
    order_id        BIGINT       NOT NULL,
    sku_id          BIGINT,
    spu_id          BIGINT,
    title           VARCHAR(255) NOT NULL,
    pic             VARCHAR(512),
    price           NUMERIC(12,2) NOT NULL,
    quantity        INT          NOT NULL,
    specs           JSONB,
    subtotal        NUMERIC(12,2) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS commerce.payment (
    id              BIGINT PRIMARY KEY,
    order_id        BIGINT       NOT NULL,
    channel         VARCHAR(32)  NOT NULL DEFAULT 'SANDBOX',
    amount          NUMERIC(12,2) NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    trade_no        VARCHAR(64),
    paid_at         TIMESTAMPTZ,
    mock_param      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS commerce.shipment (
    id              BIGINT PRIMARY KEY,
    order_id        BIGINT       NOT NULL,
    carrier         VARCHAR(64),
    tracking_no     VARCHAR(64),
    status          VARCHAR(32)  NOT NULL DEFAULT 'SHIPPED',
    traces          JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS commerce.after_sale (
    id              BIGINT PRIMARY KEY,
    after_sale_no   VARCHAR(64)  NOT NULL,
    order_id        BIGINT       NOT NULL,
    customer_id     BIGINT,
    type            VARCHAR(32)  NOT NULL,
    reason          VARCHAR(512),
    evidence_pics   JSONB,
    status          VARCHAR(32)  NOT NULL,
    refund_amount   NUMERIC(12,2),
    audit_remark    VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    trace_id        VARCHAR(64)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_after_sale_no ON commerce.after_sale (after_sale_no) WHERE deleted = FALSE;

-- ===================== 表/字段注释（COMMENT，便于库内元数据与文档同步） =====================
COMMENT ON SCHEMA identity IS '认证权限限界上下文：后台用户、RBAC、字典、配置、文件元数据';
COMMENT ON SCHEMA commerce IS '电商交易限界上下文：商品、库存、订单、支付、物流、售后';

COMMENT ON TABLE identity.admin_user IS '后台管理员账号';
COMMENT ON COLUMN identity.admin_user.id IS '主键';
COMMENT ON COLUMN identity.admin_user.username IS '登录用户名（唯一）';
COMMENT ON COLUMN identity.admin_user.password IS '密码（bcrypt）';
COMMENT ON COLUMN identity.admin_user.real_name IS '真实姓名';
COMMENT ON COLUMN identity.admin_user.phone IS '手机号';
COMMENT ON COLUMN identity.admin_user.email IS '邮箱';
COMMENT ON COLUMN identity.admin_user.dept_id IS '所属部门 ID';
COMMENT ON COLUMN identity.admin_user.status IS '状态：ENABLED启用 / DISABLED禁用';
COMMENT ON COLUMN identity.admin_user.last_login_at IS '最近登录时间';
COMMENT ON COLUMN identity.admin_user.created_at IS '创建时间';
COMMENT ON COLUMN identity.admin_user.updated_at IS '更新时间';
COMMENT ON COLUMN identity.admin_user.deleted IS '软删标记';
COMMENT ON COLUMN identity.admin_user.trace_id IS '触发创建/变更的请求 traceId';

COMMENT ON TABLE identity.role IS '角色';
COMMENT ON COLUMN identity.role.id IS '主键';
COMMENT ON COLUMN identity.role.code IS '角色编码（唯一，如 SUPER_ADMIN）';
COMMENT ON COLUMN identity.role.name IS '角色名称';
COMMENT ON COLUMN identity.role.data_scope IS '数据范围：ALL全部 / DEPT本部门 / CUSTOM自定义';
COMMENT ON COLUMN identity.role.status IS '状态：ENABLED / DISABLED';
COMMENT ON COLUMN identity.role.remark IS '备注';
COMMENT ON COLUMN identity.role.created_at IS '创建时间';
COMMENT ON COLUMN identity.role.updated_at IS '更新时间';
COMMENT ON COLUMN identity.role.deleted IS '软删标记';
COMMENT ON COLUMN identity.role.trace_id IS '请求 traceId';

COMMENT ON TABLE identity.permission IS '权限/菜单单表（DIR目录 / MENU菜单 / BUTTON按钮 / API接口）';
COMMENT ON COLUMN identity.permission.id IS '主键';
COMMENT ON COLUMN identity.permission.parent_id IS '父节点 ID，根为 0';
COMMENT ON COLUMN identity.permission.code IS '权限码（唯一，如 product:add，用于鉴权）';
COMMENT ON COLUMN identity.permission.name IS '显示名称';
COMMENT ON COLUMN identity.permission.type IS '类型：DIR / MENU / BUTTON / API';
COMMENT ON COLUMN identity.permission.path IS '前端路由 path（MENU）';
COMMENT ON COLUMN identity.permission.component IS '前端组件路径（MENU）';
COMMENT ON COLUMN identity.permission.icon IS '图标标识';
COMMENT ON COLUMN identity.permission.sort IS '同级排序，越小越靠前';
COMMENT ON COLUMN identity.permission.visible IS '是否在侧栏可见';
COMMENT ON COLUMN identity.permission.status IS '状态：ENABLED / DISABLED';
COMMENT ON COLUMN identity.permission.created_at IS '创建时间';
COMMENT ON COLUMN identity.permission.updated_at IS '更新时间';
COMMENT ON COLUMN identity.permission.deleted IS '软删标记';
COMMENT ON COLUMN identity.permission.trace_id IS '请求 traceId';

COMMENT ON TABLE identity.role_permission IS '角色-权限关联';
COMMENT ON COLUMN identity.role_permission.role_id IS '角色 ID';
COMMENT ON COLUMN identity.role_permission.permission_id IS '权限 ID';

COMMENT ON TABLE identity.admin_role IS '管理员-角色关联';
COMMENT ON COLUMN identity.admin_role.admin_user_id IS '管理员用户 ID';
COMMENT ON COLUMN identity.admin_role.role_id IS '角色 ID';

COMMENT ON TABLE identity.dept IS '组织部门树';
COMMENT ON COLUMN identity.dept.id IS '主键';
COMMENT ON COLUMN identity.dept.parent_id IS '父部门 ID，根为 0';
COMMENT ON COLUMN identity.dept.name IS '部门名称';
COMMENT ON COLUMN identity.dept.sort IS '排序';
COMMENT ON COLUMN identity.dept.status IS '状态：ENABLED / DISABLED';
COMMENT ON COLUMN identity.dept.created_at IS '创建时间';
COMMENT ON COLUMN identity.dept.updated_at IS '更新时间';
COMMENT ON COLUMN identity.dept.deleted IS '软删标记';
COMMENT ON COLUMN identity.dept.trace_id IS '请求 traceId';

COMMENT ON TABLE identity.operation_log IS '操作审计日志';
COMMENT ON COLUMN identity.operation_log.id IS '主键';
COMMENT ON COLUMN identity.operation_log.user_id IS '操作人用户 ID';
COMMENT ON COLUMN identity.operation_log.username IS '操作人用户名快照';
COMMENT ON COLUMN identity.operation_log.module IS '业务模块';
COMMENT ON COLUMN identity.operation_log.action IS '动作（如 login、update）';
COMMENT ON COLUMN identity.operation_log.params IS '请求参数（脱敏后）';
COMMENT ON COLUMN identity.operation_log.ip IS '客户端 IP';
COMMENT ON COLUMN identity.operation_log.ua IS 'User-Agent';
COMMENT ON COLUMN identity.operation_log.result IS '结果：SUCCESS / FAIL 等';
COMMENT ON COLUMN identity.operation_log.created_at IS '创建时间';
COMMENT ON COLUMN identity.operation_log.updated_at IS '更新时间';
COMMENT ON COLUMN identity.operation_log.deleted IS '软删标记';
COMMENT ON COLUMN identity.operation_log.trace_id IS '请求 traceId';

COMMENT ON TABLE identity.dict IS '数据字典类型';
COMMENT ON COLUMN identity.dict.id IS '主键';
COMMENT ON COLUMN identity.dict.code IS '字典编码（唯一，如 order_status）';
COMMENT ON COLUMN identity.dict.name IS '字典名称';
COMMENT ON COLUMN identity.dict.remark IS '备注';
COMMENT ON COLUMN identity.dict.created_at IS '创建时间';
COMMENT ON COLUMN identity.dict.updated_at IS '更新时间';
COMMENT ON COLUMN identity.dict.deleted IS '软删标记';
COMMENT ON COLUMN identity.dict.trace_id IS '请求 traceId';

COMMENT ON TABLE identity.dict_item IS '数据字典项';
COMMENT ON COLUMN identity.dict_item.id IS '主键';
COMMENT ON COLUMN identity.dict_item.dict_id IS '所属字典 ID';
COMMENT ON COLUMN identity.dict_item.label IS '显示文案';
COMMENT ON COLUMN identity.dict_item.value IS '存储值';
COMMENT ON COLUMN identity.dict_item.sort IS '排序';
COMMENT ON COLUMN identity.dict_item.status IS '状态：ENABLED / DISABLED';
COMMENT ON COLUMN identity.dict_item.created_at IS '创建时间';
COMMENT ON COLUMN identity.dict_item.updated_at IS '更新时间';
COMMENT ON COLUMN identity.dict_item.deleted IS '软删标记';
COMMENT ON COLUMN identity.dict_item.trace_id IS '请求 traceId';

COMMENT ON TABLE identity.system_config IS '系统配置项';
COMMENT ON COLUMN identity.system_config.id IS '主键';
COMMENT ON COLUMN identity.system_config.config_key IS '配置键（唯一）';
COMMENT ON COLUMN identity.system_config.config_value IS '配置值（敏感项加密存储）';
COMMENT ON COLUMN identity.system_config.category IS '分类：SYS / PAYMENT / LLM / MINIAPP / OSS 等';
COMMENT ON COLUMN identity.system_config.is_secret IS '是否敏感（界面脱敏）';
COMMENT ON COLUMN identity.system_config.remark IS '备注';
COMMENT ON COLUMN identity.system_config.created_at IS '创建时间';
COMMENT ON COLUMN identity.system_config.updated_at IS '更新时间';
COMMENT ON COLUMN identity.system_config.deleted IS '软删标记';
COMMENT ON COLUMN identity.system_config.trace_id IS '请求 traceId';

COMMENT ON TABLE identity.file_object IS '对象存储文件元数据（MinIO objectKey）';
COMMENT ON COLUMN identity.file_object.id IS '主键';
COMMENT ON COLUMN identity.file_object.bucket IS '桶名';
COMMENT ON COLUMN identity.file_object.object_key IS '对象键（唯一）';
COMMENT ON COLUMN identity.file_object.content_type IS 'MIME 类型';
COMMENT ON COLUMN identity.file_object.size_bytes IS '文件大小（字节）';
COMMENT ON COLUMN identity.file_object.biz_type IS '业务类型：PRODUCT / BRAND / AFTER_SALE / AVATAR / OTHER';
COMMENT ON COLUMN identity.file_object.uploader_id IS '上传人用户 ID';
COMMENT ON COLUMN identity.file_object.visibility IS '可见性：PUBLIC公开 / PRIVATE私有';
COMMENT ON COLUMN identity.file_object.created_at IS '创建时间';
COMMENT ON COLUMN identity.file_object.updated_at IS '更新时间';
COMMENT ON COLUMN identity.file_object.deleted IS '软删标记';
COMMENT ON COLUMN identity.file_object.trace_id IS '请求 traceId';

COMMENT ON TABLE commerce.category IS '商品类目树';
COMMENT ON COLUMN commerce.category.id IS '主键';
COMMENT ON COLUMN commerce.category.parent_id IS '父类目 ID，根为 0';
COMMENT ON COLUMN commerce.category.name IS '类目名称';
COMMENT ON COLUMN commerce.category.level IS '层级（从 1 起）';
COMMENT ON COLUMN commerce.category.sort IS '排序';
COMMENT ON COLUMN commerce.category.status IS '状态：ENABLED / DISABLED';
COMMENT ON COLUMN commerce.category.created_at IS '创建时间';
COMMENT ON COLUMN commerce.category.updated_at IS '更新时间';
COMMENT ON COLUMN commerce.category.deleted IS '软删标记';
COMMENT ON COLUMN commerce.category.trace_id IS '请求 traceId';

COMMENT ON TABLE commerce.brand IS '品牌';
COMMENT ON COLUMN commerce.brand.id IS '主键';
COMMENT ON COLUMN commerce.brand.name IS '品牌名称';
COMMENT ON COLUMN commerce.brand.logo IS 'Logo 的 MinIO objectKey';
COMMENT ON COLUMN commerce.brand.sort IS '排序';
COMMENT ON COLUMN commerce.brand.status IS '状态：ENABLED / DISABLED';
COMMENT ON COLUMN commerce.brand.created_at IS '创建时间';
COMMENT ON COLUMN commerce.brand.updated_at IS '更新时间';
COMMENT ON COLUMN commerce.brand.deleted IS '软删标记';
COMMENT ON COLUMN commerce.brand.trace_id IS '请求 traceId';

COMMENT ON TABLE commerce.attribute IS '类目属性（规格/参数）';
COMMENT ON COLUMN commerce.attribute.id IS '主键';
COMMENT ON COLUMN commerce.attribute.category_id IS '所属类目 ID';
COMMENT ON COLUMN commerce.attribute.name IS '属性名（如颜色）';
COMMENT ON COLUMN commerce.attribute.type IS '类型：SPEC规格（影响SKU） / PARAM参数（仅展示）';
COMMENT ON COLUMN commerce.attribute.options IS '可选值 JSON 数组';
COMMENT ON COLUMN commerce.attribute.sort IS '排序';
COMMENT ON COLUMN commerce.attribute.created_at IS '创建时间';
COMMENT ON COLUMN commerce.attribute.updated_at IS '更新时间';
COMMENT ON COLUMN commerce.attribute.deleted IS '软删标记';
COMMENT ON COLUMN commerce.attribute.trace_id IS '请求 traceId';

COMMENT ON TABLE commerce.spu IS '标准产品单元（SPU）';
COMMENT ON COLUMN commerce.spu.id IS '主键';
COMMENT ON COLUMN commerce.spu.category_id IS '类目 ID';
COMMENT ON COLUMN commerce.spu.brand_id IS '品牌 ID';
COMMENT ON COLUMN commerce.spu.name IS '商品名称';
COMMENT ON COLUMN commerce.spu.subtitle IS '副标题';
COMMENT ON COLUMN commerce.spu.pics IS '主图 objectKey 列表 JSONB';
COMMENT ON COLUMN commerce.spu.videos IS '视频 objectKey 列表 JSONB';
COMMENT ON COLUMN commerce.spu.detail_html IS '富文本详情';
COMMENT ON COLUMN commerce.spu.status IS '状态：DRAFT草稿 / ON上架 / OFF下架';
COMMENT ON COLUMN commerce.spu.created_at IS '创建时间';
COMMENT ON COLUMN commerce.spu.updated_at IS '更新时间';
COMMENT ON COLUMN commerce.spu.deleted IS '软删标记';
COMMENT ON COLUMN commerce.spu.trace_id IS '请求 traceId';

COMMENT ON TABLE commerce.sku IS '库存量单位（SKU）；库存不在此表，见 stock';
COMMENT ON COLUMN commerce.sku.id IS '主键';
COMMENT ON COLUMN commerce.sku.spu_id IS '所属 SPU ID';
COMMENT ON COLUMN commerce.sku.title IS 'SKU 标题';
COMMENT ON COLUMN commerce.sku.price IS '售价';
COMMENT ON COLUMN commerce.sku.cost_price IS '成本价';
COMMENT ON COLUMN commerce.sku.bar_code IS '条码（唯一，可空）';
COMMENT ON COLUMN commerce.sku.specs IS '规格快照 JSON（如 {"颜色":"黑色"}）';
COMMENT ON COLUMN commerce.sku.pic IS 'SKU 图 objectKey';
COMMENT ON COLUMN commerce.sku.status IS '状态：ENABLED / DISABLED';
COMMENT ON COLUMN commerce.sku.created_at IS '创建时间';
COMMENT ON COLUMN commerce.sku.updated_at IS '更新时间';
COMMENT ON COLUMN commerce.sku.deleted IS '软删标记';
COMMENT ON COLUMN commerce.sku.trace_id IS '请求 traceId';

COMMENT ON TABLE commerce.warehouse IS '仓库';
COMMENT ON COLUMN commerce.warehouse.id IS '主键';
COMMENT ON COLUMN commerce.warehouse.name IS '仓库名称';
COMMENT ON COLUMN commerce.warehouse.status IS '状态：ENABLED / DISABLED';
COMMENT ON COLUMN commerce.warehouse.created_at IS '创建时间';
COMMENT ON COLUMN commerce.warehouse.updated_at IS '更新时间';
COMMENT ON COLUMN commerce.warehouse.deleted IS '软删标记';
COMMENT ON COLUMN commerce.warehouse.trace_id IS '请求 traceId';

COMMENT ON TABLE commerce.stock IS 'SKU×仓库库存；可售=physical_stock-locked_stock（不落库）';
COMMENT ON COLUMN commerce.stock.id IS '主键';
COMMENT ON COLUMN commerce.stock.sku_id IS 'SKU ID';
COMMENT ON COLUMN commerce.stock.warehouse_id IS '仓库 ID';
COMMENT ON COLUMN commerce.stock.physical_stock IS '实物总库存';
COMMENT ON COLUMN commerce.stock.locked_stock IS '锁定库存（下单占用）';
COMMENT ON COLUMN commerce.stock.low_threshold IS '低库存预警阈值';
COMMENT ON COLUMN commerce.stock.version IS '乐观锁版本号';
COMMENT ON COLUMN commerce.stock.created_at IS '创建时间';
COMMENT ON COLUMN commerce.stock.updated_at IS '更新时间';
COMMENT ON COLUMN commerce.stock.deleted IS '软删标记';
COMMENT ON COLUMN commerce.stock.trace_id IS '请求 traceId';

COMMENT ON TABLE commerce.stock_log IS '库存流水';
COMMENT ON COLUMN commerce.stock_log.id IS '主键';
COMMENT ON COLUMN commerce.stock_log.sku_id IS 'SKU ID';
COMMENT ON COLUMN commerce.stock_log.warehouse_id IS '仓库 ID';
COMMENT ON COLUMN commerce.stock_log.change_qty IS '变动数量（可正可负）';
COMMENT ON COLUMN commerce.stock_log.type IS '类型：INIT/ADJUST/IN/OUT/ORDER_LOCK/ORDER_UNLOCK/SHIP 等';
COMMENT ON COLUMN commerce.stock_log.reason IS '原因说明';
COMMENT ON COLUMN commerce.stock_log.operator_id IS '操作人 ID';
COMMENT ON COLUMN commerce.stock_log.created_at IS '创建时间';
COMMENT ON COLUMN commerce.stock_log.updated_at IS '更新时间';
COMMENT ON COLUMN commerce.stock_log.deleted IS '软删标记';
COMMENT ON COLUMN commerce.stock_log.trace_id IS '请求 traceId';

COMMENT ON TABLE commerce."order" IS '订单主表';
COMMENT ON COLUMN commerce."order".id IS '主键';
COMMENT ON COLUMN commerce."order".order_no IS '订单号（唯一）';
COMMENT ON COLUMN commerce."order".customer_id IS '顾客 ID';
COMMENT ON COLUMN commerce."order".customer_name IS '顾客姓名快照';
COMMENT ON COLUMN commerce."order".status IS '状态：PENDING_PAY/PENDING_SHIP/PENDING_RECEIVE/COMPLETED/CLOSED 等';
COMMENT ON COLUMN commerce."order".total_amount IS '商品合计金额';
COMMENT ON COLUMN commerce."order".pay_amount IS '实付金额';
COMMENT ON COLUMN commerce."order".freight IS '运费';
COMMENT ON COLUMN commerce."order".address_snapshot IS '收货地址快照 JSONB';
COMMENT ON COLUMN commerce."order".remark IS '后台备注';
COMMENT ON COLUMN commerce."order".paid_at IS '支付时间';
COMMENT ON COLUMN commerce."order".ship_at IS '发货时间';
COMMENT ON COLUMN commerce."order".received_at IS '确认收货时间';
COMMENT ON COLUMN commerce."order".close_reason IS '关单原因';
COMMENT ON COLUMN commerce."order".created_at IS '创建时间';
COMMENT ON COLUMN commerce."order".updated_at IS '更新时间';
COMMENT ON COLUMN commerce."order".deleted IS '软删标记（订单一般不软删，预留）';
COMMENT ON COLUMN commerce."order".trace_id IS '请求 traceId';

COMMENT ON TABLE commerce.order_item IS '订单明细';
COMMENT ON COLUMN commerce.order_item.id IS '主键';
COMMENT ON COLUMN commerce.order_item.order_id IS '订单 ID';
COMMENT ON COLUMN commerce.order_item.sku_id IS 'SKU ID';
COMMENT ON COLUMN commerce.order_item.spu_id IS 'SPU ID';
COMMENT ON COLUMN commerce.order_item.title IS '商品标题快照';
COMMENT ON COLUMN commerce.order_item.pic IS '商品图 objectKey 快照';
COMMENT ON COLUMN commerce.order_item.price IS '成交单价';
COMMENT ON COLUMN commerce.order_item.quantity IS '数量';
COMMENT ON COLUMN commerce.order_item.specs IS '规格快照 JSONB';
COMMENT ON COLUMN commerce.order_item.subtotal IS '小计';
COMMENT ON COLUMN commerce.order_item.created_at IS '创建时间';
COMMENT ON COLUMN commerce.order_item.updated_at IS '更新时间';
COMMENT ON COLUMN commerce.order_item.deleted IS '软删标记';
COMMENT ON COLUMN commerce.order_item.trace_id IS '请求 traceId';

COMMENT ON TABLE commerce.payment IS '支付单';
COMMENT ON COLUMN commerce.payment.id IS '主键';
COMMENT ON COLUMN commerce.payment.order_id IS '订单 ID';
COMMENT ON COLUMN commerce.payment.channel IS '渠道：SANDBOX 等';
COMMENT ON COLUMN commerce.payment.amount IS '支付金额';
COMMENT ON COLUMN commerce.payment.status IS '状态：如 PAID';
COMMENT ON COLUMN commerce.payment.trade_no IS '渠道交易号';
COMMENT ON COLUMN commerce.payment.paid_at IS '支付成功时间';
COMMENT ON COLUMN commerce.payment.mock_param IS '沙箱模拟参数';
COMMENT ON COLUMN commerce.payment.created_at IS '创建时间';
COMMENT ON COLUMN commerce.payment.updated_at IS '更新时间';
COMMENT ON COLUMN commerce.payment.deleted IS '软删标记';
COMMENT ON COLUMN commerce.payment.trace_id IS '请求 traceId';

COMMENT ON TABLE commerce.shipment IS '物流发货单';
COMMENT ON COLUMN commerce.shipment.id IS '主键';
COMMENT ON COLUMN commerce.shipment.order_id IS '订单 ID';
COMMENT ON COLUMN commerce.shipment.carrier IS '承运商（可模拟）';
COMMENT ON COLUMN commerce.shipment.tracking_no IS '运单号';
COMMENT ON COLUMN commerce.shipment.status IS '物流状态';
COMMENT ON COLUMN commerce.shipment.traces IS '轨迹 JSONB';
COMMENT ON COLUMN commerce.shipment.created_at IS '创建时间';
COMMENT ON COLUMN commerce.shipment.updated_at IS '更新时间';
COMMENT ON COLUMN commerce.shipment.deleted IS '软删标记';
COMMENT ON COLUMN commerce.shipment.trace_id IS '请求 traceId';

COMMENT ON TABLE commerce.after_sale IS '售后单';
COMMENT ON COLUMN commerce.after_sale.id IS '主键';
COMMENT ON COLUMN commerce.after_sale.after_sale_no IS '售后单号（唯一）';
COMMENT ON COLUMN commerce.after_sale.order_id IS '关联订单 ID';
COMMENT ON COLUMN commerce.after_sale.customer_id IS '顾客 ID';
COMMENT ON COLUMN commerce.after_sale.type IS '类型：REFUND仅退款 / REFUND_RETURN退货退款';
COMMENT ON COLUMN commerce.after_sale.reason IS '申请原因';
COMMENT ON COLUMN commerce.after_sale.evidence_pics IS '凭证图 objectKey 列表 JSONB';
COMMENT ON COLUMN commerce.after_sale.status IS '状态：PENDING_AUDIT/REFUNDING/COMPLETED/REJECTED 等';
COMMENT ON COLUMN commerce.after_sale.refund_amount IS '退款金额';
COMMENT ON COLUMN commerce.after_sale.audit_remark IS '审核备注';
COMMENT ON COLUMN commerce.after_sale.created_at IS '创建时间';
COMMENT ON COLUMN commerce.after_sale.updated_at IS '更新时间';
COMMENT ON COLUMN commerce.after_sale.deleted IS '软删标记';
COMMENT ON COLUMN commerce.after_sale.trace_id IS '请求 traceId';

-- ===================== seed: identity =====================
INSERT INTO identity.dept (id, parent_id, name, sort, status)
SELECT 1, 0, '总部', 0, 'ENABLED'
WHERE NOT EXISTS (SELECT 1 FROM identity.dept WHERE id = 1);

-- password: Admin@123 (bcrypt)
INSERT INTO identity.admin_user (id, username, password, real_name, phone, email, dept_id, status)
SELECT 1, 'admin', '$2a$10$dXmCeRpT/BidfYGWMXobiei2d90Zyulf6UaVGR3FGEjuxxaI/Zowu',
       '超级管理员', '13800000000', 'admin@shangpivot.com', 1, 'ENABLED'
WHERE NOT EXISTS (SELECT 1 FROM identity.admin_user WHERE id = 1);

INSERT INTO identity.role (id, code, name, data_scope, status, remark)
SELECT 1, 'SUPER_ADMIN', '超级管理员', 'ALL', 'ENABLED', '拥有全部权限'
WHERE NOT EXISTS (SELECT 1 FROM identity.role WHERE id = 1);

INSERT INTO identity.admin_role (admin_user_id, role_id)
SELECT 1, 1
WHERE NOT EXISTS (SELECT 1 FROM identity.admin_role WHERE admin_user_id = 1 AND role_id = 1);

-- permission tree aligned with sidebar
INSERT INTO identity.permission (id, parent_id, code, name, type, path, component, icon, sort, visible)
SELECT * FROM (VALUES
    (100::bigint, 0::bigint, 'dashboard', '工作台', 'MENU', '/dashboard', 'dashboard/index', 'Odometer', 1, true),
    (200::bigint, 0::bigint, 'product', '商品中心', 'DIR', NULL, NULL, 'Goods', 2, true),
    (210::bigint, 200::bigint, 'products', '商品列表', 'MENU', '/products', 'product/list', 'List', 1, true),
    (211::bigint, 210::bigint, 'product:add', '新增商品', 'BUTTON', NULL, NULL, NULL, 1, true),
    (212::bigint, 210::bigint, 'product:edit', '编辑商品', 'BUTTON', NULL, NULL, NULL, 2, true),
    (213::bigint, 210::bigint, 'product:on', '上架', 'BUTTON', NULL, NULL, NULL, 3, true),
    (214::bigint, 210::bigint, 'product:off', '下架', 'BUTTON', NULL, NULL, NULL, 4, true),
    (220::bigint, 200::bigint, 'product-edit', '商品编辑', 'MENU', '/products/edit', 'product/edit', 'Edit', 2, false),
    (230::bigint, 200::bigint, 'categories', '类目管理', 'MENU', '/categories', 'product/category', 'Menu', 3, true),
    (240::bigint, 200::bigint, 'brands', '品牌管理', 'MENU', '/brands', 'product/brand', 'Medal', 4, true),
    (300::bigint, 0::bigint, 'inventory', '库存管理', 'MENU', '/inventory', 'inventory/index', 'Box', 3, true),
    (301::bigint, 300::bigint, 'stock:adjust', '库存调整', 'BUTTON', NULL, NULL, NULL, 1, true),
    (400::bigint, 0::bigint, 'order', '订单中心', 'DIR', NULL, NULL, 'Document', 4, true),
    (410::bigint, 400::bigint, 'orders', '订单列表', 'MENU', '/orders', 'order/list', 'Tickets', 1, true),
    (411::bigint, 410::bigint, 'order:ship', '发货', 'BUTTON', NULL, NULL, NULL, 1, true),
    (420::bigint, 400::bigint, 'aftersales', '售后管理', 'MENU', '/aftersales', 'order/aftersale', 'RefreshLeft', 2, true),
    (421::bigint, 420::bigint, 'aftersale:audit', '售后审核', 'BUTTON', NULL, NULL, NULL, 1, true),
    (500::bigint, 0::bigint, 'system', '系统管理', 'DIR', NULL, NULL, 'Setting', 5, true),
    (510::bigint, 500::bigint, 'users', '用户管理', 'MENU', '/users', 'system/user', 'User', 1, true),
    (520::bigint, 500::bigint, 'roles', '角色管理', 'MENU', '/roles', 'system/role', 'UserFilled', 2, true),
    (530::bigint, 500::bigint, 'menus', '菜单与权限', 'MENU', '/menus', 'system/permission', 'Menu', 3, true),
    (540::bigint, 500::bigint, 'depts', '部门管理', 'MENU', '/depts', 'system/dept', 'OfficeBuilding', 4, true),
    (550::bigint, 500::bigint, 'dicts', '字典管理', 'MENU', '/dicts', 'system/dict', 'Collection', 5, true),
    (560::bigint, 500::bigint, 'logs', '操作日志', 'MENU', '/logs', 'system/log', 'Document', 6, true),
    (570::bigint, 500::bigint, 'settings', '系统设置', 'MENU', '/settings', 'system/settings', 'Tools', 7, true)
) AS v(id, parent_id, code, name, type, path, component, icon, sort, visible)
WHERE NOT EXISTS (SELECT 1 FROM identity.permission p WHERE p.id = v.id);

INSERT INTO identity.role_permission (role_id, permission_id)
SELECT 1, p.id FROM identity.permission p
WHERE NOT EXISTS (SELECT 1 FROM identity.role_permission rp WHERE rp.role_id = 1 AND rp.permission_id = p.id);

INSERT INTO identity.dict (id, code, name, remark)
SELECT 1, 'order_status', '订单状态', '订单状态枚举'
WHERE NOT EXISTS (SELECT 1 FROM identity.dict WHERE id = 1);

INSERT INTO identity.dict_item (id, dict_id, label, value, sort)
SELECT * FROM (VALUES
    (11::bigint, 1::bigint, '待付款', 'PENDING_PAY', 1),
    (12::bigint, 1::bigint, '待发货', 'PENDING_SHIP', 2),
    (13::bigint, 1::bigint, '待收货', 'PENDING_RECEIVE', 3),
    (14::bigint, 1::bigint, '已完成', 'COMPLETED', 4),
    (15::bigint, 1::bigint, '已关闭', 'CLOSED', 5)
) AS v(id, dict_id, label, value, sort)
WHERE NOT EXISTS (SELECT 1 FROM identity.dict_item i WHERE i.id = v.id);

INSERT INTO identity.system_config (id, config_key, config_value, category, is_secret, remark)
SELECT * FROM (VALUES
    (1::bigint, 'site.name', 'Shang Pivot', 'SYS', false, '站点名称'),
    (2::bigint, 'payment.sandbox.enabled', 'true', 'PAYMENT', false, '沙箱支付开关')
) AS v(id, config_key, config_value, category, is_secret, remark)
WHERE NOT EXISTS (SELECT 1 FROM identity.system_config c WHERE c.id = v.id);

-- ===================== seed: commerce =====================
INSERT INTO commerce.warehouse (id, name, status)
SELECT 1, '默认仓', 'ENABLED'
WHERE NOT EXISTS (SELECT 1 FROM commerce.warehouse WHERE id = 1);

INSERT INTO commerce.category (id, parent_id, name, level, sort, status)
SELECT * FROM (VALUES
    (1::bigint, 0::bigint, '数码电器', 1, 1, 'ENABLED'),
    (2::bigint, 1::bigint, '手机通讯', 2, 1, 'ENABLED'),
    (3::bigint, 0::bigint, '家居生活', 1, 2, 'ENABLED')
) AS v(id, parent_id, name, level, sort, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.category c WHERE c.id = v.id);

INSERT INTO commerce.brand (id, name, logo, sort, status)
SELECT 1, '示例品牌', 'demo/brand/logo.png', 1, 'ENABLED'
WHERE NOT EXISTS (SELECT 1 FROM commerce.brand WHERE id = 1);

INSERT INTO commerce.attribute (id, category_id, name, type, options, sort)
SELECT 1, 2, '颜色', 'SPEC', '["黑色","白色"]'::jsonb, 1
WHERE NOT EXISTS (SELECT 1 FROM commerce.attribute WHERE id = 1);

INSERT INTO commerce.spu (id, category_id, brand_id, name, subtitle, pics, videos, detail_html, status)
SELECT 1, 2, 1, '示例智能手机 X1', '演示商品 · 支持上下架与库存调整',
       '["demo/product/x1-1.jpg","demo/product/x1-2.jpg"]'::jsonb,
       '[]'::jsonb,
       '<p>这是演示商品详情</p>',
       'ON'
WHERE NOT EXISTS (SELECT 1 FROM commerce.spu WHERE id = 1);

INSERT INTO commerce.sku (id, spu_id, title, price, cost_price, bar_code, specs, pic, status)
SELECT * FROM (VALUES
    (1::bigint, 1::bigint, '示例智能手机 X1 黑色', 2999.00::numeric, 2000.00::numeric, 'DEMO-SKU-BLACK',
     '{"颜色":"黑色"}'::jsonb, 'demo/product/x1-black.jpg', 'ENABLED'),
    (2::bigint, 1::bigint, '示例智能手机 X1 白色', 2999.00::numeric, 2000.00::numeric, 'DEMO-SKU-WHITE',
     '{"颜色":"白色"}'::jsonb, 'demo/product/x1-white.jpg', 'ENABLED')
) AS v(id, spu_id, title, price, cost_price, bar_code, specs, pic, status)
WHERE NOT EXISTS (SELECT 1 FROM commerce.sku s WHERE s.id = v.id);

INSERT INTO commerce.stock (id, sku_id, warehouse_id, physical_stock, locked_stock, low_threshold)
SELECT * FROM (VALUES
    (1::bigint, 1::bigint, 1::bigint, 100, 0, 10),
    (2::bigint, 2::bigint, 1::bigint, 80, 0, 10)
) AS v(id, sku_id, warehouse_id, physical_stock, locked_stock, low_threshold)
WHERE NOT EXISTS (SELECT 1 FROM commerce.stock s WHERE s.id = v.id);

INSERT INTO commerce.stock_log (id, sku_id, warehouse_id, change_qty, type, reason, operator_id)
SELECT * FROM (VALUES
    (1::bigint, 1::bigint, 1::bigint, 100, 'INIT', '种子初始化', 1::bigint),
    (2::bigint, 2::bigint, 1::bigint, 80, 'INIT', '种子初始化', 1::bigint)
) AS v(id, sku_id, warehouse_id, change_qty, type, reason, operator_id)
WHERE NOT EXISTS (SELECT 1 FROM commerce.stock_log l WHERE l.id = v.id);

INSERT INTO commerce."order" (id, order_no, customer_id, customer_name, status, total_amount, pay_amount, freight,
                              address_snapshot, paid_at)
SELECT 1, 'SO202609130001', 9001, '演示顾客', 'PENDING_SHIP', 2999.00, 2999.00, 0,
       '{"name":"张三","phone":"13900000001","region":"上海市浦东新区","detail":"演示路 1 号"}'::jsonb,
       NOW() - INTERVAL '1 day'
WHERE NOT EXISTS (SELECT 1 FROM commerce."order" o WHERE o.id = 1);

INSERT INTO commerce.order_item (id, order_id, sku_id, spu_id, title, pic, price, quantity, specs, subtotal)
SELECT 1, 1, 1, 1, '示例智能手机 X1 黑色', 'demo/product/x1-black.jpg', 2999.00, 1,
       '{"颜色":"黑色"}'::jsonb, 2999.00
WHERE NOT EXISTS (SELECT 1 FROM commerce.order_item WHERE id = 1);

INSERT INTO commerce.payment (id, order_id, channel, amount, status, trade_no, paid_at)
SELECT 1, 1, 'SANDBOX', 2999.00, 'PAID', 'SANDBOX-TRADE-001', NOW() - INTERVAL '1 day'
WHERE NOT EXISTS (SELECT 1 FROM commerce.payment WHERE id = 1);

INSERT INTO commerce.after_sale (id, after_sale_no, order_id, customer_id, type, reason, evidence_pics, status, refund_amount)
SELECT 1, 'AS202609130001', 1, 9001, 'REFUND', '不想要了（演示）',
       '["demo/aftersale/evidence-1.jpg"]'::jsonb, 'PENDING_AUDIT', 2999.00
WHERE NOT EXISTS (SELECT 1 FROM commerce.after_sale WHERE id = 1);
