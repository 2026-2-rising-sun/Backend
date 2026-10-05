// Install on GET /v1/members/me. Role comes from the authenticated server profile.
for (const key of ['member_id', 'member_role']) pm.environment.unset(key);
if (pm.response.code === 200) {
  const value = pm.response.json();
  if (value.success !== true || !value.data?.memberId || value.data.roles?.length !== 1
      || !['USER', 'SELLER'].includes(value.data.roles[0])) throw new Error('Expected a USER or SELLER profile');
  pm.environment.set('member_id', value.data.memberId);
  pm.environment.set('member_role', value.data.roles[0]);
}
