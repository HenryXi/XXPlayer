package com.example.viewerguard;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1001;
    private static final List<String> SUPPORTED_EXTENSIONS = Arrays.asList("mp4", "mkv", "avi", "3gp", "mov");

    private SurfaceView surfaceView;
    private FrameLayout videoContainer;
    private ImageButton buttonPlay;
    private ListView listVideos;
    private ProgressBar seekProgress;
    private TextView textCurrentTime;
    private TextView textTotalTime;

    private VideoFileAdapter adapter;
    private List<File> videos = new ArrayList<>();
    private final Handler progressHandler = new Handler();

    private SurfaceHolder surfaceHolder;
    private MediaPlayer mediaPlayer;
    private boolean playerPrepared;
    private File currentVideoFile;
    private VideoStatsDbHelper dbHelper;
    private int resumePositionMs;
    private boolean resumeWhenReady;
    private boolean recoveringPlayer;
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            updatePlaybackProgress();
            if (isPlayingNow()) {
                progressHandler.postDelayed(this, 500);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dbHelper = new VideoStatsDbHelper(this);

        bindViews();
        setupList();
        setupSurface();
        setupButtons();

        if (hasRequiredPermissions()) {
            loadVideoList();
        } else {
            requestRequiredPermissions();
        }
    }

    private void bindViews() {
        surfaceView = findViewById(R.id.surfaceView);
        videoContainer = findViewById(R.id.videoContainer);
        buttonPlay = findViewById(R.id.buttonPlay);
        listVideos = findViewById(R.id.listVideos);
        seekProgress = findViewById(R.id.seekProgress);
        textCurrentTime = findViewById(R.id.textCurrentTime);
        textTotalTime = findViewById(R.id.textTotalTime);

        buttonPlay.setEnabled(false);
        buttonPlay.setVisibility(View.GONE);
        seekProgress.setMax(1000);
        seekProgress.setProgress(0);
        textCurrentTime.setText(formatTime(0));
        textTotalTime.setText(formatTime(0));
        resumePositionMs = 0;
        resumeWhenReady = false;
        recoveringPlayer = false;
    }

    private void setupList() {
        adapter = new VideoFileAdapter(this);
        listVideos.setAdapter(adapter);
        listVideos.setOnItemClickListener((parent, view, position, id) -> {
            File file = adapter.getItemAt(position);
            playVideo(file);
        });
    }

    private void setupSurface() {
        // Keep SurfaceView below normal UI layers so bottom progress area never overlays video content.
        surfaceView.setZOrderOnTop(false);
        surfaceView.setZOrderMediaOverlay(false);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                surfaceHolder = holder;
                attachDisplayIfReady();
                tryRestorePlaybackIfNeeded();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                surfaceHolder = holder;
                attachDisplayIfReady();
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                if (mediaPlayer != null) {
                    try {
                        if (mediaPlayer.isPlaying()) {
                            mediaPlayer.pause();
                            showPlayOverlay();
                        }
                    } catch (IllegalStateException ignored) {
                    }
                }
                surfaceHolder = null;
            }
        });
    }

    private void setupButtons() {
        buttonPlay.setOnClickListener(v -> resumePlayback());
        videoContainer.setOnClickListener(v -> {
            if (isPlayingNow()) {
                pauseByUser();
            }
        });
    }

    private void loadVideoList() {
        File dir = new File(AppConfig.VIDEO_DIR_PATH);
        if (!dir.exists() || !dir.isDirectory()) {
            videos.clear();
            adapter.submit(videos);
            Toast.makeText(this, R.string.status_dir_missing, Toast.LENGTH_LONG).show();
            return;
        }

        File[] fileArray = dir.listFiles(pathname -> pathname.isFile() && isVideoFile(pathname.getName()));
        videos = new ArrayList<>();
        if (fileArray != null) {
            videos.addAll(Arrays.asList(fileArray));
        }

        Collections.sort(videos, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        adapter.submit(videos);

        Map<String, Integer> counts = dbHelper.queryAllPlayCounts();
        adapter.updatePlayCounts(counts);

        if (videos.isEmpty()) {
            Toast.makeText(this, R.string.status_no_video_found, Toast.LENGTH_LONG).show();
        }
    }

    private boolean isVideoFile(String name) {
        int idx = name.lastIndexOf('.');
        if (idx <= 0 || idx >= name.length() - 1) {
            return false;
        }
        String ext = name.substring(idx + 1).toLowerCase(Locale.US);
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    private void playVideo(File file) {
        if (surfaceHolder == null) {
            Toast.makeText(this, R.string.surface_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }

        currentVideoFile = file;
        resumePositionMs = 0;
        resumeWhenReady = true;
        createAndPreparePlayer(file, 0, true);
    }

    private void pauseByUser() {
        if (mediaPlayer == null || !playerPrepared || !mediaPlayer.isPlaying()) {
            return;
        }
        mediaPlayer.pause();
        resumeWhenReady = false;
        resumePositionMs = safeCurrentPosition();
        stopProgressUpdates();
        updatePlaybackProgress();
        showPlayOverlay();
    }

    private void resumePlayback() {
        // Single tap should always queue immediate resume, even if player is rebuilding/preparing.
        resumeWhenReady = true;
        hidePlayOverlay();

        if (mediaPlayer == null || !playerPrepared) {
            if (currentVideoFile == null) {
                Toast.makeText(this, R.string.select_video_first, Toast.LENGTH_SHORT).show();
                showPlayOverlay();
                return;
            }
            tryRestorePlaybackIfNeeded();
            return;
        }
        if (!mediaPlayer.isPlaying()) {
            try {
                mediaPlayer.start();
            } catch (IllegalStateException e) {
                handlePlayerFailure();
                return;
            }
            startProgressUpdates();
            hidePlayOverlay();
        }
    }

    private boolean isPlayingNow() {
        return mediaPlayer != null && playerPrepared && mediaPlayer.isPlaying();
    }

    private void releasePlayer() {
        stopProgressUpdates();
        playerPrepared = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            mediaPlayer.reset();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        seekProgress.setProgress(0);
        textCurrentTime.setText(formatTime(0));
        textTotalTime.setText(formatTime(0));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            resumePositionMs = safeCurrentPosition();
            resumeWhenReady = true;
            stopProgressUpdates();
            updatePlaybackProgress();
            showPlayOverlay();
        } else if (playerPrepared) {
            resumePositionMs = safeCurrentPosition();
            updatePlaybackProgress();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        tryRestorePlaybackIfNeeded();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (playerPrepared) {
            resumePositionMs = safeCurrentPosition();
        }
        releasePlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        attachDisplayIfReady();
        tryRestorePlaybackIfNeeded();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
        if (dbHelper != null) {
            dbHelper.close();
        }
    }

    private boolean hasRequiredPermissions() {
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_VIDEO};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private void requestRequiredPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }

        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.permission_required_exit, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }

        loadVideoList();
    }

    private void attachDisplayIfReady() {
        if (mediaPlayer == null || !playerPrepared || surfaceHolder == null) {
            return;
        }
        try {
            mediaPlayer.setDisplay(surfaceHolder);
        } catch (IllegalStateException ignored) {
        }
    }

    private void showPlayOverlay() {
        buttonPlay.setVisibility(View.VISIBLE);
    }

    private void hidePlayOverlay() {
        buttonPlay.setVisibility(View.GONE);
    }

    private void tryRestorePlaybackIfNeeded() {
        if (recoveringPlayer || currentVideoFile == null || surfaceHolder == null || mediaPlayer != null) {
            return;
        }
        createAndPreparePlayer(currentVideoFile, Math.max(0, resumePositionMs), resumeWhenReady);
    }

    private void createAndPreparePlayer(File file, int startPositionMs, boolean autoStart) {
        releasePlayer();
        resumeWhenReady = autoStart;
        recoveringPlayer = true;
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDisplay(surfaceHolder);
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.setOnVideoSizeChangedListener((mp, width, height) -> applyVideoAspect(width, height));
            mediaPlayer.setOnPreparedListener(mp -> {
                recoveringPlayer = false;
                playerPrepared = true;
                currentVideoFile = file;
                buttonPlay.setEnabled(true);
                applyVideoAspect(mp.getVideoWidth(), mp.getVideoHeight());
                seekProgress.setMax(Math.max(1, mp.getDuration()));
                textTotalTime.setText(formatTime(mp.getDuration()));
                if (startPositionMs > 0) {
                    try {
                        mp.seekTo(startPositionMs);
                    } catch (IllegalStateException ignored) {
                    }
                }
                updatePlaybackProgress();
                if (resumeWhenReady) {
                    try {
                        mp.start();
                        startProgressUpdates();
                        hidePlayOverlay();
                    } catch (IllegalStateException e) {
                        handlePlayerFailure();
                    }
                } else {
                    stopProgressUpdates();
                    showPlayOverlay();
                }
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                stopProgressUpdates();
                resumePositionMs = 0;
                resumeWhenReady = false;
                updatePlaybackProgress();
                showPlayOverlay();
                if (currentVideoFile != null) {
                    int newCount = dbHelper.incrementPlayCount(currentVideoFile.getAbsolutePath(), currentVideoFile.getName());
                    adapter.updateSinglePlayCount(currentVideoFile.getAbsolutePath(), newCount);
                }
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                handlePlayerFailure();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            recoveringPlayer = false;
            Toast.makeText(this, R.string.status_player_error, Toast.LENGTH_SHORT).show();
            releasePlayer();
            showPlayOverlay();
        }
    }

    private void handlePlayerFailure() {
        stopProgressUpdates();
        int fallbackPosition = safeCurrentPosition();
        if (fallbackPosition > 0) {
            resumePositionMs = fallbackPosition;
        }
        releasePlayer();
        recoveringPlayer = false;
        showPlayOverlay();
        Toast.makeText(this, R.string.status_player_recovering, Toast.LENGTH_SHORT).show();
        tryRestorePlaybackIfNeeded();
    }

    private int safeCurrentPosition() {
        if (mediaPlayer == null || !playerPrepared) {
            return resumePositionMs;
        }
        try {
            return mediaPlayer.getCurrentPosition();
        } catch (IllegalStateException ignored) {
            return resumePositionMs;
        }
    }

    private void startProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable);
        progressHandler.post(progressRunnable);
    }

    private void stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable);
    }

    private void updatePlaybackProgress() {
        if (mediaPlayer == null || !playerPrepared) {
            return;
        }
        try {
            int duration = Math.max(1, mediaPlayer.getDuration());
            int position = Math.max(0, mediaPlayer.getCurrentPosition());
            seekProgress.setMax(duration);
            seekProgress.setProgress(Math.min(position, duration));
            textCurrentTime.setText(formatTime(position));
            textTotalTime.setText(formatTime(duration));
        } catch (IllegalStateException ignored) {
        }
    }

    private String formatTime(int millis) {
        int totalSeconds = Math.max(0, millis / 1000);
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void applyVideoAspect(int videoWidth, int videoHeight) {
        if (videoWidth <= 0 || videoHeight <= 0 || videoContainer == null || surfaceView == null) {
            return;
        }

        int containerWidth = videoContainer.getWidth();
        int containerHeight = videoContainer.getHeight();
        if (containerWidth <= 0 || containerHeight <= 0) {
            Point size = new Point();
            getWindowManager().getDefaultDisplay().getSize(size);
            containerWidth = Math.max(1, (size.x * 2) / 3);
            containerHeight = Math.max(1, size.y);
        }

        float videoAspect = (float) videoWidth / (float) videoHeight;
        float containerAspect = (float) containerWidth / (float) containerHeight;

        int targetWidth;
        int targetHeight;
        if (containerAspect > videoAspect) {
            targetHeight = containerHeight;
            targetWidth = Math.round(containerHeight * videoAspect);
        } else {
            targetWidth = containerWidth;
            targetHeight = Math.round(containerWidth / videoAspect);
        }

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(targetWidth, targetHeight);
        params.gravity = Gravity.CENTER;
        surfaceView.setLayoutParams(params);
        surfaceView.requestLayout();
    }
}
