-- 补全业务按钮权限，并挂到对应菜单下；同步赋给超级管理员
INSERT INTO identity.permission (id, parent_id, code, name, type, path, component, icon, sort, visible, status)
SELECT * FROM (VALUES
    (231::bigint, 230::bigint, 'category:add', '新增类目', 'BUTTON', NULL, NULL, NULL, 1, true, 'ENABLED'),
    (232::bigint, 230::bigint, 'category:edit', '编辑类目', 'BUTTON', NULL, NULL, NULL, 2, true, 'ENABLED'),
    (233::bigint, 230::bigint, 'category:delete', '删除类目', 'BUTTON', NULL, NULL, NULL, 3, true, 'ENABLED'),
    (241::bigint, 240::bigint, 'brand:add', '新增品牌', 'BUTTON', NULL, NULL, NULL, 1, true, 'ENABLED'),
    (242::bigint, 240::bigint, 'brand:edit', '编辑品牌', 'BUTTON', NULL, NULL, NULL, 2, true, 'ENABLED'),
    (243::bigint, 240::bigint, 'brand:delete', '删除品牌', 'BUTTON', NULL, NULL, NULL, 3, true, 'ENABLED'),
    (511::bigint, 510::bigint, 'user:add', '新增用户', 'BUTTON', NULL, NULL, NULL, 1, true, 'ENABLED'),
    (512::bigint, 510::bigint, 'user:edit', '编辑用户', 'BUTTON', NULL, NULL, NULL, 2, true, 'ENABLED'),
    (513::bigint, 510::bigint, 'user:reset', '重置密码', 'BUTTON', NULL, NULL, NULL, 3, true, 'ENABLED'),
    (514::bigint, 510::bigint, 'user:status', '启停用户', 'BUTTON', NULL, NULL, NULL, 4, true, 'ENABLED'),
    (521::bigint, 520::bigint, 'role:add', '新增角色', 'BUTTON', NULL, NULL, NULL, 1, true, 'ENABLED'),
    (522::bigint, 520::bigint, 'role:edit', '编辑角色', 'BUTTON', NULL, NULL, NULL, 2, true, 'ENABLED'),
    (523::bigint, 520::bigint, 'role:delete', '删除角色', 'BUTTON', NULL, NULL, NULL, 3, true, 'ENABLED'),
    (524::bigint, 520::bigint, 'role:perm', '分配权限', 'BUTTON', NULL, NULL, NULL, 4, true, 'ENABLED'),
    (531::bigint, 530::bigint, 'menu:add', '新增菜单', 'BUTTON', NULL, NULL, NULL, 1, true, 'ENABLED'),
    (532::bigint, 530::bigint, 'menu:edit', '编辑菜单', 'BUTTON', NULL, NULL, NULL, 2, true, 'ENABLED'),
    (533::bigint, 530::bigint, 'menu:delete', '删除菜单', 'BUTTON', NULL, NULL, NULL, 3, true, 'ENABLED'),
    (541::bigint, 540::bigint, 'dept:add', '新增部门', 'BUTTON', NULL, NULL, NULL, 1, true, 'ENABLED'),
    (542::bigint, 540::bigint, 'dept:edit', '编辑部门', 'BUTTON', NULL, NULL, NULL, 2, true, 'ENABLED'),
    (543::bigint, 540::bigint, 'dept:delete', '删除部门', 'BUTTON', NULL, NULL, NULL, 3, true, 'ENABLED'),
    (551::bigint, 550::bigint, 'dict:add', '新增字典', 'BUTTON', NULL, NULL, NULL, 1, true, 'ENABLED'),
    (552::bigint, 550::bigint, 'dict:edit', '编辑字典', 'BUTTON', NULL, NULL, NULL, 2, true, 'ENABLED'),
    (553::bigint, 550::bigint, 'dict:delete', '删除字典', 'BUTTON', NULL, NULL, NULL, 3, true, 'ENABLED'),
    (561::bigint, 560::bigint, 'log:export', '导出日志', 'BUTTON', NULL, NULL, NULL, 1, true, 'ENABLED'),
    (571::bigint, 570::bigint, 'settings:edit', '修改配置', 'BUTTON', NULL, NULL, NULL, 1, true, 'ENABLED')
) AS v(id, parent_id, code, name, type, path, component, icon, sort, visible, status)
WHERE NOT EXISTS (SELECT 1 FROM identity.permission p WHERE p.id = v.id OR p.code = v.code);

-- 新权限赋给超级管理员
INSERT INTO identity.role_permission (role_id, permission_id)
SELECT 1, p.id
FROM identity.permission p
WHERE p.id IN (
    231, 232, 233, 241, 242, 243,
    511, 512, 513, 514,
    521, 522, 523, 524,
    531, 532, 533,
    541, 542, 543,
    551, 552, 553,
    561, 571
)
AND NOT EXISTS (
    SELECT 1 FROM identity.role_permission rp
    WHERE rp.role_id = 1 AND rp.permission_id = p.id
);
