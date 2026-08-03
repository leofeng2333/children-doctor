package com.children.doctor.plugins.dualcamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Splits a single image into a left and right half on a background thread.
 *
 * <p>Input can be either a file:// or content:// URI, or a base64 data URL.
 * Output is written to the app's external cache directory as two JPEG files
 * and the returned paths are normalized to "file://..." URIs that the WebView
 * can render directly (see {@link DualCameraPlugin#copyImageToExternalCache}
 * for the same convention).
 */
public class ImageSplitter {

    private static final String TAG = "ImageSplitter";
    private static final int JPEG_QUALITY = 95;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ImageSplitter");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    public ImageSplitter(Context context) {
        this.context = context.getApplicationContext();
    }

    public interface SplitCallback {
        void onSuccess(String leftPath, String rightPath, int leftWidth, int rightWidth, int height);
        void onError(String error);
    }

    public void split(String input, double splitRatio, SplitCallback callback) {
        split(input, splitRatio, 0, callback);
    }

    /**
     * Splits with an optional inset (in pixels) trimmed from BOTH sides of the
     * boundary, narrowing each half so that they don't visually meet at the
     * seam. A small gap may appear between the two halves when placed side by
     * side (e.g. before/after slider).
     */
    public void split(String input, double splitRatio, int inset, SplitCallback callback) {
        if (input == null || input.isEmpty()) {
            callback.onError("input is empty");
            return;
        }
        final double ratio = (splitRatio <= 0 || splitRatio >= 1) ? 0.5 : splitRatio;
        final int insetPx = inset < 0 ? 0 : inset;

        executor.execute(() -> doSplit(input, ratio, insetPx, callback));
    }

    private void doSplit(String input, double splitRatio, int inset, SplitCallback callback) {
        Bitmap source = null;
        Bitmap left = null;
        Bitmap right = null;
        FileOutputStream leftOut = null;
        FileOutputStream rightOut = null;
        File leftFile = null;
        File rightFile = null;
        try {
            byte[] bytes = readAllBytes(input);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            source = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (source == null) {
                callback.onError("Failed to decode image");
                return;
            }

            int srcWidth = source.getWidth();
            int halfWidth = (int) Math.floor(srcWidth * splitRatio);
            if (halfWidth <= 0 || halfWidth >= srcWidth) {
                callback.onError("Invalid split ratio: " + splitRatio);
                return;
            }

            // 左右各向内缩 inset 像素；夹紧到 [0, srcWidth] 内并确保不交叉。
            // left  = [0, halfWidth - inset]
            // right = [halfWidth + inset, srcWidth]
            int leftEnd = Math.max(0, halfWidth - inset);
            int rightStart = Math.min(srcWidth, halfWidth + inset);
            if (leftEnd <= 0 || rightStart >= srcWidth || leftEnd >= rightStart) {
                callback.onError("Invalid inset: " + inset);
                return;
            }

            left = Bitmap.createBitmap(source, 0, 0, leftEnd, source.getHeight());
            right = Bitmap.createBitmap(source, rightStart, 0,
                    srcWidth - rightStart, source.getHeight());

            File cacheDir = context.getExternalCacheDir();
            if (cacheDir == null) {
                callback.onError("External cache dir not available");
                return;
            }
            String token = UUID.randomUUID().toString().replace("-", "");
            leftFile = new File(cacheDir, "split_left_" + token + ".jpg");
            rightFile = new File(cacheDir, "split_right_" + token + ".jpg");

            leftOut = new FileOutputStream(leftFile);
            left.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, leftOut);
            leftOut.close();
            leftOut = null;

            rightOut = new FileOutputStream(rightFile);
            right.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, rightOut);
            rightOut.close();
            rightOut = null;

            String leftUri = "file://" + leftFile.getAbsolutePath();
            String rightUri = "file://" + rightFile.getAbsolutePath();

            int height = source.getHeight();
            callback.onSuccess(leftUri, rightUri, leftEnd,
                    srcWidth - rightStart, height);
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "splitImage OOM", oom);
            callback.onError("Out of memory while splitting image");
        } catch (Exception e) {
            Log.e(TAG, "splitImage failed", e);
            callback.onError("splitImage failed: " + e.getMessage());
        } finally {
            closeQuietly(leftOut);
            closeQuietly(rightOut);
            if (source != null && !source.isRecycled()) source.recycle();
            if (left != null && !left.isRecycled()) left.recycle();
            if (right != null && !right.isRecycled()) right.recycle();
        }
    }

    private byte[] readAllBytes(String input) throws IOException {
        if (input == null || input.isEmpty()) {
            throw new IOException("input is null or empty");
        }

        if (input.startsWith("data:")) {
            int comma = input.indexOf(',');
            if (comma < 0) throw new IOException("Malformed data URL");
            String meta = input.substring(5, comma);
            String payload = input.substring(comma + 1);
            if (meta.contains(";base64")) {
                return Base64.decode(payload, Base64.DEFAULT);
            }
            throw new IOException("Only base64 data URLs are supported");
        }

        if (input.startsWith("http://") || input.startsWith("https://")) {
            return readCapacitorFileOrDownload(input);
        }

        if (input.startsWith("file://")) {
            String path = input.substring("file://".length());
            File file = new File(path);
            if (!file.exists() || !file.isFile()) {
                throw new IOException("File not found: " + input);
            }
            return java.nio.file.Files.readAllBytes(file.toPath());
        }

        Uri uri = Uri.parse(input);
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Cannot open input stream for " + input);
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    private byte[] downloadHttp(String url) throws IOException {
        java.net.HttpURLConnection conn = null;
        InputStream in = null;
        try {
            java.net.URL u = new java.net.URL(url);
            conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            // Some TOS-style CDNs reject the default HttpURLConnection UA
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)");
            conn.connect();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " when downloading " + url);
            }

            in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } catch (java.net.MalformedURLException e) {
            throw new IOException("Invalid URL: " + url, e);
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Reads the bytes of an http(s) URL, with awareness of Capacitor's local
     * file server scheme.
     *
     * <p>Capacitor routes {@code file:///foo} through {@code
     * Capacitor.convertFileSrc()} into {@code https://<host>/_capacitor_file_/foo}
     * so the main WebView can fetch local files. Those URLs only resolve
     * inside the WebView (via the Bridge's asset loader); a plain
     * {@link java.net.HttpURLConnection} from plugin code cannot reach them.
     * We detect that shape and read the underlying file directly, which is
     * what the WebView would have done anyway.
     */
    private byte[] readCapacitorFileOrDownload(String url) throws IOException {
        Uri parsed = Uri.parse(url);
        String path = parsed.getPath();
        if (path != null && path.startsWith("/_capacitor_file_/")) {
            String localPath = "/" + path.substring("/_capacitor_file_/".length());
            File file = new File(localPath);
            if (file.exists() && file.isFile()) {
                return java.nio.file.Files.readAllBytes(file.toPath());
            }
            // File missing: surface a clearer error than the generic HTTP
            // failure that would follow otherwise.
            throw new IOException("Capacitor local file not found on disk: " + localPath);
        }
        return downloadHttp(url);
    }

    private void closeQuietly(FileOutputStream out) {
        if (out == null) return;
        try { out.close(); } catch (IOException ignored) {}
    }

    public void shutdown() {
        executor.shutdown();
    }

    /**
     * Reads an image from any supported source and returns it as a base64 string
     * (without the "data:" URL prefix). Returns null on failure.
     *
     * <p>Used by the print pipeline: the Android WebView backing the print
     * dialog runs in a null/data: origin and refuses to load {@code file://}
     * resources (Same-Origin Policy). Inlining the image as a
     * {@code data:image/jpeg;base64,...} URL sidesteps that restriction.
     */
    public String readImageAsBase64(String input) {
        if (input == null || input.isEmpty()) return null;
        try {
            byte[] bytes = readAllBytes(input);
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "readImageAsBase64 failed for " + input, e);
            return null;
        }
    }

    /**
     * Deletes image files written by this plugin from the external cache
     * directory. Only files produced by the dual-camera / image-splitter
     * features are removed (prefixes {@code display_}, {@code split_left_},
     * {@code split_right_}); other cache contents are left untouched.
     *
     * @return number of files removed
     */
    public int clearCache() {
        File cacheDir = context.getExternalCacheDir();
        if (cacheDir == null || !cacheDir.isDirectory()) {
            return 0;
        }
        int removed = 0;
        File[] children = cacheDir.listFiles();
        if (children == null) return 0;
        for (File f : children) {
            if (!f.isFile()) continue;
            String name = f.getName();
            if (name.startsWith("display_")
                    || name.startsWith("split_left_")
                    || name.startsWith("split_right_")) {
                if (f.delete()) {
                    removed++;
                } else {
                    Log.w(TAG, "Failed to delete cache file: " + f.getAbsolutePath());
                }
            }
        }
        Log.d(TAG, "clearCache removed " + removed + " files");
        return removed;
    }
}
