import { WebPlugin } from '@capacitor/core';
import type {
  DualCameraPlugin,
  DualCameraOptions,
  DualCameraPermissionStatus,
  DualCameraUploadOptions,
} from './definitions';

export class DualCameraWeb
  extends WebPlugin
  implements DualCameraPlugin {
  async checkPermissions(): Promise<DualCameraPermissionStatus> {
    throw this.unavailable('Dual camera preview is only available on Android.');
  }

  async requestPermissions(): Promise<DualCameraPermissionStatus> {
    throw this.unimplemented('Not implemented on web.');
  }

  async startPreview(_options?: DualCameraOptions): Promise<void> {
    throw this.unavailable('Dual camera preview is only available on Android.');
  }

  async stopPreview(): Promise<void> {
    throw this.unavailable('Dual camera preview is only available on Android.');
  }

  async capture(): Promise<{ frontCameraUrl: string; backCameraUrl: string; frontCameraPath: string; backCameraPath: string; timestamp: number }> {
    throw this.unavailable('Dual camera capture is only available on Android.');
  }

  async isPreviewRunning(): Promise<{ running: boolean }> {
    throw this.unavailable('Dual camera preview is only available on Android.');
  }

  async startPreviewWithPermission(_options?: DualCameraOptions): Promise<void> {
    throw this.unavailable('Dual camera preview is only available on Android.');
  }

  async uploadPhotos(_options: DualCameraUploadOptions): Promise<{ response: string }> {
    throw this.unavailable('Dual camera upload is only available on Android.');
  }
}
