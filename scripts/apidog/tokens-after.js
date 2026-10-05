// Install on login and refresh. Store tokens as local environment values; never shared values.
for (const key of ['access_token', 'refresh_token', 'member_id', 'member_role']) pm.environment.unset(key);
if (pm.response.code === 200) {
  const value = pm.response.json();
  if (value.success !== true || typeof value.data?.accessToken !== 'string' || !value.data.accessToken
      || typeof value.data?.refreshToken !== 'string' || !value.data.refreshToken) {
    throw new Error('Expected a successful token pair');
  }
  pm.environment.set('access_token', value.data.accessToken);
  pm.environment.set('refresh_token', value.data.refreshToken);
}
