// Clear credentials even when logout fails; the next test starts with an explicit login.
for (const key of ['access_token', 'refresh_token', 'member_id', 'member_role']) pm.environment.unset(key);
