import { CapacitorHttp, type HttpOptions } from '@capacitor/core'
import { createSession } from './service'

const baseUrl = import.meta.env.VITE_API_BASE_URL || ''

const request = async <T>(
  url: string,
  options: Omit<HttpOptions, 'url'> = {},
): Promise<T> => {
  const response = await CapacitorHttp.request({
    url: `${baseUrl}${url}`,
    ...options,
    headers: {
      ...options.headers,
      'Content-Type': 'application/json',
    },
  } as HttpOptions)

  if (response.status !== 200) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }

  const data = response.data
  if (data?.code !== 200) {
    return Promise.reject(data)
  }
  return data.data
}

export const get = async <T>(url: string, params?: Record<string, any>): Promise<T> => {
  return request<T>(url, {
    method: 'GET',
    params,
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
    data: tempData,
  })
}

interface FileEntry {
  type: string
  name: string
  data: string
}

async function readUrlAsMultipartEntry(fileUrl: string): Promise<FileEntry> {
  const response = await fetch(fileUrl)
  if (!response.ok) {
    throw new Error(`Failed to fetch file: ${response.status}`)
  }
  const blob = await response.blob()
  const fileName = fileUrl.split('/').pop() ?? 'file'
  const mimeType = blob.type || 'application/octet-stream'
  const base64 = await blobToBase64(blob)
  return { type: mimeType, name: fileName, data: base64 }
}

function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve((reader.result as string).split(',')[1] ?? '')
    reader.onerror = reject
    reader.readAsDataURL(blob)
  })
}

function buildFormData(
  files: FileEntry[],
  fieldName: string,
  extraData?: Record<string, any>,
): FormData {
  const formData = new FormData()
  files.forEach((file) => {
  const base64Data = file.data ?? ''
  const byteCharacters = atob(base64Data)
  const byteNumbers = Array.from({ length: byteCharacters.length }, (_, i) => byteCharacters.charCodeAt(i))
  const byteArray = new Uint8Array(byteNumbers)
    const blob = new Blob([byteArray], { type: file.type })
    formData.append(fieldName, blob, file.name)
  })
  if (extraData) {
    Object.entries(extraData).forEach(([key, value]) => {
      formData.append(key, String(value))
    })
  }
  return formData
}

async function multipartRequest<T>(url: string, formData: FormData): Promise<T> {
  const response = await fetch(`${baseUrl}${url}`, {
    method: 'POST',
    body: formData,
  })

  if (response.status !== 200) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }

  const data = await response.json()
  if (data?.code !== 200) {
    return Promise.reject(data)
  }
  return data.data
}

export const uploadByUrl = async <T>(
  url: string,
  fileUrl: string,
  fieldName: string = 'file',
  extraData?: Record<string, any>,
): Promise<T> => {
  const file = await readUrlAsMultipartEntry(fileUrl)
  const formData = buildFormData([file], fieldName, extraData)
  return multipartRequest<T>(url, formData)
}

export const uploadByUrls = async <T>(
  url: string,
  fileUrls: string[],
  fieldName: string = 'file',
  extraData?: Record<string, any>,
): Promise<T> => {
  const files = await Promise.all(fileUrls.map((u) => readUrlAsMultipartEntry(u)))
  const formData = buildFormData(files, fieldName, extraData)
  return multipartRequest<T>(url, formData)
}
