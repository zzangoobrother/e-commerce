"use client";

import { useActionState } from "react";
import type { CSSProperties } from "react";
import { registerAction } from "./actions";

// 고객 회원가입 폼 — Server Action이 auto-login 쿠키를 설정하고 홈으로 리다이렉트한다
export default function RegisterPage() {
  const [error, formAction] = useActionState(registerAction, null);

  return (
    <main style={{ padding: 24, maxWidth: 360 }}>
      <h1>회원가입</h1>
      <form action={formAction} style={{ display: "grid", gap: 12 }}>
        <input name="email" type="email" placeholder="이메일" style={inputStyle} />
        <input
          name="password"
          type="password"
          placeholder="비밀번호"
          style={inputStyle}
        />
        <button type="submit" style={{ padding: 8, cursor: "pointer" }}>
          가입하기
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
