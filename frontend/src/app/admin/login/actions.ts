"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { login } from "@/lib/api";
import { ACCESS_COOKIE, REFRESH_COOKIE, authCookieOptions } from "@/lib/auth-cookies";

// 로그인 Server Action — 백엔드 로그인 호출 후 access·refresh httpOnly 쿠키 설정.
// 실패 시 에러 메시지 문자열 반환(폼 표시), 성공 시 /admin으로 리다이렉트.
export async function loginAction(
  _prevError: string | null,
  formData: FormData,
): Promise<string | null> {
  const username = String(formData.get("username") ?? "");
  const password = String(formData.get("password") ?? "");

  let tokens;
  try {
    tokens = await login(username, password);
  } catch (err) {
    return err instanceof Error ? err.message : "로그인에 실패했습니다.";
  }

  // cookies()는 Next.js 16에서 async — await 필수
  const store = await cookies();
  store.set(ACCESS_COOKIE, tokens.accessToken, authCookieOptions(tokens.accessExpiresAt));
  store.set(REFRESH_COOKIE, tokens.refreshToken, authCookieOptions(tokens.refreshExpiresAt));

  // redirect는 내부적으로 예외를 던지므로 try/catch 밖에서 호출한다
  redirect("/admin");
}
