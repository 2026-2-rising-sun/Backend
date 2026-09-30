package com.shoppinglive.common.security;

/** 서버 설정에 등록한 키로 확인한 호출 서비스. 사용자 UUID/역할과는 별개다. */
public record AuthenticatedServiceCaller(String serviceId) { }
