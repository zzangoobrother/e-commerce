package com.ecommerce.auth;

// refresh 토큰 소유자 종류 — 단일 refresh_tokens 테이블을 어드민/고객이 공유한다.
public enum OwnerType {
    ADMIN,
    CUSTOMER
}
