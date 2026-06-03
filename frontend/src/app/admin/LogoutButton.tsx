"use client";

import { useRouter } from "next/navigation";

// 로그아웃 — 토큰 쿠키 삭제 후 로그인 페이지로 이동
export default function LogoutButton() {
  const router = useRouter();

  function handleLogout() {
    document.cookie = "admin_token=; path=/; max-age=0";
    router.push("/admin/login");
  }

  return (
    <button
      onClick={handleLogout}
      style={{ padding: "4px 12px", cursor: "pointer" }}
    >
      로그아웃
    </button>
  );
}
