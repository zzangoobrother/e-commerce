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

> **어드민 인증:** 어드민 화면/API는 JWT 로그인이 필요하다. 기본 계정은 `admin` / `admin1234`
> (환경변수 `ADMIN_USERNAME` / `ADMIN_PASSWORD`로 변경 가능). 로그인: http://localhost:3000/admin/login
> 스토어 화면/API는 인증 없이 접근 가능하다.

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

- 토큰을 일반 쿠키에 저장한다 (httpOnly 아님 → XSS에 취약). 운영 전 httpOnly 쿠키 전환 권장
- 리프레시 토큰이 없다 (만료 시 재로그인 필요, 기본 1시간)
- 서버 측 토큰 무효화가 없다 (로그아웃은 클라이언트 쿠키 삭제만)
- 로그인 시도 제한(brute-force 방어)이 없다
- 응답 시간 차이로 계정 존재 여부를 추측할 수 있다 (타이밍 기반 user enumeration — 아이디 부재 시 BCrypt 미수행)
- 토큰 만료로 401을 받으면 로그인 페이지로 이동하지만, 만료된 쿠키는 재로그인 전까지 브라우저에 남는다
