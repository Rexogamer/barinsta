package awais.instagrabber.workers;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import awais.instagrabber.BuildConfig;
import awais.instagrabber.R;
import awais.instagrabber.services.DeleteImageIntentService;
import awais.instagrabber.utils.Constants;
import awais.instagrabber.utils.TextUtils;
import awais.instagrabber.utils.Utils;
import awaisomereport.LogCollector;

import static awais.instagrabber.utils.Constants.DOWNLOAD_CHANNEL_ID;
import static awais.instagrabber.utils.Constants.NOTIF_GROUP_NAME;
import static awais.instagrabber.utils.Utils.logCollector;

public class DownloadWorker extends Worker {
    private static final String TAG = "DownloadWorker";
    private static final String PROGRESS = "PROGRESS";
    private static final String URL = "URL";
    private static final String DOWNLOAD_GROUP = "DOWNLOAD_GROUP";

    public static final String KEY_DOWNLOAD_REQUEST_JSON = "download_request_json";
    public static final int DOWNLOAD_NOTIFICATION_INTENT_REQUEST_CODE = 2020;
    public static final int DELETE_IMAGE_REQUEST_CODE = 2030;

    private final NotificationManagerCompat notificationManager;

    public DownloadWorker(@NonNull final Context context, @NonNull final WorkerParameters workerParams) {
        super(context, workerParams);
        notificationManager = NotificationManagerCompat.from(context);
    }

    @NonNull
    @Override
    public Result doWork() {
        final String downloadRequestString = getInputData().getString(KEY_DOWNLOAD_REQUEST_JSON);
        if (TextUtils.isEmpty(downloadRequestString)) {
            return Result.failure(new Data.Builder()
                                          .putString("error", "downloadRequest is empty or null")
                                          .build());
        }
        final DownloadRequest downloadRequest;
        try {
            downloadRequest = new Gson().fromJson(downloadRequestString, DownloadRequest.class);
        } catch (JsonSyntaxException e) {
            Log.e(TAG, "doWork", e);
            return Result.failure(new Data.Builder()
                                          .putString("error", e.getLocalizedMessage())
                                          .build());
        }
        if (downloadRequest == null) {
            return Result.failure(new Data.Builder()
                                          .putString("error", "downloadRequest is null")
                                          .build());
        }
        final Map<String, String> urlToFilePathMap = downloadRequest.getUrlToFilePathMap();
        download(urlToFilePathMap);
        new Handler(Looper.getMainLooper()).postDelayed(() -> showSummary(urlToFilePathMap), 500);
        return Result.success();
    }

    private void download(final Map<String, String> urlToFilePathMap) {
        final int notificationId = getNotificationId();
        final Set<Map.Entry<String, String>> entries = urlToFilePathMap.entrySet();
        int count = 1;
        final int total = urlToFilePathMap.size();
        for (final Map.Entry<String, String> urlAndFilePath : entries) {
            final String url = urlAndFilePath.getKey();
            updateDownloadProgress(notificationId, count, total, 0);
            download(notificationId, count, total, url, urlAndFilePath.getValue());
            count++;
        }
    }

    private int getNotificationId() {
        return Math.abs(getId().hashCode());
    }

    private void download(final int notificationId,
                          final int position,
                          final int total,
                          final String url,
                          final String filePath) {
        final File outFile = new File(filePath);
        try {
            final URLConnection urlConnection = new URL(url).openConnection();
            final long fileSize = Build.VERSION.SDK_INT >= 24 ? urlConnection.getContentLengthLong() :
                                  urlConnection.getContentLength();
            float totalRead = 0;
            try (final BufferedInputStream bis = new BufferedInputStream(urlConnection.getInputStream());
                 final FileOutputStream fos = new FileOutputStream(outFile)) {
                final byte[] buffer = new byte[0x2000];
                int count;
                boolean deletedIPTC = false;
                while ((count = bis.read(buffer, 0, 0x2000)) != -1) {
                    totalRead = totalRead + count;
                    // if (!deletedIPTC) {
                    //     int iptcStart = -1;
                    //     int fbmdStart = -1;
                    //     int fbmdBytesLen = -1;
                    //     for (int i = 0; i < buffer.length; ++i) {
                    //         if (buffer[i] == (byte) 0xFF && buffer[i + 1] == (byte) 0xED)
                    //             iptcStart = i;
                    //         else if (buffer[i] == (byte) 'F' && buffer[i + 1] == (byte) 'B'
                    //                 && buffer[i + 2] == (byte) 'M' && buffer[i + 3] == (byte) 'D') {
                    //             fbmdStart = i;
                    //             fbmdBytesLen = buffer[i - 10] << 24 | (buffer[i - 9] & 0xFF) << 16 |
                    //                     (buffer[i - 8] & 0xFF) << 8 | (buffer[i - 7] & 0xFF) |
                    //                     (buffer[i - 6] & 0xFF);
                    //             break;
                    //         }
                    //     }
                    //     if (iptcStart != -1 && fbmdStart != -1 && fbmdBytesLen != -1) {
                    //         final int fbmdDataLen = (iptcStart + (fbmdStart - iptcStart) + (fbmdBytesLen - iptcStart)) - 4;
                    //         fos.write(buffer, 0, iptcStart);
                    //         fos.write(buffer, fbmdDataLen + iptcStart, count - fbmdDataLen - iptcStart);
                    //         // setProgressAsync(new Data.Builder().putString(URL, url)
                    //         //                                    .putFloat(PROGRESS, totalRead * 100f / fileSize)
                    //         //                                    .build());
                    //         updateDownloadProgress(notificationId, position, total, totalRead * 100f / fileSize);
                    //         deletedIPTC = true;
                    //         continue;
                    //     }
                    // }
                    fos.write(buffer, 0, count);
                    // setProgressAsync(new Data.Builder().putString(URL, url)
                    //                                    .putFloat(PROGRESS, totalRead * 100f / fileSize)
                    //                                    .build());
                    updateDownloadProgress(notificationId, position, total, totalRead * 100f / fileSize);
                }
                fos.flush();
            }
        } catch (final Exception e) {
            Log.e(TAG, "Error while downloading: " + url, e);
        }
        updateDownloadProgress(notificationId, position, total, 100);
    }

    // private void showCompleteNotification(final String url) {
    //     final Context context = getApplicationContext();
    //     final Notification notification = new NotificationCompat.Builder(context, Constants.DOWNLOAD_CHANNEL_ID)
    //             .setCategory(NotificationCompat.CATEGORY_STATUS)
    //             .setSmallIcon(R.drawable.ic_download)
    //             .setAutoCancel(false)
    //             .setOnlyAlertOnce(true)
    //             .setContentTitle(context.getString(R.string.downloader_complete))
    //             .setGroup(DOWNLOAD_GROUP)
    //             .build();
    //     final int id = Math.abs(url.hashCode());
    //     Log.d(TAG, "showCompleteNotification: cancelling: " + id);
    //     notificationManager.cancel(id);
    //     // WorkManager.getInstance(getApplicationContext()).
    //     notificationManager.notify(id + 1, notification);
    // }

    private void updateDownloadProgress(final int notificationId,
                                        final int position,
                                        final int total,
                                        final float percent) {
        final Notification notification = createProgressNotification(position, total, percent);
        try {
            setForegroundAsync(new ForegroundInfo(notificationId, notification)).get();
        } catch (ExecutionException | InterruptedException e) {
            Log.e(TAG, "updateDownloadProgress", e);
        }
    }

    private Notification createProgressNotification(final int position, final int total, final float percent) {
        final Context context = getApplicationContext();
        boolean ongoing = true;
        int totalPercent;
        if (position == total && percent == 100) {
            ongoing = false;
            totalPercent = 100;
        } else {
            totalPercent = (int) ((100f * (position - 1) / total) + (1f / total) * (percent));
        }
        // Log.d(TAG, "createProgressNotification: position: " + position
        //         + ", total: " + total
        //         + ", percent: " + percent
        //         + ", totalPercent: " + totalPercent);
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, Constants.DOWNLOAD_CHANNEL_ID)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setSmallIcon(R.drawable.ic_download)
                .setOngoing(ongoing)
                .setProgress(100, totalPercent, totalPercent < 0)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setContentTitle(context.getString(R.string.downloader_downloading_post));
        if (total > 1) {
            builder.setContentText(context.getString(R.string.downloader_downloading_child, position, total));
        }
        return builder.build();
    }

    private void showSummary(final Map<String, String> urlToFilePathMap) {
        final Context context = getApplicationContext();
        final Collection<String> filePaths = urlToFilePathMap.values();
        final List<NotificationCompat.Builder> notifications = new LinkedList<>();
        final List<Integer> notificationIds = new LinkedList<>();
        int count = 1;
        for (final String filePath : filePaths) {
            final File file = new File(filePath);
            context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)));
            MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, null);
            final Uri uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", file);
            final ContentResolver contentResolver = context.getContentResolver();
            Bitmap bitmap = null;
            if (Utils.isImage(uri, contentResolver)) {
                try (final InputStream inputStream = contentResolver.openInputStream(uri)) {
                    bitmap = BitmapFactory.decodeStream(inputStream);
                } catch (final Exception e) {
                    if (logCollector != null)
                        logCollector.appendException(e, LogCollector.LogFile.ASYNC_DOWNLOADER, "onPostExecute::bitmap_1");
                    if (BuildConfig.DEBUG) Log.e(TAG, "", e);
                }
            }
            if (bitmap == null) {
                final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    try {
                        retriever.setDataSource(context, uri);
                    } catch (final Exception e) {
                        retriever.setDataSource(file.getAbsolutePath());
                    }
                    bitmap = retriever.getFrameAtTime();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        try {
                            retriever.close();
                        } catch (final Exception e) {
                            if (logCollector != null)
                                logCollector.appendException(e, LogCollector.LogFile.ASYNC_DOWNLOADER, "onPostExecute::bitmap_2");
                        }
                } catch (final Exception e) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "", e);
                    if (logCollector != null)
                        logCollector.appendException(e, LogCollector.LogFile.ASYNC_DOWNLOADER, "onPostExecute::bitmap_3");
                }
            }
            final String downloadComplete = context.getString(R.string.downloader_complete);
            final Intent intent = new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                      | Intent.FLAG_FROM_BACKGROUND
                                      | Intent.FLAG_GRANT_READ_URI_PERMISSION
                                      | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_STREAM, uri);
            final PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    DOWNLOAD_NOTIFICATION_INTENT_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT
            );
            final int notificationId = getNotificationId() + count;
            notificationIds.add(notificationId);
            count++;
            final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_download)
                    .setContentText(null)
                    .setContentTitle(downloadComplete)
                    .setWhen(System.currentTimeMillis())
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setGroup(NOTIF_GROUP_NAME + "_" + getId())
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                    .setContentIntent(pendingIntent)
                    .addAction(R.drawable.ic_delete,
                               context.getString(R.string.delete),
                               DeleteImageIntentService.pendingIntent(context, filePath, notificationId));
            if (bitmap != null) {
                builder.setLargeIcon(bitmap)
                       .setStyle(new NotificationCompat.BigPictureStyle()
                                         .bigPicture(bitmap)
                                         .bigLargeIcon(null))
                       .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL);
            }
            notifications.add(builder);
        }
        Notification summaryNotification = null;
        if (urlToFilePathMap.size() != 1) {
            final String text = "Downloaded " + urlToFilePathMap.size() + " items";
            summaryNotification = new NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                    .setContentTitle("Downloaded")
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_download)
                    .setStyle(new NotificationCompat.InboxStyle().setSummaryText(text))
                    .setGroup(NOTIF_GROUP_NAME + "_" + getId())
                    .setGroupSummary(true)
                    .build();
        }
        for (int i = 0; i < notifications.size(); i++) {
            final NotificationCompat.Builder builder = notifications.get(i);
            // only make sound and vibrate for the last notification
            if (i != notifications.size() - 1) {
                builder.setSound(null)
                       .setVibrate(null);
            }
            notificationManager.notify(notificationIds.get(i), builder.build());
        }
        if (summaryNotification != null) {
            notificationManager.notify(getNotificationId() + count, summaryNotification);
        }
    }

    public static class DownloadRequest {
        private final Map<String, String> urlToFilePathMap;

        public static class Builder {
            private Map<String, String> urlToFilePathMap;

            public Builder setUrlToFilePathMap(final Map<String, String> urlToFilePathMap) {
                this.urlToFilePathMap = urlToFilePathMap;
                return this;
            }

            public Builder addUrl(@NonNull final String url, @NonNull final String filePath) {
                if (urlToFilePathMap == null) {
                    urlToFilePathMap = new HashMap<>();
                }
                urlToFilePathMap.put(url, filePath);
                return this;
            }

            public DownloadRequest build() {
                return new DownloadRequest(urlToFilePathMap);
            }
        }

        public static Builder builder() {
            return new Builder();
        }

        private DownloadRequest(final Map<String, String> urlToFilePathMap) {
            this.urlToFilePathMap = urlToFilePathMap;
        }

        public Map<String, String> getUrlToFilePathMap() {
            return urlToFilePathMap;
        }
    }
}
