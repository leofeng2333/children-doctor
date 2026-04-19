export interface DualCameraPhoto {
  frontCameraUrl: string;
  backCameraUrl: string;
  frontCameraPath: string;
  backCameraPath: string;
  timestamp: number;
}

export interface DualCameraOptions {
  frontCamera?: 'front' | 'back';
  imageQuality?: 'low' | 'medium' | 'high';
  saveToGallery?: boolean;
}

export interface DualCameraPreviewRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface DualCameraPermissionStatus {
  camera: 'granted' | 'denied' | 'prompt';
}

export interface DualCameraPlugin {
  checkPermissions(): Promise<DualCameraPermissionStatus>;
  requestPermissions(): Promise<DualCameraPermissionStatus>;
  startPreview(options?: DualCameraOptions): Promise<void>;
  stopPreview(): Promise<void>;
  capture(): Promise<DualCameraPhoto>;
  isPreviewRunning(): Promise<{ running: boolean }>;
}
