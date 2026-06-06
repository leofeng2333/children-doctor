package com.children.doctor.plugins.dualcamera;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

public class PhotoUploader {

    private static final String TAG = "PhotoUploader";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int BUFFER_SIZE = 8192;

    private final Handler mainHandler;

    public interface UploadCallback {
        void onSuccess(String response);
        void onError(String error);
    }

    public PhotoUploader() {
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void upload(String uploadUrl, Map<String, String[]> files, Map<String, String> extraData, UploadCallback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String boundary = "----DualCameraUpload" + System.currentTimeMillis();
                URL url = new URL(uploadUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setDoInput(true);
                connection.setUseCaches(false);
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);

                writeMultipartBody(connection, boundary, files, extraData);

                int responseCode = connection.getResponseCode();
                BufferedReader reader;
                if (responseCode >= 200 && responseCode < 300) {
                    reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                } else {
                    reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                }

                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String responseBody = response.toString();
                if (responseCode >= 200 && responseCode < 300) {
                    mainHandler.post(() -> callback.onSuccess(responseBody));
                } else {
                    mainHandler.post(() -> callback.onError("HTTP " + responseCode + ": " + responseBody));
                }
            } catch (IOException e) {
                Log.e(TAG, "Upload failed", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private void writeMultipartBody(HttpURLConnection connection, String boundary,
                                    Map<String, String[]> files, Map<String, String> extraData) throws IOException {
        DataOutputStream dos = new DataOutputStream(connection.getOutputStream());

        if (extraData != null) {
            for (Map.Entry<String, String> entry : extraData.entrySet()) {
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n");
                dos.writeBytes(entry.getValue() + "\r\n");
            }
        }

        for (Map.Entry<String, String[]> fieldEntry : files.entrySet()) {
            String fieldName = fieldEntry.getKey();
            for (String filePath : fieldEntry.getValue()) {
                File file = new File(filePath);
                if (!file.exists()) {
                    throw new IOException("File not found: " + filePath);
                }

                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + file.getName() + "\"\r\n");
                dos.writeBytes("Content-Type: image/jpeg\r\n\r\n");

                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        dos.write(buffer, 0, bytesRead);
                    }
                }
                dos.writeBytes("\r\n");
            }
        }

        dos.writeBytes("--" + boundary + "--\r\n");
        dos.flush();
    }
}
