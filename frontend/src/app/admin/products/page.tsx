import Link from "next/link";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import type { CSSProperties } from "react";
import { getAdminProducts, getSuppliers, ApiError } from "@/lib/api";
import { ACCESS_COOKIE } from "@/lib/auth-cookies";
import LogoutButton from "../LogoutButton";

// 어드민 공급사별 상품 목록 페이지 (supplierId 쿼리 파라미터로 필터)
export default async function AdminProductsPage({
  searchParams,
}: {
  searchParams: Promise<{ supplierId?: string }>;
}) {
  // 쿠키에서 토큰 읽기 (없으면 로그인으로 — proxy의 2차 방어)
  const cookieStore = await cookies();
  const token = cookieStore.get(ACCESS_COOKIE)?.value;
  if (!token) {
    redirect("/admin/login");
  }

  const { supplierId } = await searchParams;
  const selectedId = supplierId ? Number(supplierId) : undefined;

  let suppliers, products;
  try {
    [suppliers, products] = await Promise.all([
      getSuppliers(token),
      getAdminProducts(token, selectedId),
    ]);
  } catch (err) {
    // 401 = access 만료/무효 → 자동 갱신 시도(/admin/refresh). 갱신 실패 시 그쪽에서 로그아웃 처리.
    // next에 쿼리(supplierId)를 담으므로 encodeURIComponent로 감싸 중첩 쿼리 분리를 막는다.
    if (err instanceof ApiError && err.status === 401) {
      const next = selectedId
        ? `/admin/products?supplierId=${selectedId}`
        : "/admin/products";
      redirect(`/admin/refresh?next=${encodeURIComponent(next)}`);
    }
    return <main style={{ padding: 24 }}><p>백엔드 연결 실패</p></main>;
  }

  return (
    <main style={{ padding: 24 }}>
      <header
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
        }}
      >
        <Link href="/admin">← 어드민</Link>
        <LogoutButton />
      </header>
      <h1>상품 관리 (공급사별)</h1>

      {/* 공급사 필터 네비게이션 */}
      <nav style={{ display: "flex", gap: 12, margin: "12px 0" }}>
        <Link href="/admin/products">전체</Link>
        {suppliers.map((s) => (
          <Link key={s.id} href={`/admin/products?supplierId=${s.id}`}>
            {s.name}
          </Link>
        ))}
      </nav>

      <table style={{ borderCollapse: "collapse", width: "100%" }}>
        <thead>
          <tr>
            <th style={cell}>ID</th><th style={cell}>상품명</th>
            <th style={cell}>공급사</th><th style={cell}>가격</th>
            <th style={cell}>재고</th><th style={cell}>상태</th>
          </tr>
        </thead>
        <tbody>
          {products.map((p) => (
            <tr key={p.id}>
              <td style={cell}>{p.id}</td>
              <td style={cell}>{p.name}</td>
              <td style={cell}>{p.supplierName}</td>
              <td style={cell}>{p.price.toLocaleString()}원</td>
              <td style={cell}>{p.stockQuantity}</td>
              <td style={cell}>{p.status}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </main>
  );
}

// 테이블 셀 공통 스타일
const cell: CSSProperties = { border: "1px solid #ddd", padding: 8, textAlign: "left" };
