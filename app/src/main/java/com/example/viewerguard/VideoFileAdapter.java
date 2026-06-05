package com.example.viewerguard;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VideoFileAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final List<File> files = new ArrayList<>();
    private final Map<String, Integer> playCounts = new HashMap<>();

    public VideoFileAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void submit(List<File> items) {
        files.clear();
        files.addAll(items);
        notifyDataSetChanged();
    }

    public void updatePlayCounts(Map<String, Integer> counts) {
        playCounts.clear();
        playCounts.putAll(counts);
        notifyDataSetChanged();
    }

    public void updateSinglePlayCount(String videoPath, int count) {
        playCounts.put(videoPath, count);
        notifyDataSetChanged();
    }

    public File getItemAt(int position) {
        return files.get(position);
    }

    @Override
    public int getCount() {
        return files.size();
    }

    @Override
    public Object getItem(int position) {
        return files.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.item_video, parent, false);
        }

        TextView textName = view.findViewById(R.id.textVideoName);
        TextView textCount = view.findViewById(R.id.textVideoCount);

        File file = files.get(position);
        textName.setText(file.getName());

        int count = 0;
        if (playCounts.containsKey(file.getAbsolutePath())) {
            count = playCounts.get(file.getAbsolutePath());
        }
        textCount.setText(parent.getContext().getString(R.string.play_count_list_format, count));

        return view;
    }
}
