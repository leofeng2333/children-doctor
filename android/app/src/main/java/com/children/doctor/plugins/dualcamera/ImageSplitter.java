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
        if (input == null || input.isEmpty()) {
            callback.onError("input is empty");
            return;
        }
        final double ratio = (splitRatio <= 0 || splitRatio >= 1) ? 0.5 : splitRatio;

        executor.execute(() -> doSplit(input, ratio, callback));
    }

    private void doSplit(String input, double splitRatio, SplitCallback callback) {
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

            int halfWidth = (int) Math.floor(source.getWidth() * splitRatio);
            if (halfWidth <= 0 || halfWidth >= source.getWidth()) {
                callback.onError("Invalid split ratio: " + splitRatio);
                return;
            }

            left = Bitmap.createBitmap(source, 0, 0, halfWidth, source.getHeight());
            right = Bitmap.createBitmap(source, halfWidth, 0,
                    source.getWidth() - halfWidth, source.getHeight());

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
            callback.onSuccess(leftUri, rightUri, halfWidth,
                    source.getWidth() - halfWidth, height);
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

    private void closeQuietly(FileOutputStream out) {
        if (out == null) return;
        try { out.close(); } catch (IOException ignored) {}
    }

    public void shutdown() {
        executor.shutdown();
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
