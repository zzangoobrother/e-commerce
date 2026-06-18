import Link from "next/link";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ApiError, getAdminOrders } from "@/lib/api";
import type { AdminOrder, OrderStatus } from "@/lib/api";
import { ACCESS_COOKIE } from "@/lib/auth-cookies";
import LogoutButton from "../LogoutButton";
import { cancelOrderAction, deliverOrderAction, shipOrderAction } from "./actions";

// 상태 표시용 한국어 라벨
const STATUS_LABEL: Record<OrderStatus, string> = {
  ORDERED: "주문 완료",
  SHIPPING: "배송중",
  DELIVERED: "배송완료",
  CANCELLED: "취소됨",
};

// 상태 필터 탭 (전체 = value 없음)
const FILTERS: { label: string; value?: OrderStatus }[] = [
  { label: "전체" },
  { label: "주문 완료", value: "ORDERED" },
  { label: "배송중", value: "SHIPPING" },
  { label: "배송완료", value: "DELIVERED" },
  { label: "취소됨", value: "CANCELLED" },
];

// 어드민 주문 관리 — proxy가 보호하지만 쿠키 부재 시 이중 방어로 로그인으로 보낸다
export default async function AdminOrdersPage({
  searchParams,
}: {
  searchParams: Promise<{ status?: string; error?: string }>;
}) {
  const { status, error } = await searchParams;
  const token = (await cookies()).get(ACCESS_COOKIE)?.value;
  if (!token) {
    redirect("/admin/login");
  }

  // status 쿼리를 유효한 OrderStatus로 좁힘 (아니면 전체)
  const validStatus = FILTERS.some((f) => f.value === status)
    ? (status as OrderStatus)
    : undefined;

  let orders: AdminOrder[];
  let unauthorized = false;
  try {
    orders = await getAdminOrders(token, validStatus);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      unauthorized = true;
      orders = [];
    } else {
      return (
        <main style={{ padding: 24 }}>
          <h1>주문 관리</h1>
          <p>백엔드에 연결할 수 없습니다.</p>
        </main>
      );
    }
  }
  if (unauthorized) {
    const next = validStatus ? `/admin/orders?status=${validStatus}` : "/admin/orders";
    redirect(`/admin/refresh?next=${encodeURIComponent(next)}`);
  }

  return (
    <main style={{ padding: 24 }}>
      <header style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <Link href="/admin">← 어드민</Link>
        <LogoutButton />
      </header>
      <h1>주문 관리</h1>

      <nav style={{ display: "flex", gap: 12, margin: "12px 0" }}>
        {FILTERS.map((f) => (
          <Link key={f.label}
                href={f.value ? `/admin/orders?status=${f.value}` : "/admin/orders"}>
            {f.label}
          </Link>
        ))}
      </nav>

      {error && <p style={{ color: "crimson" }}>{error}</p>}

      {orders.length === 0 ? (
        <p>주문이 없습니다.</p>
      ) : (
        <ul style={{ display: "grid", gap: 12, listStyle: "none", padding: 0 }}>
          {orders.map((order) => (
            <li key={order.id}
                style={{ border: "1px solid #ddd", padding: 16, borderRadius: 8 }}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <strong>주문 #{order.id} · {STATUS_LABEL[order.status]}</strong>
                <small>{order.customerEmail} · {new Date(order.createdAt).toLocaleString("ko-KR")}</small>
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
                <div style={{ display: "flex", gap: 8 }}>
                  {order.status === "ORDERED" && (
                    <>
                      <form action={shipOrderAction} style={{ margin: 0 }}>
                        <input type="hidden" name="orderId" value={order.id} />
                        <input type="hidden" name="status" value={status ?? ""} />
                        <button type="submit" style={{ cursor: "pointer" }}>배송 시작</button>
                      </form>
                      <form action={cancelOrderAction} style={{ margin: 0 }}>
                        <input type="hidden" name="orderId" value={order.id} />
                        <input type="hidden" name="status" value={status ?? ""} />
                        <button type="submit" style={{ cursor: "pointer" }}>취소</button>
                      </form>
                    </>
                  )}
                  {order.status === "SHIPPING" && (
                    <form action={deliverOrderAction} style={{ margin: 0 }}>
                      <input type="hidden" name="orderId" value={order.id} />
                      <input type="hidden" name="status" value={status ?? ""} />
                      <button type="submit" style={{ cursor: "pointer" }}>배송 완료</button>
                    </form>
                  )}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
