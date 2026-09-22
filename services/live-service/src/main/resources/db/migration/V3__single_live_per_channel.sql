-- 같은 channelArn 으로 두 방송이 동시에 LIVE 가 되는 것을 DB 불변조건으로 막는다.
-- 애플리케이션 lock 이 아니라 PostgreSQL partial unique index 로 보장한다.
CREATE UNIQUE INDEX uk_broadcast_live_channel
    ON broadcast (channel_arn)
    WHERE status = 'LIVE';
