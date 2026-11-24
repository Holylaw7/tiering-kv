import { expect, test } from '@playwright/test'

test.describe('Authentication & RBAC', () => {
  test('unauthenticated visitor redirects to login and admin can sign in', async ({ page }) => {
    await page.goto('/#/backups')
    await expect(page).toHaveURL(/#\/login/)
    await expect(page.getByText('登录控制台')).toBeVisible()

    await page.getByPlaceholder('admin').fill('admin')
    await page.getByPlaceholder('请输入密码').fill('admin123')
    await page.getByTestId('login-submit').click()

    await expect(page).toHaveURL(/#\/backups/)
    await expect(page.locator('.sidebar')).toContainText('审计日志')
    await expect(page.locator('.sidebar')).toContainText('用户管理')
  })

  test('VIEWER cannot access admin audit/users and cannot write via terminal or backups', async ({ page, request, baseURL }) => {
    const loginRes = await request.post(`${baseURL}/api/v1/auth/login`, {
      data: { username: 'admin', password: 'admin123' }
    })
    expect(loginRes.ok()).toBeTruthy()
    const adminToken = (await loginRes.json()).data.token as string

    const viewerName = `e2e_viewer_${Date.now()}`
    const createRes = await request.post(`${baseURL}/api/v1/users`, {
      headers: { Authorization: `Bearer ${adminToken}` },
      data: {
        username: viewerName,
        password: 'viewerPass123',
        nickname: 'E2E Viewer',
        email: `${viewerName}@example.com`,
        role: 'VIEWER',
        enabled: true
      }
    })
    expect(createRes.ok()).toBeTruthy()
    const viewerId = (await createRes.json()).data.id as number

    try {
      await page.goto('/#/login')
      await page.getByPlaceholder('admin').fill(viewerName)
      await page.getByPlaceholder('请输入密码').fill('viewerPass123')
      await page.getByTestId('login-submit').click()
      await expect(page).toHaveURL(/#\/dashboard/)

      // 审计与用户管理对 VIEWER 在侧边栏不可见，直接跳转路由亦被拦截回 dashboard
      const sidebar = page.locator('.sidebar')
      await expect(sidebar).toContainText('慢查询日志')
      await expect(sidebar).not.toContainText('审计日志')
      await expect(sidebar).not.toContainText('用户管理')

      await page.goto('/#/audit')
      await expect(page).toHaveURL(/#\/dashboard/)

      // 备份页隐藏写操作与敏感下载按钮
      await page.goto('/#/backups')
      await expect(page.getByRole('heading', { name: '备份与恢复' })).toBeVisible()
      await expect(page.getByRole('button', { name: '立即备份' })).toHaveCount(0)
      await expect(page.getByRole('button', { name: '触发物理 Checkpoint' })).toHaveCount(0)
      await expect(page.getByRole('button', { name: '下载', exact: true })).toHaveCount(0)

      // 后端 RBAC 兜底：VIEWER 调用终端写与备份下载返回 403
      const vLogin = await request.post(`${baseURL}/api/v1/auth/login`, {
        data: { username: viewerName, password: 'viewerPass123' }
      })
      const viewerToken = (await vLogin.json()).data.token as string
      const termForbidden = await request.post(`${baseURL}/api/v1/terminal/execute`, {
        headers: { Authorization: `Bearer ${viewerToken}` },
        data: { instanceId: 1, command: 'SET e2e:forbid 1' }
      })
      expect(termForbidden.status()).toBe(403)
      const dlForbidden = await request.get(`${baseURL}/api/v1/backups/1/download`, {
        headers: { Authorization: `Bearer ${viewerToken}` }
      })
      expect(dlForbidden.status()).toBe(403)
    } finally {
      await request.delete(`${baseURL}/api/v1/users/${viewerId}`, {
        headers: { Authorization: `Bearer ${adminToken}` }
      })
    }
  })
})
