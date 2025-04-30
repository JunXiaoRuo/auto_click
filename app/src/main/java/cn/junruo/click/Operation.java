package cn.junruo.click;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;

public class Operation {
    public static final int TYPE_CLICK = 0;
    public static final int TYPE_SWIPE = 1;

    public int type;
    public int delay;
    public int x1, y1;
    public int x2, y2; // 仅滑动时使用

    public static final int DEFAULT_CLICK_DURATION = 50;
    public static final int DEFAULT_SWIPE_DURATION = 100;

    public Operation(int type, int delay, int x1, int y1, int x2, int y2) {
        this.type = type;
        this.delay = delay;
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    public static String toJsonArray(ArrayList<Operation> operations) {
        JSONArray jsonArray = new JSONArray();
        for (Operation op : operations) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("type", op.type);
                obj.put("delay", op.delay);
                obj.put("x1", op.x1);
                obj.put("y1", op.y1);
                obj.put("x2", op.x2);
                obj.put("y2", op.y2);
                jsonArray.put(obj);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return jsonArray.toString();
    }

    public static ArrayList<Operation> fromJsonArray(String json) {
        ArrayList<Operation> operations = new ArrayList<>();
        try {
            JSONArray jsonArray = new JSONArray(json);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                operations.add(new Operation(
                        obj.getInt("type"),
                        obj.getInt("delay"),
                        obj.getInt("x1"),
                        obj.getInt("y1"),
                        obj.getInt("x2"),
                        obj.getInt("y2")
                ));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return operations;
    }
}