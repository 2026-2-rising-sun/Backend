package com.shoppinglive.common.kafka;

import com.shoppinglive.contracts.events.LiveBroadcastEventContract;

public final class KafkaTopics {

    public static final String ORDER_PLACED = "commerce.order-placed.v1";
    public static final String LIVE_BROADCAST = LiveBroadcastEventContract.TOPIC_NAME;

    private KafkaTopics() {
    }
}
