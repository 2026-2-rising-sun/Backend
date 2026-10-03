package com.shoppinglive.live.stream.application;

/** SSE 한 건. name 이 null 이면 heartbeat comment 다. data 는 ApiResponse 봉투가 아닌 DTO 다. */
public record StreamEvent(String name, String id, Object data) {
    static final StreamEvent HEARTBEAT = new StreamEvent(null, null, null);

    public static StreamEvent of(final String name, final Object data) {
        return new StreamEvent(name, null, data);
    }
}
