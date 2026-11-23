import type { Page } from '@playwright/test';
export async function openSection(page: Page, name: string) {
  await page.getByRole('heading', { level: 1 }).waitFor({ state: 'visible' });
  const navigation = page.getByRole('navigation', { name: 'Основная навигация', includeHidden: true });
  await navigation.waitFor({ state: 'attached' });
  if (!(await navigation.isVisible())) await page.getByRole('button', { name: 'Открыть навигацию', exact: true }).click();
  await navigation.getByRole('button', { name, exact: true }).click();
  if (name === 'Поиск') await page.getByLabel('Область поиска', { exact: true }).selectOption('channel');
}
