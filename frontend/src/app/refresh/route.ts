import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { customerRefresh } from "@/lib/api";
import {
  CUSTOMER_ACCESS_COOKIE,
  CUSTOMER_REFRESH_COOKIE,
  authCookieOptions,
} from "@/lib/auth-cookies";

// 고객 access 만료 시 자동 갱신 — customer_refresh 쿠키로 백엔드 refresh 호출.
// 성공: 새 access/refresh 쿠키 set 후 next 경로로. 실패: /logout(쿠키 삭제→로그인).
export async function GET(request: Request) {
  const url = new URL(request.url);
  const next = url.searchParams.get("next") ?? "/";
  // 오픈 리다이렉트 방지 — 같은 오리진 상대 경로만 허용("//host"는 프로토콜 상대 URL이라 차단)
  const safeNext = next.startsWith("/") && !next.startsWith("//") ? next : "/";

  const store = await cookies();
  const refreshToken = store.get(CUSTOMER_REFRESH_COOKIE)?.value;
  if (!refreshToken) {
    return NextResponse.redirect(new URL("/logout", request.url), 303);
  }

  try {
    const tokens = await customerRefresh(refreshToken);
    const response = NextResponse.redirect(new URL(safeNext, request.url), 303);
    response.cookies.set(CUSTOMER_ACCESS_COOKIE, tokens.accessToken, authCookieOptions(tokens.accessExpiresAt));
    response.cookies.set(CUSTOMER_REFRESH_COOKIE, tokens.refreshToken, authCookieOptions(tokens.refreshExpiresAt));
    return response;
  } catch {
    return NextResponse.redirect(new URL("/logout", request.url), 303);
  }
}
