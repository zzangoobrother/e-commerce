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

export interface LoginResponse {
  token: string;
  expiresAt: string;
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
): Promise<LoginResponse> {
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
  return res.json() as Promise<LoginResponse>;
}

// 어드민 (Bearer 토큰 필요)
export const getSuppliers = (token: string) =>
  getJson<Supplier[]>("/api/admin/suppliers", token);
export const getAdminProducts = (token: string, supplierId?: number) =>
  getJson<Product[]>(
    `/api/admin/products${supplierId ? `?supplierId=${supplierId}` : ""}`,
    token,
  );
