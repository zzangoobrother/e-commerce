import { NextResponse } from "next/server";

// 로그아웃 — httpOnly 쿠키를 삭제하고 로그인 페이지로 리다이렉트.
// 서버 컴포넌트는 렌더 중 쿠키를 못 지우므로, 401 처리도 이 경로로 리다이렉트해 잔존 쿠키를 제거한다.
function clearAndRedirect(request: Request) {
  // 303 See Other: POST → GET 리다이렉트 표준 응답 (쿠키 삭제 후 로그인 페이지로)
  const response = NextResponse.redirect(
    new URL("/admin/login", request.url),
    303,
  );
  response.cookies.delete("admin_token");
  return response;
}

export function GET(request: Request) {
  return clearAndRedirect(request);
}

export function POST(request: Request) {
  return clearAndRedirect(request);
}
