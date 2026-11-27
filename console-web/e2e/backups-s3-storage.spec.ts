import { gunzipSync } from 'node:zlib'
import { expect, test } from '@playwright/test'

test.describe('S3 / RustFS Object Storage Backup E2E', () => {
  test.skip(
    process.env.E2E_S3_MODE !== 'true',
    'Requires S3/RustFS profile (CONSOLE_BACKUP_STORAGE=s3, E2E_S3_MODE=true)'
  )

  test('verifies s3 storage backend, multi-type backup upload, gzip artifact integrity, async restore, purge and object deletion', async ({
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

    // 1. 确认控制面真实运行在 S3 对象存储模式（而非本地磁盘）
    const capRes = await request.get(`${baseURL}/api/v1/backups/capacity`, { headers: authHeaders })
    expect(capRes.status()).toBe(200)
    const capacity = (await capRes.json()).data as { storageKey: string }
    expect(capacity.storageKey).toMatch(/^s3:\/\/tiering-backups/)

    const instRes = await request.get(`${baseURL}/api/v1/instances`, { headers: authHeaders })
    const instances = (await instRes.json()).data as Array<{ id: number }>
    expect(instances.length).toBeGreaterThan(0)
    const instanceId = instances[0].id

    const prefix = `s3e2e:${Date.now().toString(36)}`
    const stringCount = 200
    const hashKey = `${prefix}:h`
    const listKey = `${prefix}:l`
    const setKey = `${prefix}:s`
    const zsetKey = `${prefix}:z`
    let createdBackupId: number | undefined
    let backupDeleted = false

    const execCmd = async (command: string) => {
      const res = await request.post(`${baseURL}/api/v1/terminal/execute`, {
        headers: authHeaders,
        data: { instanceId, command }
      })
      expect(res.ok()).toBeTruthy()
      return (await res.json()).data as { replyType: string; payload?: unknown; error: boolean }
    }

    try {
      // 2. 写入 204 个多类型测试键（每批 10 键，严格控制在 TerminalRequest 512 字符上限内）
      for (let start = 0; start < stringCount; start += 10) {
        const pairs: string[] = []
        for (let i = start; i < Math.min(start + 10, stringCount); i++) {
          pairs.push(`${prefix}:k${i}`, `v${i}`)
        }
        const mset = await execCmd(`MSET ${pairs.join(' ')}`)
        expect(mset.error).toBe(false)
      }
      expect((await execCmd(`HSET ${hashKey} f1 alpha f2 beta`)).error).toBe(false)
      expect((await execCmd(`RPUSH ${listKey} item-1 item-2 item-3`)).error).toBe(false)
      expect((await execCmd(`SADD ${setKey} tag-a tag-b`)).error).toBe(false)
      expect((await execCmd(`ZADD ${zsetKey} 10 score-a 25 score-b`)).error).toBe(false)

      // 3. 登录 UI 进入备份页，触发异步导出至真实 S3/RustFS
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

      let createdFileName = ''
      let createdSizeBytes = 0
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
              sizeBytes: number
            }>
            const created = records.find((r) => !existingIds.has(r.id))
            if (created && created.status === 'SUCCESS') {
              createdBackupId = created.id
              createdFileName = created.fileName
              createdSizeBytes = created.sizeBytes
              return created.keyCount
            }
            return -1
          },
          { timeout: 30_000, intervals: [500, 1000] }
        )
        .toBeGreaterThanOrEqual(stringCount + 4)

      expect(createdSizeBytes).toBeGreaterThan(0)
      expect(createdFileName).toMatch(/\.jsonl\.gz$/)

      // 4. 从 S3 流式下载 Gzip 备份并本地解压校验 5 种数据类型完整落盘
      const downloadRes = await request.get(`${baseURL}/api/v1/backups/${createdBackupId}/download`, {
        headers: authHeaders
      })
      expect(downloadRes.status()).toBe(200)
      const rawGz = await downloadRes.body()
      expect(rawGz.byteLength).toBe(createdSizeBytes)

      const jsonlLines = gunzipSync(rawGz)
        .toString('utf8')
        .trim()
        .split('\n')
        .map((line) => JSON.parse(line) as { key: string; type: string })
      const typeByKey = new Map(jsonlLines.map((entry) => [entry.key, entry.type]))
      expect(typeByKey.get(`${prefix}:k0`)).toBe('string')
      expect(typeByKey.get(`${prefix}:k199`)).toBe('string')
      expect(typeByKey.get(hashKey)).toBe('hash')
      expect(typeByKey.get(listKey)).toBe('list')
      expect(typeByKey.get(setKey)).toBe('set')
      expect(typeByKey.get(zsetKey)).toBe('zset')

      // 5. 删除全部 5 种数据类型的代表键，确认消失后在 UI 触发异步恢复
      await execCmd(`DEL ${prefix}:k0 ${prefix}:k199 ${hashKey} ${listKey} ${setKey} ${zsetKey}`)
      expect((await execCmd(`GET ${prefix}:k0`)).payload ?? null).toBeNull()
      expect((await execCmd(`HGET ${hashKey} f1`)).payload ?? null).toBeNull()

      await page.getByRole('button', { name: '刷新列表' }).click()
      const targetRow = page.locator('.el-table__row', { hasText: createdFileName }).first()
      await expect(targetRow).toBeVisible()
      await targetRow.getByRole('button', { name: '校验', exact: true }).click()
      await expect(targetRow).toContainText('已校验', { timeout: 15_000 })

      await targetRow.getByRole('button', { name: '恢复', exact: true }).click()
      const wizard = page.getByRole('dialog', { name: /安全恢复向导/ })
      await expect(wizard).toBeVisible()
      await expect(wizard).toContainText('归档总键数', { timeout: 15_000 })
      const confirmBtn = wizard.getByRole('button', { name: '确认执行恢复' })
      await expect(confirmBtn).toBeEnabled({ timeout: 15_000 })
      await confirmBtn.click()
      await expect(targetRow).toContainText(/已恢复 \d+ 键/, { timeout: 30_000 })

      // 6. 验证全部 5 种数据类型均从 S3 对象精确复原
      expect((await execCmd(`GET ${prefix}:k0`)).payload).toBe('v0')
      expect((await execCmd(`GET ${prefix}:k199`)).payload).toBe('v199')
      expect((await execCmd(`HGET ${hashKey} f2`)).payload).toBe('beta')
      expect((await execCmd(`LRANGE ${listKey} 0 -1`)).payload).toEqual(['item-1', 'item-2', 'item-3'])
      expect((await execCmd(`SISMEMBER ${setKey} tag-b`)).payload).toBe(1)
      expect(String((await execCmd(`ZSCORE ${zsetKey} score-b`)).payload)).toContain('25')

      // 7. 触发一次 S3 保留/孤儿清理巡检（校验真实 listObjectsV2 接口畅通）
      const purgeRes = await request.post(`${baseURL}/api/v1/backups/purge`, { headers: authHeaders })
      expect(purgeRes.status()).toBe(200)

      // 8. 在 UI 上删除备份记录，校验 S3 对象同步清理且再次下载返回 404
      await targetRow.getByRole('button', { name: '删除', exact: true }).click()
      await page.locator('.el-message-box__btns .el-button--primary').click()
      await expect(page.locator('.el-table__row', { hasText: createdFileName })).toHaveCount(0)
      backupDeleted = true

      const afterDeleteDownload = await request.get(`${baseURL}/api/v1/backups/${createdBackupId}/download`, {
        headers: authHeaders
      })
      expect(afterDeleteDownload.status()).toBe(404)
    } finally {
      try {
        if (createdBackupId !== undefined && !backupDeleted) {
          await request.delete(`${baseURL}/api/v1/backups/${createdBackupId}`, { headers: authHeaders })
        }
        const delBatch: string[] = [hashKey, listKey, setKey, zsetKey]
        for (let i = 0; i < stringCount; i++) {
          delBatch.push(`${prefix}:k${i}`)
        }
        for (let start = 0; start < delBatch.length; start += 10) {
          await request.post(`${baseURL}/api/v1/terminal/execute`, {
            headers: authHeaders,
            data: { instanceId, command: `DEL ${delBatch.slice(start, start + 10).join(' ')}` }
          })
        }
      } catch {
        // 测试超时退出时忽略清理异常
      }
    }
  })
})
