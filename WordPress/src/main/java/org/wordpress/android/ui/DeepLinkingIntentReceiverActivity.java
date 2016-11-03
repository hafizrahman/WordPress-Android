package org.wordpress.android.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;

import org.wordpress.android.R;
import org.wordpress.android.analytics.AnalyticsTracker;
import org.wordpress.android.models.AccountHelper;
import org.wordpress.android.ui.accounts.SignInActivity;
import org.wordpress.android.ui.reader.ReaderActivityLauncher;
import org.wordpress.android.ui.reader.ReaderCommentListActivity.DirectOperation;
import org.wordpress.android.util.AnalyticsUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.ToastUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An activity to handle deep linking and intercepting
 *
 * wordpress://viewpost?blogId={blogId}&postId={postId}
 * http[s]://wordpress.com/read/blogs/{blogId}/posts/{postId}
 * http[s]://wordpress.com/read/feeds/{feedId}/posts/{feedItemId}
 * http[s]://{username}.wordpress.com/{year}/{month}/{day}/{postSlug}
 *
 * Redirects users to the reader activity along with IDs passed in the intent
 */
public class DeepLinkingIntentReceiverActivity extends AppCompatActivity {
    private static final int INTENT_WELCOME = 0;

    private enum InterceptType {
        VIEWPOST,
        READER_BLOG,
        READER_FEED,
        WPCOM_POST_SLUG
    }

    private InterceptType mInterceptType;
    private String mBlogIdentifier; // can be an id or a slug
    private String mPostIdentifier; // can be an id or a slug
    private String mInterceptedUri;
    private DirectOperation mDirectOperation;
    private int mCommentId;

    private static final Pattern FRAGMENT_COMMENTS_PATTERN = Pattern.compile("comments", Pattern.CASE_INSENSITIVE);
    private static final Pattern FRAGMENT_COMMENT_ID_PATTERN = Pattern.compile("comment-(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FRAGMENT_RESPOND_PATTERN = Pattern.compile("respond", Pattern.CASE_INSENSITIVE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String action = getIntent().getAction();
        Uri uri = getIntent().getData();

        AnalyticsUtils.trackWithDeepLinkData(AnalyticsTracker.Stat.DEEP_LINKED, action, uri);

        // check if this intent is started via custom scheme link
        if (Intent.ACTION_VIEW.equals(action) && uri != null) {

            switch (uri.getScheme()) {
                case "wordpress":
                    mInterceptType = InterceptType.VIEWPOST;
                    mBlogIdentifier = uri.getQueryParameter("blogId");
                    mPostIdentifier = uri.getQueryParameter("postId");

                    // if user is logged in, show the post right away - otherwise show welcome activity
                    // and then show the post once the user has logged in
                    if (AccountHelper.isSignedInWordPressDotCom()) {
                        showPost();
                    } else {
                        Intent intent = new Intent(this, SignInActivity.class);
                        startActivityForResult(intent, INTENT_WELCOME);
                    }
                    return;
                case "http":
                case "https":
                    mInterceptedUri = uri.toString();

                    List<String> segments = uri.getPathSegments();

                    // Handled URLs look like this: http[s]://wordpress.com/read/feeds/{feedId}/posts/{feedItemId}
                    //  with the first segment being 'read'.
                    if (segments != null) {
                        if (segments.get(0).equals("read")) {
                            if (segments.size() > 2) {
                                mBlogIdentifier = segments.get(2);

                                if (segments.get(1).equals("blogs")) {
                                    mInterceptType = InterceptType.READER_BLOG;
                                } else if (segments.get(1).equals("feeds")) {
                                    mInterceptType = InterceptType.READER_FEED;
                                }
                            }

                            if (segments.size() > 4 && segments.get(3).equals("posts")) {
                                mPostIdentifier = segments.get(4);
                            }

                            parseFragment(uri);

                            showPost();
                            return;
                        } else if (segments.size() == 4) {
                            mBlogIdentifier = uri.getHost();
                            try {
                                mPostIdentifier = URLEncoder.encode(segments.get(3), "UTF-8");
                            } catch (UnsupportedEncodingException e) {
                                AppLog.e(T.READER, e);
                                ToastUtils.showToast(this, R.string.error_generic);
                            }

                            parseFragment(uri);
                            detectLike(uri);

                            mInterceptType = InterceptType.WPCOM_POST_SLUG;
                            showPost();
                            return;
                        }
                    }

                    break;
            }

            // at this point, just show the entry screen
            Intent intent = new Intent(this, WPLaunchActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }

        finish();
    }

    private void parseFragment(Uri uri) {
        // default to do-nothing w.r.t. comments
        mDirectOperation = null;

        if (uri == null || uri.getFragment() == null) {
            return;
        }

        final String fragment = uri.getFragment();

        // check for the general "#comments" fragment to jump to the comments section
        Matcher commentsMatcher = FRAGMENT_COMMENTS_PATTERN.matcher(fragment);
        if (commentsMatcher.matches()) {
            mDirectOperation = DirectOperation.COMMENT_JUMP;
            mCommentId = 0;
            return;
        }

        // check for the "#respond" fragment to jump to the reply box
        Matcher respondMatcher = FRAGMENT_RESPOND_PATTERN.matcher(fragment);
        if (respondMatcher.matches()) {
            mDirectOperation = DirectOperation.COMMENT_REPLY;

            // check whether we are to reply to a specific comment
            final String replyToCommentId = uri.getQueryParameter("replytocom");
            if (replyToCommentId != null) {
                try {
                    mCommentId = Integer.parseInt(replyToCommentId);
                } catch (NumberFormatException e) {
                    AppLog.e(T.UTILS, "replytocom cannot be converted to int" + replyToCommentId, e);
                }
            }

            return;
        }

        // check for the "#comment-xyz" fragment to jump to a specific comment
        Matcher commentIdMatcher = FRAGMENT_COMMENT_ID_PATTERN.matcher(fragment);
        if (commentIdMatcher.find() && commentIdMatcher.groupCount() > 0) {
            mCommentId = Integer.valueOf(commentIdMatcher.group(1));
            mDirectOperation = DirectOperation.COMMENT_JUMP;
        }
    }

    private void detectLike(Uri uri) {
        // check whether we are to like something
        final boolean doLike = "1".equals(uri.getQueryParameter("like"));
        final String likeActor = uri.getQueryParameter("like_actor");

        if (doLike && likeActor != null && likeActor.trim().length() > 0) {
            mDirectOperation = DirectOperation.POST_LIKE;

            // check whether we are to like a specific comment
            final String likeCommentId = uri.getQueryParameter("commentid");
            if (likeCommentId != null) {
                try {
                    mCommentId = Integer.parseInt(likeCommentId);
                    mDirectOperation = DirectOperation.COMMENT_LIKE;
                } catch (NumberFormatException e) {
                    AppLog.e(T.UTILS, "commentid cannot be converted to int" + likeCommentId, e);
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // show the post if user is returning from successful login
        if (requestCode == INTENT_WELCOME && resultCode == RESULT_OK)
            showPost();
    }

    private void showPost() {
        if (!TextUtils.isEmpty(mBlogIdentifier) && !TextUtils.isEmpty(mPostIdentifier)) {
            if (mInterceptType != null) {
                switch (mInterceptType) {
                    case VIEWPOST:
                        AnalyticsUtils.trackWithBlogPostDetails(AnalyticsTracker.Stat.READER_VIEWPOST_INTERCEPTED,
                                mBlogIdentifier, mPostIdentifier);
                        break;
                    case READER_BLOG:
                        AnalyticsUtils.trackWithBlogPostDetails(AnalyticsTracker.Stat.READER_BLOG_POST_INTERCEPTED,
                                mBlogIdentifier, mPostIdentifier);
                        break;
                    case READER_FEED:
                        AnalyticsUtils.trackWithFeedPostDetails(AnalyticsTracker.Stat.READER_FEED_POST_INTERCEPTED,
                                mBlogIdentifier, mPostIdentifier);
                        break;
                    case WPCOM_POST_SLUG:
                        AnalyticsUtils.trackWithBlogPostDetails(
                                AnalyticsTracker.Stat.READER_WPCOM_BLOG_POST_INTERCEPTED, mBlogIdentifier,
                                mPostIdentifier, mCommentId);
                        break;
                }
            }

            if (mInterceptType == InterceptType.WPCOM_POST_SLUG) {
                ReaderActivityLauncher.showReaderPostDetail(
                        this, mBlogIdentifier, mPostIdentifier, mDirectOperation, mCommentId, false, mInterceptedUri);
            } else {
                try {
                    final long blogId = Long.parseLong(mBlogIdentifier);
                    final long postId = Long.parseLong(mPostIdentifier);

                    ReaderActivityLauncher.showReaderPostDetail(this, InterceptType.READER_FEED.equals(mInterceptType),
                        blogId, postId, mDirectOperation, mCommentId, false, mInterceptedUri);
                } catch (NumberFormatException e) {
                    AppLog.e(T.READER, e);
                }
            }
        } else {
            ToastUtils.showToast(this, R.string.error_generic);
        }

        finish();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        finish();
    }
}
