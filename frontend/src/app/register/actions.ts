"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { registerCustomer } from "@/lib/api";
import {
  CUSTOMER_ACCESS_COOKIE,
  CUSTOMER_REFRESH_COOKIE,
  authCookieOptions,
} from "@/lib/auth-cookies";

// 회원가입 Server Action — 가입 성공 시 auto-login 쿠키 설정 후 스토어 홈으로.
export async function registerAction(
  _prevError: string | null,
  formData: FormData,
): Promise<string | null> {
  const email = String(formData.get("email") ?? "");
  const password = String(formData.get("password") ?? "");

  let tokens;
  try {
    tokens = await registerCustomer(email, password);
  } catch (err) {
    return err instanceof Error ? err.message : "회원가입에 실패했습니다.";
  }

  // cookies()는 Next.js 16에서 async — await 필수
  const store = await cookies();
  store.set(CUSTOMER_ACCESS_COOKIE, tokens.accessToken, authCookieOptions(tokens.accessExpiresAt));
  store.set(CUSTOMER_REFRESH_COOKIE, tokens.refreshToken, authCookieOptions(tokens.refreshExpiresAt));

  // redirect는 내부적으로 예외를 던지므로 try/catch 밖에서 호출한다
  redirect("/");
}
