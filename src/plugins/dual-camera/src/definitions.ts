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

export interface DualCameraUploadOptions {
  uploadUrl: string;
  files: Record<string, string[]>;
  extraData?: Record<string, string>;
}

export interface DualCameraUploadResult {
  response: string;
}

export interface DualCameraUploadProgress {
  percent: number;
}

export interface DualCameraDeviceCamera {
  cameraId: string;
  deviceId: string;
  lensFacing: number | null;
}

export interface DualCameraPreviewResult {
  cameras: DualCameraDeviceCamera[];
  concurrent: boolean;
}

export interface DualCameraPlugin {
  checkPermissions(): Promise<DualCameraPermissionStatus>;
  requestPermissions(): Promise<DualCameraPermissionStatus>;
  getAvailableCameras(): Promise<{ cameras: DualCameraDeviceCamera[] }>;
  isDualCameraSupported(): Promise<{ supported: boolean }>;
  startPreview(options?: DualCameraOptions): Promise<DualCameraPreviewResult>;
  startPreviewWithPermission(options?: DualCameraOptions): Promise<DualCameraPreviewResult>;
  stopPreview(): Promise<void>;
  closeAll(): Promise<void>;
  capture(): Promise<DualCameraPhoto>;
  isPreviewRunning(): Promise<{ running: boolean }>;
  uploadPhotos(options: DualCameraUploadOptions): Promise<DualCameraUploadResult>;
  addListener(eventName: 'captureComplete', listener: (data: DualCameraPhoto) => void): Promise<{ remove: () => void }>;
  addListener(eventName: 'previewError', listener: (data: { error: string }) => void): Promise<{ remove: () => void }>;
}
