import { WebPlugin } from '@capacitor/core'
import type {
  DualCameraPlugin,
  DualCameraOptions,
  DualCameraPreviewResult,
  DualCameraUploadOptions,
} from './definitions'

export class DualCameraWeb extends WebPlugin implements DualCameraPlugin {
  async getAvailableCameras(): Promise<{ cameras: unknown[] }> {
    throw this.unavailable('Dual camera is only available on Android.')
  }

  async isDualCameraSupported(): Promise<{ supported: boolean }> {
    throw this.unavailable('Dual camera is only available on Android.')
  }

  async startPreview(_options?: DualCameraOptions): Promise<DualCameraPreviewResult> {
    throw this.unavailable('Dual camera preview is only available on Android.')
  }

  async stopPreview(): Promise<void> {
    throw this.unavailable('Dual camera preview is only available on Android.')
  }

  async uploadPhotos(_options: DualCameraUploadOptions): Promise<{ response: string }> {
    throw this.unavailable('Dual camera upload is only available on Android.')
  }
}
