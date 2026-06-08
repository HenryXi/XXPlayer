package com.example.viewerguard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoFileAdapter extends BaseAdapter {
    private static final int NAME_MAX_LENGTH = 24;
    private final LayoutInflater inflater;
    private final List<File> files = new ArrayList<>();
    private final LruCache<String, Bitmap> memCache;
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final File diskCacheDir;

    public VideoFileAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSizeKb = Math.max(1024, maxKb / 20);
        this.memCache = new LruCache<String, Bitmap>(cacheSizeKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        this.diskCacheDir = new File(context.getCacheDir(), "thumbs");
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs();
        }
    }

    public void submit(List<File> items) {
        files.clear();
        files.addAll(items);
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

        // 1. memory cache
        Bitmap cached = memCache.get(videoPath);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        // 2. Try disk cache synchronously for immediate result
        File diskFile = diskCacheFile(videoPath);
        if (diskFile.exists()) {
            try {
                Bitmap diskBitmap = BitmapFactory.decodeFile(diskFile.getAbsolutePath());
                if (diskBitmap != null) {
                    imageView.setImageBitmap(diskBitmap);
                    memCache.put(videoPath, diskBitmap);
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        // 3. Show placeholder and extract in background
        imageView.setImageResource(android.R.drawable.ic_menu_gallery);
        thumbnailExecutor.execute(() -> {
            Bitmap bitmap = extractThumbnail(videoPath);
            if (bitmap != null) {
                saveToDiskCache(videoPath, bitmap);
                memCache.put(videoPath, bitmap);
                mainHandler.post(() -> {
                    if (videoPath.equals(imageView.getTag())) {
                        imageView.setImageBitmap(bitmap);
                    }
                });
            }
        });
    }

    private File diskCacheFile(String videoPath) {
        // Use a simple hash of the path as filename
        String name = Integer.toHexString(videoPath.hashCode()) + ".jpg";
        return new File(diskCacheDir, name);
    }

    private void saveToDiskCache(String videoPath, Bitmap bitmap) {
        File f = diskCacheFile(videoPath);
        try (FileOutputStream out = new FileOutputStream(f)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
        } catch (IOException ignored) {
        }
    }

    private Bitmap extractThumbnail(String videoPath) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoPath);
            // Get duration, seek to middle
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = durationStr != null ? Long.parseLong(durationStr) : 0;
            long seekUs = durationMs > 0 ? (durationMs / 2) * 1000L : 0L;
            Bitmap frame = retriever.getFrameAtTime(seekUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
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
        final ImageView imageThumb;

        ViewHolder(View itemView) {
            textName = itemView.findViewById(R.id.textVideoName);
            imageThumb = itemView.findViewById(R.id.imageVideoThumb);
        }
    }
}
