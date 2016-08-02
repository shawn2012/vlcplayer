package com.shawn2012.exvideoplayer.videoplay;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Locale;

import org.videolan.libvlc.IVLCVout;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media.Event;
import org.videolan.vlc.media.MediaWrapper;
import org.videolan.vlc.util.VLCInstance;
import com.shawn2012.exvideoplayer.PlaybackService;
import com.shawn2012.exvideoplayer.PlaybackServiceActivity;
import com.shawn2012.exvideoplayer.R;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public class VideoPlayerActivity extends AppCompatActivity
        implements IVLCVout.Callback, PlaybackService.Callback,
        PlaybackService.Client.Callback, OnClickListener {
    private static final String LOG_TAG = "VLC/VideoPlayActivity";

    public final static String PLAY_EXTRA_ITEM_LOCATION = "item_location";

    public final static String PLAY_EXTRA_OPENED_POSITION = "opened_position";

    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;

    private final PlaybackServiceActivity.Helper mHelper = new PlaybackServiceActivity.Helper(
            this, this);
    private PlaybackService mService;

    private Uri mUri;

    private boolean mIsPlaying = false;

    /**
     * Flag to indicate whether the media should be paused once loaded (e.g.
     * lock screen, or to restore the pause state)
     */
    private boolean mPlaybackStarted = false;

    boolean mWasPaused = false;

    private static final int OVERLAY_TIMEOUT = 4000;
    private static final int OVERLAY_INFINITE = -1;
    private static final int FADE_OUT = 1;
    private static final int SHOW_PROGRESS = 2;
    private static final int FADE_OUT_INFO = 3;
    private static final int START_PLAYBACK = 4;
    private static final int AUDIO_SERVICE_CONNECTION_FAILED = 5;
    private static final int RESET_BACK_LOCK = 6;
    private static final int CHECK_VIDEO_TRACKS = 7;
    private static final int LOADING_ANIMATION = 8;
    
    private static final int SHOW_PROGRESS_UI = 9;
    private static final int HIDE_OVERLAY = 10;
    
    private RelativeLayout mTitleLayout;
    private LinearLayout mBottomOverlayLayout;
    
    private TextView mTitleView;
    private TextView mDurationView;
    private TextView mTimeView;
    
    private SurfaceView mSubTitleSurface;
    
    private SeekBar mSeekBar;
    private ImageButton mLockBtn;
    private ImageButton mPlayPauseBtn;
    private ImageButton mForwardBtn;
    private ImageButton mBackwardBtn;
    private ImageButton mSizeBtn;
    
    private LinearLayout mLoaddingLayout;
    private TextView mBufferView;

    public static void startOpened(Context context, Uri uri,
            int openedPosition) {
        start(context, uri, null, false, openedPosition);
    }

    public static void start(Context context, Uri uri) {
        start(context, uri, null, false, -1);
    }

    public static void start(Context context, Uri uri, boolean fromStart) {
        start(context, uri, null, fromStart, -1);
    }

    public static void start(Context context, Uri uri, String title) {
        start(context, uri, title, false, -1);
    }

    private static void start(Context context, Uri uri, String title,
            boolean fromStart, int openedPosition) {
        Intent intent = getIntent(context, uri);

        context.startActivity(intent);
    }

    public static Intent getIntent(Context context, Uri uri) {
        Intent intent = new Intent(context, VideoPlayerActivity.class);
        intent.putExtra(PLAY_EXTRA_ITEM_LOCATION, uri);

        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getSupportActionBar().hide();

        setContentView(R.layout.activity_video_play);

        mSurfaceView = (SurfaceView) findViewById(R.id.video_view);
        mSurfaceHolder = mSurfaceView.getHolder();
        
        initViews();
    }

    private void initViews() {
        mTitleLayout = (RelativeLayout) findViewById(R.id.player_title_layout);
        mBottomOverlayLayout = (LinearLayout) findViewById(R.id.player_bottom_overlay_layout);
        
        mTitleView = (TextView) findViewById(R.id.player_title);
        mTimeView = (TextView) findViewById(R.id.player_time);
        mDurationView = (TextView) findViewById(R.id.player_duration);
        
        mSeekBar = (SeekBar) findViewById(R.id.player_seekbar);
        
        mLockBtn = (ImageButton) findViewById(R.id.player_lock);
        mLockBtn.setOnClickListener(this);
        mBackwardBtn = (ImageButton) findViewById(R.id.player_backward);
        mBackwardBtn.setOnClickListener(this);
        mPlayPauseBtn = (ImageButton) findViewById(R.id.player_play_pause);
        mPlayPauseBtn.setOnClickListener(this);
        mForwardBtn = (ImageButton) findViewById(R.id.player_forward);
        mForwardBtn.setOnClickListener(this);
        mSizeBtn = (ImageButton) findViewById(R.id.player_size);
        mSizeBtn.setOnClickListener(this);
        
        mLoaddingLayout = (LinearLayout) findViewById(R.id.player_loading_layout);
        mBufferView = (TextView) findViewById(R.id.player_buffer);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
        case R.id.player_lock:
            
            break;
        case R.id.player_backward:
            onBackwardClicked();
            break;
        case R.id.player_play_pause:
            onPlayPauseClicked();
            break;
        case R.id.player_forward:
            onForwardClicked();
            break;
        case R.id.player_size:
            
            break;
        }
    }

    private void onBackwardClicked() {
        seek(-10000);
    }

    private void seek(int delta) {
        long length = mService.getLength();
        if (length < 0) {
            return;
        }

        long position = mService.getTime() + delta;
        if (position < 0) {
            position = 0;
        } else if (position > mService.getLength()) {
            position = mService.getLength();
        }

        mService.setTime(position);
    }

    private void onPlayPauseClicked() {
        if (mService.isPlaying()) {
            mService.pause();
            mPlayPauseBtn.setBackgroundResource(R.drawable.ic_play);
        } else {
            mService.play();
            mPlayPauseBtn.setBackgroundResource(R.drawable.ic_pause);
        }
    }

    private void onForwardClicked() {
        seek(10000);
    }

    private String millisToString(long millis, boolean text) {
        boolean negative = millis < 0;
        millis = java.lang.Math.abs(millis);
        int mini_sec = (int) millis % 1000;
        millis /= 1000;
        int sec = (int) (millis % 60);
        millis /= 60;
        int min = (int) (millis % 60);
        millis /= 60;
        int hours = (int) millis;

        String time;
        DecimalFormat format = (DecimalFormat) NumberFormat.getInstance(Locale.US);
        format.applyPattern("00");

        DecimalFormat format2 = (DecimalFormat) NumberFormat.getInstance(Locale.US);
        format2.applyPattern("000");
        if (text) {
            if (millis > 0)
                time = (negative ? "-" : "") + hours + "h" + format.format(min) + "min";
            else if (min > 0)
                time = (negative ? "-" : "") + min + "min";
            else
                time = (negative ? "-" : "") + sec + "s";
        } else {
            if (millis > 0)
                time = (negative ? "-" : "") + hours + ":" + format.format(min) + ":" + format.format(sec) + ":" + format2.format(mini_sec);
            else
                time = (negative ? "-" : "") + min + ":" + format.format(sec) + ":" + format2.format(mini_sec);
        }
        return time;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            if (mBottomOverlayLayout.getVisibility() != View.VISIBLE) {
                showOverlay();
            } else {
                hideOverlay();
            }
        }
        return false;
    }

    private void showOverlay() {
        mTitleLayout.setVisibility(View.VISIBLE);
        mBottomOverlayLayout.setVisibility(View.VISIBLE);
        mHandler.sendEmptyMessage(SHOW_PROGRESS_UI);
        mHandler.removeMessages(HIDE_OVERLAY);
        mHandler.sendEmptyMessageDelayed(HIDE_OVERLAY, 5 * 1000);
    }

    private void hideOverlay() {
        mTitleLayout.setVisibility(View.GONE);
        mBottomOverlayLayout.setVisibility(View.GONE);
        mHandler.removeMessages(SHOW_PROGRESS_UI);
    }

    private void showLoading() {
        mLoaddingLayout.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        mLoaddingLayout.setVisibility(View.GONE);
    }

    private LibVLC libVlc() {
        return VLCInstance.get();
    }

    private void loadMedia() {
        if (mService == null) {
            return;
        }

        mUri = null;
        mIsPlaying = false;

        int positionInPlaylist = -1;

        Intent intent = getIntent();
        if (intent.getData() != null) {
            mUri = intent.getData();
        }

        Bundle extras = intent.getExtras();
        if (extras != null) {
            if (intent.hasExtra(PLAY_EXTRA_ITEM_LOCATION)) {
                mUri = extras.getParcelable(PLAY_EXTRA_ITEM_LOCATION);
            }

            positionInPlaylist = extras.getInt(PLAY_EXTRA_OPENED_POSITION, -1);
        }

        if (positionInPlaylist != -1 && mService.hasMedia()) {
            MediaWrapper openedMedia = mService.getMedias()
                    .get(positionInPlaylist);
            if (openedMedia == null) {
                return;
            }

            mUri = openedMedia.getUri();
        }

        if (mUri != null) {
            if (mService.hasMedia() && !mUri
                    .equals(mService.getCurrentMediaWrapper().getUri())) {
                mService.stop();
            }

            MediaWrapper media = null;

            // Start playback & seek
            mService.addCallback(this);
            /* prepare playback */
            boolean hasMedia = mService.hasMedia();
            if (hasMedia) {
                media = mService.getCurrentMediaWrapper();
            } else if (media == null) {
                media = new MediaWrapper(mUri);
            }

            media.removeFlags(MediaWrapper.MEDIA_FORCE_AUDIO);
            media.addFlags(MediaWrapper.MEDIA_VIDEO);

            // Handle playback
            if (!hasMedia) {
                mService.load(media);
            } else if (!mService.isPlaying()) {
                mService.playIndex(
                        positionInPlaylist != -1 ? positionInPlaylist : 0);
            }
        } else {
            // TODO
        }
    }

    @Override
    protected void onResume() {
        // TODO Auto-generated method stub
        super.onResume();

        startPlayback();
    }

    private void startPlayback() {
        /* start playback only when audio service and both surfaces are ready */
        if (mPlaybackStarted || mService == null) {
            return;
        }

        mPlaybackStarted = true;

        final IVLCVout vlcVout = mService.getVLCVout();

        vlcVout.detachViews();
        vlcVout.setVideoView(mSurfaceView);
        vlcVout.addCallback(this);
        vlcVout.attachViews();

        mService.setVideoTrackEnabled(true);

        loadMedia();

        mSurfaceView.setKeepScreenOn(true);

        boolean seekable = mService.isSeekable();
        if (seekable) {
            mBackwardBtn.setVisibility(View.VISIBLE);
            mForwardBtn.setVisibility(View.VISIBLE);
        }
    }

    private void stopPlayback() {
        if (!mPlaybackStarted) {
            return;
        }

        mWasPaused = !mService.isPlaying();

        mPlaybackStarted = false;

        mService.setVideoTrackEnabled(false);
        mService.removeCallback(this);

        mHandler.removeCallbacksAndMessages(null);

        final IVLCVout vlcVout = mService.getVLCVout();
        vlcVout.removeCallback(this);
        vlcVout.detachViews();

        if (isFinishing()) {
            mService.stop();
        } else {
            mService.pause();
        }
        
        mSurfaceView.setKeepScreenOn(false);
    }

    @Override
    protected void onStart() {
        super.onStart();

        mHelper.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();

        stopPlayback();

        if (mService != null) {
            mService.removeCallback(this);
        }

        mHelper.onStop();
    }

    @Override
    public void onNewLayout(IVLCVout vlcVout, int width, int height,
            int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onSurfacesCreated(IVLCVout vlcVout) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onSurfacesDestroyed(IVLCVout vlcVout) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onHardwareAccelerationError(IVLCVout vlcVout) {
        // TODO Auto-generated method stub

    }

    @Override
    public void update() {
        // TODO Auto-generated method stub

    }

    @Override
    public void updateProgress() {
        // TODO Auto-generated method stub

    }

    @Override
    public void onMediaEvent(Event event) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onMediaPlayerEvent(
            org.videolan.libvlc.MediaPlayer.Event event) {
        // TODO Auto-generated method stub

    }

    private final Handler mHandler = new Handler(Looper.getMainLooper(),
            new Handler.Callback() {

                @Override
                public boolean handleMessage(Message msg) {
                    if (mService == null) {
                        return true;
                    }

                    switch (msg.what) {
                    case START_PLAYBACK:
                        startPlayback();
                        break;
                    }

                    return true;
                }
            });

    public PlaybackServiceActivity.Helper getHelper() {
        return mHelper;
    }

    @Override
    public void onConnected(PlaybackService service) {
        mService = service;
        mHandler.sendEmptyMessage(START_PLAYBACK);
    }

    @Override
    public void onDisconnected() {
        mService = null;
        mHandler.sendEmptyMessage(AUDIO_SERVICE_CONNECTION_FAILED);
    }
}
