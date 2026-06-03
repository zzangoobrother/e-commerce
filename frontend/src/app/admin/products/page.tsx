import type { CSSProperties } from "react";
import Link from "next/link";
import { getAdminProducts, getSuppliers } from "@/lib/api";

// 어드민 공급사별 상품 목록 페이지 (supplierId 쿼리 파라미터로 필터)
export default async function AdminProductsPage({
  searchParams,
}: {
  searchParams: Promise<{ supplierId?: string }>;
}) {
  const { supplierId } = await searchParams;
  const selectedId = supplierId ? Number(supplierId) : undefined;

  let suppliers, products;
  try {
    [suppliers, products] = await Promise.all([
      getSuppliers(),
      getAdminProducts(selectedId),
    ]);
  } catch {
    return <main style={{ padding: 24 }}><p>백엔드 연결 실패</p></main>;
  }

  return (
    <main style={{ padding: 24 }}>
      <Link href="/admin">← 어드민</Link>
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
