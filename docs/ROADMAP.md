# 이커머스 프로젝트 로드맵

> 마지막 갱신: 2026-06-06 (토큰 수명 주기 사이클 문서 동기화)

## 완료된 사이클

| 사이클 | 기간 | 내용 | 상태 |
|--------|------|------|------|
| 1. 이커머스 골격 | 2026-05-31 ~ 06-03 | 모노레포(Spring Boot 4 + Next.js 16 + MySQL), Supplier/Product 도메인, 스토어/어드민 화면, 시드 | main 머지됨 (PR #1, #2) |
| 2. 골격 후속 정리 | 2026-06-03 | create() 이중 세팅 제거, 공급사명 유니크 제약, DB 설정 환경변수화, 메타데이터 한국어화 | main 머지됨 (PR #3) |
| 3. 어드민 인증 | 2026-06-03 | JWT 로그인(HS256), `/api/admin/**` Bearer 보호, 어드민 시드, 로그인 화면, proxy.ts 경로 보호, 로그아웃 | main 머지됨 (PR #4~#6) |
| 4. 보안 보완 | 2026-06-04 | httpOnly 쿠키 전환, 401/로그아웃 쿠키 삭제, 로그인 시도 제한(IP 기준 5회/15분), 타이밍 공격 완화, 만료 토큰 401 테스트, deprecated API 정리, JWT 시크릿 분리, 문서 단일 출처화 (핵심 8건) | main 머지됨 (PR #7) |
| 5. 토큰 수명 주기 | 2026-06-06 | access 15분 stateless + refresh 7일 opaque(MySQL 해시 저장), 회전·재사용 탐지, 로그아웃 시 서버 refresh 폐기, 401 자동 갱신(/api/admin/refresh), 프론트 쿠키 2개 운영 | `feature/token-lifecycle` 브랜치 (머지 대기) |

각 사이클의 상세 설계/플랜: `docs/superpowers/specs/`, `docs/superpowers/plans/`

## 다음 사이클 후보 (우선순위순)

### 후보 1: 보안 보완 3차 — 시도 제한 공유 저장소

2026-06-06 토큰 수명 주기 사이클로 리프레시 토큰·무효화 2건이 추가 해소됐다.
남은 한계 목록은 README "보안 한계 > 남은 한계" 섹션을 단일 출처로 한다.

| 항목 | 근거 | 규모 |
|------|------|------|
| 로그인 시도 제한 인메모리 한계 해소 | 다중 인스턴스 배포 시 인스턴스별로 카운트됨 | 소 (Redis 등 공유 저장소 도입 필요) |

### 후보 2: 고객 회원가입/로그인

- 스토어 고객 계정 (장바구니/주문의 전제 조건)
- 어드민 인증의 패턴(JWT, BCrypt, 쿠키) 재사용 가능 — 단 회원가입 폼, 이메일 검증 여부 등 설계 논의 필요
- 어드민(Admin)과 고객(Customer)의 권한 분리 (JWT 클레임에 role 추가)

### 후보 3: 어드민 CRUD 폼

- 현재 어드민 화면은 조회만 가능 (생성/수정/삭제는 API만 존재)
- 공급사/상품 등록·수정·삭제 폼 UI
- 클라이언트 컴포넌트 + 폼 검증 패턴 도입 필요

### 후보 4: 주문/장바구니 도메인

- 고객 회원가입(후보 2) 이후 진행 가능
- Order/OrderItem/Cart 도메인, 재고 차감, 주문 상태 관리

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
