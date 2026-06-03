import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

// 어드민 경로 보호 — 토큰 쿠키가 없으면 로그인 페이지로 리다이렉트
// (토큰 유효성/만료 검증은 백엔드 책임 — 여기서는 존재 여부만 확인하는 1차 방어)
export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  // 로그인 페이지 자신은 보호하지 않음
  if (pathname === "/admin/login") {
    return NextResponse.next();
  }

  const token = request.cookies.get("admin_token");
  if (!token) {
    return NextResponse.redirect(new URL("/admin/login", request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: "/admin/:path*",
};
