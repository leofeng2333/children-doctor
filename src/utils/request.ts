import { createSession } from './service'

const baseUrl = import.meta.env.VITE_API_BASE_URL || ''

const getHeaders = () => ({
  'Content-Type': 'application/json',
})

const request = async <T>(
  url: string,
  options: RequestInit & { isFormData?: boolean } = {},
): Promise<T> => {
  const { isFormData, ...fetchOptions } = options

  const headers: Record<string, string> = {}
  if (!isFormData) {
    Object.assign(headers, getHeaders())
  }

  const response = await fetch(`${baseUrl}${url}`, {
    ...fetchOptions,
    headers: {
      ...headers,
      ...fetchOptions.headers,
    },
  })

  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }

  const json = await response.json()
  if (json.code !== 200) {
    return Promise.reject(json)
  }
  return json.data
}

export const get = <T>(url: string, params?: Record<string, any>): Promise<T> => {
  let queryString = ''
  if (params) {
    queryString = '?' + new URLSearchParams(params).toString()
  }
  return request<T>(`${url}${queryString}`, {
    method: 'GET',
  })
}

export const post = async <T>(url: string, data?: Record<string, any>): Promise<T> => {
  let tempData = data
  if (!url.includes('/api/session/create')) {
    const sessionInfo = await createSession()
    tempData = {
      ...data,
      sessionId: sessionInfo.sessionId,
    }
  }
  return request<T>(url, {
    method: 'POST',
    body: JSON.stringify(tempData),
  })
}

export const upload = <T>(
  url: string,
  file: File,
  fieldName: string = 'file',
  extraData?: Record<string, any>,
): Promise<T> => {
  const formData = new FormData()
  formData.append(fieldName, file)

  if (extraData) {
    Object.entries(extraData).forEach(([key, value]) => {
      formData.append(key, value as string)
    })
  }

  return request<T>(url, {
    method: 'POST',
    isFormData: true,
    body: formData,
  })
}
