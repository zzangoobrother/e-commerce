import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { refresh } from "@/lib/api";
import { ACCESS_COOKIE, REFRESH_COOKIE, authCookieOptions } from "@/lib/auth-cookies";

// access 만료 시 자동 갱신 — admin_refresh 쿠키로 백엔드 refresh 호출.
// 성공: 새 access/refresh 쿠키 set 후 next 경로로. 실패: 쿠키 삭제 후 /admin/login으로(측면효과 GET 제거).
export async function GET(request: Request) {
  const url = new URL(request.url);
  const next = url.searchParams.get("next") ?? "/admin";
  // 오픈 리다이렉트 방지 — /admin 하위 경로만 허용
  const safeNext = next.startsWith("/admin") ? next : "/admin";

  const store = await cookies();
  const refreshToken = store.get(REFRESH_COOKIE)?.value;
  if (!refreshToken) {
    return clearAndRedirectToLogin(request);
  }

  try {
    const tokens = await refresh(refreshToken);
    const response = NextResponse.redirect(new URL(safeNext, request.url), 303);
    response.cookies.set(ACCESS_COOKIE, tokens.accessToken, authCookieOptions(tokens.accessExpiresAt));
    response.cookies.set(REFRESH_COOKIE, tokens.refreshToken, authCookieOptions(tokens.refreshExpiresAt));
    return response;
  } catch {
    return clearAndRedirectToLogin(request);
  }
}

// 갱신 실패 — 쿠키를 직접 삭제하고 로그인으로. (이전엔 /admin/logout GET으로 위임했으나 측면효과 GET을 제거)
function clearAndRedirectToLogin(request: Request) {
  const response = NextResponse.redirect(new URL("/admin/login", request.url), 303);
  response.cookies.delete(ACCESS_COOKIE);
  response.cookies.delete(REFRESH_COOKIE);
  return response;
}
