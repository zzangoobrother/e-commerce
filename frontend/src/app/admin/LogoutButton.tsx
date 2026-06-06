// 로그아웃 — /admin/logout 라우트로 POST하여 httpOnly 쿠키 삭제 후 로그인 페이지로 이동.
// 서버 컴포넌트: "use client" 불필요
export default function LogoutButton() {
  return (
    <form action="/admin/logout" method="post" style={{ margin: 0 }}>
      <button type="submit" style={{ padding: "4px 12px", cursor: "pointer" }}>
        로그아웃
      </button>
    </form>
  );
}
