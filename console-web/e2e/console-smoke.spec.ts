import { expect, test } from '@playwright/test'

test.describe('Console Core Smoke Flow', () => {
  test('overview, terminal, keyspace CRUD, slowlog view, checkpoint & backup lifecycle', async ({ page }) => {
    const key = `e2e:smoke:${Date.now()}`

    // 1. 登录并进入运行总览
    await page.goto('/#/login')
    await page.getByPlaceholder('admin').fill('admin')
    await page.getByPlaceholder('请输入密码').fill('admin123')
    await page.getByTestId('login-submit').click()
    await expect(page).toHaveURL(/#\/dashboard/)
    await expect(page.locator('.topbar')).toContainText('local-6379')

    // 2. 在线终端执行 PING / INFO 与 SET 构造测试键
    await page.locator('.sidebar').getByText('在线终端').click()
    await expect(page).toHaveURL(/#\/terminal/)
    const cmdInput = page.getByPlaceholder('输入命令后回车执行')
    await cmdInput.fill('PING')
    await cmdInput.press('Enter')
    await expect(page.locator('.term-body')).toContainText('PONG')

    await cmdInput.fill('INFO server')
    await cmdInput.press('Enter')
    await expect(page.locator('.term-body')).toContainText('tieringkv_version')

    await cmdInput.fill(`SET ${key} hello-e2e`)
    await cmdInput.press('Enter')
    await expect(page.locator('.term-body')).toContainText('OK')

    // 3. 键空间扫描、查看详情并删除
    await page.locator('.sidebar').getByText('键空间').click()
    await expect(page).toHaveURL(/#\/keyspace/)
    await page.getByPlaceholder('匹配模式，如 user:*').fill('e2e:smoke:*')
    await page.getByRole('button', { name: '扫描', exact: true }).click()
    const row = page.locator('.el-table__row', { hasText: key }).first()
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '查看' }).click()
    const valueEditor = page.locator('.el-drawer textarea')
    await expect(valueEditor).toHaveValue('hello-e2e')
    await valueEditor.fill('hello-e2e-updated')
    await page.getByRole('button', { name: '保存值' }).click()
    await expect(valueEditor).toHaveValue('hello-e2e-updated')
    await page.locator('.el-drawer__close-btn').click()

    // 删除该临时键后再写入持久化键用于备份恢复闭环
    await row.getByRole('button', { name: '删除', exact: true }).click()
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.locator('.el-table__body')).not.toContainText(key)

    // 先写一条持久化数据便于验证备份恢复
    await page.locator('.sidebar').getByText('在线终端').click()
    await cmdInput.fill(`SET ${key}:persist v1`)
    await cmdInput.press('Enter')
    await expect(page.locator('.term-body')).toContainText('OK')

    // 4. 慢查询视图可正常加载刷新
    await page.locator('.sidebar').getByText('慢查询日志').click()
    await expect(page).toHaveURL(/#\/slowlog/)
    await expect(page.getByRole('heading', { name: '慢查询日志' })).toBeVisible()
    await page.getByRole('button', { name: '刷新', exact: true }).click()

    // 5. 备份与物理 Checkpoint 全流程
    await page.locator('.sidebar').getByText('备份恢复').click()
    await expect(page).toHaveURL(/#\/backups/)
    await page.getByRole('button', { name: '触发物理 Checkpoint' }).click()
    await expect(page.locator('.checkpoint-strip')).not.toContainText('尚未执行物理 Checkpoint', { timeout: 10_000 })

    await page.getByRole('button', { name: '立即备份' }).click()
    const successRow = page.locator('.el-table__row', { hasText: 'SUCCESS' }).first()
    await expect(successRow).toBeVisible({ timeout: 20_000 })

    // 删掉数据键后再点恢复，再删备份与测试键保持零污染
    await page.locator('.sidebar').getByText('在线终端').click()
    await cmdInput.fill(`DEL ${key}:persist`)
    await cmdInput.press('Enter')
    await page.locator('.sidebar').getByText('备份恢复').click()
    const backupRow = page.locator('.el-table__row', { hasText: 'SUCCESS' }).first()
    await backupRow.getByRole('button', { name: '恢复', exact: true }).click()
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.getByText(/已恢复 \d+ 个键/)).toBeVisible({ timeout: 15_000 })

    // 清理测试备份与测试键
    await backupRow.getByRole('button', { name: '删除', exact: true }).click()
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.getByText('已删除')).toBeVisible()

    await page.locator('.sidebar').getByText('在线终端').click()
    await cmdInput.fill(`DEL ${key}:persist`)
    await cmdInput.press('Enter')
  })
})
