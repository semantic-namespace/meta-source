/** @meta-source/id :fn/format-money */
export function formatMoney(amount: number): string {
  return `$${amount.toFixed(2)}`;
}

export const ROUTES = {
  /** @meta-source/id :page/receipt */
  receipt: (id: string) => `/orders/${id}/receipt`,
  /** @meta-source/id :fn/format-money */
  home: () => '/',
};
