import type { CSSProperties } from "react";
import Link from "next/link";
import { getSuppliers } from "@/lib/api";

// 어드민 공급사 목록 페이지
export default async function AdminSuppliersPage() {
  let suppliers;
  try {
    suppliers = await getSuppliers();
  } catch {
    return <main style={{ padding: 24 }}><p>백엔드 연결 실패</p></main>;
  }

  return (
    <main style={{ padding: 24 }}>
      <Link href="/admin">← 어드민</Link>
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
