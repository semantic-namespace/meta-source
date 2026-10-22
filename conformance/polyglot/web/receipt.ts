import { formatMoney } from './money';

/** @meta-source/id :fn/render-receipt */
export function renderReceipt(order: { total: number; lines: { name: string }[] }): string {
  const items = order.lines.map((l) => `<li>${l.name}</li>`).join('');
  return `<ul>${items}</ul><p>${formatMoney(order.total)}</p>`;
}

export function receiptPage(order: { total: number; lines: { name: string }[] }): string {
  return `<html>${renderReceipt(order)}</html>`;
}
