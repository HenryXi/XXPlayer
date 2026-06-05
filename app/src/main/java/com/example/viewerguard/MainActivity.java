package com.example.viewerguard;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Point;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
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
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1001;
    private static final long AUTO_HIDE_LIST_DELAY_MS = 3000L;
    private static final List<String> SUPPORTED_EXTENSIONS = Arrays.asList("mp4", "mkv", "avi", "3gp", "mov");

    private SurfaceView surfaceView;
    private FrameLayout videoContainer;
    private ImageButton buttonPlay;
    private ImageButton buttonToggleList;
    private ListView listVideos;
    private FrameLayout listDrawer;
    private DrawerLayout drawerLayout;
    private ProgressBar seekProgress;
    private TextView textCurrentTime;
    private TextView textTotalTime;

    private VideoFileAdapter adapter;
    private List<File> videos = new ArrayList<>();
    private final Map<String, String> videoMd5ByPath = new HashMap<>();
    private final Handler progressHandler = new Handler();

    private SurfaceHolder surfaceHolder;
    private MediaPlayer mediaPlayer;
    private boolean playerPrepared;
    private File currentVideoFile;
    private VideoStatsDbHelper dbHelper;
    private int resumePositionMs;
    private boolean resumeWhenReady;
    private boolean recoveringPlayer;
    private int pendingSeekPositionMs;
    private boolean pendingStartAfterSeek;
    private OrientationEventListener orientationListener;
    private int baselineLandscapeOrientation = -1;
    private float currentVideoRotation;
    private boolean keepScreenOnActive;
    private boolean listInteractionSincePlaybackStart;
    private final Runnable autoHideListRunnable = new Runnable() {
        @Override
        public void run() {
            if (!listInteractionSincePlaybackStart && isPlayingNow() && isListDrawerOpen()) {
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

        dbHelper = new VideoStatsDbHelper(this);

        bindViews();
        setupList();
        setupSurface();
        setupButtons();
        setupOrientationCorrection();

        if (hasRequiredPermissions()) {
            PlayerLogger.i("Permission", "granted at startup");
            loadVideoList();
        } else {
            PlayerLogger.w("Permission", "request required permissions");
            requestRequiredPermissions();
        }
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

        buttonPlay.setEnabled(false);
        buttonPlay.setVisibility(View.GONE);
        seekProgress.setMax(1000);
        seekProgress.setProgress(0);
        textCurrentTime.setText(formatTime(0));
        textTotalTime.setText(formatTime(0));
        resumePositionMs = 0;
        resumeWhenReady = false;
        recoveringPlayer = false;
        pendingSeekPositionMs = 0;
        pendingStartAfterSeek = false;
        currentVideoRotation = 0f;
        keepScreenOnActive = false;
        surfaceView.setRotation(currentVideoRotation);
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
        // Keep SurfaceView below normal UI layers so bottom progress area never overlays video content.
        surfaceView.setZOrderOnTop(false);
        surfaceView.setZOrderMediaOverlay(false);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                surfaceHolder = holder;
                PlayerLogger.i("Surface", "surfaceCreated");
                attachDisplayIfReady();
                tryRestorePlaybackIfNeeded();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                surfaceHolder = holder;
                PlayerLogger.i("Surface", "surfaceChanged width=" + width + " height=" + height);
                attachDisplayIfReady();
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                PlayerLogger.w("Surface", "surfaceDestroyed");
                if (mediaPlayer != null) {
                    try {
                        if (mediaPlayer.isPlaying()) {
                            mediaPlayer.pause();
                            resumePositionMs = safeCurrentPosition();
                            resumeWhenReady = false;
                            stopProgressUpdates();
                            updatePlaybackProgress();
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

        Map<String, Integer> countsByMd5 = dbHelper.queryAllPlayCounts();
        Map<String, Integer> countsByPath = new HashMap<>();
        videoMd5ByPath.clear();
        for (File file : videos) {
            String path = file.getAbsolutePath();
            String md5 = FileMd5Utils.md5(file);
            if (md5 != null) {
                videoMd5ByPath.put(path, md5);
                countsByPath.put(path, countsByMd5.getOrDefault(md5, 0));
            } else {
                countsByPath.put(path, 0);
            }
        }
        adapter.updatePlayCounts(countsByPath);

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
        if (surfaceHolder == null) {
            PlayerLogger.w("Play", "surface not ready, file=" + file.getAbsolutePath());
            Toast.makeText(this, R.string.surface_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }

        PlayerLogger.i("Play", "select file=" + file.getAbsolutePath());
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        PlayerLogger.i("Lifecycle", "onPause playing=" + isPlayingNow());
        updateScreenAwakeState(false);
        if (orientationListener != null) {
            orientationListener.disable();
        }
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
        if (orientationListener != null && orientationListener.canDetectOrientation()) {
            orientationListener.enable();
        }
        attachDisplayIfReady();
        tryRestorePlaybackIfNeeded();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PlayerLogger.i("Lifecycle", "onDestroy");
        if (orientationListener != null) {
            orientationListener.disable();
        }
        releasePlayer();
        if (dbHelper != null) {
            dbHelper.close();
        }
    }

    private void setupOrientationCorrection() {
        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) {
                    return;
                }
                int snapped = snapToRightAngle(orientation);
                if (snapped != 90 && snapped != 270) {
                    return;
                }
                if (baselineLandscapeOrientation == -1) {
                    baselineLandscapeOrientation = snapped;
                }
                float targetRotation = snapped == baselineLandscapeOrientation ? 0f : 180f;
                applyVideoRotation(targetRotation);
            }
        };
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable();
        }
    }

    private int snapToRightAngle(int orientation) {
        return ((orientation + 45) / 90 * 90) % 360;
    }

    private void applyVideoRotation(float targetRotation) {
        if (Float.compare(currentVideoRotation, targetRotation) == 0) {
            return;
        }
        currentVideoRotation = targetRotation;
        surfaceView.animate()
                .rotation(targetRotation)
                .setDuration(220)
                .start();
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
            mediaPlayer.setDisplay(surfaceHolder);
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
                updatePlaybackProgress();
                showPlayOverlay();
                if (currentVideoFile != null) {
                    String path = currentVideoFile.getAbsolutePath();
                    String md5 = videoMd5ByPath.get(path);
                    if (md5 == null) {
                        md5 = FileMd5Utils.md5(currentVideoFile);
                        if (md5 != null) {
                            videoMd5ByPath.put(path, md5);
                        }
                    }
                    if (md5 != null) {
                        int newCount = dbHelper.incrementPlayCount(md5, path, currentVideoFile.getName());
                        adapter.updateSinglePlayCount(path, newCount);
                    }
                }
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
        listInteractionSincePlaybackStart = false;
        progressHandler.removeCallbacks(autoHideListRunnable);
        progressHandler.postDelayed(autoHideListRunnable, AUTO_HIDE_LIST_DELAY_MS);
    }

    private void cancelAutoHideList() {
        progressHandler.removeCallbacks(autoHideListRunnable);
    }

    private void markListInteraction() {
        listInteractionSincePlaybackStart = true;
        cancelAutoHideList();
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
                + " hasSurface=" + (surfaceHolder != null);
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
