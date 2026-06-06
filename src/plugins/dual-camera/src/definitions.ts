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

export interface DualCameraDeviceCamera {
  cameraId: string;
  lensFacing: number;
  previewWidth?: number;
  previewHeight?: number;
  captureWidth?: number;
  captureHeight?: number;
}

export interface DualCameraPreviewResult {
  cameras: DualCameraDeviceCamera[];
  concurrent: boolean;
}

export interface DualCameraUploadOptions {
  uploadUrl: string;
  files: Record<string, string[]>;
  extraData?: Record<string, string>;
}

export interface DualCameraUploadResult {
  response: string;
}

export interface DualCameraPlugin {
  getAvailableCameras(): Promise<{ cameras: DualCameraDeviceCamera[] }>;
  isDualCameraSupported(): Promise<{ supported: boolean }>;
  startPreview(): Promise<DualCameraPreviewResult>;
  stopPreview(): Promise<void>;
  capture(): Promise<DualCameraPhoto & Record<string, unknown>>;
  uploadPhotos(options: DualCameraUploadOptions): Promise<DualCameraUploadResult>;
  addListener(eventName: 'captureComplete', listener: (data: DualCameraPhoto & Record<string, unknown>) => void): Promise<{ remove: () => void }>;
  addListener(eventName: 'previewError', listener: (data: { error: string }) => void): Promise<{ remove: () => void }>;
}
