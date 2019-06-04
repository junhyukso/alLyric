package com.estsoft.alsong.lyric;

import android.content.Context;
import android.os.AsyncTask;
import android.os.AsyncTask.Status;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import com.estsoft.alsong.common.LocalSong;
import com.estsoft.alsong.common.Song;
import com.estsoft.alsong.common.exception.NetworkException;
import com.estsoft.alsong.common.oldversion.MediaDBAdapter;
import com.estsoft.alsong.event.PlayerEvent.ChangeLyrics;
import com.estsoft.alsong.event.PlayerEvent.ErrorLyrics;
import com.estsoft.alsong.event.PlayerEvent.ErrorMovieLyrics;
import com.estsoft.alsong.event.PlayerEvent.LoadingLyrics;
import com.estsoft.alsong.event.PlayerEvent.MovieLyrics;
import com.estsoft.alsong.lyric.PMCheck.PmInfo;
import com.estsoft.alsong.utils.AlsongLog;
import com.estsoft.alsong.utils.HexUtils;
import com.estsoft.alsong.utils.NetworkUtils;
import com.estsoft.alsong.utils.NetworkUtils.DATA_TYPE;
import com.estsoft.vvave.service.message.MessageParser;
import com.facebook.appevents.AppEventsConstants;
import com.github.kevinsawicki.http.HttpRequest;
import com.google.android.exoplayer2.DefaultLoadControl;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class LyricRequest {
    public static final int ERROR_CODE_NETWORK_UNAVAILABLE = -3;
    public static final int ERROR_CODE_NO_ERROR = 0;
    public static final int ERROR_CODE_NO_SYNC_LYRIC = -1;
    public static final int ERROR_CODE_SERVICE_PM = -6;
    public static final int ERROR_CODE_UNSUPPORTED_FORMAT = -5;
    public static final int ERROR_CODE_USER_CANCELED = -4;
    public static final int ERROR_CODE_WIFI_UNAVAILABLE = -2;
    private static final String IP;
    private static final String MAC;
    private static final int connectTimeout = 20000;
    private static String encData = null;
    private static String first_register = "";
    private static int info_id = -1;
    private static Boolean isMovieLyricRuning = Boolean.valueOf(false);
    private static volatile int lastErrorCode = 0;
    private static long lastGeneratedEncDataTime = 0;
    private static volatile List<SyncLyricToken> lastLoadedLyricList = null;
    private static String last_register = "";
    private static PmInfo pmInfo = null;
    private static final int readTimeout = 20000;
    private static final String requestUrlBase = "http://lyrics.alsong.co.kr/ALSongMobile/";
    private static Map<Song, LyricLoader> sLoaders = new Hashtable();
    private static TreeMap<String, String[]> searchMap = new TreeMap();
    private static String search_first_register = "";
    private static int search_info_id = -1;
    private static String search_last_register = "";
    private static volatile MovieLyricLoader uploader = null;

    private static class LyricLoader extends Thread {
        private volatile boolean cancelled = false;
        private Context context;
        private volatile int errorCode;
        private volatile LyricType lyricType;
        private Handler mainHandler;
        private List<SyncLyricToken> result = null;
        private volatile Song song;

        public LyricLoader(Context context, Song song) {
            this.song = song;
            this.errorCode = 0;
            this.lyricType = LyricType.SYNC;
            this.mainHandler = new Handler(Looper.getMainLooper());
            this.result = null;
            LyricRequest.lastLoadedLyricList = null;
            LyricRequest.info_id = -1;
            LyricRequest.first_register = "";
            LyricRequest.last_register = "";
            this.context = context;
        }

        public boolean cancel() {
            boolean isAlive = isAlive();
            this.cancelled = true;
            return isAlive;
        }

        public void execute() {
            this.cancelled = false;
            start();
        }

        private boolean isCancelled() {
            return this.cancelled;
        }

        /* Access modifiers changed, original: protected */
        public void onPostExecute(List<SyncLyricToken> list) {
            if (!isCancelled()) {
                StringBuilder stringBuilder;
                if (this.errorCode != 0 || list == null || list.size() <= 0) {
                    LyricRequest.lastLoadedLyricList = null;
                    LyricRequest.lastErrorCode = this.errorCode;
                    stringBuilder = new StringBuilder();
                    stringBuilder.append("lyric result!! : error : ");
                    stringBuilder.append(this.song.getTitle());
                    AlsongLog.e(stringBuilder.toString());
                    EventBus.getDefault().post(new ErrorLyrics(this.song, this.errorCode));
                } else if (this.lyricType == LyricType.TAG || this.lyricType == LyricType.UNKNOWN) {
                    this.errorCode = -1;
                    LyricRequest.lastLoadedLyricList = null;
                    LyricRequest.lastErrorCode = this.errorCode;
                    stringBuilder = new StringBuilder();
                    stringBuilder.append("lyric result!! : error : ");
                    stringBuilder.append(this.song.getTitle());
                    AlsongLog.e(stringBuilder.toString());
                    EventBus.getDefault().post(new ErrorLyrics(this.song, this.errorCode));
                } else {
                    LyricRequest.lastLoadedLyricList = list;
                    StringBuilder stringBuilder2 = new StringBuilder();
                    stringBuilder2.append("lyric result!! successed : ");
                    stringBuilder2.append(this.song.getTitle());
                    AlsongLog.e(stringBuilder2.toString());
                    EventBus.getDefault().post(new ChangeLyrics(this.song, list, (long) LyricRequest.info_id, LyricRequest.first_register, LyricRequest.last_register));
                }
                LyricRequest.sLoaders.remove(this.song);
                stringBuilder = new StringBuilder();
                stringBuilder.append("lyric result!! : end : ");
                stringBuilder.append(this.song.getTitle());
                AlsongLog.e(stringBuilder.toString());
            }
        }

        public void run() {
            String str;
            JSONException e;
            String generateMD5 = LyricMd5Utils.generateMD5(this.song.getPath());
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("lyric md5 :  ");
            stringBuilder.append(generateMD5);
            AlsongLog.e(stringBuilder.toString());
            if (TextUtils.isEmpty(generateMD5)) {
                setErrorStatus(-1);
                finishJob();
            } else if (isCancelled()) {
                setErrorStatus(-4);
                finishJob();
            } else {
                CharSequence charSequence = "";
                LyricInfo lyricInfo = LyricDB.getInstance(this.context).getLyricInfo(generateMD5);
                if (lyricInfo != null) {
                    String str2 = lyricInfo.lyric;
                    if (isCancelled()) {
                        setErrorStatus(-4);
                        finishJob();
                        return;
                    } else if (StringUtils.isNotBlank(str2)) {
                        String str3;
                        if (this.song instanceof LocalSong) {
                            if (str2.startsWith("<?")) {
                                str2 = LyricRequest.parseLocalLyricSoapXml(str2);
                                if (!TextUtils.isEmpty(str2)) {
                                    this.result = LyricRequest.parseLocalLyricList(str2);
                                    if (this.result != null && this.result.size() > 0 && StringUtils.isNotBlank(str2)) {
                                        LyricDB.getInstance(this.context).insert(generateMD5, str2, -1, "", "");
                                        finishJob();
                                        return;
                                    }
                                }
                                str3 = str2;
                                LyricDB.getInstance(this.context).insert(generateMD5, "", -1, "", "");
                                charSequence = str3;
                            } else {
                                if (lyricInfo.info_id > 0) {
                                    LyricRequest.info_id = lyricInfo.info_id;
                                    LyricRequest.first_register = lyricInfo.first_register;
                                    LyricRequest.last_register = lyricInfo.last_register;
                                }
                                this.result = LyricRequest.parseLocalLyricList(str2.substring(str2.lastIndexOf(10) + 1));
                                if (this.result != null && this.result.size() > 0) {
                                    finishJob();
                                    return;
                                }
                            }
                        }
                        str3 = charSequence;
                        LyricDB.getInstance(this.context).insert(generateMD5, "", -1, "", "");
                        charSequence = str3;
                    }
                }
                EventBus.getDefault().post(new LoadingLyrics(this.song));
                if (this.song instanceof LocalSong) {
                    String localLyric = getLocalLyric(generateMD5);
                    if (isCancelled()) {
                        setErrorStatus(-4);
                        finishJob();
                        return;
                    }
                    if (this.lyricType == LyricType.SYNC) {
                        if (!TextUtils.isEmpty(localLyric) && this.errorCode == 0) {
                            try {
                                JSONObject jSONObject = new JSONObject(localLyric);
                                str = (String) jSONObject.get("lyric");
                                try {
                                    LyricRequest.info_id = ((Integer) jSONObject.get("info_id")).intValue();
                                    LyricRequest.first_register = (String) jSONObject.get("register_first_name");
                                    LyricRequest.last_register = (String) jSONObject.get("register_name");
                                } catch (JSONException e2) {
                                    e = e2;
                                }
                            } catch (JSONException e3) {
                                str = charSequence;
                                e = e3;
                                e.printStackTrace();
                                charSequence = str;
                                this.result = LyricRequest.parseLocalLyricList(charSequence.substring(charSequence.lastIndexOf(10) + 1));
                                this.errorCode = -1;
                                finishJob();
                                return;
                            }
                            charSequence = str;
                            this.result = LyricRequest.parseLocalLyricList(charSequence.substring(charSequence.lastIndexOf(10) + 1));
                            if (this.result == null || this.result.size() == 0) {
                                this.errorCode = -1;
                                finishJob();
                                return;
                            }
                        }
                    } else if (this.lyricType == LyricType.TAG) {
                        this.result = new ArrayList();
                        SyncLyricToken syncLyricToken = new SyncLyricToken();
                        syncLyricToken.setTime(0);
                        syncLyricToken.addLine(localLyric);
                        this.result.add(syncLyricToken);
                    }
                    if (this.lyricType == LyricType.SYNC && StringUtils.isNotBlank(r1)) {
                        LyricDB.getInstance(this.context).insert(generateMD5, localLyric);
                    }
                }
                finishJob();
            }
        }

        private void finishJob() {
            this.mainHandler.post(new Runnable() {
                public void run() {
                    LyricLoader.this.onPostExecute(LyricLoader.this.result);
                }
            });
        }

        /* Access modifiers changed, original: protected */
        /* JADX WARNING: Removed duplicated region for block: B:29:0x0054 A:{RETURN} */
        /* JADX WARNING: Removed duplicated region for block: B:28:0x0053 A:{RETURN} */
        public java.lang.String getLocalLyric(java.lang.String r5) {
            /*
            r4 = this;
            r0 = com.estsoft.alsong.lyric.LyricRequest.LyricType.SYNC;
            r4.lyricType = r0;
            r0 = r4.context;
            r1 = com.estsoft.alsong.utils.NetworkUtils.DATA_TYPE.DATA_TYPE_LYRIC;
            r0 = com.estsoft.alsong.utils.NetworkUtils.isEnabledAlsong3GNetworkSetting(r0, r1);
            r1 = -3;
            r2 = 0;
            if (r0 != 0) goto L_0x001d;
        L_0x0010:
            r0 = r4.context;
            r0 = com.estsoft.alsong.utils.NetworkUtils.isWifiConnected(r0);
            if (r0 != 0) goto L_0x002b;
        L_0x0018:
            r5 = -2;
            r4.setErrorStatus(r5);
            return r2;
        L_0x001d:
            r0 = r4.context;
            r3 = com.estsoft.alsong.utils.NetworkUtils.DATA_TYPE.DATA_TYPE_LYRIC;
            r0 = com.estsoft.alsong.utils.NetworkUtils.isNetworkConnected(r0, r3);
            if (r0 != 0) goto L_0x002b;
        L_0x0027:
            r4.setErrorStatus(r1);
            return r2;
        L_0x002b:
            r0 = com.estsoft.alsong.lyric.PMCheck.pmCheck();	 Catch:{ Exception -> 0x0032 }
            com.estsoft.alsong.lyric.LyricRequest.pmInfo = r0;	 Catch:{ Exception -> 0x0032 }
        L_0x0032:
            r0 = com.estsoft.alsong.lyric.LyricRequest.pmInfo;
            if (r0 == 0) goto L_0x003c;
        L_0x0038:
            r5 = -6;
            r4.errorCode = r5;
            return r2;
        L_0x003c:
            r5 = com.estsoft.alsong.lyric.LyricRequest.receiveLyricXmlFromServer(r5);	 Catch:{ NetworkException -> 0x0049, UnknownHostException -> 0x0045, Exception -> 0x0041 }
            goto L_0x004d;
        L_0x0041:
            r4.setErrorStatus(r1);
            goto L_0x004c;
        L_0x0045:
            r4.setErrorStatus(r1);
            goto L_0x004c;
        L_0x0049:
            r4.setErrorStatus(r1);
        L_0x004c:
            r5 = r2;
        L_0x004d:
            r0 = android.text.TextUtils.isEmpty(r5);
            if (r0 != 0) goto L_0x0054;
        L_0x0053:
            return r5;
        L_0x0054:
            return r2;
            */
            throw new UnsupportedOperationException("Method not decompiled: com.estsoft.alsong.lyric.LyricRequest$LyricLoader.getLocalLyric(java.lang.String):java.lang.String");
        }

        /* Access modifiers changed, original: protected */
        /* JADX WARNING: Removed duplicated region for block: B:19:0x004b  */
        /* JADX WARNING: Removed duplicated region for block: B:17:0x0042  */
        public java.lang.String getTagLyric() {
            /*
            r3 = this;
            r0 = "getTAgLyric!!!!!";
            com.estsoft.alsong.utils.AlsongLog.e(r0);
            r0 = com.estsoft.alsong.lyric.LyricRequest.LyricType.TAG;
            r3.lyricType = r0;
            r0 = org.jaudiotagger.tag.TagOptionSingleton.getInstance();
            r1 = 1;
            r0.setAndroid(r1);
            r0 = 0;
            r1 = new java.io.File;	 Catch:{ CannotReadException -> 0x003b, IOException -> 0x0036, TagException -> 0x0031, ReadOnlyFileException -> 0x002c, InvalidAudioFrameException -> 0x0027, NoClassDefFoundError -> 0x0022 }
            r2 = r3.song;	 Catch:{ CannotReadException -> 0x003b, IOException -> 0x0036, TagException -> 0x0031, ReadOnlyFileException -> 0x002c, InvalidAudioFrameException -> 0x0027, NoClassDefFoundError -> 0x0022 }
            r2 = r2.getPath();	 Catch:{ CannotReadException -> 0x003b, IOException -> 0x0036, TagException -> 0x0031, ReadOnlyFileException -> 0x002c, InvalidAudioFrameException -> 0x0027, NoClassDefFoundError -> 0x0022 }
            r1.<init>(r2);	 Catch:{ CannotReadException -> 0x003b, IOException -> 0x0036, TagException -> 0x0031, ReadOnlyFileException -> 0x002c, InvalidAudioFrameException -> 0x0027, NoClassDefFoundError -> 0x0022 }
            r1 = org.jaudiotagger.audio.AudioFileIO.read(r1);	 Catch:{ CannotReadException -> 0x003b, IOException -> 0x0036, TagException -> 0x0031, ReadOnlyFileException -> 0x002c, InvalidAudioFrameException -> 0x0027, NoClassDefFoundError -> 0x0022 }
            goto L_0x0040;
        L_0x0022:
            r1 = move-exception;
            r1.printStackTrace();
            goto L_0x003f;
        L_0x0027:
            r1 = move-exception;
            r1.printStackTrace();
            goto L_0x003f;
        L_0x002c:
            r1 = move-exception;
            r1.printStackTrace();
            goto L_0x003f;
        L_0x0031:
            r1 = move-exception;
            r1.printStackTrace();
            goto L_0x003f;
        L_0x0036:
            r1 = move-exception;
            r1.printStackTrace();
            goto L_0x003f;
        L_0x003b:
            r1 = move-exception;
            r1.printStackTrace();
        L_0x003f:
            r1 = r0;
        L_0x0040:
            if (r1 != 0) goto L_0x004b;
        L_0x0042:
            r1 = com.estsoft.alsong.lyric.LyricRequest.LyricType.UNKNOWN;
            r3.lyricType = r1;
            r1 = -1;
            r3.setErrorStatus(r1);
            return r0;
        L_0x004b:
            r1 = r1.getTag();
            r2 = org.jaudiotagger.tag.FieldKey.LYRICS;
            r1 = r1.getFields(r2);
            r2 = r1.size();
            if (r2 <= 0) goto L_0x0066;
        L_0x005b:
            r0 = 0;
            r0 = r1.get(r0);
            r0 = (org.jaudiotagger.tag.TagField) r0;
            r0 = r0.toString();
        L_0x0066:
            return r0;
            */
            throw new UnsupportedOperationException("Method not decompiled: com.estsoft.alsong.lyric.LyricRequest$LyricLoader.getTagLyric():java.lang.String");
        }

        private void setErrorStatus(int i) {
            this.errorCode = i;
        }
    }

    private enum LyricType {
        UNKNOWN,
        SYNC,
        BUGS,
        TAG
    }

    protected static class MovieLyricLoader extends AsyncTask<Void, Integer, ArrayList<String>> {
        private Context context;
        private int errorCode = 0;
        private String murekaId;

        public MovieLyricLoader(Context context, String str) {
            this.murekaId = str;
            this.context = context;
        }

        /* Access modifiers changed, original: protected */
        public ArrayList<String> getMovieLyric(String str) {
            if (NetworkUtils.isEnabledAlsong3GNetworkSetting(this.context, DATA_TYPE.DATA_TYPE_LYRIC_MOVIE)) {
                if (!NetworkUtils.isNetworkConnected(this.context, DATA_TYPE.DATA_TYPE_LYRIC_MOVIE)) {
                    this.errorCode = -3;
                    return null;
                }
            } else if (!NetworkUtils.isWifiConnected(this.context)) {
                this.errorCode = -2;
                return null;
            }
            try {
                LyricRequest.pmInfo = PMCheck.pmCheck();
            } catch (Exception unused) {
            }
            if (LyricRequest.pmInfo != null) {
                this.errorCode = -6;
                return null;
            }
            ArrayList receiveLyricListFromServerMurekaId;
            try {
                receiveLyricListFromServerMurekaId = LyricRequest.receiveLyricListFromServerMurekaId(str);
            } catch (Exception unused2) {
                this.errorCode = -3;
                receiveLyricListFromServerMurekaId = null;
            }
            if (receiveLyricListFromServerMurekaId == null || receiveLyricListFromServerMurekaId.size() <= 0) {
                return null;
            }
            return receiveLyricListFromServerMurekaId;
        }

        /* Access modifiers changed, original: protected|varargs */
        public ArrayList<String> doInBackground(Void... voidArr) {
            return getMovieLyric(this.murekaId);
        }

        /* Access modifiers changed, original: protected */
        public void onPostExecute(ArrayList<String> arrayList) {
            if (this.errorCode != 0 || arrayList == null || arrayList.size() <= 0) {
                EventBus.getDefault().post(new ErrorMovieLyrics(this.murekaId, this.errorCode));
            } else {
                EventBus.getDefault().post(new MovieLyrics((ArrayList) arrayList));
            }
        }

        /* Access modifiers changed, original: protected */
        public void onCancelled() {
            this.errorCode = -4;
            LyricRequest.isMovieLyricRuning = Boolean.valueOf(false);
        }
    }

    public static class SyncLyricToken {
        private ArrayList<String> lines = new ArrayList();
        private long time;
        private ArrayList<Boolean> valids = new ArrayList();

        public String getLine(int i) {
            return (String) this.lines.get(i);
        }

        public void setLine(int i, String str) {
            this.lines.set(i, str);
        }

        public int getLineCount() {
            return this.lines.size();
        }

        public long getTime() {
            return this.time;
        }

        public void setTime(long j) {
            this.time = j;
        }

        public void setValid(int i, boolean z) {
            this.valids.set(i, Boolean.valueOf(z));
        }

        public boolean isValid(int i) {
            return ((Boolean) this.valids.get(i)).booleanValue();
        }

        public void addLine(String str) {
            addLine(str, true);
        }

        public void addLine(String str, boolean z) {
            this.lines.add(str);
            this.valids.add(Boolean.valueOf(z));
        }
    }

    public static native byte[] generateEncData(String str);

    static {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(Build.BRAND);
        stringBuilder.append("_");
        stringBuilder.append(Build.CPU_ABI);
        IP = stringBuilder.toString();
        stringBuilder = new StringBuilder();
        stringBuilder.append(Build.MANUFACTURER);
        stringBuilder.append("_");
        stringBuilder.append(Build.MODEL);
        MAC = stringBuilder.toString();
        try {
            System.loadLibrary("alsong-lyric");
        } catch (UnsatisfiedLinkError unused) {
            System.loadLibrary("alsong-lyric.so");
        }
    }

    public static void loadLyric(Context context, Song song) {
        if (song != null) {
            if (sLoaders.containsKey(song)) {
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append("lyric contains : ");
                stringBuilder.append(song.getTitle());
                AlsongLog.d(stringBuilder.toString());
                return;
            }
            cancel();
            StringBuilder stringBuilder2 = new StringBuilder();
            stringBuilder2.append("lyric load call : ");
            stringBuilder2.append(song.getTitle());
            AlsongLog.d(stringBuilder2.toString());
            LyricLoader lyricLoader = new LyricLoader(context, song);
            sLoaders.put(song, lyricLoader);
            lyricLoader.execute();
        }
    }

    public static void cancel() {
        for (LyricLoader lyricLoader : sLoaders.values()) {
            if (lyricLoader != null) {
                lyricLoader.cancel();
                AlsongLog.e("lyric cancel : ok");
            } else {
                AlsongLog.e("lyric cancel : null");
            }
        }
        sLoaders.clear();
    }

    public static int getLastErrorCode() {
        return lastErrorCode;
    }

    public static void setLastLyric(List<SyncLyricToken> list) {
        if (list != null && list.size() > 0) {
            lastLoadedLyricList = list;
        }
    }

    public static List<SyncLyricToken> getLastLyric() {
        return lastLoadedLyricList;
    }

    public static String getFirstNickName() {
        return first_register;
    }

    public static String getLastNickName() {
        return last_register;
    }

    public static void setFirstNickName(String str) {
        first_register = str;
    }

    public static void setLastNickName(String str) {
        last_register = str;
    }

    public static int getInfoID() {
        return info_id;
    }

    public static String getSearchFirstNickName() {
        return search_first_register;
    }

    public static String getSearchLastNickName() {
        return search_last_register;
    }

    public static int getSearchInfoID() {
        return search_info_id;
    }

    public static boolean isJSONValid(String str) {
        try {
            JSONObject jSONObject = new JSONObject(str);
            return true;
        } catch (JSONException unused) {
            return false;
        }
    }

    public static String getJSONLyricToString(String str, long j, String str2, String str3) {
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.accumulate("lyric", str);
            jSONObject.accumulate("info_id", Long.valueOf(j));
            jSONObject.accumulate("register_first_name", str2);
            jSONObject.accumulate("register_name", str3);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jSONObject.toString();
    }

    public static void loadMovieLyrics(Context context, String str) {
        if (str != null) {
            synchronized (isMovieLyricRuning) {
                if (isMovieLyricRuning.booleanValue() && uploader.getStatus() != Status.FINISHED) {
                    uploader.cancel(true);
                }
                uploader = new MovieLyricLoader(context, str);
                isMovieLyricRuning = Boolean.valueOf(true);
                uploader.execute(new Void[0]);
            }
        }
    }

    public static String parseLocalLyricSoapXml(String str) {
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        String replaceAll = str.replaceAll("(?s).*<strStatusID>(.*)<\\/strStatusID>.*", "$1");
        if (!TextUtils.equals(AppEventsConstants.EVENT_PARAM_VALUE_YES, replaceAll)) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("error!!! errorCode: ");
            stringBuilder.append(replaceAll);
            AlsongLog.e(stringBuilder.toString());
            lastErrorCode = -3;
            try {
                if (Integer.valueOf(replaceAll).intValue() == 2) {
                    AlsongLog.e("Not found lyric or endData error!!");
                }
                return null;
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
        }
        if (str.matches(".*<strLyric />.*")) {
            return null;
        }
        return str.replaceAll("(?s).*<strLyric>(.*)<\\/strLyric>.*", "$1");
    }

    public static List<SyncLyricToken> parseLocalLyricList(String str) throws NumberFormatException {
        if (TextUtils.isEmpty(str)) {
            AlsongLog.e("lyric text is empty");
            return null;
        }
        ArrayList arrayList = new ArrayList();
        SyncLyricToken syncLyricToken = null;
        for (String str2 : str.split("((<)|(&lt;))br((>)|(&gt;))")) {
            String str22;
            if (str22.length() > 10) {
                try {
                    long parseLong = (Long.parseLong(str22.substring(1, 3)) * 60000) + ((long) (Float.parseFloat(str22.substring(4, 9)) * 1000.0f));
                    str22 = str22.substring(10);
                    if (syncLyricToken == null || syncLyricToken.getTime() < parseLong || parseLong == 0) {
                        syncLyricToken = new SyncLyricToken();
                        syncLyricToken.setTime(parseLong);
                        syncLyricToken.addLine(str22);
                        arrayList.add(syncLyricToken);
                    } else if (syncLyricToken.getTime() == parseLong) {
                        syncLyricToken.addLine(str22);
                    } else {
                        syncLyricToken.addLine(str22, false);
                    }
                } catch (NumberFormatException e) {
                    StringBuilder stringBuilder = new StringBuilder();
                    stringBuilder.append("lyric is invalid [lyric: ");
                    stringBuilder.append(str22);
                    stringBuilder.append(" \nfullText: ");
                    stringBuilder.append(str);
                    stringBuilder.append(" ]");
                    AlsongLog.e(stringBuilder.toString(), e);
                }
            }
        }
        return arrayList;
    }

    public static ArrayList<String> parseOnlyLyricString(String str) throws NumberFormatException {
        if (TextUtils.isEmpty(str)) {
            AlsongLog.e("lyric text is empty");
            return null;
        }
        ArrayList arrayList = new ArrayList();
        for (String str2 : str.split("((<)|(&lt;))br((>)|(&gt;))")) {
            String str22;
            if (str22.length() > 10) {
                try {
                    str22 = str22.substring(10);
                    if (str22 != null) {
                        arrayList.add(str22);
                    }
                } catch (Exception unused) {
                }
            }
        }
        return arrayList;
    }

    public static String toSyncLocalLyricString(List<SyncLyricToken> list) throws NumberFormatException {
        if (list.isEmpty()) {
            return null;
        }
        DecimalFormat decimalFormat = new DecimalFormat("#00.00");
        int size = list.size();
        String str = "";
        int i = 0;
        while (i < size) {
            SyncLyricToken syncLyricToken = (SyncLyricToken) list.get(i);
            long time = syncLyricToken.getTime();
            float f = ((float) (time - ((long) (60000 * ((int) (time / 60000)))))) / 1000.0f;
            String format = String.format("[%02d:%s]", new Object[]{Integer.valueOf(r8), decimalFormat.format((double) f)});
            int lineCount = syncLyricToken.getLineCount();
            String str2 = str;
            for (int i2 = 0; i2 < lineCount; i2++) {
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append(str2);
                stringBuilder.append(String.format("%s%s<br>", new Object[]{format, syncLyricToken.getLine(i2)}));
                str2 = stringBuilder.toString();
            }
            i++;
            str = str2;
        }
        StringBuilder stringBuilder2 = new StringBuilder();
        stringBuilder2.append(str);
        stringBuilder2.append(MessageParser.CONTENTS_LINEBREAK);
        return stringBuilder2.toString();
    }

    public static String getEncData() {
        if (lastGeneratedEncDataTime >= new Date().getTime() - DateUtils.MILLIS_PER_HOUR) {
            return encData;
        }
        try {
            byte[] generateEncData = generateEncData(DateFormatUtils.format(new Date(), "_yyyyMMdd_HHmmss", TimeZone.getTimeZone("GMT")));
            if (generateEncData != null) {
                if (generateEncData.length != 0) {
                    encData = HexUtils.toHex(generateEncData).toUpperCase();
                    return encData;
                }
            }
            return "";
        } catch (ExceptionInInitializerError unused) {
            return "";
        }
    }

    public static String receiveLyricXmlFromServer(String str) throws Exception {
        String encData = getEncData();
        Map hashMap = new HashMap();
        hashMap.put("encData", encData);
        hashMap.put("md5", str);
        str = HttpRequest.post((CharSequence) "https://lyric.altools.com/v1/lookup").header("Connection", "close").connectTimeout(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS).form(hashMap).body();
        AlsongLog.d(str);
        return StringEscapeUtils.unescapeJava(str);
    }

    public static String receiveLyricXmlFromServerMurekaId(String str) throws Exception {
        Map hashMap = new HashMap();
        hashMap.put("murekaid", str);
        return StringEscapeUtils.unescapeJava((String) new JSONObject(HttpRequest.post((CharSequence) "https://lyric.altools.com/v1/lookupListByMurekaId").header("Connection", "close").connectTimeout(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS).form(hashMap).body()).get("lyric"));
    }

    public static ArrayList<String> receiveLyricListFromServerMurekaId(String str) throws Exception {
        ArrayList arrayList = new ArrayList();
        Map hashMap = new HashMap();
        hashMap.put("murekaid", str);
        JSONArray jSONArray = new JSONArray(HttpRequest.post((CharSequence) "https://lyric.altools.com/v1/lookupListByMurekaId").header("Connection", "close").connectTimeout(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS).form(hashMap).body());
        int length = jSONArray.length();
        if (length > 0) {
            for (int i = 0; i < length; i++) {
                String str2 = (String) jSONArray.getJSONObject(i).get("lyric");
                if (!str2.isEmpty()) {
                    str2 = StringEscapeUtils.unescapeJava(str2);
                    arrayList.add(TextUtils.join("\n", parseOnlyLyricString(str2.substring(str2.lastIndexOf(10) + 1))));
                }
            }
        }
        return arrayList;
    }

    public static int countLyrics(String str, String str2, long j) throws NetworkException {
        if (TextUtils.isEmpty(str) && TextUtils.isEmpty(str2)) {
            return 0;
        }
        try {
            pmInfo = PMCheck.pmCheck();
        } catch (Exception unused) {
        }
        if (pmInfo != null) {
            return -6;
        }
        Map hashMap = new HashMap();
        hashMap.put("title", str);
        hashMap.put("artist", str2);
        hashMap.put("playtime", String.valueOf(j));
        hashMap.put("encData", getEncData());
        try {
            return new JSONObject(HttpRequest.post((CharSequence) "https://lyric.altools.com/v1/search/count").connectTimeout(20000).readTimeout(20000).form(hashMap, "UTF-8").body()).getInt("count");
        } catch (JSONException unused2) {
            return 0;
        }
    }

    public static ArrayList<Lyric> getLyricList(String str, String str2, long j, int i) throws NetworkException {
        if (TextUtils.isEmpty(str) && TextUtils.isEmpty(str2)) {
            return new ArrayList();
        }
        if (i <= 0) {
            i = 1;
        }
        Map hashMap = new HashMap();
        hashMap.put("title", str);
        hashMap.put("artist", str2);
        hashMap.put("playtime", String.valueOf(j));
        hashMap.put("page", String.valueOf(i));
        hashMap.put("encData", getEncData());
        str = HttpRequest.post((CharSequence) "https://lyric.altools.com/v1/search").connectTimeout(20000).readTimeout(20000).form(hashMap, "UTF-8").body();
        ArrayList arrayList = new ArrayList();
        try {
            JSONArray jSONArray = new JSONArray(str);
            int length = jSONArray.length();
            for (i = 0; i < length; i++) {
                JSONObject jSONObject = jSONArray.getJSONObject(i);
                Lyric lyric = new Lyric();
                lyric.setId(String.valueOf(jSONObject.getInt("lyric_id")));
                lyric.setTitle(jSONObject.getString("title"));
                lyric.setArtist(jSONObject.getString("artist"));
                lyric.setAlbum(jSONObject.getString(MediaDBAdapter.TAG_ALBUM));
                lyric.setPlayTime(jSONObject.getString("playtime"));
                lyric.setRecDate(jSONObject.getString("register_date").replace("T", StringUtils.SPACE).substring(0, 19));
                arrayList.add(lyric);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return arrayList;
    }

    public static String getLyricWithId(String str) throws NetworkException {
        JSONException e;
        Exception e2;
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        String encData = getEncData();
        Map hashMap = new HashMap();
        hashMap.put("encData", encData);
        hashMap.put("info_id", str);
        try {
            JSONObject jSONObject = new JSONObject(HttpRequest.post((CharSequence) "https://lyric.altools.com/v1/info").header("Connection", "close").connectTimeout(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS).form(hashMap).body());
            str = (String) jSONObject.get("lyric");
            try {
                search_info_id = ((Integer) jSONObject.get("info_id")).intValue();
                search_first_register = (String) jSONObject.get("register_first_name");
                search_last_register = (String) jSONObject.get("register_name");
            } catch (JSONException e3) {
                e = e3;
            } catch (Exception e4) {
                e2 = e4;
                e2.printStackTrace();
                return str;
            }
        } catch (JSONException e5) {
            e = e5;
            str = null;
            e.printStackTrace();
            return str;
        } catch (Exception e6) {
            e2 = e6;
            str = null;
            e2.printStackTrace();
            return str;
        }
        return str;
    }

    public static String getPmMessage() {
        return pmInfo != null ? pmInfo.getMessage() : null;
    }

    public static String getPmStartTime() {
        return pmInfo != null ? pmInfo.getStartTime() : null;
    }

    public static String getPmEndTime() {
        return pmInfo != null ? pmInfo.getEndTime() : null;
    }
}
