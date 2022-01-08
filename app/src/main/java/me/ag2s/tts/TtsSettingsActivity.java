package me.ag2s.tts;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import me.ag2s.tts.adapters.TtsActorAdapter;
import me.ag2s.tts.adapters.TtsStyleAdapter;
import me.ag2s.tts.services.Constants;
import me.ag2s.tts.services.TtsActorManger;
import me.ag2s.tts.services.TtsDictManger;
import me.ag2s.tts.services.TtsFormatManger;
import me.ag2s.tts.services.TtsOutputFormat;
import me.ag2s.tts.services.TtsStyle;
import me.ag2s.tts.services.TtsStyleManger;
import me.ag2s.tts.services.TtsVoiceSample;
import me.ag2s.tts.utils.ApkInstall;
import me.ag2s.tts.utils.HttpTool;

import static me.ag2s.tts.services.Constants.CUSTOM_VOICE;


public class TtsSettingsActivity extends Activity {
    private static final String TAG = "CheckVoiceData";
    private static final AtomicInteger mNextRequestId = new AtomicInteger(0);

    public SharedPreferences sharedPreferences;

    TextToSpeech textToSpeech;
    int styleDegree;
    int volumeValue;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedPreferences = getApplicationContext().getSharedPreferences("TTS", Context.MODE_PRIVATE);
        setContentView(R.layout.activity_tts_settings);
        RecyclerView gv = findViewById(R.id.gv);

        RecyclerView rv_styles = findViewById(R.id.rv_voice_styles);
        int styleIndex = sharedPreferences.getInt(Constants.VOICE_STYLE_INDEX, 0);

        styleDegree = sharedPreferences.getInt(Constants.VOICE_STYLE_DEGREE, 100);
        volumeValue = sharedPreferences.getInt(Constants.VOICE_VOLUME, 100);


        List<TtsStyle> styles = TtsStyleManger.getInstance().getStyles();
        TtsStyleAdapter rvadapter = new TtsStyleAdapter(styles);
        rvadapter.setSelect(styleIndex);
        rv_styles.setAdapter(rvadapter);
        LinearLayoutManager linearLayoutManager = new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false);
        rv_styles.setLayoutManager(linearLayoutManager);
        linearLayoutManager.scrollToPositionWithOffset(styleIndex, 0);
        rvadapter.setItemClickListener((position, item) -> {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(Constants.VOICE_STYLE, item.value);
            editor.putInt(Constants.VOICE_STYLE_INDEX, position);
            editor.apply();
        });

        textToSpeech = new TextToSpeech(TtsSettingsActivity.this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = textToSpeech.setLanguage(Locale.CHINA);
                if (result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                     Toast.makeText(this , "初始化成功。", Toast.LENGTH_SHORT).show();
                }
            }
        }, this.getPackageName());


        TtsActorAdapter adapter = new TtsActorAdapter(TtsActorManger.getInstance().getActors());
        gv.setAdapter(adapter);
        gv.setLayoutManager(new GridLayoutManager(this, 3));
        adapter.setSelect(gv, sharedPreferences.getInt(Constants.CUSTOM_VOICE_INDEX, 0));
        adapter.setItemClickListener((position, item) -> {
            boolean origin = sharedPreferences.getBoolean(Constants.USE_CUSTOM_VOICE, false);

            if (origin) {
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(CUSTOM_VOICE, item.getShortName());
                editor.putInt(Constants.CUSTOM_VOICE_INDEX, position);
                //adapter.setSelect(gv, position);
                editor.apply();
            }

            Locale locale = item.getLocale();

            if (!textToSpeech.isSpeaking()) {
                Bundle bundle = new Bundle();
                bundle.putString("voiceName", item.getShortName());
                bundle.putString("language", locale.getISO3Language());
                bundle.putString("country", locale.getISO3Country());
                bundle.putString("variant", item.getGender() ? "Female" : "Male");
                bundle.putString("utteranceId", "Sample");
                textToSpeech.speak(TtsVoiceSample.getByLocate(this, locale), TextToSpeech.QUEUE_FLUSH, bundle, TtsSettingsActivity.class.getName() + mNextRequestId.getAndIncrement());
            } else {
                Toast.makeText(TtsSettingsActivity.this, "" + item.getShortName(), Toast.LENGTH_SHORT).show();
            }

        });
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        textToSpeech.stop();
    }

}