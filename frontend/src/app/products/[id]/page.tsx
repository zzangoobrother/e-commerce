import Link from "next/link";
import { getProduct } from "@/lib/api";
import AddToCartForm from "./AddToCartForm";

export default async function ProductDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  let product;
  try {
    product = await getProduct(id);
  } catch {
    return (
      <main style={{ padding: 24 }}>
        <p>상품을 찾을 수 없습니다.</p>
        <Link href="/">← 목록으로</Link>
      </main>
    );
  }

  return (
    <main style={{ padding: 24 }}>
      <Link href="/">← 목록으로</Link>
      <h1>{product.name}</h1>
      <p>{product.description}</p>
      <p><strong>{product.price.toLocaleString()}원</strong></p>
      <p>재고: {product.stockQuantity}개</p>
      <p>공급사: {product.supplierName}</p>
      {product.status === "ON_SALE" && (
        <AddToCartForm productId={product.id} maxQuantity={product.stockQuantity} />
      )}
    </main>
  );
}
