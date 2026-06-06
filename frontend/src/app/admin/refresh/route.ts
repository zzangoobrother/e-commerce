import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { refresh } from "@/lib/api";
import { ACCESS_COOKIE, REFRESH_COOKIE, authCookieOptions } from "@/lib/auth-cookies";

// access 만료 시 자동 갱신 — admin_refresh 쿠키로 백엔드 refresh 호출.
// 성공: 새 access/refresh 쿠키 set 후 next 경로로. 실패: /admin/logout(쿠키 삭제→로그인).
export async function GET(request: Request) {
  const url = new URL(request.url);
  const next = url.searchParams.get("next") ?? "/admin";
  // 오픈 리다이렉트 방지 — /admin 하위 경로만 허용
  const safeNext = next.startsWith("/admin") ? next : "/admin";

  const store = await cookies();
  const refreshToken = store.get(REFRESH_COOKIE)?.value;
  if (!refreshToken) {
    return NextResponse.redirect(new URL("/admin/logout", request.url), 303);
  }

  try {
    const tokens = await refresh(refreshToken);
    const response = NextResponse.redirect(new URL(safeNext, request.url), 303);
    response.cookies.set(ACCESS_COOKIE, tokens.accessToken, authCookieOptions(tokens.accessExpiresAt));
    response.cookies.set(REFRESH_COOKIE, tokens.refreshToken, authCookieOptions(tokens.refreshExpiresAt));
    return response;
  } catch {
    return NextResponse.redirect(new URL("/admin/logout", request.url), 303);
  }
}
