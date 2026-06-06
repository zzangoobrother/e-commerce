import Link from "next/link";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import type { CSSProperties } from "react";
import { getSuppliers, ApiError } from "@/lib/api";
import LogoutButton from "../LogoutButton";

// 어드민 공급사 목록 페이지
export default async function AdminSuppliersPage() {
  // 쿠키에서 토큰 읽기 (없으면 로그인으로 — proxy의 2차 방어)
  const cookieStore = await cookies();
  const token = cookieStore.get("admin_token")?.value;
  if (!token) {
    redirect("/admin/login");
  }

  let suppliers;
  try {
    suppliers = await getSuppliers(token);
  } catch (err) {
    // 401 = access 만료/무효 → 자동 갱신 시도(/admin/refresh). 갱신 실패 시 그쪽에서 로그아웃 처리.
    if (err instanceof ApiError && err.status === 401) {
      redirect("/admin/refresh?next=/admin/suppliers");
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
      <h1>공급사 관리</h1>
      <table style={{ borderCollapse: "collapse", width: "100%" }}>
        <thead>
          <tr>
            <th style={cell}>ID</th><th style={cell}>이름</th>
            <th style={cell}>이메일</th><th style={cell}>상태</th>
          </tr>
        </thead>
        <tbody>
          {suppliers.map((s) => (
            <tr key={s.id}>
              <td style={cell}>{s.id}</td>
              <td style={cell}>{s.name}</td>
              <td style={cell}>{s.contactEmail}</td>
              <td style={cell}>{s.status}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </main>
  );
}

// 테이블 셀 공통 스타일
const cell: CSSProperties = { border: "1px solid #ddd", padding: 8, textAlign: "left" };
