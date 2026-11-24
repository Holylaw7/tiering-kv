import { expect, test } from '@playwright/test'

test.describe('Backups Async Pipeline & Restore E2E', () => {
  test('seeds keys, runs async backup, downloads artifact, deletes keys and restores asynchronously via UI', async ({
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

    const instRes = await request.get(`${baseURL}/api/v1/instances`, { headers: authHeaders })
    expect(instRes.ok()).toBeTruthy()
    const instances = (await instRes.json()).data as Array<{ id: number }>
    expect(instances.length).toBeGreaterThan(0)
    const instanceId = instances[0].id

    const prefix = `e2e:o5:restore:${Date.now()}`
    const totalKeys = 30
    let createdBackupId: number | undefined

    try {
      // 1. 构造 30 个测试键，覆盖批量 Pipeline 导出与恢复链路
      for (let i = 0; i < totalKeys; i++) {
        const res = await request.post(`${baseURL}/api/v1/terminal/execute`, {
          headers: authHeaders,
          data: { instanceId, command: `SET ${prefix}:${i} val-${i}` }
        })
        expect(res.ok()).toBeTruthy()
      }

      // 2. 登录进入备份页，触发异步备份并等待 SUCCESS
      await page.goto('/#/login')
      await page.getByPlaceholder('admin').fill('admin')
      await page.getByPlaceholder('请输入密码').fill('admin123')
      await page.getByTestId('login-submit').click()
      await expect(page).toHaveURL(/#\/dashboard/)

      await page.locator('.sidebar').getByText('备份恢复').click()
      await expect(page).toHaveURL(/#\/backups/)

      const beforeListRes = await request.get(`${baseURL}/api/v1/backups?instanceId=${instanceId}`, {
        headers: authHeaders
      })
      const existingIds = new Set(
        ((await beforeListRes.json()).data as Array<{ id: number }>).map((b) => b.id)
      )

      await page.getByRole('button', { name: '立即备份' }).click()

      // 轮询后端接口拿到本次新建备份 ID 与 SUCCESS 状态
      let createdFileName = ''
      await expect
        .poll(
          async () => {
            const listRes = await request.get(`${baseURL}/api/v1/backups?instanceId=${instanceId}`, {
              headers: authHeaders
            })
            const records = (await listRes.json()).data as Array<{
              id: number
              fileName: string
              status: string
              keyCount: number
            }>
            const created = records.find((r) => !existingIds.has(r.id))
            if (created && created.status === 'SUCCESS') {
              createdBackupId = created.id
              createdFileName = created.fileName
              return created.keyCount
            }
            return -1
          },
          { timeout: 25_000, intervals: [500, 1000] }
        )
        .toBeGreaterThanOrEqual(totalKeys)

      // 3. 验证 ADMIN 可下载备份文件（O1-A：ADMIN + OPS 权限校验）
      const downloadRes = await request.get(`${baseURL}/api/v1/backups/${createdBackupId}/download`, {
        headers: authHeaders
      })
      expect(downloadRes.status()).toBe(200)
      expect(downloadRes.headers()['content-disposition'] || '').toContain(createdFileName)

      // 4. 删除前 10 个测试键，确认它们确实从内核消失
      for (let i = 0; i < 10; i++) {
        await request.post(`${baseURL}/api/v1/terminal/execute`, {
          headers: authHeaders,
          data: { instanceId, command: `DEL ${prefix}:${i}` }
        })
      }
      const deletedCheck = await request.post(`${baseURL}/api/v1/terminal/execute`, {
        headers: authHeaders,
        data: { instanceId, command: `GET ${prefix}:0` }
      })
      const deletedBody = (await deletedCheck.json()).data as { replyType: string; payload?: unknown }
      expect(deletedBody.payload ?? null).toBeNull()

      // 5. 在 UI 上点击该备份行的“恢复”并确认异步轮询完成
      await page.getByRole('button', { name: '刷新列表' }).click()
      const targetRow = page.locator('.el-table__row', { hasText: createdFileName }).first()
      await expect(targetRow).toBeVisible()
      await targetRow.getByRole('button', { name: '恢复', exact: true }).click()
      await page.locator('.el-message-box__btns .el-button--primary').click()

      await expect(targetRow).toContainText(/已恢复 \d+ 键/, { timeout: 25_000 })

      // 6. 验证被删除的键已精确复原
      for (const idx of [0, 4, 9]) {
        const restored = await request.post(`${baseURL}/api/v1/terminal/execute`, {
          headers: authHeaders,
          data: { instanceId, command: `GET ${prefix}:${idx}` }
        })
        const restoredBody = (await restored.json()).data as { payload: unknown }
        expect(restoredBody.payload).toBe(`val-${idx}`)
      }
    } finally {
      try {
        if (createdBackupId !== undefined) {
          await request.delete(`${baseURL}/api/v1/backups/${createdBackupId}`, { headers: authHeaders })
        }
        for (let i = 0; i < totalKeys; i++) {
          await request.post(`${baseURL}/api/v1/terminal/execute`, {
            headers: authHeaders,
            data: { instanceId, command: `DEL ${prefix}:${i}` }
          })
        }
      } catch {
        // 测试超时退出时忽略清理异常，避免覆盖真实报错
      }
    }
  })
})
