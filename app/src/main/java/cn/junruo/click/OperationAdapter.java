package cn.junruo.click;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

public class OperationAdapter extends ArrayAdapter<Operation> {
    private final LayoutInflater inflater;

    public OperationAdapter(Context context, ArrayList<Operation> operations) {
        super(context, 0, operations);
        inflater = LayoutInflater.from(context);
    }

    private static class ViewHolder {
        final TextView title;
        final TextView detail;

        ViewHolder(View view) {
            title = view.findViewById(R.id.tv_operation_title);
            detail = view.findViewById(R.id.tv_operation_detail);
        }
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Operation op = getItem(position);
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_operation, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        if (op == null) return convertView;

        if (op.type == Operation.TYPE_CLICK) {
            holder.title.setText("点击");
            holder.detail.setText(String.format(Locale.getDefault(), "延迟 %d ms · 坐标 (%d, %d)",
                    op.delay, op.x1, op.y1));
        } else if (op.type == Operation.TYPE_LONG_PRESS) {
            holder.title.setText("长按");
            holder.detail.setText(String.format(Locale.getDefault(), "延迟 %d ms · 坐标 (%d, %d)",
                    op.delay, op.x1, op.y1));
        } else {
            holder.title.setText("滑动");
            holder.detail.setText(String.format(Locale.getDefault(), "延迟 %d ms · (%d, %d) → (%d, %d)",
                    op.delay, op.x1, op.y1, op.x2, op.y2));
        }

        return convertView;
    }
}
