/*
 * Kontalk Android client
 * Copyright (C) 2016 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.ui;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.akalipetis.fragment.ActionModeListFragment;
import com.akalipetis.fragment.MultiChoiceModeListener;
import com.nispok.snackbar.Snackbar;
import com.nispok.snackbar.SnackbarManager;
import com.nispok.snackbar.enums.SnackbarType;
import com.nispok.snackbar.listeners.ActionClickListener;

import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smackx.chatstates.ChatState;
import org.jxmpp.util.XmppStringUtils;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.AsyncQueryHandler;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.MergeCursor;
import android.database.sqlite.SQLiteDiskIOException;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.ContactsContract.Contacts;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.view.ActionMode;
import android.text.ClipboardManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import io.codetail.animation.SupportAnimator;
import io.codetail.animation.ViewAnimationUtils;

import org.kontalk.Kontalk;
import org.kontalk.R;
import org.kontalk.authenticator.Authenticator;
import org.kontalk.crypto.Coder;
import org.kontalk.data.Contact;
import org.kontalk.data.Conversation;
import org.kontalk.message.AttachmentComponent;
import org.kontalk.message.AudioComponent;
import org.kontalk.message.CompositeMessage;
import org.kontalk.message.GroupCommandComponent;
import org.kontalk.message.ImageComponent;
import org.kontalk.message.MessageComponent;
import org.kontalk.message.TextComponent;
import org.kontalk.message.VCardComponent;
import org.kontalk.provider.MessagesProviderUtils;
import org.kontalk.provider.MyMessages.Messages;
import org.kontalk.provider.MyMessages.Threads;
import org.kontalk.provider.MyMessages.Threads.Conversations;
import org.kontalk.reporting.ReportingManager;
import org.kontalk.service.DownloadService;
import org.kontalk.service.msgcenter.MessageCenterService;
import org.kontalk.ui.adapter.MessageListAdapter;
import org.kontalk.ui.view.AudioContentView;
import org.kontalk.ui.view.AudioContentViewControl;
import org.kontalk.ui.view.AudioPlayerControl;
import org.kontalk.ui.view.ComposerBar;
import org.kontalk.ui.view.ComposerListener;
import org.kontalk.ui.view.MessageListItem;
import org.kontalk.util.MediaStorage;
import org.kontalk.util.MessageUtils;
import org.kontalk.util.Preferences;
import org.kontalk.util.SystemUtils;

import static android.content.res.Configuration.KEYBOARDHIDDEN_NO;


/**
 * Abstract message composing fragment.
 * @author Daniele Ricci
 * @author Andrea Cappelli
 */
public abstract class AbstractComposeFragment extends ActionModeListFragment implements
        ComposerListener, View.OnLongClickListener,
        // TODO these two interfaces should be handled by an inner class
        AudioDialog.AudioDialogListener, AudioPlayerControl,
        MultiChoiceModeListener {
    private static final String TAG = ComposeMessage.TAG;

    private static final int MESSAGE_LIST_QUERY_TOKEN = 8720;
    private static final int CONVERSATION_QUERY_TOKEN = 8721;
    private static final int MESSAGE_PAGE_QUERY_TOKEN = 8723;

    /** How many messages to load per page. */
    private static final int MESSAGE_PAGE_SIZE = 1000;

    private static final int SELECT_ATTACHMENT_OPENABLE = Activity.RESULT_FIRST_USER + 1;
    private static final int SELECT_ATTACHMENT_CONTACT = Activity.RESULT_FIRST_USER + 2;
    private static final int SELECT_ATTACHMENT_PHOTO = Activity.RESULT_FIRST_USER + 3;
    private static final int REQUEST_INVITE_USERS = Activity.RESULT_FIRST_USER + 4;

    protected enum WarningType {
        SUCCESS(0),    // not implemented
        INFO(1),       // not implemented
        WARNING(2),
        FATAL(3);

        private final int value;

        WarningType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    /* Attachment chooser stuff. */
    private SupportAnimator mAttachAnimator;
    private View mAttachmentCard;
    private View mAttachmentContainer;

    protected ComposerBar mComposer;

    MessageListQueryHandler mQueryHandler;
    MessageListAdapter mListAdapter;
    /** Header view for the list view: "previous messages" button. */
    private View mHeaderView;
    private View mNextPageButton;
    private TextView mStatusText;
    private MenuItem mDeleteThreadMenu;

    /** The thread id. */
    private long threadId = -1;
    protected Conversation mConversation;
    private Bundle mArguments;
    protected String mUserName;

    /** Available resources. */
    protected Set<String> mAvailableResources = new HashSet<>();

    /** Media player stuff. */
    private int mMediaPlayerStatus = AudioContentView.STATUS_IDLE;
    private Handler mHandler;
    private Runnable mMediaPlayerUpdater;
    private AudioContentViewControl mAudioControl;

    /** Audio recording dialog. */
    private AudioDialog mAudioDialog;

    private PeerObserver mPeerObserver;
    private File mCurrentPhoto;

    protected LocalBroadcastManager mLocalBroadcastManager;
    private BroadcastReceiver mPresenceReceiver;

    private boolean mOfflineModeWarned;
    protected CharSequence mCurrentStatus;

    private int mCheckedItemCount;

    /** Returns a new fragment instance from a picked contact. */
    public static AbstractComposeFragment fromUserId(Context context, String userId) {
        AbstractComposeFragment f = new ComposeMessageFragment();
        Conversation conv = Conversation.loadFromUserId(context, userId);
        // not found - create new
        if (conv == null) {
            Bundle args = new Bundle();
            args.putString("action", ComposeMessage.ACTION_VIEW_USERID);
            args.putParcelable("data", Threads.getUri(userId));
            f.setArguments(args);
            return f;
        }

        return fromConversation(context, conv);
    }

    /** Returns a new fragment instance from a {@link Conversation} instance. */
    public static AbstractComposeFragment fromConversation(Context context,
            Conversation conv) {
        return fromConversation(context, conv.getThreadId(), conv.isGroupChat());
    }

    /** Returns a new fragment instance from a thread ID. */
    private static AbstractComposeFragment fromConversation(Context context,
            long threadId, boolean group) {
        AbstractComposeFragment f = group ?
            new GroupMessageFragment() : new ComposeMessageFragment();
        Bundle args = new Bundle();
        args.putString("action", ComposeMessage.ACTION_VIEW_CONVERSATION);
        args.putParcelable("data",
                ContentUris.withAppendedId(Conversations.CONTENT_URI, threadId));
        f.setMyArguments(args);
        return f;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        // setListAdapter() is post-poned

        ListView list = getListView();

        setMultiChoiceModeListener(this);

        // add header view (this must be done before setting the adapter)
        mHeaderView = LayoutInflater.from(getActivity())
            .inflate(R.layout.message_list_header, list, false);
        mNextPageButton = mHeaderView.findViewById(R.id.load_next_page);
        mNextPageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // disable button in the meantime
                enableHeaderView(false);
                // start query for the next page
                startMessagesQuery(mQueryHandler.getLastId());
            }
        });
        list.addHeaderView(mHeaderView, null, false);

        // set custom background (if any)
        ImageView background = (ImageView) getView().findViewById(R.id.background);
        Drawable bg = Preferences.getConversationBackground(getActivity());
        if (bg != null) {
            background.setScaleType(ImageView.ScaleType.CENTER_CROP);
            background.setImageDrawable(bg);
        }
        else {
            background.setScaleType(ImageView.ScaleType.FIT_XY);
            background.setImageResource(R.drawable.app_background_tile);
        }

        processArguments(savedInstanceState);
        initAttachmentView();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mLocalBroadcastManager = LocalBroadcastManager.getInstance(context);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mLocalBroadcastManager = null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        mComposer.onKeyboardStateChanged(newConfig.keyboardHidden == KEYBOARDHIDDEN_NO);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.compose_message, container, false);

        mComposer = (ComposerBar) view.findViewById(R.id.composer_bar);
        mComposer.setComposerListener(this);

        // footer (for tablet presence status)
        mStatusText = (TextView) view.findViewById(R.id.status_text);

        mComposer.setRootView(view);

        Configuration config = getResources().getConfiguration();
        mComposer.onKeyboardStateChanged(config.keyboardHidden == KEYBOARDHIDDEN_NO);

        return view;
    }

    private final MessageListAdapter.OnContentChangedListener mContentChangedListener = new MessageListAdapter.OnContentChangedListener() {
        public void onContentChanged(MessageListAdapter adapter) {
            if (isVisible())
                startQuery(false);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setHasOptionsMenu(true);
        mQueryHandler = new MessageListQueryHandler(this);
        mHandler = new Handler();

        // list adapter creation is post-poned
    }

    public boolean isActionModeActive() {
        return mCheckedItemCount > 0;
    }

    @Override
    public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
        if (checked)
            mCheckedItemCount++;
        else
            mCheckedItemCount--;
        mode.setTitle(getResources()
            .getQuantityString(R.plurals.context_selected,
                mCheckedItemCount, mCheckedItemCount));

        mode.invalidate();
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        MenuInflater inflater = mode.getMenuInflater();
        inflater.inflate(R.menu.compose_message_ctx, menu);
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        MenuItem deleteMenu = menu.findItem(R.id.menu_delete);
        MenuItem retryMenu = menu.findItem(R.id.menu_retry);
        MenuItem shareMenu = menu.findItem(R.id.menu_share);
        MenuItem copyTextMenu = menu.findItem(R.id.menu_copy_text);
        MenuItem detailsMenu = menu.findItem(R.id.menu_details);
        MenuItem openMenu = menu.findItem(R.id.menu_open);
        MenuItem dlMenu = menu.findItem(R.id.menu_download);
        MenuItem cancelDlMenu = menu.findItem(R.id.menu_cancel_download);

        // initial status
        deleteMenu.setVisible(true);
        retryMenu.setVisible(false);
        shareMenu.setVisible(false);
        copyTextMenu.setVisible(false);
        detailsMenu.setVisible(false);
        openMenu.setVisible(false);
        dlMenu.setVisible(false);
        cancelDlMenu.setVisible(false);

        boolean singleItem = (mCheckedItemCount == 1);
        if (singleItem) {
            CompositeMessage msg = getCheckedItem();

            // group command can't be deleted or have details
            if (msg.hasComponent(GroupCommandComponent.class)) {
                deleteMenu.setVisible(false);
            }
            else {
                // message waiting for user review or not delivered
                if (msg.getStatus() == Messages.STATUS_PENDING || msg.getStatus() == Messages.STATUS_NOTDELIVERED) {
                    retryMenu.setVisible(true);
                }

                // some commands can be used only on unencrypted messages
                if (!msg.isEncrypted()) {
                    AttachmentComponent attachment = msg.getComponent(AttachmentComponent.class);
                    TextComponent text = msg.getComponent(TextComponent.class);

                    // sharing media messages has no purpose if media file hasn't been
                    // retrieved yet
                    if (text != null || attachment == null || attachment.getLocalUri() != null)
                        shareMenu.setVisible(true);

                    // non-empty text: copy text to clipboard
                    if (text != null && !TextUtils.isEmpty(text.getContent()))
                        copyTextMenu.setVisible(true);

                    if (attachment != null) {

                        // message has a local uri - add open file entry
                        if (attachment.getLocalUri() != null) {
                            int resId;
                            if (attachment instanceof ImageComponent)
                                resId = R.string.view_image;
                            else if (attachment instanceof AudioComponent)
                                resId = R.string.open_audio;
                            else
                                resId = R.string.open_file;

                            openMenu.setTitle(resId);
                            openMenu.setVisible(true);
                        }

                        // message has a fetch url - add download control entry
                        if (msg.getDirection() == Messages.DIRECTION_IN && attachment.getFetchUrl() != null) {
                            if (!DownloadService.isQueued(attachment.getFetchUrl())) {
                                int string;
                                // already fetched
                                if (attachment.getLocalUri() != null)
                                    string = R.string.download_again;
                                else
                                    string = R.string.download_file;

                                dlMenu.setTitle(string);
                                dlMenu.setVisible(true);
                            }
                            else {
                                cancelDlMenu.setVisible(true);
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_delete: {
                // using clone because listview returns its original copy
                deleteSelectedMessages(SystemUtils
                    .cloneSparseBooleanArray(getListView().getCheckedItemPositions()));
                mode.finish();
                return true;
            }

            case R.id.menu_retry: {
                CompositeMessage msg = getCheckedItem();
                retryMessage(msg);
                mode.finish();
                return true;
            }

            case R.id.menu_share: {
                CompositeMessage msg = getCheckedItem();
                shareMessage(msg);
                mode.finish();
                return true;
            }

            case R.id.menu_copy_text: {
                CompositeMessage msg = getCheckedItem();

                TextComponent txt = msg.getComponent(TextComponent.class);

                String text = (txt != null) ? txt.getContent() : "";

                ClipboardManager cpm = (ClipboardManager) getActivity()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
                cpm.setText(text);

                Toast.makeText(getActivity(), R.string.message_text_copied,
                    Toast.LENGTH_SHORT).show();
                mode.finish();
                return true;
            }

            case R.id.menu_open: {
                CompositeMessage msg = getCheckedItem();
                openFile(msg);
                mode.finish();
                return true;
            }

            case R.id.menu_download: {
                CompositeMessage msg = getCheckedItem();
                startDownload(msg);
                mode.finish();
                return true;
            }

            case R.id.menu_cancel_download: {
                CompositeMessage msg = getCheckedItem();
                stopDownload(msg);
                mode.finish();
                return true;
            }

            case R.id.menu_details: {
                CompositeMessage msg = getCheckedItem();
                showMessageDetails(msg);
                mode.finish();
                return true;
            }
        }
        return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        mCheckedItemCount = 0;
        getListView().clearChoices();
        mListAdapter.notifyDataSetChanged();
    }

    private CompositeMessage getCheckedItem() {
        if (mCheckedItemCount != 1)
            throw new IllegalStateException("checked items count must be exactly 1");

        Cursor cursor = (Cursor) getListView().getItemAtPosition(getCheckedItemPosition());
        return CompositeMessage.fromCursor(getActivity(), cursor);
    }

    private int getCheckedItemPosition() {
        SparseBooleanArray checked = getListView().getCheckedItemPositions();
        return checked.keyAt(checked.indexOfValue(true));
    }

    private void deleteSelectedMessages(final SparseBooleanArray checked) {
        new MaterialDialog.Builder(getActivity())
            .content(R.string.confirm_will_delete_messages)
            .positiveText(android.R.string.ok)
            .positiveColorRes(R.color.button_danger)
            .onPositive(new MaterialDialog.SingleButtonCallback() {
                @Override
                public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                    Context ctx = getActivity();
                    for (int i = 0, c = getListView().getCount()+getListView().getHeaderViewsCount(); i < c; ++i) {
                        if (checked.get(i)) {
                            Cursor cursor = (Cursor) getListView().getItemAtPosition(i);
                            // skip group command messages
                            if (!GroupCommandComponent.isCursor(cursor))
                                CompositeMessage.deleteFromCursor(ctx, cursor);
                        }
                    }
                    mListAdapter.notifyDataSetChanged();
                }
            })
            .negativeText(android.R.string.cancel)
            .show();
    }

    private void initAttachmentView()
    {
        View view = getView();

        mAttachmentContainer = view.findViewById(R.id.attachment_container);
        mAttachmentCard = view.findViewById(R.id.circular_card);

        View.OnClickListener hideAttachmentListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleAttachmentView();
            }
        };
        view.findViewById(R.id.attachment_overlay).setOnClickListener(hideAttachmentListener);
        view.findViewById(R.id.attach_hide).setOnClickListener(hideAttachmentListener);

        view.findViewById(R.id.attach_camera).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectPhotoAttachment();
                toggleAttachmentView();
            }
        });

        view.findViewById(R.id.attach_gallery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectGalleryAttachment();
                toggleAttachmentView();
            }
        });

        view.findViewById(R.id.attach_video).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(getContext(), R.string.msg_not_implemented, Toast.LENGTH_SHORT).show();
            }
        });

        view.findViewById(R.id.attach_audio).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAudioAttachment();
                toggleAttachmentView();
            }
        });

        view.findViewById(R.id.attach_file).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(getContext(), R.string.msg_not_implemented, Toast.LENGTH_SHORT).show();
            }
        });

        view.findViewById(R.id.attach_vcard).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectContactAttachment();
                toggleAttachmentView();
            }
        });

        view.findViewById(R.id.attach_location).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(getContext(), R.string.msg_not_implemented, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Sends out a binary message. */
    @Override
    public void sendBinaryMessage(Uri uri, String mime, boolean media,
            Class<? extends MessageComponent<?>> klass) {
        Log.v(TAG, "sending binary content: " + uri);

        try {
            // TODO convert to thread (?)

            offlineModeWarning();

            final Context context = getContext();
            final Conversation conv = mConversation;
            Uri newMsg = Kontalk.getMessagesController(context)
                .sendBinaryMessage(conv, uri, mime, media, klass);

            // update thread id from the inserted message
            if (threadId <= 0) {
                threadId = MessagesProviderUtils.getThreadByMessage(getContext(), newMsg);
                if (threadId > 0) {
                    // we can run it here because progress=false
                    startQuery(false);
                }
                else {
                    Log.v(TAG, "no data - cannot start query for this composer");
                }
            }
        }
        catch (SQLiteDiskIOException e) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getActivity(), R.string.error_store_outbox,
                        Toast.LENGTH_LONG).show();
                }
            });
        }
        catch (Exception e) {
            ReportingManager.logException(e);
            getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    Toast.makeText(getActivity(),
                            R.string.err_store_message_failed,
                            Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private final class TextMessageThread extends Thread {
        private final String mText;

        TextMessageThread(String text) {
            mText = text;
        }

        @Override
        public void run() {
            try {
                final Context context = getContext();
                final Conversation conv = mConversation;
                Uri newMsg = Kontalk.getMessagesController(context)
                    .sendTextMessage(conv, mText);

                // update thread id from the inserted message
                if (threadId <= 0) {
                    threadId = MessagesProviderUtils.getThreadByMessage(context, newMsg);
                    if (threadId > 0) {
                        // we can run it here because progress=false
                        startQuery(false);
                    }
                    else {
                        Log.v(TAG, "no data - cannot start query for this composer");
                    }
                }
            }
            catch (SQLiteDiskIOException e) {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getActivity(), R.string.error_store_outbox,
                            Toast.LENGTH_LONG).show();
                    }
                });
            }
            catch (Exception e) {
                ReportingManager.logException(e);
                getActivity().runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(getActivity(),
                            R.string.err_store_message_failed,
                            Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    }

    /** Sends out the text message in the composing entry. */
    @Override
    public void sendTextMessage(String message) {
        if (!TextUtils.isEmpty(message)) {
            offlineModeWarning();

            // start thread
            new TextMessageThread(message).start();
        }
    }

    /** Sends an inactive chat state message. */
    public abstract boolean sendInactive();

    protected abstract void onInflateOptionsMenu(Menu menu, MenuInflater inflater);

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        onInflateOptionsMenu(menu, inflater);
        mDeleteThreadMenu = menu.findItem(R.id.delete_thread);
        updateUI();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // action mode is active - no processing
        if (isActionModeActive())
            return true;

        switch (item.getItemId()) {
            case R.id.menu_attachment:
                toggleAttachmentView();
                return true;

            case R.id.delete_thread:
                if (threadId > 0)
                    deleteThread();
                return true;

            case R.id.invite_group:
                addUsers();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onListItemClick(ListView listView, View view, int position, long id) {
        int choiceMode = listView.getChoiceMode();
        if (choiceMode == ListView.CHOICE_MODE_NONE || choiceMode == ListView.CHOICE_MODE_SINGLE) {
            MessageListItem item = (MessageListItem) view;
            final CompositeMessage msg = item.getMessage();

            AttachmentComponent attachment = msg.getComponent(AttachmentComponent.class);

            if (attachment != null && (attachment.getFetchUrl() != null || attachment.getLocalUri() != null)) {

                // outgoing message or already fetched
                if (attachment.getLocalUri() != null) {
                    // open file
                    openFile(msg);
                }
                else {
                    // info & download dialog
                    CharSequence message = MessageUtils
                        .getFileInfoMessage(getActivity(), msg, getDecodedPeer(msg));

                    MaterialDialog.Builder builder = new MaterialDialog.Builder(getActivity())
                        .title(R.string.title_file_info)
                        .content(message)
                        .negativeText(android.R.string.cancel)
                        .cancelable(true);

                    if (!DownloadService.isQueued(attachment.getFetchUrl())) {
                        MaterialDialog.SingleButtonCallback startDL = new MaterialDialog.SingleButtonCallback() {
                            @Override
                            public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                                // start file download
                                startDownload(msg);
                            }
                        };
                        builder.positiveText(R.string.download)
                            .onPositive(startDL);
                    }
                    else {
                        MaterialDialog.SingleButtonCallback stopDL = new MaterialDialog.SingleButtonCallback() {
                            @Override
                            public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                                // cancel file download
                                stopDownload(msg);
                            }
                        };
                        builder.positiveText(R.string.download_cancel)
                            .onPositive(stopDL);
                    }

                    builder.show();
                }
            }

            else {
                item.onClick();
            }
        }
        else {
            super.onListItemClick(listView, view, position, id);
        }
    }

    private void startDownload(CompositeMessage msg) {
        AttachmentComponent attachment = msg
                .getComponent(AttachmentComponent.class);

        if (attachment != null && attachment.getFetchUrl() != null) {
            DownloadService.start(getContext(), msg.getDatabaseId(),
                msg.getSender(), msg.getTimestamp(),
                attachment.getSecurityFlags() != Coder.SECURITY_CLEARTEXT,
                attachment.getFetchUrl());
        }
        else {
            // corrupted message :(
            Toast.makeText(getActivity(), R.string.err_attachment_corrupted,
                Toast.LENGTH_LONG).show();
        }
    }

    private void stopDownload(CompositeMessage msg) {
        AttachmentComponent attachment = msg.getComponent(AttachmentComponent.class);

        if (attachment != null && attachment.getFetchUrl() != null) {
            Intent i = new Intent(getActivity(), DownloadService.class);
            i.setAction(DownloadService.ACTION_DOWNLOAD_ABORT);
            i.setData(Uri.parse(attachment.getFetchUrl()));
            getActivity().startService(i);
        }
    }

    private void openFile(CompositeMessage msg) {
        AttachmentComponent attachment = msg.getComponent(AttachmentComponent.class);

        if (attachment != null) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(attachment.getLocalUri(), attachment.getMime());
            try {
                startActivity(i);
            }
            catch (ActivityNotFoundException e) {
                Toast.makeText(getActivity(), R.string.chooser_error_no_app,
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    private void chooseContact() {
        // TODO one day it will be like this
        // Intent i = new Intent(Intent.ACTION_PICK, Users.CONTENT_URI);
        Intent i = new Intent(getContext(), ContactsListActivity.class);
        startActivityForResult(i, REQUEST_INVITE_USERS);
    }

    boolean tryHideAttachmentView() {
        if (isAttachmentViewVisible()) {
            setupAttachmentViewCloseAnimation();
            startAttachmentViewAnimation();
            return true;
        }
        return false;
    }

    private void setupAttachmentViewCloseAnimation() {
        if (mAttachAnimator != null && !mAttachAnimator.isRunning()) {
            // reverse the animation
            mAttachAnimator = mAttachAnimator.reverse();
            mAttachAnimator.addListener(new SupportAnimator.AnimatorListener() {
                public void onAnimationCancel() {
                }

                public void onAnimationEnd() {
                    mAttachmentContainer.setVisibility(View.INVISIBLE);
                    mAttachAnimator = null;
                }

                public void onAnimationRepeat() {
                }

                public void onAnimationStart() {
                }
            });
        }
    }

    private boolean isAttachmentViewVisible() {
        return mAttachmentContainer.getVisibility() != View.INVISIBLE || mAttachAnimator != null;
    }

    private void startAttachmentViewAnimation() {
        mAttachAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        mAttachAnimator.setDuration(250);
        mAttachAnimator.start();
    }

    /** Show or hide the attachment selector. */
    public void toggleAttachmentView() {
        if (isAttachmentViewVisible()) {
            setupAttachmentViewCloseAnimation();
        }
        else {
            mComposer.forceHideKeyboard();
            mAttachmentContainer.setVisibility(View.VISIBLE);

            int right = mAttachmentCard.getRight();
            int top = mAttachmentCard.getTop();
            float f = (float) Math.sqrt(Math.pow(mAttachmentCard.getWidth(), 2D) + Math.pow(mAttachmentCard.getHeight(), 2D));
            mAttachAnimator = ViewAnimationUtils.createCircularReveal(mAttachmentCard, right, top, 0, f);
        }

        startAttachmentViewAnimation();
    }

    /** Starts an activity for shooting a picture. */
    private void selectPhotoAttachment() {
        try {
            // check if camera is available
            final PackageManager packageManager = getActivity().getPackageManager();
            final Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            List<ResolveInfo> list =
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (list.size() <= 0) throw new UnsupportedOperationException();

            mCurrentPhoto = MediaStorage.getOutgoingPhotoFile();
            Uri uri = Uri.fromFile(mCurrentPhoto);
            Intent take = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            take.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                take.setClipData(ClipData.newUri(getContext().getContentResolver(),
                    "Picture path", uri));
            }

            startActivityForResult(take, SELECT_ATTACHMENT_PHOTO);
        }
        catch (UnsupportedOperationException ue) {
            Toast.makeText(getActivity(), R.string.chooser_error_no_camera_app,
                Toast.LENGTH_LONG).show();
        }
        catch (IOException e) {
            Log.e(TAG, "error creating temp file", e);
            Toast.makeText(getActivity(), R.string.chooser_error_no_camera,
                Toast.LENGTH_LONG).show();
        }
    }

    /** Starts an activity for picture attachment selection. */
    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void selectGalleryAttachment() {
        boolean useSAF = MediaStorage.isStorageAccessFrameworkAvailable();
        Intent pictureIntent = createGalleryIntent(useSAF);

        try {
            startActivityForResult(pictureIntent, SELECT_ATTACHMENT_OPENABLE);
        }
        catch (ActivityNotFoundException e1) {
            try {
                if (useSAF) {
                    // try direct file system access
                    pictureIntent = createGalleryIntent(false);
                    startActivityForResult(pictureIntent, SELECT_ATTACHMENT_OPENABLE);
                }
                else {
                    // simulate error
                    throw new ActivityNotFoundException("gallery");
                }
            }
            catch (ActivityNotFoundException e2) {
                Toast.makeText(getActivity(), R.string.chooser_error_no_gallery_app,
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private Intent createGalleryIntent(boolean useSAF) {
        Intent intent;
        if (!useSAF) {
            intent = new Intent(Intent.ACTION_GET_CONTENT)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        }

        return intent
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    }

    /** Starts activity for a vCard attachment from a contact. */
    private void selectContactAttachment() {
        try {
            Intent i = new Intent(Intent.ACTION_PICK, Contacts.CONTENT_URI);
            startActivityForResult(i, SELECT_ATTACHMENT_CONTACT);
        }
        catch (ActivityNotFoundException e) {
            // no contacts app found (crap device eh?)
            Toast.makeText(getActivity(),
                R.string.err_no_contacts_app,
                Toast.LENGTH_LONG).show();
        }
    }

    private void selectAudioAttachment() {
        // create audio fragment if needed
        AudioFragment audio = getAudioFragment();
        // stop everything
        if (mAudioControl != null) {
            resetAudio(mAudioControl);
        }
        else {
            audio.resetPlayer();
            audio.setMessageId(-1);
        }
        // show dialog
        mAudioDialog = new AudioDialog(getActivity(), audio, this);
        mAudioDialog.show();
    }

    private AudioFragment getAudioFragment() {
        AudioFragment fragment = findAudioFragment();
        if (fragment == null) {
            FragmentActivity parent = getActivity();
            if (parent != null) {
                fragment = new AudioFragment();
                FragmentManager fm = parent.getSupportFragmentManager();
                fm.beginTransaction()
                    .add(fragment, "audio")
                    .commit();
                // commit immediately please
                fm.executePendingTransactions();
            }
        }

        return fragment;
    }

    private AudioFragment findAudioFragment() {
        FragmentManager fm = getFragmentManager();
        return fm != null ? (AudioFragment) fm
            .findFragmentByTag("audio") : null;
    }

    protected abstract void deleteConversation();

    private void deleteThread() {
        new MaterialDialog.Builder(getActivity())
            .content(R.string.confirm_will_delete_thread)
            .positiveText(android.R.string.ok)
            .positiveColorRes(R.color.button_danger)
            .negativeText(android.R.string.cancel)
            .onPositive(new MaterialDialog.SingleButtonCallback() {
                @Override
                public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                    deleteConversation();
                }
            })
            .show();
    }

    private void addUsers() {
        chooseContact();
    }

    protected abstract void addUsers(String[] members);

    private void retryMessage(CompositeMessage msg) {
        Intent i = new Intent(getActivity(), MessageCenterService.class);
        i.setAction(MessageCenterService.ACTION_RETRY);
        i.putExtra(MessageCenterService.EXTRA_MESSAGE, ContentUris.withAppendedId
                (Messages.CONTENT_URI, msg.getDatabaseId()));
        getActivity().startService(i);
    }

    private void scrollToPosition(int position) {
        getListView().setSelection(position);
    }

    private boolean isSearching() {
        Bundle args = myArguments();
        return args != null && args.getLong(ComposeMessage.EXTRA_MESSAGE, -1) >= 0;
    }

    protected synchronized void startQuery(boolean progress) {
        if (progress)
            getActivity().setProgressBarIndeterminateVisibility(true);

        Conversation.startQuery(mQueryHandler,
                CONVERSATION_QUERY_TOKEN, threadId);
        // message list query will be started by query handler
    }

    private void startMessagesQuery() {
        CompositeMessage.startQuery(mQueryHandler, MESSAGE_LIST_QUERY_TOKEN,
            threadId, isSearching() ? 0 : MESSAGE_PAGE_SIZE, 0);
    }

    void startMessagesQuery(long lastId) {
        CompositeMessage.startQuery(mQueryHandler, MESSAGE_PAGE_QUERY_TOKEN,
            threadId, isSearching() ? 0 : MESSAGE_PAGE_SIZE, lastId);
    }

    private void stopQuery() {
        hideHeaderView();
        if (mListAdapter != null)
            mListAdapter.changeCursor(null);

        if (mQueryHandler != null) {
            // be sure to cancel all queries
            mQueryHandler.abort();
        }
    }

    private void showMessageDetails(CompositeMessage msg) {
        MessageUtils.showMessageDetails(getActivity(), msg, getDecodedPeer(msg), getDecodedName(msg));
    }

    /** Returns the phone number of the message sender, if available. */
    protected abstract String getDecodedPeer(CompositeMessage msg);

    /** Returns the display name of the message sender, if available. */
    protected abstract String getDecodedName(CompositeMessage msg);

    private void shareMessage(CompositeMessage msg) {
        Intent i = null;
        AttachmentComponent attachment = msg.getComponent(AttachmentComponent.class);

        if (attachment != null) {
            i = ComposeMessage.sendMediaMessage(attachment.getLocalUri(),
                attachment.getMime());
        }

        else {
            TextComponent txt = msg.getComponent(TextComponent.class);

            if (txt != null)
                i = ComposeMessage.sendTextMessage(txt.getContent());
        }

        if (i != null)
            startActivity(i);
        else
            // TODO ehm...
            Log.w(TAG, "error sharing message");
    }

    protected void loadConversationMetadata(Uri uri) {
        threadId = ContentUris.parseId(uri);
        mConversation = Conversation.loadFromId(getActivity(), threadId);
        if (mConversation == null) {
            Log.w(TAG, "conversation for thread " + threadId + " not found!");
            startActivity(new Intent(getActivity(), ConversationsActivity.class));
            getActivity().finish();
        }
    }

    private Bundle myArguments() {
        return (mArguments != null) ? mArguments : getArguments();
    }

    public void setMyArguments(Bundle args) {
        mArguments = args;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        // image from storage/picture from camera
        // since there are like up to 3 different ways of doing this...
        if (requestCode == SELECT_ATTACHMENT_OPENABLE || requestCode == SELECT_ATTACHMENT_PHOTO) {
            if (resultCode == Activity.RESULT_OK) {
                Uri[] uris = null;
                String[] mimes = null;

                // returning from camera
                if (data == null) {
                    if (mCurrentPhoto != null) {
                        Uri uri = Uri.fromFile(mCurrentPhoto);
                        // notify media scanner
                        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        mediaScanIntent.setData(uri);
                        getActivity().sendBroadcast(mediaScanIntent);
                        mCurrentPhoto = null;

                        uris = new Uri[] { uri };
                    }
                }
                else {
                    if (mCurrentPhoto != null) {
                        mCurrentPhoto.delete();
                        mCurrentPhoto = null;
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN && data.getClipData() != null) {
                        ClipData cdata = data.getClipData();
                        uris = new Uri[cdata.getItemCount()];

                        for (int i = 0; i < uris.length; i++) {
                            ClipData.Item item = cdata.getItemAt(i);
                            uris[i] = item.getUri();
                        }
                    }
                    else {
                        uris = new Uri[] { data.getData() };
                        mimes = new String[] { data.getType() };
                    }

                    // SAF available, request persistable permissions
                    if (MediaStorage.isStorageAccessFrameworkAvailable() &&
                            requestCode == SELECT_ATTACHMENT_OPENABLE) {
                        for (Uri uri : uris) {
                            if (uri != null && !"file".equals(uri.getScheme())) {
                                MediaStorage.requestPersistablePermissions(getActivity(), uri);
                            }
                        }
                    }
                }

                for (int i = 0 ; uris != null && i < uris.length; i++) {
                    Uri uri = uris[i];
                    if (uri == null)
                        continue;

                    String mime = (mimes != null && mimes.length >= uris.length) ?
                        mimes[i] : null;

                    if (mime == null || mime.startsWith("*/")
                            || mime.endsWith("/*")) {
                        mime = MediaStorage.getType(getActivity(), uri);
                        Log.v(TAG, "using detected mime type " + mime);
                    }

                    if (ImageComponent.supportsMimeType(mime))
                        sendBinaryMessage(uri, mime, true, ImageComponent.class);
                    else if (VCardComponent.supportsMimeType(mime))
                        sendBinaryMessage(uri, VCardComponent.MIME_TYPE, false, VCardComponent.class);
                    else
                        Toast.makeText(getActivity(), R.string.send_mime_not_supported, Toast.LENGTH_LONG)
                            .show();
                }
            }
            // operation aborted
            else {
                // delete photo :)
                if (mCurrentPhoto != null) {
                    mCurrentPhoto.delete();
                    mCurrentPhoto = null;
                }
            }
        }
        // contact card (vCard)
        else if (requestCode == SELECT_ATTACHMENT_CONTACT) {
            if (resultCode == Activity.RESULT_OK) {
                Uri uri = data.getData();
                if (uri != null) {
                    Uri vcardUri = null;

                    // get lookup key
                    final Cursor c = getContext().getContentResolver()
                        .query(uri, new String[] { Contacts.LOOKUP_KEY }, null, null, null);
                    if (c != null) {
                        try {
                            if (c.moveToFirst()) {
                                String lookupKey = c.getString(0);
                                vcardUri = Uri.withAppendedPath(Contacts.CONTENT_VCARD_URI, lookupKey);
                            }
                        }
                        catch (Exception e) {
                            Log.w(TAG, "unable to lookup selected contact. Did you grant me the permission?", e);
                            ReportingManager.logException(e);
                        }
                        finally {
                            c.close();
                        }
                    }

                    if (vcardUri != null) {
                        sendBinaryMessage(vcardUri, VCardComponent.MIME_TYPE, false, VCardComponent.class);
                    }
                    else {
                        Toast.makeText(getContext(), R.string.err_no_contact,
                            Toast.LENGTH_LONG).show();
                    }
                }
            }
        }
        // invite user
        else if (requestCode == REQUEST_INVITE_USERS) {
            if (resultCode == Activity.RESULT_OK) {

                ArrayList<Uri> uris;
                Uri threadUri = data.getData();
                if (threadUri != null) {
                    String userId = threadUri.getLastPathSegment();
                    addUsers(new String[] { userId });
                }
                else if ((uris = data.getParcelableArrayListExtra("org.kontalk.contacts")) != null) {
                    String[] users = new String[uris.size()];
                    for (int i = 0; i < users.length; i++)
                        users[i] = uris.get(i).getLastPathSegment();
                    addUsers(users);
                }

            }
        }
    }

    @Override
    public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putParcelable(Uri.class.getName(), Threads.getUri(getUserId()));
        // save composer status
        if (mComposer != null)
            mComposer.onSaveInstanceState(out);
        // current photo being shot
        if (mCurrentPhoto != null) {
            out.putString("currentPhoto", mCurrentPhoto.toString());
        }
        // audio dialog open
        if (mAudioDialog != null) {
            mAudioDialog.onSaveInstanceState(out);
        }
        // audio player stuff
        out.putInt("mediaPlayerStatus", mMediaPlayerStatus);
    }

    /** Handles ACTION_VIEW intents. */
    protected abstract void handleActionView(Uri uri);

    /** Handles ACTION_VIEW_USERID intents: providing the user ID/JID. */
    protected abstract void handleActionViewConversation(Uri uri, Bundle args);

    private void processArguments(Bundle savedInstanceState) {
        Bundle args;
        if (savedInstanceState != null) {
            Uri uri = savedInstanceState.getParcelable(Uri.class.getName());
            // threadId = ContentUris.parseId(uri);
            args = new Bundle();
            args.putString("action", ComposeMessage.ACTION_VIEW_USERID);
            args.putParcelable("data", uri);

            String currentPhoto = savedInstanceState.getString("currentPhoto");
            if (currentPhoto != null) {
                mCurrentPhoto = new File(currentPhoto);
            }

            // audio playing
            setAudioStatus(savedInstanceState.getInt("mediaPlayerStatus", AudioContentView.STATUS_IDLE));

            // audio dialog stuff
            mAudioDialog = AudioDialog.onRestoreInstanceState(getActivity(),
                savedInstanceState, getAudioFragment(), this);
            if (mAudioDialog != null) {
                Log.d(TAG, "recreating audio dialog");
                mAudioDialog.show();
            }
        }
        else {
            args = myArguments();
        }

        if (args != null && args.size() > 0) {
            final String action = args.getString("action");

            // view intent
            if (Intent.ACTION_VIEW.equals(action)) {
                Uri uri = args.getParcelable("data");
                handleActionView(uri);
            }

            // view conversation - just threadId provided
            else if (ComposeMessage.ACTION_VIEW_CONVERSATION.equals(action)) {
                Uri uri = args.getParcelable("data");
                loadConversationMetadata(uri);
            }

            // view conversation - just userId provided
            else if (ComposeMessage.ACTION_VIEW_USERID.equals(action)) {
                Uri uri = args.getParcelable("data");
                handleActionViewConversation(uri, args);
            }
        }

        // set title if we are autonomous
        if (mArguments != null) {
            String title = mUserName;
            //if (mUserPhone != null) title += " <" + mUserPhone + ">";
            setActivityTitle(title, "");
        }

        // update conversation stuff
        if (mConversation != null)
            onConversationCreated();

        onArgumentsProcessed();
    }

    protected abstract void onArgumentsProcessed();

    public void setActivityTitle(CharSequence title, CharSequence status) {
        if (mStatusText != null) {
            // tablet UI - ignore title
            mStatusText.setText(status);
        }
        else {
            ComposeMessageParent parent = (ComposeMessageParent) getActivity();
            parent.setTitle(title, status);
        }
    }

    public void setActivityStatusUpdating() {
        if (mStatusText != null) {
            CharSequence text = mStatusText.getText();
            if (text != null && text.length() > 0) {
                mStatusText.setText(ComposeMessage.applyUpdatingStyle(text));
            }
        }
        else {
            ComposeMessageParent parent = (ComposeMessageParent) getActivity();
            parent.setUpdatingSubtitle();
        }
    }

    public ComposeMessage getParentActivity() {
        Activity _activity = getActivity();
        return (_activity instanceof ComposeMessage) ? (ComposeMessage) _activity
                : null;
    }

    private void processStart(boolean resuming) {
        ComposeMessage activity = getParentActivity();
        // opening for contact picker - do nothing
        if (threadId < 0 && activity != null
                && activity.getSendIntent() != null)
            return;

        if (mListAdapter == null) {
            Pattern highlight = null;
            Bundle args = myArguments();
            if (args != null) {
                String highlightString = args
                        .getString(ComposeMessage.EXTRA_HIGHLIGHT);
                highlight = (highlightString == null) ? null : Pattern.compile(
                        "\\b" + Pattern.quote(highlightString),
                        Pattern.CASE_INSENSITIVE);
            }

            mListAdapter = new MessageListAdapter(getActivity(), null,
                    highlight, getListView(), this);
            mListAdapter.setOnContentChangedListener(mContentChangedListener);
            setListAdapter(mListAdapter);
        }

        if (threadId > 0) {
            // always reload conversation
            startQuery(resuming);
        }
        else {
            // HACK this is for crappy honeycomb :)
            getActivity().setProgressBarIndeterminateVisibility(false);

            mConversation = Conversation.createNew(getActivity());
            mConversation.setRecipient(getUserId());
            onConversationCreated();
        }
    }

    /** Called when the {@link Conversation} object has been created. */
    protected void onConversationCreated() {
        // restore any draft
        mComposer.restoreText(mConversation.getDraft());

        if (mConversation.getThreadId() > 0 && mConversation.getUnreadCount() > 0) {
            /*
             * FIXME this has the usual issue about resuming while screen is
             * still locked, having focus and so on...
             * See issue #28.
             */
            Log.v(TAG, "marking thread as read");
            mConversation.markAsRead();
        }
        else {
            // new conversation -- observe peer Uri
            registerPeerObserver();
        }

        // subscribe to presence notifications
        subscribePresence();

        updateUI();
    }

    /** Called when a presence is received. */
    protected abstract void onPresence(String jid, Presence.Type type,
        boolean removed, Presence.Mode mode, String fingerprint);

    protected abstract void onConnected();

    /** Called when the roster has been loaded (ACTION_ROSTER). */
    protected abstract void onRosterLoaded();

    /** Called when the contact starts typing. */
    protected abstract void onStartTyping(String jid);

    /** Called when the contact stops typing. */
    protected abstract void onStopTyping(String jid);

    /** Should return true if the contact is a user ID in the current context. */
    protected abstract boolean isUserId(String jid);

    private void subscribePresence() {
        // TODO this needs serious refactoring
        if (mPresenceReceiver == null) {
            mPresenceReceiver = new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    // activity is terminating
                    if (getContext() == null)
                        return;

                    String action = intent.getAction();

                    if (MessageCenterService.ACTION_PRESENCE.equals(action)) {
                        String from = intent.getStringExtra(MessageCenterService.EXTRA_FROM);
                        String bareFrom = from != null ? XmppStringUtils.parseBareJid(from) : null;

                        // we are receiving a presence from our peer
                        if (from != null && isUserId(bareFrom)) {

                            // we handle only (un)available presence stanzas
                            String type = intent.getStringExtra(MessageCenterService.EXTRA_TYPE);
                            Presence.Type presenceType = (type != null) ? Presence.Type.fromString(type) : null;

                            String mode = intent.getStringExtra(MessageCenterService.EXTRA_SHOW);
                            Presence.Mode presenceMode = (mode != null) ? Presence.Mode.fromString(mode) : null;

                            String fingerprint = intent.getStringExtra(MessageCenterService.EXTRA_FINGERPRINT);

                            boolean removed = false;
                            if (presenceType == Presence.Type.available) {
                                mAvailableResources.add(from);
                            }
                            else if (presenceType == Presence.Type.unavailable) {
                                removed = mAvailableResources.remove(from);
                            }

                            onPresence(from, presenceType, removed, presenceMode, fingerprint);
                        }
                    }

                    else if (MessageCenterService.ACTION_CONNECTED.equals(action)) {
                        // reset compose sent flag
                        mComposer.resetCompose();
                        // reset available resources list
                        mAvailableResources.clear();

                        onConnected();
                    }

                    else if (MessageCenterService.ACTION_ROSTER_LOADED.equals(action)) {
                        onRosterLoaded();
                    }

                    else if (MessageCenterService.ACTION_MESSAGE.equals(action)) {
                        String from = intent.getStringExtra(MessageCenterService.EXTRA_FROM);
                        String chatState = intent.getStringExtra("org.kontalk.message.chatState");

                        // we are receiving a composing notification from our peer
                        if (from != null && isUserId(from)) {
                            if (chatState != null && ChatState.composing.toString().equals(chatState)) {
                                onStartTyping(from);
                            }
                            else {
                                onStopTyping(from);
                            }
                        }
                    }

                }
            };

            // listen for user presence, connection and incoming messages
            IntentFilter filter = new IntentFilter();
            filter.addAction(MessageCenterService.ACTION_PRESENCE);
            filter.addAction(MessageCenterService.ACTION_CONNECTED);
            filter.addAction(MessageCenterService.ACTION_ROSTER_LOADED);
            filter.addAction(MessageCenterService.ACTION_MESSAGE);

            mLocalBroadcastManager.registerReceiver(mPresenceReceiver, filter);

            // request connection and roster load status
            Context ctx = getActivity();
            if (ctx != null) {
                MessageCenterService.requestConnectionStatus(ctx);
                MessageCenterService.requestRosterStatus(ctx);
            }
        }
    }

    private void unsubscribePresence() {
        if (mPresenceReceiver != null) {
            mLocalBroadcastManager.unregisterReceiver(mPresenceReceiver);
            mPresenceReceiver = null;
        }
    }

    protected boolean isWarningVisible(WarningType type) {
        Snackbar bar = SnackbarManager.getCurrentSnackbar();
        if (bar != null) {
            WarningType oldType = (WarningType) bar.getTag();
            if (oldType != null && oldType == type)
                return true;
        }
        return false;
    }

    protected void hideWarning() {
        SnackbarManager.dismiss();
    }

    protected void showWarning(CharSequence text, final View.OnClickListener listener, WarningType type) {
        View view = getView();
        Activity context = getActivity();
        if (view == null || context == null)
            return;

        Snackbar bar = SnackbarManager.getCurrentSnackbar();
        if (bar != null) {
            WarningType oldType = (WarningType) bar.getTag();
            if (oldType != null && oldType.getValue() > type.getValue())
                return;

            bar.dismiss();
        }

        bar = Snackbar.with(context)
            .type(SnackbarType.MULTI_LINE)
            .text(text)
            .duration(Snackbar.SnackbarDuration.LENGTH_INDEFINITE)
            .dismissOnActionClicked(false)
            .allowMultipleActionClicks(true);

        if (listener != null) {
            bar.swipeToDismiss(false)
                .actionLabel(R.string.warning_button_details)
                .actionListener(new ActionClickListener() {
                    @Override
                    public void onActionClicked(Snackbar snackbar) {
                        listener.onClick(null);
                    }
                });
        }
        else {
            bar.swipeToDismiss(true)
                .animation(false);
        }

        int colorId = 0;
        int textColorId = 0;
        switch (type) {
            case FATAL:
                textColorId = R.color.warning_bar_text_fatal;
                colorId = R.color.warning_bar_background_fatal;
                break;
            case WARNING:
                textColorId = R.color.warning_bar_text_warning;
                colorId = R.color.warning_bar_background_warning;
                break;
        }

        bar.setTag(type);
        bar.color(ContextCompat.getColor(context, colorId))
            .textColor(ContextCompat.getColor(context, textColorId));

        if (listener != null) {
            SnackbarManager.show(bar);
        }
        else {
            SnackbarManager.show(bar, (ViewGroup) view.findViewById(R.id.warning_bar));
        }
    }

    protected void setStatusText(CharSequence text) {
        ComposeMessageParent parent = (ComposeMessageParent) getActivity();
        if (parent instanceof ComposeMessage)
            setActivityTitle(null, text);
        else {
            if (mStatusText != null)
                mStatusText.setText(text);
        }
    }

    private synchronized void registerPeerObserver() {
        if (mPeerObserver == null) {
            Uri uri = Threads.getUri(mConversation.getRecipient());
            mPeerObserver = new PeerObserver(getActivity(), mQueryHandler);
            getActivity().getContentResolver().registerContentObserver(uri,
                    false, mPeerObserver);
        }
    }

    private synchronized void unregisterPeerObserver() {
        if (mPeerObserver != null) {
            Context context = mPeerObserver.mContext;
            context.getContentResolver().unregisterContentObserver(mPeerObserver);
            mPeerObserver = null;
        }
    }

    private final class PeerObserver extends ContentObserver {
        private final Context mContext;

        public PeerObserver(Context context, Handler handler) {
            super(handler);
            mContext = context;
        }

        @Override
        public void onChange(boolean selfChange) {
            Conversation conv = Conversation.loadFromUserId(mContext, getUserId());

            if (conv != null) {
                mConversation = conv;
                threadId = mConversation.getThreadId();

                // auto-unregister
                unregisterPeerObserver();
            }

            // fire cursor update
            Log.v(TAG, "peer observer active");
            processStart(false);
        }

        @Override
        public boolean deliverSelfNotifications() {
            return false;
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (Authenticator.getDefaultAccount(getActivity()) == null) {
            NumberValidation.start(getActivity());
            getActivity().finish();
            return;
        }

        // hold message center
        MessageCenterService.hold(getActivity(), true);

        ComposeMessage activity = getParentActivity();
        if (activity == null || !activity.hasLostFocus() || activity.hasWindowFocus()) {
            onFocus(true);
        }
    }

    public void onFocus(boolean resuming) {
        // resume content watcher
        resumeContentListener();

        // set notifications on pause
        MessagingNotification.setPaused(getUserId());

        // we are updating the status now
        setActivityStatusUpdating();

        // cursor was previously destroyed -- reload everything
        processStart(resuming);
    }

    @Override
    public void onPause() {
        super.onPause();

        // unsubcribe presence notifications
        unsubscribePresence();

        // notify composer bar
        mComposer.onPause();

        // hide emoji drawer
        tryHideEmojiDrawer();

        // pause content watcher
        pauseContentListener();

        // notify parent of pausing
        ComposeMessage parent = getParentActivity();
        if (parent != null)
            parent.fragmentLostFocus();

        CharSequence text = mComposer.getText();
        int len = text.length();

        // resume notifications
        MessagingNotification.setPaused(null);

        // save last message as draft
        if (threadId > 0) {

            // no draft and no messages - delete conversation
            if (len == 0 && mConversation.getMessageCount() == 0 &&
                    mConversation.getRequestStatus() != Threads.REQUEST_WAITING &&
                    !mConversation.isGroupChat()) {

                mConversation.delete(false);
            }

            // update draft
            else {
                try {
                    MessagesProviderUtils.updateDraft(getContext(), threadId, text.toString());
                }
                catch (SQLiteDiskIOException e) {
                    // TODO warn user
                    Log.w(TAG, "error saving draft", e);
                    len = 0;
                }
            }
        }

        // new thread, create empty conversation
        else {
            if (len > 0) {
                // save to local storage
                try {
                    MessagesProviderUtils.insertEmptyThread(getActivity(), getUserId(), text.toString());
                }
                catch (SQLiteDiskIOException e) {
                    // TODO warn user
                    Log.w(TAG, "error saving draft", e);
                    len = 0;
                }
            }
        }

        if (len > 0) {
            Toast.makeText(getActivity(), R.string.msg_draft_saved,
                    Toast.LENGTH_LONG).show();
        }

        if (mComposer.isComposeSent()) {
            // send inactive state notification
            sendInactive();
            mComposer.resetCompose();
        }

        // release message center
        MessageCenterService.release(getActivity());

        // release audio player
        AudioFragment audio = findAudioFragment();
        if (audio != null) {
            stopMediaPlayerUpdater();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                if (!getActivity().isChangingConfigurations()) {
                    audio.setMessageId(-1);
                    audio.finish(true);
                }
            }
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        unregisterPeerObserver();
        stopQuery();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mComposer != null) {
            mComposer.onDestroy();
        }
        if (mAudioDialog != null) {
            mAudioDialog.dismiss();
            mAudioDialog = null;
        }
    }

    private void pauseContentListener() {
        if (mListAdapter != null)
            mListAdapter.setOnContentChangedListener(null);
    }

    private void resumeContentListener() {
        if (mListAdapter != null)
            mListAdapter.setOnContentChangedListener(mContentChangedListener);
    }

    public final boolean isFinishing() {
        Activity activity = getActivity();
        return (activity == null || activity.isFinishing()) || isRemoving();
    }

    private void showHeaderView() {
        mHeaderView.setVisibility(View.VISIBLE);
    }

    private void hideHeaderView() {
        mHeaderView.setVisibility(View.GONE);
    }

    void enableHeaderView(boolean enabled) {
        mNextPageButton.setEnabled(enabled);
    }

    protected void updateUI() {
        boolean threadEnabled = (threadId > 0);

        if (mDeleteThreadMenu != null) {
            mDeleteThreadMenu.setEnabled(threadEnabled);
        }
    }

    boolean tryHideEmojiDrawer() {
        if (mComposer.isEmojiVisible()) {
            mComposer.hideEmojiDrawer(false);
            return true;
        }
        return false;
    }

    public Conversation getConversation() {
        return mConversation;
    }

    public Contact getContact() {
        return (mConversation != null) ? mConversation.getContact() : null;
    }

    public long getThreadId() {
        return threadId;
    }

    protected void setThreadId(long threadId) {
        this.threadId = threadId;
    }

    /** Returns the user id of this conversation. */
    public abstract String getUserId();

    public void setTextEntry(CharSequence text) {
        mComposer.setText(text);
    }

    @Override
    public boolean onLongClick(View v) {
        // this seems to be necessary...
        return false;
    }

    public void closeConversation() {
        // main activity
        if (getParentActivity() != null) {
            getActivity().finish();
        }
        // using fragments...
        else {
            ConversationsActivity activity = (ConversationsActivity) getActivity();
            activity.getListFragment().endConversation(this);
        }
    }

    private void offlineModeWarning() {
        if (Preferences.getOfflineMode(getActivity()) && !mOfflineModeWarned) {
            mOfflineModeWarned = true;
            Toast.makeText(getActivity(), R.string.warning_offline_mode,
                Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void textChanged(CharSequence text) {
        Snackbar bar = SnackbarManager.getCurrentSnackbar();
        if (bar != null) {
            WarningType type = (WarningType) bar.getTag();
            if (type != null && type.getValue() < WarningType.FATAL.getValue()) {
                bar.dismiss();
            }
        }
    }

    @Override
    public void onRecordingSuccessful(File file) {
        if (file != null)
            sendBinaryMessage(Uri.fromFile(file), AudioDialog.DEFAULT_MIME, false, AudioComponent.class);
    }

    @Override
    public void onRecordingCancel() {
        mAudioDialog = null;
    }

    @Override
    public void buttonClick(File audioFile, AudioContentViewControl view, long messageId) {
        AudioFragment audio = getAudioFragment();
        if (audio.getMessageId() == messageId) {
            switch (mMediaPlayerStatus) {
                case AudioContentView.STATUS_PLAYING:
                    pauseAudio(view);
                    break;
                case AudioContentView.STATUS_PAUSED:
                case AudioContentView.STATUS_ENDED:
                    playAudio(view, messageId);
                    break;

            }
        }
        else {
            switch (mMediaPlayerStatus) {
                case AudioContentView.STATUS_IDLE:
                    if (prepareAudio(audioFile, view, messageId))
                        playAudio(view, messageId);
                    break;
                case AudioContentView.STATUS_ENDED:
                case AudioContentView.STATUS_PLAYING:
                case AudioContentView.STATUS_PAUSED:
                    resetAudio(mAudioControl);
                    if (prepareAudio(audioFile, view, messageId))
                        playAudio(view, messageId);
                    break;
            }
        }
    }

    private boolean prepareAudio(File audioFile, final AudioContentViewControl view, final long messageId) {
        stopMediaPlayerUpdater();
        try {
            AudioFragment audio = getAudioFragment();
            final MediaPlayer player = audio.getPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(audioFile.getAbsolutePath());
            player.prepare();

            // prepare was successful
            audio.setMessageId(messageId);
            mAudioControl = view;

            view.prepare(player.getDuration());
            player.seekTo(view.getPosition());
            view.setProgressChangeListener(true);
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    stopMediaPlayerUpdater();
                    view.end();
                    AudioFragment audio = findAudioFragment();
                    if (audio != null)
                        audio.seekPlayerTo(0);
                    setAudioStatus(AudioContentView.STATUS_ENDED);
                }
            });
            return true;
        }
        catch (IOException e) {
            Toast.makeText(getActivity(), R.string.err_file_not_found, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    @Override
    public void playAudio(AudioContentViewControl view, long messageId) {
        view.play();
        findAudioFragment().getPlayer().start();
        setAudioStatus(AudioContentView.STATUS_PLAYING);
        startMediaPlayerUpdater(view);
    }

    private void updatePosition(AudioContentViewControl view) {
        view.updatePosition(findAudioFragment().getPlayer().getCurrentPosition());
    }

    @Override
    public void pauseAudio(AudioContentViewControl view) {
        view.pause();
        findAudioFragment().getPlayer().pause();
        stopMediaPlayerUpdater();
        setAudioStatus(AudioContentView.STATUS_PAUSED);
    }

    private void resetAudio(AudioContentViewControl view) {
        if (view != null) {
            stopMediaPlayerUpdater();
            view.end();
        }
        AudioFragment audio = findAudioFragment();
        if (audio != null) {
            audio.resetPlayer();
            audio.setMessageId(-1);
        }
    }

    private void setAudioStatus(int audioStatus) {
        mMediaPlayerStatus = audioStatus;
    }

    @Override
    public void stopAllSounds() {
        resetAudio(mAudioControl);
    }

    @Override
    public void onBind(long messageId, final AudioContentViewControl view) {
        final AudioFragment audio = findAudioFragment();
        if (audio != null && audio.getMessageId() == messageId) {
            mAudioControl = view;
            audio.getPlayer().setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    stopMediaPlayerUpdater();
                    view.end();
                    audio.seekPlayerTo(0);
                    setAudioStatus(AudioContentView.STATUS_ENDED);
                }
            });

            view.setProgressChangeListener(true);
            view.prepare(audio.getPlayer().getDuration());
            if (audio.isPlaying()) {
                startMediaPlayerUpdater(view);
                view.play();
            }
            else {
                view.pause();
            }
        }
    }

    @Override
    public void onUnbind(long messageId, AudioContentViewControl view) {
        AudioFragment audio = findAudioFragment();
        if (audio != null && audio.getMessageId() == messageId) {
            mAudioControl = null;
            MediaPlayer player = audio.getPlayer();
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    getAudioFragment().seekPlayerTo(0);
                    setAudioStatus(AudioContentView.STATUS_ENDED);
                }
            });

            view.setProgressChangeListener(false);
            if (!MessagesProviderUtils.exists(getActivity(), messageId)) {
                resetAudio(view);
            }

            else {
                stopMediaPlayerUpdater();
            }
        }
    }

    @Override
    public boolean isPlaying() {
        AudioFragment audio = findAudioFragment();
        return audio != null && audio.isPlaying();
    }

    @Override
    public void seekTo(int position) {
        AudioFragment audio = findAudioFragment();
        if (audio != null)
            audio.seekPlayerTo(position);
    }

    private void startMediaPlayerUpdater(final AudioContentViewControl view) {
        updatePosition(view);
        mMediaPlayerUpdater = new Runnable() {
            @Override
            public void run() {
                updatePosition(view);
                mHandler.postDelayed(this, 100);
            }
        };
        mHandler.postDelayed(mMediaPlayerUpdater, 100);
    }

    private void stopMediaPlayerUpdater() {
        if (mMediaPlayerUpdater != null) {
            mHandler.removeCallbacks(mMediaPlayerUpdater);
            mMediaPlayerUpdater = null;
        }
    }

    /** The conversation list query handler. */
    private static final class MessageListQueryHandler extends AsyncQueryHandler {
        private WeakReference<AbstractComposeFragment> mParent;
        private boolean mCancel;
        private long mLastId;

        public MessageListQueryHandler(AbstractComposeFragment parent) {
            super(parent.getActivity().getApplicationContext().getContentResolver());
            mParent = new WeakReference<>(parent);
        }

        @Override
        public synchronized void startQuery(int token, Object cookie, Uri uri, String[] projection, String selection, String[] selectionArgs, String orderBy) {
            mCancel = false;
            super.startQuery(token, cookie, uri, projection, selection, selectionArgs, orderBy);
        }

        @Override
        protected synchronized void onQueryComplete(int token, Object cookie, Cursor cursor) {
            final AbstractComposeFragment parent = mParent.get();
            if (parent == null || cursor == null || parent.isFinishing() || mCancel) {
                // close cursor - if any
                if (cursor != null)
                    cursor.close();

                mCancel = false;
                if (parent != null) {
                    parent.unregisterPeerObserver();
                    parent.mListAdapter.changeCursor(null);
                }
                return;
            }

            switch (token) {
                case MESSAGE_LIST_QUERY_TOKEN:

                    // no messages to show - exit
                    if (cursor.getCount() == 0
                        && (parent.mConversation == null ||
                        // no draft
                        (parent.mConversation.getDraft() == null &&
                            // no subscription request
                            parent.mConversation.getRequestStatus() != Threads.REQUEST_WAITING &&
                            // no text in compose entry
                            parent.mComposer.getText().length() == 0 &&
                            // no group chat
                            !parent.mConversation.isGroupChat()))) {

                        Log.i(TAG, "no data to view - exit");

                        // close conversation
                        parent.closeConversation();

                    }
                    else {
                        // first query - use last id of this new cursor
                        if (cursor.getCount() > 0) {
                            cursor.moveToFirst();
                            mLastId = Conversation.getMessageId(cursor);
                        }

                        // save reloading status for next time
                        Bundle args = parent.myArguments();

                        // see if we have to scroll to a specific message
                        int newSelectionPos = -1;

                        if (args != null && !args.getBoolean(ComposeMessage.EXTRA_RELOADING)) {
                            long msgId = args.getLong(ComposeMessage.EXTRA_MESSAGE, -1);
                            if (msgId > 0) {
                                cursor.moveToPosition(-1);
                                while (cursor.moveToNext()) {
                                    long curId = cursor.getLong(CompositeMessage.COLUMN_ID);
                                    if (curId == msgId) {
                                        newSelectionPos = cursor.getPosition();
                                        break;
                                    }
                                }
                            }

                            args.putBoolean(ComposeMessage.EXTRA_RELOADING, true);
                        }

                        parent.mListAdapter.changeCursor(cursor);
                        if (newSelectionPos >= 0) {
                            // +1 is for the header view
                            final int pos = newSelectionPos + 1;
                            parent.getListView().post(new Runnable() {
                                @Override
                                public void run() {
                                    parent.scrollToPosition(pos);
                                }
                            });
                        }

                        if (newSelectionPos < 0 && cursor.getCount() >= MESSAGE_PAGE_SIZE)
                            parent.showHeaderView();

                        parent.getActivity().setProgressBarIndeterminateVisibility(false);
                        parent.updateUI();
                    }

                    break;

                case MESSAGE_PAGE_QUERY_TOKEN:
                    if (cursor.getCount() > 0) {
                        int newSelectionPos = -1;

                        // there is no more data after this page
                        if (cursor.getCount() < MESSAGE_PAGE_SIZE)
                            parent.hideHeaderView();

                        // save last id of this new cursor
                        cursor.moveToFirst();
                        mLastId = Conversation.getMessageId(cursor);

                        // join with the old cursor (if any)
                        Cursor oldCursor = parent.mListAdapter.getCursor();
                        if (oldCursor != null) {
                            // the new selection will be the next item after this new cursor
                            newSelectionPos = cursor.getCount();
                            cursor = new MergeCursor(new Cursor[]{cursor, oldCursor});
                        }

                        parent.mListAdapter.swapCursor(cursor);
                        if (newSelectionPos >= 0)
                            parent.getListView().setSelection(newSelectionPos);

                        parent.getActivity().setProgressBarIndeterminateVisibility(false);
                        parent.updateUI();
                    }
                    else {
                        // this happens when the first page is exactly PAGE_SIZE big
                        parent.hideHeaderView();
                    }

                    parent.enableHeaderView(true);
                    break;

                case CONVERSATION_QUERY_TOKEN:
                    if (cursor.moveToFirst()) {
                        parent.mConversation = Conversation.createFromCursor(
                            parent.getActivity(), cursor);
                        parent.onConversationCreated();
                    }

                    cursor.close();

                    parent.startMessagesQuery();
                    break;

                default:
                    Log.e(TAG, "onQueryComplete called with unknown token " + token);
            }

        }

        public synchronized void abort() {
            mCancel = true;
            mLastId = 0;
            cancelOperation(MESSAGE_LIST_QUERY_TOKEN);
            cancelOperation(CONVERSATION_QUERY_TOKEN);
            cancelOperation(MESSAGE_PAGE_QUERY_TOKEN);
        }

        public long getLastId() {
            return mLastId;
        }

    }

}
