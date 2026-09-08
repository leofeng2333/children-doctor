export interface QuitAppPlugin {
  /**
   * 关闭当前 Activity，结束 App。
   *
   * Android: 走 {@code QuitAppPlugin.exitApp} -> {@code getActivity().finish()}
   * Web:     尝试 {@code window.close()}，浏览器通常会忽略（用户已开页面非脚本可关闭）。
   *          这是已知行为，开发期调用不会抛错。
   */
  exitApp(): Promise<void>
}
