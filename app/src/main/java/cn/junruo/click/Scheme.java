// 新建 Scheme.java 文件
package cn.junruo.click;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;

public class Scheme {
    public String name;
    public String appName;
    public String appActivity;
    public ArrayList<Operation> operations;
    public boolean stopAppsEnabled;
    public ArrayList<String> appsToStop;


    public Scheme(String name) {
        this.name = name;
        this.operations = new ArrayList<>();
        this.appsToStop = new ArrayList<>();
    }

    public static String toJsonArray(ArrayList<Scheme> schemes) {
        JSONArray jsonArray = new JSONArray();
        for (Scheme scheme : schemes) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("name", scheme.name);
                obj.put("appName", scheme.appName);
                obj.put("appActivity", scheme.appActivity);
                obj.put("operations", Operation.toJsonArray(scheme.operations));
                obj.put("stopAppsEnabled", scheme.stopAppsEnabled);
                obj.put("appsToStop", new JSONArray(scheme.appsToStop));
                jsonArray.put(obj);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return jsonArray.toString();
    }

    // 在Scheme.java中修改fromJsonArray方法
    public static ArrayList<Scheme> fromJsonArray(String json) {
        ArrayList<Scheme> schemes = new ArrayList<>();
        try {
            JSONArray jsonArray = new JSONArray(json);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                Scheme scheme = new Scheme(obj.getString("name"));
                scheme.appName = obj.optString("appName", "");
                scheme.appActivity = obj.optString("appActivity", "");
                scheme.operations = Operation.fromJsonArray(obj.getString("operations"));
                scheme.stopAppsEnabled = obj.optBoolean("stopAppsEnabled", false);

                // 修复点：更健壮的appsToStop处理
                if (obj.has("appsToStop")) {
                    JSONArray appsArray = obj.optJSONArray("appsToStop");
                    if (appsArray != null) {
                        scheme.appsToStop.clear();
                        for (int j = 0; j < appsArray.length(); j++) {
                            String app = appsArray.optString(j, null);
                            if (app != null && !app.isEmpty()) {
                                scheme.appsToStop.add(app);
                            }
                        }
                    }
                }
                schemes.add(scheme);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return schemes;
    }
}