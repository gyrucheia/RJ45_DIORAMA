package com.example.sanlomobapp;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class LogsAdapter extends RecyclerView.Adapter<LogsAdapter.LogViewHolder> {

    private final Context context;
    private final List<LogEntry> entries;

    public LogsAdapter(Context context, List<LogEntry> entries) {
        this.context = context;
        this.entries = entries;
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_log_entry, parent, false);
        return new LogViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        LogEntry entry = entries.get(position);

        holder.tvTimestamp.setText(entry.getTimestamp());
        holder.tvEventType.setText(entry.getEventType());
        holder.tvDescription.setText(entry.getDescription());

        switch (entry.getType()) {
            case LogEntry.TYPE_ACTION:
                holder.iconFrame.setBackgroundResource(R.drawable.bg_icon_circle_orange);
                holder.ivIcon.setImageResource(R.drawable.ic_tram);
                holder.ivIcon.setColorFilter(context.getColor(R.color.white));
                holder.tvNewBadge.setVisibility(View.GONE);
                break;
            case LogEntry.TYPE_PIR:
                holder.iconFrame.setBackgroundResource(R.drawable.bg_icon_circle_salmon);
                holder.ivIcon.setImageResource(R.drawable.ic_sensor_visitors);
                holder.ivIcon.setColorFilter(context.getColor(R.color.white));
                holder.tvNewBadge.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
                break;
            case LogEntry.TYPE_ALERT:
                holder.iconFrame.setBackgroundResource(R.drawable.bg_icon_circle_teal);
                holder.ivIcon.setImageResource(R.drawable.ic_sensor_water);
                holder.ivIcon.setColorFilter(context.getColor(R.color.white));
                holder.tvNewBadge.setVisibility(View.GONE);
                break;
            default:
                holder.iconFrame.setBackgroundResource(R.drawable.bg_icon_circle_gray);
                holder.ivIcon.setColorFilter(context.getColor(R.color.nav_inactive));
                holder.tvNewBadge.setVisibility(View.GONE);
                break;
        }
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    public void clearAll() {
        entries.clear();
        notifyDataSetChanged();
    }

    static class LogViewHolder extends RecyclerView.ViewHolder {
        FrameLayout iconFrame;
        ImageView ivIcon;
        TextView tvTimestamp;
        TextView tvEventType;
        TextView tvDescription;
        TextView tvNewBadge;

        LogViewHolder(@NonNull View itemView) {
            super(itemView);
            iconFrame   = itemView.findViewById(R.id.iv_log_icon).getParent() instanceof FrameLayout
                    ? (FrameLayout) itemView.findViewById(R.id.iv_log_icon).getParent() : null;
            ivIcon      = itemView.findViewById(R.id.iv_log_icon);
            tvTimestamp = itemView.findViewById(R.id.tv_log_timestamp);
            tvEventType = itemView.findViewById(R.id.tv_log_event_type);
            tvDescription = itemView.findViewById(R.id.tv_log_description);
            tvNewBadge  = itemView.findViewById(R.id.tv_log_new_badge);

            if (iconFrame == null && ivIcon != null && ivIcon.getParent() != null) {
                iconFrame = (FrameLayout) ivIcon.getParent();
            }
        }
    }
}
