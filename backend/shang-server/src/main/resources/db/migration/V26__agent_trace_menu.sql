-- V3.1 Agent 运行轨迹管理端菜单/权限
INSERT INTO identity.permission (id, parent_id, code, name, type, path, component, icon, sort, visible, status)
SELECT * FROM (VALUES
    (870::bigint, 800::bigint, 'cs-agent-runs', 'Agent 轨迹', 'MENU',
     '/customer-service/agent-runs', 'cs/agent-runs', 'Monitor', 7, true, 'ENABLED'),
    (871::bigint, 870::bigint, 'cs:agent:view', '轨迹查看', 'BUTTON', NULL, NULL, NULL, 1, true, 'ENABLED')
) AS v(id, parent_id, code, name, type, path, component, icon, sort, visible, status)
WHERE NOT EXISTS (SELECT 1 FROM identity.permission p WHERE p.id = v.id OR p.code = v.code);

INSERT INTO identity.role_permission (role_id, permission_id)
SELECT 1, p.id
FROM identity.permission p
WHERE p.id IN (870, 871)
AND NOT EXISTS (
    SELECT 1 FROM identity.role_permission rp
    WHERE rp.role_id = 1 AND rp.permission_id = p.id
);
