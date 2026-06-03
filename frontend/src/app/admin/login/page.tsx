"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import type { CSSProperties, FormEvent } from "react";
import { login } from "@/lib/api";

// 어드민 로그인 폼 — 성공 시 토큰을 쿠키에 저장하고 어드민으로 이동
export default function AdminLoginPage() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const { token, expiresAt } = await login(username, password);
      // 토큰을 쿠키에 저장 (만료 시각까지)
      const maxAge = Math.max(
        0,
        Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000),
      );
      document.cookie = `admin_token=${token}; path=/; max-age=${maxAge}`;
      router.push("/admin");
    } catch (err) {
      setError(err instanceof Error ? err.message : "로그인에 실패했습니다.");
    }
  }

  return (
    <main style={{ padding: 24, maxWidth: 360 }}>
      <h1>어드민 로그인</h1>
      <form onSubmit={handleSubmit} style={{ display: "grid", gap: 12 }}>
        <input
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          placeholder="아이디"
          style={inputStyle}
        />
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="비밀번호"
          style={inputStyle}
        />
        <button type="submit" style={{ padding: 8, cursor: "pointer" }}>
          로그인
        </button>
        {error && <p style={{ color: "crimson" }}>{error}</p>}
      </form>
    </main>
  );
}

const inputStyle: CSSProperties = {
  border: "1px solid #ddd",
  padding: 8,
  borderRadius: 4,
};
