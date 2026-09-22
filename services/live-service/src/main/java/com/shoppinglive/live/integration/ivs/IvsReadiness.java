package com.shoppinglive.live.integration.ivs;

/** 영상 상태. 업무 상태(BroadcastStatus)와 분리한다. */
public enum IvsReadiness {
    /** LIVE + HEALTHY. */
    READY,
    /** 미송출 또는 STARVING. OBS 일시 단절이 여기에 해당하며 업무 상태는 LIVE 를 유지한다. */
    NOT_READY,
    /** 권한·통신·SDK 오류. 영상 영역만 실패하고 기본정보 응답은 계속 성공한다. */
    UNAVAILABLE
}
