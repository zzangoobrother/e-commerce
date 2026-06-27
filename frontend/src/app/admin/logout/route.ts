import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { logout } from "@/lib/api";
import { ACCESS_COOKIE, REFRESH_COOKIE } from "@/lib/auth-cookies";

// 어드민 로그아웃 — refresh를 백엔드에서 폐기하고 access·refresh 쿠키를 삭제 후 로그인 페이지로. POST 전용(측면효과 GET 제거).
async function clearAndRedirect(request: Request) {
  const store = await cookies();
  const refreshToken = store.get(REFRESH_COOKIE)?.value;
  if (refreshToken) {
    // 백엔드 폐기 실패가 로그아웃을 막지 않도록 무시(쿠키 삭제는 진행)
    await logout(refreshToken).catch(() => {});
  }
  // 303 See Other: POST → GET 리다이렉트 표준 응답
  const response = NextResponse.redirect(new URL("/admin/login", request.url), 303);
  response.cookies.delete(ACCESS_COOKIE);
  response.cookies.delete(REFRESH_COOKIE);
  return response;
}

export function POST(request: Request) {
  return clearAndRedirect(request);
}
