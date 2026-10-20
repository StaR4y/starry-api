import { t } from '@/i18n';
/** 外观资源上传前压缩，避免超大 PNG 直接打到 413。 */

export type BrandingAssetKind = 'logo' | 'favicon' | 'background';

export const BRANDING_MAX_BYTES: Record<BrandingAssetKind, number> = {
  logo: 8 * 1024 * 1024,
  favicon: 8 * 1024 * 1024,
  background: 16 * 1024 * 1024,
};

const MAX_EDGE: Record<BrandingAssetKind, number> = {
  logo: 2048,
  favicon: 512,
  background: 2560,
};

export async function prepareBrandingImage(file: File, kind: BrandingAssetKind): Promise<File> {
  const maxBytes = BRANDING_MAX_BYTES[kind];
  const image = await loadImage(file);
  try {
    const scale = Math.min(1, MAX_EDGE[kind] / Math.max(image.width, image.height));
    if (file.size <= maxBytes && scale >= 1) {
      return file;
    }
    let width = Math.max(1, Math.round(image.width * scale));
    let height = Math.max(1, Math.round(image.height * scale));
    const canvas = document.createElement('canvas');
    const context = canvas.getContext('2d');
    if (!context) {
      if (file.size <= maxBytes) {
        return file;
      }
      throw new Error(t('图片过大，请压缩后再上传'));
    }
    const preferJpeg = kind === 'background' || file.type === 'image/jpeg';
    let quality = 0.9;
    for (let attempt = 0; attempt < 6; attempt += 1) {
      canvas.width = width;
      canvas.height = height;
      context.clearRect(0, 0, width, height);
      image.draw(context, width, height);
      const blob =
        (await canvasToBlob(canvas, preferJpeg ? 'image/jpeg' : 'image/webp', quality)) ??
        (await canvasToBlob(canvas, 'image/png'));
      if (blob && blob.size <= maxBytes) {
        return new File([blob], rename(file.name, blob.type), {
          type: blob.type,
          lastModified: Date.now(),
        });
      }
      width = Math.max(64, Math.round(width * 0.75));
      height = Math.max(64, Math.round(height * 0.75));
      quality = Math.max(0.55, quality - 0.1);
    }
    throw new Error(t('图片过大，请压缩后再上传'));
  } finally {
    image.close();
  }
}

function rename(fileName: string, mime: string): string {
  const stem = fileName.replace(/\.[^.]+$/, '') || 'branding';
  if (mime === 'image/jpeg') return `${stem}.jpg`;
  if (mime === 'image/webp') return `${stem}.webp`;
  return `${stem}.png`;
}

function canvasToBlob(
  canvas: HTMLCanvasElement,
  type: string,
  quality?: number,
): Promise<Blob | null> {
  return new Promise((resolve) => {
    canvas.toBlob((blob) => resolve(blob), type, quality);
  });
}

type LoadedImage = {
  width: number;
  height: number;
  draw: (context: CanvasRenderingContext2D, width: number, height: number) => void;
  close: () => void;
};

async function loadImage(file: File): Promise<LoadedImage> {
  if (typeof createImageBitmap === 'function') {
    const bitmap = await createImageBitmap(file);
    return {
      width: bitmap.width,
      height: bitmap.height,
      draw: (context, width, height) => context.drawImage(bitmap, 0, 0, width, height),
      close: () => bitmap.close(),
    };
  }
  const objectUrl = URL.createObjectURL(file);
  try {
    const image = await new Promise<HTMLImageElement>((resolve, reject) => {
      const element = new Image();
      element.onload = () => resolve(element);
      element.onerror = () => reject(new Error(t('无法读取图片')));
      element.src = objectUrl;
    });
    return {
      width: image.naturalWidth,
      height: image.naturalHeight,
      draw: (context, width, height) => context.drawImage(image, 0, 0, width, height),
      close: () => undefined,
    };
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}
