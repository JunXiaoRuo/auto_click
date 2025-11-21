package cn.junruo.click;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import java.util.ArrayList;

public class OperationAdapter extends ArrayAdapter<Operation> {
    private final Context context;
    private ArrayList<Operation> operations;

    public OperationAdapter(Context context, ArrayList<Operation> operations) {
        super(context, 0, operations);
        this.context = context;
        this.operations = operations;
    }


    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Operation op = getItem(position);
        if (convertView == null) {
            convertView = LayoutInflater.from(context)
                    .inflate(android.R.layout.simple_list_item_2, parent, false);
        }

        Log.d("OperationAdapter", "创建视图: " + op);

        TextView tv1 = convertView.findViewById(android.R.id.text1);
        TextView tv2 = convertView.findViewById(android.R.id.text2);

        if (op.type == Operation.TYPE_CLICK) {
            tv1.setText("点击操作");
            tv2.setText(String.format("延迟: %dms, 坐标: (%d, %d)",
                    op.delay, op.x1, op.y1));
        } else if (op.type == Operation.TYPE_LONG_PRESS) {
            tv1.setText("长按操作");
            tv2.setText(String.format("延迟: %dms, 坐标: (%d, %d)",
                    op.delay, op.x1, op.y1));
        } else {
            tv1.setText("滑动操作");
            tv2.setText(String.format("延迟: %dms, 从 (%d, %d) 到 (%d, %d)",
                    op.delay, op.x1, op.y1, op.x2, op.y2));
        }

        convertView.setOnClickListener(v -> {
            ((MainActivity)context).showOperationDialog(op);
        });

        return convertView;
    }

}