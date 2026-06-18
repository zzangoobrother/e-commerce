"use server";

import { revalidatePath } from "next/cache";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ApiError, adminCancelOrder, deliverOrder, shipOrder } from "@/lib/api";
import { ACCESS_COOKIE } from "@/lib/auth-cookies";

// 주의: redirect()는 예외를 던지므로 try 블록 안에서 호출하지 않는다.
// 상태 전이 실패(불법 전이 등) 메시지는 ?error= 쿼리로 전달해 페이지가 표시한다.

type Transition = (token: string, orderId: number) => Promise<unknown>;

// 전이 공통 처리 — ship/deliver/cancel이 토큰·401·에러·리다이렉트 흐름을 공유.
// status(현재 필터)를 폼에서 받아 전이 후 같은 필터 화면으로 복귀한다.
async function runTransition(formData: FormData, transition: Transition) {
  const orderId = Number(formData.get("orderId"));
  const status = String(formData.get("status") ?? "");
  const back = status ? `/admin/orders?status=${status}` : "/admin/orders";

  const token = (await cookies()).get(ACCESS_COOKIE)?.value;
  if (!token) {
    redirect(`/admin/refresh?next=${encodeURIComponent(back)}`);
  }

  let unauthorized = false;
  let errorMessage: string | null = null;
  try {
    await transition(token, orderId);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      unauthorized = true;
    } else {
      errorMessage = err instanceof Error ? err.message : "상태 변경에 실패했습니다.";
    }
  }
  if (unauthorized) {
    redirect(`/admin/refresh?next=${encodeURIComponent(back)}`);
  }
  if (errorMessage !== null) {
    const sep = back.includes("?") ? "&" : "?";
    redirect(`${back}${sep}error=${encodeURIComponent(errorMessage)}`);
  }

  revalidatePath("/admin/orders");
  redirect(back);
}

export async function shipOrderAction(formData: FormData) {
  await runTransition(formData, shipOrder);
}

export async function deliverOrderAction(formData: FormData) {
  await runTransition(formData, deliverOrder);
}

export async function cancelOrderAction(formData: FormData) {
  await runTransition(formData, adminCancelOrder);
}
