// 백엔드 REST API 호출 래퍼 + 공유 타입

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

export type ProductStatus = "ON_SALE" | "SOLD_OUT" | "HIDDEN";
export type SupplierStatus = "ACTIVE" | "INACTIVE";

export interface Product {
  id: number;
  supplierId: number;
  supplierName: string;
  name: string;
  description: string | null;
  price: number;
  stockQuantity: number;
  status: ProductStatus;
  createdAt: string;
}

export interface Supplier {
  id: number;
  name: string;
  contactEmail: string | null;
  status: SupplierStatus;
  createdAt: string;
}

export interface TokenResponse {
  accessToken: string;
  accessExpiresAt: string;
  refreshToken: string;
  refreshExpiresAt: string;
}

export interface CartItem {
  productId: number;
  productName: string;
  price: number;
  quantity: number;
  lineTotal: number;
}

export interface Cart {
  items: CartItem[];
  totalPrice: number;
}

export type OrderStatus = "ORDERED" | "SHIPPING" | "DELIVERED" | "CANCELLED";

export interface OrderItem {
  productId: number;
  productName: string;
  price: number;
  quantity: number;
  lineTotal: number;
}

export interface Order {
  id: number;
  status: OrderStatus;
  totalPrice: number;
  createdAt: string;
  items: OrderItem[];
}

export interface ExcludedItem {
  productId: number;
  productName: string;
  reason: string;
}

export interface CreateOrderResult {
  order: Order;
  excludedItems: ExcludedItem[];
}

export interface AdminOrder {
  id: number;
  customerEmail: string;
  status: OrderStatus;
  totalPrice: number;
  createdAt: string;
  items: OrderItem[];
}

// HTTP 상태 코드를 보존하는 API 에러 (401 구분용)
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function getJson<T>(path: string, token?: string): Promise<T> {
  const headers: HeadersInit = token ? { Authorization: `Bearer ${token}` } : {};
  const res = await fetch(`${API_BASE}${path}`, { cache: "no-store", headers });
  if (!res.ok) {
    throw new ApiError(res.status, `API 요청 실패 (${res.status}): ${path}`);
  }
  return res.json() as Promise<T>;
}

// 인증 변경 요청 공통 처리 — 실패 시 서버 메시지를 보존한 ApiError, 204는 본문 없음
async function sendJson<T>(
  path: string,
  method: "POST" | "PATCH" | "DELETE",
  token: string,
  body?: unknown,
): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    cache: "no-store",
  });
  if (!res.ok) {
    const data = await res.json().catch(() => null);
    throw new ApiError(res.status, data?.message ?? `API 요청 실패 (${res.status}): ${path}`);
  }
  return (res.status === 204 ? undefined : await res.json()) as T;
}

// 스토어 (인증 불필요)
export const getProducts = () => getJson<Product[]>("/api/products");
export const getProduct = (id: string | number) =>
  getJson<Product>(`/api/products/${id}`);

// 인증
export async function login(
  username: string,
  password: string,
): Promise<TokenResponse> {
  const res = await fetch(`${API_BASE}/api/admin/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
    cache: "no-store",
  });
  if (!res.ok) {
    const data = await res.json().catch(() => null);
    throw new ApiError(
      res.status,
      data?.message ?? `로그인 실패 (${res.status})`,
    );
  }
  return res.json() as Promise<TokenResponse>;
}

// 어드민 (Bearer 토큰 필요)
export const getSuppliers = (token: string) =>
  getJson<Supplier[]>("/api/admin/suppliers", token);
export const getAdminProducts = (token: string, supplierId?: number) =>
  getJson<Product[]>(
    `/api/admin/products${supplierId ? `?supplierId=${supplierId}` : ""}`,
    token,
  );

// 리프레시 — refresh 토큰으로 새 access+refresh 발급
export async function refresh(refreshToken: string): Promise<TokenResponse> {
  const res = await fetch(`${API_BASE}/api/admin/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
    cache: "no-store",
  });
  if (!res.ok) {
    throw new ApiError(res.status, `리프레시 실패 (${res.status})`);
  }
  return res.json() as Promise<TokenResponse>;
}

// 로그아웃 — refresh 토큰 서버 폐기(204). 실패해도 쿠키 삭제는 호출자가 진행
export async function logout(refreshToken: string): Promise<void> {
  await fetch(`${API_BASE}/api/admin/logout`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
    cache: "no-store",
  });
}

// 고객 회원가입 — 성공 시 auto-login 토큰(201)
export async function registerCustomer(
  email: string,
  password: string,
): Promise<TokenResponse> {
  const res = await fetch(`${API_BASE}/api/store/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
    cache: "no-store",
  });
  if (!res.ok) {
    const data = await res.json().catch(() => null);
    throw new ApiError(res.status, data?.message ?? `회원가입 실패 (${res.status})`);
  }
  return res.json() as Promise<TokenResponse>;
}

// 고객 로그인
export async function customerLogin(
  email: string,
  password: string,
): Promise<TokenResponse> {
  const res = await fetch(`${API_BASE}/api/store/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
    cache: "no-store",
  });
  if (!res.ok) {
    const data = await res.json().catch(() => null);
    throw new ApiError(res.status, data?.message ?? `로그인 실패 (${res.status})`);
  }
  return res.json() as Promise<TokenResponse>;
}

// 고객 로그아웃 — refresh 토큰 서버 폐기(204). 실패해도 쿠키 삭제는 호출자가 진행
export async function customerLogout(refreshToken: string): Promise<void> {
  await fetch(`${API_BASE}/api/store/auth/logout`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
    cache: "no-store",
  });
}

// 고객 리프레시 — customer refresh 토큰으로 새 access+refresh 발급
export async function customerRefresh(refreshToken: string): Promise<TokenResponse> {
  const res = await fetch(`${API_BASE}/api/store/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
    cache: "no-store",
  });
  if (!res.ok) {
    throw new ApiError(res.status, `리프레시 실패 (${res.status})`);
  }
  return res.json() as Promise<TokenResponse>;
}

// 장바구니 (고객 Bearer 토큰 필요)
export const getCart = (token: string) => getJson<Cart>("/api/store/cart", token);
export const addCartItem = (token: string, productId: number, quantity: number) =>
  sendJson<Cart>("/api/store/cart/items", "POST", token, { productId, quantity });
export const updateCartItemQuantity = (token: string, productId: number, quantity: number) =>
  sendJson<Cart>(`/api/store/cart/items/${productId}`, "PATCH", token, { quantity });
export const removeCartItem = (token: string, productId: number) =>
  sendJson<void>(`/api/store/cart/items/${productId}`, "DELETE", token);

// 주문 (고객 Bearer 토큰 필요)
export const createOrder = (token: string) =>
  sendJson<CreateOrderResult>("/api/store/orders", "POST", token);
export const getOrders = (token: string) => getJson<Order[]>("/api/store/orders", token);
export const cancelOrder = (token: string, orderId: number) =>
  sendJson<Order>(`/api/store/orders/${orderId}/cancel`, "POST", token);

// 어드민 주문 관리 (어드민 Bearer 토큰 필요)
export const getAdminOrders = (token: string, status?: OrderStatus) =>
  getJson<AdminOrder[]>(
    `/api/admin/orders${status ? `?status=${status}` : ""}`,
    token,
  );
export const shipOrder = (token: string, orderId: number) =>
  sendJson<AdminOrder>(`/api/admin/orders/${orderId}/ship`, "POST", token);
export const deliverOrder = (token: string, orderId: number) =>
  sendJson<AdminOrder>(`/api/admin/orders/${orderId}/deliver`, "POST", token);
export const adminCancelOrder = (token: string, orderId: number) =>
  sendJson<AdminOrder>(`/api/admin/orders/${orderId}/cancel`, "POST", token);
