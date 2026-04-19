import { post } from './request'
interface SessionInfo {
  sessionId: string
  expiresAt: string
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
