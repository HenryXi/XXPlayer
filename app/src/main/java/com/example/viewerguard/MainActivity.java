package com.example.viewerguard;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.graphics.SurfaceTexture;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.airbnb.lottie.LottieAnimationView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1001;
    private static final long AUTO_HIDE_LIST_DELAY_MS = 3000L;
    private static final List<String> SUPPORTED_EXTENSIONS = Arrays.asList("mp4", "mkv", "avi", "3gp", "mov");

    private TextureView surfaceView;
    private FrameLayout videoContainer;
    private ImageButton buttonPlay;
    private ImageButton buttonToggleList;
    private ListView listVideos;
    private FrameLayout listDrawer;
    private DrawerLayout drawerLayout;
    private ProgressBar seekProgress;
    private TextView textCurrentTime;
    private TextView textTotalTime;
    private TextView textTotalPlayCount;
    private TextView textClock;
    private TextView textBattery;
    private LottieAnimationView animationView;
    private ImageView imageThumbnail;

    private VideoFileAdapter adapter;
    private List<File> videos = new ArrayList<>();
    private final Handler progressHandler = new Handler();
    private int totalPlayCount = 0;
    private boolean playCountedForCurrentFile = false;

    private BroadcastReceiver batteryReceiver;
    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            updateClock();
            progressHandler.postDelayed(this, 30000);
        }
    };

    private Surface surface;
    private MediaPlayer mediaPlayer;
    private boolean playerPrepared;
    private File currentVideoFile;
    private int resumePositionMs;
    private boolean resumeWhenReady;
    private boolean recoveringPlayer;
    private int pendingSeekPositionMs;
    private boolean pendingStartAfterSeek;
    private boolean keepScreenOnActive;
    private final Runnable autoHideListRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlayingNow() && isListDrawerOpen()) {
                drawerLayout.closeDrawer(GravityCompat.START);
            }
        }
    };
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
        PlayerLogger.init(this);
        PlayerLogger.i("Lifecycle", "onCreate sdk=" + Build.VERSION.SDK_INT + " device=" + Build.MODEL);

        bindViews();
        setupList();
        setupSurface();
        setupButtons();

        if (hasRequiredPermissions()) {
            PlayerLogger.i("Permission", "granted at startup");
            loadVideoList();
        } else {
            PlayerLogger.w("Permission", "request required permissions");
            requestRequiredPermissions();
        }

        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (scale > 0 && textBattery != null) {
                    int pct = (int) (level * 100f / scale);
                    textBattery.setText(pct + "%");
                }
            }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        updateClock();
        progressHandler.postDelayed(clockRunnable, 30000);
    }

    private void bindViews() {
        surfaceView = findViewById(R.id.surfaceView);
        videoContainer = findViewById(R.id.videoContainer);
        buttonPlay = findViewById(R.id.buttonPlay);
        buttonToggleList = findViewById(R.id.buttonToggleList);
        listVideos = findViewById(R.id.listVideos);
        listDrawer = findViewById(R.id.listDrawer);
        drawerLayout = findViewById(R.id.drawerLayout);
        seekProgress = findViewById(R.id.seekProgress);
        textCurrentTime = findViewById(R.id.textCurrentTime);
        textTotalTime = findViewById(R.id.textTotalTime);
        textTotalPlayCount = findViewById(R.id.textTotalPlayCount);
        textClock = findViewById(R.id.textClock);
        textBattery = findViewById(R.id.textBattery);
        animationView = findViewById(R.id.animationView);
        imageThumbnail = findViewById(R.id.imageThumbnail);

        buttonPlay.setEnabled(false);
        buttonPlay.setVisibility(View.GONE);
        seekProgress.setMax(1000);
        seekProgress.setProgress(0);
        textCurrentTime.setText(formatTime(0));
        textTotalTime.setText(formatTime(0));
        textTotalPlayCount.setText("0");
        resumePositionMs = 0;
        resumeWhenReady = false;
        recoveringPlayer = false;
        pendingSeekPositionMs = 0;
        pendingStartAfterSeek = false;
        keepScreenOnActive = false;
        updateScreenAwakeState(false);

        setupListDrawer();
    }

    private void setupList() {
        adapter = new VideoFileAdapter(this);
        listVideos.setAdapter(adapter);
        listVideos.setOnTouchListener((v, event) -> {
            markListInteraction();
            return false;
        });
        listVideos.setOnItemClickListener((parent, view, position, id) -> {
            markListInteraction();
            File file = adapter.getItemAt(position);
            playVideo(file);
        });
    }

    private void setupSurface() {
        surfaceView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int width, int height) {
                surface = new Surface(st);
                PlayerLogger.i("Surface", "onSurfaceTextureAvailable");
                attachDisplayIfReady();
                refreshFrameIfPaused();
                tryRestorePlaybackIfNeeded();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int width, int height) {
                PlayerLogger.i("Surface", "onSurfaceTextureSizeChanged w=" + width + " h=" + height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
                PlayerLogger.w("Surface", "onSurfaceTextureDestroyed");
                if (mediaPlayer != null) {
                    try {
                        boolean wasPlaying = mediaPlayer.isPlaying();
                        resumePositionMs = safeCurrentPosition();
                        resumeWhenReady = wasPlaying;
                        if (wasPlaying) {
                            mediaPlayer.pause();
                            stopProgressUpdates();
                        }
                        mediaPlayer.setSurface(null);
                    } catch (IllegalStateException ignored) {
                    }
                }
                if (surface != null) {
                    surface.release();
                    surface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {
            }
        });
    }

    private void setupButtons() {
        buttonPlay.setOnClickListener(v -> {
            if (isListDrawerOpen()) {
                drawerLayout.closeDrawer(GravityCompat.START);
            }
            resumePlayback();
        });
        buttonToggleList.setOnClickListener(v -> {
            markListInteraction();
            toggleListDrawer();
        });
        videoContainer.setOnClickListener(v -> {
            markListInteraction();
            if (isListDrawerOpen()) {
                drawerLayout.closeDrawer(GravityCompat.START);
                return;
            }
            if (isPlayingNow()) {
                pauseByUser();
            }
        });
    }

    private void setupListDrawer() {
        drawerLayout.setScrimColor(Color.TRANSPARENT);
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START);
        drawerLayout.post(() -> drawerLayout.openDrawer(GravityCompat.START, false));

        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {
                markListInteraction();
                updateDrawerToggleVisual(slideOffset);
            }

            @Override
            public void onDrawerOpened(@NonNull View drawerView) {
                updateDrawerToggleVisual(1f);
            }

            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
                updateDrawerToggleVisual(0f);
            }
        });

        listDrawer.post(() -> updateDrawerToggleVisual(isListDrawerOpen() ? 1f : 0f));
    }

    private void updateDrawerToggleVisual(float slideOffset) {
        int drawerWidth = listDrawer.getWidth();
        if (drawerWidth <= 0) {
            return;
        }

        buttonToggleList.setTranslationX(drawerWidth * slideOffset);
        // Keep the handle visible but less distracting when the list is collapsed.
        float minAlphaWhenClosed = 0.22f;
        float maxAlphaWhenOpened = 0.55f;
        buttonToggleList.setAlpha(minAlphaWhenClosed + (maxAlphaWhenOpened - minAlphaWhenClosed) * slideOffset);
        if (slideOffset > 0.5f) {
            buttonToggleList.setImageResource(android.R.drawable.ic_media_next);
            buttonToggleList.setContentDescription(getString(R.string.hide_file_list));
        } else {
            buttonToggleList.setImageResource(android.R.drawable.ic_media_previous);
            buttonToggleList.setContentDescription(getString(R.string.show_file_list));
        }
    }

    private boolean isListDrawerOpen() {
        return drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START);
    }

    private void toggleListDrawer() {
        if (isListDrawerOpen()) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            drawerLayout.openDrawer(GravityCompat.START);
        }
    }

    private void loadVideoList() {
        File dir = new File(AppConfig.VIDEO_DIR_PATH);
        if (!dir.exists() || !dir.isDirectory()) {
            videos.clear();
            adapter.submit(videos);
            PlayerLogger.e("LoadList", "video dir missing: " + AppConfig.VIDEO_DIR_PATH, null);
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

        if (videos.isEmpty()) {
            PlayerLogger.w("LoadList", "no video found in " + AppConfig.VIDEO_DIR_PATH);
            Toast.makeText(this, R.string.status_no_video_found, Toast.LENGTH_LONG).show();
        } else {
            PlayerLogger.i("LoadList", "found videos=" + videos.size());
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
        if (surface == null) {
            PlayerLogger.w("Play", "surface not ready, file=" + file.getAbsolutePath());
            Toast.makeText(this, R.string.surface_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }

        PlayerLogger.i("Play", "select file=" + file.getAbsolutePath());
        currentVideoFile = file;
        resumePositionMs = 0;
        resumeWhenReady = true;
        playCountedForCurrentFile = false;
        createAndPreparePlayer(file, 0, true);
    }

    private void pauseByUser() {
        if (mediaPlayer == null || !playerPrepared || !mediaPlayer.isPlaying()) {
            return;
        }
        mediaPlayer.pause();
        cancelAutoHideList();
        updateScreenAwakeState(false);
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
            if (pendingSeekPositionMs > 0) {
                pendingStartAfterSeek = true;
                hidePlayOverlay();
                return;
            }
            try {
                startPlaybackSafely();
            } catch (IllegalStateException e) {
                handlePlayerFailure();
                return;
            }
        }
    }

    private boolean isPlayingNow() {
        return mediaPlayer != null && playerPrepared && mediaPlayer.isPlaying();
    }

    private void releasePlayer() {
        stopProgressUpdates();
        cancelAutoHideList();
        updateScreenAwakeState(false);
        playerPrepared = false;
        pendingSeekPositionMs = 0;
        pendingStartAfterSeek = false;
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
        if (videoContainer != null) {
            videoContainer.setBackgroundColor(0xFFFFFFFF);
        }
        if (surfaceView != null) {
            surfaceView.setVisibility(View.GONE);
        }
        if (imageThumbnail != null) {
            imageThumbnail.setVisibility(View.GONE);
        }
        if (animationView != null) {
            animationView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        PlayerLogger.i("Lifecycle", "onPause playing=" + isPlayingNow());
        updateScreenAwakeState(false);
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            resumePositionMs = safeCurrentPosition();
            // Back to home should pause and wait for explicit user tap to resume.
            resumeWhenReady = false;
            stopProgressUpdates();
            updatePlaybackProgress();
            showPlayOverlay();
        } else if (playerPrepared) {
            resumePositionMs = safeCurrentPosition();
            resumeWhenReady = false;
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
        PlayerLogger.i("Lifecycle", "onStop current=" + (currentVideoFile == null ? "null" : currentVideoFile.getName()));
        updateScreenAwakeState(false);
        if (mediaPlayer != null) {
            resumePositionMs = safeCurrentPosition();
        }
        resumeWhenReady = false;
        releasePlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PlayerLogger.i("Lifecycle", "onResume");
        attachDisplayIfReady();
        tryRestorePlaybackIfNeeded();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PlayerLogger.i("Lifecycle", "onDestroy");
        releasePlayer();
        progressHandler.removeCallbacks(clockRunnable);
        if (batteryReceiver != null) {
            unregisterReceiver(batteryReceiver);
        }
    }

    @Override
    public void onBackPressed() {
        if (isListDrawerOpen()) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        super.onBackPressed();
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
                PlayerLogger.w("Permission", "denied, finish activity");
                Toast.makeText(this, R.string.permission_required_exit, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }

        PlayerLogger.i("Permission", "granted by user");
        loadVideoList();
    }

    private void attachDisplayIfReady() {
        if (mediaPlayer == null || !playerPrepared || surface == null) {
            return;
        }
        try {
            mediaPlayer.setSurface(surface);
        } catch (IllegalStateException ignored) {
        }
    }

    private void refreshFrameIfPaused() {
        if (mediaPlayer == null || !playerPrepared || surface == null) {
            return;
        }
        try {
            if (!mediaPlayer.isPlaying()) {
                int pos = resumePositionMs > 0 ? resumePositionMs : safeCurrentPosition();
                mediaPlayer.seekTo(pos > 0 ? pos : 0);
                if (resumeWhenReady) {
                    resumeWhenReady = false;
                    mediaPlayer.start();
                    startProgressUpdates();
                    hidePlayOverlay();
                }
            }
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
        if (recoveringPlayer || currentVideoFile == null || surface == null || mediaPlayer != null) {
            return;
        }
        PlayerLogger.i("PlayRestore", "rebuild player file=" + currentVideoFile.getAbsolutePath()
                + " resumeMs=" + Math.max(0, resumePositionMs) + " autoStart=" + resumeWhenReady);
        createAndPreparePlayer(currentVideoFile, Math.max(0, resumePositionMs), resumeWhenReady);
    }

    private void createAndPreparePlayer(File file, int startPositionMs, boolean autoStart) {
        releasePlayer();
        resumeWhenReady = autoStart;
        recoveringPlayer = true;
        PlayerLogger.i("Prepare", "create player file=" + file.getAbsolutePath()
                + " startMs=" + startPositionMs + " autoStart=" + autoStart);
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setSurface(surface);
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.setOnVideoSizeChangedListener((mp, width, height) -> applyVideoAspect(width, height));
            mediaPlayer.setOnSeekCompleteListener(mp -> {
                pendingSeekPositionMs = 0;
                if (pendingStartAfterSeek) {
                    pendingStartAfterSeek = false;
                    startPlaybackSafely();
                } else {
                    updatePlaybackProgress();
                }
            });
            mediaPlayer.setOnPreparedListener(mp -> {
                recoveringPlayer = false;
                playerPrepared = true;
                currentVideoFile = file;
                PlayerLogger.i("Prepared", "durationMs=" + mp.getDuration()
                        + " size=" + mp.getVideoWidth() + "x" + mp.getVideoHeight()
                        + " startMs=" + startPositionMs + " autoStart=" + resumeWhenReady);
                buttonPlay.setEnabled(true);
                applyVideoAspect(mp.getVideoWidth(), mp.getVideoHeight());
                seekProgress.setMax(Math.max(1, mp.getDuration()));
                textTotalTime.setText(formatTime(mp.getDuration()));
                if (videoContainer != null) {
                    videoContainer.setBackgroundColor(0x00000000);
                }
                if (surfaceView != null) {
                    surfaceView.setVisibility(View.VISIBLE);
                }
                if (imageThumbnail != null) {
                    imageThumbnail.setVisibility(View.GONE);
                }
                if (animationView != null) {
                    animationView.setVisibility(View.GONE);
                }
                if (startPositionMs > 0) {
                    pendingSeekPositionMs = startPositionMs;
                    pendingStartAfterSeek = resumeWhenReady;
                    try {
                        mp.seekTo(startPositionMs);
                    } catch (IllegalStateException ignored) {
                        pendingSeekPositionMs = 0;
                        pendingStartAfterSeek = false;
                    }
                    if (!resumeWhenReady) {
                        showPlayOverlay();
                    }
                    return;
                }
                updatePlaybackProgress();
                if (resumeWhenReady) {
                    startPlaybackSafely();
                } else {
                    stopProgressUpdates();
                    showPlayOverlay();
                }
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                PlayerLogger.i("Completion", "complete file=" + (currentVideoFile == null ? "null" : currentVideoFile.getAbsolutePath()));
                stopProgressUpdates();
                cancelAutoHideList();
                updateScreenAwakeState(false);
                resumePositionMs = 0;
                resumeWhenReady = false;
                countPlayIfNeeded();
                // Release player so next tap on play rebuilds from scratch cleanly
                mp.reset();
                mp.release();
                mediaPlayer = null;
                playerPrepared = false;
                updatePlaybackProgress();
                showPlayOverlay();
                if (videoContainer != null) {
                    videoContainer.setBackgroundColor(0xFFFFFFFF);
                }
                if (surfaceView != null) {
                    surfaceView.setVisibility(View.GONE);
                }
                if (currentVideoFile != null && imageThumbnail != null) {
                    loadAndShowThumbnail(currentVideoFile.getAbsolutePath());
                }
                drawerLayout.openDrawer(GravityCompat.START);
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                PlayerLogger.e("MediaError", buildPlayerState("what=" + what + " extra=" + extra), null);
                handlePlayerFailure();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            recoveringPlayer = false;
            PlayerLogger.e("Prepare", "prepare failed file=" + file.getAbsolutePath(), e);
            Toast.makeText(this, R.string.status_player_error, Toast.LENGTH_SHORT).show();
            releasePlayer();
            showPlayOverlay();
        }
    }

    private void handlePlayerFailure() {
        PlayerLogger.w("Recover", "handle failure " + buildPlayerState("before_recover"));
        stopProgressUpdates();
        cancelAutoHideList();
        updateScreenAwakeState(false);
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

    private void startPlaybackSafely() {
        if (mediaPlayer == null || !playerPrepared) {
            return;
        }
        try {
            mediaPlayer.start();
            PlayerLogger.i("Start", buildPlayerState("start_ok"));
            scheduleAutoHideListAfterPlaybackStart();
            updateScreenAwakeState(true);
            startProgressUpdates();
            hidePlayOverlay();
            if (imageThumbnail != null) {
                imageThumbnail.setVisibility(View.GONE);
            }
            if (surfaceView != null) {
                surfaceView.setVisibility(View.VISIBLE);
            }
        } catch (IllegalStateException e) {
            PlayerLogger.e("Start", buildPlayerState("start_illegal_state"), e);
            handlePlayerFailure();
        }
    }

    private void updateScreenAwakeState(boolean keepOn) {
        if (keepScreenOnActive == keepOn) {
            return;
        }
        keepScreenOnActive = keepOn;
        if (keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        videoContainer.setKeepScreenOn(keepOn);
        surfaceView.setKeepScreenOn(keepOn);
    }

    private void scheduleAutoHideListAfterPlaybackStart() {
        progressHandler.removeCallbacks(autoHideListRunnable);
        progressHandler.postDelayed(autoHideListRunnable, AUTO_HIDE_LIST_DELAY_MS);
    }

    private void cancelAutoHideList() {
        progressHandler.removeCallbacks(autoHideListRunnable);
    }

    private void markListInteraction() {
        cancelAutoHideList();
        if (isPlayingNow()) {
            progressHandler.postDelayed(autoHideListRunnable, AUTO_HIDE_LIST_DELAY_MS);
        }
    }

    private String buildPlayerState(String prefix) {
        String filePath = currentVideoFile == null ? "null" : currentVideoFile.getAbsolutePath();
        int position = safeCurrentPosition();
        int duration = 0;
        if (mediaPlayer != null && playerPrepared) {
            try {
                duration = mediaPlayer.getDuration();
            } catch (IllegalStateException ignored) {
                duration = -1;
            }
        }
        return prefix
                + " file=" + filePath
                + " prepared=" + playerPrepared
                + " recovering=" + recoveringPlayer
                + " resumeWhenReady=" + resumeWhenReady
                + " positionMs=" + position
                + " durationMs=" + duration
                + " hasSurface=" + (surface != null);
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
            if (!playCountedForCurrentFile && duration > 0 && position >= duration * 0.9f) {
                countPlayIfNeeded();
            }
        } catch (IllegalStateException ignored) {
        }
    }

    private void countPlayIfNeeded() {
        if (!playCountedForCurrentFile) {
            playCountedForCurrentFile = true;
            totalPlayCount++;
            textTotalPlayCount.setText(String.valueOf(totalPlayCount));
        }
    }

    private void updateClock() {
        if (textClock == null) return;
        textClock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
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

    private void loadAndShowThumbnail(String videoPath) {
        new Thread(() -> {
            Bitmap thumbnail = extractVideoThumbnail(videoPath);
            if (thumbnail != null && imageThumbnail != null) {
                runOnUiThread(() -> {
                    imageThumbnail.setImageBitmap(thumbnail);
                    imageThumbnail.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }

    private Bitmap extractVideoThumbnail(String videoPath) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoPath);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = durationStr != null ? Long.parseLong(durationStr) : 0;
            long seekUs = durationMs > 0 ? (durationMs / 2) * 1000L : 0L;
            Bitmap frame = retriever.getFrameAtTime(seekUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                frame = retriever.getFrameAtTime();
            }
            return frame;
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }
}
