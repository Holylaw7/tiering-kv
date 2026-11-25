import { expect, test } from '@playwright/test'

test.describe('Instances TLS, SlowLog & Audit CSV E2E', () => {
  test('verifies TLS instance form controls, slowlog refresh and audit CSV export', async ({
    page,
    request,
    baseURL
  }) => {
    await page.goto('/#/login')
    await page.getByPlaceholder('admin').fill('admin')
    await page.getByPlaceholder('请输入密码').fill('admin123')
    await page.getByTestId('login-submit').click()
    await expect(page).toHaveURL(/#\/dashboard/)

    // 1. 实例管理页：校验 O2 TLS 开关与 CA PEM 输入框动态联动
    await page.locator('.sidebar').getByText('实例管理').click()
    await expect(page).toHaveURL(/#\/instances/)
    await expect(page.getByRole('heading', { name: '实例管理' })).toBeVisible()

    await page.getByRole('button', { name: '纳管实例' }).click()
    const instDialog = page.locator('.el-dialog').filter({ hasText: '纳管实例' })
    await expect(instDialog).toBeVisible()
    await expect(instDialog.locator('.el-form-item', { hasText: '校验主机名' })).toHaveCount(0)
    await expect(instDialog.locator('.el-form-item', { hasText: 'CA 证书' })).toHaveCount(0)

    await instDialog.locator('.el-form-item', { hasText: '启用 TLS' }).locator('.el-switch').click()
    await expect(instDialog.locator('.el-form-item', { hasText: '校验主机名' })).toBeVisible()
    await expect(instDialog.locator('.el-form-item', { hasText: 'CA 证书' }).locator('textarea')).toBeVisible()
    await instDialog.getByRole('button', { name: '取消' }).click()
    await expect(instDialog).toBeHidden()

    // 2. 慢查询日志页：刷新列表并校验后端接口支持 SLOWLOG
    await page.locator('.sidebar').getByText('慢查询日志').click()
    await expect(page).toHaveURL(/#\/slowlog/)
    await expect(page.getByRole('heading', { name: '慢查询日志' })).toBeVisible()
    await page.getByRole('button', { name: '刷新', exact: true }).click()

    const loginRes = await request.post(`${baseURL}/api/v1/auth/login`, {
      data: { username: 'admin', password: 'admin123' }
    })
    const adminToken = (await loginRes.json()).data.token as string
    const instListRes = await request.get(`${baseURL}/api/v1/instances`, {
      headers: { Authorization: `Bearer ${adminToken}` }
    })
    const instanceId = ((await instListRes.json()).data as Array<{ id: number }>)[0].id
    const slowRes = await request.get(`${baseURL}/api/v1/slowlog?instanceId=${instanceId}&limit=20`, {
      headers: { Authorization: `Bearer ${adminToken}` }
    })
    expect(slowRes.status()).toBe(200)
    expect((await slowRes.json()).data.supported).toBe(true)

    // 3. 审计日志页：按 LOGIN 条件筛选 + CSV 导出响应头与正文校验
    await page.locator('.sidebar').getByText('审计日志').click()
    await expect(page).toHaveURL(/#\/audit/)
    await expect(page.getByRole('heading', { name: '审计日志' })).toBeVisible()

    await page.getByPlaceholder('动作，如 KEY_DELETE').fill('LOGIN')
    await page.getByRole('button', { name: '查询', exact: true }).click()
    await expect(page.locator('.el-table__body')).toContainText('LOGIN')

    const [exportRes] = await Promise.all([
      page.waitForResponse((res) => res.url().includes('/api/v1/audit/export') && res.request().method() === 'GET'),
      page.getByRole('button', { name: '导出 CSV' }).click()
    ])
    expect(exportRes.status()).toBe(200)
    expect(exportRes.headers()['content-type'] || '').toContain('text/csv')
    const csvText = await exportRes.text()
    expect(csvText).toContain('LOGIN')

    // 4. 系统信息页（M4）：校验内核扩展 INFO Sections 只读透出、段落切换与过滤
    const sysRes = await request.get(`${baseURL}/api/v1/system/info?instanceId=${instanceId}`, {
      headers: { Authorization: `Bearer ${adminToken}` }
    })
    expect(sysRes.status()).toBe(200)
    const sysSections = (await sysRes.json()).data.sections as Record<string, Record<string, string>>
    expect(sysSections).toBeTruthy()
    expect(sysSections.Tiering).toBeTruthy()
    expect(sysSections.Persistence).toBeTruthy()
    expect(sysSections.Server).toBeTruthy()
    expect(sysSections.default).toBeUndefined()

    await page.locator('.sidebar').getByText('系统信息').click()
    await expect(page).toHaveURL(/#\/system/)
    await expect(page.getByRole('heading', { name: '系统信息' })).toBeVisible()

    const sectionsPanel = page.getByTestId('info-sections-panel')
    await expect(sectionsPanel).toBeVisible()
    const tieringTab = sectionsPanel.locator('[role="tab"][data-section="Tiering"]')
    await expect(tieringTab).toHaveAttribute('aria-selected', 'true')
    await expect(sectionsPanel.locator('.el-table__body')).toContainText('tiering_memtable_used_bytes')

    const persistenceTab = sectionsPanel.locator('[role="tab"][data-section="Persistence"]')
    await persistenceTab.click()
    await expect(persistenceTab).toHaveAttribute('aria-selected', 'true')

    const filterInput = sectionsPanel.getByPlaceholder('过滤指标键或值，如 memtable / rdb')
    await filterInput.fill('__no_such_metric_xyz__')
    await expect(sectionsPanel).toContainText('当前段落无匹配指标')
    await filterInput.fill('')
    await expect(sectionsPanel.locator('.el-table__body tr').first()).toBeVisible()
  })
})
