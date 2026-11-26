import { expect, test } from '@playwright/test'

test.describe('Backup Schedules & JWT Token Revocation E2E (v6.1.0)', () => {
  test('creates, runs immediately, toggles and deletes a backup schedule via UI', async ({
    page,
    request,
    baseURL
  }) => {
    const loginRes = await request.post(`${baseURL}/api/v1/auth/login`, {
      data: { username: 'admin', password: 'admin123' }
    })
    expect(loginRes.ok()).toBeTruthy()
    const adminToken = (await loginRes.json()).data.token as string
    const authHeaders = { Authorization: `Bearer ${adminToken}` }

    const scheduleName = `e2e-sched-${Date.now()}`

    try {
      await page.goto('/#/login')
      await page.getByPlaceholder('admin').fill('admin')
      await page.getByPlaceholder('请输入密码').fill('admin123')
      await page.getByTestId('login-submit').click()
      await expect(page).toHaveURL(/#\/dashboard/)

      await page.locator('.sidebar').getByText('备份恢复').click()
      await expect(page).toHaveURL(/#\/backups/)

      // 1. 打开新建定时策略对话框并创建物理快照（CHECKPOINT）策略
      await page.getByRole('button', { name: '新建定时策略' }).click()
      const dialog = page.locator('.el-dialog').filter({ hasText: '新建定时备份策略' })
      await expect(dialog).toBeVisible()
      await dialog.getByPlaceholder('例如：每日凌晨逻辑备份').fill(scheduleName)
      await dialog.getByRole('button', { name: '每天 02:00' }).click()
      await dialog.getByText('物理快照（BGSAVE）').click()
      await dialog.getByRole('button', { name: '保存' }).click()

      const scheduleRow = page
        .locator('.schedules-panel .el-table__row', { hasText: scheduleName })
        .first()
      await expect(scheduleRow).toBeVisible({ timeout: 10_000 })
      await expect(scheduleRow).toContainText('0 0 2 * * *')
      await expect(scheduleRow).toContainText('物理 BGSAVE')
      await expect(scheduleRow).toContainText('已启用')

      // 2. 立即执行一次策略并确认状态落库
      await scheduleRow.getByRole('button', { name: '立即执行' }).click()
      await expect(scheduleRow).toContainText(/SUCCESS|SKIPPED/, { timeout: 15_000 })

      // 3. 停用策略并确认状态切换
      await scheduleRow.getByRole('button', { name: '停用', exact: true }).click()
      await expect(scheduleRow).toContainText('已停用', { timeout: 10_000 })

      // 4. 删除策略并确认列表移除
      await scheduleRow.getByRole('button', { name: '删除', exact: true }).click()
      await page.locator('.el-message-box__btns .el-button--primary').click()
      await expect(
        page.locator('.schedules-panel .el-table__row', { hasText: scheduleName })
      ).toHaveCount(0)
    } finally {
      try {
        const listRes = await request.get(`${baseURL}/api/v1/backups/schedules`, {
          headers: authHeaders
        })
        if (listRes.ok()) {
          const items = (await listRes.json()).data as Array<{ id: number; name: string }>
          for (const item of items.filter((s) => s.name === scheduleName)) {
            await request.delete(`${baseURL}/api/v1/backups/schedules/${item.id}`, {
              headers: authHeaders
            })
          }
        }
      } catch {
        // 清理失败不覆盖真实断言错误
      }
    }
  })

  test('logout and password change immediately revoke old JWT across API and UI', async ({
    page,
    request,
    baseURL
  }) => {
    const adminLogin = await request.post(`${baseURL}/api/v1/auth/login`, {
      data: { username: 'admin', password: 'admin123' }
    })
    expect(adminLogin.ok()).toBeTruthy()
    const adminToken = (await adminLogin.json()).data.token as string
    const adminHeaders = { Authorization: `Bearer ${adminToken}` }

    const tempUser = `e2e_revoke_${Date.now()}`
    const createRes = await request.post(`${baseURL}/api/v1/users`, {
      headers: adminHeaders,
      data: {
        username: tempUser,
        password: 'initPass123',
        nickname: 'E2E Revoke',
        email: `${tempUser}@example.com`,
        role: 'OPS'
      }
    })
    expect(createRes.ok()).toBeTruthy()
    const tempUserId = (await createRes.json()).data.id as number

    try {
      // 1. UI 登录获取 token1，验证 /auth/me 正常返回 200
      await page.goto('/#/login')
      await page.getByPlaceholder('admin').fill(tempUser)
      await page.getByPlaceholder('请输入密码').fill('initPass123')
      await page.getByTestId('login-submit').click()
      await expect(page).toHaveURL(/#\/dashboard/)

      const token1 = await page.evaluate(() => localStorage.getItem('tk_console_token'))
      expect(token1).toBeTruthy()
      const meBeforeLogout = await request.get(`${baseURL}/api/v1/auth/me`, {
        headers: { Authorization: `Bearer ${token1}` }
      })
      expect(meBeforeLogout.status()).toBe(200)

      // 2. 通过顶栏菜单「退出登录」触发 POST /api/v1/auth/logout，旧 token1 立即返回 401
      await page.locator('.account').click()
      await page.getByRole('menuitem', { name: '退出登录' }).click()
      await expect(page).toHaveURL(/#\/login/)

      const meAfterLogout = await request.get(`${baseURL}/api/v1/auth/me`, {
        headers: { Authorization: `Bearer ${token1}` }
      })
      expect(meAfterLogout.status()).toBe(401)

      // 3. 重新登录获取 token2，通过顶栏菜单「修改口令」改密 → 自动跳回登录页且旧 token2 立即 401
      await page.getByPlaceholder('admin').fill(tempUser)
      await page.getByPlaceholder('请输入密码').fill('initPass123')
      await page.getByTestId('login-submit').click()
      await expect(page).toHaveURL(/#\/dashboard/)

      const token2 = await page.evaluate(() => localStorage.getItem('tk_console_token'))
      expect(token2).toBeTruthy()
      expect(token2).not.toBe(token1)

      await page.locator('.account').click()
      await page.getByRole('menuitem', { name: '修改口令' }).click()
      const pwdDialog = page.locator('.el-dialog').filter({ hasText: '修改口令' })
      await expect(pwdDialog).toBeVisible()
      const inputs = pwdDialog.locator('input[type="password"]')
      await inputs.nth(0).fill('initPass123')
      await inputs.nth(1).fill('changedPass456')
      await inputs.nth(2).fill('changedPass456')
      await pwdDialog.getByRole('button', { name: '保存' }).click()

      await expect(page).toHaveURL(/#\/login/, { timeout: 10_000 })

      const meAfterPwdChange = await request.get(`${baseURL}/api/v1/auth/me`, {
        headers: { Authorization: `Bearer ${token2}` }
      })
      expect(meAfterPwdChange.status()).toBe(401)

      // 4. 新口令可正常登录
      const loginWithNewPwd = await request.post(`${baseURL}/api/v1/auth/login`, {
        data: { username: tempUser, password: 'changedPass456' }
      })
      expect(loginWithNewPwd.status()).toBe(200)
    } finally {
      try {
        await request.delete(`${baseURL}/api/v1/users/${tempUserId}`, { headers: adminHeaders })
      } catch {
        // 忽略清理异常
      }
    }
  })
})
