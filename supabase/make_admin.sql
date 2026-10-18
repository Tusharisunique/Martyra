-- Run in the SQL Editor after creating the admin user in the dashboard.
-- Replace you@example.com with the real admin email.
-- The admin must sign in again afterwards to get a token with the new role.

update auth.users
set raw_app_meta_data = coalesce(raw_app_meta_data, '{}'::jsonb) || '{"role":"admin"}'
where email = 'you@example.com';
