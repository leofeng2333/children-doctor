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

  const frontPaths = photos.map((p) => p.frontCameraPath)
  const sidePaths = photos.map((p) => p.backCameraPath)

  console.log('[Upload] front paths:', frontPaths)
  console.log('[Upload] side paths:', sidePaths)

  const result = await DualCamera.uploadPhotos({
    uploadUrl: 'https://aiqc.hzyk.com.cn/promotion/api/photo/upload-batch',
    files: {
      front: frontPaths,
      side: sidePaths,
    },
    extraData: {
      sessionId: (await createSession()).sessionId,
    },
  })

  console.log('[Upload] 上传完成, 响应:', result.response)

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

export const startAnalysis = () => {
  return post('/api/ai/analyze')
}
