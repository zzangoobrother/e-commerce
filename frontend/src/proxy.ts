import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import {
  ACCESS_COOKIE,
  REFRESH_COOKIE,
  CUSTOMER_ACCESS_COOKIE,
  CUSTOMER_REFRESH_COOKIE,
} from "@/lib/auth-cookies";

// 인증 자체 경로는 보호하지 않는다(리다이렉트 루프 방지)
const ADMIN_PUBLIC_PATHS = ["/admin/login", "/admin/refresh", "/admin/logout"];

// 보호 경로 공통 판정 — access 쿠키 유무로 1차 판정.
// access 없음 + refresh 있음 → 자동 갱신으로. 둘 다 없음 → 로그인.
// access 쿠키 maxAge가 만료시각과 일치해 만료 시 자동 소멸 → 이 판정만으로 자동 갱신이 동작한다.
function guard(
  request: NextRequest,
  accessCookie: string,
  refreshCookie: string,
  refreshPath: string,
  loginPath: string,
) {
  if (request.cookies.has(accessCookie)) {
    return NextResponse.next();
  }
  if (request.cookies.has(refreshCookie)) {
    const url = new URL(refreshPath, request.url);
    // pathname뿐 아니라 쿼리까지 보존해 갱신 후 원래 화면으로 복귀
    url.searchParams.set("next", request.nextUrl.pathname + request.nextUrl.search);
    return NextResponse.redirect(url);
  }
  return NextResponse.redirect(new URL(loginPath, request.url));
}

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  // 고객 보호 경로 (/cart)
  if (pathname.startsWith("/cart")) {
    return guard(request, CUSTOMER_ACCESS_COOKIE, CUSTOMER_REFRESH_COOKIE, "/refresh", "/login");
  }

  // 어드민 보호 경로
  if (ADMIN_PUBLIC_PATHS.includes(pathname)) {
    return NextResponse.next();
  }
  return guard(request, ACCESS_COOKIE, REFRESH_COOKIE, "/admin/refresh", "/admin/login");
}

export const config = {
  matcher: ["/admin/:path*", "/cart/:path*"],
};
