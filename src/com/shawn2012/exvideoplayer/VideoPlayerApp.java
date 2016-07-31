package com.shawn2012.exvideoplayer;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import android.annotation.TargetApi;
import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;

public class VideoPlayerApp extends Application {

    private static VideoPlayerApp sInstance;

    private ThreadPoolExecutor mThreadPool = new ThreadPoolExecutor(0, 2, 2, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(), THREAD_FACTORY);

    public static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setPriority(android.os.Process.THREAD_PRIORITY_DEFAULT + android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);
            return thread;
        }
    };

    @Override
    public void onCreate() {
        // TODO Auto-generated method stub
        super.onCreate();

        sInstance = this;
    }

    @Override
    public void onLowMemory() {
        // TODO Auto-generated method stub
        super.onLowMemory();
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    @Override
    public void onTrimMemory(int level) {
        // TODO Auto-generated method stub
        super.onTrimMemory(level);
    }

    /**
     * @return the main context of the Application
     */
    public static Context getAppContext() {
        return sInstance;
    }

    /**
     * @return the main resources from the Application
     */
    public static Resources getAppResources() {
        return sInstance.getResources();
    }

    public static void runBackground(Runnable runnable) {
        sInstance.mThreadPool.execute(runnable);
    }

    public static boolean removeTask(Runnable runnable) {
        return sInstance.mThreadPool.remove(runnable);
    }
}
