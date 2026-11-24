import { expect, test } from '@playwright/test'

test.describe('Alerts Rules & Webhook Channels E2E', () => {
  test('creates, edits and deletes alert rules and HMAC webhook channels cleanly', async ({ page }) => {
    const suffix = Date.now()
    const ruleName = `e2e-rule-${suffix}`
    const editedRuleName = `e2e-rule-edit-${suffix}`
    const channelName = `e2e-hook-${suffix}`
    const editedChannelName = `e2e-hook-v2-${suffix}`
    const rawSecret = `hmac-secret-${suffix}`

    await page.goto('/#/login')
    await page.getByPlaceholder('admin').fill('admin')
    await page.getByPlaceholder('请输入密码').fill('admin123')
    await page.getByTestId('login-submit').click()
    await expect(page).toHaveURL(/#\/dashboard/)

    await page.locator('.sidebar').getByText('告警中心').click()
    await expect(page).toHaveURL(/#\/alerts/)
    await expect(page.getByRole('heading', { name: '告警中心' })).toBeVisible()

    // 1. 告警规则：新建 -> 校验 -> 编辑 -> 删除
    await page.getByRole('button', { name: '新建规则' }).click()
    const ruleDialog = page.locator('.el-dialog').filter({ hasText: '新建规则' })
    await expect(ruleDialog).toBeVisible()
    await ruleDialog.locator('.el-form-item', { hasText: '规则名称' }).locator('input').fill(ruleName)
    await ruleDialog.getByRole('button', { name: '保存' }).click()
    await expect(ruleDialog).toBeHidden()

    await page.getByRole('tab', { name: '告警规则' }).click()
    const ruleRow = page.locator('.el-table__row', { hasText: ruleName }).first()
    await expect(ruleRow).toBeVisible()

    await ruleRow.getByRole('button', { name: '编辑' }).click()
    const editRuleDialog = page.locator('.el-dialog').filter({ hasText: '编辑规则' })
    await expect(editRuleDialog).toBeVisible()
    await editRuleDialog.locator('.el-form-item', { hasText: '规则名称' }).locator('input').fill(editedRuleName)
    await editRuleDialog.getByRole('button', { name: '保存' }).click()
    await expect(editRuleDialog).toBeHidden()

    const editedRuleRow = page.locator('.el-table__row', { hasText: editedRuleName }).first()
    await expect(editedRuleRow).toBeVisible()
    await editedRuleRow.getByRole('button', { name: '删除' }).click()
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.locator('.el-table__row', { hasText: editedRuleName })).toHaveCount(0)

    // 2. Webhook 通知渠道（O3）：新建（带签名密钥）-> 脱敏校验 -> 编辑留空保留密钥 -> 删除
    await page.getByRole('button', { name: '新建通知渠道' }).click()
    const chanDialog = page.locator('.el-dialog').filter({ hasText: '新建通知渠道' })
    await expect(chanDialog).toBeVisible()
    await chanDialog.locator('.el-form-item', { hasText: '渠道名称' }).locator('input').fill(channelName)
    await chanDialog
      .locator('.el-form-item', { hasText: 'Webhook URL' })
      .locator('input')
      .fill('http://127.0.0.1:19999/e2e-webhook')
    await chanDialog.locator('.el-form-item', { hasText: '签名密钥' }).locator('input').fill(rawSecret)
    await chanDialog.getByRole('button', { name: '保存' }).click()
    await expect(chanDialog).toBeHidden()

    await page.getByRole('tab', { name: /通知渠道/ }).click()
    const chanRow = page.locator('.el-table__row', { hasText: channelName }).first()
    await expect(chanRow).toBeVisible()
    await expect(chanRow).toContainText('HMAC')
    await expect(page.locator('body')).not.toContainText(rawSecret)

    await chanRow.getByRole('button', { name: '编辑' }).click()
    const editChanDialog = page.locator('.el-dialog').filter({ hasText: '编辑通知渠道' })
    await expect(editChanDialog).toBeVisible()
    await editChanDialog.locator('.el-form-item', { hasText: '渠道名称' }).locator('input').fill(editedChannelName)
    await editChanDialog.getByRole('button', { name: '保存' }).click()
    await expect(editChanDialog).toBeHidden()

    const editedChanRow = page.locator('.el-table__row', { hasText: editedChannelName }).first()
    await expect(editedChanRow).toBeVisible()
    await expect(editedChanRow).toContainText('HMAC')
    await editedChanRow.getByRole('button', { name: '删除' }).click()
    await page.locator('.el-message-box__btns .el-button--primary').click()
    await expect(page.locator('.el-table__row', { hasText: editedChannelName })).toHaveCount(0)

    // 3. 切回告警事件页签可正常刷新渲染
    await page.getByRole('tab', { name: '告警事件' }).click()
    await page.getByRole('button', { name: '刷新', exact: true }).click()
    await expect(page.locator('.el-tabs__content')).toBeVisible()
  })
})
