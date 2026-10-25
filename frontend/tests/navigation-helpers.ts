import type { Page } from '@playwright/test';
export async function openSection(page: Page, name: string) {
  const navigation = page.getByRole('navigation', { name: 'Основная навигация' });
  if (!(await navigation.isVisible())) await page.getByRole('button', { name: 'Открыть навигацию', exact: true }).click();
  await navigation.getByRole('button', { name, exact: true }).click();
}
