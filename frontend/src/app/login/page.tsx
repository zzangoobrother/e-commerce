"use client";

import { useActionState } from "react";
import type { CSSProperties } from "react";
import Link from "next/link";
import { loginAction } from "./actions";

// 고객 로그인 폼 — Server Action이 httpOnly 쿠키를 설정하고 홈으로 리다이렉트한다
export default function LoginPage() {
  const [error, formAction] = useActionState(loginAction, null);

  return (
    <main style={{ padding: 24, maxWidth: 360 }}>
      <h1>로그인</h1>
      <form action={formAction} style={{ display: "grid", gap: 12 }}>
        <input name="email" type="email" placeholder="이메일" style={inputStyle} />
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
      <p style={{ marginTop: 12 }}>
        계정이 없으신가요? <Link href="/register">회원가입</Link>
      </p>
    </main>
  );
}

const inputStyle: CSSProperties = {
  border: "1px solid #ddd",
  padding: 8,
  borderRadius: 4,
};
