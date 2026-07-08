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

> **고객 인증:** 스토어 고객은 회원가입(/register) · 로그인(/login)이 가능하다. 가입 즉시 자동 로그인. 어드민과 JWT role(ADMIN/CUSTOMER)로 권한 분리 — 고객 토큰으로 어드민 API 접근 시 403 반환. 상품 목록·상세 등 조회 API는 인증 없이 접근 가능하다. 고객 보호 페이지(`/cart`, `/orders`)는 proxy가 보호하고 access 만료 시 `/refresh`로 자동 갱신(어드민과 동일 메커니즘).

> **주문(고객 전용):** 장바구니 전체를 한 번에 주문으로 전환한다. 판매 중지·재고 부족 등 구매 불가 항목은 주문에서 제외하고 사유와 함께 안내하며 장바구니에 남긴다(부분 주문). 주문 시 재고를 차감하고 상품명·단가를 주문 시점으로 고정(스냅샷)해 이후 가격 변경에 영향받지 않는다. 주문 취소 시 재고를 복원한다(이력 보존). 취소는 주문 완료(ORDERED) 상태일 때만 가능하다(고객·어드민 공통) — 배송이 시작되면 취소할 수 없다.

> **카드 결제(모의 PG):** 주문 생성 시 카드 결제(모의 PG, 테스트 카드번호로 승인/거절 판정)를 함께 처리한다. 주문 생성과 결제는 한 트랜잭션으로 묶여 **승인 시에만 주문이 확정**되며, 카드 거절(402)·형식오류(400) 시 트랜잭션이 롤백돼 주문·재고가 원복되고 장바구니는 유지된다. 주문 취소 시 모의 환불(재고 복원)하고 결제를 환불됨(REFUNDED) 상태로 전이한다. 결제 정보는 주문과 1:1인 별도 `Payment` 엔티티에 기록하며, **카드 전체번호·CVC는 저장하지 않고 카드 brand·끝 4자리(last4)만 남긴다.** 테스트 카드: 승인 `4242 4242 4242 4242`(VISA), 거절 `4000 0000 0000 0002`(한도 초과).

> **배송 상태:** 주문은 주문 완료(ORDERED) → 배송중(SHIPPING) → 배송완료(DELIVERED)로 진행한다. 모든 상태 전이는 Order 행을 잠근 뒤 출발 상태를 검증해(불법 전이 차단) 동시 전이 경합 시 한쪽만 성공시킨다. 고객 주문 목록은 현재 배송 상태를 표시한다.

> **어드민 주문 관리(어드민 전용):** 어드민은 전체 주문을 조회하고(고객 이메일 포함·상태 필터), 배송 시작(ORDERED→SHIPPING)·배송 완료(SHIPPING→DELIVERED)·취소(ORDERED만, 재고 복원)를 수행할 수 있다.

> **배송지 관리(주소록, 고객 전용):** 고객은 여러 배송지를 등록·조회·수정·삭제하고 기본배송지를 지정할 수 있다(주소록 CRUD). 기본배송지는 **항상 0/1개** 불변식을 유지한다 — 첫 배송지는 자동 기본이 되고, 다른 배송지를 기본으로 지정하면 기존 기본은 자동 해제되며, 기본배송지를 삭제하면 남은 최신 배송지가 자동 승격한다. 고객당 최대 10개까지 등록할 수 있고, 모든 단건 연산은 소유권을 강제해 타인의 배송지 접근은 존재 여부를 노출하지 않고 404를 반환한다. (주문·결제와의 배송지 연동은 후속 범위.)

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
| 고객 | `GET /api/store/cart` | 장바구니 조회 — 담기 목록·수량·합계 (고객 전용) |
| 고객 | `POST /api/store/cart/items` | 장바구니 담기 — 같은 상품 재담기 시 수량 가산, 재고 상한 검증 (고객 전용) |
| 고객 | `PATCH /api/store/cart/items/{productId}` | 장바구니 수량 변경 (고객 전용) |
| 고객 | `DELETE /api/store/cart/items/{productId}` | 장바구니 상품 삭제 — 멱등 204 (고객 전용) |
| 고객 | `POST /api/store/orders` | 주문 생성 — 장바구니 전체를 주문으로 전환(부분 주문: 구매 불가 항목 제외·사유 반환), 카드 결제 본문 수신·승인 시 확정·재고 차감·가격 스냅샷. 카드 거절 시 402·형식오류 시 400(주문·재고 미반영) (고객 전용) |
| 고객 | `GET /api/store/orders` | 내 주문 목록 — 최신순, 항목·합계·결제 요약(brand·last4·상태) 포함 (고객 전용) |
| 고객 | `POST /api/store/orders/{orderId}/cancel` | 주문 취소 — 상태 전이(ORDERED→CANCELLED)·재고 복원, ORDERED일 때만 (고객 전용) |
| 고객 | `GET /api/store/addresses` | 내 배송지 목록 — 기본배송지 먼저, 이후 최신순 (고객 전용) |
| 고객 | `POST /api/store/addresses` | 배송지 등록 — 첫 배송지는 자동 기본, 상한 10개 초과 시 400 (고객 전용) |
| 고객 | `PUT /api/store/addresses/{id}` | 배송지 수정 — 기본여부는 불변(전용 경로로 일원화), 소유권 위반 404 (고객 전용) |
| 고객 | `DELETE /api/store/addresses/{id}` | 배송지 삭제 — 기본 삭제 시 남은 최신 자동 승격, 204 (고객 전용) |
| 고객 | `POST /api/store/addresses/{id}/default` | 기본배송지 지정 — 기존 기본 자동 해제(항상 0/1개) (고객 전용) |
| 어드민 | `GET /api/admin/orders?status=` | 전체 주문 목록 — 고객 이메일 포함, 상태 필터 옵션 |
| 어드민 | `POST /api/admin/orders/{id}/ship` | 배송 시작 — 상태 전이(ORDERED→SHIPPING) |
| 어드민 | `POST /api/admin/orders/{id}/deliver` | 배송 완료 — 상태 전이(SHIPPING→DELIVERED) |
| 어드민 | `POST /api/admin/orders/{id}/cancel` | 주문 취소 — 상태 전이(ORDERED→CANCELLED)·재고 복원, ORDERED일 때만 |
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

### 해결됨 (2026-06-23 인증 하드닝 사이클)

- 회원가입(`/api/store/auth/register`)에 IP 기준 레이트리밋 추가 — 로그인과 달리 **성공 가입도 계수**(리셋 없음)해 윈도우당 상한을 두고, 초과 시 429를 반환한다(대량 계정 생성·자원 고갈 방어). 인메모리 시도 제한(`LoginAttemptService`) 재사용.
- 로그아웃 토큰 폐기에 **ownerType 가드** 추가 — 제출된 refresh 토큰의 소유자 타입이 로그아웃 경로(어드민/고객)와 일치할 때만 폐기한다(교차 타입 폐기 차단, refresh 회전 가드와 대칭). 타입 불일치는 예외 없이 no-op이며 로그아웃은 모든 경우 204(멱등).
- 로그아웃 라우트의 **GET 측면효과 제거** — 토큰 폐기·쿠키 삭제 같은 상태 변경을 GET으로 수행하던 핸들러를 제거하고 POST 전용으로 전환했다(안전한 메서드 규약 준수). refresh 갱신 실패 시의 쿠키 정리는 refresh 라우트가 직접 수행하도록 이동.

### 남은 한계 (다음 사이클)

- 로그인 시도 제한이 인메모리라 다중 인스턴스 배포 시 인스턴스별로 카운트된다 (Redis 등 공유 저장소 도입 필요)
- refresh 회전 동시성(race) 미처리 — 단일 어드민 가정으로 현재는 허용
- refresh 토큰을 다형 소유(ownerType+ownerId)로 일반화하며 **DB 외래키(참조 무결성)를 포기**했다 (어드민·고객 이종 소유자를 단일 FK로 가리킬 수 없어, 정합성은 애플리케이션 코드가 보장)
