package com.shawn2012.exvideoplayer;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Stack;

import org.videolan.libvlc.IVLCVout;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.Media.Event;
import org.videolan.libvlc.util.AndroidUtil;
import org.videolan.libvlc.util.VLCUtil;
import org.videolan.vlc.media.MediaWrapper;
import org.videolan.vlc.media.MediaWrapperList;
import org.videolan.vlc.util.FileUtils;
import org.videolan.vlc.util.VLCInstance;
import org.videolan.vlc.util.VLCOptions;

import com.shawn2012.exvideoplayer.videoplay.VideoPlayerActivity;

import android.app.KeyguardManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

public class PlaybackService extends Service implements IVLCVout.Callback {

    private static final String LOG_TAG = "VLC/PlaybackService";

    private final IBinder mBinder = new LocalBinder();

    private MediaWrapperList mMediaList = new MediaWrapperList();

    private boolean mSeekable = false;
    private boolean mVideoBackground = false;

    final private ArrayList<Callback> mCallbacks = new ArrayList<Callback>();

    private MediaPlayer mMediaPlayer;

    // Index management
    /**
     * Stack of previously played indexes, used in shuffle mode
     */
    private Stack<Integer> mPrevious;
    private int mCurrentIndex; // Set to -1 if no media is currently loaded
    private int mPrevIndex; // Set to -1 if no previous media
    private int mNextIndex; // Set to -1 if no next media

    private boolean mHasAudioFocus = false;

    @Override
    public void onCreate() {
        super.onCreate();

        mMediaPlayer = newMediaPlayer();

        if (!VLCUtil.hasCompatibleCPU(getApplicationContext())) {
            stopSelf();
            return;
        }

        mCurrentIndex = -1;
        mPrevIndex = -1;
        mNextIndex = -1;
        mPrevious = new Stack<Integer>();
    }

    private MediaPlayer newMediaPlayer() {
        final MediaPlayer mp = new MediaPlayer(libVlc());
        mp.getVLCVout().addCallback(this);
        return mp;
    }

    public LibVLC libVlc() {
        return VLCInstance.get();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }

        return super.onStartCommand(intent, flags, startId);
    }

    public synchronized void addCallback(Callback cb) {
        if (!mCallbacks.contains(cb)) {
            mCallbacks.add(cb);
        }
    }

    public synchronized void removeCallback(Callback cb) {
        mCallbacks.remove(cb);
    }

    public long getTime() {
        return mMediaPlayer.getTime();
    }

    public long getLength() {
        return mMediaPlayer.getLength();
    }

    public void loadUri(Uri uri) {
        String path = uri.toString();
        if (TextUtils.equals(uri.getScheme(), "content")) {
            path = "file://" + FileUtils.getPathFromURI(uri);
        }
        loadLocation(path);
    }

    public void loadLocation(String mediaPath) {
        loadLocations(Collections.singletonList(mediaPath), 0);
    }

    /**
     * Loads a selection of files (a non-user-supplied collection of media) into
     * the primary or "currently playing" playlist.
     *
     * @param mediaPathList A list of locations to load
     * @param position The position to start playing at
     */
    public void loadLocations(List<String> mediaPathList, int position) {
        ArrayList<MediaWrapper> mediaList = new ArrayList<MediaWrapper>();

        for (String location : mediaPathList) {
            if (!validateLocation(location)) {
                Log.w(LOG_TAG, "Invalid location " + location);
                continue;
            }
            Log.v(LOG_TAG, "Creating on-the-fly Media object for " + location);
            MediaWrapper mediaWrapper = new MediaWrapper(Uri.parse(location));
            mediaList.add(mediaWrapper);
        }
        load(mediaList, position);
    }

    public void load(MediaWrapper media) {
        ArrayList<MediaWrapper> arrayList = new ArrayList<MediaWrapper>();
        arrayList.add(media);
        load(arrayList, 0);
    }

    public void load(List<MediaWrapper> mediaList, int position) {
        Log.v(LOG_TAG, "Loading position " + ((Integer) position).toString()
                + " in " + mediaList.toString());

        mMediaList.removeEventListener(mListEventListener);
        mMediaList.clear();

        MediaWrapperList currentMediaList = mMediaList;

        mPrevious.clear();

        for (MediaWrapper media : mediaList) {
            currentMediaList.add(media);
        }

        if (mMediaList.size() == 0) {
            Log.w(LOG_TAG, "Warning: empty media list, nothing to play !");
            return;
        }

        if (mMediaList.size() > position && position >= 0) {
            mCurrentIndex = position;
        } else {
            Log.w(LOG_TAG, "Warning: positon " + position + " out of bounds");
            mCurrentIndex = 0;
        }

        // Add handler after loading the list
        mMediaList.addEventListener(mListEventListener);

        playIndex(mCurrentIndex, 0);

        onMediaChanged();
    }

    /**
     * Use this function to play a media inside whatever MediaList LibVLC is
     * following.
     *
     * Unlike load(), it does not import anything into the primary list.
     */
    public void playIndex(int index) {
        playIndex(index, 0);
    }

    /**
     * Play a media from the media list (playlist)
     *
     * @param index The index of the media
     * @param flags LibVLC.MEDIA_* flags
     */
    public void playIndex(int index, int flags) {
        if (mMediaList.size() == 0) {
            Log.w(LOG_TAG, "Warning: empty media list, nothing to play !");
            return;
        }

        if (index >= 0 && index < mMediaList.size()) {
            mCurrentIndex = index;
        } else {
            Log.w(LOG_TAG, "Warning: index " + index + " out of bounds");
            mCurrentIndex = 0;
        }

        String mrl = mMediaList.getMRL(index);
        if (mrl == null) {
            return;
        }

        final MediaWrapper mw = mMediaList.getMedia(index);
        if (mw == null) {
            return;
        }

        boolean isVideoPlaying = isVideoPlaying();
        if (!mVideoBackground && mw.getType() == MediaWrapper.TYPE_VIDEO
                && isVideoPlaying) {
            mw.addFlags(MediaWrapper.MEDIA_VIDEO);
        }
//        if (!mVideoBackground && mw.getType() == MediaWrapper.TYPE_VIDEO) {
//            mw.addFlags(MediaWrapper.MEDIA_VIDEO);
//        }

        if (mVideoBackground) {
            mw.addFlags(MediaWrapper.MEDIA_FORCE_AUDIO);
        }

        mSeekable = true;

        final Media media = new Media(libVlc(), mw.getUri());
        VLCOptions.setMediaOptions(media, this, flags | mw.getFlags());

        media.setEventListener(mMediaListener);
        mMediaPlayer.setMedia(media);
        media.release();

        if (mw.getType() != MediaWrapper.TYPE_VIDEO
                || mw.hasFlag(MediaWrapper.MEDIA_FORCE_AUDIO)
                || isVideoPlaying) {
            changeAudioFocus(true);
            mMediaPlayer.setEventListener(mMediaPlayerEventListener);
            mMediaPlayer.play();

            determinePrevAndNextIndices();
        } else {
            VideoPlayerActivity.startOpened(getApplicationContext(),
                    getCurrentMediaWrapper().getUri(), mCurrentIndex);
        }
    }

    private void onMediaChanged() {
        determinePrevAndNextIndices();
    }

    private void onMediaListChanged() {
        determinePrevAndNextIndices();
    }

    public void next() {
        int size = mMediaList.size();
        
        mPrevious.push(mCurrentIndex);
        mCurrentIndex = mNextIndex;
        
        if (size == 0 || mCurrentIndex < 0 || mCurrentIndex >= size) {
            Log.w(LOG_TAG, "Warning: invalid next index, aborted !");
            // Close video player if started
            stop();
            return;
        }
        playIndex(mCurrentIndex, 0);
    }

    public void previous() {
        if (hasPrevious() && mCurrentIndex > 0 && 
                (!mMediaPlayer.isSeekable() || mMediaPlayer.getTime() < 2000l)) {
            int size = mMediaList.size();
            mCurrentIndex = mPrevIndex;
            if (mPrevious.size() > 0) {
                mPrevious.pop();
            }
            
            if (size == 0 || mPrevIndex < 0 || mCurrentIndex >= size) {
                Log.w(LOG_TAG, "Warning: invalid previous index, aborted !");
                stop();
                return;
            }
            
            playIndex(mCurrentIndex, 0);
        } else {
            setPosition(0f);
        }
    }
    
    public void shuffle() {
        // TODO
    }

    public void seek(long position, double length) {
        if (length > 0.0D) {
            setPosition((float) (position / length));
        } else {
            setTime(position);
        }
    }

    public void setPosition(float pos) {
        if (mSeekable) {
            mMediaPlayer.setPosition(pos);
        }
    }

    public int getAudioTracksCount() {
        return mMediaPlayer.getAudioTracksCount();
    }

    public MediaPlayer.TrackDescription[] getAudioTracks() {
        return mMediaPlayer.getAudioTracks();
    }
    
    public int getAudioTrack() {
        return mMediaPlayer.getAudioTrack();
    }
    
    public boolean setAudioTrack(int index) {
        return mMediaPlayer.setAudioTrack(index);
    }
    
    public int getVideoTracksCount() {
        return mMediaPlayer.getVideoTracksCount();
    }

    public void play() {
        if (hasCurrentMedia()) {
            mMediaPlayer.play();
        }
    }

    public void pause() {
        mMediaPlayer.pause();
    }

    public void stop() {
        stopPlayback();
        stopSelf();
    }

    private void determinePrevAndNextIndices() {
        determinePrevAndNextIndices(false);
    }

    private void determinePrevAndNextIndices(boolean expand) {

    }

    public void stopPlayback() {
        if (mMediaPlayer == null) {
            return;
        }

        final Media media = mMediaPlayer.getMedia();
        if (media != null) {
            media.setEventListener(null);
            mMediaPlayer.setEventListener(null);
            mMediaPlayer.stop();
            mMediaPlayer.setMedia(null);
            media.release();
        }
        mMediaList.removeEventListener(mListEventListener);
        mCurrentIndex = -1;
        changeAudioFocus(false);
    }

    public boolean hasMedia() {
        return hasCurrentMedia();
    }

    public boolean hasPlaylist() {
        return getMediaListSize() > 1;
    }

    public boolean isVideoPlaying() {
        return mMediaPlayer.getVLCVout().areViewsAttached();
    }

    public IVLCVout getVLCVout() {
        return mMediaPlayer.getVLCVout();
    }

    private final OnAudioFocusChangeListener mAudioFocusListener = AndroidUtil
            .isFroyoOrLater() ? createOnAudioFocusChangeListener() : null;

    private OnAudioFocusChangeListener createOnAudioFocusChangeListener() {
        return new OnAudioFocusChangeListener() {
            private boolean mLossTransient = false;
            private int mLossTransientVolume = -1;
            private boolean wasPlaying = false;

            @Override
            public void onAudioFocusChange(int focusChange) {
                /*
                 * Pause playback during alerts and notifications
                 */
                switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    Log.i(LOG_TAG, "AUDIOFOCUS_LOSS");
                    // Pause playback
                    changeAudioFocus(false);
                    pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    Log.i(LOG_TAG, "AUDIOFOCUS_LOSS_TRANSIENT");
                    // Pause playback
                    mLossTransient = true;
                    wasPlaying = isPlaying();
                    if (wasPlaying) {
                        pause();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    Log.i(LOG_TAG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK");
                    // Lower the volume
                    if (mMediaPlayer.isPlaying()) {
                        mLossTransientVolume = mMediaPlayer.getVolume();
                        mMediaPlayer.setVolume(36);
                    }
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    Log.i(LOG_TAG, "AUDIOFOCUS_GAIN: " + mLossTransientVolume
                            + ", " + mLossTransient);
                    // Resume playback
                    if (mLossTransientVolume != -1) {
                        mMediaPlayer.setVolume(mLossTransientVolume);
                        mLossTransientVolume = -1;
                    } else if (mLossTransient) {
                        if (wasPlaying) {
                            mMediaPlayer.play();
                        }
                        mLossTransient = false;
                    }
                    break;
                }
            }
        };
    }

    private void changeAudioFocus(boolean acquire) {
        final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) {
            return;
        }

        if (acquire) {
            if (!mHasAudioFocus) {
                final int result = am.requestAudioFocus(mAudioFocusListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    am.setParameters("bgm_state=true");
                    mHasAudioFocus = true;
                }
            }
        } else {
            if (mHasAudioFocus) {
                final int result = am.abandonAudioFocus(mAudioFocusListener);
                am.setParameters("bgm_state=false");
                mHasAudioFocus = false;
            }
        }
    }

    public void setVideoTrackEnabled(boolean enabled) {
//        if (!hasMedia() || !isPlaying()) {
//            return;
//        }
        if (!hasMedia()) {
            return;
        }

        if (enabled) {
            getCurrentMedia().addFlags(MediaWrapper.MEDIA_VIDEO);
        } else {
            getCurrentMedia().removeFlags(MediaWrapper.MEDIA_VIDEO);
        }

        mMediaPlayer.setVideoTrackEnabled(enabled);
    }

    /**
     * Return the current media.
     *
     * @return The current media or null if there is not any.
     */
    private MediaWrapper getCurrentMedia() {
        return mMediaList.getMedia(mCurrentIndex);
    }

    /**
     * Alias for mCurrentIndex >= 0
     *
     * @return True if a media is currently loaded, false otherwise
     */
    private boolean hasCurrentMedia() {
        return mCurrentIndex >= 0 && mCurrentIndex < mMediaList.size();
    }

    public boolean isPlaying() {
        return mMediaPlayer.isPlaying();
    }

    public void append(MediaWrapper media) {
        ArrayList<MediaWrapper> arrayList = new ArrayList<MediaWrapper>();
        arrayList.add(media);
        append(arrayList);
    }

    /**
     * Append to the current existing playlist
     */
    public void append(List<MediaWrapper> mediaList) {
        if (!hasCurrentMedia()) {
            load(mediaList, 0);
            return;
        }

        for (MediaWrapper mw : mediaList) {
            mMediaList.add(mw);
        }

        onMediaListChanged();
    }

    /**
     * Move an item inside the playlist.
     */
    public void moveItem(int positionStart, int positionEnd) {
        mMediaList.move(positionStart, positionEnd);
    }

    public void insertItem(int position, MediaWrapper mw) {
        mMediaList.insert(position, mw);
        determinePrevAndNextIndices();
    }

    public void remove(int position) {
        mMediaList.remove(position);
        determinePrevAndNextIndices();
    }

    public void removeLocation(String location) {
        mMediaList.remove(location);
        determinePrevAndNextIndices();
    }

    public int getMediaListSize() {
        return mMediaList.size();
    }

    public List<MediaWrapper> getMedias() {
        final ArrayList<MediaWrapper> ml = new ArrayList<MediaWrapper>();
        for (int i = 0; i < mMediaList.size(); i++) {
            ml.add(mMediaList.getMedia(i));
        }
        return ml;
    }

    public List<String> getMediaLocations() {
        ArrayList<String> medias = new ArrayList<String>();
        for (int i = 0; i < mMediaList.size(); i++) {
            medias.add(mMediaList.getMRL(i));
        }
        return medias;
    }

    public String getCurrentMediaLocation() {
        return mMediaList.getMRL(mCurrentIndex);
    }

    public int getCurrentMediaPosition() {
        return mCurrentIndex;
    }

    public MediaWrapper getCurrentMediaWrapper() {
        return PlaybackService.this.getCurrentMedia();
    }

    public void setTime(long time) {
        if (mSeekable) {
            mMediaPlayer.setTime(time);
        }
    }

    public boolean hasNext() {
        return mNextIndex != -1;
    }

    public boolean hasPrevious() {
        return mPrevIndex != -1;
    }

    public float getRate() {
        return mMediaPlayer.getRate();
    }

    private boolean validateLocation(String location) {
        /* Check if the MRL contains a scheme */
        if (!location.matches("\\w+://.+"))
            location = "file://".concat(location);
        if (location.toLowerCase(Locale.ENGLISH).startsWith("file://")) {
            /* Ensure the file exists */
            File f;
            try {
                f = new File(new URI(location));
            } catch (URISyntaxException e) {
                return false;
            } catch (IllegalArgumentException e) {
                return false;
            }
            if (!f.isFile())
                return false;
        }
        return true;
    }

    private final MediaPlayer.EventListener mMediaPlayerEventListener = new MediaPlayer.EventListener() {

        KeyguardManager keyguardManager = (KeyguardManager) VideoPlayerApp.getAppContext().getSystemService(Context.KEYGUARD_SERVICE);

        @Override
        public void onEvent(org.videolan.libvlc.MediaPlayer.Event event) {
            // TODO Auto-generated method stub
            
        }
    };

    private final Media.EventListener mMediaListener = new Media.EventListener() {

        @Override
        public void onEvent(Event event) {
            // TODO Auto-generated method stub

        }
    };

    private final MediaWrapperList.EventListener mListEventListener = new MediaWrapperList.EventListener() {

        @Override
        public void onItemRemoved(int index, String mrl) {
            Log.i(LOG_TAG, "CustomMediaListItemDeleted");
            if (mCurrentIndex == index) {
                // The current item has been deleted
                mCurrentIndex--;
                determinePrevAndNextIndices();
                if (mNextIndex != -1) {
                    next();
                } else if (mCurrentIndex != -1) {
                    playIndex(mCurrentIndex, 0);
                } else {
                    stop();
                }
            }

            if (mCurrentIndex > index) {
                mCurrentIndex--;
            }
            determinePrevAndNextIndices();
        }

        @Override
        public void onItemMoved(int indexBefore, int indexAfter, String mrl) {
            Log.i(LOG_TAG, "CustomMediaListItemMoved");
            if (mCurrentIndex == indexBefore) {
                mCurrentIndex = indexAfter;
                if (indexAfter > indexBefore) {
                    mCurrentIndex--;
                }
            } else if (indexBefore > mCurrentIndex
                    && indexAfter <= mCurrentIndex) {
                mCurrentIndex++;
            } else
                if (indexBefore < mCurrentIndex && indexAfter > mCurrentIndex) {
                mCurrentIndex--;
            }

            // If we are in random mode, we completely reset the stored previous
            // track
            // as their indices changed.
            mPrevious.clear();

            determinePrevAndNextIndices();
        }

        @Override
        public void onItemAdded(int index, String mrl) {
            Log.i(LOG_TAG, "CustomMediaListItemAdded");
            if (mCurrentIndex >= index) {
                mCurrentIndex++;
            }

            determinePrevAndNextIndices();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (!hasCurrentMedia()) {
            stopSelf();
        }
        return true;
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

    public interface Callback {
        void update();

        void updateProgress();

        void onMediaEvent(Media.Event event);

        void onMediaPlayerEvent(MediaPlayer.Event event);
    }

    private class LocalBinder extends Binder {
        PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    public static PlaybackService getService(IBinder binder) {
        LocalBinder localBinder = (LocalBinder) binder;
        return localBinder.getService();
    }

    @Override
    public void onDestroy() {
        // TODO Auto-generated method stub
        super.onDestroy();
    }

    public static class Client {
        public static final String LOG_TAG = "PlaybackService.Client";

        public interface Callback {
            void onConnected(PlaybackService service);

            void onDisconnected();

        }

        private boolean mBound = false;
        private final Callback mCallback;
        private final Context mContext;

        private final ServiceConnection mServiceConnection = new ServiceConnection() {

            @Override
            public void onServiceDisconnected(ComponentName name) {
                mBound = false;
                mCallback.onDisconnected();
            }

            @Override
            public void onServiceConnected(ComponentName name,
                    IBinder iBinder) {
                if (!mBound) {
                    return;
                }

                final PlaybackService service = PlaybackService
                        .getService(iBinder);
                if (service != null) {
                    mCallback.onConnected(service);
                }
            }
        };

        private static Intent getServiceIntent(Context context) {
            return new Intent(context, PlaybackService.class);
        }

        private static void startService(Context context) {
            context.startService(getServiceIntent(context));
        }

        private static void stopService(Context context) {
            context.stopService(getServiceIntent(context));
        }

        public Client(Context context, Callback callback) {
            if (context == null || callback == null) {
                throw new IllegalArgumentException(
                        "Context and callback can't be null");
            }
            mContext = context;
            mCallback = callback;
        }

        public void connect() {
            if (mBound) {
                throw new IllegalStateException("already connected");
            }
            startService(mContext);
            mBound = mContext.bindService(getServiceIntent(mContext),
                    mServiceConnection, BIND_AUTO_CREATE);
        }

        public void disconnect() {
            if (mBound) {
                mBound = false;
                mContext.unbindService(mServiceConnection);
            }
        }

        public static void restartService(Context context) {
            stopService(context);
            startService(context);
        }
    }
}
