"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { login } from "@/lib/api";

// 로그인 Server Action — 백엔드 로그인 호출 후 httpOnly 쿠키 설정.
// 실패 시 에러 메시지 문자열을 반환(폼에서 표시), 성공 시 /admin으로 리다이렉트.
export async function loginAction(
  _prevError: string | null,
  formData: FormData,
): Promise<string | null> {
  const username = String(formData.get("username") ?? "");
  const password = String(formData.get("password") ?? "");

  let token: string;
  let expiresAt: string;
  try {
    const res = await login(username, password);
    token = res.token;
    expiresAt = res.expiresAt;
  } catch (err) {
    return err instanceof Error ? err.message : "로그인에 실패했습니다.";
  }

  const maxAge = Math.max(
    0,
    Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000),
  );
  // cookies()는 Next.js 16에서 async — await 필수
  const store = await cookies();
  store.set("admin_token", token, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge,
  });

  // redirect는 내부적으로 예외를 던지므로 try/catch 밖에서 호출한다
  redirect("/admin");
}
