package com.shawn2012.exvideoplayer;

import org.videolan.vlc.media.MediaUtils;

import com.shawn2012.exvideoplayer.videoplay.VideoPlayerActivity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btn = (Button) findViewById(R.id.btn);
        btn.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                MediaUtils.openStream(MainActivity.this,
                        "http://120.205.13.203:5000/nl.ts?id=CCTV8");
            }
        });
    }
}
