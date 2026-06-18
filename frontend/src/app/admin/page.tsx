import Link from "next/link";
import LogoutButton from "./LogoutButton";

// 어드민 대시보드 홈 페이지
export default function AdminHome() {
  return (
    <main style={{ padding: 24 }}>
      <header
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
        }}
      >
        <h1>어드민</h1>
        <LogoutButton />
      </header>
      <nav style={{ display: "flex", gap: 16 }}>
        <Link href="/admin/suppliers">공급사 관리</Link>
        <Link href="/admin/products">상품 관리(공급사별)</Link>
        <Link href="/admin/orders">주문 관리</Link>
        <Link href="/">← 스토어</Link>
      </nav>
    </main>
  );
}
