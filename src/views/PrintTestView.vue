<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import PrimaryButton from '@/components/PrimaryButton.vue'
import { Printer } from '@capgo/capacitor-printer'

const router = useRouter()
const goBack = () => router.push('/')

// ===== 1. 环境检测 =====
interface EnvInfo {
  userAgent: string
  isCapacitor: boolean
  platform: string
  printerPluginVer: string
  printerPluginReady: boolean
}
const envInfo = ref<EnvInfo>({
  userAgent: '',
  isCapacitor: false,
  platform: 'unknown',
  printerPluginVer: '检测中...',
  printerPluginReady: false,
})

onMounted(async () => {
  const cap = (window as unknown as { Capacitor?: { getPlatform?: () => string } })
    .Capacitor
  envInfo.value = {
    userAgent: navigator.userAgent,
    isCapacitor: !!cap,
    platform:
      cap?.getPlatform?.() ??
      (navigator.userAgent.includes('Android')
        ? 'android-web'
        : navigator.userAgent.includes('iPhone')
          ? 'ios-web'
          : 'desktop-web'),
    printerPluginVer: '检测中...',
    printerPluginReady: false,
  }
  // 检测插件是否就绪
  try {
    const { version } = await Printer.getPluginVersion()
    envInfo.value.printerPluginVer = version
    envInfo.value.printerPluginReady = true
  } catch {
    envInfo.value.printerPluginVer = '未就绪 (需重新打包)'
    envInfo.value.printerPluginReady = false
  }
})

// ===== 2. 打印状态日志 (实时追加) =====
type LogLevel = 'info' | 'success' | 'error' | 'warn'
interface LogEntry {
  time: string
  level: LogLevel
  msg: string
}
const logs = ref<LogEntry[]>([])
const appendLog = (level: LogLevel, msg: string) => {
  const time = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  logs.value.unshift({ time, level, msg })
  // 最多保留 20 条
  if (logs.value.length > 20) logs.value.pop()
}
const clearLogs = () => (logs.value = [])

// ===== 3. 两种打印方式 =====

// 方案 B: 动态创建一个 iframe 打印指定 DOM (避免主页面被冻结)
const testIframePrint = () => {
  appendLog('info', '创建 iframe 并打印 ...')
  try {
    const html = buildReportHtml()
    const iframe = document.createElement('iframe')
    // 关键: iframe 不可设为 display:none, 否则无法打印
    iframe.style.position = 'fixed'
    iframe.style.left = '-10000px'
    iframe.style.top = '0'
    iframe.style.width = '794px' // 约 A4 宽度 @96dpi
    iframe.style.height = '1123px' // 约 A4 高度
    iframe.style.border = '0'
    iframe.setAttribute('aria-hidden', 'true')
    iframe.setAttribute('title', 'print-frame')
    document.body.appendChild(iframe)
    const doc = iframe.contentDocument
    if (!doc) {
      appendLog('error', 'iframe.contentDocument 为空 (跨域?)')
      return
    }
    doc.open()
    doc.write(html)
    doc.close()
    iframe.onload = () => {
      try {
        iframe.contentWindow?.addEventListener('afterprint', () => {
          appendLog('success', 'afterprint 事件触发 - 打印对话框已关闭')
        })
        iframe.contentWindow?.addEventListener('beforeprint', () => {
          appendLog('success', 'beforeprint 事件触发 - 系统打印对话框已弹出')
        })
        iframe.contentWindow?.focus()
        iframe.contentWindow?.print()
        appendLog('info', 'iframe.print() 已调用, 等待系统对话框...')
        // 兜底: 2 秒后无论是否触发都给出提示
        setTimeout(() => {
          appendLog(
            'warn',
            '若 2 秒内未看到系统对话框, 此环境可能不支持 iframe 内打印, 请改用方案 A-1',
          )
        }, 2000)
        // 5 秒后清理 iframe
        setTimeout(() => {
          if (document.body.contains(iframe)) {
            document.body.removeChild(iframe)
            appendLog('info', 'iframe 已清理')
          }
        }, 5000)
      } catch (e: unknown) {
        appendLog('error', `iframe.print 失败: ${(e as Error)?.message ?? String(e)}`)
      }
    }
  } catch (e: unknown) {
    appendLog('error', `iframe 流程异常: ${(e as Error)?.message ?? String(e)}`)
  }
}

// 方案 A-1: 通过打印插件打印 HTML
//  - 跨设备一致, 体验稳定
// 优点: 不依赖浏览器是否提供打印接口
const testPluginPrint = async () => {
  appendLog('info', '调用打印插件 ...')
  try {
    const html = buildReportHtml()
    const start = Date.now()
    // printHtml 返回 Promise, 关闭打印对话框后 resolve
    await Printer.printHtml({
      name: `AI预诊断报告-${new Date().toLocaleTimeString('zh-CN', { hour12: false })}`,
      html,
    })
    const cost = Date.now() - start
    appendLog('success', `打印对话框已关闭 (耗时 ${cost}ms) - 任务完成`)
  } catch (e: unknown) {
    const err = e as { message?: string; code?: string }
    if (err.message?.includes('canceled') || err.code === 'CANCELLED') {
      appendLog('warn', '用户取消了打印')
    } else {
      appendLog('error', `Printer.printHtml 失败: ${err.message ?? String(e)}`)
    }
  }
}

// ===== 4. 构造测试用 HTML 报告 =====
function buildReportHtml(): string {
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <title>打印测试报告</title>
  <style>
    @page { size: A4 portrait; margin: 15mm; }
    body { font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
           color: #111; margin: 0; padding: 20px;
           -webkit-print-color-adjust: exact; print-color-adjust: exact; }
    h1 { color: #ff9900; margin: 0 0 8px; font-size: 28px; }
    h2 { color: #333; border-bottom: 2px solid #ff9900;
         padding-bottom: 6px; margin-top: 24px; }
    .meta { color: #666; font-size: 12px; margin-bottom: 20px; }
    table { width: 100%; border-collapse: collapse; margin: 12px 0; }
    th, td { border: 1px solid #ddd; padding: 8px 12px; text-align: left; }
    th { background: #fff3e0; }
    .badge { display: inline-block; padding: 2px 10px; border-radius: 12px;
             font-size: 12px; color: #fff; }
    .ok { background: #22c55e; }
    .warn { background: #f59e0b; }
    .img-box { width: 200px; height: 200px; background: #ffe361;
               border-radius: 12px; display: flex; align-items: center;
               justify-content: center; font-size: 80px; margin: 12px 0; }
    .footer { margin-top: 30px; text-align: center; color: #999; font-size: 11px; }
  </style>
</head>
<body>
  <h1>儿童颜面发育 AI 预诊断报告</h1>
  <div class="meta">测试打印 · 生成时间: ${new Date().toLocaleString('zh-CN')}</div>

  <h2>一、基本信息</h2>
  <table>
    <tr><th>姓名</th><td>测试儿童</td><th>年龄</th><td>8 岁</td></tr>
    <tr><th>性别</th><td>女</td><th>就诊号</th><td>TEST-001</td></tr>
  </table>

  <h2>二、检测项目</h2>
  <table>
    <tr><th>项目</th><th>结果</th><th>状态</th></tr>
    <tr><td>面部对称性</td><td>98.5%</td><td><span class="badge ok">正常</span></td></tr>
    <tr><td>下颌发育</td><td>轻微异常</td><td><span class="badge warn">关注</span></td></tr>
    <tr><td>咬合关系</td><td>I 类</td><td><span class="badge ok">正常</span></td></tr>
  </table>

  <h2>三、面部分析图</h2>
  <div class="img-box">🦷</div>
  <p style="color:#666; font-size:12px;">
    (生产环境此处为内联的分析图)
  </p>

  <h2>四、专家建议</h2>
  <ol>
    <li>建议每半年复查一次</li>
    <li>注意咀嚼习惯, 避免单侧咀嚼</li>
    <li>如有明显变化, 及时就诊</li>
  </ol>

  <div class="footer">— 杭州儿童口腔医院 · AI 预诊断系统 —</div>
</body>
</html>`
}

// ===== 5. 设备信息 =====
const deviceInfo = ref({
  screen: '',
  dpr: 0,
  inner: '',
})
onMounted(() => {
  deviceInfo.value = {
    screen: `${window.screen.width}×${window.screen.height}`,
    dpr: window.devicePixelRatio,
    inner: `${window.innerWidth}×${window.innerHeight}`,
  }
})
</script>

<template>
  <div class="print-test-page">
    <div class="header">
      <button class="back-btn" @click="goBack">← 返回首页</button>
      <h1 class="title">打印功能验证</h1>
      <p class="subtitle">Print Test · 两种方案快速验证</p>
    </div>

    <!-- 环境检测面板 -->
    <div class="card">
      <h2 class="card-title">① 环境检测</h2>
      <div class="env-grid">
        <div class="env-item">
          <span class="env-label">运行平台</span>
          <span class="env-value" :class="envInfo.isCapacitor ? 'tag-ok' : 'tag-warn'">
            {{ envInfo.isCapacitor ? 'App' : 'Web' }} · {{ envInfo.platform }}
          </span>
        </div>
        <div class="env-item">
          <span class="env-label">打印插件 (方案 A-1)</span>
          <span
            class="env-value"
            :class="envInfo.printerPluginReady ? 'tag-ok' : 'tag-error'"
          >
            {{ envInfo.printerPluginReady ? '✓ v' + envInfo.printerPluginVer : '✗ ' + envInfo.printerPluginVer }}
          </span>
        </div>
        <div class="env-item">
          <span class="env-label">屏幕分辨率</span>
          <span class="env-value">{{ deviceInfo.screen }} (DPR {{ deviceInfo.dpr }})</span>
        </div>
        <div class="env-item">
          <span class="env-label">视口尺寸</span>
          <span class="env-value">{{ deviceInfo.inner }}</span>
        </div>
        <div class="env-item env-item-full">
          <span class="env-label">User Agent</span>
          <span class="env-value env-ua">{{ envInfo.userAgent }}</span>
        </div>
      </div>
    </div>

    <!-- 打印方案按钮 -->
    <div class="card">
      <h2 class="card-title">② 打印方案</h2>
      <div class="action-list">
        <div class="action-item">
          <div class="action-info">
            <h3>方案 B · iframe 隔离打印</h3>
            <p>动态创建隐藏 iframe · 注入报告 HTML 后打印 · 避免主页面被冻结</p>
          </div>
          <PrimaryButton text="执行 B" color="#fff" @click="testIframePrint" />
        </div>

        <div class="action-item">
          <div class="action-info">
            <h3>方案 A-1 · 打印插件</h3>
            <p>调用打印插件打印 HTML · 不依赖浏览器</p>
          </div>
          <PrimaryButton text="执行 A-1" color="#fff" @click="testPluginPrint" />
        </div>
      </div>
    </div>

    <!-- 预期行为说明 -->
    <div class="card">
      <h2 class="card-title">⑤ 预期行为与诊断</h2>
      <div class="expect-list">
        <div class="expect-item">
          <div class="expect-tag tag-info">方案 B</div>
          <div class="expect-body">
            <p class="expect-title">预期: 屏幕底部弹出系统打印对话框, 预览内容为右侧"③ 报告样本"完全一致</p>
            <p class="expect-desc">
              <strong>成功信号</strong>: 日志出现 "beforeprint 事件触发" + "afterprint 事件触发"<br />
              <strong>可见到</strong>: 打印对话框 → 预览区显示 A4 报告 (含表格 / 徽章 / 黄色图块 / 专家建议)
            </p>
            <p class="expect-fail">
              <strong>已知坑</strong>: 某些环境不会执行 iframe 内的 print(), 静默失败 → 改用方案 A-1<br />
              <strong>关键修复</strong>: iframe 不可 display:none / 0 尺寸, 必须 off-screen 保持可渲染 (本页面已用 left:-10000px)
            </p>
          </div>
        </div>

        <div class="expect-item">
          <div class="expect-tag tag-info">方案 A-1</div>
          <div class="expect-body">
            <p class="expect-title">预期: 弹出系统打印对话框, 内容与"③ 报告样本"一致</p>
            <p class="expect-desc">
              <strong>成功信号</strong>: 日志 "打印对话框已关闭 (耗时 Xms) - 任务完成"<br />
              <strong>可见到</strong>: 系统底部弹出打印对话框<br />
              <strong>核心优势</strong>: 不依赖浏览器是否提供打印接口, 跨设备一致
            </p>
            <p class="expect-fail">
              <strong>若提示"未就绪"</strong>: 需重新打包 App 后再试<br />
              <strong>若提示"User cancelled"</strong>: 正常, 用户关闭了系统打印对话框
            </p>
          </div>
        </div>

        <div class="expect-item expect-summary">
          <div class="expect-tag tag-success">结论</div>
          <div class="expect-body">
            <p class="expect-title">生产环境推荐: 方案 A-1 (插件) · 备选方案 B (iframe)</p>
            <p class="expect-desc">
              <strong>首选 A-1</strong>: 跨平台一致, 用户体验稳定<br />
              <strong>备选 B</strong>: 纯前端方案, 无需插件, 适合 Web 端或 A-1 不可用的场景<br />
              <strong>接入成本</strong>: A-1 已装包, B 零依赖直接调
            </p>
          </div>
        </div>
      </div>
    </div>

    <!-- 打印预览样本 -->
    <div class="card">
      <h2 class="card-title">③ 打印报告样本 (iframe 用)</h2>
      <p class="card-desc">下方是方案 B 会打印的真实 HTML 结构预览</p>
      <!-- <iframe class="preview-frame" :srcdoc="buildReportHtml()" sandbox="allow-same-origin" /> -->
    </div>

    <!-- 实时日志 -->
    <div class="card">
      <div class="log-header">
        <h2 class="card-title">④ 执行日志</h2>
        <button class="clear-btn" @click="clearLogs">清空</button>
      </div>
      <div v-if="logs.length === 0" class="log-empty">尚无日志, 请点击上方按钮测试</div>
      <div v-else class="log-list">
        <div v-for="(log, i) in logs" :key="i" class="log-item" :class="`log-${log.level}`">
          <span class="log-time">{{ log.time }}</span>
          <span class="log-level">{{ log.level.toUpperCase() }}</span>
          <span class="log-msg">{{ log.msg }}</span>
        </div>
      </div>
    </div>

    <div class="footer-hint">
      验证完成后请删除 <code>src/views/PrintTestView.vue</code>、
      <code>router/index.ts</code> 中的 <code>/print-test</code> 路由、
      以及 <code>WelcomeView.vue</code> 中的 <code>goToPrintTest</code> 调用
    </div>
  </div>
</template>

<style scoped lang="scss">
.print-test-page {
  height: 100vh;
  position: relative;

  @supports (height: 100dvh) {
    height: 100dvh;
  }

  overflow: auto;
  background: #f5f5f7;
  padding: 40px;
  font-family: 'Inter',
  -apple-system,
  BlinkMacSystemFont,
  'PingFang SC',
  sans-serif;
}

.header {
  margin-bottom: 32px;

  .back-btn {
    background: rgba(0, 0, 0, 0.05);
    border: none;
    padding: 10px 20px;
    border-radius: 12px;
    font-size: 24px;
    cursor: pointer;
    margin-bottom: 16px;

    &:active {
      background: rgba(0, 0, 0, 0.1);
    }
  }

  .title {
    font-size: 56px;
    font-weight: 700;
    color: #111;
    margin: 0 0 8px;
  }

  .subtitle {
    font-size: 24px;
    color: #666;
    margin: 0;
  }
}

.card {
  background: #fff;
  border-radius: 24px;
  padding: 32px;
  margin-bottom: 24px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);

  .card-title {
    font-size: 32px;
    font-weight: 700;
    color: #111;
    margin: 0 0 20px;
  }

  .card-desc {
    font-size: 22px;
    color: #666;
    margin: 0 0 16px;
  }
}

// 环境检测
.env-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.env-item {
  display: flex;
  flex-direction: column;
  gap: 6px;

  &.env-item-full {
    grid-column: 1 / -1;
  }

  .env-label {
    font-size: 22px;
    color: #888;
  }

  .env-value {
    font-size: 24px;
    color: #111;
    font-weight: 500;
  }

  .env-ua {
    font-size: 18px;
    word-break: break-all;
    color: #555;
    font-family: monospace;
  }
}

.tag-ok {
  display: inline-block;
  padding: 4px 14px;
  background: #dcfce7;
  color: #166534;
  border-radius: 20px;
  font-size: 22px;
  font-weight: 600;
  width: fit-content;
}

.tag-warn {
  display: inline-block;
  padding: 4px 14px;
  background: #fef3c7;
  color: #92400e;
  border-radius: 20px;
  font-size: 22px;
  font-weight: 600;
  width: fit-content;
}

.tag-error {
  display: inline-block;
  padding: 4px 14px;
  background: #fee2e2;
  color: #991b1b;
  border-radius: 20px;
  font-size: 22px;
  font-weight: 600;
  width: fit-content;
}

// 方案按钮
.action-list {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

// 预期行为
.expect-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.expect-item {
  display: flex;
  gap: 16px;
  padding: 20px;
  background: #fafafa;
  border-radius: 16px;
  border-left: 4px solid #ff9900;

  &.expect-summary {
    background: #f0fdf4;
    border-left-color: #22c55e;
  }
}

.expect-tag {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  height: fit-content;
  padding: 6px 14px;
  border-radius: 12px;
  font-size: 18px;
  font-weight: 700;
  color: #fff;

  &.tag-info {
    background: #ff9900;
  }

  &.tag-success {
    background: #22c55e;
  }
}

.expect-body {
  flex: 1;

  p {
    margin: 0 0 8px;
    font-size: 20px;
    line-height: 1.5;
    color: #333;

    &:last-child {
      margin-bottom: 0;
    }
  }

  .expect-title {
    font-weight: 700;
    font-size: 22px;
    color: #111;
  }

  .expect-desc {
    color: #444;

    strong {
      color: #ff9900;
    }
  }

  .expect-fail {
    color: #666;
    font-size: 18px;
    padding: 8px 12px;
    background: rgba(0, 0, 0, 0.03);
    border-radius: 8px;

    strong {
      color: #b45309;
    }
  }
}

.action-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 20px;
  background: #fafafa;
  border-radius: 16px;
  border: 2px solid transparent;
  transition: border-color 0.2s;

  &:not(.disabled):hover {
    border-color: #ff9900;
  }

  &.disabled {
    opacity: 0.6;
  }

  .action-info {
    flex: 1;

    h3 {
      font-size: 26px;
      font-weight: 600;
      color: #111;
      margin: 0 0 6px;
    }

    p {
      font-size: 20px;
      color: #666;
      margin: 0;
    }
  }
}

// 预览 iframe
.preview-frame {
  width: 100%;
  height: 600px;
  border: 1px solid #e5e5e5;
  border-radius: 12px;
  background: #fff;
}

// 日志
.log-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;

  .clear-btn {
    background: transparent;
    border: 1px solid #ddd;
    padding: 6px 16px;
    border-radius: 8px;
    font-size: 20px;
    cursor: pointer;

    &:active {
      background: #f5f5f5;
    }
  }
}

.log-empty {
  text-align: center;
  padding: 40px;
  color: #999;
  font-size: 22px;
  background: #fafafa;
  border-radius: 12px;
}

.log-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 400px;
  overflow-y: auto;
}

.log-item {
  display: grid;
  grid-template-columns: 80px 70px 1fr;
  gap: 12px;
  padding: 10px 16px;
  border-radius: 8px;
  font-size: 20px;
  font-family: 'SF Mono', Consolas, monospace;
  align-items: center;

  .log-time {
    color: #888;
  }

  .log-level {
    font-weight: 700;
    font-size: 16px;
  }

  .log-msg {
    color: #111;
  }

  &.log-info {
    background: #f0f9ff;

    .log-level {
      color: #0369a1;
    }
  }

  &.log-success {
    background: #f0fdf4;

    .log-level {
      color: #15803d;
    }
  }

  &.log-warn {
    background: #fffbeb;

    .log-level {
      color: #b45309;
    }
  }

  &.log-error {
    background: #fef2f2;

    .log-level {
      color: #b91c1c;
    }
  }
}

.footer-hint {
  margin-top: 24px;
  padding: 20px;
  background: #fffbeb;
  border: 1px dashed #f59e0b;
  border-radius: 12px;
  font-size: 20px;
  color: #78350f;
  line-height: 1.6;

  code {
    background: rgba(0, 0, 0, 0.05);
    padding: 2px 6px;
    border-radius: 4px;
    font-family: 'SF Mono', Consolas, monospace;
  }
}
</style>
