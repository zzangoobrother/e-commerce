// 어드민 인증 쿠키 — access/refresh 공통 이름과 옵션.
// Server Action(cookies())과 Route Handler(response.cookies)가 옵션 객체를 공유한다.

export const ACCESS_COOKIE = "admin_token";
export const REFRESH_COOKIE = "admin_refresh";

// ISO 만료 시각 → 남은 초(maxAge). 과거면 0.
function maxAgeSeconds(expiresAt: string): number {
  return Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000));
}

// httpOnly 쿠키 옵션 — secure는 운영에서만(로컬 http 개발 허용)
export function authCookieOptions(expiresAt: string) {
  return {
    httpOnly: true as const,
    sameSite: "lax" as const,
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: maxAgeSeconds(expiresAt),
  };
}
