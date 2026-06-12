import Link from "next/link";
import { cookies } from "next/headers";
import { getProducts } from "@/lib/api";
import { CUSTOMER_ACCESS_COOKIE } from "@/lib/auth-cookies";

export default async function HomePage() {
  // 고객 로그인 상태 확인 — 쿠키 유무로 분기
  const isLoggedIn = (await cookies()).has(CUSTOMER_ACCESS_COOKIE);

  let products;
  try {
    products = await getProducts();
  } catch {
    return (
      <main style={{ padding: 24 }}>
        <h1>상품 목록</h1>
        <p>백엔드에 연결할 수 없습니다. (http://localhost:8080)</p>
      </main>
    );
  }

  return (
    <main style={{ padding: 24 }}>
      <header style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h1>스토어</h1>
        <nav style={{ display: "flex", gap: 12 }}>
          {isLoggedIn ? (
            <>
              {/* 프리페치 차단 — access 만료 시 백그라운드 GET /cart→/refresh가 refresh 토큰을 회전시켜 실제 내비게이션과 경합(재사용 탐지로 강제 로그아웃)할 수 있다 */}
              <Link href="/cart" prefetch={false}>장바구니</Link>
              {/* GET <Link>는 프리페치로 의도치 않게 로그아웃될 수 있어 POST 폼으로 호출(어드민 패턴과 동일) */}
              <form action="/logout" method="post" style={{ margin: 0 }}>
                <button type="submit" style={{ padding: "4px 12px", cursor: "pointer" }}>
                  로그아웃
                </button>
              </form>
            </>
          ) : (
            <>
              <Link href="/login">로그인</Link>
              <Link href="/register">회원가입</Link>
            </>
          )}
          <Link href="/admin">어드민 →</Link>
        </nav>
      </header>
      <ul style={{ display: "grid", gap: 12, listStyle: "none", padding: 0 }}>
        {products.map((p) => (
          <li key={p.id} style={{ border: "1px solid #ddd", padding: 16, borderRadius: 8 }}>
            <Link href={`/products/${p.id}`}>
              <strong>{p.name}</strong>
            </Link>
            <div>{p.price.toLocaleString()}원</div>
            <small>{p.supplierName}</small>
          </li>
        ))}
      </ul>
    </main>
  );
}
