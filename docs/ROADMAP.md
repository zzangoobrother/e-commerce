# 이커머스 프로젝트 로드맵

> 마지막 갱신: 2026-06-12 (장바구니 사이클 문서 동기화)

## 완료된 사이클

| 사이클 | 기간 | 내용 | 상태 |
|--------|------|------|------|
| 1. 이커머스 골격 | 2026-05-31 ~ 06-03 | 모노레포(Spring Boot 4 + Next.js 16 + MySQL), Supplier/Product 도메인, 스토어/어드민 화면, 시드 | main 머지됨 (PR #1, #2) |
| 2. 골격 후속 정리 | 2026-06-03 | create() 이중 세팅 제거, 공급사명 유니크 제약, DB 설정 환경변수화, 메타데이터 한국어화 | main 머지됨 (PR #3) |
| 3. 어드민 인증 | 2026-06-03 | JWT 로그인(HS256), `/api/admin/**` Bearer 보호, 어드민 시드, 로그인 화면, proxy.ts 경로 보호, 로그아웃 | main 머지됨 (PR #4~#6) |
| 4. 보안 보완 | 2026-06-04 | httpOnly 쿠키 전환, 401/로그아웃 쿠키 삭제, 로그인 시도 제한(IP 기준 5회/15분), 타이밍 공격 완화, 만료 토큰 401 테스트, deprecated API 정리, JWT 시크릿 분리, 문서 단일 출처화 (핵심 8건) | main 머지됨 (PR #7) |
| 5. 토큰 수명 주기 | 2026-06-06 | access 15분 stateless + refresh 7일 opaque(MySQL 해시 저장), 회전·재사용 탐지, 로그아웃 시 서버 refresh 폐기, 401 자동 갱신(/api/admin/refresh), 프론트 쿠키 2개 운영 | main 머지됨 (PR #8) |
| 6. 보안 보완 3차 | 2026-06-06 | 로그인 시도 제한 고정 윈도우 전환(무기한 누적 약점 제거) | main 머지됨 (PR #9) |
| 7. 고객 인증 | 2026-06-07 | 고객 회원가입/로그인/로그아웃, refresh 토큰 다형 소유 일반화(어드민/고객 공유), JWT role 클레임으로 어드민/고객 권한 분리(고객 토큰 어드민 API 차단), 가입 auto-login | main 머지됨 (PR #10) |
| 8. 재사용 탐지 롤백 결함 수정 | 2026-06-10 | refresh 재사용 탐지의 일괄 폐기가 같은 트랜잭션 예외 롤백으로 유실되던 잠복 결함 수정(TokenTheftResponder, REQUIRES_NEW 분리), @SpringBootTest 커밋 경계 회귀 테스트 | `feature/refresh-reuse-rollback-fix` 브랜치 (머지 대기) |
| 9. 장바구니 | 2026-06-12 | CartItem 플랫 모델(재담기 수량 가산), 고객 전용 보호(`hasRole('CUSTOMER')`), 고객 보호 인프라(자동 갱신 `/refresh`·proxy `/cart` 보호), 담기/수량변경/삭제/조회 UI | `feature/cart` 브랜치 (머지 대기) |

각 사이클의 상세 설계/플랜: `docs/superpowers/specs/`, `docs/superpowers/plans/`

## 다음 사이클 후보 (우선순위순)

### 후보 1 (조건부 보류): 시도 제한 공유 저장소 (다중 인스턴스)

2026-06-06 보안 보완 3차 사이클로 **인메모리 시도 제한 로직 개선(고정 윈도우 전환)이 완료**됐다.
무기한 누적 약점이 제거되어 단일 인스턴스 운영에서의 한계는 해소됐다.

**공유 저장소(Redis/MySQL) 도입은 조건부 보류**: 현재 단일 인스턴스 운영이라 인스턴스별 카운트 불일치가
실제 문제가 되지 않는다. 다중 인스턴스 배포가 실제 필요해질 때 진행한다.

남은 한계 목록은 README "보안 한계 > 남은 한계" 섹션을 단일 출처로 한다.

| 항목 | 상태 | 비고 |
|------|------|------|
| 인메모리 고정 윈도우 개선 | 완료 (2026-06-06) | 무기한 누적 약점 제거 |
| 공유 저장소(다중 인스턴스 카운트 공유) | 조건부 보류 | 다중 인스턴스 배포 시점에 진행 |

### 후보 2: 고객 회원가입/로그인 — 완료 (2026-06-07, 사이클 7)

사이클 7(고객 인증)으로 완료되었다. 상세는 완료된 사이클 표를 참조한다.

### 후보 3: 어드민 CRUD 폼

- 현재 어드민 화면은 조회만 가능 (생성/수정/삭제는 API만 존재)
- 공급사/상품 등록·수정·삭제 폼 UI
- 클라이언트 컴포넌트 + 폼 검증 패턴 도입 필요

### 후보 4: 주문/장바구니 도메인

장바구니는 사이클 9(2026-06-12)로 완료되었다. 상세는 완료된 사이클 표를 참조한다.

남은 범위는 **주문 도메인**: Order/OrderItem, 주문 생성 시 재고 차감·가격 고정, 주문 상태 관리 — 사이클 10 후보.

## 기술 스택 주의사항 (새 작업자/세션 필독)

이 프로젝트는 최신 버전을 사용하므로 학습 데이터 기준 지식과 다른 부분이 많다:

| 항목 | 이 프로젝트 | 흔한 오해 |
|------|-----------|----------|
| Spring Boot | 4.0.6 — 스타터명 변경 | `spring-boot-starter-web` (X) → `spring-boot-starter-webmvc` (O) |
| Security 스타터 | `spring-boot-starter-security-oauth2-resource-server` | 3.x의 `spring-boot-starter-oauth2-resource-server` (X) |
| Jackson | `tools.jackson.databind.ObjectMapper` (Jackson 3) | `com.fasterxml.jackson...` (X) |
| 테스트 어노테이션 | `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`, `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` | 3.x 경로 (X) |
| Next.js | 16.2.6 — `src/proxy.ts` (함수명 `proxy`) | `middleware.ts` (deprecated) |
| Next.js API | `cookies()`/`params`/`searchParams`는 async (await 필요) | 동기 접근 (X) |

## 운영 배포 전 체크리스트

- [ ] `JWT_SECRET` 환경변수 교체 (현재 dev 기본값)
- [ ] `ADMIN_USERNAME` / `ADMIN_PASSWORD` 환경변수 교체
- [ ] `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` 환경변수 교체
- [ ] CORS `allowedOrigins`를 운영 도메인으로 변경 (현재 localhost:3000 하드코딩)
- [x] 보안 보완 사이클(후보 1) 완료 — 핵심 8건 해소 (2026-06-04, `feature/admin-auth` 브랜치)
- [ ] 시드 전략 재검토 (다중 인스턴스 배포 시 DataSeeder race로 기동 실패 가능)
