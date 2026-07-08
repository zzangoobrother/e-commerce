"use server";

import { revalidatePath } from "next/cache";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import {
  ApiError,
  createAddress,
  deleteAddress,
  setDefaultAddress,
  updateAddress,
} from "@/lib/api";
import type { AddressInput } from "@/lib/api";
import { CUSTOMER_ACCESS_COOKIE } from "@/lib/auth-cookies";

// 주의: redirect()는 예외를 던지므로 try 블록 안에서 호출하지 않는다.

// 쿠키에서 access 토큰을 확보 — 없으면 로그인으로 보낸다
async function requireToken(): Promise<string> {
  const token = (await cookies()).get(CUSTOMER_ACCESS_COOKIE)?.value;
  if (!token) {
    redirect("/login");
  }
  return token;
}

// FormData → AddressInput 변환 공통 처리
function readInput(formData: FormData): AddressInput {
  return {
    label: String(formData.get("label") ?? ""),
    recipientName: String(formData.get("recipientName") ?? ""),
    phone: String(formData.get("phone") ?? ""),
    zipCode: String(formData.get("zipCode") ?? ""),
    address1: String(formData.get("address1") ?? ""),
    address2: String(formData.get("address2") ?? ""),
    isDefault: formData.get("isDefault") === "true",
  };
}

// 배송지 등록
export async function createAddressAction(formData: FormData) {
  const token = await requireToken();
  const input = readInput(formData);

  let unauthorized = false;
  try {
    await createAddress(token, input);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      unauthorized = true;
    } else {
      throw err;
    }
  }
  if (unauthorized) {
    redirect("/refresh?next=/addresses");
  }

  revalidatePath("/addresses");
}

// 배송지 수정 — 기본여부는 이 액션에서 변경하지 않는다(전용 액션 setDefaultAddressAction 사용)
export async function updateAddressAction(formData: FormData) {
  const token = await requireToken();
  const id = Number(formData.get("id"));
  const input = readInput(formData);
  // 수정에서는 기본여부를 바꾸지 않는다 — isDefault는 setDefaultAddressAction 전용
  const fields = {
    label: input.label,
    recipientName: input.recipientName,
    phone: input.phone,
    zipCode: input.zipCode,
    address1: input.address1,
    address2: input.address2,
  };

  let unauthorized = false;
  try {
    await updateAddress(token, id, fields);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      unauthorized = true;
    } else {
      throw err;
    }
  }
  if (unauthorized) {
    redirect("/refresh?next=/addresses");
  }

  revalidatePath("/addresses");
}

// 배송지 삭제 — 기본 배송지 삭제 시 백엔드가 최신 배송지를 자동으로 기본으로 승격
export async function deleteAddressAction(formData: FormData) {
  const token = await requireToken();
  const id = Number(formData.get("id"));

  let unauthorized = false;
  try {
    await deleteAddress(token, id);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      unauthorized = true;
    } else {
      throw err;
    }
  }
  if (unauthorized) {
    redirect("/refresh?next=/addresses");
  }

  revalidatePath("/addresses");
}

// 기본 배송지 지정
export async function setDefaultAddressAction(formData: FormData) {
  const token = await requireToken();
  const id = Number(formData.get("id"));

  let unauthorized = false;
  try {
    await setDefaultAddress(token, id);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      unauthorized = true;
    } else {
      throw err;
    }
  }
  if (unauthorized) {
    redirect("/refresh?next=/addresses");
  }

  revalidatePath("/addresses");
}
