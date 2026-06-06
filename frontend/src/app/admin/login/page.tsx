"use client";

import { useActionState } from "react";
import type { CSSProperties } from "react";
import { loginAction } from "./actions";

// 어드민 로그인 폼 — Server Action(loginAction)이 httpOnly 쿠키를 설정하고 리다이렉트한다
export default function AdminLoginPage() {
  const [error, formAction] = useActionState(loginAction, null);

  return (
    <main style={{ padding: 24, maxWidth: 360 }}>
      <h1>어드민 로그인</h1>
      <form action={formAction} style={{ display: "grid", gap: 12 }}>
        <input name="username" placeholder="아이디" style={inputStyle} />
        <input
          name="password"
          type="password"
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
