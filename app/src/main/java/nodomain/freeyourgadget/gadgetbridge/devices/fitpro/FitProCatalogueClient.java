/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.devices.fitpro;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only client for the public HiWatchPro catalogue matching a FitPro display. */
public final class FitProCatalogueClient {
    private static final String API = "https://fpapi2.jusonsmart.com";
    // The companion app uses this shared catalogue credential, not the user's account token.
    private static final String AUTHORIZATION = "Bearer 6fcb7f58475b4e5aad8f0f1cadce235e";
    private static final int MAX_DOWNLOAD = 7 * 1024 * 1024;
    private FitProCatalogueClient() { }

    public static final class Face {
        public final long id; public final String title, previewUrl;
        Face(long id, String title, String previewUrl) { this.id=id; this.title=title; this.previewUrl=previewUrl; }
    }
    public static final class Detail {
        public final Face face; public final String binUrl, fontUrl;
        Detail(Face face, String binUrl, String fontUrl) { this.face=face; this.binUrl=binUrl; this.fontUrl=fontUrl; }
    }

    public static List<Face> load(FitProWatchfaceInfo info) throws Exception {
        String url = API + "/api/v2/watch/theme/list?mainModel=" + q(info.mainModel) + "&mchModel=" + q(info.matchModel)
                + "&screenType=" + info.screenType + "&grade=" + info.grade + "&screenWidth=" + info.width
                + "&screenHeight=" + info.height + "&version=" + info.version + "&customer=" + q(info.customer)
                + "&arithmetic=" + info.algorithm;
        JSONObject response = new JSONObject(new String(request(url, true), StandardCharsets.UTF_8));
        if (!response.optBoolean("success")) throw new IOException(error(response, "Catalogue request failed"));
        List<Face> faces = new ArrayList<>(); JSONArray groups = response.getJSONArray("data");
        for (int group=0; group<groups.length(); group++) {
            JSONArray items = groups.getJSONObject(group).optJSONArray("list"); if (items == null) continue;
            for (int item=0; item<items.length(); item++) faces.add(face(items.getJSONObject(item)));
        }
        return faces;
    }

    public static Detail detail(long id, FitProWatchfaceInfo info) throws Exception {
        String url = API + "/api/v1/watch/theme/detail?id=" + id + "&arithmetic=" + info.algorithm
                + "&mainModel=" + q(info.mainModel) + "&mchModel=" + q(info.matchModel);
        JSONObject response = new JSONObject(new String(request(url, true), StandardCharsets.UTF_8));
        if (!response.optBoolean("success")) throw new IOException(error(response, "Face request failed"));
        JSONObject data = response.getJSONObject("data");
        Face face = face(data);
        JSONObject bin = data.optJSONObject("binFile"), font = data.optJSONObject("fontFile");
        if (bin == null || bin.optString("url").isEmpty()) throw new IOException("Catalogue face has no binary");
        return new Detail(face, bin.getString("url"), font == null ? null : font.optString("url", null));
    }

    public static byte[] download(String url) throws IOException { return request(url, false); }
    private static Face face(JSONObject item) throws Exception {
        JSONArray material = item.optJSONArray("materialList"); String preview = null;
        if (material != null) {
            for (int i=0;i<material.length();i++) { JSONObject asset=material.getJSONObject(i); if (asset.optString("name").toLowerCase().contains("gif")) { preview=asset.optString("url"); break; } }
            if (preview == null) for (int i=0;i<material.length();i++) { JSONObject asset=material.getJSONObject(i); if ("preview.png".equalsIgnoreCase(asset.optString("name"))) { preview=asset.optString("url"); break; } }
        }
        return new Face(item.getLong("id"), item.optString("name", "Watch face " + item.getLong("id")), preview);
    }
    private static byte[] request(String address, boolean authorization) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection(); connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
        if (authorization) connection.setRequestProperty("Authorization", AUTHORIZATION);
        try (InputStream input=connection.getInputStream(); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] block=new byte[8192]; int count; while((count=input.read(block))!=-1) { if(output.size()+count>MAX_DOWNLOAD) throw new IOException("Download exceeds 7 MiB"); output.write(block,0,count); } return output.toByteArray();
        } finally { connection.disconnect(); }
    }
    private static String error(JSONObject response, String fallback) { JSONObject error=response.optJSONObject("error"); return error == null ? fallback : error.optString("message", fallback); }
    private static String q(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
