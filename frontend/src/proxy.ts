import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

// 인증 자체 경로는 보호하지 않는다(리다이렉트 루프 방지)
const PUBLIC_PATHS = ["/admin/login", "/admin/refresh", "/admin/logout"];

// 어드민 경로 보호 — access 쿠키 유무로 1차 판정.
// access 없음 + refresh 있음 → 자동 갱신(/admin/refresh)로. 둘 다 없음 → 로그인.
export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  if (PUBLIC_PATHS.includes(pathname)) {
    return NextResponse.next();
  }

  if (request.cookies.has("admin_token")) {
    return NextResponse.next();
  }

  if (request.cookies.has("admin_refresh")) {
    const url = new URL("/admin/refresh", request.url);
    url.searchParams.set("next", pathname);
    return NextResponse.redirect(url);
  }

  return NextResponse.redirect(new URL("/admin/login", request.url));
}

export const config = {
  matcher: "/admin/:path*",
};
