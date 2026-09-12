export interface DevToolsDialogSlot {
  /** 预览旋转角（0/90/180/270） */
  previewRotation: number
  /** 拍照旋转角（0/90/180/270） */
  captureRotation: number
  /** 是否在预览上额外水平镜像 */
  mirror: boolean
  /** 数字缩放倍数（[1.0, 4.0]） */
  zoom: number
}

export interface DevToolsDialogResult {
  /** 用户是否点击了"保存配置"。false 表示关闭 / 系统返回 / 退出应用。 */
  saved: boolean
  /** 当前生效的配置（无论是否保存，都返回最新值，便于前端同步状态）。 */
  slots: DevToolsDialogSlot[]
}

export interface DevToolsDialogPlugin {
  /**
   * 弹出开发者工具弹窗（native Android Dialog，WindowManager TYPE_APPLICATION_PANEL，
   * 永远位于所有 WebView / TextureView / SurfaceView 之上）。
   *
   * <p>返回的 Promise 在 dialog 关闭后 resolve：
   * <ul>
   *   <li>saved=true  → 用户点击了"保存配置"，slots 是新配置</li>
   *   <li>saved=false → 用户点了"关闭"或按了系统返回键</li>
   * </ul>
   * 用户点击"退出应用"时，resolve(saved=false) 之后会关闭 Activity（与首页退出路径一致）。
   *
   * <p>web 端不支持（开发工具不需要在浏览器上用），web 实现会抛 unavailable 错误。
   */
  show(): Promise<DevToolsDialogResult>
}
