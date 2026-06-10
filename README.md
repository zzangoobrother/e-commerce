# E-commerce (Skeleton)

공급사별 상품 관리 어드민과 스토어프론트를 갖춘 이커머스 골격.

## 구성
- `backend/` — Spring Boot REST API (Java 25, Gradle)
- `frontend/` — Next.js (App Router, TypeScript)
- `docker-compose.yml` — MySQL 8

## 실행 방법
1. DB 기동: `docker compose up -d`
2. 백엔드: `cd backend && ./gradlew bootRun` (http://localhost:8080)
3. 프론트: `cd frontend && npm install && npm run dev` (http://localhost:3000)
4. 접속: 스토어 http://localhost:3000 · 어드민 http://localhost:3000/admin

> **어드민 인증:** 어드민 화면/API는 JWT 로그인이 필요하다(role: ADMIN). 기본 계정은 `admin` / `admin1234`
> (환경변수 `ADMIN_USERNAME` / `ADMIN_PASSWORD`로 변경 가능). 로그인: http://localhost:3000/admin/login

> **고객 인증:** 스토어 고객은 회원가입(/register) · 로그인(/login)이 가능하다. 가입 즉시 자동 로그인. 어드민과 JWT role(ADMIN/CUSTOMER)로 권한 분리 — 고객 토큰으로 어드민 API 접근 시 403 반환. 상품 목록·상세 등 조회 API는 인증 없이 접근 가능하다.

> **기존 로컬 DB 볼륨이 있는 경우:** 공급사명 유니크 제약이 추가되어 기존 볼륨에는 자동
> 적용되지 않을 수 있다. `docker compose down -v && docker compose up -d`로 볼륨을
> 재생성하면 새 스키마로 시작한다.

> **DB 접속 정보 변경:** `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` 환경변수로 오버라이드할 수
> 있다. 미설정 시 로컬 docker-compose 기본값을 사용한다.

## 시드 데이터

최초 기동 시 샘플 데이터가 자동으로 삽입된다(멱등 — 이미 존재하면 건너뜀).

| 구분 | 이름 |
|------|------|
| 공급사 | 신선식품 주식회사, 바삭과자 주식회사 |
| 상품 | 유기농 사과 1kg, 제철 딸기 500g, 감자칩 오리지널, 초코쿠키 12개입 |

## 도메인 모델

```
Supplier (공급사)              Product (상품)
─────────────────             ─────────────────
id (PK, Long)                 id (PK, Long)
name (String)                 supplier_id (FK) ──▶ Supplier
contactEmail (String)         name (String)
status (SupplierStatus)       description (String)
createdAt (LocalDateTime)     price (BigDecimal)
                              stockQuantity (int)
        1 ──────< N           status (ProductStatus)
                              createdAt (LocalDateTime)
```

- **관계:** `Supplier` 1 : N `Product`. `Product.supplier`는 `@ManyToOne` (지연 로딩)
- **SupplierStatus:** `ACTIVE`, `INACTIVE`
- **ProductStatus:** `ON_SALE`, `SOLD_OUT`, `HIDDEN`
- 가격은 `BigDecimal` (통화 계산 정확성 보장)

## 주요 API

| 영역 | 메서드 · 경로 | 설명 |
|------|--------------|------|
| 스토어 | `GET /api/products` | 노출 가능한(ON_SALE) 상품 목록 |
| 스토어 | `GET /api/products/{id}` | 상품 상세 |
| 고객 | `POST /api/store/auth/register` | 고객 회원가입 (가입 즉시 auto-login) — 인증 불필요 |
| 고객 | `POST /api/store/auth/login` | 고객 로그인 (JWT 발급) — 인증 불필요 |
| 고객 | `POST /api/store/auth/logout` | 고객 로그아웃 (서버 refresh 폐기) |
| 고객 | `POST /api/store/auth/refresh` | 고객 access 토큰 갱신 |
| 어드민 | `POST /api/admin/login` | 어드민 로그인 (JWT 발급) — 인증 불필요 |
| 어드민 | `GET /api/admin/suppliers` | 공급사 목록 |
| 어드민 | `POST /api/admin/suppliers` | 공급사 생성 |
| 어드민 | `PUT /api/admin/suppliers/{id}` | 공급사 수정 |
| 어드민 | `DELETE /api/admin/suppliers/{id}` | 공급사 삭제 |
| 어드민 | `GET /api/admin/products?supplierId=` | 공급사별 상품 목록(필터 옵션) |
| 어드민 | `POST /api/admin/products` | 상품 생성(공급사 지정) |
| 어드민 | `PUT /api/admin/products/{id}` | 상품 수정 |
| 어드민 | `DELETE /api/admin/products/{id}` | 상품 삭제 |

> 어드민 API(`/api/admin/**`)는 로그인 API를 제외하고 모두 `Authorization: Bearer <token>` 헤더가 필요하다.

## 보안 한계 (골격 수준 — 운영 전 보완 필요)

> 이 섹션이 보안 한계 현황의 **단일 출처(정본)**이다. 스펙·플랜 등 다른 문서는 이 섹션을 참조한다.

### 해결됨 (2026-06-04 보안 보완 사이클)

- ~~토큰을 일반 쿠키에 저장한다 (httpOnly 아님 → XSS에 취약)~~ → httpOnly 쿠키(+SameSite=Lax) 전환 완료 (Server Action 기반)
- ~~로그인 시도 제한(brute-force 방어)이 없다~~ → IP 기준 5회/15분, 429 응답 추가
- ~~응답 시간 차이로 계정 존재 여부를 추측할 수 있다 (타이밍 기반 user enumeration — 아이디 부재 시 BCrypt 미수행)~~ → 아이디 부재 시 더미 BCrypt 수행으로 완화
- ~~토큰 만료로 401을 받으면 로그인 페이지로 이동하지만, 만료된 쿠키는 재로그인 전까지 브라우저에 남는다~~ → 401/로그아웃 시 서버 측 쿠키 삭제 (Route Handler) 완료

### 해결됨 (2026-06-06 토큰 수명 주기 사이클)

- ~~리프레시 토큰이 없다 (만료 시 재로그인 필요, 기본 1시간)~~ → access 15분 + refresh 7일 회전 도입, MySQL에 해시 저장, 재사용 탐지
- ~~서버 측 토큰 무효화가 없다 (로그아웃은 클라이언트 쿠키 삭제만 — 블랙리스트 없음)~~ → 로그아웃 시 서버에서 refresh 토큰 폐기, 401 자동 갱신(/api/admin/refresh) 완료

### 해결됨 (2026-06-10 재사용 탐지 롤백 결함 수정)

- ~~재사용 탐지의 일괄 폐기가 같은 트랜잭션의 401 예외로 롤백돼 실제로는 저장되지 않았다 (잠복 결함 — @DataJpaTest의 테스트 트랜잭션이 결함을 가림)~~ → 폐기를 별도 트랜잭션(REQUIRES_NEW)으로 분리해 예외 전에 커밋 확정, @SpringBootTest 커밋 경계 회귀 테스트 추가

### 남은 한계 (다음 사이클)

- 로그인 시도 제한이 인메모리라 다중 인스턴스 배포 시 인스턴스별로 카운트된다 (Redis 등 공유 저장소 도입 필요)
- refresh 회전 동시성(race) 미처리 — 단일 어드민 가정으로 현재는 허용
- refresh 토큰을 다형 소유(ownerType+ownerId)로 일반화하며 **DB 외래키(참조 무결성)를 포기**했다 (어드민·고객 이종 소유자를 단일 FK로 가리킬 수 없어, 정합성은 애플리케이션 코드가 보장)
