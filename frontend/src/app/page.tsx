import Link from "next/link";
import { getProducts } from "@/lib/api";

export default async function HomePage() {
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
      <header style={{ display: "flex", justifyContent: "space-between" }}>
        <h1>스토어</h1>
        <Link href="/admin">어드민 →</Link>
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
