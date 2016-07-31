package com.shawn2012.exvideoplayer.videoplay;

import org.videolan.libvlc.IVLCVout;
import org.videolan.libvlc.IVLCVout.Callback;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.Media.Event;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.vlc.media.MediaWrapper;
import org.videolan.vlc.util.VLCInstance;
import org.videolan.vlc.util.VLCOptions;

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
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class VideoPlayerActivity extends AppCompatActivity
        implements IVLCVout.Callback, PlaybackService.Callback,
        PlaybackService.Client.Callback {
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

        setContentView(R.layout.activity_video_play);

        mSurfaceView = (SurfaceView) findViewById(R.id.video_view);
        mSurfaceHolder = mSurfaceView.getHolder();
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
