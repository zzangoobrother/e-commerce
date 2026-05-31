# 이커머스 골격(Skeleton) 설계

- 작성일: 2026-05-31
- 상태: 승인됨 (구현 계획 단계로 이동)

## 1. 목적과 범위

누구나 아는 일반적인 이커머스 사용 흐름(상품 목록·상세)과, 공급사별로 상품을 관리하는
어드민을 갖춘 사이트의 **실행 가능한 골격**을 만든다.

이번 작업의 범위는 "뼈대"다. 즉 전체 시스템의 구조·모듈 경계·기본 도메인 흐름을
확정하고, 빌드·실행되며 화면이 채워지는 최소 동작까지 만든다. 장바구니/주문/결제/
인증 등 살붙이기는 이후 별도 사이클에서 다룬다.

### 포함 (In scope)
- 모노레포 저장소 레이아웃
- Spring Boot 백엔드 (Java 25, Gradle Kotlin DSL)
- Supplier·Product 도메인 (엔티티/리포지토리/서비스/컨트롤러/DTO)
- 스토어프론트용 + 어드민용 REST API 분리
- Next.js 프론트엔드 (App Router, TypeScript) — 스토어 목록·상세, 어드민 공급사/상품 화면
- Docker Compose 기반 MySQL 8
- 시작 시 샘플 데이터 시드
- 백엔드 스모크 테스트, 실행 절차를 담은 README

### 제외 (Out of scope, 이후 사이클)
- 인증/인가 (이번엔 개방 API) — Spring Security 미도입
- 장바구니, 주문, 결제, 배송
- 상품 카테고리/검색/이미지 업로드
- 프론트엔드 자동화 테스트

## 2. 기술 스택

| 영역 | 선택 |
|------|------|
| 언어(백엔드) | Java 25 (Temurin LTS, 로컬 설치 확인됨) |
| 프레임워크 | Spring Boot (최신 안정 버전), Spring Web, Spring Data JPA |
| 빌드 | Gradle (Kotlin DSL) |
| DB | MySQL 8 (Docker Compose), JPA/Hibernate |
| 프론트엔드 | Next.js (App Router) + TypeScript, Node.js 22.12 LTS |
| 저장소 | 모노레포 (단일 git 저장소) |

## 3. 아키텍처 개요

```
브라우저 ──HTTP──▶ Next.js (3000) ──fetch /api──▶ Spring Boot (8080) ──JPA──▶ MySQL (3306, Docker)
        스토어프론트(/)·어드민(/admin)        REST API                      도메인 영속화
```

3계층 분리: 프론트(화면) / 백엔드(비즈니스 로직·API) / MySQL(저장소). 인증은 이번 범위에서
빼되, 스토어용·어드민용 API 경계는 처음부터 분리해 이후 어드민에만 인증을 거는 작업이
쉽도록 한다.

## 4. 저장소 레이아웃

```
e-commerce/
├── backend/                 # Spring Boot + Gradle(Kotlin DSL), Java 25
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradlew / gradlew.bat / gradle/
│   └── src/
│       ├── main/java/com/ecommerce/
│       │   ├── EcommerceApplication.java
│       │   ├── common/            # 전역 설정(예: CORS), 공통 유틸
│       │   ├── supplier/          # 공급사 도메인
│       │   │   ├── Supplier.java
│       │   │   ├── SupplierStatus.java
│       │   │   ├── SupplierRepository.java
│       │   │   ├── SupplierService.java
│       │   │   ├── SupplierController.java        # /api/admin/suppliers
│       │   │   └── dto/
│       │   └── product/           # 상품 도메인
│       │       ├── Product.java
│       │       ├── ProductStatus.java
│       │       ├── ProductRepository.java
│       │       ├── ProductService.java
│       │       ├── ProductController.java         # /api/products (스토어)
│       │       ├── AdminProductController.java     # /api/admin/products
│       │       └── dto/
│       ├── main/resources/
│       │   └── application.yml                     # MySQL 프로파일
│       └── test/java/com/ecommerce/                # 스모크 테스트
├── frontend/                # Next.js (App Router, TypeScript)
│   ├── package.json
│   ├── next.config.*
│   └── src/
│       ├── app/
│       │   ├── page.tsx                 # 스토어프론트 상품 목록
│       │   ├── products/[id]/page.tsx   # 상품 상세
│       │   └── admin/
│       │       ├── page.tsx             # 어드민 대시보드(간단)
│       │       ├── suppliers/page.tsx   # 공급사 목록/관리
│       │       └── products/page.tsx    # 공급사별 상품 관리
│       └── lib/api.ts                   # 백엔드 API 호출 래퍼
├── docker-compose.yml       # MySQL 8
├── .gitignore
└── README.md                # 실행 방법
```

백엔드는 **기능(도메인)별 패키지** 구조를 따른다. `supplier`, `product` 각 패키지가
엔티티·리포지토리·서비스·컨트롤러·DTO를 자급자족으로 가져, 한 도메인을 통째로
이해·테스트·변경할 수 있다.

## 5. 도메인 모델

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

- 관계: `Supplier` 1 : N `Product`. `Product.supplier`는 `@ManyToOne` (지연 로딩).
- `SupplierStatus`: `ACTIVE`, `INACTIVE`.
- `ProductStatus`: `ON_SALE`, `SOLD_OUT`, `HIDDEN`.
- 가격은 `BigDecimal` (통화 계산 정확성).
- "어드민에서 공급사별 상품 관리" 요구사항은 이 FK 하나로 충족된다.

## 6. API 표면 (REST)

| 영역 | 메서드 · 경로 | 설명 |
|------|------|------|
| 스토어 | `GET /api/products` | 노출 가능한(예: ON_SALE) 상품 목록 |
| 스토어 | `GET /api/products/{id}` | 상품 상세 |
| 어드민 | `GET /api/admin/suppliers` | 공급사 목록 |
| 어드민 | `POST /api/admin/suppliers` | 공급사 생성 |
| 어드민 | `PUT /api/admin/suppliers/{id}` | 공급사 수정 |
| 어드민 | `DELETE /api/admin/suppliers/{id}` | 공급사 삭제 |
| 어드민 | `GET /api/admin/products?supplierId=` | 공급사별 상품 목록(필터 옵션) |
| 어드민 | `POST /api/admin/products` | 상품 생성(공급사 지정) |
| 어드민 | `PUT /api/admin/products/{id}` | 상품 수정 |
| 어드민 | `DELETE /api/admin/products/{id}` | 상품 삭제 |

- 요청/응답은 엔티티가 아닌 **DTO**로 주고받는다(영속성 누수 방지, API 계약 명확화).
- 스토어용(`ProductController`)과 어드민용(`AdminProductController`) 컨트롤러를 분리한다.
- CORS: 프론트(localhost:3000) → 백엔드(localhost:8080) 허용 설정을 `common`에 둔다.

## 7. 데이터 흐름 & 시드

- 앱 시작 시 샘플 **공급사 2곳 + 각 공급사 상품 2~3개**를 `CommandLineRunner` 시드
  컴포넌트로 넣는다(JPA ddl-auto와 순서 충돌이 없고, 이미 데이터가 있으면 건너뛰도록
  멱등 처리). 프론트를 열면 바로 화면이 채워진다.
- 프론트는 서버 컴포넌트에서 백엔드 API를 `fetch`해 목록/상세를 렌더링한다.
- 어드민 화면은 골격 수준의 목록 표시 + 폼 자리(동작은 최소). 핵심은 공급사별 상품
  관리 흐름이 화면-API-DB까지 한 줄로 연결되는 것을 보이는 것.

## 8. 에러 처리 (골격 수준)

- 백엔드: 존재하지 않는 리소스 조회 시 404, 잘못된 입력 시 400을 반환하는 최소한의
  예외 처리(`@RestControllerAdvice` 1개)를 둔다.
- 프론트: API 실패 시 간단한 에러 메시지 표시.

## 9. 테스트 (골격 수준)

- 백엔드:
  - 스프링 컨텍스트 로딩 테스트 1개 (`@SpringBootTest`).
  - 도메인별 리포지토리 또는 컨트롤러 스모크 테스트 각 1개 — "빌드하면 통과한다"의 최소선.
  - 테스트는 인메모리(H2) 또는 테스트 프로파일로 격리.
- 프론트엔드: 자동화 테스트 생략. README의 실행 절차로 수동 검증.

## 10. 실행 방법 (README에 문서화)

1. `docker compose up -d` — MySQL 기동
2. `cd backend && ./gradlew bootRun` — 백엔드 (8080), 시작 시 샘플 데이터 시드
3. `cd frontend && npm install && npm run dev` — 프론트 (3000)
4. 브라우저: `http://localhost:3000` (스토어), `http://localhost:3000/admin` (어드민)

## 11. 검증 기준 (Definition of Done)

- `./gradlew build`가 통과한다(스모크 테스트 포함).
- `docker compose up` 후 백엔드가 MySQL에 연결되어 기동된다.
- 스토어 메인에서 시드된 상품 목록이 보이고, 상세로 이동된다.
- 어드민에서 공급사 목록과 공급사별 상품 목록이 보인다.
- README의 절차만으로 처음 받은 사람이 실행할 수 있다.
