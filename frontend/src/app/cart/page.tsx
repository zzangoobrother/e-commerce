import Link from "next/link";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ApiError, getCart } from "@/lib/api";
import type { Cart } from "@/lib/api";
import { CUSTOMER_ACCESS_COOKIE } from "@/lib/auth-cookies";
import { removeItemAction, updateQuantityAction } from "./actions";

// 장바구니 — proxy가 보호하지만 쿠키 부재 시 이중 방어로 로그인으로 보낸다
export default async function CartPage({
  searchParams,
}: {
  searchParams: Promise<{ error?: string }>;
}) {
  const { error } = await searchParams;
  const token = (await cookies()).get(CUSTOMER_ACCESS_COOKIE)?.value;
  if (!token) {
    redirect("/login");
  }

  let cart: Cart;
  let unauthorized = false;
  try {
    cart = await getCart(token);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      unauthorized = true;
      cart = { items: [], totalPrice: 0 };
    } else {
      return (
        <main style={{ padding: 24 }}>
          <h1>장바구니</h1>
          <p>백엔드에 연결할 수 없습니다. (http://localhost:8080)</p>
        </main>
      );
    }
  }
  if (unauthorized) {
    // access 만료 경계 — 갱신 후 복귀 (redirect는 try 밖에서)
    redirect("/refresh?next=/cart");
  }

  return (
    <main style={{ padding: 24 }}>
      <Link href="/">← 계속 쇼핑</Link>
      <h1>장바구니</h1>
      {error && <p style={{ color: "crimson" }}>{error}</p>}
      {cart.items.length === 0 ? (
        <p>장바구니가 비어 있습니다.</p>
      ) : (
        <>
          <ul style={{ display: "grid", gap: 12, listStyle: "none", padding: 0 }}>
            {cart.items.map((item) => (
              <li key={item.productId}
                  style={{ border: "1px solid #ddd", padding: 16, borderRadius: 8 }}>
                <Link href={`/products/${item.productId}`}>
                  <strong>{item.productName}</strong>
                </Link>
                <div>
                  {item.price.toLocaleString()}원 × {item.quantity}개 ={" "}
                  {item.lineTotal.toLocaleString()}원
                </div>
                <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
                  <form action={updateQuantityAction} style={{ display: "flex", gap: 8 }}>
                    <input type="hidden" name="productId" value={item.productId} />
                    <input
                      name="quantity"
                      type="number"
                      min={1}
                      defaultValue={item.quantity}
                      style={{ width: 64, border: "1px solid #ddd", padding: 4, borderRadius: 4 }}
                    />
                    <button type="submit" style={{ cursor: "pointer" }}>수량 변경</button>
                  </form>
                  <form action={removeItemAction}>
                    <input type="hidden" name="productId" value={item.productId} />
                    <button type="submit" style={{ cursor: "pointer" }}>삭제</button>
                  </form>
                </div>
              </li>
            ))}
          </ul>
          <p style={{ fontSize: 18 }}>
            <strong>합계: {cart.totalPrice.toLocaleString()}원</strong>
          </p>
        </>
      )}
    </main>
  );
}
