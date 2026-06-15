"use server";

import { revalidatePath } from "next/cache";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ApiError, createOrder, removeCartItem, updateCartItemQuantity } from "@/lib/api";
import { CUSTOMER_ACCESS_COOKIE } from "@/lib/auth-cookies";

// 주의: redirect()는 예외를 던지므로 try 블록 안에서 호출하지 않는다.

// 수량 변경 — 검증 실패(재고 부족 등) 메시지는 ?error= 쿼리로 전달해 페이지가 표시
export async function updateQuantityAction(formData: FormData) {
  const productId = Number(formData.get("productId"));
  const quantity = Number(formData.get("quantity"));

  // access 만료(쿠키 자동 소멸) 시 갱신 후 /cart 복귀 — refresh도 없으면 /refresh 라우트가 로그인으로 보낸다
  const token = (await cookies()).get(CUSTOMER_ACCESS_COOKIE)?.value;
  if (!token) {
    redirect("/refresh?next=/cart");
  }

  let unauthorized = false;
  let errorMessage: string | null = null;
  try {
    await updateCartItemQuantity(token, productId, quantity);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      unauthorized = true;
    } else {
      errorMessage = err instanceof Error ? err.message : "수량 변경에 실패했습니다.";
    }
  }
  if (unauthorized) {
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

  // access 만료(쿠키 자동 소멸) 시 갱신 후 /cart 복귀 — refresh도 없으면 /refresh 라우트가 로그인으로 보낸다
  const token = (await cookies()).get(CUSTOMER_ACCESS_COOKIE)?.value;
  if (!token) {
    redirect("/refresh?next=/cart");
  }

  await removeCartItem(token, productId).catch(() => {});
  revalidatePath("/cart");
  redirect("/cart");
}

// 주문하기 — 장바구니 전체 주문(구매 가능한 것만). 제외 항목은 /orders?notice=로 안내
export async function createOrderAction() {
  // access 만료(쿠키 자동 소멸) 시 갱신 후 /cart 복귀 — refresh도 없으면 /refresh 라우트가 로그인으로 보낸다
  const token = (await cookies()).get(CUSTOMER_ACCESS_COOKIE)?.value;
  if (!token) {
    redirect("/refresh?next=/cart");
  }

  let unauthorized = false;
  let errorMessage: string | null = null;
  let noticeMessage: string | null = null;
  try {
    const result = await createOrder(token);
    if (result.excludedItems.length > 0) {
      noticeMessage =
        result.excludedItems.map((e) => `${e.productName}: ${e.reason}`).join(" / ") +
        " — 해당 상품은 장바구니에 남아 있습니다.";
    }
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      unauthorized = true;
    } else {
      errorMessage = err instanceof Error ? err.message : "주문에 실패했습니다.";
    }
  }
  if (unauthorized) {
    redirect("/refresh?next=/cart");
  }
  if (errorMessage !== null) {
    redirect(`/cart?error=${encodeURIComponent(errorMessage)}`);
  }

  revalidatePath("/cart");
  if (noticeMessage !== null) {
    redirect(`/orders?notice=${encodeURIComponent(noticeMessage)}`);
  }
  redirect("/orders");
}
