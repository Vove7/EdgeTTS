package me.ag2s.tts.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.text.TextUtils;
import android.util.Log;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import me.ag2s.tts.APP;
import me.ag2s.tts.utils.ByteArrayMediaDataSource;
import me.ag2s.tts.utils.CommonTool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.Buffer;
import okio.ByteString;

public class TTSService extends TextToSpeechService {


    private static final String TAG = TTSService.class.getSimpleName();


    public SharedPreferences sharedPreferences;
    private OkHttpClient client;
    private WebSocket webSocket;
    private volatile boolean isSynthesizing;
    //当前的生成格式
    private volatile TtsOutputFormat currentFormat;
    //当前的数据
    private Buffer mData;
    public static MediaCodec mediaCodec;
    public volatile String currentMime;


    private volatile String[] mCurrentLanguage = null;


    private int oldindex = 0;
    SynthesisCallback callback;

    @Override
    public void onCreate() {
        super.onCreate();
        client = APP.getOkHttpClient();
        sharedPreferences = getApplicationContext().getSharedPreferences("TTS", Context.MODE_PRIVATE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private final WebSocketListener webSocketListener = new WebSocketListener() {
        @Override
        public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            super.onClosed(webSocket, code, reason);
            Log.v(TAG, "onClosed" + reason);
            TTSService.this.webSocket = null;
            callback.done();
            isSynthesizing = false;
        }

        @Override
        public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            super.onClosing(webSocket, code, reason);

        }

        @Override
        public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
            super.onFailure(webSocket, t, response);
            Log.v(TAG, "onFailure", t);
            TTSService.this.webSocket = null;
            callback.done();
            isSynthesizing = false;

            if (sharedPreferences.getBoolean(Constants.USE_AUTO_RETRY, true)) {
                Log.d(TAG, "AAAA:使用自动重试。");
                TTSService.this.webSocket = getOrCreateWs();
            }

        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
            super.onMessage(webSocket, text);
            //Log.v(TAG, "onMessage"+text);
            String endTag = "turn.end";
            String startTag = "turn.start";
            int endIndex = text.lastIndexOf(endTag);
            int startIndex = text.lastIndexOf(startTag);
            //生成开始
            if (startIndex != -1) {
                isSynthesizing = true;
                mData = new Buffer();
            }
            //生成结束
            if (endIndex != -1) {
                if (callback != null && !callback.hasFinished()) {
                    if (!currentFormat.needDecode) {
                        callback.done();
                        isSynthesizing = false;
                    } else {
                        doDecode(callback, currentFormat, mData.readByteString());
                    }
                }
            }
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
            super.onMessage(webSocket, bytes);
            //音频数据流标志头
            String audioTag = "Path:audio\r\n";
            String startTag = "Content-Type:";
            String endTag = "\r\nX-StreamId";

            int audioIndex = bytes.lastIndexOf(audioTag.getBytes(StandardCharsets.UTF_8)) + audioTag.length();
            int startIndex = bytes.lastIndexOf(startTag.getBytes(StandardCharsets.UTF_8)) + startTag.length();
            int endIndex = bytes.lastIndexOf(endTag.getBytes(StandardCharsets.UTF_8));
            if (audioIndex != -1 && callback != null) {
                try {
                    currentMime = bytes.substring(startIndex, endIndex).utf8();
                    Log.d(TAG, "当前Mime:" + currentMime);
                    if (!currentFormat.needDecode) {
                        if (currentMime.equals("audio/x-wav") && bytes.lastIndexOf("RIFF".getBytes(StandardCharsets.UTF_8)) != -1) {
                            //去除WAV文件的文件头，解决播放开头时的杂音
                            audioIndex += 44;
                            Log.d(TAG, "移除WAV文件头");
                        }
                        doUnDecode(callback, currentFormat, bytes.substring(audioIndex));
                    } else {
                        mData.write(bytes.substring(audioIndex));
                    }

                } catch (Exception e) {
                    Log.d(TAG, "onMessage Error:", e);

                    //如果出错返回错误
                    callback.error();
                    isSynthesizing = false;
                }

            }
        }

        @Override
        public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
            super.onOpen(webSocket, response);
            Log.d(TAG, "onOpen" + response.headers().toString());
        }
    };


    private void doDecode(SynthesisCallback cb, @SuppressWarnings("unused") TtsOutputFormat format, ByteString data) {
        try {
            MediaExtractor mediaExtractor = new MediaExtractor();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                //在高版本上使用自定义MediaDataSource
                mediaExtractor.setDataSource(new ByteArrayMediaDataSource(data.toByteArray()));
            } else {
                //在低版本上使用Base64音频数据
                mediaExtractor.setDataSource("data:" + currentMime + ";base64," + data.base64());
            }
            //找到音频流的索引
            int audioTrackIndex = -1;
            String mime = null;
            MediaFormat trackFormat = null;
            for (int i = 0; i < mediaExtractor.getTrackCount(); i++) {
                trackFormat = mediaExtractor.getTrackFormat(i);
                mime = trackFormat.getString(MediaFormat.KEY_MIME);
                if (!TextUtils.isEmpty(mime) && mime.startsWith("audio")) {
                    audioTrackIndex = i;
                    Log.d(TAG, "找到音频流的索引为：" + audioTrackIndex);
                    Log.d(TAG, "找到音频流的索引为：" + mime);
                    break;
                }
            }
            //没有找到音频流的情况下
            if (audioTrackIndex == -1) {
                Log.e(TAG, "initAudioDecoder: 没有找到音频流");
                cb.done();
                isSynthesizing = false;
                return;
            }
            //opus的音频必须设置这个才能正确的解码
            if ("audio/opus".equals(mime)) {
                Log.d(TAG, data.substring(0, 4).utf8());
                Buffer buf = new Buffer();
                // Magic Signature：固定头，占8个字节，为字符串OpusHead
                buf.write("OpusHead".getBytes(StandardCharsets.UTF_8));
                // Version：版本号，占1字节，固定为0x01
                buf.writeByte(1);
                // Channel Count：通道数，占1字节，根据音频流通道自行设置，如0x02
                buf.writeByte(1);
                // Pre-skip：回放的时候从解码器中丢弃的samples数量，占2字节，为小端模式，默认设置0x00,
                buf.writeShortLe(0);
                // Input Sample Rate (Hz)：音频流的Sample Rate，占4字节，为小端模式，根据实际情况自行设置
                buf.writeIntLe(currentFormat.HZ);
                //Output Gain：输出增益，占2字节，为小端模式，没有用到默认设置0x00, 0x00就好
                buf.writeShortLe(0);
                // Channel Mapping Family：通道映射系列，占1字节，默认设置0x00就好
                buf.writeByte(0);
                //Channel Mapping Table：可选参数，上面的Family默认设置0x00的时候可忽略


                byte[] csd1bytes = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
                byte[] csd2bytes = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
                ByteString hd = buf.readByteString();
                Log.d(TAG, hd.hex());
                ByteBuffer csd0 = ByteBuffer.wrap(hd.toByteArray());
                trackFormat.setByteBuffer("csd-0", csd0);
                ByteBuffer csd1 = ByteBuffer.wrap(csd1bytes);
                trackFormat.setByteBuffer("csd-1", csd1);
                ByteBuffer csd2 = ByteBuffer.wrap(csd2bytes);
                trackFormat.setByteBuffer("csd-2", csd2);

            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "找到音频流的索引为：" + trackFormat.toString());
            }
            //选择此音轨
            mediaExtractor.selectTrack(audioTrackIndex);

            //创建解码器
            mediaCodec = MediaCodec.createDecoderByType(mime);

            mediaCodec.configure(trackFormat, null, null, 0);


            mediaCodec.start();

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            ByteBuffer inputBuffer;
            Log.d(TAG, mediaCodec.getInputFormat().toString());
            Log.d(TAG, mediaCodec.getOutputFormat().toString());
            while (mediaCodec != null) {
                //获取可用的inputBuffer，输入参数-1代表一直等到，0代表不等待，10*1000代表10秒超时
                //超时时间10秒
                long TIME_OUT_US = 10 * 1000;
                int inputIndex = mediaCodec.dequeueInputBuffer(TIME_OUT_US);
                if (inputIndex < 0) {
                    break;
                }
                inputBuffer = mediaCodec.getInputBuffer(inputIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                } else {
                    continue;
                }
                //从流中读取的采用数据的大小
                int sampleSize = mediaExtractor.readSampleData(inputBuffer, 0);
                if (sampleSize > 0) {
                    //入队解码
                    mediaCodec.queueInputBuffer(inputIndex, 0, sampleSize, 0, 0);
                    //移动到下一个采样点
                    mediaExtractor.advance();
                } else {
                    break;
                }

                //取解码后的数据
                int outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIME_OUT_US);
                //不一定能一次取完，所以要循环取
                ByteBuffer outputBuffer;
                byte[] pcmData;
                while (outputIndex >= 0) {
                    outputBuffer = mediaCodec.getOutputBuffer(outputIndex);
                    pcmData = new byte[bufferInfo.size];
                    if (outputBuffer != null) {
                        outputBuffer.get(pcmData);
                        outputBuffer.clear();//用完后清空，复用
                    }
                    cb.audioAvailable(pcmData, 0, bufferInfo.size);
                    //释放
                    mediaCodec.releaseOutputBuffer(outputIndex, false);
                    //再次获取数据
                    outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIME_OUT_US);
                }
            }
            cb.done();
            isSynthesizing = false;

        } catch (Exception e) {
            Log.e(TAG, "doDecode", e);
            cb.error();
            isSynthesizing = false;
        }
    }


    private void doUnDecode(SynthesisCallback cb, @SuppressWarnings("unused") TtsOutputFormat format, ByteString data) {
        int length = data.toByteArray().length;
        //最大BufferSize
        final int maxBufferSize = cb.getMaxBufferSize();
        int offset = 0;
        while (offset < length) {
            int bytesToWrite = Math.min(maxBufferSize, length - offset);
            Log.d(TAG, "cb.audioAvailable " + bytesToWrite);
            cb.audioAvailable(data.toByteArray(), offset, bytesToWrite);
            offset += bytesToWrite;
        }
    }


    public TTSService() {
    }

    /**
     * 获取或者创建WS
     * WebSocket
     *
     * @return WebSocket
     */
    public WebSocket getOrCreateWs() {
        if (this.webSocket != null) {
            boolean isSuccess = this.webSocket.send("");
            if (isSuccess) {
                return this.webSocket;
            }
        }

        String conId = UUID.randomUUID().toString().replace("-", "").toLowerCase();
        Request request = new Request.Builder()
                .url(Constants.EDGE_URL + "&ConnectionId=" + conId)
                .header("User-Agent", Constants.EDGE_UA)
                .addHeader("Origin", Constants.EDGE_ORIGIN)
                .build();
        this.webSocket = client.newWebSocket(request, webSocketListener);
        sendConfig(this.webSocket, new TtsConfig.Builder(sharedPreferences.getInt(Constants.AUDIO_FORMAT_INDEX, -1)).sentenceBoundaryEnabled(true).build());
        return webSocket;
    }
    //发送合成语音配置

    private void sendConfig(WebSocket ws, TtsConfig ttsConfig) {
        String msg = "X-Timestamp:+" + getTime() + "\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n"
                + ttsConfig.toString();
        this.currentFormat = ttsConfig.getFormat();
        ws.send(msg);
    }

    /**
     * 获取时间戳
     *
     * @return String time
     */
    public String getTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z (中国标准时间)", Locale.ENGLISH);
        Date date = new Date();
        return sdf.format(date);
    }

    /**
     * 发送合成text请求
     *
     * @param request 需要合成的txt
     */
    public void sendText(SynthesisRequest request, SynthesisCallback callback) {
        //设置发送的音质
        int index = sharedPreferences.getInt(Constants.AUDIO_FORMAT_INDEX, -1);
        TtsConfig ttsConfig = new TtsConfig.Builder(index).build();
        TtsOutputFormat format = ttsConfig.getFormat();
        currentFormat = format;


        //设置发送文本内容

        StringBuilder sb = new StringBuilder(request.getCharSequenceText());
        Log.d(TAG, "源：" + sb);
        CommonTool.replaceAll(sb, ">", "");
        CommonTool.replaceAll(sb, "<", "");
        //移除空格
        CommonTool.Trim(sb);
        //判断是否全是不发声字符，如果是，直接跳过
        if (CommonTool.isNoVoice(sb.toString())) {
            callback.start(format.HZ,
                    format.BitRate, 1 /* Number of channels. */);
            callback.done();
            isSynthesizing = false;
            return;
        }
        Log.d(TAG, "源2：" + sb);
        //校正发音
        List<TtsDict> dicts = TtsDictManger.getInstance().getDict();
        for (TtsDict dict : dicts) {
            CommonTool.replaceAll(sb, dict.getWorld(), dict.getXML());
        }

        int pitch = request.getPitch() - 100;
        int rate = request.getSpeechRate() - 100;
        Log.e(TAG, "速度: " + rate +", pitch: " + pitch);

        int volume = sharedPreferences.getInt(Constants.VOICE_VOLUME, 100);


        String style = sharedPreferences.getString(Constants.VOICE_STYLE, "cheerful");
        String styleDegreeString = CommonTool.div(sharedPreferences.getInt(Constants.VOICE_STYLE_DEGREE, 100), 100, 2) + "";

        String name = request.getVoiceName();
        String time = getTime();
        Locale locale = Locale.getDefault();
        //&& request.getLanguage().equals(locale.getISO3Language())
        if (sharedPreferences.getBoolean(Constants.USE_CUSTOM_VOICE, true)) {
            name = sharedPreferences.getString(Constants.CUSTOM_VOICE, "zh-CN-XiaoxiaoNeural");
        }

        String RequestId = CommonTool.getMD5String(sb.toString() + time + request.getCallerUid());


        String xml = locale.getLanguage() + "-" + locale.getCountry();
        String txt = CommonTool.getSSML(sb, RequestId, time, name, style, styleDegreeString, pitch, rate, volume, xml);
        callback.start(format.HZ,
                format.BitRate, 1 /* Number of channels. */);

        webSocket = getOrCreateWs();
        if (oldindex != index) {
            sendConfig(webSocket, ttsConfig);
            oldindex = index;
        }
        webSocket.send(txt);


    }


    public static int getIsLanguageAvailable(String lang, String country, String variant) {
        Locale locale = new Locale(lang, country, variant);
        boolean isLanguage = false;
        boolean isCountry = false;
        for (String lan : Constants.supportedLanguages) {
            String[] temp = lan.split("-");
            Locale locale1 = new Locale(temp[0], temp[1]);
            if (locale.getISO3Language().equals(locale1.getISO3Language())) {
                isLanguage = true;
            }
            if (isLanguage && locale.getISO3Country().equals(locale1.getISO3Country())) {
                isCountry = true;
            }
            if (isCountry && locale.getVariant().equals(locale1.getVariant())) {
                return TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE;
            }

        }
        if (isCountry) {
            return TextToSpeech.LANG_COUNTRY_AVAILABLE;
        }
        if (isLanguage) {
            return TextToSpeech.LANG_AVAILABLE;
        }
        return TextToSpeech.LANG_NOT_SUPPORTED;
    }


    /**
     * 是否支持该语言。语言通过lang、country、variant这三个Locale的字段来表示，意思分别是语言、国家和地区，
     * 比如zh-CN表示大陆汉语。（ISO 639-1、ISO 639-2）。
     */
    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return getIsLanguageAvailable(lang, country, variant);

    }

    /**
     * 获取当前引擎所设置的语言信息，返回值格式为{lang,country,variant}。
     *
     * @return String[] {lang,country,variant}。
     */
    @Override
    protected String[] onGetLanguage() {
        // Note that mCurrentLanguage is volatile because this can be called from
        // multiple threads.

        return mCurrentLanguage;
    }


    @Override
    protected Set<String> onGetFeaturesForLanguage(String lang, String country, String variant) {
        HashSet<String> hashSet = new HashSet<>();
        hashSet.add(lang);
        hashSet.add(country);
        hashSet.add(variant);
        return hashSet;
    }

    public List<String> getVoiceNames(String lang, String country, String variant) {
        List<String> vos = new ArrayList<>();
        Locale locale = new Locale(lang, country, variant);
        List<TtsActor> ttsActors = TtsActorManger.getInstance().getActorsByLocale(locale);
        for (TtsActor actor : ttsActors) {
            vos.add(actor.getShortName());
        }
        return vos;
    }

    @Override
    public int onIsValidVoiceName(String voiceName) {
        for (String vn : Constants.supportVoiceNames) {
            if (voiceName.equalsIgnoreCase(vn)) {
                return TextToSpeech.SUCCESS;
            }
        }
        return TextToSpeech.SUCCESS;
    }

    @Override
    public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        String name = "zh-CN-XiaoxiaoNeural";
//        if (variant.isEmpty()) {
//            variant = "Female";
//        }
        List<String> names = getVoiceNames(lang, country, variant);
        if (names.size() > 0) {
            name = names.get(0);
        }
        //name="zh-cn-XiaoyouNeural";

        return name;
    }


    @Override
    public int onLoadVoice(String voiceName) {
        return TextToSpeech.SUCCESS;
    }

    /**
     * 设置该语言，并返回是否是否支持该语言。
     * Note that this method is synchronized, as is onSynthesizeText because
     * onLoadLanguage can be called from multiple threads (while onSynthesizeText
     * is always called from a single thread only).
     */
    @Override
    protected int onLoadLanguage(String _lang, String _country, String _variant) {
        String lang = _lang == null ? "" : _lang;
        String country = _country == null ? "" : _country;
        String variant = _variant == null ? "" : _variant;
        int result = onIsLanguageAvailable(lang, country, variant);
        if (result == TextToSpeech.LANG_COUNTRY_AVAILABLE || TextToSpeech.LANG_AVAILABLE == result || result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) {
            mCurrentLanguage = new String[]{lang, country, variant};
        }

        return result;
    }

    /**
     * 停止tts播放或合成。
     */
    @Override
    protected void onStop() {
        webSocket.close(1000, "closed by call onStop");
    }


    /**
     * 将指定的文字，合成为tts音频流
     *
     * @param request  合成请求 SynthesisRequest
     * @param callback 合成callback SynthesisCallback
     */
    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {


        int load = onLoadLanguage(request.getLanguage(), request.getCountry(),
                request.getVariant());
        if (load == TextToSpeech.LANG_NOT_SUPPORTED) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST);
            Log.e(TAG, "语言不支持:" + request.getLanguage());
            return;
        }

        this.callback = callback;

        isSynthesizing = true;
        //使用System.nanoTime()来保证获得的是精准的时间间隔
        long startTime = SystemClock.elapsedRealtime();
        sendText(request, this.callback);
        synchronized (this) {
            while (isSynthesizing) {
                try {
                    this.wait(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                long time = SystemClock.elapsedRealtime() - startTime;
                //超时50秒后跳过
                if (time > 50000) {
                    callback.error(TextToSpeech.ERROR_NETWORK_TIMEOUT);
                    isSynthesizing = false;
                    callback.done();
                }
            }
        }


    }


}