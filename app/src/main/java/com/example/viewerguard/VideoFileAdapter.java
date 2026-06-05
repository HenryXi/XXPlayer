package com.example.viewerguard;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoFileAdapter extends BaseAdapter {
    private static final int NAME_MAX_LENGTH = 24;
    private final LayoutInflater inflater;
    private final List<File> files = new ArrayList<>();
    private final Map<String, Integer> playCounts = new HashMap<>();
    private final LruCache<String, Bitmap> thumbnailCache;
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public VideoFileAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSizeKb = Math.max(1024, maxKb / 20);
        this.thumbnailCache = new LruCache<String, Bitmap>(cacheSizeKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
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
        ViewHolder holder;
        if (view == null) {
            view = inflater.inflate(R.layout.item_video, parent, false);
            holder = new ViewHolder(view);
            view.setTag(holder);
        } else {
            holder = (ViewHolder) view.getTag();
        }

        File file = files.get(position);
        String path = file.getAbsolutePath();

        holder.textName.setText(truncateName(file.getName()));

        int count = 0;
        if (playCounts.containsKey(path)) {
            count = playCounts.get(path);
        }
        holder.textCount.setText(parent.getContext().getString(R.string.play_count_list_format, count));
        loadThumbnail(holder.imageThumb, path);

        return view;
    }

    private String truncateName(String name) {
        if (name == null || name.length() <= NAME_MAX_LENGTH) {
            return name;
        }
        return name.substring(0, NAME_MAX_LENGTH - 3) + "...";
    }

    private void loadThumbnail(ImageView imageView, String videoPath) {
        imageView.setTag(videoPath);
        Bitmap cached = thumbnailCache.get(videoPath);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        imageView.setImageResource(android.R.drawable.ic_menu_gallery);
        thumbnailExecutor.execute(() -> {
            Bitmap bitmap = extractThumbnail(videoPath);
            if (bitmap == null) {
                return;
            }
            thumbnailCache.put(videoPath, bitmap);
            mainHandler.post(() -> {
                Object tag = imageView.getTag();
                if (videoPath.equals(tag)) {
                    imageView.setImageBitmap(bitmap);
                }
            });
        });
    }

    private Bitmap extractThumbnail(String videoPath) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoPath);
            Bitmap frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                frame = retriever.getFrameAtTime();
            }
            return frame;
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static class ViewHolder {
        final TextView textName;
        final TextView textCount;
        final ImageView imageThumb;

        ViewHolder(View itemView) {
            textName = itemView.findViewById(R.id.textVideoName);
            textCount = itemView.findViewById(R.id.textVideoCount);
            imageThumb = itemView.findViewById(R.id.imageVideoThumb);
        }
    }
}
