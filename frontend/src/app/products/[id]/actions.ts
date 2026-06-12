"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { addCartItem, ApiError } from "@/lib/api";
import { CUSTOMER_ACCESS_COOKIE } from "@/lib/auth-cookies";

// 장바구니 담기 — 비로그인은 로그인으로, 성공 시 장바구니로.
// 주의: redirect()는 예외를 던지므로 try 블록 안에서 호출하지 않는다.
export async function addToCartAction(
  _prevError: string | null,
  formData: FormData,
): Promise<string | null> {
  const productId = Number(formData.get("productId"));
  const quantity = Number(formData.get("quantity") ?? 1);

  const token = (await cookies()).get(CUSTOMER_ACCESS_COOKIE)?.value;
  if (!token) {
    redirect("/login");
  }

  try {
    await addCartItem(token, productId, quantity);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      // access 만료 경계 — 갱신 라우트로 보내고 갱신 후 상세로 복귀
      redirect(`/refresh?next=/products/${productId}`);
    }
    return err instanceof Error ? err.message : "장바구니 담기에 실패했습니다.";
  }

  redirect("/cart");
}
