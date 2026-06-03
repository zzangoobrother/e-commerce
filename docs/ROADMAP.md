# 이커머스 프로젝트 로드맵

> 마지막 갱신: 2026-06-03 (어드민 인증 사이클 완료 시점)

## 완료된 사이클

| 사이클 | 기간 | 내용 | 상태 |
|--------|------|------|------|
| 1. 이커머스 골격 | 2026-05-31 ~ 06-03 | 모노레포(Spring Boot 4 + Next.js 16 + MySQL), Supplier/Product 도메인, 스토어/어드민 화면, 시드 | main 머지됨 (PR #1, #2) |
| 2. 골격 후속 정리 | 2026-06-03 | create() 이중 세팅 제거, 공급사명 유니크 제약, DB 설정 환경변수화, 메타데이터 한국어화 | main 머지됨 (PR #3) |
| 3. 어드민 인증 | 2026-06-03 | JWT 로그인(HS256), `/api/admin/**` Bearer 보호, 어드민 시드, 로그인 화면, proxy.ts 경로 보호, 로그아웃 | `feature/admin-auth` 브랜치 (머지 대기) |

각 사이클의 상세 설계/플랜: `docs/superpowers/specs/`, `docs/superpowers/plans/`

## 다음 사이클 후보 (우선순위순)

### 후보 1: 보안 보완 — 운영 준비 (추천)

어드민 인증 사이클의 리뷰에서 식별된 골격 한계들을 해소한다.
README "보안 한계" 섹션과 인증 스펙 9장이 추적 목록이다.

| 항목 | 근거 | 규모 |
|------|------|------|
| httpOnly 쿠키 전환 (+ SameSite=Lax) | XSS로부터 토큰 보호 — 가장 시급 | 중 (프론트 토큰 처리 구조 변경: 서버 액션 또는 Route Handler 경유) |
| 401 시 만료 쿠키 즉시 삭제 | 현재 재로그인 전까지 잔존 (스펙 7절 문구와 불일치) | 소 |
| 로그인 시도 제한 (brute-force 방어) | 현재 무제한 시도 가능 | 중 |
| 타이밍 공격 완화 (아이디 부재 시 더미 BCrypt 수행) | 응답 시간으로 계정 존재 추측 가능 | 소 |
| 스펙 9장 ↔ README 보안 한계 동기화 | 문서 단일 출처화 (README가 6개로 더 충실) | 소 |
| 만료 토큰 → 401 테스트 추가 | oauth2 EntryPoint 분기 미커버 | 소 |
| jwt() deprecated API 정리 | Spring Security 7 deprecation 경고 | 소 |
| 테스트 전용 JWT 시크릿 프로파일 | 테스트가 운영 디폴트 시크릿 사용 중 | 소 |
| 리프레시 토큰 | 현재 1시간마다 재로그인 | 중 |

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
- [ ] 보안 보완 사이클(후보 1) 완료
- [ ] 시드 전략 재검토 (다중 인스턴스 배포 시 DataSeeder race로 기동 실패 가능)
