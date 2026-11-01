-- Align inventory under product center for sidebar grouping; fix menus icon & order sort
UPDATE identity.permission
SET parent_id = 200, sort = 5
WHERE id = 300 AND code = 'inventory';

UPDATE identity.permission
SET sort = 3
WHERE id = 400 AND code = 'order';

UPDATE identity.permission
SET sort = 4
WHERE id = 500 AND code = 'system';

UPDATE identity.permission
SET icon = 'Grid'
WHERE id = 530 AND code = 'menus' AND (icon IS NULL OR icon = 'Menu');
