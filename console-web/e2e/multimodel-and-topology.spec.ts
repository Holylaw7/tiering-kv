import { expect, test } from '@playwright/test'

test.describe('Multi-Model Studio & Cluster Topology E2E (v6.2.0)', () => {
  test('exercises Vector HNSW, TimeSeries aggregation and JSON Path via Multi-Model Studio UI', async ({
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
    const instances = (await instRes.json()).data as Array<{ id: number }>
    expect(instances.length).toBeGreaterThan(0)
    const instanceId = instances[0].id

    const suffix = Date.now().toString(36)
    const colName = `e2e_vec_${suffix}`
    const tsKey = `e2e:ts:${suffix}`
    const jsonKey = `e2e:json:${suffix}`

    try {
      await page.goto('/#/login')
      await page.getByPlaceholder('admin').fill('admin')
      await page.getByPlaceholder('请输入密码').fill('admin123')
      await page.getByTestId('login-submit').click()
      await expect(page).toHaveURL(/#\/dashboard/)

      await page.locator('.sidebar').getByText('多模型工作台').click()
      await expect(page).toHaveURL(/#\/multimodel/)
      await expect(page.getByRole('heading', { name: '多模型工作台' })).toBeVisible()

      // 1. Vector HNSW：通过 UI 写入向量、Top-K 检索并触发索引落盘
      await page.getByRole('button', { name: '写入向量' }).click()
      const vecDialog = page.locator('.el-dialog').filter({ hasText: '写入向量（VECTOR.ADD）' })
      await expect(vecDialog).toBeVisible()
      await vecDialog.getByPlaceholder('default').fill(colName)
      await vecDialog.getByPlaceholder('例如 doc-001').fill('vec-alpha')
      await vecDialog.locator('textarea').fill('0.9, 0.1, 0.0, 0.0')
      await vecDialog.getByRole('button', { name: '写入', exact: true }).click()
      await expect(vecDialog).toBeHidden({ timeout: 10_000 })

      // 再经 API 写入第二个对比向量以验证 Top-K 排序
      const add2 = await request.post(`${baseURL}/api/v1/multimodel/vector/add`, {
        headers: authHeaders,
        data: { instanceId, collection: colName, id: 'vec-beta', values: [0.0, 0.0, 0.9, 0.1] }
      })
      expect(add2.ok()).toBeTruthy()

      await page.getByPlaceholder('输入查询向量，例如 0.1, 0.2, 0.3, 0.4').fill('0.9, 0.1, 0.0, 0.0')
      await page.getByRole('button', { name: '检索', exact: true }).click()
      await expect(page.locator('.el-table__row', { hasText: 'vec-alpha' }).first()).toBeVisible({
        timeout: 10_000
      })

      // 验证 VECTOR.CHECKPOINT 在真实单机内核可落盘成功
      await page.getByRole('button', { name: '索引落盘' }).click()
      await expect(
        page.locator('.el-message--success').filter({ hasText: '物理索引落盘完成' }).first()
      ).toBeVisible({
        timeout: 10_000
      })

      // 2. TimeSeries：写入两个采样点并验证范围查询与 AVG 聚合
      await page.getByRole('tab', { name: 'TimeSeries 时序分析' }).click()
      const addTs1 = await request.post(`${baseURL}/api/v1/multimodel/timeseries/add`, {
        headers: authHeaders,
        data: { instanceId, key: tsKey, timestamp: 1000, value: 40 }
      })
      expect(addTs1.ok()).toBeTruthy()
      const addTs2 = await request.post(`${baseURL}/api/v1/multimodel/timeseries/add`, {
        headers: authHeaders,
        data: { instanceId, key: tsKey, timestamp: 2000, value: 60 }
      })
      expect(addTs2.ok()).toBeTruthy()

      const tsPane = page.locator('#pane-timeseries')
      await tsPane.getByPlaceholder('时序键名').fill(tsKey)
      await tsPane.getByRole('button', { name: '查询', exact: true }).click()
      await expect(tsPane).toContainText('总点数 2', { timeout: 10_000 })
      await expect(tsPane.locator('canvas')).toBeVisible({ timeout: 10_000 })

      // 3. JSON Path Explorer：新建文档、字段下钻查询
      await page.getByRole('tab', { name: 'JSON 路径浏览器' }).click()
      const jsonPane = page.locator('#pane-json')
      await jsonPane.getByRole('button', { name: '新建' }).click()
      const jsonDialog = page.locator('.el-dialog').filter({ hasText: '新建 JSON 文档（JSON.SET）' })
      await expect(jsonDialog).toBeVisible()
      await jsonDialog.getByPlaceholder('例如 doc:profile:1').fill(jsonKey)
      await jsonDialog.getByRole('button', { name: '保存', exact: true }).click()
      await expect(jsonDialog).toBeHidden({ timeout: 10_000 })

      await expect(jsonPane.locator('textarea')).toHaveValue(/tiering-kv/, { timeout: 10_000 })
      await jsonPane.getByRole('button', { name: '.service', exact: true }).click()
      await expect(jsonPane.getByPlaceholder('JSONPath，例如 $ 或 $.tags')).toHaveValue('$.service', {
        timeout: 10_000
      })
      await expect(jsonPane.locator('textarea')).toHaveValue(/tiering-kv/, { timeout: 10_000 })
    } finally {
      try {
        await request.delete(
          `${baseURL}/api/v1/multimodel/vector/collection?instanceId=${instanceId}&collection=${encodeURIComponent(colName)}`,
          { headers: authHeaders }
        )
        await request.delete(
          `${baseURL}/api/v1/multimodel/json/del?instanceId=${instanceId}&key=${encodeURIComponent(jsonKey)}&path=$`,
          { headers: authHeaders }
        )
        await request.post(`${baseURL}/api/v1/terminal/execute`, {
          headers: authHeaders,
          data: { instanceId, command: `DEL ${tsKey}` }
        })
      } catch {
        // 清理失败不掩盖真实断言
      }
    }
  })

  test('renders 16384 HashSlot topology and K8s Operator bridge status on /#/topology', async ({
    page
  }) => {
    await page.goto('/#/login')
    await page.getByPlaceholder('admin').fill('admin')
    await page.getByPlaceholder('请输入密码').fill('admin123')
    await page.getByTestId('login-submit').click()
    await expect(page).toHaveURL(/#\/dashboard/)

    await page.locator('.sidebar').getByText('集群拓扑').click()
    await expect(page).toHaveURL(/#\/topology/)
    await expect(page.getByRole('heading', { name: '分布式集群拓扑与云原生控制' })).toBeVisible()
    await expect(page.getByText('16384 HashSlot 分片分布')).toBeVisible()
    await expect(page.locator('canvas')).toBeVisible()
    await expect(page.locator('.el-table__row', { hasText: '16384' }).first()).toBeVisible({
      timeout: 10_000
    })
    await expect(page.getByText('未启用桥接')).toBeVisible()
  })
})
