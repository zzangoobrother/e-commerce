"use server";

import { revalidatePath } from "next/cache";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ApiError, cancelOrder } from "@/lib/api";
import { CUSTOMER_ACCESS_COOKIE } from "@/lib/auth-cookies";

// 주의: redirect()는 예외를 던지므로 try 블록 안에서 호출하지 않는다.

// 주문 취소 — 실패(중복 취소 등) 메시지는 ?error= 쿼리로 전달해 페이지가 표시
export async function cancelOrderAction(formData: FormData) {
  const orderId = Number(formData.get("orderId"));

  // access 만료(쿠키 자동 소멸) 시 갱신 후 /orders 복귀 — refresh도 없으면 /refresh 라우트가 로그인으로 보낸다
  const token = (await cookies()).get(CUSTOMER_ACCESS_COOKIE)?.value;
  if (!token) {
    redirect("/refresh?next=/orders");
  }

  let unauthorized = false;
  let errorMessage: string | null = null;
  try {
    await cancelOrder(token, orderId);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      unauthorized = true;
    } else {
      errorMessage = err instanceof Error ? err.message : "주문 취소에 실패했습니다.";
    }
  }
  if (unauthorized) {
    redirect("/refresh?next=/orders");
  }
  if (errorMessage !== null) {
    redirect(`/orders?error=${encodeURIComponent(errorMessage)}`);
  }

  revalidatePath("/orders");
  redirect("/orders");
}
