import Link from "next/link";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ApiError, getOrders } from "@/lib/api";
import type { Order } from "@/lib/api";
import { CUSTOMER_ACCESS_COOKIE } from "@/lib/auth-cookies";
import { cancelOrderAction } from "./actions";

// 상태 표시용 한국어 라벨
const STATUS_LABEL: Record<Order["status"], string> = {
  ORDERED: "주문 완료",
  CANCELLED: "취소됨",
};

// 주문 목록 — proxy가 보호하지만 쿠키 부재 시 이중 방어로 로그인으로 보낸다
export default async function OrdersPage({
  searchParams,
}: {
  searchParams: Promise<{ notice?: string; error?: string }>;
}) {
  const { notice, error } = await searchParams;
  const token = (await cookies()).get(CUSTOMER_ACCESS_COOKIE)?.value;
  if (!token) {
    redirect("/login");
  }

  let orders: Order[];
  let unauthorized = false;
  try {
    orders = await getOrders(token);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      unauthorized = true;
      orders = [];
    } else {
      return (
        <main style={{ padding: 24 }}>
          <h1>주문 내역</h1>
          <p>백엔드에 연결할 수 없습니다. (http://localhost:8080)</p>
        </main>
      );
    }
  }
  if (unauthorized) {
    // access 만료 경계 — 갱신 후 복귀 (redirect는 try 밖에서)
    redirect("/refresh?next=/orders");
  }

  return (
    <main style={{ padding: 24 }}>
      <Link href="/">← 계속 쇼핑</Link>
      <h1>주문 내역</h1>
      {notice && <p style={{ color: "darkorange" }}>{notice}</p>}
      {error && <p style={{ color: "crimson" }}>{error}</p>}
      {orders.length === 0 ? (
        <p>주문 내역이 없습니다.</p>
      ) : (
        <ul style={{ display: "grid", gap: 12, listStyle: "none", padding: 0 }}>
          {orders.map((order) => (
            <li key={order.id}
                style={{ border: "1px solid #ddd", padding: 16, borderRadius: 8 }}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <strong>주문 #{order.id} · {STATUS_LABEL[order.status]}</strong>
                <small>{new Date(order.createdAt).toLocaleString("ko-KR")}</small>
              </div>
              <ul style={{ listStyle: "none", padding: 0, marginTop: 8 }}>
                {order.items.map((item) => (
                  <li key={item.productId}>
                    {item.productName} — {item.price.toLocaleString()}원 × {item.quantity}개 ={" "}
                    {item.lineTotal.toLocaleString()}원
                  </li>
                ))}
              </ul>
              <div style={{ display: "flex", justifyContent: "space-between",
                            alignItems: "center", marginTop: 8 }}>
                <strong>합계: {order.totalPrice.toLocaleString()}원</strong>
                {order.status === "ORDERED" && (
                  <form action={cancelOrderAction} style={{ margin: 0 }}>
                    <input type="hidden" name="orderId" value={order.id} />
                    <button type="submit" style={{ cursor: "pointer" }}>주문 취소</button>
                  </form>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
