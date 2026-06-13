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

  const frontSmileFiles = photos[0] ? [photos[0].frontCameraPath] : []
  const sideSmileFiles = photos[0] ? [photos[0].backCameraPath] : []
  const frontClosedFiles = photos[1] ? [photos[1].frontCameraPath] : []
  const sideClosedFiles = photos[1] ? [photos[1].backCameraPath] : []

  console.log('[Upload] frontSmileFiles:', frontSmileFiles)
  console.log('[Upload] sideSmileFiles:', sideSmileFiles)
  console.log('[Upload] frontClosedFiles:', frontClosedFiles)
  console.log('[Upload] sideClosedFiles:', sideClosedFiles)

  const sessionId = (await createSession()).sessionId

  const uploadOptions = {
    uploadUrl: 'https://aiqc.hzyk.com.cn/promotion/api/photo/upload-batch',
    files: {
      frontSmileFiles,
      sideSmileFiles,
      frontClosedFiles,
      sideClosedFiles,
    },
    extraData: { sessionId },
  }

  console.log('[Upload] ========== 上传参数完整快照 ==========')
  console.log('[Upload] uploadUrl:', uploadOptions.uploadUrl)
  console.log('[Upload] sessionId:', sessionId)
  console.log('[Upload] files:')
  console.log('  frontSmileFiles  =', JSON.stringify(frontSmileFiles))
  console.log('  sideSmileFiles   =', JSON.stringify(sideSmileFiles))
  console.log('  frontClosedFiles =', JSON.stringify(frontClosedFiles))
  console.log('  sideClosedFiles  =', JSON.stringify(sideClosedFiles))
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

export const startAnalysis = async (): Promise<any> => {
  // Mock: delay 5s then return local JSON
  await new Promise((resolve) => setTimeout(resolve, 5000))
  const res = await fetch('/analysis-result.json')
  return res.json()
}

// export const startAnalysis = () => {
//   return post('/api/ai/analyze')
// }

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
