import Link from "next/link";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ApiError, getAddresses } from "@/lib/api";
import type { Address } from "@/lib/api";
import { CUSTOMER_ACCESS_COOKIE } from "@/lib/auth-cookies";
import {
  createAddressAction,
  deleteAddressAction,
  setDefaultAddressAction,
} from "./actions";

// 배송지 목록 — proxy가 보호하지만 쿠키 부재 시 이중 방어로 로그인으로 보낸다
export default async function AddressesPage() {
  const token = (await cookies()).get(CUSTOMER_ACCESS_COOKIE)?.value;
  if (!token) {
    redirect("/login");
  }

  let addresses: Address[];
  let unauthorized = false;
  try {
    addresses = await getAddresses(token);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      unauthorized = true;
      addresses = [];
    } else {
      return (
        <main style={{ padding: 24 }}>
          <h1>배송지 관리</h1>
          <p>백엔드에 연결할 수 없습니다. (http://localhost:8080)</p>
        </main>
      );
    }
  }
  if (unauthorized) {
    // access 만료 경계 — 갱신 후 복귀 (redirect는 try 밖에서)
    redirect("/refresh?next=/addresses");
  }

  return (
    <main style={{ padding: 24 }}>
      <Link href="/">← 계속 쇼핑</Link>
      <h1>배송지 관리</h1>
      {addresses.length === 0 ? (
        <p>등록된 배송지가 없습니다.</p>
      ) : (
        <ul style={{ display: "grid", gap: 12, listStyle: "none", padding: 0 }}>
          {addresses.map((a) => (
            <li key={a.id}
                style={{ border: "1px solid #ddd", padding: 16, borderRadius: 8 }}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <strong>
                  {a.label}
                  {a.isDefault && <span style={{ color: "darkorange" }}> [기본배송지]</span>}
                </strong>
              </div>
              <p style={{ margin: "8px 0 0" }}>
                {a.recipientName} · {a.phone}
              </p>
              <p style={{ margin: "4px 0 0", color: "#555" }}>
                ({a.zipCode}) {a.address1} {a.address2}
              </p>
              <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
                {!a.isDefault && (
                  <form action={setDefaultAddressAction} style={{ margin: 0 }}>
                    <input type="hidden" name="id" value={a.id} />
                    <button type="submit" style={{ cursor: "pointer" }}>기본배송지로 지정</button>
                  </form>
                )}
                <form action={deleteAddressAction} style={{ margin: 0 }}>
                  <input type="hidden" name="id" value={a.id} />
                  <button type="submit" style={{ cursor: "pointer" }}>삭제</button>
                </form>
              </div>
            </li>
          ))}
        </ul>
      )}

      <h2 style={{ marginTop: 32 }}>새 배송지 등록</h2>
      <form action={createAddressAction}
            style={{ display: "grid", gap: 8, maxWidth: 360, marginTop: 12 }}>
        <label>
          배송지명
          <input type="text" name="label" required style={{ width: "100%" }} />
        </label>
        <label>
          수령인
          <input type="text" name="recipientName" required style={{ width: "100%" }} />
        </label>
        <label>
          연락처
          <input type="text" name="phone" required style={{ width: "100%" }} />
        </label>
        <label>
          우편번호
          <input type="text" name="zipCode" required style={{ width: "100%" }} />
        </label>
        <label>
          주소
          <input type="text" name="address1" required style={{ width: "100%" }} />
        </label>
        <label>
          상세주소
          <input type="text" name="address2" style={{ width: "100%" }} />
        </label>
        <label>
          <input type="checkbox" name="isDefault" value="true" /> 기본배송지로 지정
        </label>
        <button type="submit" style={{ cursor: "pointer" }}>등록</button>
      </form>
    </main>
  );
}
