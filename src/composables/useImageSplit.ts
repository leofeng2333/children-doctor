import { ref, watch } from 'vue';

export interface ImageSplitResult {
  leftUrl: ReturnType<typeof ref<string>>;
  rightUrl: ReturnType<typeof ref<string>>;
  isLoading: ReturnType<typeof ref<boolean>>;
}

export function useImageSplit(
  imageUrl: string | (() => string | undefined),
  splitRatio: number = 0.5,
): ImageSplitResult {
  const leftUrl = ref<string>('');
  const rightUrl = ref<string>('');
  const isLoading = ref(false);

  let currentController: AbortController | null = null;

  function splitImage(url: string) {
    if (!url) {
      leftUrl.value = '';
      rightUrl.value = '';
      return;
    }

    isLoading.value = true;

    // Cancel any in-flight image load
    if (currentController) {
      currentController.abort();
    }
    currentController = new AbortController();

    const img = new Image();
    img.crossOrigin = 'anonymous';

    img.onload = () => {
      const halfWidth = Math.floor(img.width * splitRatio);

      // Left half
      const canvasL = document.createElement('canvas');
      canvasL.width = halfWidth;
      canvasL.height = img.height;
      const ctxL = canvasL.getContext('2d')!;
      ctxL.drawImage(img, 0, 0, halfWidth, img.height, 0, 0, halfWidth, img.height);
      leftUrl.value = canvasL.toDataURL('image/jpeg', 0.95);

      // Right half
      const canvasR = document.createElement('canvas');
      canvasR.width = img.width - halfWidth;
      canvasR.height = img.height;
      const ctxR = canvasR.getContext('2d')!;
      ctxR.drawImage(
        img,
        halfWidth,
        0,
        img.width - halfWidth,
        img.height,
        0,
        0,
        img.width - halfWidth,
        img.height,
      );
      rightUrl.value = canvasR.toDataURL('image/jpeg', 0.95);

      isLoading.value = false;
      img.remove();
    };

    img.onerror = () => {
      isLoading.value = false;
      img.remove();
    };

    img.src = url;
  }

  // Support both plain string and getter function (for computed compatibility)
  watch(
    () => (typeof imageUrl === 'function' ? imageUrl() : imageUrl),
    (url) => {
      if (url) splitImage(url);
    },
    { immediate: true },
  );

  return { leftUrl, rightUrl, isLoading };
}
