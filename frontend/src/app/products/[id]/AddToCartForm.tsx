"use client";

import { useActionState } from "react";
import { addToCartAction } from "./actions";

// 담기 폼 — Server Action이 검증 실패 메시지(재고 부족 등)를 돌려준다
export default function AddToCartForm({
  productId,
  maxQuantity,
}: {
  productId: number;
  maxQuantity: number;
}) {
  const [error, formAction] = useActionState(addToCartAction, null);

  return (
    <form action={formAction} style={{ display: "flex", gap: 8, alignItems: "center", marginTop: 12 }}>
      <input type="hidden" name="productId" value={productId} />
      <input
        name="quantity"
        type="number"
        min={1}
        max={maxQuantity}
        defaultValue={1}
        style={{ width: 64, border: "1px solid #ddd", padding: 6, borderRadius: 4 }}
      />
      <button type="submit" style={{ padding: "6px 16px", cursor: "pointer" }}>
        장바구니 담기
      </button>
      {error && <span style={{ color: "crimson" }}>{error}</span>}
    </form>
  );
}
