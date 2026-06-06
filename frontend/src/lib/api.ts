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
