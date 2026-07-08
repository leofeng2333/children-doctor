import { DualCamera } from '@/plugins/dual-camera/src/index'
import type { DualCameraPhoto } from '@/plugins/dual-camera/src/definitions'
import { post } from './request-native'

export interface SessionInfo {
  sessionId: string
  expiresAt: string
}

export const uploadPhotos = async (
  photos: DualCameraPhoto[],
): Promise<{ frontUrls: string[]; sideUrls: string[] }> => {
  if (photos.length === 0) {
    throw new Error('没有可上传的照片')
  }

  console.log('[Upload] 开始上传照片, 数量:', photos.length)

  const front = photos[0] ? [photos[0].frontCameraPath] : []
  const side = photos[0] ? [photos[0].backCameraPath] : []
  const frontSmile = photos[1] ? [photos[1].frontCameraPath] : []
  const sideSmile = photos[1] ? [photos[1].backCameraPath] : []

  console.log('[Upload] frontSmileFiles:', front)
  console.log('[Upload] sideSmileFiles:', side)
  console.log('[Upload] frontClosedFiles:', frontSmile)
  console.log('[Upload] sideClosedFiles:', sideSmile)

  const sessionId = (await createSession()).sessionId

  const uploadOptions = {
    uploadUrl: 'https://aiqc.hzyk.com.cn/promotion/api/photo/upload-batch',
    files: {
      front,
      side,
      frontSmile,
      sideSmile,
    },
    extraData: { sessionId },
  }

  console.log('[Upload] ========== 上传参数完整快照 ==========')
  console.log('[Upload] uploadUrl:', uploadOptions.uploadUrl)
  console.log('[Upload] sessionId:', sessionId)
  console.log('[Upload] files:')
  console.log('  frontSmileFiles  =', JSON.stringify(front))
  console.log('  sideSmileFiles   =', JSON.stringify(side))
  console.log('  frontClosedFiles =', JSON.stringify(frontSmile))
  console.log('  sideClosedFiles  =', JSON.stringify(sideSmile))
  console.log('[Upload] 完整 JSON:')
  console.log(JSON.stringify(uploadOptions, null, 2))
  console.log('[Upload] ========================================')

  const result = await DualCamera.uploadPhotos(uploadOptions)

  console.log('[Upload] 上传完成, 响应长度:', result.response?.length)
  console.log('[Upload] 响应内容:', result.response)

  let parsed: { frontUrls?: string[]; sideUrls?: string[] } = {}
  try {
    parsed = JSON.parse(result.response)
  } catch (e) {
    console.warn('[Upload] 响应无法解析为 JSON:', e)
  }

  return {
    frontUrls: parsed.frontUrls ?? [],
    sideUrls: parsed.sideUrls ?? [],
  }
}

export const createSession = async (): Promise<SessionInfo> => {
  const sessionInfoStr = localStorage.getItem('sessionInfo')
  if (sessionInfoStr) {
    const sessionInfo = JSON.parse(sessionInfoStr)
    if (new Date(sessionInfo.expiresAt).getTime() > new Date().getTime()) {
      return sessionInfo
    }
  }
  const response = await post<SessionInfo>('/api/session/create', {
    deviceId: '123456789011111111',
  })
  localStorage.setItem('sessionInfo', JSON.stringify(response))
  return response
}

export const saveUserInfo = (userInfo: Record<string, any>) => {
  return post('/api/user/info/save', userInfo)
}

export const saveQuestionAnswers = (answers: Record<string, any>) => {
  return post('/api/questionnaire/answer', answers)
}

// export const startAnalysis = async (): Promise<any> => {
//   // Mock: delay 5s then return local JSON
//   await new Promise((resolve) => setTimeout(resolve, 5000))
//   const res = await fetch('/analysis-result.json')
//   return res.json()
// }

/**
 * POST /api/ai/analyze
 *
 * 直接复用 `post()`，由 `request-native.request` 在以下情况 reject：
 *   - CapacitorHttp 网络/HTTP 抛错（断网、超时、CORS 等）
 *   - HTTP status !== 200（抛 `HTTP error! status: xxx`）
 *   - 响应 JSON `code` !== 200（业务失败，reject 整个 data）
 *
 * 调用方（`analysis.start` / `CameraCaptureView.startAnalysis`）的 try/catch
 * 只拿到 `e.message`，无法区分上述三类失败原因。这里包一层为日志补足上下文，
 * 不改变 reject 行为。
 */
export const startAnalysis = async () => {
  try {
    return await post('/api/ai/analyze')
  } catch (e: any) {
    const status = e?.status ?? e?.response?.status
    const code = e?.code ?? e?.data?.code
    const message = e?.message ?? String(e)
    console.error(
      `[startAnalysis] 调用 /api/ai/analyze 失败 (HTTP ${status ?? '?'}, code ${code ?? '?'}):`,
      message,
      e,
    )
    throw e
  }
}

export const createSubscriptionTask = (): Promise<{ qrcodeUrl: string; followTaskId: string }> => {
  return post('/api/wechat/follow-task/create')
}

export const getSubscriptionStatus = (taskId: string): Promise<{ status: number }> => {
  return post('/api/wechat/follow-status', {
    followTaskId: taskId,
  })
}

export const getAnalysisResult = (taskId: string) => {
  return post('/api/ai/result', {
    taskId,
  })
}
