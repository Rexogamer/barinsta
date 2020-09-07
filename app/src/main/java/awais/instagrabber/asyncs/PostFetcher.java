package awais.instagrabber.asyncs;

import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;

import awais.instagrabber.interfaces.FetchListener;
import awais.instagrabber.models.ProfileModel;
import awais.instagrabber.models.ViewerPostModel;
import awais.instagrabber.models.enums.MediaItemType;
import awais.instagrabber.utils.Constants;
import awais.instagrabber.utils.Utils;
import awaisomereport.LogCollector;

import static awais.instagrabber.utils.Constants.DOWNLOAD_USER_FOLDER;
import static awais.instagrabber.utils.Constants.FOLDER_PATH;
import static awais.instagrabber.utils.Constants.FOLDER_SAVE_TO;
import static awais.instagrabber.utils.Utils.logCollector;

public final class PostFetcher extends AsyncTask<Void, Void, ViewerPostModel[]> {
    private static final String TAG = "PostFetcher";

    private final String shortCode;
    private final FetchListener<ViewerPostModel[]> fetchListener;

    public PostFetcher(final String shortCode, final FetchListener<ViewerPostModel[]> fetchListener) {
        this.shortCode = shortCode;
        this.fetchListener = fetchListener;
    }

    @Override
    protected ViewerPostModel[] doInBackground(final Void... voids) {
        ViewerPostModel[] result = null;
        Utils.setupCookies(Utils.settingsHelper.getString(Constants.COOKIE)); // <- direct download
        try {
            final HttpURLConnection conn = (HttpURLConnection) new URL("https://www.instagram.com/p/" + shortCode + "/?__a=1").openConnection();
            conn.setUseCaches(false);
            conn.connect();

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {

                final JSONObject media = new JSONObject(Utils.readFromConnection(conn)).getJSONObject("graphql")
                                                                                       .getJSONObject("shortcode_media");

                ProfileModel profileModel = null;
                if (media.has("owner")) {
                    final JSONObject owner = media.getJSONObject("owner");
                    profileModel = new ProfileModel(
                            owner.optBoolean("is_private"),
                            owner.optBoolean("is_private"),
                            owner.optBoolean("is_verified"),
                            owner.optString("id"),
                            owner.optString("username"),
                            owner.optString("full_name"),
                            null,
                            null,
                            owner.optString("profile_pic_url"),
                            owner.optString("profile_pic_url"),
                            owner.optInt("edge_owner_to_timeline_media"),
                            owner.optInt("edge_followed_by"),
                            -1,
                            owner.optBoolean("followed_by_viewer"),
                            owner.optBoolean("restricted_by_viewer"),
                            owner.optBoolean("blocked_by_viewer"),
                            owner.optBoolean("requested_by_viewer")
                    );
                }
                final String username = profileModel == null ? "" : profileModel.getUsername();
                // to check if file exists
                final File downloadDir = new File(Environment.getExternalStorageDirectory(), "Download" +
                        (Utils.settingsHelper.getBoolean(DOWNLOAD_USER_FOLDER) ? ("/" + username) : ""));
                File customDir = null;
                if (Utils.settingsHelper.getBoolean(FOLDER_SAVE_TO)) {
                    final String customPath = Utils.settingsHelper.getString(FOLDER_PATH +
                                                                                     (Utils.settingsHelper.getBoolean(DOWNLOAD_USER_FOLDER)
                                                                                      ? ("/" + username)
                                                                                      : ""));
                    if (!Utils.isEmpty(customPath)) customDir = new File(customPath);
                }

                final long timestamp = media.getLong("taken_at_timestamp");

                final boolean isVideo = media.has("is_video") && media.optBoolean("is_video");
                final boolean isSlider = media.has("edge_sidecar_to_children");

                final MediaItemType mediaItemType;
                if (isSlider) mediaItemType = MediaItemType.MEDIA_TYPE_SLIDER;
                else if (isVideo) mediaItemType = MediaItemType.MEDIA_TYPE_VIDEO;
                else mediaItemType = MediaItemType.MEDIA_TYPE_IMAGE;

                final String postCaption;
                final JSONObject mediaToCaption = media.optJSONObject("edge_media_to_caption");
                if (mediaToCaption == null) postCaption = null;
                else {
                    final JSONArray captions = mediaToCaption.optJSONArray("edges");
                    postCaption = captions != null && captions.length() > 0 ?
                                  captions.getJSONObject(0).getJSONObject("node").optString("text") : null;
                }

                JSONObject commentObject = media.optJSONObject("edge_media_to_parent_comment");
                final long commentsCount = commentObject != null ? commentObject.optLong("count") : 0;

                String endCursor = null;
                if (commentObject != null && (commentObject = commentObject.optJSONObject("page_info")) != null) {
                    endCursor = commentObject.optString("end_cursor");
                }

                if (mediaItemType != MediaItemType.MEDIA_TYPE_SLIDER) {
                    final ViewerPostModel postModel = new ViewerPostModel(
                            mediaItemType,
                            media.getString(Constants.EXTRAS_ID),
                            isVideo ? media.getString("video_url") : Utils.getHighQualityImage(media),
                            shortCode,
                            Utils.isEmpty(postCaption) ? null : postCaption,
                            profileModel,
                            isVideo && media.has("video_view_count") ? media.getLong("video_view_count") : -1,
                            timestamp, media.getBoolean("viewer_has_liked"), media.getBoolean("viewer_has_saved"),
                            media.getJSONObject("edge_media_preview_like").getLong("count"),
                            media.isNull("location") ? null : media.getJSONObject("location").optString("name"),
                            media.isNull("location") ? null :
                            (media.getJSONObject("location").optString("id") + "/" +
                                    media.getJSONObject("location").optString("slug")));

                    postModel.setCommentsCount(commentsCount);

                    Utils.checkExistence(downloadDir, customDir, false, postModel);

                    result = new ViewerPostModel[]{postModel};

                } else {
                    final JSONArray children = media.getJSONObject("edge_sidecar_to_children").getJSONArray("edges");
                    final ViewerPostModel[] postModels = new ViewerPostModel[children.length()];

                    for (int i = 0; i < postModels.length; ++i) {
                        final JSONObject node = children.getJSONObject(i).getJSONObject("node");
                        final boolean isChildVideo = node.getBoolean("is_video");

                        postModels[i] = new ViewerPostModel(
                                isChildVideo ? MediaItemType.MEDIA_TYPE_VIDEO : MediaItemType.MEDIA_TYPE_IMAGE,
                                media.getString(Constants.EXTRAS_ID),
                                isChildVideo ? node.getString("video_url") : Utils.getHighQualityImage(node),
                                node.getString(Constants.EXTRAS_SHORTCODE),
                                postCaption,
                                profileModel,
                                isChildVideo && node.has("video_view_count") ? node.getLong("video_view_count") : -1,
                                timestamp, media.getBoolean("viewer_has_liked"), media.getBoolean("viewer_has_saved"),
                                media.getJSONObject("edge_media_preview_like").getLong("count"),
                                media.isNull("location") ? null : media.getJSONObject("location").optString("name"),
                                media.isNull("location") ? null :
                                (media.getJSONObject("location").optString("id") + "/" +
                                        media.getJSONObject("location").optString("slug")));
                        postModels[i].setSliderDisplayUrl(node.getString("display_url"));

                        Utils.checkExistence(downloadDir, customDir, true, postModels[i]);
                    }

                    postModels[0].setCommentsCount(commentsCount);

                    result = postModels;
                }
            }

            conn.disconnect();
        } catch (Exception e) {
            if (logCollector != null) {
                logCollector.appendException(e, LogCollector.LogFile.ASYNC_POST_FETCHER, "doInBackground");
            }
            Log.e(TAG, "Error fetching post", e);
        }
        return result;
    }

    @Override
    protected void onPreExecute() {
        if (fetchListener != null) fetchListener.doBefore();
    }

    @Override
    protected void onPostExecute(final ViewerPostModel[] postModels) {
        if (fetchListener != null) fetchListener.onResult(postModels);
    }
}
