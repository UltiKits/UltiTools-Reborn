package com.ultikits.ultitools.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Simple HTTP client implementation using HttpURLConnection.
 * Replaces Hutool-http to reduce dependency size.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public class SimpleHttpClient {

    public static class Response implements AutoCloseable {
        private final int status;
        private final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public int getStatus() {
            return status;
        }

        public String getBody() {
            return body;
        }

        public boolean isOk() {
            return status >= 200 && status < 300;
        }

        public String body() {
            return body;
        }

        @Override
        public void close() {
            // No resources to close as we read everything into memory
        }
    }

    public static Response get(String urlStr) {
        return get(urlStr, null);
    }

    public static Response get(String urlStr, Map<String, String> headers) {
        return execute("GET", urlStr, headers, null, null);
    }

    public static Response post(String urlStr, Map<String, String> headers, Map<String, Object> formData) {
        return execute("POST", urlStr, headers, formData, null);
    }

    public static Response post(String urlStr, Map<String, String> headers, String jsonBody) {
        return execute("POST", urlStr, headers, null, jsonBody);
    }

    private static Response execute(String method, String urlStr, Map<String, String> headers, Map<String, Object> formData, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setDoInput(true);

            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            if ("POST".equals(method)) {
                conn.setDoOutput(true);
                if (formData != null && !formData.isEmpty()) {
                    if (conn.getRequestProperty("Content-Type") == null) {
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    }
                    String encodedData = buildFormData(formData);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(encodedData.getBytes(StandardCharsets.UTF_8));
                    }
                } else if (jsonBody != null) {
                    if (conn.getRequestProperty("Content-Type") == null) {
                        conn.setRequestProperty("Content-Type", "application/json");
                    }
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }

            int status = conn.getResponseCode();
            String body = readResponse(conn);
            return new Response(status, body);

        } catch (IOException e) {
            return new Response(500, "Error: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String buildFormData(Map<String, Object> data) throws UnsupportedEncodingException {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (builder.length() > 0) {
                builder.append("&");
            }
            builder.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            builder.append("=");
            builder.append(URLEncoder.encode(String.valueOf(entry.getValue()), "UTF-8"));
        }
        return builder.toString();
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        InputStream is;
        try {
            is = conn.getInputStream();
        } catch (IOException e) {
            is = conn.getErrorStream();
        }
        if (is == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }
}
