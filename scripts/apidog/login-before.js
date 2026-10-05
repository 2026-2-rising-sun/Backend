// Install on the local/mock login case only. Body: {"email":"{{login_email}}","password":"{{login_password}}"}.
for (const key of ['access_token', 'refresh_token', 'member_id', 'member_role']) pm.environment.unset(key);
if (!['local', 'mock'].includes(pm.environment.get('test_mode'))) throw new Error('Test login requires an explicit local or mock environment');
const login = pm.environment.get('login_name');
if (!['user', 'seller'].includes(login)) throw new Error('Select user or seller');
pm.environment.set('login_email', login + '@local.test');
pm.environment.set('login_password', login);
