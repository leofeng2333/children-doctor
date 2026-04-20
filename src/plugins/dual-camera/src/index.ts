import { registerPlugin } from '@capacitor/core';
import type { DualCameraPlugin } from './definitions';
import { DualCameraWeb } from './web';

const DualCamera = registerPlugin<DualCameraPlugin>('DualCamera', {
  web: () => new DualCameraWeb(),
});

export { DualCamera };
export type {
  DualCameraPlugin,
  DualCameraOptions,
  DualCameraPhoto,
  DualCameraPreviewRect,
  DualCameraPermissionStatus,
  DualCameraUploadOptions,
  DualCameraUploadResult,
  DualCameraUploadProgress,
} from './definitions';
