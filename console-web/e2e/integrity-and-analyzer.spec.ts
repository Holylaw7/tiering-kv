import { expect, test } from '@playwright/test'

test.describe('Backup Integrity, Keyspace Analyzer & Request-ID Trace E2E (v6.4.0)', () => {
  test('verifies SHA-256 integrity, Dry-Run conflict preview + safety snapshot restore, BigKey/Prefix/TTL diagnostics, and Request-ID audit tracing', async ({
    page,
    request,
    baseURL
  }) => {
    const ts = Date.now()
    const traceId = `e2e-d4-trace-${ts}`
    const prefix = `e2e:d4:${ts}`

    // 1. 登录并验证 X-Request-Id 透传回响应头且写入 op_log
    const loginRes = await request.post(`${baseURL}/api/v1/auth/login`, {
      headers: { 'X-Request-Id': traceId },
      data: { username: 'admin', password: 'admin123' }
    })
    expect(loginRes.ok()).toBeTruthy()
    expect(loginRes.headers()['x-request-id']).toBe(traceId)
    const adminToken = (await loginRes.json()).data.token as string
    const authHeaders = { Authorization: `Bearer ${adminToken}` }

    const instRes = await request.get(`${baseURL}/api/v1/instances`, { headers: authHeaders })
    expect(instRes.ok()).toBeTruthy()
    const instances = (await instRes.json()).data as Array<{ id: number }>
    expect(instances.length).toBeGreaterThan(0)
    const instanceId = instances[0].id

    const bigKey = `${prefix}:big:payload`
    const hashKey = `${prefix}:profile:h1`
    const cacheKey1 = `${prefix}:cache:k1`
    const cacheKey2 = `${prefix}:cache:k2`
    const sessionKey1 = `${prefix}:session:s1`
    const sessionKey2 = `${prefix}:session:s2`
    const seededKeys = [bigKey, hashKey, cacheKey1, cacheKey2, sessionKey1, sessionKey2]
    let createdBackupId: number | undefined

    try {
      // 2. 构造多类型测试键（4KB 大字符串通过键写入接口避免终端 512 字符上限，其余走终端命令）
      const bigPayload = 'B'.repeat(4096)
      const putBigRes = await request.put(`${baseURL}/api/v1/keys?instanceId=${instanceId}`, {
        headers: authHeaders,
        data: { key: bigKey, value: bigPayload }
      })
      expect(putBigRes.ok()).toBeTruthy()

      const cmds = [
        `HSET ${hashKey} name alice role admin tier hot`,
        `SET ${cacheKey1} orig-cache-1 EX 3600`,
        `SET ${cacheKey2} orig-cache-2 EX 3600`,
        `SET ${sessionKey1} sess-1 EX 172800`,
        `SET ${sessionKey2} sess-2 EX 172800`
      ]
      for (const command of cmds) {
        const execRes = await request.post(`${baseURL}/api/v1/terminal/execute`, {
          headers: authHeaders,
          data: { instanceId, command }
        })
        expect(execRes.ok()).toBeTruthy()
        expect((await execRes.json()).data.error).toBe(false)
      }

      // 3. 触发逻辑备份并等待 SUCCESS，断言 SHA-256 摘要与 VERIFIED 状态
      const createRes = await request.post(`${baseURL}/api/v1/backups?instanceId=${instanceId}`, {
        headers: authHeaders
      })
      expect(createRes.ok()).toBeTruthy()
      const createdInitial = (await createRes.json()).data as { id: number; fileName: string }
      createdBackupId = createdInitial.id
      const createdFileName = createdInitial.fileName

      let backupSha256 = ''
      await expect
        .poll(
          async () => {
            const listRes = await request.get(`${baseURL}/api/v1/backups?instanceId=${instanceId}`, {
              headers: authHeaders
            })
            const records = (await listRes.json()).data as Array<{
              id: number
              status: string
              sha256?: string
              verifyStatus?: string
            }>
            const rec = records.find((r) => r.id === createdBackupId)
            if (rec?.status === 'SUCCESS') {
              backupSha256 = rec.sha256 ?? ''
              return rec.verifyStatus ?? ''
            }
            return ''
          },
          { timeout: 25_000, intervals: [500, 1000] }
        )
        .toBe('VERIFIED')
      expect(backupSha256).toMatch(/^[0-9a-f]{64}$/)

      // 4. 制造 1 个覆写冲突键 + 1 个缺失待恢复键，检验 Dry-Run 冲突预览与保护快照恢复
      await request.post(`${baseURL}/api/v1/terminal/execute`, {
        headers: authHeaders,
        data: { instanceId, command: `SET ${cacheKey1} mutated-before-restore` }
      })
      await request.post(`${baseURL}/api/v1/terminal/execute`, {
        headers: authHeaders,
        data: { instanceId, command: `DEL ${cacheKey2}` }
      })

      await page.goto('/#/login')
      await page.getByPlaceholder('admin').fill('admin')
      await page.getByPlaceholder('请输入密码').fill('admin123')
      await page.getByTestId('login-submit').click()
      await expect(page).toHaveURL(/#\/dashboard/)

      await page.locator('.sidebar').getByText('备份恢复').click()
      await expect(page).toHaveURL(/#\/backups/)

      const row = page.locator('.el-table__row', { hasText: createdFileName }).first()
      await expect(row).toBeVisible()
      await expect(row).toContainText('已校验')
      await expect(row).toContainText(backupSha256.slice(0, 12))

      // 点击「校验」验证后端流式 SHA-256 + JSONL 校验接口
      const [verifyRes] = await Promise.all([
        page.waitForResponse(
          (res) => res.url().includes(`/api/v1/backups/${createdBackupId}/verify`) && res.request().method() === 'POST'
        ),
        row.getByRole('button', { name: '校验', exact: true }).click()
      ])
      expect(verifyRes.status()).toBe(200)
      const verifyBody = (await verifyRes.json()).data as { valid: boolean; verifyStatus: string; validEntries: number }
      expect(verifyBody.valid).toBe(true)
      expect(verifyBody.verifyStatus).toBe('VERIFIED')
      expect(verifyBody.validEntries).toBeGreaterThanOrEqual(seededKeys.length)

      // 打开“安全恢复向导”并断言 Dry-Run 精确检出冲突与新增键，开启保护快照执行恢复
      const [previewRes] = await Promise.all([
        page.waitForResponse(
          (res) =>
            res.url().includes(`/api/v1/backups/${createdBackupId}/restore/preview`) &&
            res.request().method() === 'POST'
        ),
        row.getByRole('button', { name: '恢复', exact: true }).click()
      ])
      expect(previewRes.status()).toBe(200)
      const previewBody = (await previewRes.json()).data as {
        conflictKeys: number
        newKeys: number
        sampleConflicts: string[]
      }
      expect(previewBody.conflictKeys).toBeGreaterThanOrEqual(1)
      expect(previewBody.newKeys).toBeGreaterThanOrEqual(1)

      const wizard = page.getByRole('dialog', { name: /安全恢复向导/ })
      await expect(wizard).toBeVisible()
      await expect(wizard).toContainText('覆盖冲突键')
      await expect(wizard).toContainText('预计新增键')
      const confirmBtn = wizard.getByRole('button', { name: '确认执行恢复' })
      await expect(confirmBtn).toBeEnabled({ timeout: 15_000 })
      await confirmBtn.click()

      await expect(row).toContainText(/已恢复 \d+ 键/, { timeout: 25_000 })
      await expect(row).toContainText(/保护快照 #\d+/, { timeout: 10_000 })

      // 断言被篡改与被删除的键均恢复至快照原值
      const k1After = await request.post(`${baseURL}/api/v1/terminal/execute`, {
        headers: authHeaders,
        data: { instanceId, command: `GET ${cacheKey1}` }
      })
      expect((await k1After.json()).data.payload).toBe('orig-cache-1')
      const k2After = await request.post(`${baseURL}/api/v1/terminal/execute`, {
        headers: authHeaders,
        data: { instanceId, command: `GET ${cacheKey2}` }
      })
      expect((await k2After.json()).data.payload).toBe('orig-cache-2')

      // 5. 键空间 -> 大键 / 前缀 / TTL 诊断：运行采样并校验图表、榜单与详情抽屉联动
      await page.locator('.sidebar').getByText('键空间').click()
      await expect(page).toHaveURL(/#\/keyspace/)

      await page.getByRole('tab', { name: '大键 / 前缀 / TTL 诊断' }).click()
      const analyzePane = page.getByRole('tabpanel', { name: '大键 / 前缀 / TTL 诊断' })
      await expect(analyzePane).toBeVisible()
      await analyzePane.getByPlaceholder('匹配模式，如 *').fill(`${prefix}:*`)

      const [analyzeRes] = await Promise.all([
        page.waitForResponse(
          (res) => res.url().includes('/api/v1/keys/analyze') && res.request().method() === 'POST'
        ),
        analyzePane.getByRole('button', { name: /运行诊断/ }).click()
      ])
      expect(analyzeRes.status()).toBe(200)
      const analysisBody = (await analyzeRes.json()).data as {
        scannedKeys: number
        bigKeys: Array<{ key: string; type: string; sizeUnits: number }>
      }
      expect(analysisBody.scannedKeys).toBe(seededKeys.length)
      expect(analysisBody.bigKeys[0]?.key).toBe(bigKey)

      await expect(analyzePane).toContainText('本次采样键数')
      await expect(analyzePane).toContainText('永久未设 TTL')
      await expect(analyzePane).toContainText('Top 命名空间前缀分布')
      await expect(analyzePane).toContainText('TTL 过期健康度分桶')
      await expect(analyzePane).toContainText('STRING:')
      await expect(analyzePane).toContainText('HASH:')

      const bigKeyRow = analyzePane.locator('.el-table__row', { hasText: bigKey }).first()
      await expect(bigKeyRow).toBeVisible()
      await bigKeyRow.getByRole('button', { name: '查看详情' }).click()
      const drawer = page.locator('.el-drawer')
      await expect(drawer).toBeVisible()
      await expect(drawer).toContainText(bigKey, { timeout: 10_000 })
      await page.keyboard.press('Escape')

      // 6. 审计日志 -> 按 Request ID 精确筛选，验证全链路追踪闭环
      await page.locator('.sidebar').getByText('审计日志').click()
      await expect(page).toHaveURL(/#\/audit/)
      await expect(page.locator('.el-table__header')).toContainText('Request ID')

      await page.getByPlaceholder('Request ID').fill(traceId)
      const [auditRes] = await Promise.all([
        page.waitForResponse(
          (res) => res.url().includes('/api/v1/audit') && res.url().includes(encodeURIComponent(traceId))
        ),
        page.getByRole('button', { name: '查询', exact: true }).click()
      ])
      expect(auditRes.status()).toBe(200)
      await expect(page.locator('.el-table__body')).toContainText('LOGIN', { timeout: 10_000 })
      await expect(page.locator('.el-table__body')).toContainText(traceId.slice(0, 14))
    } finally {
      try {
        if (createdBackupId !== undefined) {
          const listRes = await request.get(`${baseURL}/api/v1/backups?instanceId=${instanceId}`, {
            headers: authHeaders
          })
          const allRecords = ((await listRes.json()).data ?? []) as Array<{
            id: number
            preRestoreBackupId?: number
          }>
          const targetRec = allRecords.find((r) => r.id === createdBackupId)
          if (targetRec?.preRestoreBackupId) {
            await request.delete(`${baseURL}/api/v1/backups/${targetRec.preRestoreBackupId}`, {
              headers: authHeaders
            })
          }
          await request.delete(`${baseURL}/api/v1/backups/${createdBackupId}`, { headers: authHeaders })
        }
        for (const key of seededKeys) {
          await request.post(`${baseURL}/api/v1/terminal/execute`, {
            headers: authHeaders,
            data: { instanceId, command: `DEL ${key}` }
          })
        }
      } catch {
        // 清理阶段忽略异常，避免遮蔽真实断言错误
      }
    }
  })
})
