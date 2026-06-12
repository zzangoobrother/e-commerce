"use server";

import { revalidatePath } from "next/cache";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ApiError, removeCartItem, updateCartItemQuantity } from "@/lib/api";
import { CUSTOMER_ACCESS_COOKIE } from "@/lib/auth-cookies";

// 주의: redirect()는 예외를 던지므로 try 블록 안에서 호출하지 않는다.

// 수량 변경 — 검증 실패(재고 부족 등) 메시지는 ?error= 쿼리로 전달해 페이지가 표시
export async function updateQuantityAction(formData: FormData) {
  const productId = Number(formData.get("productId"));
  const quantity = Number(formData.get("quantity"));

  const token = (await cookies()).get(CUSTOMER_ACCESS_COOKIE)?.value;
  if (!token) {
    redirect("/login");
  }

  let errorMessage: string | null = null;
  try {
    await updateCartItemQuantity(token, productId, quantity);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      errorMessage = "AUTH";
    } else {
      errorMessage = err instanceof Error ? err.message : "수량 변경에 실패했습니다.";
    }
  }
  if (errorMessage === "AUTH") {
    redirect("/refresh?next=/cart");
  }
  if (errorMessage !== null) {
    redirect(`/cart?error=${encodeURIComponent(errorMessage)}`);
  }

  revalidatePath("/cart");
  redirect("/cart");
}

// 삭제 — 백엔드가 멱등(204)이라 실패 분기 없이 갱신만
export async function removeItemAction(formData: FormData) {
  const productId = Number(formData.get("productId"));

  const token = (await cookies()).get(CUSTOMER_ACCESS_COOKIE)?.value;
  if (!token) {
    redirect("/login");
  }

  await removeCartItem(token, productId).catch(() => {});
  revalidatePath("/cart");
  redirect("/cart");
}
